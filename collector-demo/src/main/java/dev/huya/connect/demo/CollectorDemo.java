package dev.huya.connect.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.huya.connect.sdk.HuyaClient;
import dev.huya.connect.sdk.HuyaStatus;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Long-running two-room application using only the public SDK API. */
public final class CollectorDemo implements AutoCloseable {
    static final long MIN_FREE_BYTES = 512L * 1024 * 1024;
    record Target(String room, String uid, String label) { }
    static final List<Target> TARGETS = List.of(new Target("1995", "2272316519", "1995"),
            new Target("527988", "1199568157971", "童锦程【798】"));
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root, runDirectory;
    private final String runId = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
            + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final Instant startedAt = Instant.now();
    private final Map<String, HuyaClient> clients = new LinkedHashMap<>();
    private final Map<String, RoomHealth> trackers = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private volatile String stopReason;
    private volatile String fatalError;
    private long statusWriteFailures;
    private Instant lastStatusWriteFailureAt;
    private boolean statusWriteBlocked;

    CollectorDemo(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        lockChannel = FileChannel.open(this.root.resolve("collector.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Collector directory is already locked");
        } catch (IOException | RuntimeException e) { lockChannel.close(); throw e; }
        lock = acquired;
        runDirectory = this.root.resolve("runs").resolve(runId);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].equals("--data-dir")) throw new IllegalArgumentException("--data-dir DIRECTORY required");
        try (CollectorDemo demo = new CollectorDemo(Path.of(args[1]))) {
            Thread hook = new Thread(() -> {
                if (demo.stopReason == null && demo.fatalError == null) demo.stopReason = "shutdown_signal";
                demo.close();
            }, "collector-demo-shutdown");
            Runtime.getRuntime().addShutdownHook(hook);
            try { demo.run(); }
            finally { Runtime.getRuntime().removeShutdownHook(hook); }
        }
    }
    private void run() throws Exception {
        try {
            Files.createDirectories(runDirectory);
            for (Target target : TARGETS) {
                RoomHealth tracker = new RoomHealth(); trackers.put(target.room, tracker);
                HuyaClient client = HuyaClient.builder(target.room).presenterUid(target.uid).listener(tracker)
                        .recording(runDirectory.resolve(target.room)).build();
                clients.put(target.room, client); client.start();
            }
            while (!closed.get()) {
                if (Files.exists(root.resolve("STOP"))) { stopReason = "user_requested"; break; }
                long free = Files.getFileStore(root).getUsableSpace();
                if (free < MIN_FREE_BYTES) throw new IOException("Insufficient disk space; raw data retained");
                for (HuyaClient client : clients.values()) {
                    if (client.status().state() == HuyaStatus.State.FAILED)
                        throw new IOException("SDK client failed for room " + client.status().roomId());
                }
                publish(false);
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            fatalError = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("Collector failed: " + fatalError);
            throw e;
        } finally { close(); }
    }
    synchronized void publish(boolean terminal) throws IOException {
        Instant now = Instant.now();
        Map<String, Object> rooms = new LinkedHashMap<>(); boolean allHealthy = clients.size() == TARGETS.size();
        for (Target target : TARGETS) {
            HuyaClient client = clients.get(target.room);
            if (client == null) { allHealthy = false; continue; }
            Map<String, Object> room = trackers.get(target.room).snapshot(client.status(), now);
            room.put("label", target.label); room.put("recordingDirectory", runDirectory.resolve(target.room).toString());
            rooms.put(target.room, room); allHealthy &= Boolean.TRUE.equals(room.get("healthy"));
        }
        long free = Files.getFileStore(root).getUsableSpace();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("version", 1); status.put("application", "huya-sdk-collector-demo"); status.put("runId", runId);
        status.put("pid", ProcessHandle.current().pid());
        status.put("processStartedAt", ProcessHandle.current().info().startInstant().orElse(startedAt).toString());
        status.put("startedAt", startedAt.toString()); status.put("updatedAt", now.toString());
        status.put("dataDirectory", root.toString()); status.put("runDirectory", runDirectory.toString());
        status.put("stopReason", stopReason); status.put("fatalError", fatalError);
        status.put("statusWriteFailures", statusWriteFailures);
        status.put("lastStatusWriteFailureAt", lastStatusWriteFailureAt == null ? null : lastStatusWriteFailureAt.toString());
        status.put("freeBytes", free); status.put("diskWarning", free < 2L * 1024 * 1024 * 1024);
        status.put("phase", terminal ? fatalError == null ? "stopped" : "failed" : allHealthy ? "running" : "starting_or_recovering");
        status.put("healthy", !terminal && allHealthy && free >= MIN_FREE_BYTES); status.put("rooms", rooms);
        try {
            atomicJson(root.resolve("status.json"), status);
            if (statusWriteBlocked) System.err.println("Status publication recovered; capture continued");
            statusWriteBlocked = false;
        } catch (AccessDeniedException e) {
            // Windows readers without delete sharing can block replacement; metadata must not close raw recording.
            statusWriteFailures++; lastStatusWriteFailureAt = now;
            if (!statusWriteBlocked) System.err.println("Status publication blocked (AccessDeniedException); capture continues; stale status requires attention");
            statusWriteBlocked = true;
        }
    }
    static void atomicJson(Path path, Object value) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException e) {
                if (attempt == 10) throw e;
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while publishing status");
                }
            }
        }
    }
    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (HuyaClient client : clients.values()) client.close();
        try { publish(true); }
        catch (IOException e) { System.err.println("Cannot save final status: " + e.getClass().getSimpleName()); }
        try { lock.release(); } catch (IOException e) { System.err.println("Cannot release collector lock"); }
        try { lockChannel.close(); } catch (IOException e) { System.err.println("Cannot close collector lock"); }
    }
}

package dev.huya.connect.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Append-only session recorder. The directory must be empty at creation. */
public final class Recording implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.databind.module.SimpleModule()
            .addSerializer(java.time.Instant.class, com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));
    private final Path directory;
    private final long segmentLimit;
    private final BufferedWriter events;
    private BufferedWriter raw;
    private int segment;
    private long size;
    private boolean closed;

    public Recording(Path directory) throws IOException { this(directory, 64L * 1024 * 1024); }
    public Recording(Path directory, long segmentLimit) throws IOException {
        if (segmentLimit <= 0) throw new IllegalArgumentException("Segment limit must be positive");
        Files.createDirectories(directory);
        try (var paths = Files.list(directory)) {
            if (paths.findAny().isPresent()) throw new IOException("Recording directory must be empty");
        }
        this.directory = directory; this.segmentLimit = segmentLimit;
        events = Files.newBufferedWriter(directory.resolve("events.ndjson"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try { open(); } catch (IOException e) { events.close(); throw e; }
    }
    private void open() throws IOException {
        raw = Files.newBufferedWriter(directory.resolve(String.format("raw-%04d.ndjson", ++segment)),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        size = 0;
    }
    public synchronized void recordRaw(RawFrame frame) throws IOException {
        ObjectNode row = json.valueToTree(frame);
        byte[] payload = frame.payload();
        row.put("byteLength", payload.length); row.put("sha256", sha256(payload)); row.put("formatVersion", 1);
        String line = json.writeValueAsString(row) + "\n";
        int bytes = line.getBytes(StandardCharsets.UTF_8).length;
        if (size > 0 && size + bytes > segmentLimit) { raw.close(); open(); }
        raw.write(line); raw.flush(); size += bytes;
    }
    public synchronized void recordEvent(HuyaEvent event) throws IOException {
        ObjectNode row = json.createObjectNode();
        row.put("type", event.getClass().getSimpleName());
        row.put("rawSequence", event.raw().sequence()); row.put("sessionId", event.raw().sessionId());
        row.put("connectionId", event.raw().connectionId());
        row.put("receivedAt", event.raw().receivedAt().toString());
        row.put("roomId", event.raw().roomId()); row.put("direction", event.raw().direction());
        ObjectNode data = json.valueToTree(event);
        data.remove("raw");
        if (data.get("message") instanceof ObjectNode message) message.remove("raw");
        row.set("event", data);
        events.write(json.writeValueAsString(row)); events.newLine(); events.flush();
    }
    static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { raw.close(); } finally { events.close(); }
    }
}

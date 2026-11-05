package dev.huya.connect.sdk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Runnable example; prints counts rather than viewer content or session parameters. */
public final class CaptureExample {
    private CaptureExample() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--replay")) {
            AtomicLong messages = new AtomicLong();
            long frames = Replay.read(Path.of(args[1]), new HuyaListener() {
                @Override public void onEvent(HuyaEvent e) { if (e instanceof HuyaEvent.Message) messages.incrementAndGet(); }
            });
            System.out.println("Replay frames=" + frames + ", pushes=" + messages); return;
        }
        if (args.length < 1 || args.length > 4) throw new IllegalArgumentException("ROOM [PRESENTER_UID|-] [SECONDS] [NEW_RECORDING_DIR]");
        long seconds = args.length > 2 ? Long.parseLong(args[2]) : 120;
        if (seconds < 0) throw new IllegalArgumentException("Duration cannot be negative");
        Map<Long, Long> counts = java.util.Collections.synchronizedMap(new TreeMap<>());
        var builder = HuyaClient.builder(args[0]).listener(new HuyaListener() {
            @Override public void onStatus(HuyaStatus s) { System.out.println("State=" + s.state() + ": " + s.detail()); }
            @Override public void onError(Throwable e) { System.err.println("Error=" + e.getClass().getSimpleName() + ": " + e.getMessage()); }
            @Override public void onEvent(HuyaEvent e) {
                if (e instanceof HuyaEvent.Message m) counts.merge(m.uri(), 1L, Long::sum);
            }
        });
        if (args.length > 1 && !args[1].equals("-")) builder.presenterUid(args[1]);
        Path recording = args.length > 3 ? Path.of(args[3]) : null;
        if (recording != null) builder.recording(recording);
        try (HuyaClient client = builder.build()) {
            Thread hook = new Thread(client::close, "sdk-example-stop");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                client.start().get(60, TimeUnit.SECONDS);
                long deadline = seconds == 0 ? Long.MAX_VALUE : System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
                while (System.nanoTime() < deadline && client.status().state() != HuyaStatus.State.FAILED
                        && client.status().state() != HuyaStatus.State.CLOSED) {
                    if (recording != null && Files.exists(recording.resolve("STOP"))) break;
                    Thread.sleep(200);
                }
                if (client.status().state() == HuyaStatus.State.FAILED) throw new IllegalStateException(client.status().detail());
            } finally { Runtime.getRuntime().removeShutdownHook(hook); }
        }
        System.out.println("URI counts=" + counts);
    }
}

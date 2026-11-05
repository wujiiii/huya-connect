package dev.huya.connect.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static dev.huya.connect.sdk.HuyaStatus.State;
import static dev.huya.connect.sdk.Jce.*;

/** Anonymous read-only public-room client. One instance owns one room and one session. */
public final class HuyaClient implements AutoCloseable {
    private final Builder options;
    private final String sessionId = UUID.randomUUID().toString();
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1, threads("huya-control"));
    private final ThreadPoolExecutor callbacks;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Recording recorder;
    private volatile HuyaStatus status;
    private volatile boolean stopped;
    private volatile Throwable terminalError;
    private volatile Connection current;
    private volatile Thread callbackThread;
    private String presenterUid;
    private long sequence;
    private int failures;
    private boolean launched;

    private HuyaClient(Builder builder) throws IOException {
        options = builder.copy(); presenterUid = options.presenterUid;
        status = new HuyaStatus(State.NEW, options.roomId, presenterUid, null, Instant.now(), "created");
        recorder = options.directory == null ? null : new Recording(options.directory);
        callbacks = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(options.capacity), action -> {
                    Thread thread = threads("huya-callback").newThread(action); callbackThread = thread; return thread;
                }) {
            @Override protected void terminated() {
                if (terminalError != null) report(terminalError);
                invoke(() -> options.listener.onStatus(status));
            }
        };
        control.setRemoveOnCancelPolicy(true);
        callbacks.prestartCoreThread();
    }
    public static Builder builder(String roomId) { return new Builder(roomId); }
    public synchronized CompletableFuture<Void> start() {
        if (!launched && !stopped) {
            launched = true; control.execute(this::connect);
            control.scheduleAtFixedRate(this::tick, options.tickMillis, options.tickMillis, TimeUnit.MILLISECONDS);
        }
        return started.copy();
    }
    public HuyaStatus status() { return status; }

    private void connect() {
        if (stopped) return;
        transition(State.CONNECTING, "opening anonymous connection");
        try {
            if (presenterUid == null) presenterUid = resolve();
            if (stopped) return;
            var connection = new Connection(); current = connection;
            URI endpoint = options.endpoints.get(failures % options.endpoints.size());
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15))
                    .header("Origin", "https://www.huya.com")
                    .buildAsync(Protocol.connectUrl(endpoint, connection.guid), connection)
                    .whenComplete((socket, error) -> {
                        if (error != null) execute(() -> recover(connection, new IOException("WebSocket connection failed")));
                        else if (stopped || current != connection) socket.abort();
                    });
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { recover(current, new IOException("Room metadata resolution failed: " + e.getClass().getSimpleName())); }
    }
    private String resolve() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.huya.com/" + options.roomId))
                .timeout(Duration.ofSeconds(15)).header("User-Agent", "Mozilla/5.0").GET().build();
        HttpResponse<byte[]> response = http.send(request, ignored -> new LimitedBody());
        if (response.statusCode() != 200) throw new IOException("Room HTTP status " + response.statusCode());
        return resolveHtml(options.roomId, new String(response.body(), StandardCharsets.UTF_8));
    }
    static String resolveHtml(String roomId, String html) throws IOException {
        var match = Pattern.compile("\\bTT_PROFILE_INFO\\s*=\\s*").matcher(html);
        if (!match.find()) throw new IOException("Public room metadata missing");
        JsonNode profile = new ObjectMapper().readTree(html.substring(match.end()));
        String uid = profile.path("lp").asText();
        if (!roomId.equals(profile.path("profileRoom").asText()) || !uid.matches("[1-9][0-9]{0,18}"))
            throw new IOException("Public room identity mismatch");
        try { Long.parseLong(uid); } catch (NumberFormatException e) { throw new IOException("Presenter UID out of range"); }
        return uid;
    }
    private void tick() {
        try {
            Connection c = current;
            if (stopped || c == null || c.socket == null) return;
            long now = System.nanoTime();
            if (!c.registered && elapsed(now, c.opened) > options.registrationMillis)
                recover(c, new IOException("Core registration timeout"));
            else if (elapsed(now, c.lastResponse) > options.receiveMillis)
                recover(c, new IOException("Protocol receive timeout"));
            else if (elapsed(now, c.lastHeartbeat) >= options.heartbeatMillis) {
                send(c, Protocol.command(20, new byte[0], 0)); c.lastHeartbeat = now;
            }
        } catch (Exception e) { recover(current, new IOException("Heartbeat processing failed")); }
    }
    private static long elapsed(long now, long then) { return TimeUnit.NANOSECONDS.toMillis(now - then); }
    private void send(Connection c, byte[] bytes) {
        if (stopped || current != c) return;
        accept(c, "out", "BinaryWebSocketFrame", bytes);
        if (stopped) return;
        c.outgoing = c.outgoing.thenCompose(ignored -> c.socket.sendBinary(ByteBuffer.wrap(bytes), true))
                .thenApply(ignored -> (Void) null);
        c.outgoing.whenComplete((socket, error) -> {
            if (error != null) execute(() -> recover(c, new IOException("WebSocket send failed")));
        });
    }
    private synchronized void accept(Connection c, String direction, String type, byte[] bytes) {
        if (stopped || current != c) return;
        var raw = new RawFrame(options.roomId, sessionId, c.id, ++sequence, Instant.now(), direction,
                type, Base64.getEncoder().encodeToString(bytes));
        try {
            if (recorder != null) recorder.recordRaw(raw);
            List<HuyaEvent> events = Protocol.decode(raw);
            if (recorder != null) for (HuyaEvent event : events) recorder.recordEvent(event);
            if (direction.equals("in")) {
                for (HuyaEvent event : events) {
                    if (event instanceof HuyaEvent.Command command) {
                        c.lastResponse = System.nanoTime();
                        if (command.opcode() == 17) execute(() -> registration(c, command));
                    }
                }
            }
            dispatch(() -> {
                invoke(() -> options.listener.onRawFrame(raw));
                for (HuyaEvent event : events) invoke(() -> options.listener.onEvent(event));
            });
        } catch (IOException e) { terminate(State.FAILED, new IOException("Recording failed", e)); }
        catch (RuntimeException e) { terminate(State.FAILED, e); }
    }
    private void registration(Connection c, HuyaEvent.Command command) {
        if (stopped || current != c) return;
        try {
            List<Field> ack = Jce.decode(bytes(command.jce(), 1));
            long code = number(ack, 0, -1);
            if ("2".equals(command.requestId())) {
                if (code != 0) dispatch(() -> report(new IOException("Auxiliary registration rejected: " + code)));
                return;
            }
            if (!"1".equals(command.requestId()) || c.registered) return;
            if (code != 0) { recover(c, new IOException("Core registration rejected: " + code)); return; }
            c.registered = true;
            transition(State.RUNNING, "core groups registered");
            started.completeAsync(() -> null);
            byte[] user = Protocol.user(c.guid);
            send(c, Protocol.wup("getLiveRoomActivityBanner", concat(user, integer(1, Long.parseLong(presenterUid))), 100));
            send(c, Protocol.wup("getResourceInfo", concat(user, string(1, "web"), string(2, ""),
                    integer(3, Long.parseLong(presenterUid))), 101));
        } catch (RuntimeException e) { recover(c, new IOException("Invalid registration response")); }
    }
    private void recover(Connection c, Throwable error) {
        if (stopped || current != c) return;
        if (c != null && c.registered && elapsed(System.nanoTime(), c.opened) >= 180_000) failures = 0;
        current = null;
        if (c != null && c.socket != null) c.socket.abort();
        failures++;
        dispatch(() -> report(error));
        transition(State.BACKOFF, error.getMessage());
        long delay = Math.min(60_000, options.backoffMillis * (1L << Math.min(failures - 1, 6)));
        if (options.jitter) delay += java.util.concurrent.ThreadLocalRandom.current().nextLong(1000);
        if (!stopped) control.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }
    private synchronized void transition(State state, String detail) {
        if (stopped) return;
        status = new HuyaStatus(state, options.roomId, presenterUid, current == null ? null : current.id, Instant.now(), detail);
        HuyaStatus snapshot = status;
        dispatch(() -> invoke(() -> options.listener.onStatus(snapshot)));
    }
    private void dispatch(Runnable action) {
        if (stopped) return;
        try { callbacks.execute(action); }
        catch (RejectedExecutionException e) { if (!stopped) terminate(State.FAILED, new IllegalStateException("Callback buffer overflow")); }
    }
    private void execute(Runnable action) {
        if (stopped) return;
        try { control.execute(action); } catch (RejectedExecutionException ignored) { /* closed concurrently */ }
    }
    private void invoke(Runnable action) {
        try { action.run(); } catch (Throwable e) { report(e); }
    }
    private void report(Throwable error) {
        try { options.listener.onError(error); } catch (Throwable ignored) { /* avoid recursive listener failure */ }
    }
    private synchronized void terminate(State state, Throwable error) {
        if (stopped) return;
        stopped = true; terminalError = error;
        if (current != null && current.socket != null) current.socket.abort();
        control.shutdownNow();
        try { if (recorder != null) recorder.close(); }
        catch (IOException e) { state = State.FAILED; if (terminalError == null) terminalError = e; else terminalError.addSuppressed(e); }
        status = new HuyaStatus(state, options.roomId, presenterUid, current == null ? null : current.id,
                Instant.now(), terminalError == null ? "closed" : terminalError.getMessage());
        started.completeExceptionally(terminalError == null ? new IllegalStateException("Client closed") : terminalError);
        callbacks.shutdown();
    }
    @Override public void close() {
        terminate(State.CLOSED, null);
        if (Thread.currentThread() != callbackThread) {
            try { callbacks.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    private static ThreadFactory threads(String name) {
        return action -> { Thread thread = new Thread(action, name); thread.setDaemon(true); return thread; };
    }

    /** Bounds bytes before aggregation; the request timeout covers the complete body subscriber. */
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        java.util.concurrent.Flow.Subscription subscription;
        long size;
        boolean failed;
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            subscription = value; delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) size += buffer.remaining();
            if (size > 4 * 1024 * 1024) {
                failed = true; subscription.cancel(); delegate.onError(new IOException("Room HTML limit"));
            } else delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) { failed = true; delegate.onError(error); } }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }

    private final class Connection implements WebSocket.Listener {
        final String guid = UUID.randomUUID().toString().replace("-", "");
        final String id = UUID.randomUUID().toString();
        final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        final StringBuilder text = new StringBuilder();
        volatile WebSocket socket;
        volatile long opened, lastResponse, lastHeartbeat;
        boolean registered;
        CompletableFuture<Void> outgoing = CompletableFuture.completedFuture(null);
        @Override public void onOpen(WebSocket socket) {
            this.socket = socket;
            if (stopped || current != this) { socket.abort(); return; }
            opened = lastResponse = lastHeartbeat = System.nanoTime();
            execute(() -> {
                transition(State.REGISTERING, "waiting for core group ACK");
                send(this, Protocol.groups(List.of("live:" + presenterUid, "chat:" + presenterUid), 1));
                send(this, Protocol.groups(List.of("comm:vipbar_" + presenterUid), 2));
            });
            socket.request(1);
        }
        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            // Bound transport assembly too; fail explicitly rather than truncate a message.
            if ((long) binary.size() + data.remaining() > options.maxMessageBytes) {
                terminate(State.FAILED, new IllegalStateException("WebSocket message byte limit")); return null;
            }
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); binary.writeBytes(bytes);
            if (last) { byte[] payload = binary.toByteArray(); binary.reset(); accept(this, "in", "BinaryWebSocketFrame", payload); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            if ((long) text.length() + data.length() > options.maxMessageBytes / 4) {
                terminate(State.FAILED, new IllegalStateException("WebSocket text limit")); return null;
            }
            text.append(data);
            if (last) { accept(this, "in", "TextWebSocketFrame", text.toString().getBytes(StandardCharsets.UTF_8)); text.setLength(0); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            execute(() -> recover(this, new IOException("WebSocket closed: " + code))); return null;
        }
        @Override public void onError(WebSocket socket, Throwable error) {
            execute(() -> recover(this, new IOException("WebSocket receive failed")));
        }
    }
    public static final class Builder {
        private final String roomId;
        private String presenterUid;
        private HuyaListener listener = new HuyaListener() { };
        private Path directory;
        private int capacity = 256;
        private List<URI> endpoints = List.of(URI.create("wss://wsapi.huya.com/"), URI.create("wss://cdnws.api.huya.com/"));
        private long heartbeatMillis = 30_000, registrationMillis = 30_000, receiveMillis = 90_000;
        private long tickMillis = 1000, backoffMillis = 2000;
        private int maxMessageBytes = 64 * 1024 * 1024;
        private boolean jitter = true;
        private Builder(String roomId) {
            if (roomId == null || !roomId.matches("[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Numeric room ID required");
            this.roomId = roomId;
        }
        public Builder presenterUid(String uid) {
            if (uid == null || !uid.matches("[1-9][0-9]{0,18}") || Long.parseLong(uid) <= 0)
                throw new IllegalArgumentException("Positive signed-64 presenter UID required");
            presenterUid = uid; return this;
        }
        public Builder listener(HuyaListener listener) { this.listener = java.util.Objects.requireNonNull(listener); return this; }
        public Builder recording(Path directory) { this.directory = java.util.Objects.requireNonNull(directory); return this; }
        public Builder callbackCapacity(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("Positive callback capacity required");
            this.capacity = capacity; return this;
        }
        // Package-private transport/timing overrides keep tests local without opening an arbitrary command API.
        Builder transport(List<URI> endpoints, long heartbeat, long registration, long receive) {
            this.endpoints = List.copyOf(endpoints); heartbeatMillis = heartbeat; registrationMillis = registration;
            receiveMillis = receive; tickMillis = 10; backoffMillis = 20; jitter = false; return this;
        }
        private Builder copy() {
            Builder b = new Builder(roomId); b.presenterUid = presenterUid; b.listener = listener; b.directory = directory;
            b.capacity = capacity; b.endpoints = endpoints; b.heartbeatMillis = heartbeatMillis;
            b.registrationMillis = registrationMillis; b.receiveMillis = receiveMillis; b.tickMillis = tickMillis;
            b.backoffMillis = backoffMillis; b.maxMessageBytes = maxMessageBytes; b.jitter = jitter; return b;
        }
        public HuyaClient build() throws IOException { return new HuyaClient(this); }
    }
}

package dev.huya.connect.sdk;

import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal RFC6455 server fixture, deliberately independent of SDK codecs for framing. */
final class LocalWebSocket implements AutoCloseable {
    interface Scenario { void run(Peer peer, int connection) throws Exception; }
    final ServerSocket server = new ServerSocket(0, 20, java.net.InetAddress.getLoopbackAddress());
    final List<Socket> sockets = new CopyOnWriteArrayList<>();
    final List<Throwable> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    final ExecutorService threads = Executors.newCachedThreadPool();
    volatile boolean closed;
    LocalWebSocket(Scenario scenario) throws IOException {
        threads.submit(() -> {
            while (!closed) {
                try {
                    Socket socket = server.accept(); sockets.add(socket); socket.setSoTimeout(5000);
                    int ordinal = connections.incrementAndGet();
                    threads.submit(() -> {
                        try (socket) { handshake(socket); scenario.run(new Peer(socket), ordinal); }
                        catch (Throwable e) { if (!closed && !(e instanceof EOFException)) failures.add(e); }
                    });
                } catch (IOException e) { if (!closed) failures.add(e); }
            }
        });
    }
    URI uri() { return URI.create("ws://127.0.0.1:" + server.getLocalPort() + "/"); }
    private void handshake(Socket socket) throws Exception {
        var input = socket.getInputStream(); StringBuilder request = new StringBuilder();
        while (!request.toString().endsWith("\r\n\r\n")) {
            int b = input.read(); if (b < 0) throw new EOFException(); request.append((char) b);
            if (request.length() > 16384) throw new IOException("Header limit");
        }
        String key = request.toString().lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:"))
                .findFirst().orElseThrow().split(":", 2)[1].trim();
        String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }
    static final class Peer {
        final Socket socket;
        Peer(Socket socket) { this.socket = socket; }
        byte[] read() throws IOException {
            var in = socket.getInputStream();
            int op = in.read(); if (op < 0) throw new EOFException();
            int size = in.read(); if (size < 0) throw new EOFException();
            boolean masked = (size & 128) != 0; long length = size & 127;
            if (length == 126) length = ((long) in.read() << 8) | in.read();
            else if (length == 127) { length = 0; for (int i = 0; i < 8; i++) length = length << 8 | in.read(); }
            if (length < 0 || length > 1_000_000) throw new IOException("Fixture frame limit");
            byte[] mask = masked ? in.readNBytes(4) : new byte[0];
            byte[] data = in.readNBytes((int) length); if (data.length != length) throw new EOFException();
            if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i % 4];
            if ((op & 15) == 8) throw new EOFException();
            return data;
        }
        synchronized void send(byte[] data) throws IOException {
            var out = socket.getOutputStream(); out.write(130);
            if (data.length < 126) out.write(data.length);
            else { out.write(126); out.write(data.length >>> 8); out.write(data.length & 255); }
            out.write(data); out.flush();
        }
        void ack(int request, int code) throws IOException {
            send(Protocol.command(17, Jce.integer(0, code), request));
        }
        void consume() throws IOException { while (true) read(); }
    }
    @Override public void close() throws IOException {
        closed = true; server.close();
        for (Socket socket : sockets) socket.close();
        threads.shutdownNow();
    }
}

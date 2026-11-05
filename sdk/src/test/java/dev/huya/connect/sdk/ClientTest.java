package dev.huya.connect.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.huya.connect.sdk.Jce.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientTest {
    @TempDir Path directory;
    private static HuyaClient.Builder builder(LocalWebSocket server) {
        return HuyaClient.builder("456").presenterUid("123").transport(List.of(server.uri()), 40, 250, 400)
                .followerEndpoint(java.net.URI.create("http://127.0.0.1:1/followers"));
    }
    private static int opcode(byte[] data) { return (int) number(Jce.decode(data), 0, -1); }
    private static void ready(LocalWebSocket.Peer peer) throws Exception {
        assertEquals(16, opcode(peer.read())); assertEquals(16, opcode(peer.read())); peer.ack(1, 0); peer.ack(2, 0);
    }
    @Test void onlyCoreAckCompletesStartAndCallbacksDoNotBlockHeartbeat() throws Exception {
        CountDownLatch auxSent = new CountDownLatch(1), allowCore = new CountDownLatch(1);
        CountDownLatch callbackBlocked = new CountDownLatch(1), release = new CountDownLatch(1), heartbeat = new CountDownLatch(1);
        List<Integer> ops = new CopyOnWriteArrayList<>();
        try (var server = new LocalWebSocket((peer, n) -> {
            ops.add(opcode(peer.read())); ops.add(opcode(peer.read())); peer.ack(2, 0); auxSent.countDown();
            assertTrue(allowCore.await(3, TimeUnit.SECONDS)); peer.ack(1, 0);
            while (true) { int op = opcode(peer.read()); ops.add(op); if (op == 20) { heartbeat.countDown(); peer.send(Protocol.command(21, new byte[0], 0)); } }
        }); var client = builder(server).listener(new HuyaListener() {
            @Override public void onEvent(HuyaEvent e) {
                if (e instanceof HuyaEvent.Command c && "2".equals(c.requestId()) && c.opcode() == 17) {
                    callbackBlocked.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException x) { Thread.currentThread().interrupt(); }
                }
            }
        }).build()) {
            CompletableFuture<Void> start = client.start();
            assertTrue(auxSent.await(3, TimeUnit.SECONDS)); assertTrue(callbackBlocked.await(3, TimeUnit.SECONDS));
            assertFalse(start.isDone()); allowCore.countDown();
            start.get(3, TimeUnit.SECONDS);
            assertTrue(heartbeat.await(3, TimeUnit.SECONDS)); assertEquals(HuyaStatus.State.RUNNING, client.status().state());
            assertFalse(ops.contains(27));
            release.countDown(); assertTrue(server.failures.isEmpty(), server.failures.toString());
        } finally { release.countDown(); allowCore.countDown(); }
    }
    @Test void reconnectsRebootstrapsAndPreservesUnknownRaw() throws Exception {
        CountDownLatch second = new CountDownLatch(1); AtomicInteger queries = new AtomicInteger();
        List<HuyaEvent.Message> messages = new CopyOnWriteArrayList<>();
        try (var server = new LocalWebSocket((peer, n) -> {
            ready(peer);
            for (int i = 0; i < 5; i++) { assertEquals(3, opcode(peer.read())); queries.incrementAndGet(); }
            peer.send(ProtocolTest.v1(999999, n, integer(17, 42)));
            if (n == 1) return;
            peer.consume();
        }); var client = builder(server).listener(new HuyaListener() {
            @Override public void onEvent(HuyaEvent e) {
                if (e instanceof HuyaEvent.Message m) { messages.add(m); if (messages.size() == 2) second.countDown(); }
            }
        }).recording(directory).build()) {
            client.start().get(3, TimeUnit.SECONDS); assertTrue(second.await(3, TimeUnit.SECONDS));
            assertEquals(10, queries.get()); assertNotEquals(messages.get(0).raw().connectionId(), messages.get(1).raw().connectionId());
            assertEquals(List.of("1", "2"), messages.stream().map(HuyaEvent.Message::messageId).toList());
            assertTrue(Files.size(directory.resolve("raw-0001.ndjson")) > 0);
        }
    }
    @Test void registrationAndReceiveTimeoutRetryInsteadOfClaimingRunning() throws Exception {
        for (boolean registered : new boolean[]{false, true}) {
            CountDownLatch reconnected = new CountDownLatch(1);
            try (var server = new LocalWebSocket((peer, n) -> {
                peer.read(); peer.read();
                if (registered) peer.ack(1, 0); else peer.ack(2, 0);
                if (n > 1) reconnected.countDown(); peer.consume();
            }); var client = builder(server).build()) {
                var start = client.start(); assertTrue(reconnected.await(4, TimeUnit.SECONDS));
                if (!registered) assertFalse(start.isDone());
            }
        }
    }
    @Test void rejectsCoreAckButAuxiliaryRejectionDoesNotBlockCore() throws Exception {
        AtomicInteger auxErrors = new AtomicInteger(); CountDownLatch retried = new CountDownLatch(1);
        try (var server = new LocalWebSocket((peer, n) -> {
            peer.read(); peer.read();
            if (n == 1) { peer.ack(1, 904); peer.consume(); }
            else { peer.ack(2, 904); peer.ack(1, 0); retried.countDown(); peer.consume(); }
        }); var client = builder(server).listener(new HuyaListener() {
            @Override public void onError(Throwable e) { if (e.getMessage().contains("Auxiliary")) auxErrors.incrementAndGet(); }
        }).build()) {
            client.start().get(4, TimeUnit.SECONDS); assertTrue(retried.await(3, TimeUnit.SECONDS));
            assertTrue(server.connections.get() >= 2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (auxErrors.get() == 0 && System.nanoTime() < deadline) Thread.yield();
            assertEquals(1, auxErrors.get());
        }
    }
    @Test void callbackExceptionsAreReportedAndLaterMessagesStillArrive() throws Exception {
        CountDownLatch received = new CountDownLatch(1); AtomicInteger errors = new AtomicInteger();
        try (var server = new LocalWebSocket((peer, n) -> {
            ready(peer); peer.send(ProtocolTest.v1(999999, 1, integer(0, 1))); peer.send(ProtocolTest.v1(999999, 2, integer(0, 2))); peer.consume();
        }); var client = builder(server).listener(new HuyaListener() {
            @Override public void onEvent(HuyaEvent e) {
                if (e instanceof HuyaEvent.Message m) {
                    if (m.messageId().equals("1")) throw new IllegalStateException("caller failed");
                    received.countDown();
                }
            }
            @Override public void onError(Throwable e) { errors.incrementAndGet(); }
        }).build()) {
            client.start().get(3, TimeUnit.SECONDS); assertTrue(received.await(3, TimeUnit.SECONDS)); assertTrue(errors.get() > 0);
        }
    }
    @Test void overflowIsTerminalAndReportedAfterQueuedCallbacksDrain() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1), reported = new CountDownLatch(1);
        try (var server = new LocalWebSocket((peer, n) -> {
            peer.read(); peer.read(); assertTrue(blocked.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 30; i++) peer.send(ProtocolTest.v1(999999, i + 1, integer(0, i)));
            peer.consume();
        }); var client = builder(server).callbackCapacity(4).listener(new HuyaListener() {
            @Override public void onRawFrame(RawFrame raw) {
                blocked.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            @Override public void onError(Throwable error) { if (error.getMessage().contains("overflow")) reported.countDown(); }
        }).build()) {
            client.start(); assertTrue(blocked.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (client.status().state() != HuyaStatus.State.FAILED && System.nanoTime() < deadline) Thread.yield();
            assertEquals(HuyaStatus.State.FAILED, client.status().state()); release.countDown();
            assertTrue(reported.await(3, TimeUnit.SECONDS)); assertEquals(1, server.connections.get());
        } finally { release.countDown(); }
    }
    @Test void storageFailureStopsWithoutNetworkRetry() throws Exception {
        CountDownLatch allowPush = new CountDownLatch(1), failed = new CountDownLatch(1);
        try (var server = new LocalWebSocket((peer, n) -> {
            ready(peer); assertTrue(allowPush.await(3, TimeUnit.SECONDS)); peer.send(ProtocolTest.v1(999999, 1, integer(0, 1))); peer.consume();
        }); var client = builder(server).recording(directory).listener(new HuyaListener() {
            @Override public void onStatus(HuyaStatus s) { if (s.state() == HuyaStatus.State.FAILED) failed.countDown(); }
        }).build()) {
            client.start().get(3, TimeUnit.SECONDS);
            var field = HuyaClient.class.getDeclaredField("recorder"); field.setAccessible(true);
            ((Recording) field.get(client)).close(); allowPush.countDown();
            assertTrue(failed.await(3, TimeUnit.SECONDS)); assertTrue(client.status().detail().contains("Recording"));
            assertEquals(1, server.connections.get());
        } finally { allowPush.countDown(); }
    }
    @Test void closeCancelsPendingStartIsIdempotentAndDoesNotReconnect() throws Exception {
        try (var server = new LocalWebSocket((peer, n) -> peer.consume()); var client = builder(server).build()) {
            var first = client.start(); client.close(); client.close();
            assertTrue(first.isCompletedExceptionally()); assertTrue(client.start().isCompletedExceptionally());
            assertEquals(HuyaStatus.State.CLOSED, client.status().state());
        }
    }
    @Test void closeFromCallbackDoesNotWaitForItselfAndTerminalStateStaysClosed() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<HuyaClient> ref = new java.util.concurrent.atomic.AtomicReference<>();
        try (var server = new LocalWebSocket((peer, n) -> peer.consume()); var client = builder(server).listener(new HuyaListener() {
            @Override public void onRawFrame(RawFrame raw) { ref.get().close(); closed.countDown(); }
        }).build()) {
            ref.set(client); client.start();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertEquals(HuyaStatus.State.CLOSED, client.status().state());
        }
    }
    @Test void metadataBodyLimitCancelsUpstreamBeforeAggregatingUnboundedBytes() throws Exception {
        var body = new HuyaClient.LimitedBody();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        body.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancelled.set(true); }
        });
        body.onNext(List.of(java.nio.ByteBuffer.allocate(4 * 1024 * 1024 + 1)));
        assertTrue(cancelled.get());
        assertThrows(java.util.concurrent.ExecutionException.class, () -> body.getBody().toCompletableFuture().get());
        var valid = new HuyaClient.LimitedBody();
        valid.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { }
        });
        valid.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[]{1, 2, 3}))); valid.onComplete();
        assertArrayEquals(new byte[]{1, 2, 3}, valid.getBody().toCompletableFuture().get());
    }
}

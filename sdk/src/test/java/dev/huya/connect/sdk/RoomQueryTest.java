package dev.huya.connect.sdk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.huya.connect.sdk.Jce.*;
import static org.junit.jupiter.api.Assertions.*;

class RoomQueryTest {
    static int opcode(byte[] frame) { return (int) number(Jce.decode(frame), 0, -1); }
    static List<Field> header(byte[] frame) {
        byte[] data = bytes(Jce.decode(frame), 1);
        return Jce.decode(java.util.Arrays.copyOfRange(data, 4, data.length));
    }
    static byte[] response(int id, String function, byte[] fields) {
        byte[] attrs = Protocol.map(0, string(0, "tRsp"), blob(1, concat(head(0, 10), fields, head(0, 11))));
        byte[] header = concat(integer(1, 3), integer(4, id), string(5, "wupui"), string(6, function), blob(7, attrs));
        return Protocol.command(4, concat(ByteBuffer.allocate(4).putInt(header.length + 4).array(), header), 0);
    }
    static byte[] vector(int tag, byte[]... fields) {
        byte[] items = new byte[0];
        for (byte[] item : fields) items = concat(items, head(0, 10), item, head(0, 11));
        return concat(head(tag, 9), integer(0, fields.length), items);
    }
    static byte[] catalog() {
        byte[] gift = concat(integer(0, Long.MAX_VALUE), string(1, "Gift"), integer(2, 10), integer(8, 3));
        byte[] item = concat(string(0, "resource-id"), integer(1, 1), blob(2, gift));
        return concat(vector(0, concat(integer(0, 1), vector(1, item))), integer(2, 0), integer(4, 1));
    }
    static byte[] live(long living, long replay) {
        return concat(integer(0, living), head(1, 10), integer(0, 123), integer(7, Long.MAX_VALUE),
                integer(24, 1_700_000_000), integer(39, replay), head(0, 11));
    }
    static HuyaEvent.Snapshot snapshot(String function, byte[] fields) {
        return Protocol.decode(ProtocolTest.raw(response(1000, function, fields))).stream()
                .filter(HuyaEvent.Snapshot.class::isInstance).map(HuyaEvent.Snapshot.class::cast).findFirst().orElseThrow();
    }
    static void ready(LocalWebSocket.Peer peer) throws Exception {
        assertEquals(16, opcode(peer.read())); assertEquals(16, opcode(peer.read()));
        peer.ack(1, 0); peer.ack(2, 0);
    }
    static void answer(LocalWebSocket.Peer peer, byte[] frame, long count) throws Exception {
        if (opcode(frame) == 20) { peer.send(Protocol.command(21, new byte[0], 0)); return; }
        if (opcode(frame) != 3) return;
        var h = header(frame); String fn = text(h, 6, ""); int id = Math.toIntExact(number(h, 4, -1));
        byte[] result = switch (fn) {
            case "getFansScoreRankPanel" -> concat(integer(2, 123), integer(5, count));
            case "getGuardianCount" -> integer(0, count);
            case "getLivingInfo" -> live(1, 0);
            case "getResourceInfo" -> catalog();
            default -> vector(0);
        };
        peer.send(response(id, fn, result));
    }
    static HuyaClient.Builder builder(LocalWebSocket ws, URI http) {
        return HuyaClient.builder("456").presenterUid("123").transport(List.of(ws.uri()), 40, 300, 600)
                .followerEndpoint(http).queryTimeout(Duration.ofSeconds(2));
    }

    @Test void countsMissingFieldsIdentityAndLiveReplayAreDistinct() throws Exception {
        assertEquals(0L, RoomQueries.parse(RoomQueries.Kind.FAN_CLUB,
                snapshot("getFansScoreRankPanel", concat(integer(2, 123), integer(5, 0))), "123"));
        assertThrows(IllegalArgumentException.class, () -> RoomQueries.parse(RoomQueries.Kind.FAN_CLUB,
                snapshot("getFansScoreRankPanel", integer(2, 123)), "123"));
        assertThrows(IllegalArgumentException.class, () -> RoomQueries.parse(RoomQueries.Kind.FAN_CLUB,
                snapshot("getFansScoreRankPanel", concat(integer(2, 999), integer(5, 42))), "123"));
        assertEquals(0L, RoomQueries.parse(RoomQueries.Kind.GUARDIANS, snapshot("getGuardianCount", integer(0, 0)), "123"));
        assertThrows(IllegalArgumentException.class, () -> RoomQueries.count(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> RoomQueries.count(Jce.decode(concat(integer(0, 0), integer(0, 7))), 0));
        var real = RoomQueries.live(snapshot("getLivingInfo", live(1, 0)).response(), "123");
        assertEquals(HuyaRoomInfo.LiveState.LIVE, real.state()); assertFalse(real.replay());
        assertEquals(Long.toString(Long.MAX_VALUE), real.liveId()); assertEquals(1_700_000_000L, real.startTimeEpochSeconds());
        var replay = RoomQueries.live(snapshot("getLivingInfo", live(1, 1)).response(), "123");
        assertEquals(HuyaRoomInfo.LiveState.OFFLINE, replay.state()); assertTrue(replay.replay());
        assertEquals(HuyaRoomInfo.LiveState.OFFLINE, RoomQueries.live(snapshot("getLivingInfo", live(0, 0)).response(), "123").state());
        assertThrows(IllegalArgumentException.class, () -> RoomQueries.live(snapshot("getLivingInfo", integer(0, 0)).response(), "123"));
        var absentReplay = RoomQueries.live(snapshot("getLivingInfo", concat(integer(0, 1), head(1, 10), integer(0, 123), head(0, 11))).response(), "123");
        assertEquals(HuyaRoomInfo.LiveState.UNKNOWN, absentReplay.state()); assertNull(absentReplay.replay());
        var offline = RoomQueries.live(snapshot("getLivingInfo", concat(integer(0, 0), head(1, 10), integer(0, 123), integer(7, 0), integer(24, 0), integer(39, 1), head(0, 11))).response(), "123");
        assertEquals("0", offline.liveId()); assertEquals(0L, offline.startTimeEpochSeconds());
    }
    @Test void fullCatalogReturnsDecimalIdsTypesAndRejectsIncrementalResponses() {
        var value = RoomQueries.catalog(snapshot("getResourceInfo", catalog()).response());
        assertEquals(3, value.gifts().get(Long.toString(Long.MAX_VALUE)).iPropsType());
        assertThrows(UnsupportedOperationException.class, () -> value.gifts().clear());
        assertThrows(IllegalArgumentException.class, () -> RoomQueries.catalog(snapshot("getResourceInfo", concat(vector(0), integer(2, 0), integer(4, 0))).response()));
        assertThrows(IllegalArgumentException.class, () -> RoomQueries.catalog(snapshot("getResourceInfo", concat(vector(0), integer(4, 1))).response()));
    }
    @Test void followerJsonPreservesZeroAndRejectsOverflowFailureAndMalformedCounts() throws Exception {
        assertEquals(0L, RoomQueries.followers("{\"result\":0,\"subscribe_count\":0}".getBytes(StandardCharsets.UTF_8)));
        for (String text : List.of("{}", "{\"result\":1,\"subscribe_count\":0}", "{\"result\":0,\"subscribe_count\":-1}",
                "{\"result\":0,\"subscribe_count\":1.5}", "{\"result\":0,\"subscribe_count\":9223372036854775808}",
                "{\"result\":0,\"subscribe_count\":0,\"subscribe_count\":1}", "{\"result\":0,\"subscribe_count\":0}{}"))
            assertThrows(Exception.class, () -> RoomQueries.followers(text.getBytes(StandardCharsets.UTF_8)), text);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) { gzip.write("{\"result\":0,\"subscribe_count\":7}".getBytes(StandardCharsets.UTF_8)); }
        assertEquals(7L, RoomQueries.followers(bytes.toByteArray()));
    }
    @Test void startupAndRefreshDeliverTypedZeroSnapshotsAndPushesDoNotAccumulateGuardians() throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/followers", exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("to_key=123"));
            byte[] b = "{\"result\":0,\"subscribe_count\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length); exchange.getResponseBody().write(b); exchange.close();
        }); http.start();
        AtomicInteger phase = new AtomicInteger(), sent = new AtomicInteger();
        try (var ws = new LocalWebSocket((peer,n) -> {
            ready(peer);
            while (true) {
                byte[] frame = peer.read(); answer(peer, frame, 0);
                if (phase.get() > sent.get()) {
                    if (phase.get() == 1) {
                        peer.send(ProtocolTest.v1(6249, 100, integer(0, 7)));
                        peer.send(ProtocolTest.v1(6249, 101, integer(0, 7)));
                        peer.send(Protocol.command(7, concat(integer(1, 6249), blob(2, integer(0, 99)),
                                string(4, "chat:999"), integer(5, 102)), 0));
                    } else peer.send(ProtocolTest.v1(6249, 103, integer(0, 0)));
                    sent.set(phase.get());
                }
            }
        }); var client = builder(ws, URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/followers")).build()) {
            assertFalse(client.roomInfo().fanClubMembers().successful());
            client.start().get(3, TimeUnit.SECONDS);
            for (var future : List.of(client.refreshFanClubMembers(), client.refreshGuardianCount(), client.refreshFollowerCount())) {
                var result = future.get(3, TimeUnit.SECONDS); assertTrue(result.successful(), result.detail()); assertEquals(0L, result.value());
                assertEquals("123", result.presenterUid()); assertNotNull(result.requestId()); assertNotNull(result.responseSha256());
            }
            var gifts = client.refreshGiftCatalog().get(3, TimeUnit.SECONDS);
            assertTrue(gifts.successful(), gifts.detail()); assertEquals(1, gifts.value().gifts().size()); assertInstanceOf(HuyaEvent.Snapshot.class, gifts.evidence());
            assertEquals(HuyaRoomInfo.LiveState.LIVE, client.refreshLiveStatus().get(3, TimeUnit.SECONDS).value().state());
            phase.set(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((sent.get() != 1 || !Long.valueOf(7).equals(client.roomInfo().guardianCount().value())) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(7L, client.roomInfo().guardianCount().value()); // Not7+7 or the foreign group's99.
            phase.set(2); deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while ((sent.get() != 2 || !Long.valueOf(0).equals(client.roomInfo().guardianCount().value())) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0L, client.roomInfo().guardianCount().value());
            assertTrue(ws.failures.isEmpty(), ws.failures.toString());
        } finally { http.stop(0); }
    }
    @Test void timeoutsDoNotReconnectAndCloseResolvesAllPendingQueries() throws Exception {
        try (var ws = new LocalWebSocket((peer,n) -> {
            ready(peer);
            while (true) if (opcode(peer.read()) == 20) peer.send(Protocol.command(21, new byte[0], 0));
        }); var client = builder(ws, URI.create("http://127.0.0.1:1/followers")).queryTimeout(Duration.ofMillis(80)).build()) {
            client.start().get(2, TimeUnit.SECONDS);
            var failed = client.refreshFanClubMembers().get(2, TimeUnit.SECONDS);
            assertFalse(failed.successful()); assertNull(failed.value()); assertEquals("Query timeout", failed.detail());
            assertEquals(1, ws.connections.get()); assertEquals(HuyaStatus.State.RUNNING, client.status().state());
            var pending = client.refreshGuardianCount(); client.close();
            assertEquals(HuyaQueryResult.Outcome.UNKNOWN, pending.get(2, TimeUnit.SECONDS).outcome());
            assertFalse(client.refreshGiftCatalog().get(1, TimeUnit.SECONDS).successful());
        }
    }
    @Test void delayedOldConnectionHttpCannotReplaceNewConnectionObservation() throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(); http.setExecutor(executor);
        CountDownLatch firstHttp = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        http.createContext("/followers", exchange -> {
            int n = calls.incrementAndGet();
            if (n == 1) { firstHttp.countDown(); try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
            byte[] b = ("{\"result\":0,\"subscribe_count\":" + n + "}").getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, b.length); exchange.getResponseBody().write(b); } finally { exchange.close(); }
        }); http.start();
        try (var ws = new LocalWebSocket((peer,n) -> {
            ready(peer);
            if (n == 1) { assertTrue(firstHttp.await(2, TimeUnit.SECONDS)); return; }
            while (true) answer(peer, peer.read(), n);
        }); var client = builder(ws, URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/followers")).build()) {
            client.start().get(2, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ((ws.connections.get() < 2 || !client.roomInfo().followerCount().successful()) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(2L, client.roomInfo().followerCount().value());
            String connection = client.roomInfo().followerCount().connectionId(); release.countDown();
            Thread.sleep(100);
            assertEquals(2L, client.roomInfo().followerCount().value()); assertEquals(connection, client.status().connectionId());
            assertEquals(2L, client.refreshGuardianCount().get(2, TimeUnit.SECONDS).value());
        } finally { release.countDown(); http.stop(0); executor.shutdownNow(); }
    }
    @Test void callerFutureContinuationDoesNotBlockHeartbeatAndDelayedQueryCannotOverwritePush() throws Exception {
        CountDownLatch allowResponse = new CountDownLatch(1), blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch heartbeatWhileBlocked = new CountDownLatch(1);
        try (var ws = new LocalWebSocket((peer,n) -> {
            ready(peer);
            while (true) {
                byte[] frame = peer.read();
                if (opcode(frame) == 20) {
                    peer.send(Protocol.command(21, new byte[0], 0));
                    if (blocked.getCount() == 0) heartbeatWhileBlocked.countDown();
                } else if (opcode(frame) == 3 && text(header(frame), 6, "").equals("getGuardianCount")) {
                    assertTrue(allowResponse.await(2, TimeUnit.SECONDS));
                    peer.send(ProtocolTest.v1(6249, 200, integer(0, 9)));
                    peer.send(response(Math.toIntExact(number(header(frame), 4, -1)), "getGuardianCount", integer(0, 2)));
                } else answer(peer, frame, 0);
            }
        }); var client = builder(ws, URI.create("http://127.0.0.1:1/followers")).build()) {
            client.start().get(2, TimeUnit.SECONDS);
            var future = client.refreshGuardianCount();
            var continuation = future.thenRun(() -> {
                blocked.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            allowResponse.countDown(); assertTrue(blocked.await(2, TimeUnit.SECONDS));
            assertTrue(heartbeatWhileBlocked.await(2, TimeUnit.SECONDS));
            assertEquals(2L, future.get(1, TimeUnit.SECONDS).value()); // Response remains available for its own request.
            assertEquals(9L, client.roomInfo().guardianCount().value()); // Newer push remains the latest observation.
            release.countDown(); continuation.get(1, TimeUnit.SECONDS);
        } finally { allowResponse.countDown(); release.countDown(); }
    }
}

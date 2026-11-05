package dev.huya.connect.demo;

import dev.huya.connect.sdk.HuyaEvent;
import dev.huya.connect.sdk.HuyaStatus;
import dev.huya.connect.sdk.RawFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoomHealthTest {
    static RawFrame raw(long sequence) {
        return new RawFrame("1995", "session", "connection", sequence, Instant.now(), "in", "BinaryWebSocketFrame", "AA==");
    }
    static HuyaStatus status(HuyaStatus.State state) {
        return new HuyaStatus(state, "1995", "2272316519", "connection", Instant.now(), "test");
    }
    @Test void idleRoomIsHealthyWithHeartbeatAndBecomesStaleWithoutProtocol() {
        RoomHealth health = new RoomHealth(); RawFrame raw = raw(1);
        health.onRawFrame(raw); health.onEvent(new HuyaEvent.Command(raw, 21, "0", List.of()));
        Map<String, Object> fresh = health.snapshot(status(HuyaStatus.State.RUNNING), Instant.now());
        assertEquals(true, fresh.get("healthy")); assertEquals(0L, fresh.get("pushes"));
        assertNotNull(fresh.get("lastHeartbeatReplyAt"));
        assertEquals(false, health.snapshot(status(HuyaStatus.State.RUNNING), Instant.now().plusSeconds(110)).get("healthy"));
        assertEquals(false, health.snapshot(status(HuyaStatus.State.REGISTERING), Instant.now()).get("healthy"));
    }
    @Test void countsGenericMessageOnceAndSeparatesQualityErrorsFromTransport() {
        RoomHealth health = new RoomHealth(); RawFrame raw = raw(1);
        var message = new HuyaEvent.Message(raw, 6501, "chat:test", "1", "gift", "AA==", List.of(), Map.of(), null);
        health.onRawFrame(raw); health.onEvent(new HuyaEvent.Command(raw, 7, "0", List.of()));
        health.onEvent(message); health.onEvent(new HuyaEvent.Gift(message, "2272316519", null, null,
                null, null, null, null, null, null, null, null, null));
        health.onEvent(new HuyaEvent.DecodeError(raw, "synthetic decode failure"));
        var snapshot = health.snapshot(status(HuyaStatus.State.RUNNING), Instant.now());
        assertEquals(1L, snapshot.get("pushes")); assertEquals(1L, snapshot.get("gifts"));
        assertEquals(1L, snapshot.get("decodeErrors")); assertEquals(true, snapshot.get("healthy"));
        assertFalse(snapshot.containsKey("senderName"));
    }
    @Test void reconnectDiagnosticsStayBoundedAndPersistAfterRecovery(@TempDir Path root) throws Exception {
        RoomHealth health = new RoomHealth();
        for (int i = 0; i < 40; i++) health.onConnectionDiagnostic(new dev.huya.connect.sdk.HuyaConnectionDiagnostic(
                "527988", "1199568157971", "connection-" + i, Instant.now().toString(), 0,
                "WebSocket closed: 1012", 1012, List.of("java.io.IOException"), 20L, 30L, 2000));
        var snapshot = health.snapshot(status(HuyaStatus.State.RUNNING), Instant.now());
        var diagnostics = (List<?>) snapshot.get("connectionDiagnostics");
        assertEquals(32, diagnostics.size());
        Path file = root.resolve("status.json"); CollectorDemo.atomicJson(file, snapshot);
        String json = Files.readString(file);
        assertTrue(json.contains("connection-8")); assertTrue(json.contains("connection-39"));
        assertFalse(json.contains("connection-7\"")); assertTrue(json.contains("1012"));
    }
    @Test void atomicStatusCanBeReplacedAndTargetsStayPinned(@TempDir Path root) throws Exception {
        Path file = root.resolve("status.json");
        CollectorDemo.atomicJson(file, Map.of("version", 1)); CollectorDemo.atomicJson(file, Map.of("version", 2));
        assertTrue(Files.readString(file).contains("2")); assertFalse(Files.exists(root.resolve("status.json.tmp")));
        assertEquals("1199568157971", CollectorDemo.TARGETS.get(1).uid());
        assertEquals("527988", CollectorDemo.TARGETS.get(1).room());
    }
}

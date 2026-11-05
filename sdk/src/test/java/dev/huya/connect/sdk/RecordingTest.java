package dev.huya.connect.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecordingTest {
    @TempDir Path directory;
    @Test void rotatesPreservesHashAndReplaysWithoutChangingInput() throws Exception {
        var raw = ProtocolTest.raw(ProtocolTest.v1(6501, 11, Jce.integer(41, 300)));
        try (Recording recording = new Recording(directory, 1)) {
            recording.recordRaw(raw);
            for (var event : Protocol.decode(raw)) recording.recordEvent(event);
            recording.recordRaw(raw);
        }
        List<String> before = Files.readAllLines(directory.resolve("raw-0001.ndjson"));
        var row = new ObjectMapper().readTree(before.get(0));
        assertEquals(Recording.sha256(raw.payload()), row.path("sha256").asText());
        assertTrue(Files.exists(directory.resolve("raw-0002.ndjson")));
        List<HuyaEvent> replay = new ArrayList<>();
        assertEquals(2, Replay.read(directory, new HuyaListener() {
            @Override public void onEvent(HuyaEvent event) { replay.add(event); }
        }));
        assertEquals(2, replay.stream().filter(HuyaEvent.Gift.class::isInstance).count());
        assertEquals(before, Files.readAllLines(directory.resolve("raw-0001.ndjson")));
        assertThrows(java.io.IOException.class, () -> new Recording(directory));
    }
    @Test void readsLegacyMissingSessionAndRejectsTampering() throws Exception {
        var raw = ProtocolTest.raw(ProtocolTest.v1(999999, 1, Jce.integer(0, 1)));
        var json = new ObjectMapper(); var row = json.createObjectNode();
        row.put("roomId", "123"); row.put("sequence", 1); row.put("receivedAt", raw.receivedAt().toString());
        row.put("base64", raw.base64());
        Files.writeString(directory.resolve("raw-0001.ndjson"), row.toString() + "\n");
        assertEquals(1, Replay.read(directory, new HuyaListener() {
            @Override public void onRawFrame(RawFrame f) { assertEquals("legacy", f.sessionId()); }
        }));
        row.put("sha256", "incorrect"); Files.writeString(directory.resolve("raw-0001.ndjson"), row.toString() + "\n");
        assertThrows(java.io.IOException.class, () -> Replay.read(directory, new HuyaListener() { }));
    }
    @Test void listenerSeesRawOnDiskBeforeDerivedEvent() throws Exception {
        var raw = ProtocolTest.raw(ProtocolTest.v1(999999, 1, Jce.integer(0, 1)));
        try (Recording recording = new Recording(directory)) {
            recording.recordRaw(raw);
            assertTrue(Files.size(directory.resolve("raw-0001.ndjson")) > 0);
            assertEquals(0, Files.size(directory.resolve("events.ndjson")));
            recording.recordEvent(Protocol.decode(raw).get(0));
            assertTrue(Files.size(directory.resolve("events.ndjson")) > 0);
        }
    }
    @Test void legacyContinuationIsExposedAsRawAndExplicitDecodeLimitation() throws Exception {
        String row = "{\"roomId\":\"123\",\"sequence\":1,\"receivedAt\":\"1970-01-01T00:00:00Z\","
                + "\"frameType\":\"ContinuationWebSocketFrame\",\"base64\":\"AA==\"}\n";
        Files.writeString(directory.resolve("raw-0001.ndjson"), row);
        List<HuyaEvent> events = new ArrayList<>();
        assertEquals(1, Replay.read(directory, new HuyaListener() {
            @Override public void onEvent(HuyaEvent event) { events.add(event); }
            @Override public void onRawFrame(RawFrame raw) { assertArrayEquals(new byte[]{0}, raw.payload()); }
        }));
        assertInstanceOf(HuyaEvent.DecodeError.class, events.get(0));
    }
}

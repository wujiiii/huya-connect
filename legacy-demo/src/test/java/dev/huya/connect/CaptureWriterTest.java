package dev.huya.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureWriterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void savesEveryRawFrameWithoutChangingBytesAndRotatesSegments() throws Exception {
        byte[] first = new byte[]{0, 1, (byte) 255};
        byte[] second = new byte[]{2, 3, 4};
        try (CaptureWriter writer = new CaptureWriter(directory, "123", 150)) {
            writer.recordRaw(first);
            writer.recordRaw(second);
        }

        List<Path> segments;
        try (var files = Files.list(directory)) {
            segments = files.filter(path -> path.getFileName().toString().startsWith("raw-"))
                    .sorted().toList();
        }
        assertEquals(2, segments.size());
        JsonNode one = mapper.readTree(Files.readString(segments.get(0)).trim());
        JsonNode two = mapper.readTree(Files.readString(segments.get(1)).trim());
        assertEquals("123", one.path("roomId").asText());
        assertEquals(1, one.path("sequence").asLong());
        assertEquals(2, two.path("sequence").asLong());
        assertEquals(Base64.getEncoder().encodeToString(first), one.path("base64").asText());
        assertEquals(Base64.getEncoder().encodeToString(second), two.path("base64").asText());
    }

    @Test
    void writesParsedEventsSeparatelyFromRawFrames() throws Exception {
        try (CaptureWriter writer = new CaptureWriter(directory, "123", 1024)) {
            writer.recordRaw(new byte[]{1});
            writer.recordEvent("gift", Map.of("giftId", "8", "count", 2));
        }

        JsonNode event = mapper.readTree(Files.readString(directory.resolve("events.ndjson")).trim());
        assertEquals("gift", event.path("type").asText());
        assertEquals(1, event.path("rawSequence").asLong());
        assertEquals("8", event.path("data").path("giftId").asText());
        assertEquals(2, event.path("data").path("count").asInt());
        assertTrue(Files.exists(directory.resolve("raw-0001.ndjson")));
    }
}

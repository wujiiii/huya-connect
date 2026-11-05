package dev.huya.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecodeErrorHandlerTest {
    @TempDir
    Path directory;

    @Test
    void recordsDecoderFailureWithLastRawFrameSequence() throws Exception {
        try (CaptureWriter writer = new CaptureWriter(directory, "99", 1024)) {
            writer.recordRaw(new byte[]{3});
            EmbeddedChannel channel = new EmbeddedChannel(new DecodeErrorHandler(writer));
            channel.pipeline().fireExceptionCaught(new IllegalStateException("bad field"));
            channel.finishAndReleaseAll();
        }
        String[] lines = Files.readString(directory.resolve("events.ndjson")).trim().split("\\R");
        JsonNode event = new ObjectMapper().readTree(lines[0]);
        assertEquals("decode_error", event.path("type").asText());
        assertEquals(1, event.path("rawSequence").asLong());
        assertEquals("IllegalStateException", event.path("data").path("errorType").asText());
    }
}

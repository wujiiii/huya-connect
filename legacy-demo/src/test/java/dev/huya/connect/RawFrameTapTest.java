package dev.huya.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RawFrameTapTest {
    @TempDir
    Path directory;

    @Test
    void recordsFrameAndStillForwardsItsOriginalBytes() throws Exception {
        byte[] payload = new byte[]{10, 0, (byte) 250};
        try (CaptureWriter writer = new CaptureWriter(directory, "99", 1024)) {
            EmbeddedChannel channel = new EmbeddedChannel(new RawFrameTap(writer));
            channel.writeInbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)));
            BinaryWebSocketFrame forwarded = channel.readInbound();
            byte[] result = new byte[forwarded.content().readableBytes()];
            forwarded.content().readBytes(result);
            assertArrayEquals(payload, result);
            forwarded.release();
            channel.finishAndReleaseAll();
        }

        JsonNode raw = new ObjectMapper().readTree(Files.readString(directory.resolve("raw-0001.ndjson")).trim());
        assertEquals("BinaryWebSocketFrame", raw.path("frameType").asText());
        assertEquals("in", raw.path("direction").asText());
        assertEquals(Base64.getEncoder().encodeToString(payload), raw.path("base64").asText());
    }

    @Test
    void recordsOutboundFrameWithoutChangingItsBytes() throws Exception {
        byte[] payload = new byte[]{7, 8};
        try (CaptureWriter writer = new CaptureWriter(directory, "99", 1024)) {
            EmbeddedChannel channel = new EmbeddedChannel(new RawFrameTap(writer));
            channel.writeOutbound(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)));
            BinaryWebSocketFrame forwarded = channel.readOutbound();
            byte[] result = new byte[forwarded.content().readableBytes()];
            forwarded.content().readBytes(result);
            assertArrayEquals(payload, result);
            forwarded.release();
            channel.finishAndReleaseAll();
        }
        JsonNode raw = new ObjectMapper().readTree(Files.readString(directory.resolve("raw-0001.ndjson")).trim());
        assertEquals("out", raw.path("direction").asText());
        assertEquals(Base64.getEncoder().encodeToString(payload), raw.path("base64").asText());
    }
}

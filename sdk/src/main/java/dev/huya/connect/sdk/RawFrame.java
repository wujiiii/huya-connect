package dev.huya.connect.sdk;

import java.time.Instant;
import java.util.Base64;

/** WebSocket application payload; live SDK messages are assembled before delivery. Not TLS/TCP packets. */
public record RawFrame(String roomId, String sessionId, String connectionId, long sequence,
                       Instant receivedAt, String direction, String frameType, String base64) {
    public byte[] payload() { return Base64.getDecoder().decode(base64); }
}

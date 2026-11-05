package dev.huya.connect.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Synchronous replay on the caller's thread. Never writes into the input directory. */
public final class Replay {
    private Replay() { }
    public static long read(Path directory, HuyaListener listener) throws IOException {
        ObjectMapper json = new ObjectMapper(); long count = 0;
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(p -> p.getFileName().toString().matches("raw-\\d+\\.ndjson")).sorted().toList();
        }
        for (Path file : files) {
            try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                for (String line; (line = input.readLine()) != null;) {
                    try {
                        JsonNode row = json.readTree(line);
                        var raw = new RawFrame(row.path("roomId").asText(), row.path("sessionId").asText("legacy"),
                                row.path("connectionId").asText("legacy"), row.path("sequence").asLong(),
                                Instant.parse(row.path("receivedAt").asText()), row.path("direction").asText("in"),
                                row.path("frameType").asText("BinaryWebSocketFrame"), row.path("base64").asText());
                        byte[] payload = raw.payload();
                        if (row.has("byteLength") && row.path("byteLength").asLong() != payload.length)
                            throw new IOException("Recorded byte length mismatch");
                        if (row.has("sha256") && !row.path("sha256").asText().equals(Recording.sha256(payload)))
                            throw new IOException("Recorded SHA-256 mismatch");
                        listener.onRawFrame(raw);
                        if (raw.frameType().equals("ContinuationWebSocketFrame")) {
                            listener.onEvent(new HuyaEvent.DecodeError(raw,
                                    "Legacy continuation frame has no final-fragment metadata; raw payload retained"));
                        } else {
                            for (HuyaEvent event : Protocol.decode(raw)) listener.onEvent(event);
                        }
                        count++;
                    } catch (RuntimeException e) { throw new IOException("Invalid replay record in " + file.getFileName(), e); }
                }
            }
        }
        return count;
    }
}

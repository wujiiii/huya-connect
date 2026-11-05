package dev.huya.connect;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

final class CaptureWriter implements AutoCloseable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path directory;
    private final String roomId;
    private final long maxRawSegmentBytes;
    private final BufferedWriter eventWriter;
    private BufferedWriter rawWriter;
    private int segment = 1;
    private long rawSegmentBytes;
    private long sequence;

    CaptureWriter(Path directory, String roomId, long maxRawSegmentBytes) throws IOException {
        if (maxRawSegmentBytes <= 0) {
            throw new IllegalArgumentException("maxRawSegmentBytes must be positive");
        }
        this.directory = directory;
        this.roomId = roomId;
        this.maxRawSegmentBytes = maxRawSegmentBytes;
        Files.createDirectories(directory);
        this.rawWriter = openRawSegment();
        this.eventWriter = Files.newBufferedWriter(directory.resolve("events.ndjson"), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    synchronized void recordRaw(byte[] payload) throws IOException {
        recordRaw("in", "BinaryWebSocketFrame", payload);
    }

    synchronized void recordRaw(String frameType, byte[] payload) throws IOException {
        recordRaw("in", frameType, payload);
    }

    synchronized void recordRaw(String direction, String frameType, byte[] payload) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("receivedAt", Instant.now().toString());
        record.put("roomId", roomId);
        record.put("sequence", ++sequence);
        record.put("direction", direction);
        record.put("frameType", frameType);
        record.put("byteLength", payload.length);
        record.put("base64", Base64.getEncoder().encodeToString(payload));
        String line = mapper.writeValueAsString(record) + System.lineSeparator();
        long lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
        if (rawSegmentBytes > 0 && rawSegmentBytes + lineBytes > maxRawSegmentBytes) {
            rawWriter.close();
            segment++;
            rawWriter = openRawSegment();
            rawSegmentBytes = 0;
        }
        rawWriter.write(line);
        rawWriter.flush();
        rawSegmentBytes += lineBytes;
    }

    synchronized void recordEvent(String type, Map<String, ?> data) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("receivedAt", Instant.now().toString());
        record.put("roomId", roomId);
        record.put("rawSequence", sequence);
        record.put("type", type);
        record.put("data", data);
        eventWriter.write(mapper.writeValueAsString(record));
        eventWriter.newLine();
        eventWriter.flush();
    }

    private BufferedWriter openRawSegment() throws IOException {
        return Files.newBufferedWriter(directory.resolve(String.format("raw-%04d.ndjson", segment)),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            rawWriter.close();
        } finally {
            eventWriter.close();
        }
    }
}

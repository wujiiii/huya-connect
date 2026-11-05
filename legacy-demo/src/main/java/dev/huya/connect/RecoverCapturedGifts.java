package dev.huya.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.WSPushMessage;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.base.BaseHuyaCmdMsg;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.dto.WSMsgItem;
import tech.ordinaryroad.live.chat.client.codec.huya.util.HuyaCodecUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Replays historical decode_error frames without changing the capture. */
public final class RecoverCapturedGifts {
    private RecoverCapturedGifts() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: RecoverCapturedGifts CAPTURE_DIRECTORY");
        }
        Path directory = Path.of(args[0]);
        ObjectMapper json = new ObjectMapper();
        Set<Long> errorSequences = new HashSet<>();
        try (BufferedReader input = Files.newBufferedReader(directory.resolve("events.ndjson"), StandardCharsets.UTF_8)) {
            for (String line; (line = input.readLine()) != null; ) {
                JsonNode event = json.readTree(line);
                if ("decode_error".equals(event.path("type").asText())) {
                    errorSequences.add(event.path("rawSequence").asLong());
                }
            }
        }

        long recovered = 0;
        try (BufferedWriter output = Files.newBufferedWriter(directory.resolve("recovered-gifts.ndjson"),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("raw-\\d+\\.ndjson"))
                    .sorted().toList()) {
                try (BufferedReader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    for (String line; (line = input.readLine()) != null; ) {
                        JsonNode frame = json.readTree(line);
                        long sequence = frame.path("sequence").asLong();
                        if (!errorSequences.contains(sequence)) {
                            continue;
                        }
                        byte[] payload = Base64.getDecoder().decode(frame.path("base64").asText());
                        for (var message : HuyaCodecUtil.decode(Unpooled.wrappedBuffer(payload))) {
                            if (!(message instanceof BaseHuyaCmdMsg command) || command.getLUri() != 6501) {
                                continue;
                            }
                            byte[] giftBytes = message instanceof WSMsgItem item ? item.getSMsg()
                                    : message instanceof WSPushMessage push ? push.getDataBytes() : null;
                            if (giftBytes == null) {
                                continue;
                            }
                            var gift = TolerantGiftDecoder.decode(giftBytes).message();
                            Map<String, Object> record = new LinkedHashMap<>();
                            record.put("receivedAt", frame.path("receivedAt").asText());
                            record.put("roomId", frame.path("roomId").asText());
                            record.put("rawSequence", sequence);
                            record.put("type", "gift_recovered");
                            record.put("decodeMode", "partial");
                            record.put("senderId", gift.getUid());
                            record.put("senderName", gift.getUsername());
                            record.put("giftId", gift.getGiftId());
                            record.put("giftName", gift.getGiftName());
                            record.put("count", gift.getGiftCount());
                            record.put("payTotalRaw", gift.getLPayTotal());
                            record.put("payId", gift.getStrPayId());
                            record.put("comboSequenceId", gift.getLComboSeqId());
                            record.put("payTypeRaw", gift.getIPayType());
                            output.write(json.writeValueAsString(record));
                            output.newLine();
                            recovered++;
                        }
                    }
                }
            }
        }
        System.out.println("Historical decode errors: " + errorSequences.size()
                + "; recovered gift records: " + recovered);
    }
}

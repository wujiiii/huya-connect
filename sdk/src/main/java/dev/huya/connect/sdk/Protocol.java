package dev.huya.connect.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.huya.connect.sdk.Jce.*;

/** Stateless decoder shared by live reception and replay. No network or filesystem access. */
public final class Protocol {
    static final String WEB_UA = "webh5&2611021517&websocket";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(32).maxNumberLength(100).maxStringLength(MAX_BYTES).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final JsonNode SCHEMA;
    static {
        try (var input = Protocol.class.getResourceAsStream("schemas.json")) {
            if (input == null) throw new IOException("Missing protocol schema");
            SCHEMA = new ObjectMapper().readTree(input);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private Protocol() { }
    public static List<HuyaEvent> decode(RawFrame raw) {
        List<HuyaEvent> result = new ArrayList<>();
        try {
            if (!raw.frameType().equals("BinaryWebSocketFrame")) return List.of();
            List<Field> fields = Jce.decode(raw.payload());
            int opcode = Math.toIntExact(number(fields, 0, -1));
            if (opcode < 0) throw new IllegalArgumentException("Missing command type");
            result.add(new HuyaEvent.Command(raw, opcode, id(fields, 2), fields));
            byte[] data = bytes(fields, 1);
            if (opcode == 7) {
                List<Field> push = Jce.decode(data);
                push(result, raw, number(push, 1, -1), text(push, 4, ""), id(push, 5), bytes(push, 2));
            } else if (opcode == 22) {
                List<Field> push = Jce.decode(data);
                String group = text(push, 0, "");
                Field vector = find(push, 1);
                if (vector == null || vector.type() != 9) throw new IllegalArgumentException("Missing V2 vector");
                for (Field item : vector.fields()) {
                    try {
                        if (item.type() != 10) throw new IllegalArgumentException("Invalid V2 item");
                        List<Field> body = item.fields();
                        push(result, raw, number(body, 0, -1), group, id(body, 2), bytes(body, 1));
                    } catch (RuntimeException e) { result.add(new HuyaEvent.DecodeError(raw, e.getMessage())); }
                }
            } else if (opcode == 4) {
                snapshot(result, raw, data);
            }
        } catch (RuntimeException e) { result.add(new HuyaEvent.DecodeError(raw, e.getMessage())); }
        return List.copyOf(result);
    }
    private static void push(List<HuyaEvent> result, RawFrame raw, long uri, String group, String id, byte[] payload) {
        if (uri < 0) throw new IllegalArgumentException("Missing push URI");
        String name = uri == 3102 ? "SubscribeCountNotice" : SCHEMA.path("uris").path(Long.toString(uri)).asText(null);
        List<Field> jce = List.of(); Map<String, Object> named = Map.of(); String error = null;
        try {
            // URI 3102 carries JSON inside the JCE push envelope.
            if (uri == 3102) named = subscriptionCount(payload);
            else { jce = Jce.decode(payload); named = project(jce, name); }
        } catch (IOException | RuntimeException e) {
            error = uri == 3102 ? "Invalid URI 3102 subscription-count JSON payload" : e.getMessage();
        }
        var message = new HuyaEvent.Message(raw, uri, group, id, name,
                Base64.getEncoder().encodeToString(payload), jce, named, error);
        result.add(message);
        if (error != null) { result.add(new HuyaEvent.DecodeError(raw, error)); return; }
        if (uri > Integer.MAX_VALUE) return;
        try {
            switch ((int) uri) {
                case 1400 -> {
                    Field user = find(jce, 0);
                    List<Field> u = user != null && user.type() == 10 ? user.fields() : List.of();
                    result.add(new HuyaEvent.Danmu(message, id(u, 0), text(u, 2, null), text(jce, 3, null)));
                }
                case 6501 -> result.add(new HuyaEvent.Gift(message, id(jce, 3), id(jce, 4), text(jce, 6, null),
                        optionalNumber(jce, 0), text(jce, 20, null), optionalNumber(jce, 2), optionalNumber(jce, 8),
                        optionalNumber(jce, 9), id(jce, 39), text(jce, 1, null), optionalNumber(jce, 27), optionalNumber(jce, 41)));
                case 6110 -> result.add(new HuyaEvent.Entry(message));
                case 1020001 -> result.add(new HuyaEvent.Guardian(message));
                case 8000, 8001 -> result.add(new HuyaEvent.Live(message, uri == 8000,
                        named.containsKey("lPresenterUid") ? named.get("lPresenterUid").toString() : null));
                default -> { }
            }
        } catch (RuntimeException e) { result.add(new HuyaEvent.DecodeError(raw, "Business projection: " + e.getMessage())); }
    }
    private static Map<String, Object> subscriptionCount(byte[] payload) throws IOException {
        if (payload.length > MAX_BYTES) throw new IllegalArgumentException("JSON byte limit");
        JsonNode object = JSON.readTree(payload);
        if (object == null || !object.isObject()) throw new IllegalArgumentException("Expected JSON object");
        JsonNode pid = object.path("pid"), count = object.path("subscribe_count");
        if (!pid.isIntegralNumber() || pid.bigIntegerValue().signum() <= 0 || pid.bigIntegerValue().bitLength() > 64
                || !count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0)
            throw new IllegalArgumentException("Invalid subscription-count fields");
        return Map.of("pid", pid.asText(), "subscribe_count", count.longValue());
    }
    private static Long optionalNumber(List<Field> fields, int tag) {
        return find(fields, tag) == null ? null : number(fields, tag, 0);
    }
    private static Map<String, Object> project(List<Field> fields, String name) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (name == null) return result;
        for (JsonNode spec : SCHEMA.path("structs").path(name)) {
            Field f = find(fields, spec.path("tag").asInt());
            if (f != null) {
                String key = spec.path("name").asText();
                Object value = f.value();
                if (spec.path("kind").asText().equals("Int64") && key.matches(".*(Uid|Pid|Sid|Tid|Id)$"))
                    value = id(fields, f.tag());
                if (value != null) result.put(key, value);
            }
        }
        return Map.copyOf(result);
    }
    @SuppressWarnings("unchecked")
    private static void snapshot(List<HuyaEvent> result, RawFrame raw, byte[] data) {
        if (data.length < 4 || ByteBuffer.wrap(data).getInt() != data.length)
            throw new IllegalArgumentException("Invalid WUP length");
        List<Field> header = Jce.decode(java.util.Arrays.copyOfRange(data, 4, data.length));
        List<Field> attributes = Jce.decode(bytes(header, 7));
        List<Field> response = List.of();
        Field map = find(attributes, 0);
        if (map != null && map.type() == 8) {
            for (Entry entry : (List<Entry>) map.value()) {
                if (entry.key().text().equals("tRsp") && entry.value().type() == 13) {
                    List<Field> body = Jce.decode(Base64.getDecoder().decode(entry.value().text()));
                    response = body.size() == 1 && body.get(0).type() == 10 ? body.get(0).fields() : body;
                }
            }
        }
        String function = text(header, 6, "");
        // Return-code tags are response-specific; banner tag 0 is a vector, not a status.
        Long returnCode = function.equals("getResourceInfo") ? optionalNumber(response, 2) : null;
        result.add(new HuyaEvent.Snapshot(raw, id(header, 4), function, returnCode, header, attributes, response));
    }
    static byte[] command(int opcode, byte[] data, long request) {
        return concat(integer(0, opcode), blob(1, data), integer(2, request), string(3, ""),
                integer(4, 0), integer(5, 0), string(6, ""));
    }
    static byte[] groups(List<String> groups, long request) {
        byte[] items = new byte[0];
        for (String group : groups) items = concat(items, string(0, group));
        return command(16, concat(head(0, 9), integer(0, groups.size()), items, string(1, "")), request);
    }
    static byte[] map(int tag, byte[]... entries) {
        return concat(head(tag, 8), integer(0, entries.length / 2), concat(entries));
    }
    static URI connectUrl(URI endpoint, String guid) {
        byte[] custom = map(10, string(0, "HUYA_NET"), string(1, "0"), string(0, "HUYA_VSDKUA"), string(1, WEB_UA));
        byte[] base = concat(integer(0, 0), string(1, guid), string(2, WEB_UA), string(3, "HUYA&ZH&2052"),
                string(4, ""), string(5, ""), integer(6, 0), string(7, ""), string(8, ""), string(9, ""), custom);
        return URI.create(endpoint + "?baseinfo=" + URLEncoder.encode(Base64.getEncoder().encodeToString(base), StandardCharsets.UTF_8));
    }
    static byte[] user(String guid) {
        return concat(head(0, 10), integer(0, 0), string(1, guid), string(2, ""), string(3, WEB_UA),
                string(4, ""), integer(5, 0), string(6, ""), string(7, ""), head(0, 11));
    }
    static byte[] wup(String function, byte[] fields, int request) {
        byte[] attrs = map(0, string(0, "tReq"), blob(1, concat(head(0, 10), fields, head(0, 11))));
        byte[] header = concat(integer(1, 3), integer(2, 0), integer(3, 0), integer(4, request),
                string(5, "wupui"), string(6, function), blob(7, attrs), integer(8, 5000), map(9), map(10));
        return command(3, concat(ByteBuffer.allocate(4).putInt(header.length + 4).array(), header), request);
    }
}

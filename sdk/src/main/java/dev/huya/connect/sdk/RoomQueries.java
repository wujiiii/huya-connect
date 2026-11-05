package dev.huya.connect.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static dev.huya.connect.sdk.Jce.*;

/** Allowlisted read-only room query codecs, separate from realtime business models. */
final class RoomQueries {
    enum Kind {
        FAN_CLUB("getFansScoreRankPanel"), GUARDIANS("getGuardianCount"), FOLLOWERS("getSubscribeStatus"),
        LIVE("getLivingInfo"), GIFTS("getResourceInfo");
        final String function;
        Kind(String function) { this.function = function; }
    }
    private RoomQueries() { }
    static byte[] request(Kind kind, String guid, String uid, String roomId, int requestId) {
        byte[] user = Protocol.user(guid);
        byte[] body = switch (kind) {
            case FAN_CLUB -> concat(user, integer(1, Long.parseLong(uid)), integer(2, 0), integer(3, 0));
            case GUARDIANS -> concat(user, integer(1, Long.parseLong(uid)));
            case LIVE -> concat(user, integer(1, 0), integer(2, 0), integer(3, Long.parseLong(uid)),
                    string(4, "web"), string(5, ""), integer(6, Long.parseLong(roomId)), integer(7, 0), integer(8, 0));
            case GIFTS -> concat(user, string(1, "web"), string(2, ""), integer(3, Long.parseLong(uid)));
            case FOLLOWERS -> throw new IllegalArgumentException("Follower query uses the public HTTP endpoint");
        };
        return Protocol.wup(kind.function, body, requestId);
    }
    static Object parse(Kind kind, HuyaEvent.Snapshot snapshot, String uid) {
        if (!kind.function.equals(snapshot.function())) throw new IllegalArgumentException("Unexpected query function");
        // Error replies can contain only status metadata, without tRsp. Do not reinterpret them as zero.
        Field status = find(snapshot.header(), 10);
        if (status != null && status.type() == 8) {
            @SuppressWarnings("unchecked") var entries = (List<Entry>) status.value();
            for (Entry entry : entries) {
                if (entry.key().text().equals("STATUS_RESULT_CODE") && !entry.value().text().equals("0"))
                    throw new IllegalArgumentException("WUP query rejected: " + entry.value().text());
            }
        }
        List<Field> response = snapshot.response();
        if (response.isEmpty()) throw new IllegalArgumentException("Query response missing tRsp");
        return switch (kind) {
            case FAN_CLUB -> {
                if (!uid.equals(identifier(response, 2))) throw new IllegalArgumentException("Fan-club presenter mismatch");
                yield count(response, 5);
            }
            case GUARDIANS -> count(response, 0);
            case LIVE -> live(response, uid);
            case GIFTS -> catalog(response);
            case FOLLOWERS -> throw new IllegalArgumentException("Follower query is HTTP");
        };
    }
    static long count(List<Field> fields, int tag) {
        Long raw = optionalNumber(fields, tag);
        if (raw == null) throw new IllegalArgumentException("Count field missing");
        long value = raw;
        if (value < 0) throw new IllegalArgumentException("Invalid negative count");
        return value;
    }
    static Long optionalNumber(List<Field> fields, int tag) {
        if (fields.stream().filter(f -> f.tag() == tag).count() > 1) throw new IllegalArgumentException("Duplicate query field " + tag);
        return find(fields, tag) == null ? null : number(fields, tag, 0);
    }
    private static String identifier(List<Field> fields, int tag) {
        Long raw = optionalNumber(fields, tag);
        return raw == null ? null : Long.toUnsignedString(raw);
    }
    static HuyaRoomInfo.LiveInfo live(List<Field> response, String uid) {
        Long living = optionalNumber(response, 0);
        if (living == null || (living != 0 && living != 1)) throw new IllegalArgumentException("Live state missing or invalid");
        Field notice = find(response, 1);
        if (notice == null || notice.type() != 10 || !uid.equals(identifier(notice.fields(), 0)))
            throw new IllegalArgumentException("Live presenter identity missing or mismatched");
        Long replayRaw = optionalNumber(notice.fields(), 39);
        if (replayRaw != null && replayRaw != 0 && replayRaw != 1) throw new IllegalArgumentException("Invalid replay flag");
        Boolean replay = replayRaw == null ? null : replayRaw == 1;
        HuyaRoomInfo.LiveState state = living == 0 || Boolean.TRUE.equals(replay) ? HuyaRoomInfo.LiveState.OFFLINE
                : replay == null ? HuyaRoomInfo.LiveState.UNKNOWN : HuyaRoomInfo.LiveState.LIVE;
        return new HuyaRoomInfo.LiveInfo(state, replay, identifier(notice.fields(), 7), optionalNumber(notice.fields(), 24));
    }
    static HuyaRoomInfo.GiftCatalog catalog(List<Field> response) {
        Long code = optionalNumber(response, 2), full = optionalNumber(response, 4);
        if (code == null || code != 0)
            throw new IllegalArgumentException("Resource query failed or return code missing");
        if (full == null || full != 1)
            throw new IllegalArgumentException("Resource response is not a full directory");
        var gifts = new LinkedHashMap<String, HuyaRoomInfo.GiftItem>();
        for (Field group : vector(response, 0)) {
            if (number(group.fields(), 0, -1) != 1) continue;
            for (Field item : vector(group.fields(), 1)) {
                if (number(item.fields(), 1, -1) != 1) continue;
                List<Field> fields = Jce.decode(bytes(item.fields(), 2));
                String giftId = identifier(fields, 0);
                if (giftId == null) throw new IllegalArgumentException("Gift ID missing");
                Long type = optionalNumber(fields, 8);
                var gift = new HuyaRoomInfo.GiftItem(giftId, type == null ? null : Math.toIntExact(type),
                        text(fields, 1, null), optionalNumber(fields, 2), fields);
                if (gifts.putIfAbsent(giftId, gift) != null) throw new IllegalArgumentException("Duplicate gift ID in full directory");
            }
        }
        return new HuyaRoomInfo.GiftCatalog(gifts, text(response, 1, null));
    }
    private static List<Field> vector(List<Field> fields, int tag) {
        Field field = find(fields, tag);
        if (field == null || field.type() != 9) throw new IllegalArgumentException("Resource vector missing");
        for (Field item : field.fields()) if (item.type() != 10) throw new IllegalArgumentException("Invalid resource item");
        return field.fields();
    }
    static long followers(byte[] response) throws IOException {
        if (response.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Follower response byte limit");
        if (response.length >= 2 && response[0] == (byte) 0x1f && response[1] == (byte) 0x8b) {
            try (var stream = new GZIPInputStream(new ByteArrayInputStream(response))) {
                response = stream.readNBytes(4 * 1024 * 1024 + 1);
            }
            if (response.length > 4 * 1024 * 1024) throw new IllegalArgumentException("Follower JSON byte limit");
        }
        var factory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(4 * 1024 * 1024).maxNumberLength(100).build()).build();
        var json = new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(response);
        if (json == null || !json.isObject() || !json.path("result").isIntegralNumber() || json.path("result").bigIntegerValue().signum() != 0)
            throw new IllegalArgumentException("Follower query rejected or result missing");
        var count = json.path("subscribe_count");
        if (!count.isIntegralNumber() || !count.canConvertToLong() || count.longValue() < 0)
            throw new IllegalArgumentException("Follower count missing or invalid");
        return count.longValue();
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

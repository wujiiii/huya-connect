package dev.huya.connect.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static dev.huya.connect.sdk.Jce.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    static RawFrame raw(byte[] bytes) {
        return new RawFrame("123", "session", "connection", 1, Instant.EPOCH, "in", "BinaryWebSocketFrame",
                Base64.getEncoder().encodeToString(bytes));
    }
    static byte[] v1(long uri, long id, byte[] body) {
        return Protocol.command(7, concat(integer(1, uri), blob(2, body), string(4, "chat:123"), integer(5, id)), 0);
    }
    @Test void matchesFrozenOfficialCodecBytes() throws Exception {
        var fixture = new ObjectMapper().readTree(getClass().getResourceAsStream("/codec-fixture.json"));
        byte[] actual = Protocol.wup("getResourceInfo", concat(Protocol.user("unit-guid"), string(1, "web"),
                string(2, ""), integer(3, 123)), 42);
        assertArrayEquals(Base64.getDecoder().decode(fixture.path("packet_b64").asText()), actual);
    }
    @Test void integerBoundariesAndDuplicateTagsAndMapKeysSurvive() {
        for (long value : new long[]{Long.MIN_VALUE, -129, -128, 0, 127, 128, 32768, Integer.MAX_VALUE, Long.MAX_VALUE})
            assertEquals(value, Jce.decode(integer(17, value)).get(0).number());
        assertEquals(2, Jce.decode(concat(integer(0, 1), integer(0, 2))).size());
        var map = Jce.decode(Protocol.map(0, integer(0, 7), string(1, "a"), integer(0, 7), string(1, "b"))).get(0);
        assertEquals(2, ((List<?>) map.value()).size());
        assertEquals("字".repeat(300), Jce.decode(string(0, "字".repeat(300))).get(0).text());
    }
    @Test void v2BadBusinessPayloadDoesNotLoseGoodSiblingsOrUnknownUri() {
        byte[] bad = concat(head(0, 10), integer(0, 6501), blob(1, new byte[]{(byte) 0xff}), integer(2, 1), head(0, 11));
        byte[] good = concat(head(0, 10), integer(0, 999999), blob(1, integer(17, 123)), integer(2, -1), head(0, 11));
        List<HuyaEvent> events = Protocol.decode(raw(Protocol.command(22,
                concat(string(0, "chat:123"), head(1, 9), integer(0, 2), bad, good), 0)));
        var messages = events.stream().filter(HuyaEvent.Message.class::isInstance).map(HuyaEvent.Message.class::cast).toList();
        assertEquals(2, messages.size()); assertNotNull(messages.get(0).decodeError());
        assertEquals(999999, messages.get(1).uri()); assertNull(messages.get(1).name());
        assertEquals("18446744073709551615", messages.get(1).messageId());
        assertEquals(17, messages.get(1).jce().get(0).tag());
    }
    @Test void typedGiftKeepsAmountsAndUnrelatedRecipientWithoutFiltering() {
        var events = Protocol.decode(raw(v1(6501, 99, concat(integer(0, 1), string(1, ""), integer(2, 3),
                integer(3, 999), integer(4, 123), string(6, "anonymous"), integer(8, 3), integer(9, 2),
                string(20, "gift"), integer(27, 8), integer(39, Long.MAX_VALUE), integer(41, 300)))));
        var gift = events.stream().filter(HuyaEvent.Gift.class::isInstance).map(HuyaEvent.Gift.class::cast).findFirst().orElseThrow();
        assertEquals("999", gift.presenterUid()); assertEquals(300L, gift.payTotalRaw());
        assertEquals(3L, gift.count()); assertEquals(8L, gift.payTypeRaw()); assertEquals("", gift.payId());
    }
    @Test void allCommonBusinessModelsKeepTheirMessage() {
        for (long uri : new long[]{1400, 6110, 1020001, 8000, 8001}) {
            var events = Protocol.decode(raw(v1(uri, 1, integer(0, 123))));
            assertTrue(events.stream().anyMatch(HuyaEvent.Business.class::isInstance), "uri=" + uri);
        }
    }
    @Test void rejectsMalformedAndResourceExhaustionWithoutThrowingAwayRaw() {
        assertThrows(IllegalArgumentException.class, () -> Jce.decode(new byte[]{11}));
        assertThrows(IllegalArgumentException.class, () -> Jce.decode(concat(head(0, 9), integer(0, 100001))));
        assertThrows(IllegalArgumentException.class, () -> Jce.decode(new byte[MAX_BYTES + 1]));
        byte[] nested = integer(0, 1);
        for (int i = 0; i < 34; i++) nested = concat(head(0, 10), nested, head(0, 11));
        byte[] deep = nested;
        assertThrows(IllegalArgumentException.class, () -> Jce.decode(deep));
        var frame = raw(new byte[]{(byte) 0xff});
        assertEquals(frame, Protocol.decode(frame).get(0).raw());
        assertInstanceOf(HuyaEvent.DecodeError.class, Protocol.decode(frame).get(0));
    }
    @Test void snapshotKeepsRequestCodeAndResponseAndChecksLength() {
        byte[] attrs = Protocol.map(0, string(0, "tRsp"), blob(1, concat(head(0, 10), string(1, "value"), integer(2, 0), head(0, 11))));
        byte[] header = concat(integer(1, 3), integer(4, 100), string(6, "getResourceInfo"), blob(7, attrs));
        byte[] data = concat(java.nio.ByteBuffer.allocate(4).putInt(header.length + 4).array(), header);
        var snapshot = Protocol.decode(raw(Protocol.command(4, data, 100))).stream()
                .filter(HuyaEvent.Snapshot.class::isInstance).map(HuyaEvent.Snapshot.class::cast).findFirst().orElseThrow();
        assertEquals("100", snapshot.requestId()); assertEquals(0L, snapshot.returnCode());
        assertEquals(2, snapshot.response().size());
        assertTrue(Protocol.decode(raw(Protocol.command(4, new byte[]{0, 0, 0, 4, 0}, 0)))
                .stream().anyMatch(HuyaEvent.DecodeError.class::isInstance));
    }
    @Test void roomResolutionRejectsRecommendationsAndMissingIdentity() throws Exception {
        assertEquals("123", HuyaClient.resolveHtml("456", "var TT_PROFILE_INFO={\"lp\":123,\"profileRoom\":456};var tail=1;"));
        assertThrows(java.io.IOException.class, () -> HuyaClient.resolveHtml("456", "TT_PROFILE_INFO={\"lp\":123,\"profileRoom\":789};"));
        assertThrows(java.io.IOException.class, () -> HuyaClient.resolveHtml("456", "no metadata"));
    }
    @Test void wideUnknownUriCannotAliasCommonBusinessEvent() {
        var events = Protocol.decode(raw(v1((1L << 32) + 6501, 1, integer(0, 123))));
        assertTrue(events.stream().anyMatch(HuyaEvent.Message.class::isInstance));
        assertFalse(events.stream().anyMatch(HuyaEvent.Gift.class::isInstance));
    }
    @Test void frozenSchemaCarriesExpectedProvenance() throws Exception {
        var schema = new ObjectMapper().readTree(Protocol.class.getResourceAsStream("schemas.json"));
        assertEquals(133, schema.path("uris").size());
        assertEquals(767, schema.path("structs").size());
        assertEquals("90777c1fb1fac341329b8fe596348aecc8a4ba1a8baf4be76d8d6cd1fc760372", schema.path("source").path("sha256").asText());
    }
    @Test void subscriptionCountJsonDecodesInV1AndV2WithoutChangingRaw() {
        byte[] json = "{\"pid\":9007199254740993,\"subscribe_count\":12345}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] item = concat(head(0, 10), integer(0, 3102), blob(1, json), integer(2, 99), head(0, 11));
        for (byte[] packet : List.of(v1(3102, 99, json), Protocol.command(22,
                concat(string(0, "chat:123"), head(1, 9), integer(0, 1), item), 0))) {
            var events = Protocol.decode(raw(packet));
            var message = events.stream().filter(HuyaEvent.Message.class::isInstance)
                    .map(HuyaEvent.Message.class::cast).findFirst().orElseThrow();
            assertNull(message.decodeError());
            assertEquals("SubscribeCountNotice", message.name());
            assertEquals("9007199254740993", message.fields().get("pid"));
            assertEquals(12345L, message.fields().get("subscribe_count"));
            assertTrue(message.jce().isEmpty());
            assertArrayEquals(json, Base64.getDecoder().decode(message.payloadBase64()));
            assertFalse(events.stream().anyMatch(HuyaEvent.DecodeError.class::isInstance));
        }
    }
    @Test void invalidSubscriptionJsonStillReportsErrorAndIsNotUsedAsGenericFallback() {
        for (String body : List.of("{", "[]", "null", "{}", "{\"pid\":123,\"subscribe_count\":-1}",
                "{\"pid\":123,\"subscribe_count\":1.5}", "{\"pid\":0,\"subscribe_count\":1}",
                "{\"pid\":123,\"subscribe_count\":9223372036854775808}",
                "{\"pid\":123,\"subscribe_count\":1} {}", "{\"pid\":123,\"pid\":456,\"subscribe_count\":1}")) {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var events = Protocol.decode(raw(v1(3102, 1, bytes)));
            var message = events.stream().filter(HuyaEvent.Message.class::isInstance)
                    .map(HuyaEvent.Message.class::cast).findFirst().orElseThrow();
            assertNotNull(message.decodeError(), body);
            assertArrayEquals(bytes, Base64.getDecoder().decode(message.payloadBase64()));
            assertTrue(events.stream().anyMatch(HuyaEvent.DecodeError.class::isInstance));
        }
        byte[] json = "{\"pid\":123,\"subscribe_count\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(Protocol.decode(raw(v1(999999, 1, json))).stream().anyMatch(HuyaEvent.DecodeError.class::isInstance));
        assertThrows(IllegalArgumentException.class, () -> Jce.decode(json));
    }
}

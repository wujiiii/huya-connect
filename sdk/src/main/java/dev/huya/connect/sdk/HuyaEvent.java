package dev.huya.connect.sdk;

import java.util.List;
import java.util.Map;

/** All events reference the exact raw application message, including outgoing/unknown commands. */
public interface HuyaEvent {
    RawFrame raw();

    record Message(RawFrame raw, long uri, String group, String messageId, String name,
                   String payloadBase64, List<Jce.Field> jce, Map<String, Object> fields,
                   String decodeError) implements HuyaEvent {
        public Message { jce = List.copyOf(jce); fields = Map.copyOf(fields); }
    }
    record Command(RawFrame raw, int opcode, String requestId, List<Jce.Field> jce) implements HuyaEvent { }
    record Snapshot(RawFrame raw, String requestId, String function, Long returnCode,
                    List<Jce.Field> header, List<Jce.Field> attributes, List<Jce.Field> response) implements HuyaEvent { }
    record DecodeError(RawFrame raw, String reason) implements HuyaEvent { }

    interface Business extends HuyaEvent {
        Message message();
        default RawFrame raw() { return message().raw(); }
    }
    record Danmu(Message message, String senderUid, String senderName, String content) implements Business { }
    record Gift(Message message, String presenterUid, String senderUid, String senderName,
                Long giftId, String giftName, Long count, Long countByGroup, Long group,
                String comboSequenceId, String payId, Long payTypeRaw, Long payTotalRaw) implements Business { }
    record Entry(Message message) implements Business { }
    record Guardian(Message message) implements Business { }
    record Live(Message message, boolean live, String presenterUid) implements Business { }
}

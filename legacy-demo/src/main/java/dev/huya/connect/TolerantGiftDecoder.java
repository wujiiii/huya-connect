package dev.huya.connect;

import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.exc.TarsDecodeException;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.SendItemSubBroadcastPacketMsg;
import tech.ordinaryroad.live.chat.client.codec.huya.util.HuyaCodecUtil;

/** Keeps the stable gift fields when the upstream effect-data schema changes. */
final class TolerantGiftDecoder {
    private TolerantGiftDecoder() {
    }

    static Result decode(byte[] payload) {
        try {
            return new Result(new SendItemSubBroadcastPacketMsg(HuyaCodecUtil.newUtf8TarsInputStream(payload)), false);
        } catch (TarsDecodeException originalFailure) {
            TarsInputStream input = HuyaCodecUtil.newUtf8TarsInputStream(payload);
            RecoveredGiftMessage message = new RecoveredGiftMessage();
            message.setIItemType(input.read(0, 0, false));
            message.setStrPayId(input.read("", 1, false));
            message.setIItemCount(input.read(0, 2, false));
            message.setLPresenterUid(input.read(0L, 3, false));
            message.setLSenderUid(input.read(0L, 4, false));
            message.setSPresenterNick(input.read("", 5, false));
            message.setSSenderNick(input.read("", 6, false));
            message.setSSendContent(input.read("", 7, false));
            message.setSPropsName(input.read("", 20, false));
            message.setLRoomId(input.read(0L, 24, false));
            message.setIPayType(input.read(-1, 27, false));
            message.setSCustomText(input.read("", 37, false));
            message.setLComboSeqId(input.read(0L, 39, false));
            message.setLPayTotal(input.read(0L, 41, false));
            return new Result(message, true);
        }
    }

    record Result(SendItemSubBroadcastPacketMsg message, boolean recovered) {
    }

    static final class RecoveredGiftMessage extends SendItemSubBroadcastPacketMsg {
    }
}

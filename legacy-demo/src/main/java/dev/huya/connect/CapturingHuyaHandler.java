package dev.huya.connect;

import tech.ordinaryroad.live.chat.client.codec.huya.api.HuyaApis;
import tech.ordinaryroad.live.chat.client.codec.huya.constant.HuyaCmdEnum;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.SendItemSubBroadcastPacketMsg;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.WSPushMessage;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.dto.PropsItem;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.dto.WSMsgItem;
import tech.ordinaryroad.live.chat.client.commons.base.msg.ICmdMsg;
import tech.ordinaryroad.live.chat.client.huya.client.HuyaLiveChatClient;
import tech.ordinaryroad.live.chat.client.huya.listener.IHuyaMsgListener;
import tech.ordinaryroad.live.chat.client.huya.netty.handler.HuyaBinaryFrameHandler;

import java.util.List;

final class CapturingHuyaHandler extends HuyaBinaryFrameHandler {
    CapturingHuyaHandler(List<IHuyaMsgListener> listeners, HuyaLiveChatClient client) {
        super(listeners, client);
    }

    @Override
    public void onCmdMsg(HuyaCmdEnum command, ICmdMsg<HuyaCmdEnum> commandMessage) {
        if (command != HuyaCmdEnum.SendItemSubBroadcastPacket) {
            super.onCmdMsg(command, commandMessage);
            return;
        }
        byte[] payload;
        if (commandMessage instanceof WSMsgItem item) {
            payload = item.getSMsg();
        } else if (commandMessage instanceof WSPushMessage push) {
            payload = push.getDataBytes();
        } else {
            super.onCmdMsg(command, commandMessage);
            return;
        }
        SendItemSubBroadcastPacketMsg gift = TolerantGiftDecoder.decode(payload).message();
        gift.setPropsItem(HuyaApis.GIFT_ITEMS.getOrDefault(gift.getIItemType(), PropsItem.DEFAULT));
        iteratorMsgListeners(listener -> listener.onGiftMsg(this, gift));
    }
}

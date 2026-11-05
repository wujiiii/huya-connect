package dev.huya.connect;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.io.IOException;

final class RawFrameTap extends ChannelDuplexHandler {
    private final CaptureWriter writer;

    RawFrameTap(CaptureWriter writer) {
        this.writer = writer;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        capture(context, "in", message);
        context.fireChannelRead(message);
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        capture(context, "out", message);
        context.write(message, promise);
    }

    private void capture(ChannelHandlerContext context, String direction, Object message) throws IOException {
        if (message instanceof WebSocketFrame frame) {
            byte[] payload = new byte[frame.content().readableBytes()];
            frame.content().getBytes(frame.content().readerIndex(), payload);
            try {
                writer.recordRaw(direction, frame.getClass().getSimpleName(), payload);
            } catch (IOException failure) {
                context.close();
                throw failure;
            }
        }
    }
}

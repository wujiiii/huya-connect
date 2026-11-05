package dev.huya.connect;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class DecodeErrorHandler extends ChannelInboundHandlerAdapter {
    private final CaptureWriter writer;

    DecodeErrorHandler(CaptureWriter writer) {
        this.writer = writer;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", cause.getClass().getSimpleName());
        String message = cause.getMessage();
        data.put("message", message == null ? "" : message.substring(0, Math.min(message.length(), 300)));
        try {
            writer.recordEvent("decode_error", data);
        } catch (IOException writeFailure) {
            context.close();
            throw writeFailure;
        }
    }
}

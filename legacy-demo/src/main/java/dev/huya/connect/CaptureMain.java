package dev.huya.connect;

import io.netty.channel.socket.SocketChannel;
import tech.ordinaryroad.live.chat.client.codec.huya.constant.HuyaCmdEnum;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.MessageNoticeMsg;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.SendItemSubBroadcastPacketMsg;
import tech.ordinaryroad.live.chat.client.commons.base.msg.ICmdMsg;
import tech.ordinaryroad.live.chat.client.commons.base.msg.IMsg;
import tech.ordinaryroad.live.chat.client.huya.client.HuyaLiveChatClient;
import tech.ordinaryroad.live.chat.client.huya.config.HuyaLiveChatClientConfig;
import tech.ordinaryroad.live.chat.client.huya.listener.IHuyaMsgListener;
import tech.ordinaryroad.live.chat.client.huya.netty.handler.HuyaBinaryFrameHandler;
import tech.ordinaryroad.live.chat.client.huya.netty.handler.HuyaCodecHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CaptureMain {
    private static final long MAX_RAW_SEGMENT_BYTES = 64L * 1024 * 1024;

    private CaptureMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Path output = options.output != null ? options.output
                : Path.of("captures", options.roomId + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                        .withZone(java.time.ZoneOffset.UTC).format(Instant.now()));
        CaptureWriter writer = new CaptureWriter(output, options.roomId, MAX_RAW_SEGMENT_BYTES);
        AtomicBoolean running = new AtomicBoolean(true);
        HuyaLiveChatClientConfig config = HuyaLiveChatClientConfig.builder()
                .roomId(options.roomId)
                .build();
        CapturingClient client = new CapturingClient(config, writer);
        client.addMsgListener(new CaptureListener(writer));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running.set(false);
            client.destroy();
            try {
                writer.close();
            } catch (IOException ignored) {
                // All records are flushed as they are written.
            }
        }, "capture-shutdown"));

        writer.recordEvent("session_start", Map.of("roomId", options.roomId, "output", output.toAbsolutePath().toString()));
        System.out.println("Capture directory: " + output.toAbsolutePath());
        System.out.println("Anonymous room " + options.roomId + "; create STOP in the capture directory to stop.");
        client.connect(() -> {
            record(writer, "websocket_handshake", Map.of("status", "connected"));
            System.out.println("WebSocket handshake complete; waiting for room messages...");
        });

        long deadline = options.durationSeconds == 0 ? Long.MAX_VALUE
                : System.currentTimeMillis() + options.durationSeconds * 1000L;
        while (running.get() && System.currentTimeMillis() < deadline) {
            if (Files.exists(output.resolve("STOP"))) {
                Files.delete(output.resolve("STOP"));
                break;
            }
            Thread.sleep(1000);
        }
        writer.recordEvent("session_end", Map.of("reason", "requested_or_duration_elapsed"));
        running.set(false);
        client.destroy();
        writer.close();
        System.out.println("Capture stopped: " + output.toAbsolutePath());
    }

    private static void record(CaptureWriter writer, String type, Map<String, ?> data) {
        try {
            writer.recordEvent(type, data);
        } catch (IOException failure) {
            throw new UncheckedIOException("无法写入采集数据", failure);
        }
    }

    private static final class CapturingClient extends HuyaLiveChatClient {
        private final CaptureWriter writer;

        private CapturingClient(HuyaLiveChatClientConfig config, CaptureWriter writer) {
            super(config);
            this.writer = writer;
        }

        @Override
        protected void initChannel(SocketChannel channel) {
            channel.pipeline().addLast("rawFrameTap", new RawFrameTap(writer));
            channel.pipeline().addLast("huyaCodec", new HuyaCodecHandler());
            channel.pipeline().addLast("huyaMessages", new CapturingHuyaHandler(getMsgListeners(), this));
            channel.pipeline().addLast("decodeErrors", new DecodeErrorHandler(writer));
        }
    }

    private static final class CaptureListener implements IHuyaMsgListener {
        private final CaptureWriter writer;

        private CaptureListener(CaptureWriter writer) {
            this.writer = writer;
        }

        @Override
        public void onDanmuMsg(HuyaBinaryFrameHandler handler, MessageNoticeMsg message) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", message.getUid());
            data.put("userName", message.getUsername());
            data.put("content", message.getContent());
            record(writer, "danmu", data);
        }

        @Override
        public void onGiftMsg(HuyaBinaryFrameHandler handler, SendItemSubBroadcastPacketMsg message) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("senderId", message.getUid());
            data.put("senderName", message.getUsername());
            data.put("giftId", message.getGiftId());
            data.put("giftName", message.getGiftName());
            data.put("count", message.getGiftCount());
            data.put("payTotalRaw", message.getLPayTotal());
            data.put("payId", message.getStrPayId());
            data.put("comboSequenceId", message.getLComboSeqId());
            data.put("payTypeRaw", message.getIPayType());
            data.put("customText", message.getSCustomText());
            boolean recovered = message instanceof TolerantGiftDecoder.RecoveredGiftMessage;
            data.put("decodeMode", recovered ? "partial" : "full");
            record(writer, recovered ? "gift_recovered" : "gift", data);
        }

        @Override
        public void onOtherCmdMsg(HuyaBinaryFrameHandler handler, HuyaCmdEnum command,
                                  ICmdMsg<HuyaCmdEnum> message) {
            record(writer, "other_command", Map.of("command", command.name(), "code", command.getCode()));
        }

        @Override
        public void onUnknownCmd(HuyaBinaryFrameHandler handler, String command, IMsg message) {
            record(writer, "unknown_command", Map.of("command", command));
        }
    }

    private static final class Options {
        private final String roomId;
        private final Path output;
        private final long durationSeconds;

        private Options(String roomId, Path output, long durationSeconds) {
            this.roomId = roomId;
            this.output = output;
            this.durationSeconds = durationSeconds;
        }

        private static Options parse(String[] args) {
            String roomId = null;
            Path output = null;
            long durationSeconds = 0;
            for (int index = 0; index < args.length; index++) {
                String option = args[index];
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("缺少参数值: " + option);
                }
                String value = args[++index];
                switch (option) {
                    case "--room" -> roomId = value;
                    case "--out" -> output = Path.of(value);
                    case "--duration-seconds" -> durationSeconds = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("未知参数: " + option);
                }
            }
            if (roomId == null || !roomId.matches("[A-Za-z0-9_]{1,64}")) {
                throw new IllegalArgumentException("请用 --room 指定虎牙房间号或短号");
            }
            if (durationSeconds < 0) {
                throw new IllegalArgumentException("--duration-seconds 不能为负数");
            }
            return new Options(roomId, output, durationSeconds);
        }
    }
}

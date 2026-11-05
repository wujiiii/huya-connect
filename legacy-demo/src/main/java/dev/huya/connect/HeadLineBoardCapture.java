package dev.huya.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qq.tars.protocol.tars.TarsInputStream;
import com.qq.tars.protocol.tars.TarsOutputStream;
import com.qq.tars.protocol.tars.TarsStructBase;
import tech.ordinaryroad.live.chat.client.codec.huya.api.HuyaApis;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.WupRsp;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.dto.UserId;
import tech.ordinaryroad.live.chat.client.codec.huya.msg.req.WupReq;

import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Experimental anonymous snapshot capture for Huya's paid headline board. */
public final class HeadLineBoardCapture {
    private static final String UA = "HYSDK(Windows,30000002)_APP(pc_exe&7090000&official)_SDK(trans&2.35.0.5996)";
    private static final ObjectMapper JSON = new ObjectMapper();

    private HeadLineBoardCapture() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: HeadLineBoardCapture ROOM OUTPUT_DIR DURATION_SECONDS INTERVAL_SECONDS");
        }
        String roomId = args[0];
        Path directory = Path.of(args[1]);
        long durationSeconds = Long.parseLong(args[2]);
        long intervalSeconds = Long.parseLong(args[3]);
        if (durationSeconds <= 0 || intervalSeconds <= 0) {
            throw new IllegalArgumentException("Duration and interval must be positive");
        }
        Files.createDirectories(directory);
        long pid = HuyaApis.roomInit(roomId).path("lChannelId").asLong();
        if (pid <= 0) {
            throw new IllegalStateException("Cannot resolve room's internal channel ID");
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        Instant end = Instant.now().plusSeconds(durationSeconds);
        Path file = directory.resolve("headline-board.ndjson");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW)) {
            while (Instant.now().isBefore(end)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fetchedAt", Instant.now().toString());
                row.put("roomId", roomId);
                row.put("internalChannelId", pid);
                try {
                    byte[] requestBytes = requestBytes(pid);
                    row.put("requestBase64", Base64.getEncoder().encodeToString(requestBytes));
                    HttpRequest request = HttpRequest.newBuilder(URI.create("https://wup.huya.com"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/x-wup")
                            .header("Origin", "https://www.huya.com")
                            .header("Referer", "https://www.huya.com")
                            .header("User-Agent", UA)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBytes))
                            .build();
                    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    row.put("httpStatus", response.statusCode());
                    row.put("responseBase64", Base64.getEncoder().encodeToString(response.body()));
                    BoardResponse board = new WupRsp(response.body()).getUniAttribute()
                            .getByClass("tRsp", new BoardResponse());
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (BoardInfo info : board.panel.items) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("messageId", info.messageId);
                        item.put("userName", info.person.name);
                        item.put("avatar", info.person.avatar);
                        item.put("content", info.content);
                        item.put("costRaw", info.cost);
                        item.put("costPayRaw", info.costPay);
                        item.put("totalSeconds", info.totalSeconds);
                        item.put("remainingSeconds", info.remainingSeconds);
                        items.add(item);
                    }
                    row.put("items", items);
                } catch (Exception failure) {
                    row.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
                writer.write(JSON.writeValueAsString(row));
                writer.newLine();
                writer.flush();
                Thread.sleep(intervalSeconds * 1000L);
            }
        }
    }

    private static byte[] requestBytes(long pid) {
        BoardRequest board = new BoardRequest();
        board.pid = pid;
        board.user.setSHuYaUA(UA);
        WupReq request = new WupReq();
        request.getTarsServantRequest().setServantName("wupui");
        request.getTarsServantRequest().setFunctionName("getHeadLineMessageBoard");
        request.getUniAttribute().put("tReq", board);
        return request.encode();
    }

    public static final class BoardRequest extends TarsStructBase {
        private long pid;
        private final UserId user = new UserId();

        @Override
        public void writeTo(TarsOutputStream out) {
            out.write(pid, 0);
            out.write("", 1);
            out.write(user, 2);
            out.write(0, 3);
            out.write(10, 4);
        }

        @Override
        public void readFrom(TarsInputStream in) {
        }

        @Override
        public TarsStructBase newInit() {
            return new BoardRequest();
        }
    }

    public static final class BoardResponse extends TarsStructBase {
        private BoardPanel panel = new BoardPanel();

        @Override
        public void writeTo(TarsOutputStream out) {
        }

        @Override
        public void readFrom(TarsInputStream in) {
            panel = (BoardPanel) in.directRead(panel, 1, false);
        }

        @Override
        public TarsStructBase newInit() {
            return new BoardResponse();
        }
    }

    public static final class BoardPanel extends TarsStructBase {
        private List<BoardInfo> items = new ArrayList<>(List.of(new BoardInfo()));

        @Override
        public void writeTo(TarsOutputStream out) {
        }

        @Override
        public void readFrom(TarsInputStream in) {
            items = in.readArray(items, 1, false);
        }

        @Override
        public TarsStructBase newInit() {
            return new BoardPanel();
        }
    }

    public static final class BoardInfo extends TarsStructBase {
        private BoardPerson person = new BoardPerson();
        private String content = "";
        private int cost;
        private int totalSeconds;
        private int remainingSeconds;
        private long messageId;
        private int costPay;

        @Override
        public void writeTo(TarsOutputStream out) {
        }

        @Override
        public void readFrom(TarsInputStream in) {
            person = (BoardPerson) in.directRead(person, 0, false);
            content = in.read(content, 1, false);
            cost = in.read(cost, 2, false);
            totalSeconds = in.read(totalSeconds, 4, false);
            remainingSeconds = in.read(remainingSeconds, 5, false);
            messageId = in.read(messageId, 9, false);
            costPay = in.read(costPay, 12, false);
        }

        @Override
        public TarsStructBase newInit() {
            return new BoardInfo();
        }
    }

    public static final class BoardPerson extends TarsStructBase {
        private String name = "";
        private String avatar = "";

        @Override
        public void writeTo(TarsOutputStream out) {
        }

        @Override
        public void readFrom(TarsInputStream in) {
            name = in.read(name, 1, false);
            avatar = in.read(avatar, 2, false);
        }

        @Override
        public TarsStructBase newInit() {
            return new BoardPerson();
        }
    }
}

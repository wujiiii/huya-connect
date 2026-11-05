package dev.huya.connect.demo;

import dev.huya.connect.sdk.HuyaEvent;
import dev.huya.connect.sdk.HuyaListener;
import dev.huya.connect.sdk.HuyaStatus;
import dev.huya.connect.sdk.RawFrame;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Only operational counters go into status.json; viewer content remains in SDK recordings. */
final class RoomHealth implements HuyaListener {
    private long received, sent, pushes, danmu, gifts, subscriptionCounts, decodeErrors, errors, reconnects;
    private long sequence;
    private Instant lastReceived, lastProtocol, lastWrite, lastHeartbeatReply;
    private String lastErrorType, lastDecodeError;
    private final Map<String, Long> uriCounts = new TreeMap<>();
    private final Map<String, Object> snapshots = new TreeMap<>();

    @Override public synchronized void onRawFrame(RawFrame raw) {
        sequence = raw.sequence(); lastWrite = Instant.now();
        if (raw.direction().equals("in")) { received++; lastReceived = Instant.now(); }
        else sent++;
    }
    @Override public synchronized void onEvent(HuyaEvent event) {
        if (!event.raw().direction().equals("in")) return;
        if (event instanceof HuyaEvent.Command command) {
            lastProtocol = Instant.now();
            if (command.opcode() == 21) lastHeartbeatReply = lastProtocol;
        } else if (event instanceof HuyaEvent.Message message) {
            pushes++; uriCounts.merge(Long.toString(message.uri()), 1L, Long::sum);
            if (message.uri() == 1400) danmu++;
            if (message.uri() == 6501) gifts++;
            if (message.uri() == 3102 && message.decodeError() == null) subscriptionCounts++;
        } else if (event instanceof HuyaEvent.Snapshot snapshot) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("receivedAt", Instant.now().toString()); info.put("requestId", snapshot.requestId());
            info.put("returnCode", snapshot.returnCode()); info.put("responseFields", snapshot.response().size());
            snapshots.put(snapshot.function(), info);
        } else if (event instanceof HuyaEvent.DecodeError error) {
            decodeErrors++; lastDecodeError = error.reason();
        }
    }
    @Override public synchronized void onStatus(HuyaStatus status) {
        if (status.state() == HuyaStatus.State.BACKOFF) reconnects++;
    }
    @Override public synchronized void onError(Throwable error) {
        errors++; lastErrorType = error.getClass().getSimpleName();
    }
    synchronized Map<String, Object> snapshot(HuyaStatus status, Instant now) {
        boolean healthy = status.state() == HuyaStatus.State.RUNNING && fresh(lastProtocol, now, 100)
                && fresh(lastWrite, now, 100);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("roomId", status.roomId()); row.put("presenterUid", status.presenterUid());
        row.put("state", status.state().name()); row.put("stateSince", status.changedAt().toString());
        row.put("connectionId", status.connectionId()); row.put("healthy", healthy);
        row.put("receivedFrames", received); row.put("sentFrames", sent); row.put("lastRawSequence", sequence);
        row.put("lastReceivedAt", iso(lastReceived)); row.put("lastProtocolAt", iso(lastProtocol));
        row.put("lastWriteAt", iso(lastWrite)); row.put("lastHeartbeatReplyAt", iso(lastHeartbeatReply));
        row.put("pushes", pushes); row.put("danmu", danmu); row.put("gifts", gifts);
        row.put("subscriptionCounts", subscriptionCounts); row.put("decodeErrors", decodeErrors);
        row.put("errors", errors); row.put("reconnects", reconnects); row.put("lastErrorType", lastErrorType);
        row.put("lastDecodeError", lastDecodeError); row.put("uriCounts", new TreeMap<>(uriCounts));
        row.put("snapshots", new TreeMap<>(snapshots));
        return row;
    }
    private static boolean fresh(Instant at, Instant now, long seconds) {
        return at != null && !at.isAfter(now.plusSeconds(5)) && Duration.between(at, now).getSeconds() <= seconds;
    }
    private static String iso(Instant at) { return at == null ? null : at.toString(); }
}

package dev.huya.connect.sdk;

import java.time.Instant;

public record HuyaStatus(State state, String roomId, String presenterUid, String connectionId,
                         Instant changedAt, String detail) {
    public enum State { NEW, CONNECTING, REGISTERING, RUNNING, BACKOFF, FAILED, CLOSED }
}

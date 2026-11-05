package dev.huya.connect.sdk;

import java.time.Instant;

/** One connection-scoped query or push observation. A missing value is never fabricated as zero. */
public record HuyaQueryResult<T>(String presenterUid, String connectionId, String requestId,
                                 Instant requestedAt, Instant receivedAt, Outcome outcome, T value,
                                 String detail, String responseSha256, HuyaEvent evidence) {
    public enum Outcome { SUCCESS, UNKNOWN, FAILED }
    public boolean successful() { return outcome == Outcome.SUCCESS; }
}

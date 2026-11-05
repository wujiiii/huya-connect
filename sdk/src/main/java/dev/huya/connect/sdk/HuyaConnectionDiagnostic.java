package dev.huya.connect.sdk;

import java.util.ArrayList;
import java.util.List;

/** Sanitized reconnect metadata. Endpoint index is zero-based in the configured endpoint list. */
public record HuyaConnectionDiagnostic(String roomId, String presenterUid, String connectionId,
                                       String occurredAt, Integer endpointIndex, String trigger,
                                       Integer closeCode, List<String> causeTypes,
                                       Long lastResponseAgeMillis, Long lastHeartbeatReplyAgeMillis,
                                       long retryDelayMillis) {
    public HuyaConnectionDiagnostic { causeTypes = List.copyOf(causeTypes); }

    // Throwable messages can contain connection URLs or credentials; retain only bounded class names.
    static List<String> causeTypes(Throwable error) {
        List<String> types = new ArrayList<>();
        for (int i = 0; error != null && i < 8; i++, error = error.getCause())
            types.add(error.getClass().getName());
        return types;
    }
}

package com.netsentinel.events;

import java.time.Instant;

public record TrafficEvent(
        Instant timestamp,
        String routeId,
        String backendId,
        String method,
        String uri,
        int statusCode,
        long latencyNanos,
        boolean success
) {
}

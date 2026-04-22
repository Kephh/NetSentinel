package com.netsentinel.routing;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

public final class RouteDefinition {
    private final String id;
    private final String host;
    private final Map<String, String> requiredHeaders;
    private final RoutingPolicy policy;
    private final List<BackendServer> backends;
    private final AtomicLong roundRobinCounter = new AtomicLong();

    public RouteDefinition(String id, String host, Map<String, String> requiredHeaders, RoutingPolicy policy, List<BackendServer> backends) {
        this.id = Objects.requireNonNull(id, "id");
        this.host = normalizeHost(host);
        this.requiredHeaders = requiredHeaders == null ? Map.of() : Map.copyOf(requiredHeaders);
        this.policy = policy == null ? RoutingPolicy.WEIGHTED_ROUND_ROBIN : policy;
        this.backends = List.copyOf(backends);
    }

    public String id() {
        return id;
    }

    public List<BackendServer> backends() {
        return backends;
    }

    public boolean matches(HttpHeaders headers) {
        String requestHost = normalizeHost(headers.get(HttpHeaderNames.HOST));
        boolean hostMatches = "*".equals(host) || host.equals(requestHost);
        if (!hostMatches) {
            return false;
        }
        for (Map.Entry<String, String> entry : requiredHeaders.entrySet()) {
            String actual = headers.get(entry.getKey());
            if (actual == null || !actual.equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    public Optional<BackendServer> select(Duration slowStartDuration) {
        long now = System.nanoTime();
        List<BackendServer> available = backends.stream()
                .filter(BackendServer::isAvailable)
                .toList();
        if (available.isEmpty()) {
            return Optional.empty();
        }
        if (policy == RoutingPolicy.LEAST_RESPONSE_TIME) {
            return available.stream()
                    .min(Comparator.comparingLong(backend -> backend.leastResponseScore(now, slowStartDuration)));
        }
        int totalWeight = available.stream()
                .mapToInt(backend -> backend.effectiveWeight(now, slowStartDuration))
                .sum();
        if (totalWeight <= 0) {
            return Optional.empty();
        }
        long slot = Math.floorMod(roundRobinCounter.getAndIncrement(), totalWeight);
        int cursor = 0;
        for (BackendServer backend : available) {
            cursor += backend.effectiveWeight(now, slowStartDuration);
            if (slot < cursor) {
                return Optional.of(backend);
            }
        }
        return Optional.of(available.getLast());
    }

    private static String normalizeHost(String value) {
        if (value == null || value.isBlank()) {
            return "*";
        }
        String lower = value.toLowerCase(Locale.ROOT).trim();
        int colon = lower.indexOf(':');
        return colon > -1 ? lower.substring(0, colon) : lower;
    }
}

package com.netsentinel.routing;

import com.netsentinel.resilience.CircuitBreaker;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class BackendServer {
    private static final long DEFAULT_EWMA_NANOS = TimeUnit.MILLISECONDS.toNanos(25);

    private final String id;
    private final URI uri;
    private final int configuredWeight;
    private final String healthPath;
    private final CircuitBreaker circuitBreaker;
    private final AtomicReference<BackendStatus> status = new AtomicReference<>(BackendStatus.UP);
    private final AtomicLong ewmaLatencyNanos = new AtomicLong(DEFAULT_EWMA_NANOS);
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile long warmupStartedNanos = System.nanoTime();

    public BackendServer(String id, URI uri, int configuredWeight, String healthPath, CircuitBreaker circuitBreaker,
            com.netsentinel.metrics.NetSentinelMetrics metrics) {
        this.id = Objects.requireNonNull(id, "id");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.configuredWeight = Math.max(1, configuredWeight);
        this.healthPath = healthPath == null || healthPath.isBlank() ? "/health" : healthPath;
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker, "circuitBreaker");

        if (metrics != null) {
            this.circuitBreaker.setStateChangeListener(newState -> {
                int code = switch (newState) {
                    case CLOSED -> 0;
                    case HALF_OPEN -> 1;
                    case OPEN -> 2;
                };
                metrics.recordCircuitState(id, code);
            });
        }
    }

    public String id() {
        return id;
    }

    public URI uri() {
        return uri;
    }

    public int configuredWeight() {
        return configuredWeight;
    }

    public String healthPath() {
        return healthPath;
    }

    public BackendStatus status() {
        return status.get();
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public boolean isAvailable() {
        return status.get() == BackendStatus.UP && circuitBreaker.allowRequest();
    }

    public int effectiveWeight(long nowNanos, Duration slowStartDuration) {
        if (status.get() != BackendStatus.UP) {
            return 0;
        }
        long elapsed = Math.max(0L, nowNanos - warmupStartedNanos);
        double progress = Math.min(1.0d, (double) elapsed / slowStartDuration.toNanos());
        double factor = 0.10d + (0.90d * progress);
        return Math.max(1, (int) Math.round(configuredWeight * factor));
    }

    public long leastResponseScore(long nowNanos, Duration slowStartDuration) {
        int effectiveWeight = Math.max(1, effectiveWeight(nowNanos, slowStartDuration));
        long latency = ewmaLatencyNanos.get();
        long concurrencyPenalty = TimeUnit.MILLISECONDS.toNanos(2L) * Math.max(0, inFlight.get());
        return (latency + concurrencyPenalty) / effectiveWeight;
    }

    public void beginRequest() {
        inFlight.incrementAndGet();
    }

    public void recordSuccess(long latencyNanos) {
        finishRequest();
        updateLatency(latencyNanos);
        circuitBreaker.recordSuccess(latencyNanos);
    }

    public void recordFailure(long latencyNanos) {
        finishRequest();
        updateLatency(latencyNanos);
        circuitBreaker.recordFailure(latencyNanos);
    }

    public void markDown() {
        status.set(BackendStatus.DOWN);
    }

    public void markUp() {
        BackendStatus previous = status.getAndSet(BackendStatus.UP);
        if (previous != BackendStatus.UP) {
            warmupStartedNanos = System.nanoTime();
        }
    }

    public String hostHeader() {
        int port = port();
        boolean defaultHttp = "http".equalsIgnoreCase(uri.getScheme()) && port == 80;
        boolean defaultHttps = "https".equalsIgnoreCase(uri.getScheme()) && port == 443;
        return defaultHttp || defaultHttps ? uri.getHost() : uri.getHost() + ":" + port;
    }

    public int port() {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void finishRequest() {
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
    }

    private void updateLatency(long latencyNanos) {
        long sanitized = Math.max(0L, latencyNanos);
        ewmaLatencyNanos.updateAndGet(current -> current + Math.round((sanitized - current) * 0.20d));
    }
}

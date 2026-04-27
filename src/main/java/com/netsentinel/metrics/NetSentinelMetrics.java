package com.netsentinel.metrics;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class NetSentinelMetrics implements AutoCloseable {
    private final CollectorRegistry registry;
    private final Counter requests;
    private final Counter errors;
    private final Histogram requestLatency;
    private final Gauge backendUp;
    private final Gauge requestsPerSecond;
    private final Gauge inFlightRequests;
    private final Counter wafBlocks;
    private final Counter retries;
    private final Gauge circuitBreakerState;
    private final LongAdder requestsInCurrentSecond = new LongAdder();
    private final AtomicLong inFlight = new AtomicLong();
    private final ScheduledExecutorService sampler;
    private HTTPServer httpServer;

    public NetSentinelMetrics() {
        this(CollectorRegistry.defaultRegistry);
    }

    NetSentinelMetrics(CollectorRegistry registry) {
        this.registry = registry;
        this.requests = Counter.build()
                .name("netsentinel_requests_total")
                .help("Total HTTP requests routed by NetSentinel.")
                .labelNames("route")
                .register(registry);
        this.errors = Counter.build()
                .name("netsentinel_http_errors_total")
                .help("Total 4xx/5xx HTTP responses produced through NetSentinel.")
                .labelNames("route", "status_class")
                .register(registry);
        this.requestLatency = Histogram.build()
                .name("netsentinel_request_latency_seconds")
                .help("End-to-end request latency observed by the proxy.")
                .buckets(0.001, 0.002, 0.005, 0.010, 0.025, 0.050, 0.100, 0.250, 0.500, 1.0, 2.5)
                .labelNames("route")
                .register(registry);
        this.backendUp = Gauge.build()
                .name("netsentinel_backend_up")
                .help("Backend health status, 1 for UP and 0 for DOWN.")
                .labelNames("backend")
                .register(registry);
        this.requestsPerSecond = Gauge.build()
                .name("netsentinel_requests_per_second")
                .help("Proxy throughput sampled over the last second.")
                .register(registry);
        this.inFlightRequests = Gauge.build()
                .name("netsentinel_requests_in_flight")
                .help("Current number of in-flight HTTP requests.")
                .register(registry);
        this.wafBlocks = Counter.build()
                .name("netsentinel_waf_blocked_total")
                .help("Total requests blocked by WAF.")
                .labelNames("route", "pattern")
                .register(registry);
        this.retries = Counter.build()
                .name("netsentinel_proxy_retries_total")
                .help("Total request retries attempted by the proxy.")
                .labelNames("route")
                .register(registry);
        this.circuitBreakerState = Gauge.build()
                .name("netsentinel_circuit_breaker_state")
                .help("Circuit breaker state (0: CLOSED, 1: HALF_OPEN, 2: OPEN).")
                .labelNames("backend")
                .register(registry);
    this.sampler = Executors.newSingleThreadScheduledExecutor(runnable ->
        Thread.ofPlatform().name("netsentinel-metrics-sampler").daemon(true).unstarted(runnable)
    );
    }

    public void start(int managementPort) throws IOException {
    sampler.scheduleAtFixedRate(this::publishRequestRate, 1, 1, TimeUnit.SECONDS);
    this.httpServer = new HTTPServer(new InetSocketAddress(managementPort), registry, true);
    }

    public void recordRequest(String route) {
        requests.labels(sanitize(route)).inc();
    requestsInCurrentSecond.increment();
    }

    public void recordError(String route, int statusCode) {
        if (statusCode >= 400) {
            errors.labels(sanitize(route), (statusCode / 100) + "xx").inc();
        }
    }

    public void recordLatency(String route, long latencyNanos) {
        requestLatency.labels(sanitize(route)).observe(latencyNanos / 1_000_000_000.0d);
    }

    public void requestStarted() {
        inFlightRequests.set(inFlight.incrementAndGet());
    }

    public void requestFinished() {
        long next = inFlight.updateAndGet(current -> Math.max(0L, current - 1L));
        inFlightRequests.set(next);
    }

    public void backendUp(String backendId, boolean up) {
        backendUp.labels(sanitize(backendId)).set(up ? 1.0d : 0.0d);
    }

    public void recordWafBlock(String route, String pattern) {
        wafBlocks.labels(sanitize(route), sanitize(pattern)).inc();
    }

    public void recordRetry(String route) {
        retries.labels(sanitize(route)).inc();
    }

    public void recordCircuitState(String backendId, int stateCode) {
        circuitBreakerState.labels(sanitize(backendId)).set(stateCode);
    }

    @Override
    public void close() {
        if (httpServer != null) {
            httpServer.stop();
        }
        sampler.shutdownNow();
    }

    void publishRequestRate() {
        requestsPerSecond.set(requestsInCurrentSecond.sumThenReset());
    }

    private static String sanitize(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}

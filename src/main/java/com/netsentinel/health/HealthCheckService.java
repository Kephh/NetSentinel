package com.netsentinel.health;

import com.netsentinel.config.NetSentinelConfig;
import com.netsentinel.metrics.NetSentinelMetrics;
import com.netsentinel.routing.BackendServer;
import com.netsentinel.routing.BackendStatus;
import com.netsentinel.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class HealthCheckService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(HealthCheckService.class);
    private final RoutingEngine routingEngine;
    private final NetSentinelConfig.HealthConfig config;
    private final NetSentinelMetrics metrics;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService virtualExecutor;
    private final HttpClient httpClient;
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();

    public HealthCheckService(RoutingEngine routingEngine, NetSentinelConfig.HealthConfig config, NetSentinelMetrics metrics) {
        this.routingEngine = routingEngine;
        this.config = config;
        this.metrics = metrics;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().name("netsentinel-health-scheduler").daemon(true).unstarted(runnable));
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeoutMillis()))
                .executor(virtualExecutor)
                .build();
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::scheduleChecks, 0, config.intervalSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        virtualExecutor.shutdownNow();
    }

    private void scheduleChecks() {
        for (BackendServer backend : routingEngine.backends()) {
            virtualExecutor.submit(() -> check(backend));
        }
    }

    private void check(BackendServer backend) {
        boolean healthy = false;
        try {
            URI healthUri = backend.uri().resolve(backend.healthPath());
            HttpRequest request = HttpRequest.newBuilder(healthUri)
                    .timeout(Duration.ofMillis(config.timeoutMillis()))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            healthy = response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception exception) {
            logger.debug("Health check failed for {}: {}", backend.id(), exception.getMessage());
            healthy = false;
        }

        if (healthy) {
            failures.remove(backend.id());
            if (backend.status() != BackendStatus.UP) {
                logger.info("Backend {} is now UP", backend.id());
                routingEngine.markBackendUp(backend.id());
            }
            metrics.backendUp(backend.id(), true);
            return;
        }

        int failed = failures.merge(backend.id(), 1, Integer::sum);
        if (failed >= config.failureThreshold()) {
            if (backend.status() != BackendStatus.DOWN) {
                logger.warn("Backend {} is now DOWN (failed {} consecutive checks)", backend.id(), failed);
                routingEngine.markBackendDown(backend.id());
            }
            metrics.backendUp(backend.id(), false);
        }
    }
}

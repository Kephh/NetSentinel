package com.netsentinel.routing;

import com.netsentinel.config.ConfigLoader;
import com.netsentinel.config.NetSentinelConfig;
import com.netsentinel.resilience.CircuitBreaker;
import io.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class RoutingEngine {
    private static final Logger logger = LoggerFactory.getLogger(RoutingEngine.class);
    private static final Duration SLOW_START_DURATION = Duration.ofMinutes(2);

    private final AtomicReference<List<RouteDefinition>> routes = new AtomicReference<>(List.of());
    private com.netsentinel.metrics.NetSentinelMetrics metrics;

    public void load(NetSentinelConfig config, com.netsentinel.metrics.NetSentinelMetrics metrics) {
        this.metrics = metrics;
        Map<String, BackendServer> existing = existingBackends();
        List<RouteDefinition> nextRoutes = new ArrayList<>();
        for (NetSentinelConfig.RouteConfig routeConfig : config.routes()) {
            List<BackendServer> backends = routeConfig.backends().stream()
                    .map(backend -> reuseOrCreate(existing, backend))
                    .toList();
            nextRoutes.add(new RouteDefinition(routeConfig.id(), routeConfig.host(), routeConfig.headers(),
                    routeConfig.policy(), backends));
        }
        routes.set(List.copyOf(nextRoutes));
        logger.info("Loaded {} routes", nextRoutes.size());
    }

    public Optional<BackendSelection> select(HttpRequest request) {
        return select(request.headers());
    }

    public Optional<BackendSelection> select(io.netty.handler.codec.http.HttpHeaders headers) {
        for (RouteDefinition route : routes.get()) {
            if (!route.matches(headers)) {
                continue;
            }
            Optional<BackendServer> backend = route.select(SLOW_START_DURATION);
            if (backend.isPresent()) {
                return Optional.of(new BackendSelection(route.id(), backend.get()));
            }
        }
        return Optional.empty();
    }

    public Collection<BackendServer> backends() {
        return existingBackends().values();
    }

    public void markBackendDown(String id) {
        BackendServer backend = existingBackends().get(id);
        if (backend != null) {
            backend.markDown();
        }
    }

    public void markBackendUp(String id) {
        BackendServer backend = existingBackends().get(id);
        if (backend != null) {
            backend.markUp();
        }
    }

    public AutoCloseable startHotReload(Path configPath) throws IOException {
        Path absoluteConfig = configPath.toAbsolutePath().normalize();
        Path parent = absoluteConfig.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return () -> {
            };
        }
        WatchService watchService = parent.getFileSystem().newWatchService();
        parent.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread watcher = Thread.ofVirtual().name("netsentinel-config-watch").start(() -> {
            while (running.get()) {
                try {
                    WatchKey key = watchService.take();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (absoluteConfig.getFileName().equals(event.context())) {
                            logger.info("Configuration change detected, reloading routes...");
                            load(ConfigLoader.load(absoluteConfig), metrics);
                        }
                    }
                    key.reset();
                } catch (ClosedWatchServiceException ignored) {
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception exception) {
                    logger.error("Failed to hot-reload NetSentinel config: {}", exception.getMessage());
                }
            }
        });
        return () -> {
            running.set(false);
            watcher.interrupt();
            watchService.close();
        };
    }

    private BackendServer reuseOrCreate(Map<String, BackendServer> existing,
            NetSentinelConfig.BackendConfig backendConfig) {
        BackendServer current = existing.get(backendConfig.id());
        if (current != null
                && current.uri().equals(backendConfig.uri())
                && current.configuredWeight() == backendConfig.weight()
                && current.healthPath().equals(backendConfig.healthPath())) {
            return current;
        }
        return new BackendServer(
                backendConfig.id(),
                backendConfig.uri(),
                backendConfig.weight(),
                backendConfig.healthPath(),
                new CircuitBreaker(0.15d, Duration.ofMillis(500), Duration.ofSeconds(5)),
                metrics);
    }

    private Map<String, BackendServer> existingBackends() {
        Map<String, BackendServer> result = new HashMap<>();
        for (RouteDefinition route : routes.get()) {
            for (BackendServer backend : route.backends()) {
                result.put(backend.id(), backend);
            }
        }
        return result;
    }
}

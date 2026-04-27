package com.netsentinel.config;

import com.netsentinel.routing.RoutingPolicy;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record NetSentinelConfig(
        ServerConfig server,
        SecurityConfig security,
        WafConfig waf,
        HealthConfig health,
        AuditConfig audit,
        List<RouteConfig> routes
) {
    public NetSentinelConfig {
        server = server == null ? ServerConfig.defaults() : server;
        security = security == null ? SecurityConfig.defaults() : security;
        waf = waf == null ? WafConfig.defaults() : waf;
        health = health == null ? HealthConfig.defaults() : health;
        audit = audit == null ? AuditConfig.defaults() : audit;
        routes = routes == null ? List.of() : List.copyOf(routes);
    }

    public NetSentinelConfig(
            ServerConfig server,
            SecurityConfig security,
            WafConfig waf,
            HealthConfig health,
            List<RouteConfig> routes
    ) {
        this(server, security, waf, health, AuditConfig.defaults(), routes);
    }

    public record ServerConfig(
            String host,
            int port,
            int managementPort,
            String redisUri,
            RateLimitConfig rateLimit
    ) {
        public ServerConfig {
            host = (host == null || host.isBlank()) ? "0.0.0.0" : host;
            port = port <= 0 ? 8080 : port;
            managementPort = managementPort <= 0 ? 9090 : managementPort;
            redisUri = redisUri == null ? "" : redisUri;
            rateLimit = rateLimit == null ? RateLimitConfig.defaults() : rateLimit;
        }

        public static ServerConfig defaults() {
            return new ServerConfig("0.0.0.0", 8080, 9090, "", RateLimitConfig.defaults());
        }
    }

    public enum RateLimitAlgorithm {
        TOKEN_BUCKET,
        SLIDING_WINDOW
    }

    public record RateLimitConfig(
            boolean enabled,
            RateLimitAlgorithm algorithm,
            long capacity,
            long refillPerSecond
    ) {
        public RateLimitConfig {
            algorithm = algorithm == null ? RateLimitAlgorithm.TOKEN_BUCKET : algorithm;
            capacity = capacity <= 0 ? 1000 : capacity;
            refillPerSecond = refillPerSecond <= 0 ? 500 : refillPerSecond;
        }

        public static RateLimitConfig defaults() {
            return new RateLimitConfig(true, RateLimitAlgorithm.TOKEN_BUCKET, 1000, 500);
        }
    }

    public record SecurityConfig(TlsConfig inboundTls, TlsConfig backendTls) {
        public SecurityConfig {
            inboundTls = inboundTls == null ? TlsConfig.disabled() : inboundTls;
            backendTls = backendTls == null ? TlsConfig.disabled() : backendTls;
        }

        public static SecurityConfig defaults() {
            return new SecurityConfig(TlsConfig.disabled(), TlsConfig.disabled());
        }
    }

    public record TlsConfig(
            boolean enabled,
            String certificateChainPath,
            String privateKeyPath,
            String trustCertificatePath,
            boolean requireClientAuth
    ) {
        public TlsConfig {
            certificateChainPath = certificateChainPath == null ? "" : certificateChainPath;
            privateKeyPath = privateKeyPath == null ? "" : privateKeyPath;
            trustCertificatePath = trustCertificatePath == null ? "" : trustCertificatePath;
        }

        public static TlsConfig disabled() {
            return new TlsConfig(false, "", "", "", false);
        }
    }

    public record WafConfig(boolean enabled, List<String> patterns) {
        public WafConfig {
            patterns = patterns == null || patterns.isEmpty()
                    ? List.of("' or 1=1", "union select", "<script", "../", "javascript:")
                    : List.copyOf(patterns);
        }

        public static WafConfig defaults() {
            return new WafConfig(true, List.of());
        }
    }

    public record HealthConfig(int intervalSeconds, int failureThreshold, int timeoutMillis) {
        public HealthConfig {
            intervalSeconds = intervalSeconds <= 0 ? 5 : intervalSeconds;
            failureThreshold = failureThreshold <= 0 ? 3 : failureThreshold;
            timeoutMillis = timeoutMillis <= 0 ? 500 : timeoutMillis;
        }

        public static HealthConfig defaults() {
            return new HealthConfig(5, 3, 500);
        }
    }

    public record AuditConfig(boolean enabled, String sink, String filePath) {
        public AuditConfig {
            sink = (sink == null || sink.isBlank()) ? "stdout" : sink.trim().toLowerCase();
            filePath = (filePath == null || filePath.isBlank()) ? "logs/netsentinel-audit.log" : filePath;
        }

        public static AuditConfig defaults() {
            return new AuditConfig(true, "stdout", "logs/netsentinel-audit.log");
        }

        public boolean fileSink() {
            return "file".equals(sink);
        }
    }

    public record RouteConfig(
            String id,
            String host,
            Map<String, String> headers,
            RoutingPolicy policy,
            List<BackendConfig> backends
    ) {
        public RouteConfig {
            id = (id == null || id.isBlank()) ? "default" : id;
            host = (host == null || host.isBlank()) ? "*" : host.toLowerCase();
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            policy = policy == null ? RoutingPolicy.WEIGHTED_ROUND_ROBIN : policy;
            backends = backends == null ? List.of() : List.copyOf(backends);
        }
    }

    public record BackendConfig(String id, URI uri, int weight, String healthPath) {
        public BackendConfig {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("backend id is required");
            }
            if (uri == null) {
                throw new IllegalArgumentException("backend uri is required");
            }
            weight = weight <= 0 ? 100 : weight;
            healthPath = (healthPath == null || healthPath.isBlank()) ? "/health" : healthPath;
        }
    }
}

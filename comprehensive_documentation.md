# NetSentinel: Master Architecture & Operations Guide

NetSentinel is a high-performance, event-driven Layer-7 reverse proxy and traffic orchestrator built upon Java 21 and the Netty framework.

---

## 1. Vision & Purpose: The "Why"
Historically, organizations choosing an API Gateway or Edge Proxy have to balance between raw performance (like Nginx, built in C) and extensibility/ecosystem integration (like Java or Node gateways). 

NetSentinel was created to bridge this gap. By utilizing modern Java capabilities—specifically **Virtual Threads** for non-blocking I/O and Netty's **Native Epoll** transport—NetSentinel achieves C-like throughput and latency while remaining deeply embedded in the enterprise Java ecosystem. 

**What is it useful for?**
- Defending local services against payload attacks (WAF).
- Throttling abusive traffic spikes (Resilient Rate Limiting).
- Abstracting routing complexity away from microservices.
- Providing standardized observability (Prometheus/ELK) without modifying the downstream applications.

**Why should people use it?**
Because it treats "Day-2 Operations" as first-class citizens. If your Redis cache fails, standard API gateways crash or lock up; NetSentinel automatically falls back to local rate limiting. It guarantees a sub-2-millisecond proxy overhead in optimal environments, maintaining ultra-low latency.

---

## 2. Technical Architecture: "How It Works"

### Netty Event Loop Model
NetSentinel processes requests using an asynchronous, event-driven pipeline.
- On Linux, it leverages `EpollEventLoopGroup` and `EpollServerSocketChannel`. This natively hooks into Linux's high-performance I/O event notification mechanism, drastically reducing CPU context switching.
- It uses `PooledByteBufAllocator`, meaning memory chunks used to read HTTP requests are pooled and reused, nearly eliminating garbage collection pauses during high-throughput traffic spikes.

### The Proxy Pipeline
When an HTTP request enters, it flows through these sequential Netty Handlers:
1.  **HttpRequestDecoder / HttpResponseEncoder**: Parses bytes into HTTP objects.
2.  **BackpressureHandler**: Pauses reading from the socket if the downstream system is too slow, preventing Out-Of-Memory errors.
3.  **RateLimitHandler**: Checks if the request should be throttled.
4.  **WafHandler**: Inspects payloads for malicious patterns.
5.  **MetricsHandler**: Tracks active connections and requests-per-second.
6.  **ProxyHandler**: Routes the request to the target backend and streams the response back. 

### Security: Aho-Corasick WAF
The Web Application Firewall (WAF) uses the **Aho-Corasick** string matching algorithm. Rather than evaluating dozens of Regex patterns one by one ($O(n \times m)$), Aho-Corasick builds a deterministic finite automaton (DFA). It scans the request URI, Headers, and Body for malicious strings (like `union select` or `<script`) in a **single pass**, achieving a vastly superior $O(n + m)$ time complexity.

### Intelligent Routing & Resilience
- **Weighted Round Robin**: Distributes load based on configured capacity.
- **Least Response Time via EWMA**: Predicts the fastest backend by maintaining an *Exponentially Weighted Moving Average* of past latencies, penalized by the number of currently in-flight requests.
- **Circuit Breaker**: If a backend's failure rate exceeds the target (or if the p99 latency spikes), the circuit trips to an `OPEN` state, immediately rejecting requests to give the backend time to recover, before testing the waters in a `HALF-OPEN` state.
- **Resilient Rate Limiting**: Wraps a primary `RedisTokenBucketRateLimiter` and a fallback `LocalTokenBucketRateLimiter`. If Redis times out, it silently switches back to local memory for the duration of a cooldown window.

---

## 3. Operational Reality

### Deployment Mode
NetSentinel is designed to be deployed as a **Kubernetes Sidecar** or an **Edge Gateway**. 
In sidecar mode (as seen in `k8s/deployment-elk-sidecar.yaml`), it sits in the same pod as your application. Traffic hits NetSentinel first, which cleans it, routes it to `localhost:9001`, and publishes audit JSON files.

### Observability
- **Prometheus**: Exposes `/metrics` on port 9090, detailing `netsentinel_request_latency_seconds` and `netsentinel_backend_up`.
- **Audit Logs**: Generates `ndjson` (Newline Delimited JSON). In K8s, a Filebeat sidecar tails `/var/log/netsentinel/audit.log` and ships it directly to Logstash/Elasticsearch for real-time traffic analysis.

---

## 4. Current Drawbacks & Mitigation

| Drawback | Impact | Mitigation Plan |
| :--- | :--- | :--- |
| **No HTTP/2 or gRPC Support** | Limits usage in modern microservice meshes that rely heavily on gRPC multiplexing. | **Planned Architecture Revamp**: Upgrade the Netty pipeline to include `Http2FrameCodec` and ALPN negotiation for inbound connections. |
| **Static Configuration** | Hot-reloading via file changes works, but requires disk access. It lacks a dynamic REST API for on-the-fly route updates. | **Control Plane Integration**: Introduce a Management API on port 9090 to dynamically push `BackendConfig` JSON states to the `RoutingEngine` without file system interaction. |
| **Stateless Rate Limiting Fallback** | When falling back to the local Rate Limiter, limits are per-node, not global. | **Acceptable Risk**: This is a known trade-off. It is better to have inaccurate localized limiting than a total cascading failure due to a Redis outage. |

---

## 5. Next Scope (Roadmap)

To push NetSentinel fully into the enterprise Service Mesh territory, the upcoming technical roadmap includes:
1.  **Distributed Tracing**: Native OpenTelemetry (`traceparent` header propagation) to link NetSentinel logs with backend APM (Application Performance Monitoring).
2.  **Advanced WAF Rulesets**: Integration with standard OWASP ModSecurity core rulesets.
3.  **Active Health Checks**: A background daemon that proactively pings backends rather than relying strictly on passive circuit breaking. (Currently partially implemented via `HealthCheckService`, needs tighter TLS support).
4.  **mTLS (Mutual TLS)**: Requiring client certificates for inbound requests, turning NetSentinel into a Zero-Trust gateway.

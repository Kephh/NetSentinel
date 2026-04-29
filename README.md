# NetSentinel

NetSentinel is a high-performance Layer-7 reverse proxy and cloud-native traffic orchestrator engineered in Java 21 using the Netty framework. It targets sub-2ms processing overhead by leveraging **Linux Epoll transport**, **Zero-Copy memory pooling**, and **Virtual Threads (Project Loom)**.

### Key Features
- **Intelligent Routing**: Dynamic Weighted Round-Robin and predictive Least-Response-Time routing via EWMA calculations.
- **Aho-Corasick WAF**: Inspects inbound HTTP streams for malicious payloads in a single $O(n+m)$ pass without freezing the event loop.
- **Resilient Rate Limiting**: "Fail-open" architecture that gracefully degrades from a Redis-backed cluster limit down to local memory token buckets during infrastructure outages.
- **Circuit Breaking**: Active latency and failure-rate monitoring preventing cascading system failures.
- **Dual-Layer Observability**:
  - **Console Access Logs**: Real-time HTTP summaries and low-level socket hex dumps.
  - **JSON Audit Telemetry**: Deep traffic analytics designed for stdout, file/ELK sidecars, or Kafka topics.


## Run locally

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target\netsentinel-0.1.0.jar config\netsentinel.json
```

On Linux, NetSentinel uses `EpollEventLoopGroup` and `EpollServerSocketChannel` when native transport is available. On Windows/macOS it falls back to NIO so local development still works.

## Ports

- Proxy: `8080`
- Prometheus metrics: `9090`

## Audit & Access Logging

NetSentinel provides comprehensive visibility into network traffic via a dual-layer logging system.

### 1. Real-Time Console Access Logs
When running NetSentinel, the console will actively print detailed HTTP access summaries for every request, providing immediate operational visibility:
```text
[INFO] AccessLogHandler - [Access] 127.0.0.1 - GET /api/data -> 200 (45ms)
```

### 2. JSON Audit Sink
Audit settings are controlled through the `audit` section in the `netsentinel.json` configuration file:

```json
"audit": {
	"enabled": true,
	"sink": "file",
	"filePath": "logs/netsentinel-audit.log",
	"kafkaBootstrapServers": "",
	"kafkaTopic": "netsentinel-traffic"
}
```

- `sink: file` writes structured, Newline-Delimited JSON (NDJSON) to `filePath`. This is the recommended mode for sidecar shipping.
- `sink: stdout` publishes the JSON traffic events directly to container logs.
- `sink: kafka` publishes the same JSON traffic events to `kafkaTopic` through `kafkaBootstrapServers`.
- A ready-to-use file sink sample is available at `config/netsentinel-elk.json`.

## Example metrics

```text
curl http://localhost:9090/metrics
```

Key metrics include:

- `netsentinel_requests_total`
- `netsentinel_requests_per_second`
- `netsentinel_requests_in_flight`
- `netsentinel_request_latency_seconds`
- `netsentinel_http_errors_total`
- `netsentinel_backend_up`

## Stress test

```bash
wrk -t8 -c10000 -d60s http://127.0.0.1:8080/
```

## Overhead benchmark automation

Use the benchmark script to compare direct backend latency against NetSentinel proxy latency and enforce the `<2ms` overhead target:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\benchmark.ps1 \
	-DirectUrl http://127.0.0.1:9001/ \
	-ProxyUrl http://127.0.0.1:8080/ \
	-Threads 8 -Connections 10000 -Duration 30s -TargetOverheadMs 2.0
```

Linux CI and container environments can use:

```bash
PROXY_URL=http://127.0.0.1:8080/ \
DIRECT_URL=http://127.0.0.1:9001/ \
THREADS=8 CONNECTIONS=2000 DURATION=20s TARGET_OVERHEAD_MS=2.0 \
bash ./scripts/benchmark.sh
```

`config/netsentinel-benchmark.json` is included for repeatable benchmark runs with low-noise settings.

## ELK Integration Assets

The repository now includes ELK ingestion assets for audit telemetry:

- Filebeat input config: `observability/elk/filebeat/filebeat.yml`
- Logstash pipeline: `observability/elk/logstash/pipeline/netsentinel.conf`
- Elasticsearch index template: `observability/elk/elasticsearch/netsentinel-index-template.json`
- Kubernetes sidecar example: `k8s/deployment-elk-sidecar.yaml`

Use `k8s/deployment-elk-sidecar.yaml` when deploying NetSentinel as a sidecar with Filebeat in the same pod. In this mode, configure NetSentinel with `config/netsentinel-elk.json` so audit logs are written to `/var/log/netsentinel/audit.log`.

## CI Performance Gate

GitHub Actions workflow `/.github/workflows/performance-gate.yml` builds NetSentinel, starts a direct backend, runs NetSentinel, then executes `scripts/benchmark.sh` and fails if latency overhead is `>= 2.0ms`.

The practical latency target is less than 2 ms added proxy overhead compared with a direct backend connection, validated on Linux with epoll enabled.

## Dashboard Cockpit (React)

An operations dashboard is included in `dashboard/` with a dark-core command deck UX:

- Real-time telemetry ribbon (200ms query polling)
- Traffic flow map with backend slide-over detail panel
- AI-assisted log stream with anomaly scan modal

Run locally:

```powershell
cd dashboard
npm install
npm run dev
```

Quality checks:

```powershell
cd dashboard
npm run lint
npm run build
```

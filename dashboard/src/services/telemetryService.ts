import type {
  AegisAnalysis,
  BackendNode,
  BackendStatus,
  FlowEdge,
  LogEntry,
  TelemetrySnapshot,
  TrafficPoint,
} from './types'

const backendSeed: Omit<BackendNode, 'status' | 'cpuPct' | 'ramPct' | 'socketCount' | 'reqPerSec' | 'latencyMs'>[] = [
  { id: 'svc-auth', name: 'auth-core', zone: 'zone-a' },
  { id: 'svc-billing', name: 'billing-ledger', zone: 'zone-b' },
  { id: 'svc-profile', name: 'profile-cache', zone: 'zone-c' },
  { id: 'svc-search', name: 'search-index', zone: 'zone-b' },
]

let activeConnections = 8120
let throughputGbps = 1.72
let p99LatencyMs = 12.8
let errorRatePct = 0.38
let ticks = 0

const backendStatusMap = new Map<string, BackendStatus>(
  backendSeed.map((backend) => [backend.id, 'online']),
)

let flowSeries: TrafficPoint[] = Array.from({ length: 30 }, (_, index) => ({
  timestamp: Date.now() - (29 - index) * 200,
  throughputGbps: Number((1.6 + Math.sin(index * 0.3) * 0.16).toFixed(3)),
  p99LatencyMs: Number((11.5 + Math.cos(index * 0.4) * 1.8).toFixed(2)),
  errorRatePct: Number((0.32 + Math.sin(index * 0.6) * 0.09).toFixed(2)),
}))

const logTemplates = [
  {
    service: 'proxy-core',
    severity: 'INFO' as const,
    message: 'Adaptive routing update applied to edge cluster',
  },
  {
    service: 'svc-auth',
    severity: 'WARN' as const,
    message: 'Latency drift detected on TLS handshake pool',
  },
  {
    service: 'svc-billing',
    severity: 'ERROR' as const,
    message: 'Circuit breaker opened after elevated downstream timeout rate',
  },
  {
    service: 'svc-search',
    severity: 'WARN' as const,
    message: 'Cache miss storm observed on shard rebalance event',
  },
  {
    service: 'proxy-core',
    severity: 'INFO' as const,
    message: 'Backpressure watermark normalized after burst traffic',
  },
]

let rollingLogs: LogEntry[] = []

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

function vary(value: number, magnitude: number) {
  return value + (Math.random() * 2 - 1) * magnitude
}

function updateBackendStatus(previous: BackendStatus): BackendStatus {
  const roll = Math.random()
  if (previous === 'offline') {
    if (roll < 0.12) {
      return 'degraded'
    }
    return 'offline'
  }
  if (previous === 'degraded') {
    if (roll < 0.2) {
      return 'online'
    }
    if (roll > 0.92) {
      return 'offline'
    }
    return 'degraded'
  }
  if (roll > 0.97) {
    return 'offline'
  }
  if (roll > 0.9) {
    return 'degraded'
  }
  return 'online'
}

function evaluateHealthStatus(nextP99: number, nextErrorRate: number, backends: BackendNode[]) {
  const offlineCount = backends.filter((backend) => backend.status === 'offline').length
  const degradedCount = backends.filter((backend) => backend.status === 'degraded').length

  if (nextP99 >= 32 || nextErrorRate >= 2.1 || offlineCount >= 2) {
    return 'red' as const
  }
  if (nextP99 >= 22 || nextErrorRate >= 1.2 || offlineCount >= 1 || degradedCount >= 1) {
    return 'yellow' as const
  }
  return 'green' as const
}

function buildMockTelemetrySnapshot(): TelemetrySnapshot {
  ticks += 1

  activeConnections = Math.round(clamp(vary(activeConnections, 110), 5000, 17000))
  throughputGbps = Number(clamp(vary(throughputGbps, 0.08), 0.9, 4.5).toFixed(3))
  p99LatencyMs = Number(clamp(vary(p99LatencyMs, 1.2), 6, 58).toFixed(2))
  errorRatePct = Number(clamp(vary(errorRatePct, 0.08), 0.05, 5.2).toFixed(2))

  const backends: BackendNode[] = backendSeed.map((seed, index) => {
    const previousStatus = backendStatusMap.get(seed.id) ?? 'online'
    const status = updateBackendStatus(previousStatus)
    backendStatusMap.set(seed.id, status)

    const statusFactor = status === 'online' ? 1 : status === 'degraded' ? 1.45 : 2.1
    const reqPerSec = Math.round(clamp(220 + Math.sin((ticks + index) * 0.45) * 80, 40, 420))
    const latencyMs = Number(clamp(vary(9 + index * 1.4, 1.4) * statusFactor, 7, 66).toFixed(2))

    return {
      id: seed.id,
      name: seed.name,
      zone: seed.zone,
      status,
      cpuPct: Number(clamp(vary(44 + index * 8, 6) * statusFactor, 14, 98).toFixed(1)),
      ramPct: Number(clamp(vary(51 + index * 7, 4) * statusFactor, 22, 98).toFixed(1)),
      socketCount: Math.round(clamp(vary(430 + index * 95, 40) * statusFactor, 80, 2200)),
      reqPerSec,
      latencyMs,
    }
  })

  const healthStatus = evaluateHealthStatus(p99LatencyMs, errorRatePct, backends)

  flowSeries = [
    ...flowSeries.slice(-29),
    {
      timestamp: Date.now(),
      throughputGbps,
      p99LatencyMs,
      errorRatePct,
    },
  ]

  const totalReqPerSec = backends.reduce((sum, backend) => sum + backend.reqPerSec, 0)
  const flowEdges: FlowEdge[] = backends.map((backend) => ({
    from: 'netsentinel-proxy',
    to: backend.id,
    reqPerSec: backend.reqPerSec,
    errorRatePct: Number(((backend.reqPerSec / totalReqPerSec) * errorRatePct).toFixed(2)),
  }))

  return {
    generatedAt: Date.now(),
    metrics: {
      activeConnections,
      throughputGbps,
      p99LatencyMs,
      errorRatePct,
    },
    proxy: {
      id: 'netsentinel-proxy',
      activeConnections,
      status: healthStatus,
    },
    backends,
    flowSeries,
    flowEdges,
    healthStatus,
  }
}

function createLogEntry(seed: (typeof logTemplates)[number], sequence: number): LogEntry {
  const severityWeight = seed.severity === 'ERROR' ? 0.9 : seed.severity === 'WARN' ? 0.64 : 0.18
  return {
    id: `${Date.now()}-${sequence}`,
    timestamp: new Date().toISOString(),
    service: seed.service,
    severity: seed.severity,
    message: seed.message,
    anomalyScore: Number(clamp(vary(severityWeight, 0.12), 0.05, 0.99).toFixed(2)),
    correlationId: Math.random().toString(16).slice(2, 10),
  }
}

function buildMockLogs() {
  const newCount = Math.random() > 0.6 ? 2 : 1
  for (let index = 0; index < newCount; index += 1) {
    const seed = logTemplates[Math.floor(Math.random() * logTemplates.length)]
    rollingLogs.unshift(createLogEntry(seed, index))
  }
  rollingLogs = rollingLogs.slice(0, 80)
  return rollingLogs
}

async function fetchFromApi<T>(path: string, init?: RequestInit): Promise<T | null> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 900)
  try {
    const response = await fetch(path, {
      ...init,
      signal: controller.signal,
      headers: {
        'Content-Type': 'application/json',
        ...(init?.headers ?? {}),
      },
    })
    if (!response.ok) {
      return null
    }
    return (await response.json()) as T
  } catch {
    return null
  } finally {
    clearTimeout(timeout)
  }
}

export async function fetchTelemetrySnapshot(): Promise<TelemetrySnapshot> {
  const apiSnapshot = await fetchFromApi<TelemetrySnapshot>('/api/dashboard/telemetry')
  return apiSnapshot ?? buildMockTelemetrySnapshot()
}

export async function fetchLogStream(): Promise<LogEntry[]> {
  const apiLogs = await fetchFromApi<LogEntry[]>('/api/dashboard/logs')
  return apiLogs ?? buildMockLogs()
}

export async function scanLogWithAegis(log: LogEntry): Promise<AegisAnalysis> {
  const apiResult = await fetchFromApi<AegisAnalysis>('/api/dashboard/aegis/scan', {
    method: 'POST',
    body: JSON.stringify(log),
  })
  if (apiResult) {
    return apiResult
  }

  await new Promise<void>((resolve) => {
    setTimeout(() => resolve(), 380)
  })

  const highRisk = log.anomalyScore >= 0.78 || log.severity === 'ERROR'
  return {
    summary: highRisk
      ? 'Aegis AI detected a correlated error burst with cascading retry pressure.'
      : 'Aegis AI detected localized turbulence with no cluster-wide instability.',
    probableCause: log.message,
    impactedZone: log.service.includes('billing') ? 'zone-b' : 'edge-mesh',
    confidencePct: highRisk ? 93 : 76,
    suggestedActions: highRisk
      ? [
          'Pin traffic to healthy replicas using temporary weighted override.',
          'Inspect timeout budget for downstream dependency and raise circuit cooldown.',
          'Purge stale connection pool entries and replay synthetic health probes.',
        ]
      : [
          'Monitor retry fan-out for the next 2 minutes before intervention.',
          'Validate cache hit ratio and socket pool pressure for affected service.',
        ],
  }
}

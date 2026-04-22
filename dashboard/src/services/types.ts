export type HealthStatus = 'green' | 'yellow' | 'red'
export type BackendStatus = 'online' | 'degraded' | 'offline'
export type LogSeverity = 'INFO' | 'WARN' | 'ERROR'

export interface TelemetryRibbonMetrics {
  activeConnections: number
  throughputGbps: number
  p99LatencyMs: number
  errorRatePct: number
}

export interface BackendNode {
  id: string
  name: string
  zone: string
  status: BackendStatus
  cpuPct: number
  ramPct: number
  socketCount: number
  reqPerSec: number
  latencyMs: number
}

export interface TrafficPoint {
  timestamp: number
  throughputGbps: number
  p99LatencyMs: number
  errorRatePct: number
}

export interface FlowEdge {
  from: string
  to: string
  reqPerSec: number
  errorRatePct: number
}

export interface TelemetrySnapshot {
  generatedAt: number
  metrics: TelemetryRibbonMetrics
  proxy: {
    id: string
    activeConnections: number
    status: HealthStatus
  }
  backends: BackendNode[]
  flowSeries: TrafficPoint[]
  flowEdges: FlowEdge[]
  healthStatus: HealthStatus
}

export interface LogEntry {
  id: string
  timestamp: string
  service: string
  severity: LogSeverity
  message: string
  anomalyScore: number
  correlationId: string
}

export interface AegisAnalysis {
  summary: string
  probableCause: string
  impactedZone: string
  confidencePct: number
  suggestedActions: string[]
}

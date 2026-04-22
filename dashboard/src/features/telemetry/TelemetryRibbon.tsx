import { motion } from 'framer-motion'
import {
  Activity,
  Gauge,
  Network,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Skeleton } from '../../components/ui/skeleton'
import type { TelemetryRibbonMetrics } from '../../services/types'

interface TelemetryRibbonProps {
  metrics?: TelemetryRibbonMetrics
  isLoading: boolean
}

interface MetricDefinition {
  id: keyof TelemetryRibbonMetrics
  label: string
  formatter: (value: number) => string
  icon: LucideIcon
  accent: string
}

const metricDefinitions: MetricDefinition[] = [
  {
    id: 'activeConnections',
    label: 'Active Connections',
    formatter: (value) => value.toLocaleString(),
    icon: Network,
    accent: 'var(--tone-cyan)',
  },
  {
    id: 'throughputGbps',
    label: 'Throughput (Gbps)',
    formatter: (value) => `${value.toFixed(2)} Gbps`,
    icon: Activity,
    accent: 'var(--tone-teal)',
  },
  {
    id: 'p99LatencyMs',
    label: 'P99 Latency',
    formatter: (value) => `${value.toFixed(2)} ms`,
    icon: Gauge,
    accent: 'var(--tone-amber)',
  },
  {
    id: 'errorRatePct',
    label: 'Error Rate',
    formatter: (value) => `${value.toFixed(2)} %`,
    icon: TriangleAlert,
    accent: 'var(--tone-coral)',
  },
]

function TelemetrySkeleton() {
  return (
    <div className="telemetry-grid" role="presentation" aria-hidden="true">
      {Array.from({ length: 4 }, (_, index) => (
        <Card key={`metric-skeleton-${index}`}>
          <CardHeader>
            <Skeleton className="skeleton-line-sm" />
            <Skeleton className="skeleton-line-lg" />
          </CardHeader>
          <CardContent>
            <Skeleton className="skeleton-line-xs" />
          </CardContent>
        </Card>
      ))}
    </div>
  )
}

export function TelemetryRibbon({ metrics, isLoading }: TelemetryRibbonProps) {
  if (isLoading || !metrics) {
    return <TelemetrySkeleton />
  }

  return (
    <div className="telemetry-grid">
      {metricDefinitions.map((metric) => {
        const Icon = metric.icon
        const value = metrics[metric.id]
        return (
          <motion.div
            key={metric.id}
            layout
            whileHover={{ scale: 1.02 }}
            transition={{ type: 'spring', stiffness: 320, damping: 24 }}
          >
            <Card className="telemetry-card">
              <CardHeader>
                <CardDescription className="telemetry-label">{metric.label}</CardDescription>
                <CardTitle className="telemetry-value-row">
                  <span className="telemetry-value mono">{metric.formatter(value)}</span>
                  <span
                    className="telemetry-icon"
                    style={{
                      color: metric.accent,
                      boxShadow: `0 0 32px ${metric.accent}55`,
                    }}
                  >
                    <Icon size={18} />
                  </span>
                </CardTitle>
              </CardHeader>
              <CardContent>
                <div className="telemetry-footnote">
                  sampled every <span className="mono">200ms</span>
                </div>
              </CardContent>
            </Card>
          </motion.div>
        )
      })}
    </div>
  )
}

import { memo, useMemo } from 'react'
import { AnimatePresence, motion } from 'framer-motion'
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Cpu, MemoryStick, PlugZap, Radar, Server } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../../components/ui/card'
import { Badge } from '../../components/ui/badge'
import { Skeleton } from '../../components/ui/skeleton'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet'
import { useAppDispatch, useAppSelector } from '../../store/hooks'
import { setSelectedBackend } from '../../store/dashboardSlice'
import type { BackendNode, TelemetrySnapshot, TrafficPoint } from '../../services/types'

interface TrafficFlowMapProps {
  snapshot?: TelemetrySnapshot
  isLoading: boolean
}

const EMPTY_BACKENDS: BackendNode[] = []

function toClockLabel(timestamp: number) {
  return new Date(timestamp).toLocaleTimeString([], {
    minute: '2-digit',
    second: '2-digit',
  })
}

const TrafficTrendChart = memo(function TrafficTrendChart({ series }: { series: TrafficPoint[] }) {
  return (
    <ResponsiveContainer width="100%" height={272}>
      <AreaChart data={series} margin={{ top: 16, right: 12, left: -8, bottom: 0 }}>
        <defs>
          <linearGradient id="throughputGradient" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="rgba(37, 212, 190, 0.55)" />
            <stop offset="100%" stopColor="rgba(37, 212, 190, 0.03)" />
          </linearGradient>
        </defs>
        <CartesianGrid stroke="rgba(151, 179, 212, 0.15)" vertical={false} />
        <XAxis
          dataKey="timestamp"
          tickFormatter={toClockLabel}
          tickLine={false}
          axisLine={false}
          tick={{ fill: 'rgba(188, 202, 224, 0.7)', fontSize: 11 }}
          minTickGap={24}
        />
        <YAxis
          yAxisId="left"
          domain={[0, 'auto']}
          tick={{ fill: 'rgba(188, 202, 224, 0.7)', fontSize: 11 }}
          tickLine={false}
          axisLine={false}
        />
        <YAxis
          yAxisId="right"
          orientation="right"
          domain={[0, 'auto']}
          tick={{ fill: 'rgba(188, 202, 224, 0.7)', fontSize: 11 }}
          tickLine={false}
          axisLine={false}
        />
        <Tooltip
          contentStyle={{
            borderRadius: 12,
            border: '1px solid rgba(129, 153, 186, 0.3)',
            background: 'rgba(10, 12, 17, 0.93)',
            color: '#d9e3f4',
          }}
          formatter={(value, name) => {
            const numeric = Number(value ?? 0)
            const key = String(name ?? '')
            if (key === 'throughputGbps') {
              return [`${numeric.toFixed(2)} Gbps`, 'Throughput']
            }
            return [`${numeric.toFixed(2)} ms`, 'P99 Latency']
          }}
          labelFormatter={(value) => `T+ ${toClockLabel(Number(value))}`}
        />
        <Area
          yAxisId="left"
          type="monotone"
          dataKey="throughputGbps"
          stroke="rgba(37, 212, 190, 0.9)"
          strokeWidth={2.2}
          fill="url(#throughputGradient)"
        />
        <Line
          yAxisId="right"
          type="monotone"
          dataKey="p99LatencyMs"
          stroke="rgba(255, 186, 87, 0.95)"
          dot={false}
          strokeWidth={2}
        />
      </AreaChart>
    </ResponsiveContainer>
  )
})

function backendPointColor(status: BackendNode['status']) {
  if (status === 'offline') {
    return 'rgba(245, 99, 98, 0.95)'
  }
  if (status === 'degraded') {
    return 'rgba(250, 190, 87, 0.95)'
  }
  return 'rgba(53, 220, 197, 0.95)'
}

function TrafficFlowSkeleton() {
  return (
    <Card>
      <CardHeader>
        <Skeleton className="skeleton-line-sm" />
        <Skeleton className="skeleton-line-lg" />
      </CardHeader>
      <CardContent>
        <div className="flow-zone-grid">
          <Skeleton className="flow-skeleton-block" />
          <Skeleton className="flow-skeleton-block" />
        </div>
      </CardContent>
    </Card>
  )
}

export function TrafficFlowMap({ snapshot, isLoading }: TrafficFlowMapProps) {
  const dispatch = useAppDispatch()
  const selectedBackendId = useAppSelector((state) => state.dashboard.selectedBackendId)

  const backends = snapshot?.backends ?? EMPTY_BACKENDS
  const selectedBackend = backends.find((backend) => backend.id === selectedBackendId) ?? null

  const nodePositions = useMemo(() => {
    const total = Math.max(backends.length, 1)
    return backends.map((backend, index) => {
      const angle = (-75 + (150 / Math.max(total - 1, 1)) * index) * (Math.PI / 180)
      return {
        ...backend,
        x: 56 + Math.cos(angle) * 35,
        y: 50 + Math.sin(angle) * 38,
      }
    })
  }, [backends])

  if (isLoading || !snapshot) {
    return <TrafficFlowSkeleton />
  }

  return (
    <>
      <Card>
        <CardHeader className="flow-title-row">
          <div>
            <CardDescription>Traffic Flow Map</CardDescription>
            <CardTitle>Proxy fan-out and backend health</CardTitle>
          </div>
          <div className="flow-legend">
            <Badge variant="success">online</Badge>
            <Badge variant="warning">degraded</Badge>
            <Badge variant="danger">offline</Badge>
          </div>
        </CardHeader>
        <CardContent>
          <div className="flow-zone-grid">
            <div className="flow-glass-zone">
              <TrafficTrendChart series={snapshot.flowSeries} />
            </div>

            <div className="flow-map-zone glass-panel">
              <svg className="flow-lines" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
                {nodePositions.map((backend) => (
                  <line
                    key={`line-${backend.id}`}
                    x1="50"
                    y1="50"
                    x2={backend.x}
                    y2={backend.y}
                    stroke={backendPointColor(backend.status)}
                    strokeWidth="0.45"
                    strokeOpacity="0.72"
                  />
                ))}
              </svg>

              <button
                type="button"
                className="flow-node flow-node-proxy"
                aria-label="NetSentinel proxy node"
              >
                <Radar size={18} />
                <span>proxy</span>
              </button>

              <AnimatePresence>
                {nodePositions.map((backend) => (
                  <motion.button
                    key={backend.id}
                    type="button"
                    className={`flow-node flow-node-backend status-${backend.status}`}
                    style={{ left: `${backend.x}%`, top: `${backend.y}%` }}
                    whileHover={{ scale: 1.02 }}
                    initial={{ opacity: 0, scale: 0.82 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, scale: 0.86 }}
                    transition={{ duration: 0.24 }}
                    onClick={() => dispatch(setSelectedBackend(backend.id))}
                    aria-label={`Open details for ${backend.name}`}
                  >
                    <Server size={14} />
                    <span className="mono">{backend.name}</span>
                  </motion.button>
                ))}
              </AnimatePresence>
            </div>
          </div>

          <div className="offline-alert-wrap" aria-live="polite">
            <AnimatePresence>
              {backends
                .filter((backend) => backend.status === 'offline')
                .map((backend) => (
                  <motion.div
                    key={`offline-${backend.id}`}
                    className="offline-chip"
                    initial={{ opacity: 0, y: -8 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -8 }}
                  >
                    {backend.name} is offline
                  </motion.div>
                ))}
            </AnimatePresence>
          </div>
        </CardContent>
      </Card>

      <Sheet
        open={Boolean(selectedBackend)}
        onOpenChange={(isOpen) => {
          if (!isOpen) {
            dispatch(setSelectedBackend(null))
          }
        }}
      >
        <SheetContent>
          {selectedBackend ? (
            <>
              <SheetHeader>
                <SheetTitle>{selectedBackend.name}</SheetTitle>
                <SheetDescription>
                  {selectedBackend.zone} · {selectedBackend.status}
                </SheetDescription>
              </SheetHeader>

              <div className="sheet-stat-grid">
                <div className="sheet-stat-card">
                  <Cpu size={14} />
                  <strong className="mono">{selectedBackend.cpuPct.toFixed(1)}%</strong>
                  <span>CPU</span>
                </div>
                <div className="sheet-stat-card">
                  <MemoryStick size={14} />
                  <strong className="mono">{selectedBackend.ramPct.toFixed(1)}%</strong>
                  <span>RAM</span>
                </div>
                <div className="sheet-stat-card">
                  <PlugZap size={14} />
                  <strong className="mono">{selectedBackend.socketCount}</strong>
                  <span>Sockets</span>
                </div>
              </div>

              <div className="sheet-bar-row">
                <label htmlFor="latencyBar">latency</label>
                <progress
                  id="latencyBar"
                  className="sheet-progress"
                  value={selectedBackend.latencyMs}
                  max={70}
                />
                <span className="mono">{selectedBackend.latencyMs.toFixed(2)} ms</span>
              </div>
              <div className="sheet-bar-row">
                <label htmlFor="rpsBar">requests/s</label>
                <progress
                  id="rpsBar"
                  className="sheet-progress"
                  value={selectedBackend.reqPerSec}
                  max={500}
                />
                <span className="mono">{selectedBackend.reqPerSec}</span>
              </div>
            </>
          ) : null}
        </SheetContent>
      </Sheet>
    </>
  )
}

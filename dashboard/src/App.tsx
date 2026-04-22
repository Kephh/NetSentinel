import { useEffect } from 'react'
import { motion } from 'framer-motion'
import { ShieldCheck, Signal } from 'lucide-react'
import { TelemetryRibbon } from './features/telemetry/TelemetryRibbon'
import { TrafficFlowMap } from './features/traffic/TrafficFlowMap'
import { LogStream } from './features/logs/LogStream'
import { useTelemetry } from './hooks/useTelemetry'
import { useLogStream } from './hooks/useLogStream'
import { useAppDispatch, useAppSelector } from './store/hooks'
import { setSystemHealth } from './store/dashboardSlice'

function statusLabel(status: 'green' | 'yellow' | 'red') {
  if (status === 'red') {
    return 'critical'
  }
  if (status === 'yellow') {
    return 'elevated'
  }
  return 'stable'
}

function App() {
  const dispatch = useAppDispatch()
  const telemetryQuery = useTelemetry()
  const logsQuery = useLogStream()
  const systemHealth = useAppSelector((state) => state.dashboard.systemHealth)

  useEffect(() => {
    if (telemetryQuery.data?.healthStatus) {
      dispatch(setSystemHealth(telemetryQuery.data.healthStatus))
    }
  }, [dispatch, telemetryQuery.data?.healthStatus])

  return (
    <div className="dashboard-shell">
      <div className="background-grid" aria-hidden="true" />

      <header className="top-bar glass-panel">
        <div className="top-bar-left">
          <div className="brand-chip">
            <ShieldCheck size={16} />
            <span>NetSentinel Command Deck</span>
          </div>
          <h1>Enterprise Dark-Core Operations</h1>
        </div>

        <div className={`health-breadcrumb health-${systemHealth}`}>
          <Signal size={14} />
          <span className="mono">system health</span>
          <strong>{statusLabel(systemHealth)}</strong>
        </div>
      </header>

      <motion.main
        className="dashboard-main"
        initial={{ opacity: 0, y: 14 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.36, ease: 'easeOut' }}
      >
        <section className="zone zone-telemetry">
          <TelemetryRibbon
            metrics={telemetryQuery.data?.metrics}
            isLoading={telemetryQuery.isLoading}
          />
        </section>

        <section className="zone zone-flow">
          <TrafficFlowMap snapshot={telemetryQuery.data} isLoading={telemetryQuery.isLoading} />
        </section>

        <section className="zone zone-logs">
          <LogStream logs={logsQuery.data} isLoading={logsQuery.isLoading} />
        </section>
      </motion.main>
    </div>
  )
}

export default App

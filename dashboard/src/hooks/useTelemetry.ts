import { useQuery } from '@tanstack/react-query'
import { fetchTelemetrySnapshot } from '../services/telemetryService'

export function useTelemetry() {
  return useQuery({
    queryKey: ['telemetry-snapshot'],
    queryFn: fetchTelemetrySnapshot,
    refetchInterval: 200,
    staleTime: 100,
    refetchOnWindowFocus: false,
  })
}

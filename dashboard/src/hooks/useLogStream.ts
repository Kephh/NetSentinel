import { useQuery } from '@tanstack/react-query'
import { fetchLogStream } from '../services/telemetryService'

export function useLogStream() {
  return useQuery({
    queryKey: ['log-stream'],
    queryFn: fetchLogStream,
    refetchInterval: 450,
    staleTime: 300,
    refetchOnWindowFocus: false,
  })
}

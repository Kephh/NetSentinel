import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import type { HealthStatus } from '../services/types'

interface DashboardState {
  selectedBackendId: string | null
  systemHealth: HealthStatus
}

const initialState: DashboardState = {
  selectedBackendId: null,
  systemHealth: 'green',
}

const dashboardSlice = createSlice({
  name: 'dashboard',
  initialState,
  reducers: {
    setSelectedBackend(state, action: PayloadAction<string | null>) {
      state.selectedBackendId = action.payload
    },
    setSystemHealth(state, action: PayloadAction<HealthStatus>) {
      state.systemHealth = action.payload
    },
  },
})

export const { setSelectedBackend, setSystemHealth } = dashboardSlice.actions
export default dashboardSlice.reducer

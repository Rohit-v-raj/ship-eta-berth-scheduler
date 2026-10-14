import { useQuery, useQueryClient } from '@tanstack/react-query'
import { getBerthPlan, getMaintenance, getPort, getVisit, getVisits } from './scheduler'

/**
 * Server state, read through TanStack Query. The port never changes while the
 * app is open; visits and the berth plan are polled.
 */

export const POLL_MS = 5000

export const queryKeys = {
  port: ['port'],
  visits: (filters = {}) => ['visits', filters],
  visit: (visitRef) => ['visit', visitRef],
  berthPlan: (range) => ['berth-plan', range],
  maintenance: ['maintenance'],
  events: (filters) => ['events', filters],
}

export function usePort() {
  return useQuery({ queryKey: queryKeys.port, queryFn: getPort, staleTime: Infinity, retry: 1 })
}

/** Open visits in queue order. Pass `vessel` to narrow to one vessel code. */
export function useVisits(filters = {}, options = {}) {
  return useQuery({
    queryKey: queryKeys.visits(filters),
    queryFn: () => getVisits(filters),
    refetchInterval: POLL_MS,
    ...options,
  })
}

export function useVisit(visitRef) {
  return useQuery({
    queryKey: queryKeys.visit(visitRef),
    queryFn: () => getVisit(visitRef),
    enabled: Boolean(visitRef),
    refetchInterval: POLL_MS,
  })
}

/** `range` is { from, to } as ISO strings, stable between renders. */
export function useBerthPlan(range) {
  return useQuery({
    queryKey: queryKeys.berthPlan(range),
    queryFn: () => getBerthPlan(range),
    refetchInterval: POLL_MS,
    placeholderData: (previous) => previous,
  })
}

/** Maintenance windows not yet over, on every berth. */
export function useMaintenance() {
  return useQuery({ queryKey: queryKeys.maintenance, queryFn: getMaintenance, refetchInterval: POLL_MS })
}

/** After an action, everything read from the scheduler may have changed. */
export function useRefreshPort() {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: ['visits'] }),
      client.invalidateQueries({ queryKey: ['visit'] }),
      client.invalidateQueries({ queryKey: ['berth-plan'] }),
      client.invalidateQueries({ queryKey: ['events'] }),
      client.invalidateQueries({ queryKey: ['maintenance'] }),
    ])
}

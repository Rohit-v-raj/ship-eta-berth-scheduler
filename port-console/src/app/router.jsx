import { createBrowserRouter, Navigate } from 'react-router-dom'
import { Notice } from '@/components/ui/controls'
import { EventsPage } from '@/features/events/EventsPage'
import { OperatorPage } from '@/features/operator/OperatorPage'
import { HarbourPage } from '@/features/planner/HarbourPage'
import { HOME } from '@/session/permissions'
import { RequireRole } from '@/session/RequireRole'
import { useSession } from '@/session/session'
import { AppShell } from './AppShell'

/** Each role lands on its own page. */
function Home() {
  const { session } = useSession()
  return <Navigate to={HOME[session.role]} replace />
}

/**
 * The pages, and which permission each one needs. A page behind RequireRole
 * is simply not shown to a seat without that permission.
 */
export const routes = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Home /> },
      { path: 'planner', element: <RequireRole action="plan.view"><HarbourPage /></RequireRole> },
      { path: 'operator', element: <RequireRole action="vessel.view"><OperatorPage /></RequireRole> },
      { path: 'operator/:vesselId', element: <RequireRole action="vessel.view"><OperatorPage /></RequireRole> },
      { path: 'events', element: <RequireRole action="events.view"><EventsPage /></RequireRole> },
      { path: '*', element: <Notice title="There is no such page" className="mt-20">Use the tabs above to find your way.</Notice> },
    ],
  },
]

export const createRouter = () => createBrowserRouter(routes)

import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { ThemeProvider, TimeProvider } from '@/app/providers'
import { TooltipProvider } from '@/components/ui/controls'
import { ROLES } from '@/session/permissions'
import { DemoSessionProvider } from '@/session/session'

/** Render a component inside everything a page normally has around it. */
export function renderWithApp(ui, { role = ROLES.PLANNER, route = '/', zone = 'UTC' } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <DemoSessionProvider initialRole={role}>
          <TimeProvider portZone={zone}>
            <TooltipProvider>
              <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
            </TooltipProvider>
          </TimeProvider>
        </DemoSessionProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
}

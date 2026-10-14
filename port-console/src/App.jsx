import { useState } from 'react'
import { RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ThemeProvider } from '@/app/providers'
import { createRouter } from '@/app/router'
import { TooltipProvider } from '@/components/ui/controls'
import { DemoSessionProvider } from '@/session/session'

/**
 * The providers every screen relies on, outermost first: server state, theme,
 * the session (who is using the console), then the pages.
 *
 * DemoSessionProvider is the one line to change when login is built.
 */
export default function App() {
  const [queryClient] = useState(
    () => new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } }),
  )
  const [router] = useState(createRouter)

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <DemoSessionProvider>
          <TooltipProvider delayDuration={250}>
            <RouterProvider router={router} />
          </TooltipProvider>
        </DemoSessionProvider>
      </ThemeProvider>
    </QueryClientProvider>
  )
}

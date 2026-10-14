import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import * as Menu from '@radix-ui/react-dropdown-menu'
import { Anchor, Cable, Check, ChevronDown, Moon, PlugZap, Sun, UserRound } from 'lucide-react'
import { Toaster } from 'sonner'
import { usePort } from '@/api/queries'
import { Button, Notice } from '@/components/ui/controls'
import { StoryDock } from '@/features/story/StoryDock'
import { StoryProvider } from '@/features/story/StoryProvider'
import { WireInspector } from '@/features/wire/WireInspector'
import { useWireLog } from '@/features/wire/wireStore'
import { cn } from '@/lib/cn'
import { clock } from '@/lib/time'
import { HOME, ROLE_LABELS, ROLES } from '@/session/permissions'
import { useCan, useSession } from '@/session/session'
import { TimeProvider, useTheme, useTime } from './providers'

/**
 * The frame every page sits in: header, the page, the story dock and the wire
 * inspector. The port is loaded here once; without it nothing can be drawn, so
 * a scheduler that does not answer is reported here rather than on each page.
 */
export function AppShell() {
  const port = usePort()
  const [wireOpen, setWireOpen] = useState(false)

  return (
    <TimeProvider portZone={port.data?.timeZone}>
      <div className="flex h-full flex-col">
        <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-surface focus:px-3 focus:py-2">
          Skip to the page
        </a>
        <Header port={port.data} onOpenWire={() => setWireOpen(true)} />
        <main id="main" className="min-h-0 flex-1 overflow-y-auto">
          {port.data ? (
            <StoryProvider port={port.data}>
              <div className="flex min-h-full flex-col">
                <div className="flex-1"><Outlet context={{ port: port.data }} /></div>
                <div className="sticky bottom-0 z-20"><StoryDock /></div>
              </div>
            </StoryProvider>
          ) : port.isError ? (
            <Notice icon={PlugZap} tone="error" title="The scheduler is not answering" className="mt-20">
              The console could not reach the scheduler on port 8080. Start it with <code className="font-mono">.\gradlew bootRun</code> in{' '}
              <code className="font-mono">port-scheduler-api</code>, then reload.
            </Notice>
          ) : (
            <Notice title="Loading the port" className="mt-20" />
          )}
        </main>
        <WireInspector open={wireOpen} onOpenChange={setWireOpen} />
        <Toaster position="top-center" toastOptions={{ className: 'font-sans' }} />
      </div>
    </TimeProvider>
  )
}

function Header({ port, onOpenWire }) {
  const canPlan = useCan('plan.view')
  const canOperate = useCan('vessel.view')
  const wireCount = useWireLog().length
  return (
    <header className="flex h-13 shrink-0 items-center gap-2 border-b border-line bg-surface px-3 whitespace-nowrap sm:gap-4 sm:px-4">
      <div className="flex items-center gap-2">
        <Anchor className="size-5 shrink-0 text-accent" aria-hidden="true" />
        <span className="hidden text-[15px] font-semibold md:inline">Port Call Console</span>
        {port && (
          <span className="hidden rounded-full border border-line px-2 py-0.5 text-xs text-ink-2 xl:inline">
            {port.name} · {port.unLocode}
          </span>
        )}
      </div>

      <nav aria-label="Pages" className="flex min-w-0 items-center gap-1 overflow-x-auto">
        {canPlan && <Tab to="/planner">Harbour</Tab>}
        {canOperate && <Tab to="/operator">My vessel</Tab>}
        <Tab to="/events">Port call events</Tab>
      </nav>

      <div className="ml-auto flex items-center gap-1.5">
        <ZoneToggle />
        <Button variant="ghost" size="sm" onClick={onOpenWire}>
          <Cable className="size-4" aria-hidden="true" />
          <span className="hidden md:inline">Wire</span>
          {wireCount > 0 && <span className="tabular rounded-full bg-surface-2 px-1.5 text-[11px]">{wireCount}</span>}
        </Button>
        <ThemeToggle />
        <SeatSwitcher />
      </div>
    </header>
  )
}

function Tab({ to, children }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'rounded-md px-3 py-1.5 text-[13px] font-medium',
          isActive ? 'bg-surface-2 text-ink' : 'text-ink-2 hover:bg-surface-2 hover:text-ink',
        )
      }
    >
      {children}
    </NavLink>
  )
}

/** The clock, and the choice of zone every time on the screen is shown in. */
function ZoneToggle() {
  const { now, zone, utc, toggleUtc, portZone } = useTime()
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={toggleUtc}
      aria-pressed={utc}
      title={utc ? `Showing UTC. Click for port time (${portZone}).` : `Showing port time (${portZone}). Click for UTC.`}
    >
      <span className="tabular font-semibold">{clock(now, zone)}</span>
      <span className="hidden text-xs text-ink-2 sm:inline">{utc ? 'UTC' : 'port time'}</span>
    </Button>
  )
}

function ThemeToggle() {
  const { theme, toggleTheme } = useTheme()
  const dark = theme === 'dark'
  return (
    <Button variant="ghost" size="sm" onClick={toggleTheme} aria-label={dark ? 'Use the light theme' : 'Use the dark theme'} className="px-2">
      {dark ? <Sun className="size-4" aria-hidden="true" /> : <Moon className="size-4" aria-hidden="true" />}
    </Button>
  )
}

/**
 * Who is at the console. With no login yet this is a choice of seat; when
 * login is added the same place shows the signed-in person instead.
 */
function SeatSwitcher() {
  const { session, switchSeat } = useSession()
  const navigate = useNavigate()
  const choose = (role) => {
    switchSeat(role)
    navigate(HOME[role])
  }
  return (
    <Menu.Root>
      <Menu.Trigger asChild>
        <Button size="sm" aria-label={`Seat: ${session.displayName}. Change seat.`}>
          <UserRound className="size-4" aria-hidden="true" />
          <span className="hidden lg:inline">{session.displayName}</span>
          <ChevronDown className="size-3.5 text-ink-2" aria-hidden="true" />
        </Button>
      </Menu.Trigger>
      <Menu.Portal>
        <Menu.Content align="end" sideOffset={6} className="z-50 w-64 rounded-md border border-line bg-surface p-1 shadow-panel">
          <Menu.Label className="px-2.5 py-1.5 text-xs text-ink-2">
            {session.demo ? 'Demo seats, no login. Pick the role to act as.' : 'Signed in as'}
          </Menu.Label>
          {Object.values(ROLES).map((role) => (
            <Menu.Item
              key={role}
              onSelect={() => choose(role)}
              className="flex cursor-pointer items-center justify-between rounded-sm px-2.5 py-2 text-[13px] outline-none data-[highlighted]:bg-surface-2"
            >
              {ROLE_LABELS[role]}
              {session.role === role && <Check className="size-4" aria-label="current seat" />}
            </Menu.Item>
          ))}
        </Menu.Content>
      </Menu.Portal>
    </Menu.Root>
  )
}

# The Port Call Console — Explained

> **What this doc is:** a walkthrough of the React app in `port-console/`: what
> each screen is for, the design rules behind it, how the code is laid out, and
> how login and roles will fit in later. Read it alongside
> `spring-boot-service-explained.md`, which explains the service it talks to.
> `END-TO-END-GUIDE.md` lists the request behind every button.

---

## 1. The one-sentence summary

The console is the screen for the scheduler: a **terminal planner** sees the
berth plan and who is coming, a **vessel operator** works out when the ship can
arrive, sends that estimate and answers the time the terminal asks for, and
anyone can watch the exchange between the two as DCSA Port Call events.

It adds no logic of its own. Every figure on screen comes from the scheduler;
the console only reads it, shows it, and sends the requests a person asks for.

---

## 2. The screens

| Screen | Seat | The question it answers |
|---|---|---|
| **Harbour** `/planner` | Planner | Is my berth plan safe for the hours ahead? |
| **Visit** (a drawer on Harbour) | Planner | Who said which time, and when? |
| **My vessel** `/operator/{vessel}` | Operator | When can I arrive, when am I wanted, and what speed meets that? |
| **Port call events** `/events` | Either | What has been exchanged, in DCSA terms? |
| **Wire inspector** (header) | Either | What did that click send? |
| **Stories** (bar at the bottom) | Either | Show me the whole exchange |

**Harbour** has four parts, in the order a planner reads them: four figures
(berths in use, ships waiting, next berth free, answers awaited); a map with
each ship at the position it last gave; the **berth plan** against time; and
every visit as a table. No speed appears anywhere on it, because the terminal
never sends one. A requested time changes on screen by itself when the
terminal re-issues it. An answer that is late is flagged in the table and
counted in the fourth figure: "No answer for 1 h 20 min". So are a ship past
its agreed time that has not arrived ("Not arrived, 40 min past agreed
time") and one that owes a fresh estimate ("Estimate due"). Below the table,
**Berth maintenance** schedules a window (berth, from, to, reason), lists
those planned and removes one; a window over a ship's booking is refused, and
the refusal is shown beside the form.

**The berth plan** has one row per berth and a row for waiting ships. Time
runs left to right, with a line at "now". A berth window is a light wash while
the berth is only reserved and solid once the ship is alongside. For a waiting
ship, a dotted line joins when it would arrive to when it is asked to arrive:
that gap is the time it would otherwise spend at anchor, and the time a slower
passage can use up instead. Maintenance is a hatched block on its berth's
lane, and a berth under maintenance now says "maint. to 16:00".

**Visit** shows the four arrival times side by side, the last position the
ship gave, and the ledger: every time anyone reported, who reported it and
when. The planner's two actions are here: *Release* for a ship alongside, and
*Cancel this call* for a ship that has not arrived. A late answer is pointed
out above the button; whether to cancel is left to the planner.

**My vessel** has three parts, listed here in the order the conversation runs
(on screen, *Where you stand* sits above the calculator):

1. **Your calculator.** Position, speed and course (clicking the map fills in
   the position). *Calculate only* asks the ETA model when the ship would
   arrive and sends nothing. *Send estimated arrival to the terminal* sends
   that answer as the ship's estimate, with the position, which opens the call.
2. **Where you stand.** The terminal's answer: a berth or a place in the queue,
   and the requested arrival in large type. Under it, the speed that would
   meet that time, worked out by the calculator.
3. **Your answer.** Accept, propose another time, or omit the call. Once the
   ship holds a berth: report arrival, then departure.

If the terminal cancels the call, this page says so, with the reason, instead
of going blank. It also says by when the terminal expects the next estimate.
The calculator has optional fields for the ship's size (length overall, beam,
draft); they travel with the estimate, and the terminal gives the ship only a
berth it fits.

**Stories** play the conversation step by step. A story is a list of real
requests, not a simulation: each step sends one request to the scheduler and
then describes, from the answer, what happened. The ships are invented names
at open-sea positions. Five are built in: *The port fills up*, *A quiet port*,
*A change of plan*, *A ship goes quiet* (a silent ship's call is cancelled
and the ships behind it are given new times) and *A ship falls behind* (a
booked ship sends a much later estimate and its berth goes to a waiting ship
that can come sooner).

---

## 3. The design rules

- **One encoding for the four kinds of time.** Estimated is a hollow circle,
  requested a hollow diamond, planned (the request accepted) a filled diamond,
  actual a filled circle. The same marks appear on the map, the plan, the
  ledger and the event feed, always with a word beside them. Shape carries the
  meaning, never colour alone.
- **Time is the main axis.** A time is shown as a clock time plus how far away
  it is ("06:27, in 13 h"). The header switches every time on screen between
  port time and UTC.
- **The server decides.** A button shows that it is working, then shows what
  the scheduler said. Nothing is assumed in advance. A refusal appears beside
  the button that caused it.
- **The system flags, a person decides.** The scheduler says when an answer
  is due; the console compares that with the clock and shows the lateness. It
  never acts on it. Cancelling a call is a button, a confirmation and the
  planner's permission.
- **Each side sees its own sums.** The terminal sends a time; the speed that
  meets it is the ship's business. So the speed appears only on the operator's
  page, labelled as a calculation, and the planner's screens show times.
- **Honest by design.** The model's ETA is shown beside plain distance over
  speed. A calculation that was not sent says so. No fuel or CO2 figure
  appears, because the system does not calculate one. The assistant is left
  out until its tools return real data.
- **Nothing is readable only by sight.** The Arrivals table carries everything
  the map and the plan show. Every control works from the keyboard, and
  dialogs trap focus.
- **Colours come from tokens.** `src/styles/tokens.css` defines every colour by
  role, once for light and once for dark. The two berth-plan colours were
  checked for colour-blind separation on both backgrounds. Status colours
  (good, warning, serious, critical) are used only for state, with an icon and
  words.

---

## 4. How it is built

| Piece | Choice | Why |
|---|---|---|
| Build tool | Vite | Fast dev server; forwards `/api` to Spring Boot |
| UI | React, JavaScript (JSX) | The API shapes are documented in `src/api/shapes.js` as JSDoc comments |
| Pages | React Router | One route per screen, each behind a permission |
| Server data | TanStack Query | Polls visits and the plan every 5 s; refreshes after an action |
| Styling | Tailwind CSS on design tokens | One place for colour, light and dark |
| Dialogs, menus, tooltips | Radix UI | Keyboard and screen-reader behaviour done properly |
| Map | MapLibre with OpenFreeMap tiles | A real coastline, no API key |
| Berth plan | SVG drawn by hand, no charting library | Full control of a domain-specific chart |
| Forms | React Hook Form with Zod | The calculator checks what the scheduler checks |
| Tests | Vitest, Testing Library, MSW | MSW answers the API from fixtures |

```
port-console/src/
  app/         the page frame, the router, theme and clock providers
  session/     who is at the console, and what each role may do
  api/         http.js (the one way out), scheduler.js, dcsa.js, queries.js, shapes.js
  components/  buttons, dialogs, and the marks and chips for port-call facts
  features/    planner/  operator/  events/  map/  story/  wire/
  lib/         time, visit and geometry helpers (pure functions, tested)
  styles/      tokens.css, globals.css
  test/        fixtures and the mock scheduler
```

**No CORS setting is needed.** In development the browser talks only to Vite
(port 5173), which forwards `/api` to Spring Boot (port 8080). The page and the
API share one origin.

**One way out.** Every request goes through `src/api/http.js`. That is why
three things happen in one place: a readable message for any failure, the wire
log, and (later) a login token on each request.

**Two files, one per kind of request.** They match the scheduler's controllers:

| File | Talks to | What it sends |
|---|---|---|
| `src/api/dcsa.js` | `/api/port-call/v2/events` | Everything that changes a call, as DCSA events: an estimated arrival (`EST`, with the position) to open or replan it, a planned arrival (`PLN`) to accept, actual arrival and departure (`ACT`), an omitted port call |
| `src/api/scheduler.js` | `/api/operator/eta`, `/api/port`, `/api/visits` | The calculator's question, the console's reads, and the planner's actions: release a berth, cancel a call, schedule and remove maintenance |

The scheduler answers `200` even when it refuses an event, with the refusal in
`feedbackElements`; `sendEvents` turns an `ERROR` there into a thrown error so
a refusal cannot pass for success.

**The calculator is asked twice when an estimate is sent.** Once for the
arrival time to send. Then, after the terminal has answered, once more with
the same position, because only then is there a requested arrival to work out
a speed for. The page asks again by itself whenever the terminal changes its
request.

---

## 5. Roles now, login later

There is no login yet. The header offers two **seats**, planner and operator,
and the choice is remembered in the browser. (`?seat=operator` on an address
opens it in that seat.) The app is built so that login replaces the seat
switcher and nothing else:

- **`session/session.jsx`** defines the session (`role`, display name, the
  vessels an operator may act for). Today `DemoSessionProvider` fills it from
  the seat. A provider that reads a login token would supply the same shape.
- **`session/permissions.js`** is the one list of who may do what. Screens ask
  `useCan('berth.release')`; they never test a role directly. Pages sit behind
  `RequireRole`, and a seat without the permission sees "Not allowed".
- **`api/http.js`** has the single place where an `Authorization` header will
  be added, and a hook for `401` and `403` answers.

The server does not check roles today: every endpoint is open. When Spring
Security is added, these are the rules it has to enforce:

| Role | Endpoints |
|---|---|
| Planner | `GET /api/port/**`, `GET /api/visits/**`, `PUT /api/port/berths/*/release`, `PUT /api/visits/*/cancel`, `POST` and `DELETE /api/port/berths/*/maintenance/**` |
| Operator | `POST /api/operator/eta`, `POST /api/port-call/v2/events`, and its own visit from `GET /api/visits` |
| Both | `GET /api/port-call/v2/events` |

One thing is missing for that: nothing in the data model yet says which
operator a vessel belongs to, so "its own visit" cannot be enforced until that
link exists.

---

## 6. How to run it

```powershell
cd port-console
npm install          # once
npm run dev          # http://localhost:5173, needs both services running
npm run dev:mock     # every screen from sample data, nothing else running
npm test             # 69 tests
npm run lint
npm run build        # a production build in dist/
```

The map background is fetched from the internet. Without a connection the map
says so and the rest of the page works.

---

## 7. Limits to know about

- **No login and no server-side roles.** The seats decide only what the screen
  offers.
- **A ship stays where it last said it was.** The terminal learns a position
  only when an estimate carries one, so nothing moves between estimates, and
  the map's ship marks point at the port because the standard's message has no
  course in it.
- **The model's ETA reads late near port.** The console shows it as the
  scheduler gives it, next to distance over speed. See the README's known
  limits.
- **The speed is a straight-line sum.** It is the distance to the berth as the
  crow flies over the time available. A real passage is longer, so the figure
  is a starting point, not a passage plan.
- **Nothing alerts anyone.** A late answer shows on the planner's screen and
  nowhere else. Nobody who is not looking at the console is told, and the ship
  is not told its answer is overdue.
- **Stories assume every berth is available.** *Reset the port* closes the
  visits in progress but leaves maintenance alone, and a window under way can
  stop a story's first step.
- **Stories write to the real database.** They open and close visits like any
  other client. *Reset the port* closes what is open; the history stays in the
  ledger.
- **The console is served by Vite.** Serving the built files from Spring Boot,
  so one process runs everything, is not set up.

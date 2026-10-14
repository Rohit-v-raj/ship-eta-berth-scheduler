# AI-Powered Ship ETA Predictor & Berth Scheduler

**The aim is to cut shipping emissions.** A ship that hurries to port and then
waits at anchor for a berth has burned fuel for nothing. This project predicts
when ships will arrive from raw **AIS** (Automatic Identification System)
tracking data, then plans their berths: a free berth or a place in the queue, a
requested arrival time, and a slower speed that brings the ship in when its
berth is ready. An end-to-end system spanning a Python data/model pipeline and
a Java Spring Boot backend, built as the marine side of a terminal operating
system and being brought in line with the **DCSA Port Call** standard. A React
console puts it on screen: the berth plan against time, the ships on a map, and
the exchange between terminal and ship.

```
  Vessel operator ──┐    ┌───────────────────────────┐      ┌──────────────────────┐
  Terminal staff ───┼──▶ │  Spring Boot API (Java 21) │ ───▶ │  FastAPI service     │
  Carrier system ───┘    │  ETA → berth or queue      │ HTTP │  (Python)            │
   (DCSA events)         │  requested arrival, speed  │ ◀─── │  XGBoost ETA model   │
                         └─────────────┬─────────────┘ ETA  └──────────────────────┘
                                       ▼
                               MySQL (10 tables)
```

## Why: decarbonisation

Shipping produces close to 3% of the world's greenhouse gas emissions, and the
IMO's 2023 strategy aims for net zero from international shipping by around
2050. One measure needs no new fuel and no new ship: stop vessels racing to a
port that is not ready for them.

Fuel burn rises steeply with speed. A ship told early that its berth will be
free at 14:00 can slow down and arrive at 14:00, instead of arriving at 11:00
and waiting three hours at anchor. The distance is the same; the fuel burned,
and the CO2 emitted, is less. This is **just-in-time arrival**.

An IMO study of container ship voyages in 2019 (*Just In Time Arrival:
Emissions reduction potential in global container shipping*, 2022, carried out
by MarineTraffic for the GreenVoyage2050 Low Carbon Global Industry Alliance)
estimated what it could save:

| Speed optimised over | Mean fuel saving per voyage |
|---|---|
| the whole voyage | 14.16% |
| the last 24 hours | 5.90% |
| the last 12 hours | 4.23% |

Just-in-time arrival needs two things, and they are the two halves of this
project:

1. **An arrival time the terminal can trust**, so it knows when each ship will
   really arrive. This is the ETA model.
2. **A berth plan that answers with a time and a speed**, so the ship knows
   when it is wanted and how fast to sail. This is the scheduler.

**What this project does not claim.** It gives the advice; it does not yet
calculate the fuel or CO2 a ship saves by following it. The figures above are
the study's, not measurements of this system. Its voyages are also short (a
median of about four hours of approach), so the room to slow down is smaller
than in the study's shortest case.

## Results

Trained on **~30M AIS position reports** (Norwegian west coast, Jul–Oct 2020)
→ 688 labelled port-approach voyages, evaluated on **held-out vessels** the
model has never seen:

| Model | Test MAE | Notes |
|---|---|---|
| Naive physics prior (distance ÷ speed) | 161.5 min | the baseline to beat |
| **XGBoost** (production model) | **69 min** (R² 0.62) | **57% better than naive** |
| Bi-GRU (PyTorch, 12-step sequences) | 74.9 min | built, measured, lost — see below |

The naive baseline does badly on slow or stationary pings, which are a quarter
of the data. On moving pings alone the gain over it is 18% (67.1 vs 82.3 min).

## What the scheduler does

- **A call opens with an estimate.** A ship's operator sends an estimated
  arrival; the terminal gives the ship the first free berth, or a place in a
  virtual queue.
- **A time, not just a number.** The terminal answers every estimate with a
  *requested arrival*: the ship's own estimate if a berth is free, otherwise
  the time a berth is expected to come free.
- **The operator answers.** Accept the requested time, propose the time the
  ship can make, or omit the call.
- **A berth is held from the terminal's answer**, not from the ship's
  acceptance, so a slow reply never loses it to another ship. A reply that is
  an hour late is flagged on the planner's screen, and the planner may cancel
  the call. Nothing is cancelled automatically.
- **One route for all of it.** Estimates, acceptances, arrivals, departures
  and omitted calls are DCSA Port Call events. There is no second way in.
- **The queue moves by itself.** A freed berth goes to the ship that has
  waited longest, and the terminal issues it a new request. Whenever the berth
  plan changes (a ship berths early or late, a call is dropped, an estimate
  moves), every ship still waiting is given a new time if its own has moved.
- **A late ship gives its berth up.** If a ship holding a berth sends an
  estimate more than 4 hours after the time it agreed, and a waiting ship can
  be alongside sooner, the berth goes to that ship. The late one keeps its
  place in line.
- **Berths fit the ships, and maintenance is planned around.** A ship is given
  only a berth its length, beam and draft fit, and is turned away if it fits
  none. Berth maintenance is recorded in advance; no ship is given a berth for
  a stay that would run into it.
- **Speed is the ship's sum.** The terminal sends a time, as the standard
  says, and never a speed. The operator's calculator runs the ETA model and
  works out the speed that meets the requested time, never below an economical
  speed for the ship's type. This is the just-in-time arrival idea: slow down
  rather than wait, and burn less fuel doing it.
- **Nothing is lost on restart.** State lives in MySQL: visits, berth
  allocations, each position a ship gave, and a ledger of every timestamp and
  who reported it.

## The console

`port-console/` is a React app for the two people in the exchange, with a seat
for each (there is no login yet; roles are built in so one can be added).

- **Terminal planner:** berths in use and ships waiting, the berth plan against
  time, each ship on a map at the position it last gave, and every visit in a
  table. A visit opens to show its four times, who gave each, and the ledger.
  A late answer is flagged; a call whose ship has not arrived can be cancelled.
  Berth maintenance is scheduled and shown on the plan.
- **Vessel operator:** a calculator (click the map for the position) for the
  model's ETA and the speed that meets the terminal's time; then send the
  estimate, accept the request, propose another time or omit.
- **Port call events:** the DCSA feed, filterable, with the raw message per row.
- **Wire inspector:** every request a click sent and the scheduler's answer.
- **Stories:** the whole berth conversation played one step at a time against
  the real scheduler, each step explained from the answer it got.

It shows what the system says and adds no claims of its own: the speed is
marked as the operator's own calculation, and the model's ETA sits beside plain
distance over speed. See `docs/frontend-explained.md`.

## Working towards the DCSA Port Call standard

[DCSA](https://dcsa.org) publishes open standards for container shipping. Its
Port Call standard (version 2.0) defines how carriers, terminals and ports
exchange berth and service times. This project is being aligned with it step
by step. It is **not certified** and has not been run through DCSA's
conformance tests.

**Followed today**

- **The four kinds of time** from IMO circular FAL.5/Circ.52, which DCSA
  implements: *estimated* (the ship), *requested* (the terminal), *planned*
  (the ship's acceptance of the request) and *actual*. Each is recorded with
  who issued it, and a new request cancels an earlier acceptance.
- **The standard's two endpoints**, `GET /events` and `POST /events`, with its
  field names, nesting, codes (`EST` / `REQ` / `PLN` / `ACT`, `BERTH`, `ARRI`,
  `DEPA`), headers (`API-Version`, `Next-Page-Cursor`), cursor paging and error
  format (`feedbackElements`).
- **The berth planning conversation** from DCSA's Industry Blueprint: estimate,
  request, then accept, replan or omit.
- **Who ended a call.** A call omitted by the carrier and a berth service
  cancelled by the terminal are published with the standard's two different
  flags, `isOmitted` and `isCanceled`.

**Not there yet**

- Only the **berth** service is handled. Pilotage, towage, cargo operations
  and moves forecasts are reported as not processed.
- A visit is one record; the standard separates a port call from the terminal
  calls within it.
- Terminal codes are invented, so no facility code is sent.
- No login and no subscriber registration (the standard leaves both to the
  implementer).
- The terminal publishes its requests for others to read; it does not push
  them to a carrier's own endpoint.

## API at a glance

Four controllers, one per flow.

| Controller | Endpoint | For |
|---|---|---|
| DCSA events | `POST /api/port-call/v2/events` | the berth conversation: estimate (`EST`, opens a call), accept (`PLN`), arrival and departure (`ACT`), omit. The only way a call begins, changes or ends |
| | `GET /api/port-call/v2/events` | the timestamp ledger as DCSA Port Call 2.0 events, with filters and paging |
| Operator's calculator | `POST /api/operator/eta` | the model's ETA for a position, and the speed that meets the terminal's requested time. Stores nothing |
| Console | `GET /api/port` · `/api/port/berth-plan` | the port and its berths; berth windows over a period |
| | `GET /api/visits` · `/api/visits/{ref}` | visits in progress; one visit with its ledger |
| | `PUT /api/port/berths/{code}/release` | the planner frees a berth when a ship's side has not reported leaving |
| | `PUT /api/visits/{ref}/cancel` | the planner cancels the call of a ship that has not arrived, for example one that never answered |
| | `GET /api/port/maintenance` · `POST`, `DELETE /api/port/berths/{code}/maintenance` | berth maintenance: the windows planned, schedule one, remove one |
| Assistant | `/api/agent/*` | a Gemini assistant; its tools still return sample data |

`docs/END-TO-END-GUIDE.md` exercises every one of them, request by request.

## What makes this project different

- **Leakage-safe evaluation.** Data is split **by vessel**, so no ship ever
  appears in both train and test. A random row split (used by a 2024 journal
  paper we analyzed) leaks near-duplicate 3-second pings across the split and
  inflates every metric.
- **Honest negative results.** A Bi-GRU was built and lost
  fairly to XGBoost (74.9 vs 68.4 min on identical test points) — 688 voyages
  is too little data for a deep model, so the tree model ships
  (`docs/bigru-explained.md`). Vessel `length` and time-of-day/season features
  from published papers were tested via seeded ablations and rejected when
  they didn't improve held-out error (the time-of-day/season ablation is
  `scratch/ablate_tid.py`).
- **Hidden-missing-value handling.** AIS encodes "not available" as
  real-looking numbers (`sog=102.3`, `cog=360`, `heading=511`, `length=0`);
  everything passes through `clean_ais()` before use.
- **A real cross-service bug, diagnosed and fixed.** The JDK `HttpClient`
  defaults to an HTTP/2 h2c upgrade that uvicorn rejects, silently dropping
  the request body. Traced via the uvicorn logs and fixed by forcing HTTP/1.1
  (`docs/spring-boot-service-explained.md`).
- **The database enforces the rules.** One open allocation per berth and one
  open visit per vessel are unique indexes, so two requests arriving together
  cannot double-book a berth.
- **Research-grounded choices.** Four papers analyzed — the model choice,
  target construction, and split strategy all trace to published prior art.

## Known limits

- **The ETA label includes idle time.** Arrival is taken as the moment the crew
  sets the status to "moored", which is well after the ship has stopped: in the
  last 30 minutes before it, 71% of position reports are under 5 knots. The
  model therefore predicts late when a ship is close to port.
- **No emissions figure.** The calculator advises a speed but does not estimate
  the fuel or CO2 saved. That needs a fuel curve for each ship, which the data
  does not have.
- **The advised speed uses straight-line distance.** The route a ship sails
  around a coast is longer, so the figure is on the low side: a starting
  point, not a passage plan.
- **The terminal keeps no model predictions.** The calculator stores nothing,
  and the terminal knows a ship's position only when an estimate carries one.
  Checking the model against real arrivals needs a position feed of its own.
- **The queue never reorders.** A berth can stand empty for a ship delayed by
  weather while a nearer one waits.
- **Waiting times follow events, not the clock.** A waiting ship's requested
  time is worked out again whenever something happens to the berth plan. A
  berthed ship that simply overstays changes nothing until the next event.
- **A stay is a median.** A berth's planned end is arrival plus a published
  median stay for the vessel type, so about half of all ships will overstay
  it. Nothing yet gives the terminal a better departure time during the stay.
- **Vessel type is not matched to terminal type.** Size is checked; type is not.
- **Stay and speed figures are published medians**, not measurements of this
  port: time in port from UNCTAD (2018), economical speeds from industry
  sources. Stay depends on vessel type only, not on cargo volume.
- **Every endpoint is open.** There is no authentication.

## Layout

```
src/                  Python pipeline
  data_loader.py        AIS access + sentinel cleaning
  feature_engineer.py   arrival labelling, voyage extraction, by-vessel split
  train.py              XGBoost ETA baseline (production model)
  train_bigru.py        Bi-GRU head-to-head experiment
  predict_api.py        FastAPI service: /health, /predict
port-console/         React console (Vite, JavaScript): planner, operator, events, stories
port-scheduler-api/   Spring Boot 3 / Java 21 / Gradle
  controller/           four controllers: DCSA events, operator's calculator, console, assistant
  dcsa/                 DCSA Port Call 2.0: message shapes, publishing, receiving
  service/              visit lifecycle, berth allocation, planning rules
  entity/, repository/  the data model and its queries (MySQL, Flyway migrations)
notebooks/            Jupyter EDA (executed against the real data)
docs/                 ROADMAP, WORKFLOW (runbook),
                      per-component explainers (FastAPI, Spring Boot, XGBoost…)
data/                 raw parquets + processed voyages (not in repo — see below)
models/               trained artefacts (not in repo)
reports/              Excel study workbooks (generated locally, not in repo)
```

> **Note on data:** `data/`, `models/`, `reports/*.xlsx`, and the paper PDFs are
> excluded from the repo. The study workbooks and datasets contain raw AIS rows
> and vessel identities from the source data and are not redistributed; regenerate
> them locally from the pipeline.

## Quick start

Full runbook with expected outputs: **`docs/WORKFLOW.md`**. Condensed:

```powershell
# Python env (sibling directory) + deps
..\port_ml_env\Scripts\Activate.ps1
pip install -r requirements.txt

# 1. Build the dataset and train (requires raw AIS data, see Data below)
python src/feature_engineer.py --n-files -1
python src/train.py

# 2. Serve the model
python -m uvicorn predict_api:app --app-dir src --port 8000

# 3. Run the scheduler (separate terminal)
#    One-time setup first: MySQL with an empty `port_core` database and a
#    secrets.properties file — see docs/WORKFLOW.md, Phase 0.3
cd port-scheduler-api; .\gradlew bootRun

# 4. Ask the calculator when a ship would arrive (stores nothing)
curl -X POST http://localhost:8080/api/operator/eta -H "Content-Type: application/json" `
  -d '{"vesselId":"V001","lat":61.62,"lon":4.40,"sog":13.0,"cog":92.0,"destLat":61.612,"destLon":5.07}'
# → {"vesselId":"V001","etaMinutes":226.9,"estimatedArrival":"...","distanceNm":19.13,...}

# 5. Open the console (separate terminal; needs Node.js) and play a story
cd port-console; npm install; npm run dev      # then http://localhost:5173
```

A call is opened, answered and closed with DCSA Port Call events. The console
sends them for you; **`docs/END-TO-END-GUIDE.md`** gives every request as a
`curl` command, with the answer to expect and how each step lines up with the
standard.

Java tests (101, against a throwaway MySQL schema): `.\gradlew test` from
`port-scheduler-api/`. Console tests (69): `npm test` from `port-console/`.

## Data

The raw AIS data (~30M rows, 40 daily Parquet files, Norwegian west coast,
2020-07-27 → 2020-10-21) is **not included** in the repo due to size. The
pipeline expects it in `data/parquets/`; any AIS dump with the standard fields
(`mmsi, date_time_utc, lat, lon, sog, cog, nav_status, length…`) works. The
ETA target is derived from `nav_status == 5` (moored) arrival events —
no manual labelling.

## Documentation

| Doc | What it covers |
|---|---|
| `docs/END-TO-END-GUIDE.md` | from an empty machine to the whole berth conversation: every controller and endpoint, the request to send, the answer to expect, and how it lines up with DCSA Port Call 2.0 |
| `docs/WORKFLOW.md` | end-to-end runbook: setup → data → train → serve → test |
| `docs/fastapi-service-explained.md` | the Python model service, line by line |
| `docs/spring-boot-service-explained.md` | the Java scheduler: data model, berth planning, operator and DCSA endpoints, and the HTTP/2 bug story |
| `docs/frontend-explained.md` | the console: screens, design rules, how it is built, and how login and roles will fit in |
| `docs/system-map.html` | diagrams of the whole system: every API, the berth conversation step by step, the database, which function writes which table, and current progress |
| `docs/request-flow-diagram.html` | visual trace of one calculator request across both services |
| `docs/xgboost-explained.md`, `docs/bigru-explained.md` | how the models work |

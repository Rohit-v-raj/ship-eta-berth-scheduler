# End-to-End Guide — From an Empty Machine to the Whole Berth Conversation

> **What this doc is:** one walk from nothing to a fully tested system. It sets
> the project up, starts every part, then exercises **every controller and
> every endpoint** with the exact request to send, the answer to expect, and a
> note on how each step lines up with the **DCSA Port Call 2.0** standard.
>
> Every answer shown below was captured from a real run on 14 October 2026.
> Your times and visit references will differ; the shape will not.
>
> Shorter companions: `WORKFLOW.md` (the runbook), `spring-boot-service-explained.md`
> (how the service works inside), `frontend-explained.md` (the console).

---

## 1. What you are about to run

```
                       ┌──────────────────────────────────────┐
  Vessel operator ───▶ │  Spring Boot scheduler  (port 8080)  │ ───▶  FastAPI ETA model (port 8000)
  Terminal planner ──▶ │  four controllers, one per flow      │         XGBoost, /predict
  Carrier system ────▶ │                                      │
        ▲              └───────────────┬──────────────────────┘
        │                              ▼
  Port Call Console (React, port 5173)       MySQL  port_core
```

There are **four controllers, one per flow**:

| Controller | Address | Who uses it | What it is for | Part of the DCSA standard? |
|---|---|---|---|---|
| `PortCallEventsController` | `/api/port-call/v2/events` | Vessel operator, terminal, any carrier system | **The berth conversation.** `POST` sends timestamps to the terminal, `GET` publishes what has been exchanged | **Yes.** These are the standard's two endpoints |
| `VesselOperatorController` | `/api/operator/eta` | Vessel operator | **The operator's calculator.** When would we arrive from here, and what speed meets the time the terminal asked for | No. The standard exchanges times; working out a speed is the ship's own business |
| `ConsoleController` | `/api/port`, `/api/visits` | The console | **What the screen needs:** the port and its berths, visits in progress, one visit's history, the berth plan, and the planner's two actions: release a berth, cancel a call | No. A terminal's internal view; the standard does not cover it |
| `AgentController` | `/api/agent` | Anyone | A Gemini assistant. Its tools still return sample data | No |

The idea behind the split: **a vessel's side can begin, change and end a call
only through the DCSA endpoint.** Everything else either reads, helps one side
decide what to send, or is the terminal planner acting on the terminal's own
berths.

---

## 2. How this lines up with DCSA Port Call 2.0

### 2.1 The four kinds of time

The standard takes its vocabulary from IMO circular FAL.5/Circ.52. Every time in
a port call is one of four kinds, and each kind has an owner:

| Kind | Code | Meaning | Sent by, for arrival at berth |
|---|---|---|---|
| Estimated | `EST` | "This is when I expect to arrive" | The vessel operator |
| Requested | `REQ` | "This is when I want you to arrive" | The terminal |
| Planned | `PLN` | "Agreed": the vessel's acceptance of the request. **A planned time is always equal to a requested time** | The vessel operator |
| Actual | `ACT` | What happened | The vessel operator |

Two rules follow, and this service enforces both:

- A `PLN` that does not equal the current `REQ` is refused.
- A new `REQ` cancels an earlier `PLN`: the vessel has to accept again.

### 2.2 The conversation

DCSA's weekly berth planning process, in the order this guide exercises it:

```
 Vessel operator                              Terminal
 ───────────────                              ────────
 EST  "I expect to arrive 10:16"   ───────▶   opens the call, plans a berth
                                   ◀───────   REQ  "arrive 10:16"  (or later, if no berth is free)
 is that acceptable?
   yes  ─ PLN  (= the REQ time)    ───────▶   agreed
   no   ─ EST  (another time)      ───────▶   replans, answers with a new REQ
   drop ─ omitted port call        ───────▶   call cancelled, berth passes on
   (no answer)                     ◀───────   the planner may cancel the berth service
 ACT  anchorage (if early)         ───────▶   in port: brought in as soon as it delays no one
 ACT  arrival                      ───────▶   vessel alongside
 ACT  departure                    ───────▶   berth free, the vessels booked after it move up
```

Three things the terminal does without being asked:

- **It keeps a berth plan, and every ship's time in it current.** Each berth
  holds a sequence of ships across the week, each in its own window; a ship
  due soon may use a gap before a booking, but never delays it. Whenever the
  plan changes (a ship arrives early or late, a berth is released, a call is
  omitted or cancelled, a ship sends a new estimate), it works out again when
  each booked ship can berth, moves a booking to a berth that can take it more
  than 4 hours sooner, and sends a new `REQ` to every ship whose time moves by
  15 minutes or more.
- **It flags an answer that is late.** A ship is expected to accept a request
  within 60 minutes. After that the planner's screen says so. The terminal
  does not take the berth away for silence: the planner decides (step 13).
- **It plans a berth again when a booked ship falls far behind.** If a ship
  holding a booking sends an estimate more than 4 hours after the time it
  agreed, and another ship can be alongside on that berth sooner, the place
  goes to that ship (step 19).

It also gives a ship only a berth it fits (step 17) and plans around berth
maintenance (step 18). The 15 minutes, 60 minutes and 4 hours are this
project's own choices, set in `application.properties`.

### 2.3 Standard element by element

| In the standard | In this service |
|---|---|
| `POST /events` (subscriber endpoint) and `GET /events` (publisher endpoint) | Both implemented, at `/api/port-call/v2/events` |
| `Event` with `portCall`, `terminalCall`, `portCallService`, `vessel`, `timestamp` | Same names and nesting. `eventID`s are stable UUIDs |
| `eventID`, `eventUpdatedDateTime` on an incoming event | Read. An event already applied is not applied again (`INFO`); an estimate or omitted call sent before the vessel's latest event is not applied (`WARN`); a time more than 5 minutes ahead is refused (step 20) |
| `timestamp.classifierCode` `EST` / `REQ` / `PLN` / `ACT`, `serviceDateTime` | Same. Times are UTC |
| `portCallServiceTypeCode` `BERTH`, event type `ARRI` / `DEPA`, phase `INBD` / `OUTB`, facility `BRTH` | Same codes. The `BERTH` and `ANCHORAGE` services are acted on; others are answered "not processed" |
| `portCallServiceTypeCode` `ANCHORAGE`, event type `STRT` / `CMPL`, facility `ANCH`, no phase | Read, actual times only: the ship anchored, or left the anchorage. A ship at anchor may be brought in sooner (step 24). Published the same way |
| `vessel.vesselIMONumber`, `vesselMMSINumber`, `vesselName`, `vesselTypeCode` | A vessel is matched by IMO number, then MMSI, then name. An IMO number must pass its check digit |
| `vessel.vesselPosition`, `vessel.milesToDestinationPort` (optional) | Read from an `EST`. This is how the terminal learns where a ship is |
| `vessel.lengthOverall`, `width` in `vesselSizeUnit`; `draft` in `draftUnit` (optional in the standard) | Read from an `EST`, in metres or feet. **Length and beam are required here once per ship**: a call opens only for a ship the terminal knows the size of, and they stay on the vessel record. The draft is per call, due a day before arrival. A ship is given only a berth it fits, and refused if it fits none (step 17) |
| `portCall.isOmitted` / `terminalCall.isOmitted` ("omitted by the carrier") | Read: it cancels the call. Published on every event of such a call |
| `portCallService.isCanceled` ("canceled by the service provider") | Published on every event of a call the terminal cancelled (step 13). Not read: here the terminal is the provider |
| `feedbackElements` with `severity`, `message`, `propertyPath` | Same. A refused event is reported by its position, `$.events[0]`; a field that cannot be read, by its path, `$.events[1].timestamp.serviceDateTime` |
| `200` even when some events could not be applied | Same |
| `API-Version` header on every response | Sent, value `2.0.0` |
| Paging by `limit`, `cursor` and the `Next-Page-Cursor` header | Same |
| A publisher must say which filters it supports; an unsupported one may be refused | Ten are supported; any other gets `400` |
| An empty result is `200` with an empty list, not `404` | Same |
| Authentication and subscriber registration: out of scope | Not built. Every endpoint is open |

**Not part of the standard, and why they exist:** the operator's calculator
(the standard carries no speed), the console's reads (a terminal's own view),
"release berth" (so a planner can free a berth when a ship's side never
reports leaving) and "cancel call" (the planner's answer to a ship that does
not reply; the result is published in the standard's own terms). Section 12
lists where the service falls short of the standard.

---

## 3. Set up (once per machine)

### 3.1 Tools

| Tool | Version used | Check |
|---|---|---|
| Git (with Git Bash) | 2.47 | `git --version` |
| Java JDK | 21 | `java -version` |
| Python | 3.14 | `python --version` |
| MySQL Server | 8.0 | the `MySQL80` service is running |
| Node.js | 24 (LTS) | `node --version` |

Gradle is not needed: the project carries its own wrapper.

### 3.2 The code

```powershell
git clone https://github.com/Rohit-v-raj/ship-eta-berth-scheduler.git
cd ship-eta-berth-scheduler
```

### 3.3 Python environment and the model

The virtual environment lives beside the project folder:

```powershell
python -m venv ..\port_ml_env
..\port_ml_env\Scripts\pip install -r requirements.txt
```

The scheduler needs a trained model at `models/eta_xgb.pkl`. It is **not in
the repository** (nor is the AIS data it is trained from). With the raw AIS
files in `data/parquets/`, build it in two commands; `WORKFLOW.md` Phases 1
and 2 explain them:

```powershell
..\port_ml_env\Scripts\python src\feature_engineer.py --n-files -1
..\port_ml_env\Scripts\python src\train.py
```

### 3.4 Database

In the MySQL command line, signed in as root:

```sql
CREATE DATABASE port_core;
CREATE USER 'dev'@'localhost' IDENTIFIED BY 'choose-a-password';
GRANT ALL PRIVILEGES ON port_core.* TO 'dev'@'localhost';
GRANT ALL PRIVILEGES ON port_core_test.* TO 'dev'@'localhost';
```

You create no tables. Flyway builds ten of them and seeds the demo port
(Florø, berths `B1` to `B4`) the first time the service starts.
`port_core_test` is used only by the automated tests.

### 3.5 Secrets

In `port-scheduler-api/`, copy `secrets.properties.example` to
`secrets.properties` and fill in the database password and a Google AI Studio
API key (only the assistant uses the key). The file is ignored by git.

### 3.6 The console's packages

```powershell
cd port-console
npm install
cd ..
```

---

## 4. Start it

Three terminals, in this order. Leave each one running.

**Terminal 1, the ETA model** (from the project folder):

```powershell
..\port_ml_env\Scripts\python -m uvicorn predict_api:app --app-dir src --host 127.0.0.1 --port 8000
```
Ready when it prints `Uvicorn running on http://127.0.0.1:8000`.

**Terminal 2, the scheduler:**

```powershell
cd port-scheduler-api
.\gradlew bootRun --console=plain
```
Ready when it prints `Started PortSchedulerApplication`. The first start
downloads dependencies and builds the database.

**Terminal 3, the console:**

```powershell
cd port-console
npm run dev
```
Ready when it prints `Local: http://localhost:5173/`.

---

## 5. Before the calls: one shell, three shortcuts

The calls below are written for **Git Bash** (it comes with Git for Windows),
because they quote JSON, which Windows PowerShell rewrites. Open Git Bash and
paste this once:

```bash
API=http://127.0.0.1:8080
MODEL=http://127.0.0.1:8000
J='Content-Type: application/json'

# pick one value out of a JSON answer, for example:  ... | pick "d[0]['rtaBerth']"
pick() { python -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
```

`pick` is there because the conversation feeds on its own answers: to accept a
requested time you must send that exact time back.

---

## 6. The walk-through

Start from an empty port (section 11 shows how to reset one).

The answers below were captured in one run on 23 Nov 2026, on the scheduler
with its production settings. Steps 20, 21 and 22 each start again from an
empty port. Later that day the terminal began to require each ship's length
and beam with its first estimate, so every first estimate below carries them
(120 m and 20 m, which fit every berth). The walk-through was replayed in full
with the sizes: every berth, time and move came out the same; what changed is
the size fields in the answers, `draftDueSince`, and step 17's first refusal.

### Step 1 — The model service on its own

```bash
curl -s $MODEL/health
```
```json
{"status":"ok","model":"eta_xgb.pkl","features":["distance_to_dest_nm","sog","cog","bearing_to_dest_deg","naive_eta_min","oncourse"]}
```

```bash
curl -s -X POST $MODEL/predict -H "$J" \
  -d '{"lat":61.62,"lon":4.40,"sog":13.0,"cog":92.0,"dest_lat":61.612,"dest_lon":5.07}'
```
```json
{"eta_minutes":121.1,"eta_hours":2.02,"distance_to_dest_nm":19.13,"bearing_to_dest_deg":91.1,"naive_eta_min":88.3}
```

**What to notice.** A ship 19 nm west of Florø at 13 knots: the model says 121
minutes, plain distance over speed says 88. The difference is the way in: the
route is longer than the straight line, and a ship slows down as it nears the
berth. The model gives the minutes until the ship stops at its berth, learnt
from real voyages. Nothing outside the
scheduler calls this service directly. It is shown here so you know the chain
works before adding the next link.

### Step 2 — The port, as the console reads it (`ConsoleController`)

```bash
curl -s $API/api/port
```
```json
{"unLocode":"NOFRO","name":"Floro","countryCode":"NO","timeZone":"Europe/Oslo","latitude":61.6,"longitude":5.033,
 "terminals":[{"code":"FRO-CT","name":"Floro Container Terminal","type":"CONTAINER","status":"ACTIVE"}, ...],
 "berths":[{"code":"B1","name":"Berth 1","seq":1,"terminalCode":"FRO-CT","quayCode":"Q1","latitude":61.612,"longitude":5.07,
            "lengthMeters":200.00,"depthMeters":12.00,"maxLoaMeters":190.00,"maxBeamMeters":32.00,"maxDraftMeters":10.50,"status":"ACTIVE"}, ...]}
```

```bash
curl -s $API/api/visits
```
```json
[]
```

**What to notice.** Four berths, no visits. Both are read-only.
**DCSA:** not part of the standard, which describes the exchange between
parties and says nothing of a terminal's infrastructure.

### Step 3 — The operator's calculator (`VesselOperatorController`)

The operator of the ship NORDVIK asks when it would arrive:

```bash
curl -s -X POST $API/api/operator/eta -H "$J" \
  -d '{"vesselId":"NORDVIK","lat":61.62,"lon":4.40,"sog":13.0,"cog":92.0,"destLat":61.612,"destLon":5.07,"vesselType":"GENERAL_CARGO"}'
```
```json
{"vesselId":"NORDVIK","etaMinutes":121.1,"estimatedArrival":"2026-11-25T08:41:48.775721800Z","distanceNm":19.13,
 "naiveEtaMinutes":88.3,"requestedArrival":null,"advisedSpeedKnots":null}
```

**What to notice.** The same 121.1 minutes as step 1, now as a clock time.
`requestedArrival` and `advisedSpeedKnots` are empty because NORDVIK has no
call open: the terminal has not asked for anything yet. **Nothing was
stored.** Check with `curl -s $API/api/visits`: still `[]`.

Keep the two values the next step needs:

```bash
ANSWER=$(curl -s -X POST $API/api/operator/eta -H "$J" \
  -d '{"vesselId":"NORDVIK","lat":61.62,"lon":4.40,"sog":13.0,"cog":92.0,"destLat":61.612,"destLon":5.07,"vesselType":"GENERAL_CARGO"}')
ETA=$(echo "$ANSWER" | pick "d['estimatedArrival']")
MILES=$(echo "$ANSWER" | pick "d['distanceNm']")
```

**DCSA:** not part of the standard. Under it the ship states its own estimate;
how it arrives at that figure is its own affair. This endpoint is the "how".

### Step 4 — Open the call: an estimated arrival (`PortCallEventsController`, `POST`)

The operator sends the estimate to the terminal, with the ship's position and
size:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{
    "vesselName":"NORDVIK",
    "vesselTypeCode":"GCGO",
    "vesselPosition":{"geoCoordinate":{"latitude":"61.62","longitude":"4.40"}},
    "milesToDestinationPort":$MILES,
    "vesselSizeUnit":"MTR","lengthOverall":120,"width":20
  },
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"$ETA"}
}]}
EOF
```
```json
{}
```

**What to notice.** An empty answer means every event was applied; there is
nothing to report. Behind it the terminal opened a visit, booked the first
free berth, and recorded its own answer, a requested arrival.

The length overall and beam are required the first time the terminal hears of
a ship: a berth is given only to a ship known to fit it. They stay on the
vessel record, so NORDVIK's later calls need not repeat them. Without them the
estimate is refused (step 17).

**DCSA:** this is the standard's `POST /events`. An `EST` for service `BERTH`,
event `ARRI`, is "estimated time of arrival at berth", sent by the vessel
operator. `vesselPosition`, `milesToDestinationPort` and the size fields are
the standard's own optional fields; the coordinates are text, as it specifies.
`GCGO` is its code for general cargo.

### Step 5 — See where the ship stands (`ConsoleController`)

```bash
curl -s "$API/api/visits?vessel=NORDVIK"
```
```json
[{"visitRef":"VV-20261125-6D1896C2",
  "vessel":{"code":"NORDVIK","name":"NORDVIK","imoNumber":null,"mmsi":null,"type":"GENERAL_CARGO","loaMeters":120.00,"beamMeters":20.00},
  "phase":"INBOUND","terminalCode":"FRO-CT",
  "berth":{"berthCode":"B1","berthName":"Berth 1","status":"PLANNED",
           "plannedStart":"2026-11-25T08:41:48.775722Z","plannedEnd":"2026-11-26T07:58:48.775722Z","actualStart":null,"actualEnd":null},
  "queueNumber":null,
  "etaBerth":"2026-11-25T08:41:48.775722Z","rtaBerth":"2026-11-25T08:41:48.775722Z","ptaBerth":null,"ataBerth":null,"atdBerth":null,
  "ataAnchorage":null,"atdAnchorage":null,"plannedServiceMinutes":1397,
  "lastReport":{"reportedAt":"2026-11-25T06:40:43.603981Z","lat":61.62,"lon":4.4,"distanceNm":19.13,"estimatedArrival":"2026-11-25T08:41:48.775722Z"},
  "requestedAt":"2026-11-25T06:40:43.716120Z","answerDueAt":"2026-11-25T07:40:43.716120Z","cancelledBy":null,"remarks":null,
  "estimateDueSince":null,"nextEstimateBy":null,"notArrivedSince":null,"arrivalDraftMeters":null,"overstayingSince":null,
  "draftDueSince":"2026-11-24T08:41:48.775722Z","aheadOnBerth":null}]
```

**What to notice.**

- `berth` is `B1` with status `PLANNED`: booked, the ship is not alongside.
  **The booking is held from this moment**, not from the moment the ship
  accepts. Another ship can use `B1` only in a gap that ends, with an hour to
  spare, before NORDVIK's window starts; it can never move NORDVIK.
- `rtaBerth` equals `etaBerth`. The berth was free, so the terminal asks the
  ship to come at its own estimate.
- `ptaBerth` is empty: the ship has not accepted yet.
- `requestedAt` is when the terminal issued that request, and `answerDueAt` is
  60 minutes later: when the ship's answer is expected by.
- `estimateDueSince` and `nextEstimateBy` say when the terminal expects a
  fresh estimate: ports ask for one 72, 48 and 24 hours before arrival, and
  NORDVIK is closer than that. `notArrivedSince` is set once a booked ship is
  past its agreed time without arriving, and `overstayingSince` once a ship
  alongside is past its planned end (step 22).
- The vessel's `loaMeters` and `beamMeters` are the size sent in step 4.
  `arrivalDraftMeters` is the draft for this call, which NORDVIK has not given.
  A draft is due a day before arrival (`port.planning.draft-due-hours`, 24),
  and NORDVIK arrives within the day, so `draftDueSince` is set: the console
  shows "Draft due". Until a draft comes, the largest the ship has ever
  declared is used to check the fit.
- `aheadOnBerth` names the ship whose turn on the berth comes first, while
  an arrival now would be refused (step 23). NORDVIK is first on `B1`, so it
  is `null`.
- `plannedEnd` is 1,397 minutes (0.97 days) after the start: the typical stay
  for this vessel type, from published UNCTAD medians.
- `lastReport` is the position sent in step 4.

A visit has a `berth` or a `queueNumber`, never both. Only a ship due more
than 7 days ahead has a queue number (step 21).

### Step 6 — Read the terminal's answer the DCSA way (`PortCallEventsController`, `GET`)

A carrier's system would not call `/api/visits`. It reads the feed:

```bash
curl -s -D - "$API/api/port-call/v2/events?classifierCode=REQ&vesselName=NORDVIK"
```
```
HTTP/1.1 200
API-Version: 2.0.0
Content-Type: application/json

{"events":[{
  "eventID":"a0bdd746-f7a2-38cc-88fe-0ecf849b225a",
  "eventUpdatedDateTime":"2026-11-25T06:40:43.716120Z",
  "portCall":{"portCallID":"784d4eda-f24b-359a-b240-47225b56aa56","portVisitReference":"VV-20261125-6D1896C2","UNLocationCode":"NOFRO"},
  "terminalCall":{"terminalCallID":"74bb1d94-525d-3000-9402-973519aad096","sequenceNumber":1},
  "portCallService":{"portCallServiceID":"f5d2d864-14fd-3416-be0f-889d047360a7",
    "portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","portCallPhaseTypeCode":"INBD","facilityTypeCode":"BRTH",
    "serviceLocation":{"locationName":"Berth 1","geoCoordinate":{"latitude":"61.612","longitude":"5.07"},"UNLocationCode":"NOFRO"}},
  "vessel":{"vesselName":"NORDVIK","vesselTypeCode":"GCGO","vesselSizeUnit":"MTR","lengthOverall":120.0,"width":20.0},
  "timestamp":{"classifierCode":"REQ","serviceDateTime":"2026-11-25T08:41:48.775722Z"}}]}
```

**What to notice.** The same fact as `rtaBerth` in step 5, in the standard's
shape, with the berth as `serviceLocation`.
**DCSA:** this is the standard's `GET /events`. `REQ` is the terminal's
timestamp; the `API-Version` header is required on every response.

Keep the requested time:

```bash
RTA=$(curl -s "$API/api/visits?vessel=NORDVIK" | pick "d[0]['rtaBerth']")
```

### Step 7 — Accept: a planned arrival

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{"vesselName":"NORDVIK"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"PLN","serviceDateTime":"$RTA"}
}]}
EOF
```
```json
{}
```

`ptaBerth` now equals `rtaBerth`: the arrival is agreed. `answerDueAt` is
`null`, because no answer is owed any more.

Now try to accept a time the terminal did **not** ask for:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{"vesselName":"NORDVIK"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"PLN","serviceDateTime":"2026-11-25T15:00:00Z"}
}]}
EOF
```
```json
{"feedbackElements":[{"severity":"ERROR","message":"The requested arrival is now 2026-11-25T08:41:48.775722Z; confirm that time","propertyPath":"$.events[0]"}]}
```

**What to notice.** The HTTP status is still `200`. The refusal is in
`feedbackElements`, pointing at the event by its position. This is also what
an operator gets who answers slowly and accepts a time the terminal has
since changed: the acceptance is refused, never silently applied to the wrong
time.
**DCSA:** "a planned time is always equal to a requested time", and a
subscriber answers `200` with feedback when it could read the request but not
apply an event.

### Step 8 — Fill the port

Three more ships declare an estimate. They send no position, which is allowed:

```bash
for SHIP in "SELJE 2" "ULSTEIN 3" "VARTDAL 4"; do
  set -- $SHIP
  WHEN=$(date -u -d "+$2 hours" +%Y-%m-%dT%H:%M:%SZ)
  curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{"vesselName":"$1","vesselTypeCode":"GCGO","vesselSizeUnit":"MTR","lengthOverall":120,"width":20},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"$WHEN"}
}]}
EOF
  echo
done
```
Each answers `{}`. `curl -s $API/api/visits` now lists NORDVIK on `B1`, SELJE
on `B2`, ULSTEIN on `B3`, VARTDAL on `B4`. Their `lastReport` is `null`: the
terminal knows their times but not where they are.

Each berth is planned free one typical stay after its ship is due:

| Berth | Ship | Planned free |
|---|---|---|
| `B1` | NORDVIK | `2026-11-26T07:58:48Z` |
| `B2` | SELJE | `2026-11-26T07:57:44Z` |
| `B3` | ULSTEIN | `2026-11-26T08:57:45Z` |
| `B4` | VARTDAL | `2026-11-26T09:57:45Z` |

### Step 9 — A fifth ship: booked behind another, a later request, and the speed

HAREID is 60 nm to the north at 14 knots. Its operator calculates, then sends:

```bash
Q='{"vesselId":"HAREID","lat":62.60,"lon":5.40,"sog":14.0,"cog":190.0,"destLat":61.612,"destLon":5.07,"vesselType":"GENERAL_CARGO"}'
ANSWER=$(curl -s -X POST $API/api/operator/eta -H "$J" -d "$Q"); echo "$ANSWER"
ETA=$(echo "$ANSWER" | pick "d['estimatedArrival']"); MILES=$(echo "$ANSWER" | pick "d['distanceNm']")
```
```json
{"vesselId":"HAREID","etaMinutes":372.6,"estimatedArrival":"2026-11-25T12:53:21.874973800Z","distanceNm":60.04,
 "naiveEtaMinutes":257.3,"requestedArrival":null,"advisedSpeedKnots":null}
```

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{"vesselName":"HAREID","vesselTypeCode":"GCGO",
            "vesselPosition":{"geoCoordinate":{"latitude":"62.60","longitude":"5.40"}},"milesToDestinationPort":$MILES,
            "vesselSizeUnit":"MTR","lengthOverall":120,"width":20},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"$ETA"}
}]}
EOF
curl -s "$API/api/visits?vessel=HAREID"
```
```json
[{"visitRef":"VV-20261125-9B37C4BE", ... "phase":"INBOUND","terminalCode":"FRO-CT",
  "berth":{"berthCode":"B2","berthName":"Berth 2","status":"PLANNED",
           "plannedStart":"2026-11-26T08:57:44Z","plannedEnd":"2026-11-27T08:14:44Z","actualStart":null,"actualEnd":null},
  "queueNumber":null,
  "etaBerth":"2026-11-25T12:53:21.874974Z","rtaBerth":"2026-11-26T08:57:44Z","ptaBerth":null, ...
  "nextEstimateBy":"2026-11-25T08:57:44Z", ...
  "aheadOnBerth":{"vesselCode":"SELJE","vesselName":"SELJE","status":"PLANNED","plannedStart":"2026-11-25T08:40:44Z"}}]
```

**What to notice.**

- Every berth is taken by then, so HAREID is **booked behind another ship**:
  on `B2`, after SELJE, the first berth due free. A berth holds a sequence of
  ships across the week, each in its own window, and `GET /api/port/berth-plan`
  now shows two windows on `B2`.
- `rtaBerth` is **20 hours after** `etaBerth`. The terminal asks the ship to
  come when SELJE is expected to leave (`07:57:44`) plus the hour's turnaround
  between ships, not when the ship could get there. The ledger says so: the
  request carries the remark "Berth expected free 2026-11-26T07:57:44Z".
- `nextEstimateBy` is `2026-11-25T08:57:44Z`, 24 hours before the requested
  arrival: the terminal expects a fresh estimate by then. If none has come by
  the time it passes, `estimateDueSince` says so and the console flags
  "Estimate due". The standard has no message for the terminal to ask; this
  only says when one is expected.

That gap is the reason just-in-time arrival exists. Sailing at 14 knots, the
ship would arrive and wait 20 hours. The terminal has said when; **how fast**
is the operator's sum. Ask the calculator again, now that the call is open:

```bash
curl -s -X POST $API/api/operator/eta -H "$J" -d "$Q"
```
```json
{"vesselId":"HAREID","etaMinutes":372.6,"estimatedArrival":"2026-11-25T12:53:23.294205200Z","distanceNm":60.04,
 "naiveEtaMinutes":257.3,"requestedArrival":"2026-11-26T08:57:44Z","advisedSpeedKnots":5.0}
```

**What to notice.** The same question as before, and a fuller answer:
`requestedArrival` is the terminal's time, and `advisedSpeedKnots` is the
speed that meets it. The rule is distance over time available, never below an
economical speed for the vessel type. Here 60 nm in 26 hours needs only 2.3
knots, so the answer is the floor for this type, 5.0 knots. It is advice, and
no fuel or CO2 figure is calculated.

**DCSA:** exactly the standard's division of labour. The terminal's message
carries a time and nothing else; there is no speed field anywhere in the
standard.

HAREID accepts, as NORDVIK did in step 7:

```bash
RTA=$(curl -s "$API/api/visits?vessel=HAREID" | pick "d[0]['rtaBerth']")
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"HAREID"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"PLN","serviceDateTime":"$RTA"}}]}
EOF
```

### Step 10 — Actual arrival and departure, and a booking that moves berth

NORDVIK reports it is alongside. (The walk-through skips the sailing, so this
is two hours before its estimate.)

```bash
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"NORDVIK"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"ACT","serviceDateTime":"$NOW"}}]}
EOF
curl -s "$API/api/visits?vessel=NORDVIK"
```
```json
[{... "phase":"BERTHED", ...
  "berth":{"berthCode":"B1","berthName":"Berth 1","status":"ACTIVE",
           "plannedStart":"2026-11-25T08:41:48.775722Z","plannedEnd":"2026-11-26T05:57:48Z","actualStart":"2026-11-25T06:40:48Z","actualEnd":null},
  "etaBerth":"2026-11-25T08:41:48.775722Z", ... "ataBerth":"2026-11-25T06:40:48Z", ...}]
```

**What to notice.** The visit is `BERTHED`, the berth `ACTIVE`, and `ataBerth`
is set. `plannedEnd` has moved from `07:58:48` to `05:57:48`: **a stay counts
from when the ship is really alongside**, not from its estimate.

`B1` will now be free first, at `06:57:48` after the turnaround: two hours
before `B2`. HAREID's booking stays where it is, at `08:57:44` on `B2`, and
its acceptance stands. A booking moves to another berth only when that berth
can take it more than the late tolerance (4 hours) sooner: a ship is not
asked to change its plans for a gain of two hours.

NORDVIK leaves. Note the event type `DEPA`:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"NORDVIK"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"DEPA","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"ACT","serviceDateTime":"$NOW"}}]}
EOF
curl -s "$API/api/visits?vessel=HAREID"
```
```json
[{... "berth":{"berthCode":"B1","berthName":"Berth 1","status":"PLANNED",
               "plannedStart":"2026-11-25T12:53:21.874974Z","plannedEnd":"2026-11-26T12:10:21.874974Z", ...},
  "queueNumber":null,
  "etaBerth":"2026-11-25T12:53:21.874974Z","rtaBerth":"2026-11-25T12:53:21.874974Z","ptaBerth":null, ...
  "requestedAt":"2026-11-25T06:40:48.947478Z","answerDueAt":"2026-11-25T07:40:48.947478Z", ...}]
```

**What to notice.**

- `B1` is free now, and can take HAREID at its own estimate, 20 hours
  sooner than `B2`. That is more than the 4 hours, so HAREID's booking
  **moves to `B1`**. The ledger's remark says why: "Moved from B2 to B1,
  which can take it 20 h 4 min sooner".
- `ptaBerth` is empty again, and a new `answerDueAt` has started: the time
  HAREID accepted in step 9 is no longer the time being asked for.

The terminal works the plan out again after every change: a ship booked after
another moves up or back on its berth with it, and a booking that another
berth can take more than 4 hours sooner moves there. A request is re-issued
only when it moves by 15 minutes or more, so small changes do not flood a
ship with new times. HAREID would now accept again, as in step 9; the
walk-through goes straight on.

**DCSA:** "if there is a new requested time, the previous planned time is
cancelled."

### Step 11 — Propose another time

HAREID is delayed by two hours. It does not refuse the request; it states a new
estimate, and the terminal answers:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"HAREID"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"2026-11-25T14:53:21Z"}}]}
EOF
curl -s "$API/api/visits?vessel=HAREID" | pick "{k: d[0][k] for k in ('etaBerth','rtaBerth','ptaBerth')}"
```
```
{'etaBerth': '2026-11-25T14:53:21Z', 'rtaBerth': '2026-11-25T14:53:21Z', 'ptaBerth': None}
```

**What to notice.** The ship holds a berth, so the terminal simply follows:
the request moves to the new estimate, and any earlier acceptance lapses.
**DCSA:** this is the "counter proposal" branch of the berth planning process.
The standard has no "reject" message; a different estimate *is* the rejection.

### Step 12 — The planner releases a berth (`ConsoleController`)

SELJE arrives:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"SELJE"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"ACT","serviceDateTime":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}}]}
EOF
```

Later it sails, and its side never reports the departure. The planner frees
the berth:

```bash
curl -s -X PUT $API/api/port/berths/B2/release
```
```json
{"released":true,"berthCode":"B2"}
```

Release acts only on a ship **alongside**. A berth with no ship alongside
answers `{"released":false,...}` and records nothing, even when ships are
booked on it: `B4`, where VARTDAL is booked but has not arrived, answers
`{"released":false,"berthCode":"B4"}`. A berth that does not exist answers
`404`:

```json
{"statusCode":404,"message":"Berth not found: Berth ID B9 does not exist","timeStamp":1795588850936}
```

**What to notice.** The effect is the same as the ship's own `ACT` departure in
step 10: the visit is closed as departed, and the ships booked after it move
up. For a ship that never arrived, the next step is the right action.
**DCSA:** not part of the standard, where an actual departure is the vessel's
to send. This is the terminal's own override.

### Step 13 — The planner cancels a call that goes unanswered (`ConsoleController`)

VARTDAL was given `B4` in step 8 and has never accepted the requested arrival:

```bash
REF=$(curl -s "$API/api/visits?vessel=VARTDAL" | pick "d[0]['visitRef']")
curl -s "$API/api/visits?vessel=VARTDAL"
```
```json
[{"visitRef":"VV-20261125-72FBF09B", ...
  "berth":{"berthCode":"B4","berthName":"Berth 4","status":"PLANNED",
           "plannedStart":"2026-11-25T10:40:45Z","plannedEnd":"2026-11-26T09:57:45Z", ...},
  "rtaBerth":"2026-11-25T10:40:45Z","ptaBerth":null, ...
  "requestedAt":"2026-11-25T06:40:45.469069Z","answerDueAt":"2026-11-25T07:40:45.469069Z", ...}]
```

The booking is safe for VARTDAL while it stays silent, and that is the
problem: the berth is held for a ship that may not be coming. Once
`answerDueAt` has passed, the console flags the visit ("No answer for 1 h 20
min"). **The terminal does not act on that by itself.** The planner decides,
and can cancel the call at any time, overdue or not:

```bash
curl -s -X PUT $API/api/visits/$REF/cancel -H "$J" -d '{"reason":"no answer to the requested arrival"}'
```
```json
{"cancelled":true,"visitRef":"VV-20261125-72FBF09B"}
```

The body is optional. A `reason`, at most 400 characters, is kept with the
visit:

```bash
curl -s "$API/api/visits?vessel=VARTDAL&state=all"
```
```json
[{... "phase":"CANCELLED", ... "berth":{"berthCode":"B4", ... "status":"CANCELLED", ...}, ...
  "answerDueAt":null,"cancelledBy":"TERMINAL","remarks":"Cancelled by the terminal: no answer to the requested arrival"}]
```

A carrier's system learns of it from the feed:

```bash
curl -s "$API/api/port-call/v2/events?vesselName=VARTDAL&classifierCode=REQ"
```
```json
{"events":[{
  "eventID":"696ff9fd-18c7-3a76-aebd-639fd75cca07",
  "eventUpdatedDateTime":"2026-11-25T06:40:45.469069Z",
  "portCall":{"portCallID":"e701820b-5c13-313a-8bd1-95dc28e3c2a6","portVisitReference":"VV-20261125-72FBF09B","UNLocationCode":"NOFRO"},
  "terminalCall":{"terminalCallID":"daac26f0-0441-319b-a4fd-f51dba07fc68","sequenceNumber":1},
  "portCallService":{"portCallServiceID":"7c995b6a-6c0f-3885-9ab5-3f0069c0ad3a",
    "portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","portCallPhaseTypeCode":"INBD","facilityTypeCode":"BRTH",
    "serviceLocation":{"locationName":"Berth 4","geoCoordinate":{"latitude":"61.61","longitude":"5.071"},"UNLocationCode":"NOFRO"},
    "isCanceled":true},
  "vessel":{"vesselName":"VARTDAL","vesselTypeCode":"GCGO","vesselSizeUnit":"MTR","lengthOverall":120.0,"width":20.0},
  "timestamp":{"classifierCode":"REQ","serviceDateTime":"2026-11-25T10:40:45Z"}}]}
```

**What to notice.**

- `B4` is free. Had a ship booked on another berth been able to start on `B4`
  more than 4 hours sooner, it would have moved there, as HAREID did in step
  10. The story *A ship goes quiet* in the console shows exactly that.
- VARTDAL may announce itself again with a new `EST`. That opens a new visit,
  planned like any new call.
- A call can be cancelled only while its ship has not arrived. Cancelling it
  twice, or cancelling a ship alongside, answers `409`; an unknown reference
  answers `404`:

```json
{"statusCode":409,"message":"Conflict: Visit VV-20261125-72FBF09B is already over","timeStamp":1795588851999}
{"statusCode":409,"message":"Conflict: The vessel is alongside; release its berth when it leaves","timeStamp":...}
{"statusCode":404,"message":"Visit not found: No visit has the reference VV-NOT-THERE","timeStamp":1795588852094}
```

**DCSA:** the action is the terminal's own, but its result is published in the
standard's terms. `portCallService.isCanceled` is "this port call service is
canceled by the service provider", and for a berth the provider is the
terminal. It is a different flag from `isOmitted`, which is the carrier
dropping the call (next step).

### Step 14 — Omit a call

ULSTEIN will not call after all:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" \
  -d '{"events":[{"vessel":{"vesselName":"ULSTEIN"},"portCall":{"UNLocationCode":"NOFRO","isOmitted":true}}]}'
```
```json
{}
```

The visit's phase becomes `CANCELLED`, with `"cancelledBy":"VESSEL"`, and `B3`
is free. Only HAREID (`B1`) remains in `curl -s $API/api/visits`.
**DCSA:** `isOmitted` on the port call, "the port call is omitted by the
carrier". An omitted event needs no service and no timestamp.

### Step 15 — A visit's whole history, and the berth plan (`ConsoleController`)

```bash
REF=$(curl -s "$API/api/visits?vessel=HAREID" | pick "d[0]['visitRef']")
curl -s $API/api/visits/$REF
```

The answer has three parts: `visit` (as in step 5), `events` (the ledger) and
`reports` (every position the ship gave). HAREID's ledger reads as the story of
steps 9 to 11, in order:

```
 ESTIMATED  2026-11-25T12:53:21Z  by VESSEL                the first estimate
 REQUESTED  2026-11-26T08:57:44Z  by TERMINAL  berth B2    "Berth expected free 2026-11-26T07:57:44Z": after SELJE
 PLANNED    2026-11-26T08:57:44Z  by VESSEL    berth B2    accepted
 REQUESTED  2026-11-25T12:53:21Z  by TERMINAL  berth B1    "Moved from B2 to B1, which can take it 20 h 4 min sooner"
 ESTIMATED  2026-11-25T14:53:21Z  by VESSEL                delayed two hours
 REQUESTED  2026-11-25T14:53:21Z  by TERMINAL  berth B1    the terminal follows
```

There is no entry for NORDVIK's early arrival: HAREID's time did not change
then. Each entry also carries `recordedAt`: when it was said, as distinct from
the time it is about.

```bash
curl -s $API/api/port/berth-plan
```

One lane per berth, each with its windows: every one still open, and completed
ones that overlap the period. Here `B1` has NORDVIK's completed window and
HAREID's planned one, `B2` has SELJE's completed window, and `B3` and `B4` are
empty: a cancelled window never occupied its berth and is left out. Without
`from` and `to` the period is 12 hours back to 48 hours ahead; name your own
with `?from=2026-11-22T00:00:00Z&to=2026-11-25T00:00:00Z`.

`curl -s "$API/api/visits?state=all"` includes the closed visits, newest first:
HAREID still open, VARTDAL cancelled by the `TERMINAL`, ULSTEIN cancelled by
the `VESSEL`, SELJE and NORDVIK departed.

### Step 16 — The feed in full: filters and paging (`PortCallEventsController`, `GET`)

```bash
curl -s -D - -o /dev/null "$API/api/port-call/v2/events?limit=3"
```
```
HTTP/1.1 200
API-Version: 2.0.0
Next-Page-Cursor: 359
```

`Next-Page-Cursor` appears only when more events remain. It is the service's
own marker, so yours will be a different value. Send it back unchanged as
`cursor`, **with the same filters**, for the next page:

```bash
curl -s "$API/api/port-call/v2/events?limit=3&cursor=359"
```

The ten filters this publisher supports:

| Filter | Example |
|---|---|
| `classifierCode` | `?classifierCode=PLN` (every acceptance) |
| `vesselName`, `vesselIMONumber`, `vesselMMSINumber` | `?vesselName=HAREID` |
| `portCallServiceTypeCode` | `?portCallServiceTypeCode=BERTH` |
| `UNLocationCode` | `?UNLocationCode=NOFRO` |
| `eventTimestampMin`, `eventTimestampMax` | `?eventTimestampMin=2026-11-23T00:00:00Z` |
| `limit` (at most 500, default 100), `cursor` | above |

Events of a call that did not take place carry a flag, and the flag says who
ended it:

| Call | In each of its events |
|---|---|
| `?vesselName=ULSTEIN`, omitted by its operator (step 14) | `"portCall":{..., "isOmitted":true}` and `"terminalCall":{..., "isOmitted":true}` |
| `?vesselName=VARTDAL`, cancelled by the terminal (step 13) | `"portCallService":{..., "isCanceled":true}` |

### Step 17 — A ship that fits no berth, and one that fits only some

A ship's size travels in the estimate, in the standard's own optional fields:
`lengthOverall` and `width` (the beam) in `vesselSizeUnit`, and `draft` in
`draftUnit`, either metres (`MTR`) or feet (`FOT`). The terminal requires the
length and beam once per ship, and gives a ship only a berth it fits. Here `B1` and `B2` take 190 m, a 32 m beam and a 10.5 m
draft; `B3` and `B4` take 140 m, 24 m and 7.5 m.

A ship too big for every berth:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{"vesselName":"GIANT","vesselTypeCode":"GCGO",
            "vesselSizeUnit":"MTR","lengthOverall":250,"width":40,"draftUnit":"MTR","draft":12},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"$(date -u -d '+3 hours' +%Y-%m-%dT%H:%M:%SZ)"}
}]}
EOF
```
```json
{"feedbackElements":[{"severity":"ERROR","message":"No berth here takes GIANT: length 250 m, beam 40 m, draft 12 m; the largest berth here takes length 190 m, beam 32 m, draft 10.5 m","propertyPath":"$.events[0]"}]}
```

No call is opened: a ship that fits nowhere is not left waiting for a berth
that will never take it.

A ship the terminal has never heard of, with no size: ALDA, due in two hours,
sends only its name and time.

```json
{"feedbackElements":[{"severity":"ERROR","message":"The terminal holds no length overall or beam for ALDA; send lengthOverall and width (in vesselSizeUnit) with its estimate. A berth is given only to a vessel known to fit it","propertyPath":"$.events[0]"}]}
```

No call is opened and no vessel record is left behind. A length without a beam
is refused the same way, naming the beam.

Now three ships. ALDA sends again, 120 m with a 20 m beam. LANGE is 180 m long
with a 9 m draft, due in an hour; KORT is 120 m, also due in an hour, and
reports after LANGE. (Send each as above, with its own name, time and size.)

```bash
curl -s "$API/api/visits?vessel=LANGE"
```
```json
[{"visitRef":"VV-20261123-E032AF33",
  "vessel":{"code":"LANGE","name":"LANGE","imoNumber":null,"mmsi":null,"type":"GENERAL_CARGO","loaMeters":180.00,"beamMeters":28.00},
  "phase":"INBOUND","terminalCode":"FRO-CT",
  "berth":{"berthCode":"B2","berthName":"Berth 2","status":"PLANNED","plannedStart":"2026-11-23T07:18:16Z","plannedEnd":"2026-11-24T06:35:16Z", ...},
  "queueNumber":null,"etaBerth":"2026-11-23T07:18:16Z","rtaBerth":"2026-11-23T07:18:16Z", ...
  "arrivalDraftMeters":9.00}]
```

| Ship | Size | Got |
|---|---|---|
| ALDA | 120 m, beam 20 m, no draft | `B3`, at its own estimate |
| LANGE | 180 m, draft 9 m | `B2`, at its own estimate |
| KORT | 120 m, draft 6.5 m | `B4`, at its own estimate |

**What to notice.** LANGE fits only `B1` and `B2`. `B1` is booked for HAREID
from `14:53`, and the seven hours before that are too short for LANGE's stay,
so LANGE takes `B2`. ALDA took `B3`: `B2` was in its hour of turnaround after
SELJE, and of berths a ship can start on equally soon, the one whose last ship
left first wins. ALDA gave no draft, so the largest it has ever declared would
be used; it has declared none, so only its length and beam are checked.

**DCSA:** the size fields are the standard's own, optional, on the vessel of
any event. The terminal requires the length and beam once per ship (section
12). A unit other than `MTR` or `FOT` is refused with `ERROR`
("vesselSizeUnit must be MTR or FOT").

### Step 18 — Maintenance on a berth (`ConsoleController`)

Maintenance is known in advance and recorded by the planner. No ship is given
a berth for a stay that would run into a window, and a window over a booked
ship's stay moves that ship.

LANGE is booked on `B2` from `07:18`. The planner records fender repairs there
from three hours from now, for three hours:

```bash
curl -s -X POST $API/api/port/berths/B2/maintenance -H "$J" \
  -d "{\"from\":\"$(date -u -d '+3 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"to\":\"$(date -u -d '+6 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"reason\":\"Fender repair\"}"
```
```json
{"id":5,"berthCode":"B2","berthName":"Berth 2","from":"2026-11-23T09:18:16Z","to":"2026-11-23T12:18:16Z","reason":"Fender repair"}
```

LANGE's booking is planned again around the window, and it is told:

```
 LANGE  REQ  2026-11-23T12:18:16Z  B2  "Moved for maintenance on B2, 2026-11-23T09:18:16Z to 2026-11-23T12:18:16Z"
```

A ship moved for maintenance has first claim on the berths: it is planned
again before any other booking moves. Here `B2` straight after the window is
the soonest it can be alongside anywhere. A window over the stay of a ship
**alongside** is refused with `409` ("B2 has … alongside until …; maintenance
cannot overlap a vessel at the berth"): a ship at the berth cannot be moved
by a plan.

A second window, on `B4` from an hour from now for 29 hours, moves KORT the
same way, to `B3` after ALDA, tomorrow at `08:35:15`:

```bash
curl -s -X POST $API/api/port/berths/B4/maintenance -H "$J" \
  -d "{\"from\":\"$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)\",\"to\":\"$(date -u -d '+30 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"reason\":\"Crane rail\"}"
```
```json
{"id":6,"berthCode":"B4","berthName":"Berth 4","from":"2026-11-23T07:18:17Z","to":"2026-11-24T12:18:17Z","reason":"Crane rail"}
```

A third window on `B4` that overlaps it answers `409` ("B4 already has
maintenance from 2026-11-23T07:18:17Z to 2026-11-24T12:18:17Z"); one that
ends before it starts, or is already over, answers `400`.

Now SMAA, 100 m, due in two hours, reports. `B4` is free, but a stay of almost
a day would run into the window, and the other berths are booked, so SMAA is
booked on `B4` straight after the window:

```
 SMAA  B4  PLANNED  2026-11-24T12:18:17Z .. 2026-11-25T11:35:17Z   eta 2026-11-23T08:18:17Z
```

The windows are listed by `GET /api/port/maintenance`, and each berth's lane in
`GET /api/port/berth-plan` carries its own under `maintenance`:

```json
[{"id":6,"berthCode":"B4","berthName":"Berth 4","from":"2026-11-23T07:18:17Z","to":"2026-11-24T12:18:17Z","reason":"Crane rail"},
 {"id":5,"berthCode":"B2","berthName":"Berth 2","from":"2026-11-23T09:18:16Z","to":"2026-11-23T12:18:16Z","reason":"Fender repair"}]
```

Remove the `B4` window (your id will differ), and the plan is worked out
again without it:

```bash
curl -s -X DELETE $API/api/port/berths/B4/maintenance/6
```
```json
{"id":6,"removed":true}
```

SMAA, booked on `B4`, moves up to its own estimate, `2026-11-23T08:18:17Z`.
KORT stays on `B3` tomorrow: `B4` cannot take its day-long stay now without
delaying SMAA, and a booking never moves into a place that would delay
another. Remove the `B2` window too (`DELETE .../B2/maintenance/5`): LANGE
returns to its own estimate, and the rest of the walk-through has no
maintenance. An id that does not exist on a berth answers `404`.

**DCSA:** not part of the standard, which does not describe a terminal's
berths. The ships see only the result: requested arrivals that already allow
for maintenance.

### Step 19 — A booked ship falls behind

ALDA accepts `B3` for `2026-11-23T08:18:15Z` (a `PLN`, as in step 7). Then it
sends a new estimate six hours later:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"ALDA"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"2026-11-23T14:18:15Z"}}]}
EOF
```
```json
{}
```

| Ship | Before | After |
|---|---|---|
| ALDA | `B3`, agreed for `08:18:15` | `B2` after LANGE, asked for `2026-11-24T07:35:16Z` |
| KORT | `B3` after ALDA, tomorrow at `08:35:15` | `B3`, asked for its own estimate, `2026-11-23T07:18:16Z` |

And in the ledger:

```
 ALDA  REQ  2026-11-24T07:35:16Z  B2  "Berth B3 withdrawn: this estimate is 6 h after the agreed arrival,
                                       and another vessel can be alongside sooner"
 KORT  REQ  2026-11-23T07:18:16Z  B3  "Given up by ALDA, now expected 6 h after its agreed arrival"
```

**What to notice.**

- A booking is held from the terminal's answer. It is **given up** when the
  ship holding it sends an estimate more than the tolerance (4 hours) after
  its agreed arrival **and** another ship can be alongside on that berth
  sooner: one booked behind it there, one booked on another berth that would
  gain more than 4 hours, or one with no booking yet. Both have to be true:
  had nobody been able to use `B3` sooner, ALDA would have kept it at its new
  time.
- ALDA is planned again at once, like a new call, and is now booked on `B2`.
  KORT, the ship behind it on `B3`, moves up to its own estimate.
- A smaller delay changes nothing but the time. KORT accepted `B3` for
  `07:18:16`, then sent `09:18:16`, two hours later: it keeps `B3`, the
  terminal asks for `09:18:16`, and KORT must accept that new time.

**DCSA:** DCSA's weekly berth planning process (7 days to 48 hours before
arrival) asks "Is PTA Berth delayed by 12H?" and, if so, sends both sides
back to estimate and request again. This service applies the same rule with
its own tolerance, 4 hours, because its ships report within the last day or
two. Lateness is measured against the time the ship agreed, as there.

### Step 20 — What a ship reports is checked against its call

Reset the port first (section 11). Each report from a ship has to fit the
state of its call. SVANOY estimated an arrival three hours ago, reported it
was alongside at that time, and then tries five things.

```bash
T0=$(date -u -d '-3 hours' +%Y-%m-%dT%H:%M:%SZ)
for C in EST ACT; do curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"SVANOY","vesselSizeUnit":"MTR","lengthOverall":120,"width":20},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"$C","serviceDateTime":"$T0"}}]}
EOF
echo; done
```

The estimate is applied with a warning, since its time has passed:
`WARN` `Applied, but the estimated arrival … has already passed: the ship is
planned from now`. The arrival answers `{}`.

Then, each sent the same way:

| SVANOY sends | Answer (`ERROR` in `feedbackElements`) |
|---|---|
| an omitted call (`"portCall":{"isOmitted":true}`) | `Vessel SVANOY is alongside; report its departure instead of omitting the call` |
| an `EST` arrival | `Vessel SVANOY is alongside; an estimated arrival no longer applies to this call` |
| an `ACT` departure dated before its arrival | `The departure (2026-11-23T02:18:21Z) is before the arrival (2026-11-23T03:18:21Z)` |
| an `ACT` departure three hours from now | `An actual departure cannot be in the future: 2026-11-23T09:18:22Z has not happened yet` |
| an `ACT` departure an hour ago | `{}`: accepted |

The ledger keeps the departure at the time the ship sent, as the ship's own
report:

```
 DEPARTURE_BERTH  ACTUAL  2026-11-23T05:18:22Z  by VESSEL  B3   recorded 2026-11-23T06:18:22Z
```

A ship that never arrived cannot leave, and an arrival cannot be reported
ahead of time. VIGRA, booked for an hour from now:

| VIGRA sends | Answer |
|---|---|
| an `ACT` departure | `Vessel VIGRA has not arrived; a departure needs an arrival first` |
| an `ACT` arrival five hours from now | `An actual arrival cannot be in the future: 2026-11-23T11:18:22Z has not happened yet` |

An event for another port is refused, whether the port is named in `portCall`
or in the service's location; an event that names no port is taken as meant
here:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"portCall":{"UNLocationCode":"NLRTM"},"vessel":{"vesselName":"TANGEN"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"2026-11-23T08:18:22Z"}}]}
EOF
```
```json
{"feedbackElements":[{"severity":"ERROR","message":"This terminal is at NOFRO; the event is for NLRTM","propertyPath":"$.events[0]"}]}
```

No call is opened and no vessel record is left behind:
`GET /api/visits?vessel=TANGEN&state=all` answers `[]`.

**What to notice.**

- An actual time may be up to 5 minutes ahead of the terminal's clock, to
  allow for clocks that differ a little; more than that is refused.
- The planner's *Release* (step 12) still records a departure at the moment
  it is pressed, as the terminal's own report.
- A ship that has accepted a time and then sends the same estimate again
  keeps its acceptance: HELLE accepted `2026-11-24T02:18:23Z`, sent that
  estimate again, and its ledger shows no new request. A request is re-sent
  only when the time moves by 15 minutes or more.
- An arrival at a berth where another ship still lies is refused too: step 22
  shows it.

**Repeated and out-of-order messages.** Messages arrive at least once and in
no promised order. Each event may carry the standard's `eventID` and
`eventUpdatedDateTime` (when the sender made it); the console's events always
do. Captured on 24 Nov 2026, for a ship that sent an estimate ten minutes
earlier:

| The ship sends | Answer |
|---|---|
| the same event again (same `eventID`) | `INFO`: `Already received at 2026-11-24T05:18:07.425250Z; this event was applied then and is not applied again`. Nothing changes |
| the same `eventID` with a later `eventUpdatedDateTime` and a new time | `{}`: an update of the event, applied |
| an estimate made 20 minutes ago that arrives only now, after the one above | `WARN`: `Not applied: this event was sent at 2026-11-24T04:58:08Z, before the latest event from this vessel, sent at 2026-11-24T05:17:08Z, which overtook it`. The plan is unchanged |
| an omitted call made 30 minutes ago that arrives only now | `WARN`, the same reason. The call stays open |
| an `eventUpdatedDateTime` an hour ahead of the terminal's clock | `ERROR`: `eventUpdatedDateTime cannot be in the future: 2026-11-24T06:18:10Z has not happened yet` |

After the ship omits its call, an estimate made two minutes before the
omission and delivered afterwards is not applied either, so it opens no new
call.

- Only estimates and omitted calls are put in order. A planned arrival must
  equal the requested time, and an actual time is checked against the call's
  state (a second departure meets a closed call), so a stale one of those is
  refused already.
- An event without `eventUpdatedDateTime` cannot be put in order: it is
  applied as it comes and takes no part in the order. One without `eventID`
  cannot be recognised as a repeat.
- **DCSA:** `eventID` is the event's "universal unique identifier", and
  `eventUpdatedDateTime` is "when the technical event was last updated". Each
  event is applied in its own transaction, together with the record that it
  was received.

### Step 21 — A call far ahead holds no berth yet

Reset the port first. FEDJE sends an estimate sixty days ahead, with every
berth free:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"FEDJE","vesselSizeUnit":"MTR","lengthOverall":120,"width":20},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"2027-01-22T06:18:24Z"}}]}
EOF
curl -s "$API/api/visits?vessel=FEDJE"
```
```json
[{... "phase":"INBOUND","berth":null,"queueNumber":1,
  "etaBerth":"2027-01-22T06:18:24Z","rtaBerth":"2027-01-22T06:18:24Z","ptaBerth":null, ...}]
```

And in its ledger:

```
 REQUESTED  2027-01-22T06:18:24Z  by TERMINAL   "A berth is given within 7 days of arrival"
```

A ship with no berth cannot report an arrival; FEDJE trying gets:

```json
{"feedbackElements":[{"severity":"ERROR","message":"The vessel has no berth allocated yet","propertyPath":"$.events[0]"}]}
```

SOLUND, due in two hours, is booked on `B3` at once. Three days before its
arrival FEDJE sends a fresh estimate, and is booked:

```json
[{... "berth":{"berthCode":"B4","berthName":"Berth 4","status":"PLANNED","plannedStart":"2026-11-26T06:18:24Z", ...},
  "queueNumber":null,"etaBerth":"2026-11-26T06:18:24Z","rtaBerth":"2026-11-26T06:18:24Z", ...}]
```

**What to notice.**

- Every ship due within 7 days (`port.planning.booking-horizon-hours`, 168)
  holds a booking: a berth and a window. A ship due later is told when to
  come and keeps its place in line, but holds no berth, and only such a ship
  has a `queueNumber`.
- It is booked at the next change to the plan inside the horizon. Any event
  causes one, and a ship is expected to send an estimate 72 hours out anyway.
- FEDJE got `B4`: of berths a ship can start on equally soon, it takes the
  one whose last ship left first, which has the most time in hand.
- A booked ship whose new estimate moves beyond the horizon gives its booking
  up, with the remark "Berth B1 withdrawn: the new estimate is more than 7 days
  away", and keeps its place in line.

### Step 22 — A ship stays longer (`ConsoleController`)

Reset the port first. A berth's planned end is the arrival plus a typical stay
for the ship's type, so about half of all ships run past it. KVITSOY sends an
estimate and an actual arrival for 30 hours ago: it has been alongside `B3`
since `2026-11-22T00:18:25Z`, so its planned end, `23:35:25` yesterday, has
passed. SULA, ATLOY and HOLMEN, due in an hour, are booked on the other three
berths.

```bash
curl -s "$API/api/visits?vessel=KVITSOY"
```
```json
[{... "phase":"BERTHED",
  "berth":{"berthCode":"B3","berthName":"Berth 3","status":"ACTIVE", ...,"plannedEnd":"2026-11-22T23:35:25Z", ...},
  "ataBerth":"2026-11-22T00:18:25Z", ... "overstayingSince":"2026-11-22T23:35:25Z"}]
```

BJORNSUND reports that it can be alongside in ten minutes, at `06:28:26`:

```json
[{... "berth":{"berthCode":"B3","berthName":"Berth 3","status":"PLANNED","plannedStart":"2026-11-23T08:18:26.201438Z", ...},
  "etaBerth":"2026-11-23T06:28:26Z","rtaBerth":"2026-11-23T08:18:26.201438Z", ...}]
```

and in its ledger, `REQUESTED 08:18:26 "Berth expected free 2026-11-23T07:18:26.201437500Z"`.

**What to notice.**

- A ship still alongside after its planned end is taken to be **still there**.
  The terminal assumes it leaves one step from now
  (`port.planning.overstay-step-minutes`, 60), and the next ship can come
  after the hour's turnaround: two hours from now. BJORNSUND is booked on `B3`
  behind KVITSOY, because every other berth is booked for a day.
- `overstayingSince` is the planned end that passed; the console shows
  "Overstaying, 6 h 43 min past planned end". It is worked out each time the
  plan changes or is read; no timer runs.

BJORNSUND cannot report an arrival at `B3` while KVITSOY is recorded there:

```json
{"feedbackElements":[{"severity":"ERROR","message":"B3 still has KVITSOY alongside; its departure is recorded first","propertyPath":"$.events[0]"}]}
```

The database itself allows only one ship alongside a berth.

The planner, who knows the cranes and the cargo left, sets when KVITSOY is
expected to leave:

```bash
REF=$(curl -s "$API/api/visits?vessel=KVITSOY" | pick "d[0]['visitRef']")
curl -s -X PUT $API/api/visits/$REF/expected-completion -H "$J" -d '{"at":"2026-11-23T09:18:26Z","reason":"Crane breakdown"}'
```
```json
{"expectedCompletion":"2026-11-23T09:18:26Z","visitRef":"VV-20261123-AD3A4640"}
```

`B3`'s planned end is now `09:18:26`, the flag is gone, and BJORNSUND is asked
for `10:18:26`, an hour after it, with the remark "Berth expected free
2026-11-23T09:18:26Z". Had another berth been able to take BJORNSUND more than
4 hours sooner, its booking would have moved there instead. KVITSOY's ledger
and the DCSA feed carry the terminal's estimate:

```
 CARGO_OPS_COMPLETE  ESTIMATED  2026-11-23T09:18:26Z  by TERMINAL  B3   "Crane breakdown"
```
```bash
curl -s "$API/api/port-call/v2/events?portCallServiceTypeCode=CARGO_OPERATIONS"
```
```
 KVITSOY  CARGO_OPERATIONS  CMPL  ALGS  EST  2026-11-23T09:18:26Z  "Crane breakdown"  Berth 3
```

A time already past answers `400` ("the expected departure must be in the
future"); a ship not alongside answers `409` ("Vessel SULA is not alongside;
an expected departure is set for a vessel at its berth").

KVITSOY then reports its departure at `06:18:27`, earlier than expected.
BJORNSUND moves up on `B3` to `07:18:27`: the turnaround after the actual
departure.

**DCSA:** the planner's time is the standard's estimated completion of cargo
operations (`CARGO_OPERATIONS`, `CMPL`, `EST`), which IMO FAL.5/Circ.52 calls
the ETC: "the date and time the berth planner estimates that all cargo
operations ... will be completed". In a full terminal operating system it is
forecast from the moves left and the cranes on the ship; here the planner
types it.

### Step 23 — An early ship waits its turn

Reset the port first. Ships 180 m long fit only `B1` and `B2`. HELLESOY is
alongside `B1`. ASKVOLL is due in three days: it is booked on `B2` and accepts
the requested arrival. DALE is due in an hour, and is booked on `B2` too, in
the gap before ASKVOLL: its day alongside ends long before ASKVOLL's window.

```bash
send() {  # vessel, ARRI or DEPA, EST/PLN/ACT, time
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"$1","vesselSizeUnit":"MTR","lengthOverall":180,"width":28},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"$2","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"$3","serviceDateTime":"$4"}}]}
EOF
echo; }
NOW=$(date -u +%Y-%m-%dT%H:%M:%SZ)
send HELLESOY ARRI EST $NOW; send HELLESOY ARRI ACT $NOW
send ASKVOLL ARRI EST $(date -u -d '+72 hours' +%Y-%m-%dT%H:%M:%SZ)
send ASKVOLL ARRI PLN $(curl -s "$API/api/visits?vessel=ASKVOLL" | pick "d[0]['rtaBerth']")
send DALE ARRI EST $(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)
```

`B2`'s line is now DALE from `2026-11-24T10:29:51Z`, then ASKVOLL from
`2026-11-27T09:29:50Z`, agreed. ASKVOLL reports that it is alongside `B2`,
three days early:

```bash
send ASKVOLL ARRI ACT $(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -s "$API/api/visits?vessel=ASKVOLL"
```
```json
{"feedbackElements":[{"severity":"ERROR","message":"B2 is booked for DALE from 2026-11-24T10:29:51Z, ahead of this vessel's turn at 2026-11-27T09:29:50Z; a vessel comes in out of turn only when its stay ends before the next booking","propertyPath":"$.events[0]"}]}
[{... "vessel":{"code":"ASKVOLL", ...},"phase":"INBOUND", ...
  "berth":{"berthCode":"B2","berthName":"Berth 2","status":"PLANNED","plannedStart":"2026-11-27T09:29:50Z", ...},
  ... "ptaBerth":"2026-11-27T09:29:50Z","ataBerth":null, ...
  "aheadOnBerth":{"vesselCode":"DALE","vesselName":"DALE","status":"PLANNED","plannedStart":"2026-11-24T10:29:51Z"}}]
```

**What to notice.**

- The arrival is refused and nothing moves: ASKVOLL is still `INBOUND`, and
  DALE keeps its time and is told nothing new. An early arrival gains nothing
  over a ship booked before it.
- `aheadOnBerth` names the ship whose turn comes first. It is worked out each
  time the view is read. The console's *My vessel* page shows it in place of
  *Report arrival*: "B2 is booked for DALE first, from 11:29. Report arrival
  at your turn, once it has left."
- An early ship is not always refused. One whose stay and the hour's
  turnaround end before the next booking starts delays no one, and is taken
  in.

DALE accepts and arrives. ASKVOLL's `aheadOnBerth` becomes DALE with status
`ACTIVE`, and an arrival now answers `B2 still has DALE alongside; its
departure is recorded first`. Once DALE reports its departure, `aheadOnBerth`
is `null`: ASKVOLL is first in line, and its arrival is recorded.

**Ports:** where berthing windows are booked, an early ship waits. Napier
Port: an early vessel "can only occupy the berth if it is vacant and it
doesn't impact on any other window arrangement". Ports that serve ships in
order of arrival count arrival at the anchorage or the pilot station, not at
the berth. DCSA has events for both (`ANCHORAGE`, `PILOT_BOARDING_PLACE`):
this terminal takes the anchorage (step 24), not the pilot boarding place
(section 12).

### Step 24 — An early ship anchors, and is brought in sooner

Carry on from step 23 before DALE arrives: HELLESOY is alongside `B1`, DALE is
due on `B2` in an hour, and ASKVOLL, agreed for three days from now, is booked
behind it. (Or reset the port and run step 23's first block again.) ASKVOLL is
here early. It anchors and says so, with the standard's anchorage service:

```bash
anchorage() {  # vessel, STRT (anchored) or CMPL (left the anchorage), time
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"$1"},
  "portCallService":{"portCallServiceTypeCode":"ANCHORAGE","portCallServiceEventTypeCode":"$2","facilityTypeCode":"ANCH"},
  "timestamp":{"classifierCode":"ACT","serviceDateTime":"$3"}}]}
EOF
echo; }
anchorage ASKVOLL STRT $(date -u +%Y-%m-%dT%H:%M:%SZ)
curl -s "$API/api/visits?vessel=ASKVOLL"
```
```json
{}
[{... "vessel":{"code":"ASKVOLL", ...},"phase":"ARRIVED", ...
  "berth":{"berthCode":"B2","berthName":"Berth 2","status":"PLANNED","plannedStart":"2026-11-25T12:35:37Z","plannedEnd":"2026-11-26T11:52:37Z", ...},
  "etaBerth":"2026-11-27T11:18:36Z","rtaBerth":"2026-11-25T12:35:37Z","ptaBerth":null, ...
  "ataAnchorage":"2026-11-24T11:18:37Z","atdAnchorage":null, ... "nextEstimateBy":null, ...
  "aheadOnBerth":{"vesselCode":"DALE","vesselName":"DALE","status":"PLANNED","plannedStart":"2026-11-24T12:18:37Z"}}]
```

and in its ledger:

```
 ANCHORAGE_START  ACTUAL     2026-11-24T11:18:37Z  by VESSEL
 ARRIVAL_BERTH    REQUESTED  2026-11-25T12:35:37Z  by TERMINAL  B2  "At the anchorage since 2026-11-24T11:18:37Z, ready to come in"
```

**What to notice.**

- ASKVOLL is brought forward by almost two days: from Friday to an hour after
  DALE is planned to leave (`11:35:37`, then the turnaround). It is here, so
  its estimate no longer says when it can come. The terminal takes it to be
  ready an hour after it anchored (`port.planning.anchorage-to-berth-minutes`,
  60, our own choice: weighing anchor, the pilot, the passage in).
- DALE keeps its time and is told nothing. A ship at anchor moves up only
  behind the ships booked before it on its berth, never into a gap that would
  delay a booking. A berth that could take it more than 4 hours sooner, in
  such a gap, would take it instead.
- The new time is a new request: `ptaBerth` is empty again, and ASKVOLL
  accepts as in step 7. The phase is `ARRIVED`, in port but not alongside. No
  further estimate is expected of it (`nextEstimateBy` is `null`); its draft
  is still due.
- On *My vessel*, *Report arrival at the anchorage* sends this event.
  Afterwards the page shows "At the anchorage, …", and so does the planner's
  arrivals table.

The feed publishes it in the standard's terms. The anchorage has no port call
phase, so none is sent:

```bash
curl -s "$API/api/port-call/v2/events?portCallServiceTypeCode=ANCHORAGE"
```
```json
{"events":[{... "portCallService":{... "portCallServiceTypeCode":"ANCHORAGE","portCallServiceEventTypeCode":"STRT","facilityTypeCode":"ANCH"},
  "vessel":{"vesselName":"ASKVOLL", ...},"timestamp":{"classifierCode":"ACT","serviceDateTime":"2026-11-24T11:18:37Z"}}]}
```

Only actual anchorage times are taken. An estimated one answers `WARN`:
`Not processed: this terminal records actual anchorage times only`. Anchoring
again answers `ERROR`: `Vessel ASKVOLL has been at the anchorage since
2026-11-24T11:18:37Z; it reports leaving it first`. When ASKVOLL weighs anchor,
`anchorage ASKVOLL CMPL …` records `atdAnchorage`. The plan does not change,
since the ship was already taken to be ready from the anchorage.

**DCSA and ports:** the anchorage service has the event types `STRT` and
`CMPL` and the facility `ANCH`. Ports that serve ships in order of arrival
count arrival at the anchorage and call a ship in when its berth is ready.
The IMO's just-in-time guide aims to make that wait unnecessary, by timing the
ship's arrival from the start.

## 7. What the service refuses, and how

Worth running: a system is as clear in its refusals as in its answers.

| Send | Answer |
|---|---|
| `GET /api/port-call/v2/events?carrierServiceCode=FE1` (a filter it does not support) | `400`, `feedbackElements` naming the ten it does |
| `POST` events for a service other than the berth and the anchorage, e.g. `"portCallServiceTypeCode":"PILOTAGE"` | `200`, `WARN`: `Not processed: this terminal acts on BERTH and ANCHORAGE events only` |
| `POST` an anchorage time that is not actual (`EST`, `PLN`) | `200`, `WARN`: `Not processed: this terminal records actual anchorage times only` |
| `POST` an anchorage arrival for a ship already at anchor or alongside; leaving an anchorage it is not at, or before it arrived there | `200`, `ERROR`, saying which (step 24) |
| `POST` a `REQ` (the terminal's own kind of time) | `200`, `ERROR`: `A requested time at berth is issued by the terminal, not sent to it` |
| `POST` an estimated or planned **departure** | `200`, `WARN`: it is addressed to the port authority |
| `POST` a `PLN`, `ACT` or omit for a ship with no open call | `200`, `ERROR`: `Vessel X has no open visit` |
| `POST` a position that cannot be read, e.g. `"latitude":"north"` | `200`, `ERROR` for that event |
| `POST` `{"events":[]}` or a body that is not JSON | `400`, `feedbackElements` |
| `POST` an event that cannot be read, such as a time with no UTC offset or a word where a number belongs | `200`, `ERROR` for that event only, at the field: `timestamp.serviceDateTime: '2026-10-12T14:00:00' is not a date-time with a UTC offset; send ISO 8601, for example 2026-11-25T10:00:00Z`, path `$.events[1].timestamp.serviceDateTime`. The other events are applied |
| `POST` an estimated arrival more than 5 minutes in the past | `200`, `WARN`: `Applied, but the estimated arrival … has already passed: the ship is planned from now`. The estimate is applied |
| `GET /api/visits/VV-NOT-THERE` | `404`, `{"statusCode":404,"message":"Visit not found: ..."}` |
| `GET /api/visits?state=closed` (only `open` and `all` exist) | `400`, `state must be 'open' or 'all'` |
| `PUT /api/visits/{visitRef}/cancel` for a ship alongside, or a visit already over | `409`, saying which |
| `PUT /api/visits/VV-NOT-THERE/cancel` | `404` |
| `POST` an estimate for a ship that fits no berth | `200`, `ERROR`: `No berth here takes …`, with its size and the largest berth's limits. No call is opened |
| `POST` the first estimate of a ship whose length overall or beam the terminal does not hold | `200`, `ERROR`: `The terminal holds no length overall or beam for …`, naming what is missing. No call is opened and no vessel record is left |
| `POST` a size in a unit other than `MTR` or `FOT` | `200`, `ERROR`: `vesselSizeUnit must be MTR or FOT` |
| `POST /api/port/berths/{code}/maintenance` over the stay of the ship alongside, or over another window | `409`, saying which. Over a booked ship's window it is accepted, and the ship is planned again (step 18) |
| `PUT /api/port/berths/{code}/release` for a berth with no ship alongside, even one with bookings | `200`, `"released":false`; nothing is recorded |
| `POST` an `ACT` arrival at a berth where another ship is still recorded alongside | `200`, `ERROR`: `B3 still has KVITSOY alongside; its departure is recorded first` |
| `POST` an `ACT` arrival before the ship's turn: a ship booked ahead of it on the berth is still to come, and this stay would not be over before it | `200`, `ERROR`: `B2 is booked for DALE from …, ahead of this vessel's turn at …; a vessel comes in out of turn only when its stay ends before the next booking`. Nothing changes (step 23) |
| `POST /api/port/berths/{code}/maintenance` ending before it starts, already over, or without `from` or `to` | `400`, saying which: `to must be after from`, `the window must end in the future`, `from: must not be null` |
| `DELETE /api/port/berths/{code}/maintenance/{id}` with an id that berth does not have | `404` |
| `PUT /api/visits/{visitRef}/cancel` with a `reason` longer than 400 characters | `400`, `reason: size must be between 0 and 400` |
| `PUT /api/visits/{visitRef}/expected-completion` with a time already past, or without `at` | `400`, `the expected departure must be in the future` or `at: must not be null` |
| `PUT /api/visits/{visitRef}/expected-completion` for a ship not alongside | `409`, `Vessel … is not alongside; …` |
| `GET /api/port/berth-plan` with `from` after `to`, or a malformed time | `400`, `from must be before to` or `The value of 'from' could not be read` |
| `POST /api/operator/eta` with a negative speed or no `vesselId` | `400`, before the model is asked, e.g. `vesselId: must not be blank` |
| `POST /api/operator/eta` with a latitude outside -90 to 90 or a longitude outside -180 to 180 (the ship's or the destination's) | `400`, naming it, e.g. `lat: must be less than or equal to 90` |
| `POST /api/operator/eta` while the model service is down or does not answer | `503`, `The ETA model service is not answering; try again shortly`, at once if it is down and after 10 seconds if it is hung |
| `POST` an omitted call, an estimate, or a departure the call's state does not allow | `200`, `ERROR`, saying why (step 20) |
| `POST` an event naming another port | `200`, `ERROR`: `This terminal is at NOFRO; the event is for …` |
| `POST` an IMO number that is not 7 digits or fails its check digit, an MMSI that is not 9, or a name over 32 characters with neither | `200`, `ERROR` naming the field, for example `vesselIMONumber 9321484 fails its check digit: the last digit should be 3`. No vessel record is created |
| `POST` an event whose `eventID` was already applied | `200`, `INFO`: `Already received at …`. Not applied again |
| `POST` an estimate or omitted call sent before the latest event already applied for its vessel | `200`, `WARN`: `Not applied: this event was sent at …, before the latest event from this vessel, …` |
| `POST` an `eventUpdatedDateTime` more than 5 minutes ahead of the clock | `200`, `ERROR`: `eventUpdatedDateTime cannot be in the future: …` |
| `POST /api/ships/schedule`, `GET /api/ships/berths`, `POST /api/operator/vessels/X/accept` | `404`. These routes were removed; section 13 says what replaced each |

Two refusals in one request, to see that each is reported against its own
event:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[
  {"vessel":{"vesselName":"HAREID"},
   "portCallService":{"portCallServiceTypeCode":"PILOTAGE","portCallServiceEventTypeCode":"STRT"},
   "timestamp":{"classifierCode":"EST","serviceDateTime":"2026-10-14T03:00:00Z"}},
  {"vessel":{"vesselName":"HAREID"},
   "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
   "timestamp":{"classifierCode":"REQ","serviceDateTime":"2026-10-14T03:00:00Z"}}
]}
EOF
```
```json
{"feedbackElements":[
  {"severity":"WARN","message":"Not processed: this terminal acts on BERTH and ANCHORAGE events only","propertyPath":"$.events[0]"},
  {"severity":"ERROR","message":"A requested time at berth is issued by the terminal, not sent to it","propertyPath":"$.events[1]"}]}
```

Each event is applied in its own transaction, so one that fails does not undo
the others.

---

## 8. The assistant (`AgentController`)

Not part of the flow, and **its tools return fixed sample data**, so its
answers do not describe the real port. It needs the Google AI Studio key from
section 3.5, and each call uses that key's quota.

```bash
curl -s "$API/api/agent/ping?prompt=Say%20hello"

curl -s -X POST $API/api/agent/chat -H "$J" \
  -d '{"message":"A vessel is approaching berth B-1. Is there a conflict?"}'

curl -s -X POST $API/api/agent/evaluate -H "$J" -d '{"mmsi":"123456789","berthId":"B-1"}'
```

`/ping` returns Gemini's text. `/chat` returns a structured decision
(`toolsUsed`, `reasoningSummary`, `operationalDecision`, `instructionsToMaster`)
and `/evaluate` an assessment (`vesselStatus`, `conflictDetected`,
`operationalRecommendation`, `noticeToCaptain`). Connecting its tools to the
real berths and the real model is planned work.

---

## 9. The same flow on screen

Open <http://localhost:5173>. Everything above can be done without typing a
request, and the **Wire** button in the header shows the request each click
sent, so you can compare it with this guide.

| In this guide | In the console |
|---|---|
| Steps 2, 5, 15 | **Harbour** (planner seat): figures, map, berth plan, arrivals table. Click a vessel for its four times and ledger |
| Steps 3, 4, 9 | **My vessel** (operator seat, switch seats in the header): fill in the calculator, *Calculate only*, or *Send estimated arrival to the terminal* |
| Steps 7, 11, 14 | **My vessel**: *Accept requested arrival*, *Propose another time*, *Omit this call* |
| Step 9 | **Harbour**: the berth plan draws each berth's sequence, one bar per booked ship. The buttons above it show 12, 24 or 48 hours, or 3, 7 or 14 days ahead |
| Step 10 | **My vessel**: *Report arrival* (offered once the requested arrival is accepted, and at the ship's turn: before it, the page names the ship it waits for, step 23), *Report departure*. On **Harbour**, a booked ship's requested time, and sometimes its berth, changes by itself |
| Step 12 | **Harbour**: open the visit of a ship alongside, *Release berth* |
| Step 13 | **Harbour**: a late answer shows as "No answer for ..." in the arrivals table and in the *Awaiting the ship's answer* figure. Open the visit of a ship that has not arrived, *Cancel this call*. The ship's own page then says the terminal cancelled it |
| Steps 6, 16 | **Port call events** |
| Steps 5, 17 | **My vessel**: the calculator's *Ship's size* fields travel with the estimate; length and beam are asked for the first time a ship is sent, and filled in after that. On **Harbour**, a ship due within a day with no draft shows "Draft due", and its drawer shows the size |
| Step 18 | **Harbour**: the *Berth maintenance* panel schedules and removes windows; the berth plan draws them hatched, and a berth under maintenance now says "maint. to …" |
| Step 19 | **Stories**: *A ship falls behind*. The ledger in the visit drawer gives the reason |
| Step 20 | **My vessel**: *Omit this call* and *Propose another time* are not offered once the ship is alongside, and *Report departure* only then; a refusal from the scheduler appears beside the button |
| Step 21 | **Harbour**: a ship due more than 7 days ahead is listed as in line with no berth; its visit's ledger says "A berth is given within 7 days of arrival". The *Ships booked* figure counts the others |
| Step 22 | **Harbour**: a ship past its planned end shows "Overstaying, … past planned end"; in its visit, *Expected to leave at* sets the time, above *Release* |
| Step 23 | **My vessel**: a ship booked behind another sees "B2 is booked for DALE first, from …" where *Report arrival* would be, until that ship has left |
| Step 24 | **My vessel**: *Report arrival at the anchorage*; then "At the anchorage, …" and the new requested time to accept. **Harbour** shows the same chip |
| Steps 8 to 14 and 19, played for you | **Stories**, the bar at the bottom: *The port fills up*, *A quiet port*, *A change of plan*, *A ship goes quiet*, *A ship falls behind* |

---

## 10. The automated tests

```powershell
cd port-scheduler-api
.\gradlew test          # 159 tests in thirteen classes; needs MySQL, not FastAPI
```

| Test class | What it proves |
|---|---|
| `PortCallEventsControllerTest` | The feed's JSON, headers, paging and error format against the standard |
| `PortCallEventReceiverTest` | Each incoming event: a repeat applied once, one overtaken by a later event not applied, estimate, position, accept, stale accept, arrival, departure, omit, the anchorage |
| `PortCallEventPublisherTest` | What is published, the filters, the cursor, and the two flags for a call that did not take place |
| `VesselOperatorControllerTest` | The calculator's answer, with and without an open call, and that it stores nothing |
| `ConsoleControllerTest` | The JSON of every read, of release and of cancel, with their `404`, `409` and `400` answers |
| `PortViewServiceTest`, `BerthSchedulingTest` | The views and the scheduling rules through real MySQL: booked ships' times refreshed, the answer and the estimate that are due, the terminal's cancellation, berth fit, maintenance, a booked ship that falls behind, overstays |
| `BerthSequenceTest` | Several ships per berth: the gap before a booking, a booking that moves to a berth much sooner, maintenance over a booked ship, an arrival at an occupied berth or before the ship's turn, a ship at anchor brought in sooner |
| `BerthTurnaroundTest` | The hour between ships, with the turnaround set as in production |
| `VesselSizeTest` | Length and beam required once per ship, kept for later calls; the largest draft kept and used; the draft due a day before arrival |
| `PlanningRulesTest`, `SchemaMigrationTest` | The stay and speed arithmetic; the migrations and the database's own rules |

```powershell
cd port-console
npm test                # 92 tests; nothing else needs to be running
npm run lint
```

The Java tests use their own schema, `port_core_test`, which they wipe and
rebuild. They never touch `port_core`.

---

## 11. Reset and shut down

**Close every open visit** (the console's *Reset the port* does the same): omit
every ship not yet alongside, booked or in line, then release the berths with a
ship alongside. Each berth released this way is free for the next ship an hour
later, after the turnaround.

```bash
curl -s $API/api/visits | python -c "
import sys, json, urllib.request as u
api = '$API'
visits = json.load(sys.stdin)
alongside = lambda v: v['berth'] is not None and v['berth']['status'] == 'ACTIVE'
for v in visits:
    if not alongside(v):
        body = json.dumps({'events': [{'vessel': {'vesselName': v['vessel']['code']}, 'portCall': {'isOmitted': True}}]}).encode()
        u.urlopen(u.Request(api + '/api/port-call/v2/events', data=body, headers={'Content-Type': 'application/json'}, method='POST'))
for v in visits:
    if alongside(v):
        u.urlopen(u.Request(api + '/api/port/berths/' + v['berth']['berthCode'] + '/release', method='PUT'))
print('closed', len(visits))"
```

Closed visits stay in the database as history. To empty it completely, stop
the scheduler and run this in MySQL:

```sql
USE port_core;
DELETE FROM visit_event; DELETE FROM eta_prediction;
DELETE FROM berth_allocation; DELETE FROM vessel_visit; DELETE FROM vessel;
DELETE FROM berth_maintenance;
```

**Shut down:** press Ctrl+C in each of the three terminals.

---

## 12. Where the service departs from the standard

It follows the standard's messages and codes. It is **not certified** and has
not been run through DCSA's conformance tests.

- **Only the berth and anchorage services are acted on.** Pilotage, towage,
  cargo operations, the pilot boarding place and moves forecasts are answered
  "not processed". Of the anchorage, only actual times are taken.
- **A ship at anchor is taken to be ready an hour after it anchored**, whatever
  its estimate said (`port.planning.anchorage-to-berth-minutes`, this
  service's own setting), and may be brought in sooner (step 24).
- **A ship's length and beam are required, once per ship**, where the
  standard leaves them optional: a call opens only for a ship the terminal
  knows the size of. A terminal operating system holds these as vessel master
  data; here they arrive with the first estimate and stay on the vessel
  record.
- **An actual arrival at the berth is refused before the ship's turn**
  (step 23). The standard records an actual arrival as the ship's report of a
  fact; this terminal refuses one at a berth booked for another ship first,
  since a ship berths when the terminal is ready for it. A ship that is early
  reports arrival at the anchorage instead (step 24).
- **One record is both the port call and the terminal call.** The standard
  allows several terminal calls within one port call.
- **No facility codes.** The demo terminal codes are invented, so no SMDG code
  is published.
- **`replyToEventID` is not set**, so a request is not linked to the estimate
  it answers.
- **`isFYI`, `isDeclined` and `delayReasonCodes` are ignored.** `isCanceled`
  is published for a call the terminal cancelled, but not read from an
  incoming event.
- **A call that is omitted or cancelled is flagged on its existing events, not
  announced by a new one.** A reader paging by cursor who has already read
  those events is not told.
- **There is no answer deadline in the messages.** The 60-minute window after
  which an unanswered request is flagged is this service's own rule, and it is
  not sent to the ship.
- **The terminal cannot ask for an estimate.** The standard has no such
  message. The service only works out when a fresh one is expected (72, 48
  and 24 hours before arrival) and flags it when none has come.
- **The lateness rule is tighter than DCSA's weekly stage.** DCSA replans when
  an agreed arrival slips by 12 hours or more, between 7 days and 48 hours
  before arrival; this service uses 4 hours throughout.
- **A booking horizon of 7 days is this service's own rule.** DCSA's weekly
  berth planning starts 7 days out, so a ship due later keeps its place but is
  given no berth until then.
- **No authentication and no subscriber registration**, both of which the
  standard leaves to the implementer.
- **The terminal does not push.** It publishes on `GET`; it does not `POST`
  its requests to a carrier's endpoint.

---

## 13. Endpoint reference

| Method and address | Controller | Changes anything? | Step |
|---|---|---|---|
| `POST /api/port-call/v2/events` | `PortCallEventsController` | Yes: the only way a vessel's side opens, changes or ends a call | 4, 7 to 12, 14, 17, 19 to 22 |
| `GET /api/port-call/v2/events` | `PortCallEventsController` | No | 6, 13, 16 |
| `POST /api/operator/eta` | `VesselOperatorController` | No | 3, 9 |
| `GET /api/port` | `ConsoleController` | No | 2 |
| `GET /api/port/berth-plan` | `ConsoleController` | No | 15, 18 |
| `GET /api/visits` (`?vessel=`, `?state=all`) | `ConsoleController` | No | 2, 5, 10, 13, 15 |
| `GET /api/visits/{visitRef}` | `ConsoleController` | No | 15 |
| `PUT /api/port/berths/{berthCode}/release` | `ConsoleController` | Yes: the planner frees the berth of a ship alongside that has left; bookings are left as they are | 12 |
| `PUT /api/visits/{visitRef}/cancel` | `ConsoleController` | Yes: the planner cancels the call of a ship that has not arrived | 13 |
| `PUT /api/visits/{visitRef}/expected-completion` | `ConsoleController` | Yes: the planner sets when a ship alongside is expected to leave | 22 |
| `GET /api/port/maintenance` | `ConsoleController` | No | 18 |
| `POST /api/port/berths/{berthCode}/maintenance` | `ConsoleController` | Yes: the planner records maintenance; booked ships it overlaps are planned again around it | 18 |
| `DELETE /api/port/berths/{berthCode}/maintenance/{id}` | `ConsoleController` | Yes: the window is removed and the plan is worked out again | 18 |
| `GET /api/agent/ping`, `POST /api/agent/chat`, `POST /api/agent/evaluate` | `AgentController` | No (sample data) | section 8 |
| `GET /health`, `POST /predict` on port 8000 | FastAPI, not Spring | No | 1 |

**Routes that no longer exist, and what replaced them**

| Removed | Use instead |
|---|---|
| `POST /api/ships/schedule` | `POST /api/operator/eta` to calculate, then an `EST` event to open the call (steps 3 and 4) |
| `GET /api/ships/berths` | `GET /api/port` and `GET /api/visits` (step 2) |
| `PUT /api/ships/berths/{id}/release` | `PUT /api/port/berths/{berthCode}/release` (step 12) |
| `POST /api/operator/vessels/{id}/accept` | A `PLN` event (step 7) |
| `POST /api/operator/vessels/{id}/reject` | An `EST` event with another time (step 11) |
| `POST /api/operator/vessels/{id}/omit` | An omitted port call (step 14) |

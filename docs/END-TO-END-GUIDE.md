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
 ACT  arrival                      ───────▶   vessel alongside
 ACT  departure                    ───────▶   berth free, next vessel promoted
```

Three things the terminal does without being asked:

- **It keeps waiting ships' times current.** Whenever the berth plan changes (a
  ship arrives early or late, a berth is released, a call is omitted or
  cancelled, a ship sends a new estimate), it works out again when each
  waiting ship can berth, and sends a new `REQ` to every ship whose time moves
  by 15 minutes or more.
- **It flags an answer that is late.** A ship is expected to accept a request
  within 60 minutes. After that the planner's screen says so. The terminal
  does not take the berth away for silence: the planner decides (step 13).
- **It assigns a berth again when a booked ship falls far behind.** If a ship
  holding a berth sends an estimate more than 4 hours after the time it
  agreed, and a waiting ship can be alongside sooner, the berth goes to that
  ship (step 19).

It also gives a ship only a berth it fits (step 17) and plans around berth
maintenance (step 18). The 15 minutes, 60 minutes and 4 hours are this
project's own choices, set in `application.properties`.

### 2.3 Standard element by element

| In the standard | In this service |
|---|---|
| `POST /events` (subscriber endpoint) and `GET /events` (publisher endpoint) | Both implemented, at `/api/port-call/v2/events` |
| `Event` with `portCall`, `terminalCall`, `portCallService`, `vessel`, `timestamp` | Same names and nesting. `eventID`s are stable UUIDs |
| `timestamp.classifierCode` `EST` / `REQ` / `PLN` / `ACT`, `serviceDateTime` | Same. Times are UTC |
| `portCallServiceTypeCode` `BERTH`, event type `ARRI` / `DEPA`, phase `INBD` / `OUTB`, facility `BRTH` | Same codes. Only the `BERTH` service is acted on; others are answered "not processed" |
| `vessel.vesselIMONumber`, `vesselMMSINumber`, `vesselName`, `vesselTypeCode` | A vessel is matched by IMO number, then MMSI, then name |
| `vessel.vesselPosition`, `vessel.milesToDestinationPort` (optional) | Read from an `EST`. This is how the terminal learns where a ship is |
| `vessel.lengthOverall`, `width` in `vesselSizeUnit`; `draft` in `draftUnit` (optional) | Read from an `EST`, in metres or feet. A ship is given only a berth it fits, and refused if it fits none (step 17) |
| `portCall.isOmitted` / `terminalCall.isOmitted` ("omitted by the carrier") | Read: it cancels the call. Published on every event of such a call |
| `portCallService.isCanceled` ("canceled by the service provider") | Published on every event of a call the terminal cancelled (step 13). Not read: here the terminal is the provider |
| `feedbackElements` with `severity`, `message`, `propertyPath` | Same. A refused event is reported by its position, `$.events[0]` |
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

### Step 1 — The model service on its own

```bash
curl -s $MODEL/health
```
```json
{"status":"ok","model":"eta_xgb.pkl","features":["distance_to_dest_nm","sog","cog","bearing_to_dest_deg","lat","lon","naive_eta_min"]}
```

```bash
curl -s -X POST $MODEL/predict -H "$J" \
  -d '{"lat":61.62,"lon":4.40,"sog":13.0,"cog":92.0,"dest_lat":61.612,"dest_lon":5.07}'
```
```json
{"eta_minutes":226.9,"eta_hours":3.78,"distance_to_dest_nm":19.13,"bearing_to_dest_deg":91.1,"naive_eta_min":88.3}
```

**What to notice.** A ship 19 nm west of Florø at 13 knots: the model says 227
minutes, plain distance over speed says 88. The model's figure runs late close
to port; the README's "Known limits" explains why. Nothing outside the
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
{"vesselId":"NORDVIK","etaMinutes":226.9,"estimatedArrival":"2026-10-14T10:16:54.324115900Z","distanceNm":19.13,
 "naiveEtaMinutes":88.3,"requestedArrival":null,"advisedSpeedKnots":null}
```

**What to notice.** The same 226.9 minutes as step 1, now as a clock time.
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

The operator sends the estimate to the terminal, with the ship's position:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{
    "vesselName":"NORDVIK",
    "vesselTypeCode":"GCGO",
    "vesselPosition":{"geoCoordinate":{"latitude":"61.62","longitude":"4.40"}},
    "milesToDestinationPort":$MILES
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
nothing to report. Behind it the terminal opened a visit, allocated the first
free berth, and recorded its own answer, a requested arrival.

**DCSA:** this is the standard's `POST /events`. An `EST` for service `BERTH`,
event `ARRI`, is "estimated time of arrival at berth", sent by the vessel
operator. `vesselPosition` and `milesToDestinationPort` are the standard's own
optional fields; the coordinates are text, as it specifies. `GCGO` is its code
for general cargo.

### Step 5 — See where the ship stands (`ConsoleController`)

```bash
curl -s "$API/api/visits?vessel=NORDVIK"
```
```json
[{"visitRef":"VV-20261014-85DEF72E",
  "vessel":{"code":"NORDVIK","name":"NORDVIK","imoNumber":null,"mmsi":null,"type":"GENERAL_CARGO","loaMeters":null,"beamMeters":null},
  "phase":"INBOUND","terminalCode":"FRO-CT",
  "berth":{"berthCode":"B1","berthName":"Berth 1","status":"PLANNED",
           "plannedStart":"2026-10-14T10:16:54.324116Z","plannedEnd":"2026-10-15T09:33:54.324116Z","actualStart":null,"actualEnd":null},
  "queueNumber":null,
  "etaBerth":"2026-10-14T10:16:54.324116Z","rtaBerth":"2026-10-14T10:16:54.324116Z","ptaBerth":null,"ataBerth":null,"atdBerth":null,
  "plannedServiceMinutes":1397,
  "lastReport":{"reportedAt":"2026-10-14T06:30:01.204146Z","lat":61.62,"lon":4.4,"distanceNm":19.13,"estimatedArrival":"2026-10-14T10:16:54.324116Z"},
  "requestedAt":"2026-10-14T06:30:01.303417Z","answerDueAt":"2026-10-14T07:30:01.303417Z","cancelledBy":null,"remarks":null,
  "estimateDueSince":null,"nextEstimateBy":null,"notArrivedSince":null,"arrivalDraftMeters":null}]
```

**What to notice.**

- `berth` is `B1` with status `PLANNED`: reserved, the ship is not alongside.
  **The berth is held from this moment**, not from the moment the ship
  accepts. Another ship reporting in now gets `B2`; it cannot take `B1`.
- `rtaBerth` equals `etaBerth`. The berth was free, so the terminal asks the
  ship to come at its own estimate.
- `ptaBerth` is empty: the ship has not accepted yet.
- `requestedAt` is when the terminal issued that request, and `answerDueAt` is
  60 minutes later: when the ship's answer is expected by.
- The last four fields are empty for now. `estimateDueSince` and
  `nextEstimateBy` say when the terminal expects a fresh estimate: ports ask
  for one 72, 48 and 24 hours before arrival, and NORDVIK is closer than that.
  `notArrivedSince` is set once a ship holding a berth is past its agreed time
  without arriving. `arrivalDraftMeters` and the vessel's `loaMeters` and
  `beamMeters` are its size, when it gives one (step 17).
- `plannedEnd` is 1,397 minutes (0.97 days) after the start: the typical stay
  for this vessel type, from published UNCTAD medians.
- `lastReport` is the position sent in step 4.

A visit has a `berth` or a `queueNumber`, never both.

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
  "eventID":"bd21cbee-66d3-3f71-8f79-40594585aaf0",
  "eventUpdatedDateTime":"2026-10-14T06:30:01.303417Z",
  "portCall":{"portCallID":"645c35f1-89b2-39e5-9a93-d5c831f005ea","portVisitReference":"VV-20261014-85DEF72E","UNLocationCode":"NOFRO"},
  "terminalCall":{"terminalCallID":"8c7bb367-0f4d-3b09-acf9-8024e4386902","sequenceNumber":1},
  "portCallService":{"portCallServiceID":"1bbaa9dc-06c0-3243-9c98-0937136e9b07",
    "portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","portCallPhaseTypeCode":"INBD","facilityTypeCode":"BRTH",
    "serviceLocation":{"locationName":"Berth 1","geoCoordinate":{"latitude":"61.612","longitude":"5.07"},"UNLocationCode":"NOFRO"}},
  "vessel":{"vesselName":"NORDVIK","vesselTypeCode":"GCGO"},
  "timestamp":{"classifierCode":"REQ","serviceDateTime":"2026-10-14T10:16:54.324116Z"}}]}
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
  "timestamp":{"classifierCode":"PLN","serviceDateTime":"2026-10-14T15:00:00Z"}
}]}
EOF
```
```json
{"feedbackElements":[{"severity":"ERROR","message":"The requested arrival is now 2026-10-14T10:16:54.324116Z; confirm that time","propertyPath":"$.events[0]"}]}
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
  "vessel":{"vesselName":"$1","vesselTypeCode":"GCGO"},
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
| `B1` | NORDVIK | `2026-10-15T09:33:54Z` |
| `B2` | SELJE | `2026-10-15T07:47:03Z` |
| `B3` | ULSTEIN | `2026-10-15T08:47:03Z` |
| `B4` | VARTDAL | `2026-10-15T09:47:03Z` |

### Step 9 — A fifth ship: the queue, a later request, and the speed

HAREID is 60 nm to the north at 14 knots. Its operator calculates, then sends:

```bash
Q='{"vesselId":"HAREID","lat":62.60,"lon":5.40,"sog":14.0,"cog":190.0,"destLat":61.612,"destLon":5.07,"vesselType":"GENERAL_CARGO"}'
ANSWER=$(curl -s -X POST $API/api/operator/eta -H "$J" -d "$Q"); echo "$ANSWER"
ETA=$(echo "$ANSWER" | pick "d['estimatedArrival']"); MILES=$(echo "$ANSWER" | pick "d['distanceNm']")
```
```json
{"vesselId":"HAREID","etaMinutes":501.9,"estimatedArrival":"2026-10-14T14:51:57.967784300Z","distanceNm":60.04,
 "naiveEtaMinutes":257.3,"requestedArrival":null,"advisedSpeedKnots":null}
```

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{
  "vessel":{"vesselName":"HAREID","vesselTypeCode":"GCGO",
            "vesselPosition":{"geoCoordinate":{"latitude":"62.60","longitude":"5.40"}},"milesToDestinationPort":$MILES},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"$ETA"}
}]}
EOF
curl -s "$API/api/visits?vessel=HAREID"
```
```json
[{"visitRef":"VV-20261014-D1016C85", ... "phase":"INBOUND","terminalCode":null,
  "berth":null,"queueNumber":1,
  "etaBerth":"2026-10-14T14:51:57.967784Z","rtaBerth":"2026-10-15T07:47:03Z","ptaBerth":null, ...}]
```

**What to notice.**

- Every berth is taken, so `berth` is `null` and `queueNumber` is `1`.
- `rtaBerth` is **17 hours after** `etaBerth`. The terminal asks the ship to
  come when a berth is expected free (the earliest planned end among the four,
  which is SELJE's `B2`), not when the ship could get there.
- `nextEstimateBy` is `2026-10-14T07:47:03Z`, 24 hours before the requested
  arrival: the terminal expects a fresh estimate by then. If none has come by
  the time it passes, `estimateDueSince` says so and the console flags
  "Estimate due". The standard has no message for the terminal to ask; this
  only says when one is expected.

That gap is the reason just-in-time arrival exists. Sailing at 14 knots, the
ship would arrive and wait 17 hours. The terminal has said when; **how fast**
is the operator's sum. Ask the calculator again, now that the call is open:

```bash
curl -s -X POST $API/api/operator/eta -H "$J" -d "$Q"
```
```json
{"vesselId":"HAREID","etaMinutes":501.9,"estimatedArrival":"2026-10-14T14:51:59.759562300Z","distanceNm":60.04,
 "naiveEtaMinutes":257.3,"requestedArrival":"2026-10-15T07:47:03Z","advisedSpeedKnots":5.0}
```

**What to notice.** The same question as before, and a fuller answer:
`requestedArrival` is the terminal's time, and `advisedSpeedKnots` is the
speed that meets it. The rule is distance over time available, never below an
economical speed for the vessel type. Here 60 nm in 25 hours needs only 2.4
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

### Step 10 — Actual arrival, a waiting ship's time refreshed, and the queue moving

NORDVIK reports it is alongside. (The walk-through skips the sailing, so this
is almost four hours before its estimate.)

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
           "plannedStart":"2026-10-14T10:16:54.324116Z","plannedEnd":"2026-10-15T05:47:06Z","actualStart":"2026-10-14T06:30:06Z","actualEnd":null},
  "etaBerth":"2026-10-14T10:16:54.324116Z", ... "ataBerth":"2026-10-14T06:30:06Z", ...}]
```

**What to notice.** The visit is `BERTHED`, the berth `ACTIVE`, and `ataBerth`
is set. `plannedEnd` has moved from `09:33:54` to `05:47:06`: **a stay counts
from when the ship is really alongside**, not from its estimate.

That changes the berth plan, and one ship is waiting on it. Nobody sent
anything on HAREID's behalf, yet:

```bash
curl -s "$API/api/visits?vessel=HAREID"
```
```json
[{... "berth":null,"queueNumber":1,
  "etaBerth":"2026-10-14T14:51:57.967784Z","rtaBerth":"2026-10-15T05:47:06Z","ptaBerth":null, ...
  "requestedAt":"2026-10-14T06:30:06.477881Z","answerDueAt":"2026-10-14T07:30:06.477881Z", ...}]
```

- `rtaBerth` moved **two hours earlier**, from `07:47:03` to `05:47:06`: `B1`
  is now the first berth due free, ahead of SELJE's `B2`.
- `ptaBerth` is empty again, and a new `answerDueAt` has started: the time
  HAREID accepted in step 9 is no longer the time being asked for.

The terminal does this after every change to the plan, for every waiting ship,
and re-issues a request only when it moves by 15 minutes or more, so small
changes do not flood a ship with new times.

**DCSA:** "if there is a new requested time, the previous planned time is
cancelled."

A ship with no berth cannot report an arrival; HAREID trying gets:

```json
{"feedbackElements":[{"severity":"ERROR","message":"The vessel has no berth allocated yet","propertyPath":"$.events[0]"}]}
```

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
               "plannedStart":"2026-10-14T14:51:57.967784Z","plannedEnd":"2026-10-15T14:08:57.967784Z", ...},
  "queueNumber":null,
  "etaBerth":"2026-10-14T14:51:57.967784Z","rtaBerth":"2026-10-14T14:51:57.967784Z","ptaBerth":null, ...}]
```

**What to notice.**

- `B1` went straight to HAREID: a freed berth passes to the ship that has
  waited longest.
- `rtaBerth` moved again, **15 hours earlier**, to the ship's own estimate.
  There is no longer anything to wait for.

HAREID must accept the new request (repeat the `PLN` call of step 9 with the
new `rtaBerth`).

### Step 11 — Propose another time

HAREID is delayed by two hours. It does not refuse the request; it states a new
estimate, and the terminal answers:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"HAREID"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"2026-10-14T16:51:57Z"}}]}
EOF
curl -s "$API/api/visits?vessel=HAREID" | pick "{k: d[0][k] for k in ('etaBerth','rtaBerth','ptaBerth')}"
```
```
{'etaBerth': '2026-10-14T16:51:57Z', 'rtaBerth': '2026-10-14T16:51:57Z', 'ptaBerth': None}
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

Releasing a berth that is already free answers `{"released":false,"berthCode":"B2"}`.
A berth that does not exist answers `404`:

```json
{"statusCode":404,"message":"Berth not found: Berth ID B9 does not exist","timeStamp":1791959409351}
```

**What to notice.** The effect is the same as the ship's own `ACT` departure in
step 10: the visit is closed as departed and the berth passes to the head of
the queue. Release is for **a ship that was alongside**. For one that never
arrived, the next step is the right action.
**DCSA:** not part of the standard, where an actual departure is the vessel's
to send. This is the terminal's own override.

### Step 13 — The planner cancels a call that goes unanswered (`ConsoleController`)

VARTDAL was given `B4` in step 8 and has never accepted the requested arrival:

```bash
REF=$(curl -s "$API/api/visits?vessel=VARTDAL" | pick "d[0]['visitRef']")
curl -s "$API/api/visits?vessel=VARTDAL"
```
```json
[{"visitRef":"VV-20261014-E531F56A", ...
  "berth":{"berthCode":"B4","berthName":"Berth 4","status":"PLANNED",
           "plannedStart":"2026-10-14T10:30:03Z","plannedEnd":"2026-10-15T09:47:03Z", ...},
  "rtaBerth":"2026-10-14T10:30:03Z","ptaBerth":null, ...
  "requestedAt":"2026-10-14T06:30:03.693897Z","answerDueAt":"2026-10-14T07:30:03.693897Z", ...}]
```

The berth is safe for VARTDAL while it stays silent, and that is the problem:
it is held for a ship that may not be coming. Once `answerDueAt` has passed,
the console flags the visit ("No answer for 1 h 20 min"). **The terminal does
not act on that by itself.** The planner decides, and can cancel the call at
any time, overdue or not:

```bash
curl -s -X PUT $API/api/visits/$REF/cancel -H "$J" -d '{"reason":"no answer to the requested arrival"}'
```
```json
{"cancelled":true,"visitRef":"VV-20261014-E531F56A"}
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
  "eventID":"ac29522a-833b-33c7-afe1-8504f23e6131",
  "eventUpdatedDateTime":"2026-10-14T06:30:03.693897Z",
  "portCall":{"portCallID":"b8291002-797f-3a1f-b292-38bd8c09c7b3","portVisitReference":"VV-20261014-E531F56A","UNLocationCode":"NOFRO"},
  "terminalCall":{"terminalCallID":"a7db5b83-3bf8-32c2-a47c-b49d2cde8027","sequenceNumber":1},
  "portCallService":{"portCallServiceID":"ad135308-00e5-36e1-a99c-2e47118fc571",
    "portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","portCallPhaseTypeCode":"INBD","facilityTypeCode":"BRTH",
    "serviceLocation":{"locationName":"Berth 4","geoCoordinate":{"latitude":"61.61","longitude":"5.071"},"UNLocationCode":"NOFRO"},
    "isCanceled":true},
  "vessel":{"vesselName":"VARTDAL","vesselTypeCode":"GCGO"},
  "timestamp":{"classifierCode":"REQ","serviceDateTime":"2026-10-14T10:30:03Z"}}]}
```

**What to notice.**

- `B4` is free. Had ships been waiting, it would have gone to the first of
  them, and the rest would have been given new times as in step 10. The story
  *A ship goes quiet* in the console shows exactly that.
- VARTDAL may announce itself again with a new `EST`. That opens a new visit,
  behind any ship already waiting.
- A call can be cancelled only while its ship has not arrived. Cancelling it
  twice, or cancelling a ship alongside, answers `409`; an unknown reference
  answers `404`:

```json
{"statusCode":409,"message":"Conflict: Visit VV-20261014-E531F56A is already over","timeStamp":1791959410349}
{"statusCode":409,"message":"Conflict: The vessel is alongside; release its berth when it leaves","timeStamp":1791959409090}
{"statusCode":404,"message":"Visit not found: No visit has the reference VV-NOT-THERE","timeStamp":1791959410424}
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
 ESTIMATED  2026-10-14T14:51:57Z  by VESSEL                the first estimate
 REQUESTED  2026-10-15T07:47:03Z  by TERMINAL              "Berth expected free 2026-10-15T07:47:03Z"
 PLANNED    2026-10-15T07:47:03Z  by VESSEL                accepted
 REQUESTED  2026-10-15T05:47:06Z  by TERMINAL              "Berth expected free 2026-10-15T05:47:06Z": NORDVIK berthed early
 REQUESTED  2026-10-14T14:51:57Z  by TERMINAL  berth B1    promoted when NORDVIK left
 PLANNED    2026-10-14T14:51:57Z  by VESSEL                accepted again
 ESTIMATED  2026-10-14T16:51:57Z  by VESSEL                delayed two hours
 REQUESTED  2026-10-14T16:51:57Z  by TERMINAL  berth B1    the terminal follows
```

Each entry also carries `recordedAt`: when it was said, as distinct from the
time it is about.

```bash
curl -s $API/api/port/berth-plan
```

One lane per berth, each with its windows: every one still open, and completed
ones that overlap the period. Here `B1` has NORDVIK's completed window and
HAREID's planned one, `B2` has SELJE's completed window, and `B3` and `B4` are
empty: a cancelled window never occupied its berth and is left out. Without
`from` and `to` the period is 12 hours back to 48 hours ahead; name your own
with `?from=2026-10-13T00:00:00Z&to=2026-10-15T00:00:00Z`.

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
Next-Page-Cursor: 368
```

`Next-Page-Cursor` appears only when more events remain. It is the service's
own marker, so yours will be a different value. Send it back unchanged as
`cursor`, **with the same filters**, for the next page:

```bash
curl -s "$API/api/port-call/v2/events?limit=3&cursor=368"
```

The ten filters this publisher supports:

| Filter | Example |
|---|---|
| `classifierCode` | `?classifierCode=PLN` (every acceptance) |
| `vesselName`, `vesselIMONumber`, `vesselMMSINumber` | `?vesselName=HAREID` |
| `portCallServiceTypeCode` | `?portCallServiceTypeCode=BERTH` |
| `UNLocationCode` | `?UNLocationCode=NOFRO` |
| `eventTimestampMin`, `eventTimestampMax` | `?eventTimestampMin=2026-10-13T00:00:00Z` |
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
`draftUnit`, either metres (`MTR`) or feet (`FOT`). The terminal gives a ship
only a berth it fits. Here `B1` and `B2` take 190 m, a 32 m beam and a 10.5 m
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

Now three ships. ALDA gives no size and takes `B2`, the first free berth.
LANGE is 180 m long with a 9 m draft, due in an hour; KORT is 120 m, also due
in an hour, and reports after LANGE. (Send each as above, with its own name,
time and size.)

```bash
curl -s "$API/api/visits?vessel=LANGE"
```
```json
[{"visitRef":"VV-20261014-4FB739E9",
  "vessel":{"code":"LANGE","name":"LANGE","imoNumber":null,"mmsi":null,"type":"GENERAL_CARGO","loaMeters":180.00,"beamMeters":28.00},
  "phase":"INBOUND","terminalCode":null,"berth":null,"queueNumber":1,
  "etaBerth":"2026-10-14T07:30:13Z","rtaBerth":"2026-10-15T07:47:13Z", ...
  "arrivalDraftMeters":9.00}]
```

| Ship | Size | Got |
|---|---|---|
| ALDA | not given | `B2` |
| LANGE | 180 m, draft 9 m | waiting, 1st in line, asked for `2026-10-15T07:47:13Z`, when `B2` comes free |
| KORT | 120 m, draft 6.5 m | `B3` |

**What to notice.** `B3` and `B4` were free when LANGE reported, but take
140 m at most, so LANGE waits for one of the large berths. KORT reported
after LANGE, yet took `B3` at once: a free berth that the ship ahead in line
cannot use goes to the first ship that can. A ship of unknown size, like ALDA,
is assumed to fit; nothing can be checked against a size the terminal has not
been told.

**DCSA:** the size fields are the standard's own, optional, on the vessel of
any event. A unit other than `MTR` or `FOT` is refused with `ERROR`
("vesselSizeUnit must be MTR or FOT").

### Step 18 — Maintenance on a berth (`ConsoleController`)

Maintenance is known in advance and recorded by the planner. No ship is given
a berth for a stay that would run into a window.

A window over a ship's booking is refused. ALDA holds `B2` from 08:30:

```bash
curl -s -X POST $API/api/port/berths/B2/maintenance -H "$J" \
  -d "{\"from\":\"$(date -u -d '+3 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"to\":\"$(date -u -d '+6 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"reason\":\"Fender repair\"}"
```
```json
{"statusCode":409,"message":"Conflict: B2 is booked for ALDA from 2026-10-14T08:30:13Z to 2026-10-15T07:47:13Z; maintenance cannot overlap a booking","timeStamp":1791959414671}
```

`B4` is free. A window there, from an hour from now for 29 hours, is accepted
with `201`:

```bash
curl -s -X POST $API/api/port/berths/B4/maintenance -H "$J" \
  -d "{\"from\":\"$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)\",\"to\":\"$(date -u -d '+30 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"reason\":\"Crane rail\"}"
```
```json
{"id":7,"berthCode":"B4","berthName":"Berth 4","from":"2026-10-14T07:30:14Z","to":"2026-10-15T12:30:14Z","reason":"Crane rail"}
```

A second window on `B4` that overlaps it answers `409` ("B4 already has
maintenance from … to …"); one that ends before it starts, or is already over,
answers `400`.

Now SMAA, 100 m, due in two hours, reports. `B4` is free, but a stay of almost
a day would run into the window:

```json
{'queueNumber': 2, 'etaBerth': '2026-10-14T08:30:15Z', 'rtaBerth': '2026-10-15T06:47:13Z'} | berth None
```

SMAA waits, 2nd in line behind LANGE, and is asked for when `B3` comes free.
The windows are listed by `GET /api/port/maintenance`, and each berth's lane in
`GET /api/port/berth-plan` carries its own under `maintenance`:

```json
[{"id":7,"berthCode":"B4","berthName":"Berth 4","from":"2026-10-14T07:30:14Z","to":"2026-10-15T12:30:14Z","reason":"Crane rail"}]
```

Remove the window, and the waiting ships are planned again without it:

```bash
curl -s -X DELETE $API/api/port/berths/B4/maintenance/7
```
```json
{"id":7,"removed":true}
```

SMAA is now booked on `B4` at its own estimate, `2026-10-14T08:30:15Z`. An id
that does not exist on that berth answers `404`.

**DCSA:** not part of the standard, which does not describe a terminal's
berths. The ships see only the result: requested arrivals that already allow
for maintenance.

### Step 19 — A booked ship falls behind

ALDA accepts `B2` for `2026-10-14T08:30:13Z` (a `PLN`, as in step 7). Then it
sends a new estimate six hours later:

```bash
curl -s -X POST $API/api/port-call/v2/events -H "$J" --data @- <<EOF
{"events":[{"vessel":{"vesselName":"ALDA"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"2026-10-14T14:30:13Z"}}]}
EOF
```
```json
{}
```

| Ship | Before | After |
|---|---|---|
| ALDA | `B2`, agreed for 08:30:13 | waiting, 1st in line, asked for `2026-10-15T06:47:13Z` |
| LANGE | waiting, 1st in line | `B2`, asked for its own estimate, `2026-10-14T07:30:13Z` |

And in the ledger:

```
 ALDA   REQ  2026-10-15T06:47:13Z  "Berth B2 withdrawn: this estimate is 6 h after the agreed arrival,
                                    and a waiting vessel can be alongside sooner. Berth expected free 2026-10-15T06:47:13Z"
 LANGE  REQ  2026-10-14T07:30:13Z  B2  "Given up by ALDA, now expected 6 h after its agreed arrival"
```

**What to notice.**

- A berth is held from the terminal's answer. It is **assigned again** when
  the ship holding it sends an estimate more than the tolerance (4 hours)
  after its agreed arrival **and** a waiting ship that fits can be alongside
  sooner. Both have to be true: with nobody waiting, ALDA would have kept
  `B2` at its new time.
- ALDA keeps its place in line. Being late costs it this berth, not its turn.
  The same rule applies to waiting ships: a berth goes to the first ship in
  line that can be alongside within 4 hours of the soonest any waiting ship
  can.
- A smaller delay changes nothing but the time. KORT accepted `B3` for
  07:30:13, then sent 09:30:13, two hours later: it keeps `B3`, the terminal
  asks for 09:30:13, and KORT must accept that new time.

**DCSA:** DCSA's weekly berth planning process (7 days to 48 hours before
arrival) asks "Is PTA Berth delayed by 12H?" and, if so, sends both sides
back to estimate and request again. This service applies the same rule with
its own tolerance, 4 hours, because its ships report within the last day or
two. Lateness is measured against the time the ship agreed, as there.

## 7. What the service refuses, and how

Worth running: a system is as clear in its refusals as in its answers.

| Send | Answer |
|---|---|
| `GET /api/port-call/v2/events?carrierServiceCode=FE1` (a filter it does not support) | `400`, `feedbackElements` naming the ten it does |
| `POST` events for a non-berth service, e.g. `"portCallServiceTypeCode":"PILOTAGE"` | `200`, `WARN`: `Not processed: this terminal acts on BERTH events only` |
| `POST` a `REQ` (the terminal's own kind of time) | `200`, `ERROR`: `A requested time at berth is issued by the terminal, not sent to it` |
| `POST` an estimated or planned **departure** | `200`, `WARN`: it is addressed to the port authority |
| `POST` a `PLN`, `ACT` or omit for a ship with no open call | `200`, `ERROR`: `Vessel X has no open visit` |
| `POST` a position that cannot be read, e.g. `"latitude":"north"` | `200`, `ERROR` for that event |
| `POST` `{"events":[]}` or a body that is not JSON | `400`, `feedbackElements` |
| `GET /api/visits/VV-NOT-THERE` | `404`, `{"statusCode":404,"message":"Visit not found: ..."}` |
| `GET /api/visits?state=closed` (only `open` and `all` exist) | `400` |
| `PUT /api/visits/{visitRef}/cancel` for a ship alongside, or a visit already over | `409`, saying which |
| `PUT /api/visits/VV-NOT-THERE/cancel` | `404` |
| `POST` an estimate for a ship that fits no berth | `200`, `ERROR`: `No berth here takes …`, with its size and the largest berth's limits. No call is opened |
| `POST` a size in a unit other than `MTR` or `FOT` | `200`, `ERROR`: `vesselSizeUnit must be MTR or FOT` |
| `POST /api/port/berths/{code}/maintenance` over the berth's booking, or over another window | `409`, saying which |
| `POST /api/port/berths/{code}/maintenance` ending before it starts, already over, or without `from` or `to` | `400` |
| `DELETE /api/port/berths/{code}/maintenance/{id}` with an id that berth does not have | `404` |
| `PUT /api/visits/{visitRef}/cancel` with a `reason` longer than 400 characters | `400` |
| `GET /api/port/berth-plan` with `from` after `to`, or a malformed time | `400` |
| `POST /api/operator/eta` with a negative speed or no `vesselId` | `400`, before the model is asked |
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
  {"severity":"WARN","message":"Not processed: this terminal acts on BERTH events only","propertyPath":"$.events[0]"},
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
| Step 10 | **My vessel**: *Report arrival*, *Report departure*. On **Harbour**, a waiting ship's requested time changes by itself |
| Step 12 | **Harbour**: open the visit of a ship alongside, *Release berth* |
| Step 13 | **Harbour**: a late answer shows as "No answer for ..." in the arrivals table and in the *Awaiting the ship's answer* figure. Open the visit of a ship that has not arrived, *Cancel this call*. The ship's own page then says the terminal cancelled it |
| Steps 6, 16 | **Port call events** |
| Step 17 | **My vessel**: the calculator's optional *Ship's size* fields travel with the estimate. A visit's drawer shows the size, or "size unknown" |
| Step 18 | **Harbour**: the *Berth maintenance* panel schedules and removes windows; the berth plan draws them hatched, and a berth under maintenance now says "maint. to …" |
| Step 19 | **Stories**: *A ship falls behind*. The ledger in the visit drawer gives the reason |
| Steps 8 to 14 and 19, played for you | **Stories**, the bar at the bottom: *The port fills up*, *A quiet port*, *A change of plan*, *A ship goes quiet*, *A ship falls behind* |

---

## 10. The automated tests

```powershell
cd port-scheduler-api
.\gradlew test          # 101 tests in nine classes; needs MySQL, not FastAPI
```

| Test class | What it proves |
|---|---|
| `PortCallEventsControllerTest` | The feed's JSON, headers, paging and error format against the standard |
| `PortCallEventReceiverTest` | Each incoming event: estimate, position, accept, stale accept, arrival, departure, omit |
| `PortCallEventPublisherTest` | What is published, the filters, the cursor, and the two flags for a call that did not take place |
| `VesselOperatorControllerTest` | The calculator's answer, with and without an open call, and that it stores nothing |
| `ConsoleControllerTest` | The JSON of every read, of release and of cancel, with their `404`, `409` and `400` answers |
| `PortViewServiceTest`, `BerthSchedulingTest` | The views and the scheduling rules through real MySQL: waiting ships' times refreshed, the answer and the estimate that are due, the terminal's cancellation, berth fit, maintenance, a booked ship that falls behind |
| `PlanningRulesTest`, `SchemaMigrationTest` | The stay and speed arithmetic; the migrations and the database's own rules |

```powershell
cd port-console
npm test                # 69 tests; nothing else needs to be running
npm run lint
```

The Java tests use their own schema, `port_core_test`, which they wipe and
rebuild. They never touch `port_core`.

---

## 11. Reset and shut down

**Close every open visit** (the console's *Reset the port* does the same): omit
the ships that are waiting, then release the berths.

```bash
curl -s $API/api/visits | python -c "
import sys, json, urllib.request as u
api = '$API'
visits = json.load(sys.stdin)
for v in visits:
    if not v['berth']:
        body = json.dumps({'events': [{'vessel': {'vesselName': v['vessel']['code']}, 'portCall': {'isOmitted': True}}]}).encode()
        u.urlopen(u.Request(api + '/api/port-call/v2/events', data=body, headers={'Content-Type': 'application/json'}, method='POST'))
for v in visits:
    if v['berth']:
        u.urlopen(u.Request(api + '/api/port/berths/' + v['berth']['berthCode'] + '/release', method='PUT'))
print('closed', len(visits))"
```

Closed visits stay in the database as history. To empty it completely, stop
the scheduler and run this in MySQL:

```sql
USE port_core;
DELETE FROM visit_event; DELETE FROM eta_prediction;
DELETE FROM berth_allocation; DELETE FROM vessel_visit; DELETE FROM vessel;
```

**Shut down:** press Ctrl+C in each of the three terminals.

---

## 12. Where the service departs from the standard

It follows the standard's messages and codes. It is **not certified** and has
not been run through DCSA's conformance tests.

- **Only the berth service is acted on.** Pilotage, towage, cargo operations
  and moves forecasts are answered "not processed".
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
- **An actual departure is timed by the service's clock**, not by the time in
  the event.
- **No authentication and no subscriber registration**, both of which the
  standard leaves to the implementer.
- **The terminal does not push.** It publishes on `GET`; it does not `POST`
  its requests to a carrier's endpoint.

---

## 13. Endpoint reference

| Method and address | Controller | Changes anything? | Step |
|---|---|---|---|
| `POST /api/port-call/v2/events` | `PortCallEventsController` | Yes: the only way a vessel's side opens, changes or ends a call | 4, 7 to 12, 14, 17, 19 |
| `GET /api/port-call/v2/events` | `PortCallEventsController` | No | 6, 13, 16 |
| `POST /api/operator/eta` | `VesselOperatorController` | No | 3, 9 |
| `GET /api/port` | `ConsoleController` | No | 2 |
| `GET /api/port/berth-plan` | `ConsoleController` | No | 15, 18 |
| `GET /api/visits` (`?vessel=`, `?state=all`) | `ConsoleController` | No | 2, 5, 10, 13, 15 |
| `GET /api/visits/{visitRef}` | `ConsoleController` | No | 15 |
| `PUT /api/port/berths/{berthCode}/release` | `ConsoleController` | Yes: the planner frees the berth of a ship that has left | 12 |
| `PUT /api/visits/{visitRef}/cancel` | `ConsoleController` | Yes: the planner cancels the call of a ship that has not arrived | 13 |
| `GET /api/port/maintenance` | `ConsoleController` | No | 18 |
| `POST /api/port/berths/{berthCode}/maintenance` | `ConsoleController` | Yes: the planner records maintenance; waiting ships are planned around it | 18 |
| `DELETE /api/port/berths/{berthCode}/maintenance/{id}` | `ConsoleController` | Yes: the window is removed and waiting ships are planned again | 18 |
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

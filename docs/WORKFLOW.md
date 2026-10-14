# Project Workflow — End-to-End Runbook

> **What this doc is:** the single, ordered checklist for taking this project from
> a fresh machine all the way to a running, tested two-service system. It covers
> **(1)** environment setup, **(2)** data → features, **(3)** training & testing
> the model, **(4)** starting both applications, and **(5)** testing the live
> system. Every step has the exact command, what it produces, and how to confirm
> it worked.
>
> For *conceptual* explanations see `fastapi-service-explained.md` and
> `spring-boot-service-explained.md`. This doc is the **operational** companion —
> just the steps.

---

## The big picture

```
 PHASE 0  Setup        ─►  Python venv + JDK 21 + MySQL + secrets file ready
 PHASE 1  Data         ─►  raw AIS parquet  →  feature_engineer.py  →  voyage_features.parquet
 PHASE 2  Train        ─►  train.py  →  models/eta_xgb.pkl   (test MAE ~69 min)
 PHASE 3  Serve model  ─►  predict_api.py (FastAPI)  on :8000
 PHASE 4  Serve API    ─►  port-scheduler-api (Spring Boot)  on :8080
 PHASE 5  Test live    ─►  curl the full chain  →  ETA, then the berth conversation
 PHASE 5b Console      ─►  port-console (React)  on :5173  →  the same system on screen
```

**Runtime dependency order (when starting the live system):** FastAPI (:8000)
**must** be up before Spring Boot can answer the operator's calculator
(`/api/operator/eta`), because Spring Boot calls it. Always start Python first,
Java second.

All commands assume you are in the **project root** (the folder this repo was
cloned into) unless stated otherwise.
The virtual environment is the sibling folder `..\port_ml_env\`.

---

## PHASE 0 — One-time environment setup

You only do this once per machine.

### 0.1 Python environment

```powershell
# First time on this machine: create the venv beside the project (needs Python 3.14)
python -m venv ..\port_ml_env

# Activate the venv (PowerShell)
..\port_ml_env\Scripts\Activate.ps1

# Install all Python dependencies
..\port_ml_env\Scripts\pip install -r requirements.txt
```

**Confirm it worked:**
```powershell
..\port_ml_env\Scripts\python --version       # -> Python 3.14.6
..\port_ml_env\Scripts\python src\data_loader.py   # smoke test: loads day-1 AIS, prints row/vessel counts
```
The `data_loader.py` smoke test should print something like *"loaded 638,236 rows
/ 175 vessels"* and the count of cleaned sentinel values. If that runs, your data
access works.

### 0.2 Java environment (for the Spring Boot service)

```powershell
java -version        # need Java 21 (LTS)
```
You do **not** need to install Gradle — the project ships a **Gradle wrapper**
(`gradlew.bat`) that downloads the correct version automatically on first use.

### 0.3 Database and secrets (for the Spring Boot service)

The scheduler stores its state in **MySQL 8**. You need:

1. MySQL running on `localhost:3306`.
2. An empty database called `port_core`:
   ```sql
   CREATE DATABASE port_core;
   ```
3. A MySQL user that can create tables in it. Its name goes in
   `spring.datasource.username` in
   `port-scheduler-api/src/main/resources/application.properties` (currently `dev`).
4. A secrets file. In `port-scheduler-api/`, copy `secrets.properties.example` to
   `secrets.properties` and fill in two values: the database password and a
   Google AI Studio API key (used by the harbor-master agent).

`secrets.properties` is gitignored. Never put the key or the password in
`application.properties`.

You do **not** create any tables. Flyway builds them, and seeds a demo port with
berths `B1`–`B4`, the first time the service starts.

**Confirm it worked:** after the first `bootRun` (Phase 4), `SHOW TABLES` in
`port_core` lists ten tables plus `flyway_schema_history`.

### 0.4 Node.js (for the console)

The console in `port-console/` needs **Node.js** (the current LTS). Install it,
then fetch the console's packages once:

```powershell
winget install OpenJS.NodeJS.LTS      # then open a new terminal
cd port-console
npm install
```

**Confirm it worked:** `node --version` prints a version, and `npm test` in
`port-console/` reports 69 tests passing (they need neither service running).

---

## PHASE 1 — Data → features

Turn the raw AIS parquet files into the labelled voyage dataset the model trains on.

```powershell
# Quick run on 3 files (fast, for iterating)
..\port_ml_env\Scripts\python src\feature_engineer.py --n-files 3

# Full run on all 40 files (the real dataset)
..\port_ml_env\Scripts\python src\feature_engineer.py --n-files -1
```

**What it does:** loads raw AIS → cleans sentinels → detects arrivals
(`nav_status==5` **plus** real displacement) → builds per-voyage sequences with
destination-conditioned features → splits **by vessel** (no leakage) → writes:

| Output file | Contents |
|---|---|
| `data/processed/voyage_features.parquet` | the labelled training rows |
| `data/processed/voyages_meta.parquet` | per-voyage metadata |

**Confirm it worked:** the full run reports roughly **688 voyages / 202k points**.
Both parquet files should now exist in `data/processed/`.

> ⚠️ This is a prerequisite for training. If `data/processed/` is empty, `train.py`
> has nothing to read.

---

## PHASE 2 — Train & test the model

### 2.1 Train the XGBoost baseline (the production ETA model)

```powershell
..\port_ml_env\Scripts\python src\train.py
```

**What it does:** loads `data/processed/`, trains XGBoost on the by-vessel split,
prints metrics, and saves the model bundle.

| Output | Value |
|---|---|
| `models/eta_xgb.pkl` | the trained model `{"model", "features", "target"}` |
| Test **MAE** | ~**69 minutes** (on unseen vessels) |
| Test **R²** | ~**0.62** |

**Confirm it worked:** the console prints MAE / RMSE / R² **and an error-by-horizon
table** (error broken down by how far the ship is from arrival), and
`models/eta_xgb.pkl` now exists. That `.pkl` is what the FastAPI service loads in
Phase 3 — **training must succeed before serving.**

### 2.2 (Optional) Train the Bi-GRU for comparison

```powershell
..\port_ml_env\Scripts\python src\train_bigru.py
```
This builds the PyTorch Bi-GRU and runs a **fair head-to-head** (re-scores XGBoost
on the same window-end points). Expected result: **Bi-GRU ~74.9 min vs XGBoost
~68.4 min** — XGBoost wins on this dataset. Saves `models/eta_bigru.pt`. This is a
documented experiment, **not** part of the production path.

### 2.3 (Optional) Run the tests

**Java (scheduler), from `port-scheduler-api/`:**

```powershell
.\gradlew test
```
101 tests in nine classes: migrations and schema, the scheduling flow, the
planning rules, the console's read views, DCSA publishing and receiving, and
the JSON contract of each controller. The ones that touch the database need
MySQL running (Phase 0.3) and use their own schema, `port_core_test`, which is
wiped and rebuilt on every run. They do not need FastAPI, and they never touch
`port_core`.

**Console, from `port-console/`:**

```powershell
npm test        # 69 tests: time and visit rules, DCSA messages, timeline layout, seats, the screens' main parts
npm run lint
```
They run against a mock of the scheduler, so nothing else has to be running.

**Python:** there are no Python tests yet; `pytest` is not in `requirements.txt`.

---

## PHASE 3 — Start the model service (FastAPI, port 8000)

```powershell
..\port_ml_env\Scripts\python -m uvicorn predict_api:app --app-dir src --host 127.0.0.1 --port 8000
```

> 🔑 **The `--app-dir src` is required** — `predict_api.py` does
> `import feature_engineer`, and that import only resolves when `src/` is on the
> path. Without it you get `ModuleNotFoundError: No module named 'feature_engineer'`.

**Confirm it's up:** you should see `Uvicorn running on http://127.0.0.1:8000`.
Then, in another terminal:

```bash
curl http://127.0.0.1:8000/health
# -> {"status":"ok","model":"eta_xgb.pkl","features":[...]}
```

**Test the model directly (before involving Java):**
```bash
curl -s -X POST http://127.0.0.1:8000/predict \
  -H "Content-Type: application/json" \
  -d '{"lat":60.1,"lon":4.9,"sog":12.5,"cog":210.0,"dest_lat":60.39,"dest_lon":5.32}'
# -> {"eta_minutes":414.0,"eta_hours":6.9,"distance_to_dest_nm":21.44,...}
```
You can also open <http://127.0.0.1:8000/docs> for an interactive test form.

Leave this service **running** and move to Phase 4 in a **new terminal**.

---

## PHASE 4 — Start the scheduler API (Spring Boot, port 8080)

In a **separate terminal**, from the `port-scheduler-api/` directory:

```powershell
cd port-scheduler-api
.\gradlew bootRun --console=plain
```

**What it does:** compiles and launches the Java service on port 8080, applies
any pending Flyway migrations to `port_core`, and checks the entity classes
against the schema. First run downloads dependencies (slow once); later runs are
fast.

**Confirm it's up:** the log ends with `Started PortSchedulerApplication`. Then:
```bash
curl http://127.0.0.1:8080/api/port
# -> {"unLocode":"NOFRO","name":"Floro",...,"berths":[{"code":"B1",...}, ... B4]}
```

> 🔑 Spring Boot's `EtaService` is configured (`application.properties`) to reach
> FastAPI at `http://localhost:8000`. If FastAPI is **not** running, the
> calculator (`/api/operator/eta`) will fail — start Phase 3 first.

> 🔑 Berth occupancy and the queue are **stored**. Stopping the service does not
> clear them, so a second run of Phase 5 starts from wherever the first one left
> off. To start clean, press *Reset the port* in the console, or empty the
> transaction tables:
> ```sql
> DELETE FROM visit_event; DELETE FROM eta_prediction;
> DELETE FROM berth_allocation; DELETE FROM vessel_visit; DELETE FROM vessel;
> ```

---

## PHASE 5 — Test the live system (the full chain)

With **both** services running. The calls are written for **Git Bash**.
**`END-TO-END-GUIDE.md` is the full version of this phase:** every endpoint,
every request, the answer to expect and the DCSA rule behind it. The four calls
below are the short proof that the chain works.

### 5.1 Ask the calculator (Spring Boot → FastAPI → model)
```bash
curl -s -X POST http://127.0.0.1:8080/api/operator/eta \
  -H "Content-Type: application/json" \
  -d '{"vesselId":"V001","lat":61.62,"lon":4.40,"sog":13.0,"cog":92.0,"destLat":61.612,"destLon":5.07}'
```
**Expect:** `{"vesselId":"V001","etaMinutes":226.9,"estimatedArrival":"...","distanceNm":19.13,"naiveEtaMinutes":88.3,"requestedArrival":null,"advisedSpeedKnots":null}`.
The `etaMinutes` is the model's answer, passed through unchanged. Nothing is
stored, and the last two fields are empty because V001 has no call open.

### 5.2 Open a call with a DCSA estimated arrival
```bash
curl -s -X POST http://127.0.0.1:8080/api/port-call/v2/events -H "Content-Type: application/json" -d '{"events":[{
  "vessel":{"vesselName":"V001","vesselTypeCode":"GCGO"},
  "portCallService":{"portCallServiceTypeCode":"BERTH","portCallServiceEventTypeCode":"ARRI","facilityTypeCode":"BRTH"},
  "timestamp":{"classifierCode":"EST","serviceDateTime":"2026-12-01T12:00:00Z"}}]}'
```
**Expect:** `{}` (every event applied). Use a time in the future.

### 5.3 See the berth and the terminal's requested arrival
```bash
curl -s "http://127.0.0.1:8080/api/visits?vessel=V001"
curl -s "http://127.0.0.1:8080/api/port-call/v2/events?classifierCode=REQ&vesselName=V001"
```
**Expect:** the visit holds `B1` (`"status":"PLANNED"`), `rtaBerth` equals
`etaBerth` because the berth was free, and `ptaBerth` is `null` until the ship
accepts. The second call shows the same request as a DCSA `REQ` event.

### 5.4 Cancel the call
V001 never arrived, so the planner's action for it is to cancel the call.
(`PUT /api/port/berths/B1/release` is for a ship that was alongside and left.)
```bash
REF=$(curl -s "http://127.0.0.1:8080/api/visits?vessel=V001" | python -c "import sys,json; print(json.load(sys.stdin)[0]['visitRef'])")
curl -s -X PUT http://127.0.0.1:8080/api/visits/$REF/cancel         # -> {"cancelled":true,"visitRef":"VV-..."}
curl -s http://127.0.0.1:8080/api/visits                            # -> []
```

**What to know about the behaviour**

- A call opens, changes and ends only through `POST /api/port-call/v2/events`:
  an `EST` arrival opens or replans it, a `PLN` equal to the requested time
  accepts, `ACT` arrival and departure move the ship on and off its berth, and
  `"portCall":{"isOmitted":true}` drops it. The answer is `200` either way; a
  refusal comes back in `feedbackElements` with severity `ERROR`.
- The first four ships get `B1` to `B4`. The fifth is queued and is asked to
  arrive when a berth is expected free, which is one typical stay after the
  earliest ship's arrival.
- A freed berth goes straight to the ship that has waited longest, with a new
  requested arrival; a newcomer cannot take it ahead of the queue.
- A second estimate from the same ship replans its call. It does not take a
  second berth.
- When the berth plan changes (a ship berths early or late, a berth is
  released, a call is omitted or cancelled, an estimate moves), every waiting
  ship's requested arrival is worked out again and re-issued if it moved by 15
  minutes or more. A re-issued request clears the ship's earlier acceptance.
- A ship is given only a berth it fits (length, beam and draft, sent in the
  `EST` in the standard's optional size fields), and one that fits no berth is
  refused with `ERROR` feedback. Maintenance windows (`/api/port/maintenance`,
  `POST`/`DELETE /api/port/berths/{code}/maintenance`) are planned around.
- A ship holding a berth that sends an estimate more than 4 hours after its
  agreed time gives the berth up, if a waiting ship can be alongside sooner.
- A berth is held from the terminal's answer, whether or not the ship accepts.
  `GET /api/visits` shows `answerDueAt`, 60 minutes after the request; the
  console flags a visit past it. Nothing is cancelled automatically: the
  planner decides, with `PUT /api/visits/{visitRef}/cancel`.
- The terminal never sends a speed. `POST /api/operator/eta` returns one
  (`advisedSpeedKnots`) for a ship whose call has a requested arrival later
  than it would otherwise arrive.
- The console reads four `GET` endpoints that change nothing: `/api/port`,
  `/api/port/berth-plan`, `/api/visits` (add `?vessel=` or `?state=all`) and
  `/api/visits/{visitRef}`.
- State is stored. Restart Spring Boot and `GET /api/visits` answers the same.

---

## PHASE 5b — The console (React, port 5173)

With both services running, in a **third terminal**:

```powershell
cd port-console
npm run dev
```

Open <http://localhost:5173>. The dev server forwards `/api` to Spring Boot on
port 8080, so nothing has to be configured.

**Confirm it worked:** the Harbour page shows four berths and, below it, the
**Stories** bar. Choose *The port fills up* and press the step button until the
story ends. You should see four berths taken, two ships waiting with a
requested arrival, the operator's calculator working out a speed, a berth
released and passed to the head of the queue, and the wire inspector (header,
*Wire*) listing every request. *Reset the port* closes whatever the story left open.

Two more ways to run it:

- `npm run dev:mock` shows every screen from sample data, with neither service
  running.
- Add `?seat=operator` or `?seat=planner` to an address to open it in that
  seat. There is no login yet; the seat only decides which screens are offered.

---

## PHASE 6 — Shut down

When you're done, stop both services.

- In each service's terminal: press **Ctrl+C**.
- Or kill by port (PowerShell), if they're running in the background:

```powershell
# Find the PID listening on a port, then stop it
Get-NetTCPConnection -LocalPort 8000 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Get-NetTCPConnection -LocalPort 8080 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
Get-NetTCPConnection -LocalPort 5173 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

**Confirm both are down:**
```bash
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8000/health   # 000 = down
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/port   # 000 = down
```

---

## Quick reference — the minimal happy path

Once Phase 0 is done and the model is trained, the day-to-day loop is just:

```powershell
# Terminal 1 — model service
..\port_ml_env\Scripts\python -m uvicorn predict_api:app --app-dir src --host 127.0.0.1 --port 8000

# Terminal 2 — scheduler API
cd port-scheduler-api ; .\gradlew bootRun --console=plain

# Terminal 3 — test
curl -s -X POST http://127.0.0.1:8080/api/operator/eta -H "Content-Type: application/json" -d '{"vesselId":"V001","lat":61.62,"lon":4.40,"sog":13.0,"cog":92.0,"destLat":61.612,"destLon":5.07}'
```

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `ModuleNotFoundError: feature_engineer` when starting FastAPI | `src/` not on the import path | Add `--app-dir src` to the uvicorn command (Phase 3) |
| `train.py` errors: no data | Phase 1 not run | Run `feature_engineer.py --n-files -1` first |
| FastAPI won't load model | `models/eta_xgb.pkl` missing | Run `train.py` (Phase 2) first |
| `/api/operator/eta` returns 500, FastAPI log shows `Unsupported upgrade request` / 422 | JDK HttpClient defaulted to HTTP/2 (h2c upgrade dropped the body) | Already fixed in `EtaService` (forces HTTP/1.1). If it recurs, confirm that fix is present |
| `/api/operator/eta` fails, FastAPI not reachable | FastAPI (:8000) not running | Start Phase 3 before Phase 4 |
| `curl` returns `000` | Nothing listening on that port | The service isn't running — start it |
| Port already in use | A previous run didn't shut down | Kill the PID on that port (Phase 6) |
| Spring Boot `bootRun` fails on first run | Dependencies still downloading / wrong Java | Confirm `java -version` is 21; let the first build finish |
| `bootRun` fails with `Access denied for user` or `Communications link failure` | MySQL not running, or `secrets.properties` missing / wrong password | Start MySQL; create `secrets.properties` from the `.example` (Phase 0.3) |
| `bootRun` fails with `Unknown database 'port_core'` | Database not created | `CREATE DATABASE port_core;` (Phase 0.3) |
| `bootRun` fails with `Schema-validation: missing column` or `missing table` | An entity class and the schema disagree | Add a new Flyway migration for the change; do not edit one that has been applied |
| `bootRun` fails with `Migration checksum mismatch` | An already-applied migration file was edited | Revert the edit and put the change in a new `V<n>__*.sql` file |
| A ship is queued, or gets a berth, when you did not expect it | State from an earlier run is still stored | Press *Reset the port* in the console, or empty the transaction tables (Phase 4 note) |
| `PUT .../release` returns `404` | No berth with that code | Use `B1`–`B4`; `GET /api/port` lists them |
| Console says "The scheduler is not answering" | Spring Boot (:8080) is not running | Start Phase 4, then reload the page |
| Console says the scheduler could not get an ETA | FastAPI (:8000) is not running | Start Phase 3 |
| The console's map is blank | No internet (the map background is fetched), or the browser has no WebGL | Positions are still in the Arrivals table; reconnect to see the map |
| A story says the port must be reset | Visits from an earlier run are still open | Press *Reset the port* in the Stories bar |

---

## Where each step is explained in depth

| Topic | Doc |
|---|---|
| Data loading & AIS sentinels | `src/data_loader.py`, `notebooks/01_eda.ipynb` |
| Feature engineering & labels | `src/feature_engineer.py`, `docs/baseline-approach.md` |
| XGBoost baseline | `docs/xgboost-explained.md` |
| Bi-GRU experiment | `docs/bigru-explained.md` |
| FastAPI model service | `docs/fastapi-service-explained.md` |
| Spring Boot scheduler | `docs/spring-boot-service-explained.md` |
| The console | `docs/frontend-explained.md` |
| Every endpoint, request by request, against the DCSA standard | `docs/END-TO-END-GUIDE.md` |
| The original plan | `docs/ROADMAP.md` |

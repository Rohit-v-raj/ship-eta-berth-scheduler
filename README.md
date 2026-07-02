# AI-Powered Ship ETA Predictor & Berth Scheduler

Predicts when ships will arrive at port from raw **AIS** (Automatic
Identification System) tracking data, then assigns berths and virtual queue
numbers through a REST API — an end-to-end ML system spanning a Python
data/model pipeline and a Java Spring Boot backend.

```
                 ┌─────────────────────┐      ┌──────────────────────┐
  Client ──────▶ │  Spring Boot API    │ ───▶ │  FastAPI service     │
  POST /api/     │  (Java 21)          │ HTTP │  (Python)            │
  ships/schedule │  berth allocation   │ ◀─── │  XGBoost ETA model   │
                 │  + virtual queue    │ ETA  │  feature rebuild     │
                 └─────────────────────┘      └──────────────────────┘
```

## Results

Trained on **~30M AIS position reports** (Norwegian west coast, Jul–Oct 2020)
→ 688 labelled port-approach voyages, evaluated on **held-out vessels** the
model has never seen:

| Model | Test MAE | Notes |
|---|---|---|
| Naive physics prior (distance ÷ speed) | 161.5 min | the baseline to beat |
| **XGBoost** (production model) | **69 min** (R² 0.62) | **57% better than naive** |
| Bi-GRU (PyTorch, 12-step sequences) | 74.9 min | built, measured, lost — see below |

## What makes this project different

- **Leakage-safe evaluation.** Data is split **by vessel**, so no ship ever
  appears in both train and test. A random row split (used by a 2024 journal
  paper we analyzed) leaks near-duplicate 3-second pings across the split and
  inflates every metric.
- **Honest negative results, kept in the log.** A Bi-GRU was built and lost
  fairly to XGBoost (74.9 vs 68.4 min on identical test points) — 688 voyages
  is too little data for a deep model, so the tree model ships. Vessel `length`
  and time-of-day/season features from published papers were tested via seeded
  ablations and rejected when they didn't improve held-out error
  (`docs/PROGRESS.md` → experiments log).
- **Hidden-missing-value handling.** AIS encodes "not available" as
  real-looking numbers (`sog=102.3`, `cog=360`, `heading=511`, `length=0`);
  everything passes through `clean_ais()` before use.
- **A real cross-service bug, diagnosed and fixed.** The JDK `HttpClient`
  defaults to an HTTP/2 h2c upgrade that uvicorn rejects, silently dropping
  the request body. Traced via the uvicorn logs and fixed by forcing HTTP/1.1
  (`docs/spring-boot-service-explained.md`).
- **Research-grounded choices.** Four papers analyzed, each with a
  plain-language summary in `references/` — the model choice, target
  construction, and split strategy all trace to published prior art.

## Layout

```
src/                  Python pipeline
  data_loader.py        AIS access + sentinel cleaning
  feature_engineer.py   arrival labelling, voyage extraction, by-vessel split
  train.py              XGBoost ETA baseline (production model)
  train_bigru.py        Bi-GRU head-to-head experiment
  predict_api.py        FastAPI service: /health, /predict
port-scheduler-api/   Spring Boot 3 / Java 21 / Gradle — berth + queue REST API
notebooks/            Jupyter EDA (executed against the real data)
docs/                 ROADMAP, PROGRESS (work log), WORKFLOW (runbook),
                      per-component explainers (FastAPI, Spring Boot, XGBoost…)
references/           plain-language summaries of the 4 research papers
data/                 raw parquets + processed voyages (not in repo — see below)
models/               trained artefacts (not in repo)
```

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
cd port-scheduler-api; .\gradlew bootRun

# 4. Schedule a ship
curl -X POST http://localhost:8080/api/ships/schedule -H "Content-Type: application/json" `
  -d '{"vesselId":"V001","lat":62.47,"lon":6.15,"sog":12.0,"cog":45.0,"destLat":62.47,"destLon":6.24}'
# → {"vesselId":"V001","etaMinutes":414.0,"status":"BERTHED","berthId":"B1",...}
```

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
| `docs/WORKFLOW.md` | end-to-end runbook: setup → data → train → serve → test |
| `docs/PROGRESS.md` | chronological work log incl. all experiments (also the failures) |
| `docs/fastapi-service-explained.md` | the Python model service, line by line |
| `docs/spring-boot-service-explained.md` | the Java scheduler + the HTTP/2 bug story |
| `docs/xgboost-explained.md`, `docs/bigru-explained.md` | how the models work |
| `references/*.md` | plain-language analyses of the 4 research papers |

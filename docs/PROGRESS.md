# Project Progress Log

A chronological walkthrough of everything done on the **AI-Powered ETA & Berth
Scheduler** project so far. Written so you (or a future Claude session) can pick
up exactly where we left off.

---

## 1. Project planning

- Defined the project concept: **predict a ship's Estimated Time of Arrival
  (ETA)** with a Python ML model, then wrap it in a **Spring Boot REST API**
  that assigns berths and virtual queue numbers.
- Decided on a **full-stack scope** (Python ML + Spring Boot API) targeting a
  beginner-to-intermediate skill level.
- Wrote a phased plan and saved it as **`ROADMAP.md`** in the project root.

**Files created:** `ROADMAP.md`

---

## 2. Scaffolded the folder structure

Created the standard project layout:

```
Port_ML_project/
├── data/
│   ├── raw/             # (empty placeholder)
│   ├── processed/       # cleaned data output (later)
│   └── parquets/        # <-- the real AIS data lives here
├── notebooks/
├── src/
├── models/
└── port-scheduler-api/  # Spring Boot project (later phase)
```

Also created **`requirements.txt`** listing the Python dependencies (pandas,
numpy, scikit-learn, xgboost, fastapi, etc.).

**Files created:** the directory tree, `requirements.txt`

---

## 3. Reviewed the research paper

You shared the paper *"Multi-factor influence-based ship trajectory prediction
analysis via deep learning"* (Jiao et al., 2025) to decide which ML model to use.

- The paper's PDF reader needed a tool that wasn't installed, so I **extracted
  the text with `PyPDF2`** instead.
- Read all 20 content pages.

**Key finding — model ranking (best to worst):**

| Rank | Model | Notes |
|------|-------|-------|
| **1** | **Bi-GRU** | Best accuracy, fewest parameters → **our choice** |
| 2 | Transformer | Strong on long sequences, heavier compute |
| 3 | GRU | Faster than LSTM |
| 4 | Bi-LSTM | Good but slower |
| 5 | LSTM | Slow, many parameters |
| 6 | Seq2seq | Worst |

**Most important input features:** `latitude`, `longitude`, `SOG` (Speed Over
Ground), `COG` (Course Over Ground) — and **SOG matters more than COG**.

**Training config from the paper (to reuse later):** optimizer RAdam, learning
rate `1e-4`, dropout `0.5`, hidden size `128`, 2 layers, batch size `256`, early
stopping after 10 stagnant epochs.

---

## 4. Inspected the data

You pointed me to the AIS data in `data/parquets/`. I profiled it:

- **40 Parquet files** named `2020-2_group_N` — **no file extension, but they
  are Parquet format** (must load with `pandas.read_parquet`, not by extension).
- **~30.4 million rows total**; each file ≈ one day of data.
- **Region:** Norwegian west coast (lon 4–8, lat 61–63).
- **Cadence:** AIS pings every ~3 seconds (the paper resamples to 5s).

**Schema (11 columns):**

| Column | Meaning |
|--------|---------|
| `mmsi` | vessel ID |
| `imo_nr` | IMO registration number |
| `length` | ship length in metres (`0` = unknown) |
| `date_time_utc` | ping timestamp |
| `lon`, `lat` | position |
| `sog` | Speed Over Ground (knots) |
| `cog` | Course Over Ground (degrees) |
| `true_heading` | heading (`511` = not available) |
| `nav_status` | navigational status code |
| `message_nr` | AIS message type |

**Critical discovery — AIS sentinel values.** The columns have *no nulls*, but
AIS hides "not available" inside real-looking numbers. These must be cleaned or
they corrupt the model:

| Field | Sentinel = "not available" |
|-------|------|
| `sog` | `102.3` |
| `cog` | `360.0` |
| `true_heading` | `511` |
| `length` | `0` |

**`nav_status == 5` (moored)** marks when a ship has berthed → this is how we'll
build the ETA target label.

---

## 5. Built the data loader

Created **`src/data_loader.py`** — the single entry point for all data access.

**What it provides:**
- `list_files()` — the 40 parquet files, sorted by group number.
- `load_file(which)` — load one file (by index or name).
- `load_sample(n_files)` — load and concatenate the first N files.
- `load_all()` — load everything (~30M rows; warns about memory).
- `clean_ais(df)` — replaces the AIS sentinels above with `NaN`.
- `get_vessel_track(df, mmsi)` — one vessel's pings, time-sorted.
- Constants `SOG_NA`, `COG_NA`, `HEADING_NA`, `LENGTH_NA` and a `NAV_STATUS`
  code→name map.

**Verified:** ran the built-in smoke test — loaded 638,236 rows / 175 vessels
for day 1, and `clean_ais` correctly flagged 84 missing SOG, 908 missing COG,
45,442 missing heading, 6,552 unknown lengths.

**Files created:** `src/data_loader.py`

---

## 6. Built the EDA notebook

Created **`notebooks/01_eda.ipynb`** — a 9-section exploratory analysis that
imports `data_loader` and walks through the data:

1. Load one day
2. Schema, dtypes, missing values
3. AIS sentinel values (the hidden missing data)
4. Temporal coverage and ping cadence
5. Spatial coverage (map colored by speed)
6. Distributions of SOG / COG / length
7. Navigational status — detecting arrivals
8. A single vessel trajectory
9. Takeaways & next steps

**Verified:** executed **all 19 cells headlessly** against the real Parquet
files — every cell ran without error. Confirmed findings:
- **52.7%** of pings are near-stationary (vessels in port/anchorage).
- **75 vessels** have moored (arrival) events on day 1.
- Median **2,430 pings per vessel**, ~3s cadence.

**Files created:** `notebooks/01_eda.ipynb`

---

## 7. Updated the project docs

- **`CLAUDE.md`** — added the real data schema, the AIS sentinel gotcha, the
  source layout, and the Bi-GRU model decision (so future sessions know the
  context immediately).
- **`ROADMAP.md`** — updated Phase 1 to reflect Bi-GRU as the primary model
  (with the paper's training config) and the AIS-specific feature pipeline.

---

## Current state

| Artifact | Status |
|----------|--------|
| Project structure | ✅ done |
| `requirements.txt` | ✅ done |
| `src/data_loader.py` | ✅ done & verified |
| `notebooks/01_eda.ipynb` | ✅ done & verified |
| `CLAUDE.md` / `ROADMAP.md` | ✅ updated |
| `src/feature_engineer.py` | ✅ done & verified (688 voyages, 202k points from all 40 files) |
| `src/train.py` (XGBoost baseline) | ✅ done — test MAE **69 min**, R² **0.62**; **best ETA model** |
| `src/train_bigru.py` (Bi-GRU ETA) | ✅ done — 74.9 min; did not beat XGBoost (too little data) |
| Bi-GRU trajectory head (bonus) | ⬜ optional — Bi-GRU's natural task |
| `src/predict_api.py` (FastAPI) | ✅ done & verified — serves XGBoost, `/predict` + `/health` |
| `port-scheduler-api/` (Spring Boot) | ✅ done & verified — calls `/predict`, assigns berths + queue |
| End-to-end integration (Spring → FastAPI) | ✅ verified — full chain returns ETA + berth |
| Spring AI assistant | ⬜ planned (Phase 5) |

**Detailed setup docs:** see `docs/fastapi-service-explained.md` (Phase 2) and
`docs/spring-boot-service-explained.md` (Phase 3 + integration). For the full
operational runbook (setup → data → train → serve → test, with every command and
expected output), see **`docs/WORKFLOW.md`**.

---

## Experiments log

- **Bi-GRU ETA model (built, did NOT beat the baseline).** Built a PyTorch
  Bi-GRU (`src/train_bigru.py`) reading 12-step ping windows per voyage, with
  sin/cos angle encoding and a fair head-to-head (XGBoost re-scored on the exact
  same window-end test points). Result: **Bi-GRU 74.9 min MAE vs XGBoost 68.4
  min**. Adding the physics prior to the Bi-GRU barely helped (76.2 → 74.9).
  **Why:** 688 voyages is too little for a data-hungry deep model; gradient-boosted
  trees dominate small/medium tabular problems. The paper's Bi-GRU win was for
  *trajectory* prediction on millions of points — a different task. **Decision:
  XGBoost stays the production ETA model;** the Bi-GRU is redirected to the
  trajectory bonus (where ~30M per-ping samples play to its strength). TensorFlow
  has no Python 3.14 wheel, so we use PyTorch (torch 2.12.1 CPU).

- **TID/TIY time features (tested, rejected for baseline).** Added the
  time-of-day / season one-hots from the Abdi & Amrit 2024 fusion paper to
  `feature_engineer.py` (kept in the dataset) and ablated them on the XGBoost
  baseline across 4 seeds (`scratch/ablate_tid.py`). Results: baseline MAE
  69.2±0.4 / RMSE 105.1±0.5; +TID 69.4 / 104.5; +TIY 68.5±0.4 / 103.5±1.1;
  +TID+TIY 69.4±0.4 / 102.0±0.3. All differences are ≤1 min MAE, inconsistent
  in direction across configs, and a per-season error breakdown showed the
  apparent gains don't come from the season signal itself. Root cause: the raw
  data spans only 2020-07-27 → 2020-10-21 (despite the misleading `2020-2`
  filenames), so TIY is a 2-summer-day flag with no generalizable signal, and
  coastal approach times evidently don't vary enough by time-of-day. **Kept in
  the dataset** (may matter with more months of data or for the Bi-GRU) but
  **excluded from the baseline model.** Same lesson as `length`: a paper's
  feature is a prior to validate, not a guarantee.

- **Vessel `length` feature (tested, rejected for baseline).** The ETA-review
  paper lists vessel size as a standard feature, so we carried `length` through
  the pipeline and added it to the XGBoost baseline. Result: held-out test MAE
  *worsened* 69 → 75 min (R² 0.62 → 0.53). On this small coastal-vessel dataset
  it added memorization/noise, not signal. **Kept in the dataset** (for the
  Bi-GRU later) but **excluded from the baseline model.** Lesson: a paper's
  "standard feature" is a prior to validate, not a guarantee.

## 8. Built the FastAPI prediction service (Phase 2)

Created **`src/predict_api.py`** — a FastAPI app that loads `models/eta_xgb.pkl`
at startup and exposes `GET /health` + `POST /predict`. It rebuilds the *exact*
training features from raw AIS state (importing `haversine_nm` / `bearing_deg`
from `feature_engineer` so the API and training can never drift), then returns
the predicted ETA. **Verified** with a direct POST: a vessel 21.4 nm out
returned `eta_minutes: 414.0`.

**Files created:** `src/predict_api.py`, `docs/fastapi-service-explained.md`

---

## 9. Built the Spring Boot scheduler (Phase 3) and integrated end-to-end (Phase 4)

Scaffolded **`port-scheduler-api/`** — a Gradle + Java 21 + Spring Boot 3.3.5
service. It accepts a vessel's AIS state, calls the FastAPI model for the ETA,
then assigns the first free berth (or a virtual queue number when full).

- `EtaService` — HTTP client to FastAPI `/predict` (via `RestClient`).
- `BerthService` — thread-safe in-memory berth allocation + queue.
- `PortController` — `POST /api/ships/schedule`, `GET /api/ships/berths`,
  `POST /api/ships/berths/{id}/release`.

**Integration bug found & fixed (HTTP/2 → HTTP/1.1).** The first full-chain test
returned HTTP 500. Tracing the uvicorn log showed `Unsupported upgrade request`
and a 422 — the JDK `HttpClient` defaults to **HTTP/2** and attempts an *h2c*
upgrade that uvicorn rejects, which silently **drops the request body**. Fix:
force `HttpClient.Version.HTTP_1_1` in `EtaService` via a `JdkClientHttpRequestFactory`.

**Verified end-to-end** (both services running):

| Test | Result |
|------|--------|
| `POST /schedule` (V001) | `200` — ETA 414 min (identical to FastAPI direct), berth **B1** |
| Fill berths V002–V004 | B2, B3, B4 assigned |
| 5th vessel V005 | `QUEUED`, queueNumber **1** |
| Release B2 → schedule V006 | B2 freed, V006 **reassigned to B2** |

The uvicorn log now shows every `/predict` call as `200 OK` — the h2c warnings
are gone.

**Files created:** the `port-scheduler-api/` project tree,
`docs/spring-boot-service-explained.md`

---

## Reference summaries

Plain-language write-ups of all four papers live in `references/`:
`ETA-review-paper-summary.md`, `trajectory-prediction-paper-summary.md`,
`eta-grand-challenge-2018-summary.md` (the DEBS 2018 maritime ETA system), and
`vessel-arrival-fusion-paper-summary.md` (Abdi & Amrit 2024, ESWA — a CNN+Attention+
LSTM fusion net; the **most on-target paper**, solving our exact AIS→ETA regression
task, and a cautionary example of a leaky random train/test split).

## Next step

Two open options, both optional:
1. **Phase 5 — Spring AI assistant:** a natural-language chat layer over the
   scheduler (tool calling), defaulting to the latest Claude models.
2. **Bi-GRU trajectory head (bonus):** redirect the Bi-GRU to next-position
   prediction, where the ~30M per-ping samples play to a deep model's strength.

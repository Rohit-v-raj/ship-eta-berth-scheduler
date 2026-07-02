# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Environment

The project uses a Python virtual environment located at `../port_ml_env/` (sibling directory).

- **Python version:** 3.14.6
- **Interpreter:** `C:\Users\rohit\AppData\Local\Programs\Python\Python314\python.exe`

Activate the environment before running anything:

```powershell
# From this project directory
..\port_ml_env\Scripts\Activate.ps1
```

Install packages into the environment:

```powershell
..\port_ml_env\Scripts\pip install <package>
```

## Common commands

```powershell
# Run a script
..\port_ml_env\Scripts\python <script.py>

# Install dependencies from requirements file (once created)
..\port_ml_env\Scripts\pip install -r requirements.txt

# Run tests (once pytest is installed)
..\port_ml_env\Scripts\pytest

# Run a single test file
..\port_ml_env\Scripts\pytest tests/test_<module>.py

# Run a single test by name
..\port_ml_env\Scripts\pytest tests/test_<module>.py::test_<name>

# Freeze dependencies
..\port_ml_env\Scripts\pip freeze > requirements.txt
```

## What this project does

AI-powered ETA & berth scheduler. A Python ML model predicts ship arrival times
from AIS data; a Spring Boot REST API (`port-scheduler-api/`, not yet built)
wraps it to assign berths and virtual queue numbers. See `docs/ROADMAP.md` for
the full phased plan and `docs/PROGRESS.md` for the running work log.

**Model decision:** Bi-GRU (bidirectional GRU) is the chosen architecture,
based on the benchmark in the paper at `references/Multi-factor influence-based
ship trajectory prediction analysis via deep learning.pdf`, where Bi-GRU ranked
#1 of 6 deep-learning models. An XGBoost baseline (`src/train.py`) is built
first; Bi-GRU comes next.

## Data

Raw AIS (Automatic Identification System) data lives in `data/parquets/` as 40
files named `2020-2_group_N` — **no file extension, but they are Parquet**. Load
with `pandas.read_parquet`, never by extension. ~30M rows total; each file is
~one day of high-frequency (~3s) position reports for the Norwegian west coast.
Despite the `2020-2` prefix, the data is **not February**: it spans
**2020-07-27 to 2020-10-21** (1 July day, 1 August, 22 September, 16 October).

**Critical gotcha — AIS sentinel values.** The raw columns contain no nulls, but
"not available" is encoded as real-looking numbers that will corrupt training if
left in. Always pass data through `clean_ais()` in `src/data_loader.py`:

| Field | Sentinel = "not available" |
|-------|------|
| `sog` | `102.3` |
| `cog` | `360.0` |
| `true_heading` | `511` |
| `length` | `0` |

`nav_status == 5` (moored) marks berthing/arrival events — this is how the ETA
target label is derived.

## Source layout

- `src/data_loader.py` — entry point for all data access (`list_files`,
  `load_file`, `load_sample`, `load_all`, `clean_ais`, `get_vessel_track`) plus
  the AIS sentinel constants and `NAV_STATUS` code map. Run directly for a smoke
  test: `..\port_ml_env\Scripts\python src/data_loader.py`.
- `notebooks/01_eda.ipynb` — exploratory analysis; imports `data_loader` via
  `sys.path.append('../src')`.
- `src/feature_engineer.py` — turns raw AIS into labelled voyage sequences.
  Defines arrival as `nav_status==5` **plus** real displacement (filters out
  "parked overnight" loiterers), adds destination-conditioned features
  (`distance_to_dest_nm`, `bearing_to_dest_deg`), resamples to a fixed step, and
  keeps both targets (`minutes_to_arrival` for ETA, `next_lat/next_lon` for the
  trajectory bonus head). Splits **by vessel** to prevent leakage. Outputs
  `data/processed/voyage_features.parquet` + `voyages_meta.parquet`. Run:
  `python src/feature_engineer.py --n-files 3` (use `-1` for all files).
- `src/train.py` — XGBoost ETA baseline. Loads `data/processed/`, trains on the
  by-vessel split, reports MAE/RMSE/R² and error-by-horizon, saves
  `models/eta_xgb.pkl`. Run: `python src/train.py`.
- `src/predict_api.py` — FastAPI service (Phase 2, done & verified). Loads
  `models/eta_xgb.pkl`, exposes `GET /health` + `POST /predict`, and rebuilds the
  exact training features via `feature_engineer`'s geometry helpers. Run from the
  project root: `..\port_ml_env\Scripts\python -m uvicorn predict_api:app
  --app-dir src --port 8000` (the `--app-dir src` is required so the
  `feature_engineer` import resolves). See `docs/fastapi-service-explained.md`.
- `port-scheduler-api/` — Spring Boot scheduler (Phase 3, done & verified). Gradle
  + Java 21 + Spring Boot 3.3.5. Calls FastAPI `/predict` and assigns berths /
  queue numbers. Endpoints under `/api/ships`. Run: `.\gradlew bootRun` from
  `port-scheduler-api/`. `EtaService` forces HTTP/1.1 (the JDK client's default
  HTTP/2 h2c upgrade is rejected by uvicorn and drops the body). See
  `docs/spring-boot-service-explained.md`.

## Repository layout

- `src/` — Python pipeline modules.
- `notebooks/` — Jupyter EDA.
- `data/parquets/` raw AIS · `data/processed/` engineered voyage datasets.
- `models/` — trained model artefacts.
- `reports/` — Excel study workbooks (`data_study.xlsx`, `sample_voyage.xlsx`).
- `docs/` — `ROADMAP.md` (plan), `PROGRESS.md` (work log), and `WORKFLOW.md`
  (end-to-end runbook: setup → data → train → serve → test). Plus per-component
  explainers (`fastapi-service-explained.md`, `spring-boot-service-explained.md`,
  `xgboost-explained.md`, `bigru-explained.md`, `baseline-approach.md`).
- `references/` — research papers + plain-language summaries
  (`ETA-review-paper-summary.md`, `trajectory-prediction-paper-summary.md`,
  `eta-grand-challenge-2018-summary.md`).
- `port-scheduler-api/` — Spring Boot service (Phase 3, done & verified).
- `scratch/` — throwaway experiments, safe to delete.

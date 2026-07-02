# AI-Powered ETA & Berth Scheduler — Roadmap

## What This Project Does

Uncontrolled ship arrivals cause port congestion and delays. This system:
1. Predicts a ship's **Estimated Time of Arrival (ETA)** using a Python ML model trained on historical port data
2. Exposes predictions via a **Spring Boot REST API** that automatically assigns virtual queue numbers and optimal berth allocations based on the predicted ETA

---

## Project Structure

```
Port_ML_project/
├── data/
│   ├── raw/                  # Drop your dataset here (CSV/Excel)
│   └── processed/            # Cleaned & feature-engineered output
├── notebooks/
│   └── 01_eda.ipynb          # Exploratory Data Analysis
├── src/
│   ├── data_loader.py        # Load & validate raw data
│   ├── preprocessor.py       # Cleaning, encoding, scaling
│   ├── feature_engineer.py   # Build model features
│   ├── train.py              # Train, evaluate, save model
│   └── predict_api.py        # FastAPI service — POST /predict
├── models/
│   └── eta_model.pkl         # Serialized trained model
├── port-scheduler-api/       # Spring Boot project (Maven)
│   └── src/main/java/com/port/scheduler/
│       ├── controller/PortController.java
│       ├── service/ETAService.java        # Calls Python API
│       ├── service/BerthService.java      # Berth allocation logic
│       └── model/ShipRequest.java
├── requirements.txt
├── CLAUDE.md
└── ROADMAP.md                # This file
```

---

## Phase 1 — Python ML Pipeline

**Goal:** Clean data, engineer features, train an **ETA regression** model (primary)
plus a **Bi-GRU trajectory predictor** (secondary/bonus).

**Files:** `data_loader.py` ✅, `feature_engineer.py`, `train.py`, `notebooks/01_eda.ipynb` ✅

### Strategy: one architecture, two tasks

Bi-GRU is a sequence **encoder**; we reuse it for both tasks by swapping the output head:

```
Past AIS sequence [lat, lon, sog, cog, dist_to_dest, bearing_to_dest, ...]
            │
        Bi-GRU encoder            ← paper's #1 architecture
            │
     ┌──────┴───────┐
     ▼              ▼
 single ETA      next positions
 (PRIMARY)       (BONUS trajectory head)
```

- **ETA regression is the product** (drives berth/queue scheduling). Trajectory
  prediction is a secondary deliverable for path visualisation / collision angle.
- Decided scope: **ETA + trajectory bonus**, **baseline-first**.

### Steps

| Step | What to do |
|------|-----------|
| 1 | Load + clean AIS sentinels via `data_loader.clean_ais` ✅ |
| 2 | EDA ✅ (`notebooks/01_eda.ipynb`) |
| 3 | **Define arrival** = `nav_status==5` (moored) **AND** meaningful displacement from origin (filter out "parked overnight" loiterers — see `sample_voyage.xlsx`) |
| 4 | **Destination-conditioned features:** the target berth/port is known, so add `distance_to_destination`, `bearing_to_destination` alongside `lat/lon/sog/cog` |
| 5 | Resample each voyage to a fixed step (~5s as in the paper); build sequence windows. Keep BOTH targets: `minutes_to_arrival` (ETA) and next-position (trajectory) |
| 6 | **Split by vessel/voyage, NOT random rows** (random split leaks a voyage into train+test and inflates scores) |
| 7 | **Baseline ETA:** `XGBoostRegressor` on tabular snapshot features — validates the whole pipeline fast |
| 8 | **Bi-GRU ETA:** sequence window → single ETA. Then **Bi-GRU trajectory head** as bonus |
| 9 | Evaluate: **MAE in minutes** (primary), RMSE; bucket error by time-to-arrival |
| 10 | Save: XGBoost via `joblib`; Bi-GRU via Keras `model.save` |

**Skills:** pandas, numpy, matplotlib/seaborn, scikit-learn, XGBoost, TensorFlow/Keras (Bi-GRU), joblib

**Bi-GRU config (from the paper):** ranking was **Bi-GRU > Transformer > GRU >
Bi-LSTM > LSTM > Seq2seq**; Bi-GRU won with the fewest parameters. Reuse:
optimizer RAdam (or Adam), lr `1e-4`, dropout `0.5`, hidden size `128`, 2 layers,
batch `256`, early stopping after 10 stagnant epochs. SOG matters more than COG.

---

## Architecture decision (serving)

Decided: **FastAPI (Python) model service + Spring Boot (Java) business layer**,
two services over HTTP. The model stays native Python (no ONNX conversion). A
**Spring AI** natural-language layer is planned later (the one place generative AI
genuinely fits — e.g. "when does vessel X arrive and which berth?").

```
Spring AI chat (later)  →  Spring Boot (berth/queue + REST)  →  FastAPI (XGBoost)  →  eta_xgb.pkl
```

## Phase 2 — Python Prediction API (FastAPI)

**Goal:** Wrap the **XGBoost** model (`models/eta_xgb.pkl`) in an HTTP service.

**File:** `src/predict_api.py`

The service takes the ship's current AIS state + known destination, derives the
model features (`distance_to_dest_nm`, `bearing_to_dest_deg`, `naive_eta_min`),
and returns the ETA.

```
POST /predict
Body:     { lat, lon, sog, cog, dest_lat, dest_lon }
Response: { eta_minutes: 87, distance_to_dest_nm: 12.3 }
```

**Skills:** FastAPI, Pydantic, uvicorn, joblib. Reuse `train.add_features` /
`feature_engineer` geometry helpers so API features match training exactly.

---

## Phase 3 — Spring Boot REST API

**Goal:** Accept ship requests, fetch ETA from Python, assign berth + queue number.

**Stack:** Java 17, Spring Boot 3.x, Maven

```
POST /api/ships/schedule
Body:     { vesselId, lat, lon, sog, cog, destLat, destLon }
Response: { etaMinutes, queueNumber, berthId, berthName }
```

- `ETAService.java` — calls the FastAPI `/predict` via `RestClient`/`RestTemplate`
- `BerthService.java` — in-memory berth state; assigns next available berth or queues the vessel

**Skills:** Spring Boot, REST controllers, HTTP client, Java OOP, Maven

---

## Phase 5 — Spring AI assistant (planned)

**Goal:** A natural-language layer over the scheduler — ask arrival/berth questions
in plain English; optionally parse unstructured inputs (weather/news) into features.

- Spring AI chat model + **tool/function calling** that invokes the existing
  `/api/ships/schedule` endpoint, so the LLM answers using real predictions.
- Default to the latest Claude models when wiring the provider.

**Skills:** Spring AI, chat models, tool calling.

---

## Phase 4 — Integration & Testing

1. Start Python API: `uvicorn src.predict_api:app --reload --port 8000`
2. Start Spring Boot: `mvn spring-boot:run` (inside `port-scheduler-api/`)
3. Test end-to-end with Postman or curl:
   ```bash
   curl -X POST http://localhost:8080/api/ships/schedule \
     -H "Content-Type: application/json" \
     -d '{"vesselId":"V001","vesselType":"Cargo","departurePort":"Singapore","arrivalPort":"Chennai","departureTime":"2026-06-25T08:00:00","loadWeight":5000}'
   ```
4. Verify: ETA returned, berth assigned, queue number increments on repeated calls

---

## Quick Command Reference

```powershell
# Activate virtual environment (from Port_ML_project/)
..\port_ml_env\Scripts\Activate.ps1

# Install all Python dependencies
pip install -r requirements.txt

# Train the model
python src/train.py

# Run prediction API
uvicorn src.predict_api:app --reload --port 8000

# Run Spring Boot API (from port-scheduler-api/)
mvn spring-boot:run
```

---

## Skills You Will Learn / Practice

| Skill | Used For |
|-------|---------|
| Python, pandas, numpy | Data loading and feature engineering |
| scikit-learn, XGBoost | Model training and evaluation |
| joblib | Saving/loading the trained model |
| FastAPI + Pydantic | Serving ML predictions over HTTP |
| Java 17 + Spring Boot 3 | REST API, business logic |
| RestTemplate | Spring Boot → Python HTTP calls |
| Maven | Spring Boot build and dependency management |
| Git | Version control |

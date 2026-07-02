# The FastAPI Model Service — Explained

> **What this doc is:** a complete, beginner-friendly walkthrough of the Python
> prediction service (`src/predict_api.py`) — what it is, why it exists, the tech
> behind it, how the code works line by line, and how to run and test it. Read
> this together with `spring-boot-service-explained.md` to understand how the two
> halves of the system talk to each other.

---

## 1. The one-sentence summary

`predict_api.py` is a small web server that **loads our trained XGBoost ETA model
once, then answers HTTP requests**: you send it a ship's current position, speed,
heading, and destination; it sends back "this ship will arrive in ~414 minutes."

It is **Phase 2** of the roadmap — the bridge between the machine-learning work
(Phase 1) and the Spring Boot business logic (Phase 3).

---

## 2. Why does this service exist at all?

We trained a model and saved it as a file: `models/eta_xgb.pkl`. That `.pkl`
("pickle") file is a frozen Python object — it can **only be opened by Python**.

But our user-facing API is written in **Java (Spring Boot)**. Java cannot open a
Python pickle. So we have a language barrier:

```
   Java (Spring Boot)  ──✗── cannot read ──✗──  Python model (.pkl)
```

The standard solution is to put the model behind a **web API**. HTTP is a neutral,
language-agnostic protocol — *any* language can send an HTTP request. So we wrap
the Python model in a tiny Python web server, and Java talks to it over HTTP:

```
   Java  ──HTTP POST /predict──►  Python (FastAPI)  ──►  XGBoost model
         ◄──── JSON: {eta} ─────
```

This is called a **microservice** architecture: each service does one job in the
language best suited to it, and they communicate over the network.

> **"Couldn't we run the model inside Java instead?"** Yes — you can export models
> to a format like ONNX and run them in Java. We deliberately chose **two services**
> instead, because it keeps the Python ML code and the Java business code cleanly
> separated, and it's the more common real-world pattern. (This was a deliberate
> design decision recorded earlier in the project.)

---

## 3. The tech stack (and what each piece does)

| Tool | Version | What it is | Why we use it |
|------|---------|-----------|---------------|
| **Python** | 3.14.6 | The language | The model is a Python object |
| **FastAPI** | — | A web framework | Turns Python functions into HTTP endpoints with almost no boilerplate |
| **Pydantic** | (bundled with FastAPI) | Data validation | Defines the *shape* of the request and auto-rejects bad input |
| **Uvicorn** | — | An ASGI web server | The actual program that listens on a port and runs FastAPI |
| **joblib** | — | Object loader | Reads the `.pkl` model file back into memory |
| **pandas** | — | DataFrames | The model expects a table; pandas builds it |

**How FastAPI and Uvicorn relate** (a common point of confusion):

- **FastAPI** is the *framework* — it's where you write your endpoints (`/predict`).
  It does not, by itself, listen for network traffic.
- **Uvicorn** is the *server* — it's the running process that opens TCP port 8000,
  accepts incoming HTTP connections, and hands each request to FastAPI.

Analogy: FastAPI is the restaurant's recipes and kitchen rules; Uvicorn is the
building, the door, and the waiter who brings orders in. You need both.

> **ASGI?** It stands for *Asynchronous Server Gateway Interface* — the modern
> Python standard for how a web server (Uvicorn) and a web app (FastAPI) talk.
> You don't need to memorise this; just know Uvicorn speaks it and FastAPI speaks it.

---

## 4. The request/response contract

This is the most important thing to understand — what goes **in** and what comes **out**.

**You send (POST `/predict`), a JSON body:**

```json
{
  "lat": 62.7375,
  "lon": 7.2000,
  "sog": 13.0,
  "cog": 220.0,
  "dest_lat": 61.6021,
  "dest_lon": 5.0555
}
```

| Field | Meaning | Rule |
|-------|---------|------|
| `lat`, `lon` | the ship's **current** position | any number |
| `sog` | Speed Over Ground, in knots | must be ≥ 0 |
| `cog` | Course Over Ground, in degrees | 0–360 |
| `dest_lat`, `dest_lon` | the **destination berth** position | any number |

**You get back, a JSON body:**

```json
{
  "eta_minutes": 414.0,
  "eta_hours": 6.9,
  "distance_to_dest_nm": 21.44,
  "bearing_to_dest_deg": 35.5,
  "naive_eta_min": 102.9
}
```

`eta_minutes` is the model's answer. The other fields are returned for
transparency — so a human (or the Spring Boot layer) can sanity-check *why* the
model said what it said.

> **Note the snake_case** (`dest_lat`, not `destLat`). Python convention is
> snake_case; Java convention is camelCase. The Java side translates between them
> — that detail is explained in the Spring Boot doc.

---

## 5. The code, walked through

The whole service is ~88 lines. Here's what each part does.

### 5a. Load the model **once**, at startup

```python
MODEL_PATH = Path(__file__).resolve().parent.parent / "models" / "eta_xgb.pkl"

_bundle = joblib.load(MODEL_PATH)
_model = _bundle["model"]
FEATURES: list[str] = _bundle["features"]
```

This runs **one time** when Uvicorn boots the app — not on every request. Loading
a model from disk is slow; doing it once and keeping it in memory means every
prediction afterwards is fast.

Notice the model file is a **bundle** (`{"model", "features", "target"}`), not just
the raw model. We saved the **list of feature names alongside the model** on
purpose, so the API can guarantee it builds the input columns in the exact order
and shape the model was trained on. If the API and the training script ever
disagreed on column order, predictions would be silently wrong.

### 5b. Define the input shape with Pydantic

```python
class ShipState(BaseModel):
    lat: float = Field(..., description="Current latitude")
    lon: float = Field(..., description="Current longitude")
    sog: float = Field(..., ge=0, description="Speed over ground (knots)")
    cog: float = Field(..., ge=0, le=360, description="Course over ground (degrees)")
    dest_lat: float = Field(..., description="Destination berth latitude")
    dest_lon: float = Field(..., description="Destination berth longitude")
```

`ShipState` is a **Pydantic model**. This single class does a lot for free:

- **Validation:** `ge=0` means "greater-or-equal to 0." If a request sends a
  negative speed, FastAPI **automatically rejects it with a `422` error** before
  your code ever runs. You never write `if sog < 0:` checks.
- **Parsing:** the incoming JSON text is automatically converted into a typed
  Python object. `state.sog` is a real `float`, not a string.
- **Documentation:** those `description=` strings show up in the auto-generated
  `/docs` page (see §7).

The `...` (Ellipsis) inside `Field(...)` means **"this field is required."**

### 5c. Rebuild the features — the critical part

```python
def build_features(s: ShipState) -> dict[str, float]:
    dist = float(fe.haversine_nm(s.lat, s.lon, s.dest_lat, s.dest_lon))
    bearing = float(fe.bearing_deg(s.lat, s.lon, s.dest_lat, s.dest_lon))
    naive_eta = min(dist / max(s.sog, 0.5) * 60, 6000)  # physics prior, clipped
    return {
        "distance_to_dest_nm": dist,
        "sog": s.sog,
        "cog": s.cog,
        "bearing_to_dest_deg": bearing,
        "lat": s.lat,
        "lon": s.lon,
        "naive_eta_min": naive_eta,
    }
```

**This is where most prediction bugs hide, so understand it well.** A model does
not take "a ship" as input — it takes the **exact same engineered features it was
trained on**, in the **same order**. During training (`feature_engineer.py`) we
computed:

- `distance_to_dest_nm` — great-circle distance to the destination (nautical miles)
- `bearing_to_dest_deg` — compass direction to the destination
- `naive_eta_min` — a **physics prior**: distance ÷ speed × 60 = "if the ship just
  held its current speed, how many minutes to arrive?" This gives the model a strong
  starting hint; it learns the *correction* on top of it.

The clever, important detail: **we import those geometry functions from
`feature_engineer`**:

```python
import feature_engineer as fe   # haversine_nm, bearing_deg — shared with training
```

We do **not** copy-paste the distance formula into the API. If we did, and someone
later tweaked the training formula, the two would silently drift apart and
predictions would degrade. By importing the *same* function, the API and the model
can never disagree on how a feature is computed. This is a deliberate
single-source-of-truth design.

`max(s.sog, 0.5)` avoids dividing by zero for a stopped ship. The `min(..., 6000)`
clips absurd ETAs (a barely-moving ship) to a sane ceiling.

### 5d. The two endpoints

```python
@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": MODEL_PATH.name, "features": FEATURES}
```

`/health` is a **liveness check** — a trivial endpoint that proves the server is
up and the model loaded. Monitoring tools and startup scripts ping it. (Our test
scripts poll `/health` until it returns `200` before sending real requests.)

```python
@app.post("/predict")
def predict(state: ShipState) -> dict:
    feats = build_features(state)
    X = pd.DataFrame([feats])[FEATURES]          # enforce training column order
    eta = max(float(_model.predict(X)[0]), 0.0)  # ETA can't be negative
    return {
        "eta_minutes": round(eta, 1),
        "eta_hours": round(eta / 60, 2),
        ...
    }
```

The flow, top to bottom:
1. `state: ShipState` — FastAPI has already validated and parsed the JSON into this object.
2. `build_features(state)` — turn raw AIS into the model's features.
3. `pd.DataFrame([feats])[FEATURES]` — make a **one-row table**, and reorder the
   columns to exactly `FEATURES`. XGBoost needs a table, and column order matters.
4. `_model.predict(X)[0]` — ask the model; take the single prediction.
5. `max(..., 0.0)` — a ship can't arrive in negative time; floor at zero.
6. Return a tidy JSON dict.

---

## 6. How to run it

From the **project root**, with the virtual environment:

```powershell
..\port_ml_env\Scripts\python.exe -m uvicorn predict_api:app --app-dir src --host 127.0.0.1 --port 8000
```

**Why `--app-dir src`?** `predict_api.py` does `import feature_engineer`, and
`feature_engineer.py` is a *sibling* file inside `src/`. `--app-dir src` puts the
`src/` folder on Python's import path so that import resolves. (Running it the
naïve way, `uvicorn src.predict_api:app`, fails with
`ModuleNotFoundError: No module named 'feature_engineer'` — a real bug we hit.)

Breaking down the command:
- `predict_api:app` — in the file `predict_api.py`, use the variable named `app`.
- `--host 127.0.0.1` — only accept connections from this machine (localhost).
- `--port 8000` — listen on port 8000. (Spring Boot is configured to call this port.)
- add `--reload` during development to auto-restart when you edit the file.

When it's up you'll see:

```
INFO:     Uvicorn running on http://127.0.0.1:8000 (Press CTRL+C to quit)
```

---

## 7. How to test it

**Interactive (easiest):** open <http://127.0.0.1:8000/docs> in a browser. FastAPI
auto-generates a full Swagger UI from your Pydantic models — you can fill in a form
and hit "Execute" with no curl needed. This page is free; you wrote zero lines for it.

**From the command line:**

```bash
curl -s -X POST http://127.0.0.1:8000/predict \
  -H "Content-Type: application/json" \
  -d '{"lat":60.1,"lon":4.9,"sog":12.5,"cog":210.0,"dest_lat":60.39,"dest_lon":5.32}'
```

Expected (verified):

```json
{"eta_minutes":414.0,"eta_hours":6.9,"distance_to_dest_nm":21.44,"bearing_to_dest_deg":35.5,"naive_eta_min":102.9}
```

**Health check:**

```bash
curl http://127.0.0.1:8000/health
# {"status":"ok","model":"eta_xgb.pkl","features":[...]}
```

---

## 8. Things that bit us (so you remember them)

- **`ModuleNotFoundError: feature_engineer`** — fixed with `--app-dir src` (see §6).
- **HTTP/2 vs HTTP/1.1** — when Java called this service, Uvicorn logged
  `Unsupported upgrade request` and returned `422`. That was **not** a FastAPI bug
  — the Java client was using HTTP/2. Uvicorn's default worker speaks HTTP/1.1, and
  the mismatch dropped the request body. The fix lives on the **Java** side
  (forcing HTTP/1.1) and is explained in the Spring Boot doc. Lesson: a `422`
  ("field required") doesn't always mean your validation is wrong — sometimes the
  body never arrived.

---

## 9. Mental model to keep

```
              ┌─────────────────── predict_api.py (FastAPI on :8000) ───────────────────┐
  HTTP POST   │                                                                          │
  /predict    │   ShipState (Pydantic)   build_features()        pandas 1-row table      │
  {lat,lon,   │   validates + parses  →  dist, bearing,      →   in FEATURES order   →   │ → XGBoost
   sog,cog,   │                          naive_eta prior                                 │   .predict()
   dest_*}    │                                                                          │     │
              │   JSON response  ◄───────────────────────────────────────────────────── │ ◄───┘
              │   {eta_minutes, eta_hours, distance, bearing, naive_eta}                  │
              └──────────────────────────────────────────────────────────────────────────┘
```

That's the whole service: **validate → engineer features (shared with training) →
predict → return JSON.** Small on purpose. The interesting business logic (berths,
queues) lives in Spring Boot, which is the subject of the companion doc.

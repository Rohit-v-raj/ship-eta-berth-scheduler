"""FastAPI service exposing the trained XGBoost ETA model.

Phase 2 of ROADMAP. The Spring Boot layer calls this over HTTP.

The service takes a ship's current AIS state plus its known destination berth,
derives the SAME features the model was trained on (distance + bearing to the
destination, and the physics prior), and returns the predicted ETA. The geometry
helpers are imported from ``feature_engineer`` so the API and training can never
drift apart.

Run (from the src/ directory):
    ..\\port_ml_env\\Scripts\\uvicorn predict_api:app --port 8000 --reload

Then POST to http://localhost:8000/predict — see /docs for the interactive UI.
"""
from __future__ import annotations

from pathlib import Path

import joblib
import pandas as pd
from fastapi import FastAPI
from pydantic import BaseModel, Field

import feature_engineer as fe  # haversine_nm, bearing_deg — shared with training

MODEL_PATH = Path(__file__).resolve().parent.parent / "models" / "eta_xgb.pkl"

_bundle = joblib.load(MODEL_PATH)
_model = _bundle["model"]
FEATURES: list[str] = _bundle["features"]

app = FastAPI(title="Port ETA Prediction API", version="1.0")


class ShipState(BaseModel):
    """Current AIS state of a vessel plus its destination berth."""
    lat: float = Field(..., description="Current latitude")
    lon: float = Field(..., description="Current longitude")
    sog: float = Field(..., ge=0, description="Speed over ground (knots)")
    cog: float = Field(..., ge=0, le=360, description="Course over ground (degrees)")
    dest_lat: float = Field(..., description="Destination berth latitude")
    dest_lon: float = Field(..., description="Destination berth longitude")

    model_config = {
        "json_schema_extra": {
            "example": {
                "lat": 62.7375, "lon": 7.2000, "sog": 13.0, "cog": 220.0,
                "dest_lat": 61.6021, "dest_lon": 5.0555,
            }
        }
    }


def build_features(s: ShipState) -> dict[str, float]:
    """Derive model features from raw AIS state — must match training exactly."""
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


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": MODEL_PATH.name, "features": FEATURES}


@app.post("/predict")
def predict(state: ShipState) -> dict:
    feats = build_features(state)
    X = pd.DataFrame([feats])[FEATURES]          # enforce training column order
    eta = max(float(_model.predict(X)[0]), 0.0)  # ETA can't be negative
    return {
        "eta_minutes": round(eta, 1),
        "eta_hours": round(eta / 60, 2),
        "distance_to_dest_nm": round(feats["distance_to_dest_nm"], 2),
        "bearing_to_dest_deg": round(feats["bearing_to_dest_deg"], 1),
        "naive_eta_min": round(feats["naive_eta_min"], 1),
    }

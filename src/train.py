"""Train the baseline ETA regression model (XGBoost).

Step 7 of ROADMAP Phase 1. Predicts ``minutes_to_arrival`` from snapshot
features, using the leakage-safe **by-vessel** split produced by
``feature_engineer.py``. This is the baseline the Bi-GRU model must beat.

Run:
    ..\\port_ml_env\\Scripts\\python src/train.py
"""
from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from xgboost import XGBRegressor

PROCESSED_DIR = Path(__file__).resolve().parent.parent / "data" / "processed"
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"

# Snapshot features available at inference time (no look-ahead).
# NOTE: raw dest_lat/dest_lon are deliberately excluded - they let the model
# memorise specific berths and destroy generalisation to unseen ports. We rely
# on RELATIVE geometry (distance/bearing to destination) which transfers.
FEATURES = [
    "distance_to_dest_nm",   # dominant predictor
    "sog", "cog",
    "bearing_to_dest_deg",
    "lat", "lon",
    "naive_eta_min",         # physics prior: distance / speed
]
# NOTE: vessel `length` is carried in the dataset (and is a "standard" ETA feature
# per Jiang et al. 2026) but was tested and EXCLUDED here — it slightly worsened
# held-out MAE (69 -> 75 min) on this small coastal-vessel dataset. Revisit for
# the Bi-GRU model where more capacity may use it well.
# NOTE: TID/TIY time-of-day/season one-hots (Abdi & Amrit 2024) are also carried
# in the dataset but EXCLUDED — a 4-seed ablation (scratch/ablate_tid.py) showed
# no consistent gain (~±0.5 min MAE), and the data spans too few months for the
# season flags to mean anything. See docs/PROGRESS.md experiments log.
TARGET = "minutes_to_arrival"


def add_features(df: pd.DataFrame) -> pd.DataFrame:
    """Add the physics-prior naive ETA (distance / speed, in minutes)."""
    df = df.copy()
    # fill sentinel-cleaned speed gaps for the prior only (raw 'sog' keeps NaN,
    # which XGBoost handles natively as its own feature)
    sog_filled = df["sog"].fillna(df["sog"].median())
    # clip speed so near-stationary pings don't produce infinite ETA
    df["naive_eta_min"] = (df["distance_to_dest_nm"] / sog_filled.clip(lower=0.5) * 60)
    df["naive_eta_min"] = df["naive_eta_min"].clip(upper=6000)
    return df


def load_splits() -> dict[str, pd.DataFrame]:
    path = PROCESSED_DIR / "voyage_features.parquet"
    if not path.exists():
        raise FileNotFoundError(
            f"{path} not found - run feature_engineer.py first.")
    df = add_features(pd.read_parquet(path))
    return {s: df[df["split"] == s].copy() for s in ("train", "val", "test")}


def evaluate(name: str, y_true, y_pred) -> dict:
    mae = mean_absolute_error(y_true, y_pred)
    rmse = np.sqrt(mean_squared_error(y_true, y_pred))
    r2 = r2_score(y_true, y_pred)
    print(f"  {name:<22} MAE={mae:7.1f} min   RMSE={rmse:7.1f} min   R2={r2:6.3f}")
    return {"mae": mae, "rmse": rmse, "r2": r2}


def error_by_horizon(y_true: pd.Series, y_pred: np.ndarray) -> None:
    """MAE bucketed by how far the ship is from arrival."""
    bins = [0, 30, 60, 120, 240, np.inf]
    labels = ["0-30", "30-60", "60-120", "120-240", "240+"]
    buckets = pd.cut(y_true, bins=bins, labels=labels, right=False)
    out = pd.DataFrame({"true": y_true.values, "err": np.abs(y_true.values - y_pred),
                        "bucket": buckets})
    print("\n  MAE by time-to-arrival (minutes remaining):")
    for label in labels:
        sel = out[out["bucket"] == label]
        if len(sel):
            print(f"    {label:>8} min : MAE={sel['err'].mean():6.1f} "
                  f"(n={len(sel):,})")


def main() -> None:
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    splits = load_splits()
    for s, d in splits.items():
        print(f"{s:<6}: {len(d):,} points, {d['voyage_id'].nunique()} voyages, "
              f"{d['mmsi'].nunique()} vessels")

    Xtr, ytr = splits["train"][FEATURES], splits["train"][TARGET]
    Xval, yval = splits["val"][FEATURES], splits["val"][TARGET]
    Xte, yte = splits["test"][FEATURES], splits["test"][TARGET]

    model = XGBRegressor(
        n_estimators=600, max_depth=6, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8, min_child_weight=5,
        reg_lambda=1.0, objective="reg:squarederror",
        eval_metric="mae", early_stopping_rounds=30, n_jobs=-1,
        random_state=42,
    )
    model.fit(Xtr, ytr, eval_set=[(Xval, yval)], verbose=False)
    print(f"\nBest iteration: {model.best_iteration} "
          f"(of {model.n_estimators} max)")

    print("\nResults (XGBoost vs naive distance/speed prior):")
    evaluate("naive prior (test)", yte, splits["test"]["naive_eta_min"].clip(upper=6000))
    evaluate("XGBoost (val)", yval, model.predict(Xval))
    xgb_test = model.predict(Xte)
    evaluate("XGBoost (test)", yte, xgb_test)

    error_by_horizon(yte, xgb_test)

    print("\n  Feature importance (gain):")
    imp = pd.Series(model.feature_importances_, index=FEATURES).sort_values(ascending=False)
    for feat, val in imp.items():
        print(f"    {feat:<22} {val:.3f}")

    out = MODELS_DIR / "eta_xgb.pkl"
    joblib.dump({"model": model, "features": FEATURES, "target": TARGET}, out)
    print(f"\nSaved model -> {out}")


if __name__ == "__main__":
    main()

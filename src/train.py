"""Train the ETA regression model (XGBoost).

Step 7 of ROADMAP Phase 1. Predicts ``minutes_to_arrival``, the minutes until
the ship stops at its berth, from snapshot features, using the leakage-safe
**by-vessel** split produced by ``feature_engineer.py``. This is the baseline
the Bi-GRU model must beat.

Revised on 2026-11-25 after experiments scored by 5-fold cross-validation by
vessel, every model marked against the same truth (the ship's stop):

- the target is the time to the stop, not to the moored status, which is
  often set minutes or hours later; that made the model late close to port;
- pings before a stop of 30+ minutes part-way are left out of training: their
  time to go includes a wait, which is the berth plan's business, not sailing;
- the model minimises absolute error;
- latitude and longitude are dropped (they memorise places and do worse on
  ones the model has not seen) and the on-course angle is added.

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

# Snapshot features available at inference time (no look-ahead), all derived
# from what the calculator sends: position, speed, course and destination.
# NOTE: raw lat/lon and dest_lat/dest_lon are deliberately excluded - they let
# the model memorise specific places and berths. Held out by whole 0.5-degree
# destination areas, the model without lat/lon was 6 to 27 min better on the
# final leg, at a cost of 1 to 1.4 min on places it had seen.
FEATURES = [
    "distance_to_dest_nm",   # dominant predictor
    "sog", "cog",
    "bearing_to_dest_deg",
    "naive_eta_min",         # physics prior: distance / speed
    "oncourse",              # cos(course - bearing to the berth): 1 heading straight for it
]
# NOTE: vessel `length`, heading and nav status are carried in the dataset but
# EXCLUDED: together they scored 1 to 2 min worse. TID/TIY time-of-day/season
# one-hots (Abdi & Amrit 2024) are also carried but EXCLUDED - a 4-seed ablation
# (scratch/ablate_tid.py) showed no consistent gain (~±0.5 min MAE), and the
# data spans too few months for the season flags to mean anything.
TARGET = "minutes_to_arrival"

# The recipe before 2026-11-25, kept so both can be scored on the same truth.
PREVIOUS_FEATURES = ["distance_to_dest_nm", "sog", "cog", "bearing_to_dest_deg", "lat", "lon", "naive_eta_min"]
PREVIOUS_TARGET = "minutes_to_moored"


def add_features(df: pd.DataFrame) -> pd.DataFrame:
    """Add the physics-prior naive ETA (distance / speed, in minutes) and the on-course angle."""
    df = df.copy()
    # fill sentinel-cleaned speed gaps for the prior only (raw 'sog' keeps NaN,
    # which XGBoost handles natively as its own feature)
    sog_filled = df["sog"].fillna(df["sog"].median())
    # clip speed so near-stationary pings don't produce infinite ETA
    df["naive_eta_min"] = (df["distance_to_dest_nm"] / sog_filled.clip(lower=0.5) * 60)
    df["naive_eta_min"] = df["naive_eta_min"].clip(upper=6000)
    df["oncourse"] = np.cos(np.radians(df["cog"] - df["bearing_to_dest_deg"]))
    return df


def load_splits() -> dict[str, pd.DataFrame]:
    path = PROCESSED_DIR / "voyage_features.parquet"
    if not path.exists():
        raise FileNotFoundError(
            f"{path} not found - run feature_engineer.py first.")
    df = add_features(pd.read_parquet(path))
    return {s: df[df["split"] == s].copy() for s in ("train", "val", "test")}


def training_rows(df: pd.DataFrame) -> pd.DataFrame:
    """The pings the model learns from: not those before a stop of 30+ min part-way (their time includes a wait)."""
    return df[~df["before_last_long_stop"]]


def under_way(df: pd.DataFrame) -> pd.Series:
    """Pings of a ship still sailing: before its stop, at 1 kn or more."""
    return ~df["after_stop"] & (df["sog"] >= 1)


def final_leg(df: pd.DataFrame) -> pd.Series:
    """Pings under way after any stop of 30+ min part-way: the sailing the model is for."""
    return under_way(df) & ~df["before_last_long_stop"]


def make_model(objective: str = "reg:absoluteerror") -> XGBRegressor:
    return XGBRegressor(
        n_estimators=600, max_depth=6, learning_rate=0.05,
        subsample=0.8, colsample_bytree=0.8, min_child_weight=5,
        reg_lambda=1.0, objective=objective,
        eval_metric="mae", early_stopping_rounds=30, n_jobs=-1,
        random_state=42,
    )


def evaluate(name: str, y_true, y_pred) -> dict:
    mae = mean_absolute_error(y_true, y_pred)
    rmse = np.sqrt(mean_squared_error(y_true, y_pred))
    r2 = r2_score(y_true, y_pred)
    bias = float(np.mean(np.asarray(y_pred) - np.asarray(y_true)))
    print(f"  {name:<34} MAE={mae:7.1f} min   RMSE={rmse:7.1f}   R2={r2:6.3f}   bias={bias:+7.1f}")
    return {"mae": mae, "rmse": rmse, "r2": r2, "bias": bias}


def error_by_horizon(y_true: pd.Series, predictions: dict[str, np.ndarray]) -> None:
    """MAE and bias bucketed by how far the ship is from its stop."""
    bins = [0, 30, 60, 120, 240, np.inf]
    labels = ["0-30", "30-60", "60-120", "120-240", "240+"]
    buckets = pd.cut(y_true, bins=bins, labels=labels, right=False)
    print("\n  MAE / bias by minutes to the stop:" + "".join(f"   {name:>22}" for name in predictions))
    for label in labels:
        sel = (buckets == label).to_numpy()
        if sel.any():
            cells = "".join(f"   {np.abs(p[sel] - y_true.to_numpy()[sel]).mean():9.1f} / {np.mean(p[sel] - y_true.to_numpy()[sel]):+8.1f}"
                            for p in predictions.values())
            print(f"    {label:>8} min (n={sel.sum():6,}):{cells}")


def cross_validate(df: pd.DataFrame, features: list[str], target: str, objective: str,
                   leave_out_waits: bool, k: int = 5, seed: int = 0) -> dict:
    """k folds by vessel; inside each, 15% of the training vessels stop the boosting early.
    Marked on every ping against the time to the stop."""
    vessels = np.array(sorted(df["mmsi"].unique()))
    np.random.default_rng(seed).shuffle(vessels)
    fold_of = {v: i % k for i, v in enumerate(vessels)}
    folds = df["mmsi"].map(fold_of)
    pred = pd.Series(np.nan, index=df.index)
    for f in range(k):
        train = df[folds != f]
        if leave_out_waits:
            train = training_rows(train)
        tv = np.array(sorted(train["mmsi"].unique()))
        np.random.default_rng(seed * 100 + f).shuffle(tv)
        val_v = set(tv[: int(round(len(tv) * 0.15))])
        fit, val = train[~train["mmsi"].isin(val_v)], train[train["mmsi"].isin(val_v)]
        model = make_model(objective)
        model.fit(fit[features], fit[target], eval_set=[(val[features], val[target])], verbose=False)
        test = folds == f
        pred[test] = model.predict(df.loc[test, features])
    err = pred - df[TARGET]
    per_fold = [float(err[folds == f].abs().mean()) for f in range(k)]
    return {"all": float(err.abs().mean()), "under_way": float(err[under_way(df)].abs().mean()),
            "final_leg": float(err[final_leg(df)].abs().mean()), "bias": float(err.mean()), "folds": per_fold}


def main() -> None:
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    splits = load_splits()
    for s, d in splits.items():
        print(f"{s:<6}: {len(d):,} points, {d['voyage_id'].nunique()} voyages, "
              f"{d['mmsi'].nunique()} vessels")

    train, val, test = training_rows(splits["train"]), training_rows(splits["val"]), splits["test"]
    print(f"training on {len(train):,} points (left out before a stop of 30+ min part-way: "
          f"{len(splits['train']) - len(train):,})")

    model = make_model()
    model.fit(train[FEATURES], train[TARGET], eval_set=[(val[FEATURES], val[TARGET])], verbose=False)
    print(f"\nBest iteration: {model.best_iteration} "
          f"(of {model.n_estimators} max)")

    xgb_test = model.predict(test[FEATURES])
    yte = test[TARGET]
    print("\nResults on the test vessels, against the time to the stop:")
    evaluate("naive prior (distance / speed)", yte, test["naive_eta_min"].clip(upper=6000))
    evaluate("XGBoost (val)", val[TARGET], model.predict(val[FEATURES]))
    evaluate("XGBoost (test, all pings)", yte, xgb_test)
    uw, leg = under_way(test).to_numpy(), final_leg(test).to_numpy()
    evaluate("XGBoost (test, under way)", yte[uw], xgb_test[uw])
    evaluate("XGBoost (test, final leg)", yte[leg], xgb_test[leg])
    predictions = {"this model": xgb_test}

    previous = MODELS_DIR / "eta_xgb_prev.pkl"
    if previous.exists():
        prev = joblib.load(previous)
        prev_test = prev["model"].predict(test[prev["features"]])
        print("\nThe previous model (models/eta_xgb_prev.pkl) on the same test pings:")
        evaluate("previous, against the stop", yte, prev_test)
        evaluate("previous, against moored status", test["minutes_to_moored"], prev_test)
        predictions["previous model"] = prev_test

    error_by_horizon(yte, predictions)

    print("\n5-fold cross-validation by vessel, every ping, against the time to the stop:")
    full = pd.concat(splits.values())
    for name, cv in (("this recipe", cross_validate(full, FEATURES, TARGET, "reg:absoluteerror", True)),
                     ("previous recipe", cross_validate(full, PREVIOUS_FEATURES, PREVIOUS_TARGET,
                                                        "reg:squarederror", False))):
        folds = ", ".join(f"{m:.1f}" for m in cv["folds"])
        print(f"  {name:<16} all {cv['all']:6.1f}   under way {cv['under_way']:6.1f}   "
              f"final leg {cv['final_leg']:6.1f}   bias {cv['bias']:+6.1f}   folds: {folds}")

    print("\n  Feature importance (gain):")
    imp = pd.Series(model.feature_importances_, index=FEATURES).sort_values(ascending=False)
    for feat, val_imp in imp.items():
        print(f"    {feat:<22} {val_imp:.3f}")

    out = MODELS_DIR / "eta_xgb.pkl"
    joblib.dump({"model": model, "features": FEATURES, "target": TARGET}, out)
    print(f"\nSaved model -> {out}")


if __name__ == "__main__":
    main()

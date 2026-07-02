"""Ablation: do the TID/TIY one-hots (Abdi & Amrit 2024) help the XGBoost ETA
baseline? Trains identical models on identical splits, only FEATURES varies.
Does NOT overwrite models/eta_xgb.pkl.

Run from project root: ..\\port_ml_env\\Scripts\\python scratch/ablate_tid.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

import numpy as np
import pandas as pd
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from xgboost import XGBRegressor

from train import FEATURES, TARGET, load_splits

TID = ["tid_night", "tid_morning", "tid_afternoon", "tid_evening"]
TIY = ["tiy_spring", "tiy_summer", "tiy_autumn", "tiy_winter"]

CONFIGS = {
    "baseline (current)": FEATURES,
    "+ TID": FEATURES + TID,
    "+ TID + TIY": FEATURES + TID + TIY,
}


def main():
    splits = load_splits()
    ytr, yval, yte = (splits[s][TARGET] for s in ("train", "val", "test"))

    rows = []
    for name, feats in CONFIGS.items():
        model = XGBRegressor(
            n_estimators=600, max_depth=6, learning_rate=0.05,
            subsample=0.8, colsample_bytree=0.8, min_child_weight=5,
            reg_lambda=1.0, objective="reg:squarederror",
            eval_metric="mae", early_stopping_rounds=30, n_jobs=-1,
            random_state=42,
        )
        model.fit(splits["train"][feats], ytr,
                  eval_set=[(splits["val"][feats], yval)], verbose=False)
        pred = model.predict(splits["test"][feats])
        rows.append({
            "config": name,
            "n_feats": len(feats),
            "best_iter": model.best_iteration,
            "test_MAE": mean_absolute_error(yte, pred),
            "test_RMSE": np.sqrt(mean_squared_error(yte, pred)),
            "test_R2": r2_score(yte, pred),
        })
        imp = pd.Series(model.feature_importances_, index=feats)
        extra = imp[[f for f in feats if f not in FEATURES]]
        if len(extra):
            print(f"{name} — importance (gain) of the added features:")
            print(extra.sort_values(ascending=False).round(4).to_string())
            print()

    out = pd.DataFrame(rows)
    out["test_MAE"] = out["test_MAE"].round(1)
    out["test_RMSE"] = out["test_RMSE"].round(1)
    out["test_R2"] = out["test_R2"].round(3)
    print(out.to_string(index=False))


if __name__ == "__main__":
    main()

"""Train a Bi-GRU sequence model for ETA regression (PyTorch).

Step 8 of ROADMAP Phase 1. Unlike the XGBoost baseline (one snapshot per
prediction), this reads a WINDOW of recent resampled pings per voyage and
predicts ``minutes_to_arrival`` at the final ping. Architecture follows Jiao et
al. (2025): bidirectional GRU, 2 layers, dropout 0.5.

Key design choices:
- Angles (cog, bearing) are encoded as sin/cos so 359 deg and 1 deg are close.
- Features are standardised using TRAIN statistics only (no leakage).
- The same by-vessel split as the baseline is used, and the XGBoost baseline is
  re-scored on the EXACT same window-end points for a fair head-to-head.

Run:
    ..\\port_ml_env\\Scripts\\python src/train_bigru.py
"""
from __future__ import annotations

from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import torch
import torch.nn as nn
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score

import train as baseline  # reuse add_features / FEATURES / load paths

PROCESSED_DIR = Path(__file__).resolve().parent.parent / "data" / "processed"
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"

WINDOW = 12          # timesteps of history (12 x 60s = 12 min)
STRIDE = 4           # step between windows (keeps CPU training tractable)
HIDDEN = 64
LAYERS = 2
DROPOUT = 0.5
BATCH = 256
LR = 1e-3
MAX_EPOCHS = 40
PATIENCE = 6
SEED = 42

# Per-timestep model inputs. Angles become sin/cos pairs below.
# naive_eta_min is the physics prior (distance/speed) — the baseline's top feature,
# given to the Bi-GRU explicitly so it need not re-learn it from scratch.
SCALE_COLS = ["lat", "lon", "sog", "distance_to_dest_nm", "naive_eta_min"]
SEQ_FEATURES = SCALE_COLS + ["cog_sin", "cog_cos", "bearing_sin", "bearing_cos"]


def prepare(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    # physics prior, from RAW distance/speed (before standardisation)
    sog_for_eta = df["sog"].fillna(df["sog"].median()).clip(lower=0.5)
    df["naive_eta_min"] = (df["distance_to_dest_nm"] / sog_for_eta * 60).clip(upper=6000)
    df["sog"] = df["sog"].fillna(0.0)
    cog = np.radians(df["cog"].fillna(0.0))
    brg = np.radians(df["bearing_to_dest_deg"].fillna(0.0))
    df["cog_sin"], df["cog_cos"] = np.sin(cog), np.cos(cog)
    df["bearing_sin"], df["bearing_cos"] = np.sin(brg), np.cos(brg)
    return df


def make_windows(df: pd.DataFrame):
    """Build (X, y, end_index, split) sliding windows within each voyage."""
    X, y, end_idx, splits = [], [], [], []
    for _, g in df.groupby("voyage_id", sort=False):
        feats = g[SEQ_FEATURES].to_numpy(dtype=np.float32)
        tgt = g["minutes_to_arrival"].to_numpy(dtype=np.float32)
        idx = g.index.to_numpy()
        split = g["split"].iloc[0]
        for end in range(WINDOW - 1, len(g), STRIDE):
            X.append(feats[end - WINDOW + 1: end + 1])
            y.append(tgt[end])
            end_idx.append(idx[end])
            splits.append(split)
    return (np.stack(X), np.asarray(y, np.float32),
            np.asarray(end_idx), np.asarray(splits))


class BiGRU(nn.Module):
    def __init__(self, n_features: int):
        super().__init__()
        self.gru = nn.GRU(n_features, HIDDEN, num_layers=LAYERS,
                          batch_first=True, dropout=DROPOUT, bidirectional=True)
        self.head = nn.Sequential(
            nn.Dropout(DROPOUT), nn.Linear(2 * HIDDEN, 64), nn.ReLU(),
            nn.Linear(64, 1),
        )

    def forward(self, x):
        out, _ = self.gru(x)
        return self.head(out[:, -1, :]).squeeze(-1)


def report(name, y_true, y_pred):
    mae = mean_absolute_error(y_true, y_pred)
    rmse = np.sqrt(mean_squared_error(y_true, y_pred))
    r2 = r2_score(y_true, y_pred)
    print(f"  {name:<26} MAE={mae:6.1f} min   RMSE={rmse:6.1f} min   R2={r2:6.3f}")
    return mae


def error_by_horizon(y_true, y_pred):
    bins = [0, 30, 60, 120, 240, np.inf]
    labels = ["0-30", "30-60", "60-120", "120-240", "240+"]
    b = pd.cut(y_true, bins=bins, labels=labels, right=False)
    err = np.abs(y_true - y_pred)
    print("\n  Bi-GRU MAE by time-to-arrival:")
    for lab in labels:
        m = b == lab
        if m.any():
            print(f"    {lab:>8} min : MAE={err[m].mean():6.1f} (n={m.sum():,})")


def main():
    torch.manual_seed(SEED)
    np.random.seed(SEED)
    torch.set_num_threads(torch.get_num_threads())

    raw = pd.read_parquet(PROCESSED_DIR / "voyage_features.parquet")
    df = prepare(raw)

    # standardise scale-cols using TRAIN rows only
    tr_mask = df["split"] == "train"
    mu = df.loc[tr_mask, SCALE_COLS].mean()
    sd = df.loc[tr_mask, SCALE_COLS].std().replace(0, 1)
    df[SCALE_COLS] = (df[SCALE_COLS] - mu) / sd

    X, y, end_idx, splits = make_windows(df)
    print(f"Windows: total {len(X):,} | "
          f"train {(splits=='train').sum():,} "
          f"val {(splits=='val').sum():,} test {(splits=='test').sum():,}")

    # standardise target on train windows
    tr = splits == "train"
    ty_mu, ty_sd = y[tr].mean(), y[tr].std()

    def split_tensors(name):
        m = splits == name
        xt = torch.tensor(X[m])
        yt = torch.tensor((y[m] - ty_mu) / ty_sd)
        return xt, yt, m

    Xtr, ytr, _ = split_tensors("train")
    Xval, yval, _ = split_tensors("val")
    Xte, yte, te_mask = split_tensors("test")

    model = BiGRU(len(SEQ_FEATURES))
    opt = torch.optim.Adam(model.parameters(), lr=LR)
    loss_fn = nn.MSELoss()

    train_ds = torch.utils.data.TensorDataset(Xtr, ytr)
    loader = torch.utils.data.DataLoader(train_ds, batch_size=BATCH, shuffle=True)

    best_val, best_state, bad = np.inf, None, 0
    for epoch in range(1, MAX_EPOCHS + 1):
        model.train()
        for xb, yb in loader:
            opt.zero_grad()
            loss = loss_fn(model(xb), yb)
            loss.backward()
            opt.step()
        model.eval()
        with torch.no_grad():
            val_pred = model(Xval)
            val_mae_min = mean_absolute_error(
                yval.numpy() * ty_sd + ty_mu, val_pred.numpy() * ty_sd + ty_mu)
        print(f"  epoch {epoch:2d}  val_MAE={val_mae_min:6.1f} min")
        if val_mae_min < best_val - 0.1:
            best_val, best_state, bad = val_mae_min, model.state_dict(), 0
        else:
            bad += 1
            if bad >= PATIENCE:
                print(f"  early stop at epoch {epoch}")
                break
    model.load_state_dict(best_state)

    # ---- evaluate on test ----
    model.eval()
    with torch.no_grad():
        te_pred = model(Xte).numpy() * ty_sd + ty_mu
    te_true = y[te_mask]

    print("\nHead-to-head on the SAME test window-end points:")
    # fair baseline: re-score XGBoost on the exact window-end rows
    bundle = joblib.load(MODELS_DIR / "eta_xgb.pkl")
    end_rows = baseline.add_features(raw.loc[end_idx[te_mask]])
    xgb_pred = bundle["model"].predict(end_rows[bundle["features"]])
    report("XGBoost baseline", te_true, xgb_pred)
    bigru_mae = report("Bi-GRU", te_true, te_pred)

    error_by_horizon(te_true, te_pred)

    out = MODELS_DIR / "eta_bigru.pt"
    torch.save({"state_dict": model.state_dict(),
                "seq_features": SEQ_FEATURES, "window": WINDOW,
                "scale_cols": SCALE_COLS, "feat_mu": mu.to_dict(),
                "feat_sd": sd.to_dict(), "ty_mu": float(ty_mu),
                "ty_sd": float(ty_sd)}, out)
    print(f"\nSaved Bi-GRU -> {out}")


if __name__ == "__main__":
    main()

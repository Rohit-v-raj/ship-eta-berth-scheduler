"""Turn raw AIS pings into labelled voyage sequences for model training.

Pipeline (Phase 1, steps 3-6 of ROADMAP.md):

1. **Find the calls** - a ``nav_status == 5`` (moored) segment that the vessel
   reached *after travelling a meaningful distance*. This filters out the
   "parked overnight" loiterers (see ``reports/sample_voyage.xlsx``) whose
   mooring point equals their origin.
2. **Define arrival as the moment the ship stops** - the last ping at
   ``STOP_KN`` or more before the ship settles (stays below ``STOP_KN`` for
   ``SETTLE_MINUTES`` within ``SETTLE_NM`` of the berth). The moored status is
   often set minutes or hours after the ship stopped (median 13 min), and now
   and then while it still moves, so the time it was set is kept only for
   comparison, as ``minutes_to_moored`` (experiments of 2026-11-25).
3. **Extract the approach voyage** before each call and label every ping with
   ``minutes_to_arrival`` (the ETA target: minutes to the stop, 0 after it).
   Pings before a stop of ``LONG_STOP_MINUTES`` or more part-way are flagged
   ``before_last_long_stop``: their time to go includes a wait, which is the
   berth plan's business, not the sailing the model predicts.
4. **Destination-conditioned features** - the berth/port is known, so we add
   ``distance_to_dest_nm`` and ``bearing_to_dest_deg``. Plus TID/TIY one-hots
   (time-of-day / season, per Abdi & Amrit 2024).
5. **Resample** each voyage to a fixed time step so sequences are uniform, and
   keep BOTH targets: ``minutes_to_arrival`` (ETA) and ``next_lat/next_lon``
   (trajectory bonus head).
6. **Split by vessel** (not random rows) so a voyage never leaks across
   train/val/test.

Each file is one UTC day. The days before and after a file, when the data has
them, are read with it, so a call that starts late in the evening and the stop
before a call just after midnight are seen whole; the rows themselves come only
from the arrival day.

Outputs (written to ``data/processed/``):
    voyage_features.parquet   one row per resampled ping
    voyages_meta.parquet      one row per voyage (summary + split)

Run directly to build from the first few files:
    ..\\port_ml_env\\Scripts\\python src/feature_engineer.py --n-files 3
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

import numpy as np
import pandas as pd

import data_loader as dl

PROCESSED_DIR = Path(__file__).resolve().parent.parent / "data" / "processed"

# --- tunable thresholds -----------------------------------------------------
MIN_DISPLACEMENT_NM = 3.0   # voyage must start >= this far from the berth
MIN_MOORED_PINGS = 10       # a real mooring, not a transient blip
MOOR_GAP_HOURS = 1.0        # gap that separates two distinct mooring events
MIN_VOYAGE_POINTS = 10      # resampled points required to keep a voyage
MIN_TRANSIT_SOG = 3.0       # voyage must show real movement (knots)
MAX_LOOKBACK_HOURS = 24.0   # cap how far back an approach extends
RESAMPLE = "60s"            # fixed step for the sequences
EARTH_NM = 3440.065         # earth radius in nautical miles

# arrival: when the ship stops (tested against 0.27 kn, the thesis threshold: 3 min later on the median, scored worse)
STOP_KN = 1.0               # moving at or above this speed
SETTLE_NM = 0.5             # settled within this distance of the berth ...
SETTLE_MINUTES = 10         # ... below STOP_KN for this long
LONG_STOP_MINUTES = 30      # a stop part-way of this length or more is a wait, not sailing


# --- geometry helpers -------------------------------------------------------
def haversine_nm(lat1, lon1, lat2, lon2):
    lat1, lon1, lat2, lon2 = map(np.radians, [lat1, lon1, lat2, lon2])
    dlat, dlon = lat2 - lat1, lon2 - lon1
    a = np.sin(dlat / 2) ** 2 + np.cos(lat1) * np.cos(lat2) * np.sin(dlon / 2) ** 2
    return 2 * EARTH_NM * np.arcsin(np.sqrt(a))


def bearing_deg(lat1, lon1, lat2, lon2):
    """Initial great-circle bearing from point 1 to point 2, degrees [0, 360)."""
    lat1, lat2 = np.radians(lat1), np.radians(lat2)
    dlon = np.radians(lon2 - lon1)
    x = np.sin(dlon) * np.cos(lat2)
    y = np.cos(lat1) * np.sin(lat2) - np.sin(lat1) * np.cos(lat2) * np.cos(dlon)
    return (np.degrees(np.arctan2(x, y)) + 360) % 360


def _split_for(mmsi: int) -> str:
    """Deterministic train/val/test bucket keyed on the vessel (not the row)."""
    h = int(hashlib.md5(str(mmsi).encode()).hexdigest(), 16) % 100
    return "train" if h < 70 else "val" if h < 85 else "test"


def add_time_features(seg: pd.DataFrame) -> pd.DataFrame:
    """TID/TIY one-hots per Abdi & Amrit 2024 (vessel-arrival-fusion paper).

    TID = time-of-day (night 00-06, morning 06-12, afternoon 12-18,
    evening 18-24), TIY = season. Timestamps are UTC (Norway is UTC+1, so
    buckets are shifted ~1h from local time — irrelevant for a tree model).
    NOTE: despite the "2020-2" filenames, the raw data spans 2020-07-27 to
    2020-10-21 (mostly Sep/Oct), so TIY only ever hits summer (2 files) and
    autumn (38 files) — very low signal until more months are added.
    """
    hour = seg["date_time_utc"].dt.hour
    seg["tid_night"] = (hour < 6).astype(np.int8)
    seg["tid_morning"] = ((hour >= 6) & (hour < 12)).astype(np.int8)
    seg["tid_afternoon"] = ((hour >= 12) & (hour < 18)).astype(np.int8)
    seg["tid_evening"] = (hour >= 18).astype(np.int8)

    month = seg["date_time_utc"].dt.month
    seg["tiy_spring"] = month.isin([3, 4, 5]).astype(np.int8)
    seg["tiy_summer"] = month.isin([6, 7, 8]).astype(np.int8)
    seg["tiy_autumn"] = month.isin([9, 10, 11]).astype(np.int8)
    seg["tiy_winter"] = month.isin([12, 1, 2]).astype(np.int8)
    return seg


# --- core extraction --------------------------------------------------------
def _mooring_events(track: pd.DataFrame) -> list[dict]:
    """Collapse a vessel's moored pings into discrete arrival events."""
    moored = track[track["nav_status"] == 5]
    if len(moored) < MIN_MOORED_PINGS:
        return []
    gap = moored["date_time_utc"].diff().dt.total_seconds().gt(MOOR_GAP_HOURS * 3600)
    group = gap.cumsum()
    events = []
    for _, seg in moored.groupby(group):
        if len(seg) < MIN_MOORED_PINGS:
            continue
        events.append({
            "arrival_time": seg["date_time_utc"].iloc[0],
            "end_time": seg["date_time_utc"].iloc[-1],
            "dest_lat": float(seg["lat"].median()),
            "dest_lon": float(seg["lon"].median()),
        })
    return events


def _physical_arrival(t: np.ndarray, sog: np.ndarray, dist: np.ndarray, start: int, stop: int) -> int | None:
    """Index of the last ping at STOP_KN or more before the ship settles near the berth, among pings
    [start, stop); None if it never settles there. Settled: below STOP_KN within SETTLE_NM of the berth,
    with no ping at STOP_KN or more for SETTLE_MINUTES after."""
    moving = np.where(np.nan_to_num(sog[start:stop], nan=-1.0) >= STOP_KN)[0] + start
    candidates = np.where((sog[start:stop] < STOP_KN) & (dist[start:stop] <= SETTLE_NM))[0] + start
    settle = np.timedelta64(SETTLE_MINUTES, "m")
    for c in candidates:
        k = np.searchsorted(moving, c, side="right")
        if k == len(moving) or t[moving[k]] - t[c] >= settle:
            return int(moving[k - 1]) if k > 0 else start
    return None


def _last_long_stop_end(t: np.ndarray, sog: np.ndarray, start: int, stop: int):
    """When the ship moved off after its last stop of LONG_STOP_MINUTES or more among pings [start, stop);
    None if it made no such stop."""
    moving = np.where(np.nan_to_num(sog[start:stop], nan=-1.0) >= STOP_KN)[0] + start
    if len(moving) < 2:
        return None
    long_gaps = np.where(t[moving[1:]] - t[moving[:-1]] >= np.timedelta64(LONG_STOP_MINUTES, "m"))[0]
    return pd.Timestamp(t[moving[long_gaps[-1] + 1]]) if len(long_gaps) else None


def extract_voyages(df: pd.DataFrame, day: str,
                    arrival_file: int | None = None) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Extract all valid labelled approach voyages arriving on one day.

    ``df`` may hold the neighbouring days' pings too, with a ``file_idx`` column:
    then only calls whose moored status starts in file ``arrival_file`` are taken,
    and only that file's pings become rows. Pings must already be cleaned
    (:func:`data_loader.clean_ais`).
    """
    point_frames, meta_rows = [], []

    for mmsi, track in df.groupby("mmsi"):
        track = track.sort_values("date_time_utc").reset_index(drop=True)
        t = track["date_time_utc"].values
        sog = pd.to_numeric(track["sog"], errors="coerce").to_numpy(dtype=float)
        lat, lon = track["lat"].to_numpy(dtype=float), track["lon"].to_numpy(dtype=float)
        file_idx = track["file_idx"].to_numpy() if arrival_file is not None else None
        events = _mooring_events(track)
        prev_end = track["date_time_utc"].iloc[0]

        for ev in events:
            lookback = ev["arrival_time"] - pd.Timedelta(hours=MAX_LOOKBACK_HOURS)
            start = max(prev_end, lookback)
            prev_end = ev["end_time"]
            if file_idx is not None and file_idx[np.searchsorted(t, np.datetime64(ev["arrival_time"]))] != arrival_file:
                continue  # a call arriving on a neighbouring day is that day's
            approach = track[(track["date_time_utc"] >= start)
                             & (track["date_time_utc"] < ev["arrival_time"])]
            if file_idx is not None:
                approach = approach[approach["file_idx"] == arrival_file]

            if len(approach) < MIN_VOYAGE_POINTS:
                continue
            # must have actually moved, and started far from the berth
            origin = approach.iloc[0]
            disp = haversine_nm(origin["lat"], origin["lon"],
                                ev["dest_lat"], ev["dest_lon"])
            if disp < MIN_DISPLACEMENT_NM:
                continue
            if not (approach["sog"].fillna(0) > MIN_TRANSIT_SOG).any():
                continue

            # the stop: searched from the approach start to the end of the moored status
            i0 = int(np.searchsorted(t, np.datetime64(start)))
            i1 = int(np.searchsorted(t, np.datetime64(ev["end_time"]), side="right"))
            dist = haversine_nm(lat, lon, ev["dest_lat"], ev["dest_lon"])
            stop_idx = _physical_arrival(t, sog, dist, i0, i1)
            stopped_at = pd.Timestamp(t[stop_idx]) if stop_idx is not None else ev["arrival_time"]
            stop_end = int(np.searchsorted(t, np.datetime64(stopped_at), side="right"))
            ev = ev | {"stopped_at": stopped_at, "long_stop_end": _last_long_stop_end(t, sog, i0, stop_end)}

            seq = _resample_voyage(approach, ev, day, int(mmsi))
            if len(seq) < MIN_VOYAGE_POINTS:
                continue
            point_frames.append(seq)
            meta_rows.append({
                "voyage_id": seq["voyage_id"].iloc[0],
                "mmsi": int(mmsi),
                "day": day,
                "split": _split_for(int(mmsi)),
                "n_points": len(seq),
                "displacement_nm": round(float(disp), 1),
                "voyage_minutes": round(float(seq["minutes_to_arrival"].max()), 1),
                "dest_lat": ev["dest_lat"],
                "dest_lon": ev["dest_lon"],
                "moored_at": ev["arrival_time"],
                "stopped_at": stopped_at,
                "stop_found": stop_idx is not None,
                "idle_minutes": round((ev["arrival_time"] - stopped_at).total_seconds() / 60, 1),
                "long_stop": ev["long_stop_end"] is not None,
            })

    points = pd.concat(point_frames, ignore_index=True) if point_frames else pd.DataFrame()
    meta = pd.DataFrame(meta_rows)
    return points, meta


def _resample_voyage(approach: pd.DataFrame, ev: dict, day: str, mmsi: int) -> pd.DataFrame:
    """Resample one approach to a fixed step and compute features + targets."""
    cols = ["lat", "lon", "sog", "cog"]
    seg = (approach.set_index("date_time_utc")[cols]
           .resample(RESAMPLE).first().dropna(subset=["lat", "lon"]))
    if seg.empty:
        return seg.reset_index()

    seg = seg.reset_index()
    to_stop = (ev["stopped_at"] - seg["date_time_utc"]).dt.total_seconds() / 60
    seg["minutes_to_arrival"] = to_stop.clip(lower=0)
    seg["after_stop"] = to_stop <= 0
    seg["minutes_to_moored"] = (ev["arrival_time"] - seg["date_time_utc"]).dt.total_seconds() / 60
    seg["before_last_long_stop"] = (seg["date_time_utc"] < ev["long_stop_end"]
                                    if ev["long_stop_end"] is not None else False)

    seg = add_time_features(seg)

    # static vessel attribute (constant per voyage; 0 already cleaned to NaN).
    # Vessel size is a standard ETA feature (Jiang et al. 2026 review).
    seg["length"] = approach["length"].median()

    # destination-conditioned features (berth is known)
    seg["dest_lat"], seg["dest_lon"] = ev["dest_lat"], ev["dest_lon"]
    seg["distance_to_dest_nm"] = haversine_nm(seg["lat"], seg["lon"],
                                              ev["dest_lat"], ev["dest_lon"])
    seg["bearing_to_dest_deg"] = bearing_deg(seg["lat"], seg["lon"],
                                             ev["dest_lat"], ev["dest_lon"])
    # trajectory (bonus) target: the next resampled position
    seg["next_lat"] = seg["lat"].shift(-1)
    seg["next_lon"] = seg["lon"].shift(-1)

    seg.insert(0, "voyage_id", f"{day}_{mmsi}_{ev['arrival_time']:%H%M%S}")
    seg.insert(1, "mmsi", mmsi)
    seg["split"] = _split_for(mmsi)
    return seg


def _load_day(path: Path, file_idx: int) -> pd.DataFrame:
    df = dl.clean_ais(dl.load_file(path.name))
    df["file_idx"] = file_idx
    return df


def build_dataset(n_files: int | None = None) -> tuple[pd.DataFrame, pd.DataFrame]:
    files = sorted(dl.list_files(), key=lambda f: pd.read_parquet(f, columns=["date_time_utc"])
                   ["date_time_utc"].iloc[0])
    if n_files is not None:
        files = files[:n_files]

    loaded: dict[int, pd.DataFrame] = {}

    def day_of(i: int) -> pd.Timestamp:
        if i not in loaded:
            loaded[i] = _load_day(files[i], i)
        return pd.Timestamp(loaded[i]["date_time_utc"].iloc[0]).normalize()

    all_points, all_meta = [], []
    for i, f in enumerate(files):
        for k in [k for k in loaded if k < i - 1]:
            del loaded[k]
        day = day_of(i)
        parts = [loaded[i]]
        # the neighbouring days, when the data has them: a call is seen whole across midnight
        if i > 0 and day_of(i - 1) == day - pd.Timedelta(days=1):
            parts.insert(0, loaded[i - 1])
        if i + 1 < len(files) and day_of(i + 1) == day + pd.Timedelta(days=1):
            parts.append(loaded[i + 1])
        df = pd.concat(parts, ignore_index=True)
        df = df[df["mmsi"].isin(loaded[i]["mmsi"].unique())]
        points, meta = extract_voyages(df, str(day.date()), arrival_file=i)
        all_points.append(points)
        all_meta.append(meta)
        n_v = 0 if meta.empty else len(meta)
        print(f"  {f.name} {day.date()}: {n_v} voyages, {len(points):,} points")

    points = pd.concat(all_points, ignore_index=True)
    meta = pd.concat(all_meta, ignore_index=True)
    return points, meta


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--n-files", type=int, default=3,
                    help="number of parquet files to process (default 3; omit cap with -1)")
    args = ap.parse_args()
    n = None if args.n_files == -1 else args.n_files

    PROCESSED_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Building voyage dataset from {n or 'all'} file(s)...")
    points, meta = build_dataset(n)

    points_path = PROCESSED_DIR / "voyage_features.parquet"
    meta_path = PROCESSED_DIR / "voyages_meta.parquet"
    points.to_parquet(points_path, index=False)
    meta.to_parquet(meta_path, index=False)

    print(f"\nTotal voyages: {len(meta)}")
    print(f"Total labelled points: {len(points):,}")
    print("Split (by vessel) - voyages per split:")
    print(meta["split"].value_counts())
    print(f"Median voyage length: {meta['voyage_minutes'].median():.0f} min, "
          f"median displacement: {meta['displacement_nm'].median():.1f} nm")
    print(f"Moored status after the stop: median {meta['idle_minutes'].median():.1f} min; "
          f"stop not found (moored time used): {int((~meta['stop_found']).sum())}; "
          f"with a stop of {LONG_STOP_MINUTES}+ min part-way: {int(meta['long_stop'].sum())}")
    print(f"\nSaved:\n  {points_path}\n  {meta_path}")


if __name__ == "__main__":
    main()

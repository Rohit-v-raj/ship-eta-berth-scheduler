"""Load and clean raw AIS parquet data for the Port ML project.

The raw files live in ``data/parquets/`` named ``2020-2_group_N`` (no file
extension, but they are Parquet). Each file holds roughly one day of
high-frequency AIS position reports for vessels along the Norwegian west coast
(lon ~4-8, lat ~61-63). There are ~30M rows across all 40 files.

Schema (11 columns)::

    mmsi           int    vessel identifier (Maritime Mobile Service Identity)
    imo_nr         int    IMO registration number
    length         int    ship length in metres (0 == unknown)
    date_time_utc  str    timestamp of the AIS ping (parsed to datetime here)
    lon, lat       float  position
    sog            float  Speed Over Ground, knots
    cog            float  Course Over Ground, degrees
    true_heading   int    heading, degrees (511 == not available)
    nav_status     int    navigational status code (see NAV_STATUS)
    message_nr     int    AIS message type (1, 3 == Class A position report)

AIS encodes "value not available" as specific numbers rather than nulls. These
appear as legitimate-looking values and MUST be handled before modelling:

    sog == 102.3   -> speed not available
    cog == 360.0   -> course not available
    true_heading == 511 -> heading not available
    length == 0    -> length unknown
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "parquets"

# AIS "not available" sentinel values that masquerade as real numbers.
SOG_NA = 102.3
COG_NA = 360.0
HEADING_NA = 511
LENGTH_NA = 0

# Navigational status codes (ITU-R M.1371). Only the ones observed in this
# dataset are listed; 5 (moored) is the key signal for berthing/arrival.
NAV_STATUS = {
    0: "under way using engine",
    1: "at anchor",
    3: "restricted manoeuvrability",
    5: "moored",
    7: "engaged in fishing",
    8: "under way sailing",
    15: "undefined / default",
}

RAW_COLUMNS = [
    "mmsi", "imo_nr", "length", "date_time_utc",
    "lon", "lat", "sog", "cog", "true_heading",
    "nav_status", "message_nr",
]


def list_files() -> list[Path]:
    """Return the parquet files sorted by their numeric group suffix."""
    files = DATA_DIR.glob("2020-2_group_*")
    return sorted(files, key=lambda p: int(p.name.split("_")[-1]))


def load_file(which: int | str, columns: list[str] | None = None,
              parse_time: bool = True) -> pd.DataFrame:
    """Load a single parquet file.

    ``which`` may be an integer index into :func:`list_files` or a file name
    such as ``"2020-2_group_3"``.
    """
    if isinstance(which, int):
        path = list_files()[which]
    else:
        path = DATA_DIR / which
    df = pd.read_parquet(path, columns=columns)
    if parse_time and "date_time_utc" in df.columns:
        df["date_time_utc"] = pd.to_datetime(df["date_time_utc"])
    return df


def load_sample(n_files: int = 1, columns: list[str] | None = None,
                parse_time: bool = True) -> pd.DataFrame:
    """Load and concatenate the first ``n_files`` parquet files."""
    frames = [pd.read_parquet(f, columns=columns) for f in list_files()[:n_files]]
    df = pd.concat(frames, ignore_index=True)
    if parse_time and "date_time_utc" in df.columns:
        df["date_time_utc"] = pd.to_datetime(df["date_time_utc"])
    return df


def load_all(columns: list[str] | None = None,
             parse_time: bool = True) -> pd.DataFrame:
    """Load every parquet file into one DataFrame.

    Warning: this is ~30M rows. Pass ``columns`` to limit memory use.
    """
    frames = [pd.read_parquet(f, columns=columns) for f in list_files()]
    df = pd.concat(frames, ignore_index=True)
    if parse_time and "date_time_utc" in df.columns:
        df["date_time_utc"] = pd.to_datetime(df["date_time_utc"])
    return df


def clean_ais(df: pd.DataFrame) -> pd.DataFrame:
    """Replace AIS sentinel values with ``NaN`` so they are not modelled as real.

    Returns a copy; the input is not modified.
    """
    df = df.copy()
    if "sog" in df:
        df.loc[df["sog"] == SOG_NA, "sog"] = pd.NA
    if "cog" in df:
        df.loc[df["cog"] == COG_NA, "cog"] = pd.NA
    if "true_heading" in df:
        df.loc[df["true_heading"] == HEADING_NA, "true_heading"] = pd.NA
    if "length" in df:
        df.loc[df["length"] == LENGTH_NA, "length"] = pd.NA
    return df


def get_vessel_track(df: pd.DataFrame, mmsi: int) -> pd.DataFrame:
    """Return one vessel's pings, time-sorted, as a clean trajectory."""
    track = df[df["mmsi"] == mmsi].copy()
    if "date_time_utc" in track.columns:
        track = track.sort_values("date_time_utc")
    return track.reset_index(drop=True)


if __name__ == "__main__":
    # Quick smoke test against the first file.
    df = load_file(0)
    print(f"Loaded {len(df):,} rows, {df['mmsi'].nunique()} vessels")
    print(f"Time range: {df['date_time_utc'].min()} -> {df['date_time_utc'].max()}")
    cleaned = clean_ais(df)
    print("Nulls after clean_ais:")
    print(cleaned[["sog", "cog", "true_heading", "length"]].isna().sum())

#!/usr/bin/env python3
"""
Aggregate per-trial replay results from Santos, NONAN, MAREA, and Luo into
web/public/validation-summary.json for the web showcase.

Per-dataset metric shapes differ:
  - Santos, NONAN: against-ground-truth error percentages (cadence MAE, stride
    length MAE) plus ICC(3,1) absolute agreement where the dataset has a
    repeated-session structure.
  - MAREA: cadence MAE only (no spatial ground truth in this accelerometer-only
    dataset). No ICC (single-session protocol).
  - Luo: cross-sensor agreement (trunk vs left shank) reported as mean
    difference, 95 percent Bland-Altman limits of agreement, mean absolute
    difference, and Pearson correlation. The Luo dataset does not publish
    per-trial spatiotemporal ground truth, which is documented as a limitation
    in the paper's Discussion.

Datasets with absent results CSVs get a "pending" status placeholder so the
web build does not break while MAREA or NONAN are still in flight.
"""
import argparse
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

try:
    import pingouin as pg
    HAVE_PINGOUIN = True
except ImportError:
    HAVE_PINGOUIN = False


DATASETS = [
    {
        "key": "santos",
        "name": "Santos et al. 2022",
        "citation": "Santos et al., A multi-sensor human gait dataset, Sci Data 2022.",
        "doi": "10.6084/m9.figshare.14727231",
        "mount": "Leg strap (smartphone)",
        "n_participants": 25,
        "sample_rate_hz": 100,
        "validation_kind": "ground_truth_error",
        "results_filename": "santos-replay-results.csv",
        "has_repeated_sessions": True,
        "has_stride_length_truth": True,
        "caveats": [
            "Pipeline is calibrated for front-pocket mount; under reads stride length on leg-strap mount. ICC of 0.803 on stride length confirms the bias is reproducible across sessions, not noisy.",
        ],
    },
    {
        "key": "nonan",
        "name": "NONAN GaitPrint young adults",
        "citation": "Wiles et al., NONAN GaitPrint, Sci Data 2023.",
        "doi": "10.1038/s41597-023-02704-z",
        "mount": "Pelvis (sacrum, near-pocket analog)",
        "n_participants": 35,
        "sample_rate_hz": 200,
        "validation_kind": "ground_truth_error",
        "results_filename": "nonan-replay-results.csv",
        "has_repeated_sessions": True,
        "has_stride_length_truth": True,
        "caveats": [
            "Pelvis sensor is the closest production analog (front-pocket mount). Test-retest ICC is reported across the dataset's two-session structure.",
        ],
    },
    {
        "key": "marea",
        "name": "MAREA",
        "citation": "Khandelwal and Wickstrom, Gait Posture 2017.",
        "doi": "10.1016/j.gaitpost.2016.09.023",
        "mount": "Waist (accelerometer-only)",
        "n_participants": 20,
        "sample_rate_hz": 128,
        "validation_kind": "ground_truth_error",
        "results_filename": "marea-replay-results.csv",
        "has_repeated_sessions": False,
        "has_stride_length_truth": False,
        # treadIncline is the longest recording per subject and would otherwise be
        # roughly 44 percent of the trials, so the headline figure is level walking
        # and inclined walking is reported separately. No other dataset in this
        # table contains inclined walking, so pooling would make this row describe
        # a different regime from the three it sits beside.
        "subgroup_column": "activity",
        "subgroups": {
            "level": ["treadWalk", "indoorWalk", "outdoorWalk"],
            "incline": ["treadIncline"],
        },
        "headline_subgroup": "level",
        "caveats": [
            "Accelerometer-only IMU; Madgwick filter degrades to accelerometer-only attitude. Stride length not reported (no spatial ground truth).",
            "Headline cadence error covers level walking only (treadmill, indoor, outdoor). Inclined treadmill walking is reported separately under by_subgroup; no other dataset in this table includes inclined walking.",
            "Ground truth is heel strike timing from foot mounted force sensitive resistors. Cadence truth is derived from the interval between consecutive heel strikes rather than a step count over a fixed window, which avoids quantizing truth to 2 steps per minute at the 30 second trial length.",
        ],
    },
    {
        "key": "luo",
        "name": "Luo et al. 2020 (irregular surfaces)",
        "citation": "Luo et al., A database of human gait performance on irregular and uneven surfaces, Sci Data 2020.",
        "doi": "10.1038/s41597-020-0563-y",
        "mount": "Trunk (production analog) + Left shank (cross-sensor reference)",
        "n_participants": 30,
        "sample_rate_hz": 100,
        "validation_kind": "cross_sensor_agreement",
        "results_filename": "luo-replay-results.csv",
        "has_repeated_sessions": False,
        "has_stride_length_truth": False,
        "caveats": [
            "Dataset does not publish per-trial spatiotemporal ground truth (Discussion acknowledges this as a limitation). Validated via cross-sensor cadence agreement between trunk (production analog) and left shank (independent reference) running the same pipeline.",
            "9 outdoor surfaces sampled: flat even, cobble stone, stairs up/down, slope up/down, bank left/right, grass.",
        ],
    },
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--results-root", default="app/build/validation")
    parser.add_argument("--output", default="web/public/validation-summary.json")
    args = parser.parse_args()

    results_root = Path(args.results_root)
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    out = {
        "generated_at_utc": pd.Timestamp.utcnow().isoformat(),
        "datasets": [],
    }
    for ds in DATASETS:
        entry = {
            "key": ds["key"],
            "name": ds["name"],
            "citation": ds["citation"],
            "doi": ds["doi"],
            "mount": ds["mount"],
            "n_participants": ds["n_participants"],
            "sample_rate_hz": ds["sample_rate_hz"],
            "validation_kind": ds["validation_kind"],
            "caveats": ds["caveats"],
        }
        csv_path = results_root / ds["results_filename"]
        if not csv_path.exists():
            entry["status"] = "pending"
            entry["n_trials_processed"] = 0
            entry["metrics"] = {}
            out["datasets"].append(entry)
            continue
        df = pd.read_csv(csv_path)
        entry["status"] = "complete"
        entry["n_trials_processed"] = int(len(df))
        if ds["validation_kind"] == "ground_truth_error":
            entry["metrics"] = compute_ground_truth_metrics(df, ds)
        elif ds["validation_kind"] == "cross_sensor_agreement":
            entry["metrics"] = compute_cross_sensor_metrics(df)
        else:
            entry["metrics"] = {}
        out["datasets"].append(entry)

    with open(output_path, "w") as f:
        json.dump(out, f, indent=2)
    print(f"wrote {output_path}")
    for ds in out["datasets"]:
        m = ds.get("metrics", {})
        if not m:
            print(f"  {ds['key']:7s} {ds['status']}")
        else:
            summary = ", ".join(f"{k}={v}" for k, v in m.items() if v is not None)
            print(f"  {ds['key']:7s} {ds['status']:9s} n={ds['n_trials_processed']:4d}  {summary}")


def cadence_stats(df: pd.DataFrame) -> dict:
    if "cadence_pct_error" not in df.columns:
        return {}
    err = df["cadence_pct_error"].dropna().abs()
    if len(err) == 0:
        return {}
    return {
        "n_trials": int(len(err)),
        "cadence_mae_pct": round(float(np.mean(err)), 2),
        "cadence_median_pct": round(float(np.median(err)), 2),
        "cadence_p95_pct": round(float(np.percentile(err, 95)), 2),
    }


def compute_ground_truth_metrics(df: pd.DataFrame, ds: dict) -> dict:
    metrics = {}
    subgroup_col = ds.get("subgroup_column")
    subgroups = ds.get("subgroups")
    if subgroup_col and subgroups and subgroup_col in df.columns:
        by_subgroup = {}
        for label, activities in subgroups.items():
            stats = cadence_stats(df[df[subgroup_col].isin(activities)])
            if stats:
                by_subgroup[label] = stats
        pooled = cadence_stats(df)
        if pooled:
            by_subgroup["pooled"] = pooled
        metrics["by_subgroup"] = by_subgroup
        # The headline stays at the top level so this row reads the same way as the
        # rows beside it, but it describes the headline subgroup rather than the
        # pooled sample. by_subgroup carries the rest.
        headline = by_subgroup.get(ds.get("headline_subgroup", ""), {})
        metrics.update({k: v for k, v in headline.items() if k != "n_trials"})
        metrics["headline_subgroup"] = ds.get("headline_subgroup")
        metrics["headline_n_trials"] = headline.get("n_trials")
    elif "cadence_pct_error" in df.columns:
        stats = cadence_stats(df)
        metrics.update({k: v for k, v in stats.items() if k != "n_trials"})
    if ds["has_stride_length_truth"] and "stride_length_pct_error" in df.columns:
        stride_err = df["stride_length_pct_error"].dropna().abs()
        if len(stride_err) > 0:
            metrics["stride_length_mae_pct"] = round(float(np.mean(stride_err)), 2)
            metrics["stride_length_median_pct"] = round(float(np.median(stride_err)), 2)
    if ds["has_repeated_sessions"] and HAVE_PINGOUIN:
        icc_cadence = icc_3_1(df, target_col="cadence_recovered")
        if icc_cadence is not None:
            metrics["cadence_icc_3_1"] = round(icc_cadence, 3)
        if ds["has_stride_length_truth"]:
            icc_stride = icc_3_1(df, target_col="stride_length_recovered_m")
            if icc_stride is not None:
                metrics["stride_length_icc_3_1"] = round(icc_stride, 3)
    return metrics


def compute_cross_sensor_metrics(df: pd.DataFrame) -> dict:
    if "cadence_trunk" not in df.columns or "cadence_shank" not in df.columns:
        return {}
    trunk = df["cadence_trunk"].astype(float)
    shank = df["cadence_shank"].astype(float)
    diff = trunk - shank
    abs_diff = diff.abs()
    mean_d = float(diff.mean())
    sd_d = float(diff.std())
    return {
        "mean_cadence_trunk_spm": round(float(trunk.mean()), 2),
        "mean_cadence_shank_spm": round(float(shank.mean()), 2),
        "mean_difference_spm": round(mean_d, 2),
        "sd_difference_spm": round(sd_d, 2),
        "loa_lower_spm": round(mean_d - 1.96 * sd_d, 2),
        "loa_upper_spm": round(mean_d + 1.96 * sd_d, 2),
        "mean_absolute_difference_spm": round(float(abs_diff.mean()), 2),
        "pearson_r": round(float(trunk.corr(shank)), 3),
    }


def icc_3_1(df: pd.DataFrame, target_col: str):
    if not HAVE_PINGOUIN:
        return None
    if target_col not in df.columns:
        return None
    sub = df[["participant", "session", target_col]].dropna()
    if len(sub) == 0 or sub["session"].nunique() < 2:
        return None
    try:
        icc = pg.intraclass_corr(
            data=sub,
            targets="participant",
            raters="session",
            ratings=target_col,
            nan_policy="omit",
        )
        row = icc[icc["Type"] == "ICC3"]
        if row.empty:
            return None
        return float(row.iloc[0]["ICC"])
    except Exception:
        return None


if __name__ == "__main__":
    main()

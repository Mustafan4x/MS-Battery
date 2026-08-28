#!/usr/bin/env python3
"""
Preprocess the MAREA gait database (Khandelwal and Wickstrom 2017, Gait and
Posture 51, pages 84 to 90, DOI 10.1016/j.gaitpost.2016.09.023) into the layout
the Kotlin MareaReplayTest consumes.

MAREA differs from the other three validated datasets in two ways that shape
this script.

First, it is accelerometer only. There is no gyroscope channel at any of the
four sensor placements. GaitPipeline drives Madgwick with both, so the emitted
smartphone.csv carries a zero gyroscope vector. Madgwick.kt documents that with
zero gyro the filter degenerates to gravity alignment from the accelerometer
alone and still converges at beta = 0.1, which is the same path the synthetic
fixtures exercise. This is a real methodological deviation and the reason the
MAREA row reports cadence only and no stride length.

Second, the ground truth event indices in GroundTruth.mat are SEGMENT RELATIVE,
1 based from the start of each activity, not absolute positions in the full
recording. treadWalk and outdoorWalk hide this completely because their segments
begin at sample 1, so relative and absolute coincide for exactly those two.
Assuming absolute indices yields zero matched events for treadIncline and
indoorWalk while treadWalk and outdoorWalk still look correct, which is a silent
partial failure rather than a loud one. See resolve_events below.

Ground truth comes from foot mounted force sensitive resistors, giving explicit
heel strike and toe off sample indices. The events are body events on a sample
clock shared across all four sensor placements, so heel strikes recorded by the
foot FSRs are valid cadence ground truth for the waist accelerometer signal.

Only level and inclined walking is emitted. The three activities whose names end
in Run (treadWalknRun, indoorWalknRun, outdoorWalknRun) are supersets that append
a running bout to the corresponding walk, and this pipeline validates walking.

Acceleration is already in m/s^2, matching what Android reports for
Sensor.TYPE_ACCELEROMETER and what GaitPipeline expects, so no unit conversion is
applied. Verified empirically: mean signal magnitude is 10.19 quasi static and
10.3 to 10.5 during treadmill walking, against roughly 1.0 had the units been g.

Output:
    <output_root>/participant-NN/session-1/trial-NNN/
        smartphone.csv     timestamp_ns, ax, ay, az, gx, gy, gz (gyro always 0)
        ground-truth.csv   cadence_steps_per_minute, activity, steps, duration_s

    <output_root>/manifest.csv  one row per emitted trial, for aggregate reporting

Every subject contributes a single session, so MAREA supports cadence error
reporting but not the test retest ICC that NONAN's two sessions support.

Usage:
    ./marea-preprocess.py \
        --raw-root  "$SSD/marea-gait-database/raw/MAREA_dataset" \
        --output-root "$SSD/marea-gait-database/normalized"
"""
import argparse
import csv
import sys
from pathlib import Path

import numpy as np
import scipy.io as sio

SAMPLE_RATE_HZ = 128.0
NS_PER_S = 1_000_000_000

INDOOR_SUBJECTS = range(1, 12)
OUTDOOR_SUBJECTS = range(12, 21)

# MATLAB 1 based column pairs into the timings matrices, from the dataset's own
# mainScript.m. Walking only; the *WalknRun supersets are deliberately absent.
INDOOR_ACTIVITIES = {"treadWalk": (1, 2), "treadIncline": (4, 5), "indoorWalk": (6, 7)}
OUTDOOR_ACTIVITIES = {"outdoorWalk": (1, 2)}


def load_inputs(raw_root: Path):
    timings = raw_root / "Activity Timings"
    indoor = sio.loadmat(timings / "Indoor Experiment Timings.mat", squeeze_me=True)["indoorTime"]
    outdoor = sio.loadmat(timings / "Outdoor Experiment Timings.mat", squeeze_me=True)["outdoorTime"]
    ground_truth = sio.loadmat(
        raw_root / "GroundTruth.mat", struct_as_record=False, squeeze_me=True
    )["GroundTruth"]
    return indoor, outdoor, ground_truth


def resolve_events(ground_truth, gt_index: int, activity: str, segment_len: int):
    """Heel strike indices for one activity, as 0 based offsets into the segment.

    The stored indices are 1 based offsets from the segment start. Anything
    outside [1, segment_len] means the segment relative assumption has broken for
    this entry, so the caller is told rather than silently given a short count.
    """
    entry = getattr(ground_truth[gt_index], activity, None)
    if entry is None:
        return None, "activity absent from GroundTruth"
    left = np.atleast_1d(getattr(entry, "LF_HS", np.array([]))).astype(np.int64)
    right = np.atleast_1d(getattr(entry, "RF_HS", np.array([]))).astype(np.int64)
    events = np.sort(np.concatenate([left, right]))
    if events.size == 0:
        return None, "no heel strike events"
    outside = int(((events < 1) | (events > segment_len)).sum())
    if outside:
        return None, "%d of %d events fall outside the segment" % (outside, events.size)
    return events - 1, None


def cadence_from_events(events: np.ndarray) -> float:
    """Steps per minute from the mean interval between consecutive heel strikes.

    Counting events and dividing by a fixed window length quantizes the result to
    60 / window_seconds, which is 2 spm at the default 30 second window, roughly
    0.9 percent at a typical 114 spm. That granularity would sit on top of the
    error being measured. Spanning the observed events instead keeps the value
    continuous and matches how the other datasets derive their cadence truth.
    """
    return 60.0 * (events.size - 1) / ((events[-1] - events[0]) / SAMPLE_RATE_HZ)


def write_trial(trial_dir: Path, samples: np.ndarray, cadence: float, counted_cadence: float,
                activity: str, steps: int, duration_s: float) -> None:
    trial_dir.mkdir(parents=True, exist_ok=True)
    with open(trial_dir / "smartphone.csv", "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["timestamp_ns", "ax", "ay", "az", "gx", "gy", "gz"])
        step_ns = int(round(NS_PER_S / SAMPLE_RATE_HZ))
        for i, (ax, ay, az) in enumerate(samples):
            writer.writerow([i * step_ns, ax, ay, az, 0.0, 0.0, 0.0])
    with open(trial_dir / "ground-truth.csv", "w", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["cadence_steps_per_minute", "cadence_counted_steps_per_minute",
                         "activity", "steps", "duration_s"])
        writer.writerow(["%.6f" % cadence, "%.6f" % counted_cadence,
                         activity, steps, "%.6f" % duration_s])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-root", required=True,
                        help="The extracted MAREA_dataset directory")
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--window-seconds", type=float, default=30.0,
                        help="Trial length. Defaults to 30 to match the SPEC Section 6.2 "
                             "gait capture duration. Pass 0 to emit whole segments.")
    parser.add_argument("--min-steps", type=int, default=10,
                        help="Drop windows with fewer heel strikes than this")
    parser.add_argument("--include-incline", dest="include_incline",
                        action="store_true", default=True,
                        help="Include treadIncline segments (default)")
    parser.add_argument("--exclude-incline", dest="include_incline", action="store_false")
    args = parser.parse_args()

    raw_root = Path(args.raw_root).expanduser()
    out_root = Path(args.output_root).expanduser()
    subject_dir = raw_root / "Subject Data_txt format"
    if not subject_dir.is_dir():
        sys.exit("no 'Subject Data_txt format' under %s" % raw_root)

    indoor, outdoor, ground_truth = load_inputs(raw_root)
    window_n = int(round(args.window_seconds * SAMPLE_RATE_HZ)) if args.window_seconds > 0 else 0

    manifest = []
    skipped = []
    for subject in list(INDOOR_SUBJECTS) + list(OUTDOOR_SUBJECTS):
        is_indoor = subject in INDOOR_SUBJECTS
        activities = INDOOR_ACTIVITIES if is_indoor else OUTDOOR_ACTIVITIES
        timing_row = indoor[subject - 1] if is_indoor else outdoor[subject - 12]
        # Outdoor subjects 12 to 20 index GroundTruth at subNo minus 11, per mainScript.m.
        gt_index = subject - 1 if is_indoor else subject - 12

        waist = subject_dir / ("Sub%d_Waist.txt" % subject)
        if not waist.is_file():
            skipped.append((subject, "-", "waist file missing"))
            continue
        signal = np.loadtxt(waist, delimiter=",", skiprows=1)

        trial_no = 0
        for activity, (col_lo, col_hi) in activities.items():
            if activity == "treadIncline" and not args.include_incline:
                continue
            lo, hi = int(timing_row[col_lo - 1]), int(timing_row[col_hi - 1])
            segment = signal[lo - 1:hi]
            segment_len = segment.shape[0]

            events, problem = resolve_events(ground_truth, gt_index, activity, segment_len)
            if problem is not None:
                skipped.append((subject, activity, problem))
                continue

            span = window_n if window_n else segment_len
            # A trailing partial window would divide its step count by a full
            # window duration, biasing cadence low, so it is dropped.
            for start in range(0, segment_len - span + 1, span):
                stop = start + span
                in_window = events[(events >= start) & (events < stop)]
                steps = int(in_window.size)
                if steps < args.min_steps:
                    skipped.append((subject, activity, "window at %d has %d steps" % (start, steps)))
                    continue
                duration_s = span / SAMPLE_RATE_HZ
                cadence = cadence_from_events(in_window)
                counted_cadence = steps / duration_s * 60.0
                trial_no += 1
                trial_dir = (out_root / ("participant-%02d" % subject) / "session-1"
                             / ("trial-%03d" % trial_no))
                write_trial(trial_dir, segment[start:stop], cadence, counted_cadence,
                            activity, steps, duration_s)
                manifest.append({
                    "participant": "participant-%02d" % subject,
                    "session": "session-1",
                    "trial": "trial-%03d" % trial_no,
                    "group": "indoor" if is_indoor else "outdoor",
                    "activity": activity,
                    "start_sample_in_segment": start,
                    "samples": span,
                    "duration_s": "%.6f" % duration_s,
                    "steps": steps,
                    "cadence_steps_per_minute": "%.6f" % cadence,
                    "cadence_counted_steps_per_minute": "%.6f" % counted_cadence,
                })

    out_root.mkdir(parents=True, exist_ok=True)
    with open(out_root / "manifest.csv", "w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(manifest[0].keys()) if manifest else ["participant"])
        writer.writeheader()
        writer.writerows(manifest)

    cadences = [float(row["cadence_steps_per_minute"]) for row in manifest]
    participants = sorted({row["participant"] for row in manifest})
    print("wrote %d trials across %d participants to %s" % (len(manifest), len(participants), out_root))
    if cadences:
        print("ground truth cadence: %.1f to %.1f spm, median %.1f"
              % (min(cadences), max(cadences), float(np.median(cadences))))
    by_activity = {}
    for row in manifest:
        by_activity.setdefault(row["activity"], []).append(float(row["cadence_steps_per_minute"]))
    for activity, values in sorted(by_activity.items()):
        print("  %-13s %4d trials, cadence %.1f to %.1f, median %.1f"
              % (activity, len(values), min(values), max(values), float(np.median(values))))
    if skipped:
        print("\nskipped %d:" % len(skipped))
        for subject, activity, reason in skipped[:10]:
            print("  Sub%s %s: %s" % (subject, activity, reason))
        if len(skipped) > 10:
            print("  ... and %d more" % (len(skipped) - 10))


if __name__ == "__main__":
    main()

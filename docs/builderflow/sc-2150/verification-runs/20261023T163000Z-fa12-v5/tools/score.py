#!/usr/bin/env python3
"""SC-2150 FA12 scorer: compares harness predictions with corpus labels.

Gates (frozen in fedora-implementation-and-qualification-plan.md section 10, 1080p-equivalent):
  median position error <= 4 px, p95 <= 12 px, visible recall >= 99%, absent-span false
  detections <= 1%; cuts F1 >= 0.90 within one indexed source-frame tolerance.

Usage: score.py --corpus DIR --split dev|heldout --predictions DIR [--compare DIR] --out FILE
"""

import argparse
import glob
import json
import math
import os
import random
import sys

# Scoring classes (fixed before held-out evaluation): >= half the sprite visible counts for recall
# and error; nothing visible counts for false detections; partially visible frames count for
# neither. Supported clips are decided by geometry (harness "geometry_resolvable"), never by output.
VISIBLE_STATES = {"VISIBLE", "VISIBLE_CLIPPED", "PARTIALLY_OCCLUDED", "UNDER_TRANSLUCENT_OVERLAY",
                  "HOTSPOT_OCCLUDED"}
ABSENT_STATES = {"ABSENT", "OCCLUDED"}
GATES = {"median_px": 4.0, "p95_px": 12.0, "recall": 0.99, "absent_false_rate": 0.01,
         "cut_f1": 0.90}


def percentile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    low = math.floor(position)
    high = math.ceil(position)
    if low == high:
        return ordered[low]
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def match_clip(label_doc, prediction_doc):
    """Returns per-sampled-frame rows joined with their ground truth."""
    labels = [entry for entry in label_doc["labels"] if not entry.get("dropped")]
    by_time = sorted(labels, key=lambda entry: entry["pts_time"])
    times = [entry["pts_time"] for entry in by_time]
    predictions = {row["ordinal"]: row for row in prediction_doc["cursor"]}
    step = prediction_doc["step"]
    scale = label_doc["px_to_1080p"]
    rows = []
    unmatched = 0
    for position, pts in enumerate(prediction_doc["sampled_pts_time"][
            :prediction_doc["sampled_frames"]]):
        pts_value = float(pts)
        # Nearest label by presentation time; must be within half a composed frame.
        lo, hi = 0, len(times)
        while lo < hi:
            mid = (lo + hi) // 2
            if times[mid] < pts_value:
                lo = mid + 1
            else:
                hi = mid
        candidates = [i for i in (lo - 1, lo) if 0 <= i < len(times)]
        best = min(candidates, key=lambda i: abs(times[i] - pts_value))
        if abs(times[best] - pts_value) > 0.5 / label_doc["fps_compose"]:
            unmatched += 1
            continue
        truth = by_time[best]
        ordinal = position * step
        predicted = predictions.get(ordinal)
        row = {"ordinal": ordinal, "state": truth["state"],
               "detected": bool(predicted and predicted.get("visibility") == "VISIBLE"),
               "confidence": predicted.get("confidence") if predicted else None}
        if row["detected"] and truth["state"] in VISIBLE_STATES:
            row["error_px_1080p"] = math.hypot(predicted["x"] - truth["x"],
                                               predicted["y"] - truth["y"]) * scale
        rows.append(row)
    return rows, unmatched


def summarise(rows):
    visible = [r for r in rows if r["state"] in VISIBLE_STATES]
    absent = [r for r in rows if r["state"] in ABSENT_STATES]
    errors = [r["error_px_1080p"] for r in visible if r["detected"]]
    detected = sum(1 for r in visible if r["detected"])
    false = sum(1 for r in absent if r["detected"])
    return {
        "visible_frames": len(visible),
        "absent_frames": len(absent),
        "detected_visible": detected,
        "recall": detected / len(visible) if visible else None,
        "median_px": percentile(errors, 0.5),
        "p95_px": percentile(errors, 0.95),
        "within_12px_rate": (sum(1 for e in errors if e <= 12) / len(visible)) if visible else None,
        "absent_false_detections": false,
        "absent_false_rate": false / len(absent) if absent else None,
    }


def cut_scores(label_doc, prediction_doc):
    fps = label_doc["fps_compose"]
    truth_times = [n / fps for n in label_doc["cut_compose_ordinals"]]
    sampled = prediction_doc["sampled_pts_time"]
    step = prediction_doc["step"]
    predicted_times = []
    for ordinal in prediction_doc["scene_cut_ordinals"]:
        position = ordinal // step
        if position < len(sampled):
            predicted_times.append(float(sampled[position]))
    # One indexed source-frame tolerance: the largest adjacent PTS gap around the boundary.
    gaps = [float(b) - float(a) for a, b in zip(sampled, sampled[1:])] or [1.0 / fps]
    tolerance = max(gaps) * step + 1e-6
    used = set()
    true_positive = 0
    for t in truth_times:
        hit = next((i for i, p in enumerate(predicted_times)
                    if i not in used and abs(p - t) <= tolerance), None)
        if hit is not None:
            used.add(hit)
            true_positive += 1
    return true_positive, len(predicted_times) - true_positive, len(truth_times) - true_positive


def bootstrap(per_clip_rows, metric, rounds=2000, seed=2150):
    rng = random.Random(seed)
    clips = list(per_clip_rows)
    values = []
    for _ in range(rounds):
        sample = []
        for _ in clips:
            sample.extend(per_clip_rows[rng.choice(clips)])
        value = summarise(sample)[metric]
        if value is not None:
            values.append(value)
    return [percentile(values, 0.025), percentile(values, 0.975)] if values else None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--split", required=True)
    parser.add_argument("--predictions", required=True)
    parser.add_argument("--compare", help="second prediction run for determinism hashes")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    per_clip = {}
    report_clips = []
    cut_tp = cut_fp = cut_fn = 0
    total_unmatched = 0
    determinism = []
    covered_seconds = 0.0
    for label_path in sorted(glob.glob(os.path.join(args.corpus, args.split, "*.labels.json"))):
        label_doc = json.load(open(label_path))
        clip_id = label_doc["clip_id"]
        prediction_path = os.path.join(args.predictions, clip_id + ".predictions.json")
        prediction_doc = json.load(open(prediction_path))
        if prediction_doc["media_sha256"] != label_doc["media_sha256"]:
            raise SystemExit("media hash mismatch for " + clip_id)
        rows, unmatched = match_clip(label_doc, prediction_doc)
        total_unmatched += unmatched
        per_clip[clip_id] = rows
        tp, fp, fn = cut_scores(label_doc, prediction_doc)
        cut_tp, cut_fp, cut_fn = cut_tp + tp, cut_fp + fp, cut_fn + fn
        covered_seconds += len(label_doc["labels"]) / label_doc["fps_compose"]
        entry = {"clip_id": clip_id, "source_id": label_doc["source_id"],
                 "variant": label_doc["variant"]["id"], "template": label_doc["template"],
                 "output_size": label_doc["output_size"], "step": prediction_doc["step"],
                 "sampled_frames": prediction_doc["sampled_frames"],
                 "unmatched_samples": unmatched, "cuts": {"tp": tp, "fp": fp, "fn": fn},
                 "analyze_millis": prediction_doc.get("analyze_millis"),
                 "geometry_resolvable": prediction_doc.get("geometry_resolvable", True),
                 "cursor_calibration": prediction_doc.get("cursor_calibration"),
                 "observations_sha256": prediction_doc["observations_sha256"]}
        entry.update(summarise(rows))
        report_clips.append(entry)
        if args.compare:
            other = json.load(open(os.path.join(args.compare, clip_id + ".predictions.json")))
            determinism.append({"clip_id": clip_id,
                                "equal": other["observations_sha256"]
                                == prediction_doc["observations_sha256"]})

    supported_ids = {e["clip_id"] for e in report_clips if e["geometry_resolvable"]}
    all_rows = [row for rows in per_clip.values() for row in rows]
    supported_per_clip = {k: v for k, v in per_clip.items() if k in supported_ids}
    supported_rows = [row for rows in supported_per_clip.values() for row in rows]
    overall = summarise(supported_rows)
    overall_all_clips = summarise(all_rows)
    precision = cut_tp / (cut_tp + cut_fp) if cut_tp + cut_fp else None
    recall = cut_tp / (cut_tp + cut_fn) if cut_tp + cut_fn else None
    cut_f1 = (2 * precision * recall / (precision + recall)
              if precision and recall else 0.0)

    def group(key):
        groups = {}
        for entry in report_clips:
            groups.setdefault(entry[key], []).extend(per_clip[entry["clip_id"]])
        return {name: summarise(rows) for name, rows in sorted(groups.items())}

    by_state = {}
    for row in all_rows:
        by_state.setdefault(row["state"], []).append(row)
    gates = {
        "median_px": overall["median_px"] is not None
        and overall["median_px"] <= GATES["median_px"],
        "p95_px": overall["p95_px"] is not None and overall["p95_px"] <= GATES["p95_px"],
        "recall": overall["recall"] is not None and overall["recall"] >= GATES["recall"],
        "absent_false_rate": overall["absent_false_rate"] is not None
        and overall["absent_false_rate"] <= GATES["absent_false_rate"],
        "cut_f1": cut_f1 >= GATES["cut_f1"],
    }
    report = {
        "schema": "sc2150-fa12-score/v1",
        "split": args.split,
        "gates": GATES,
        "gate_results": gates,
        "all_gates_pass": all(gates.values()),
        "gate_scope": "supported clips (geometry_resolvable)",
        "supported_clips": sorted(supported_ids),
        "unsupported_clips": sorted(set(per_clip) - supported_ids),
        "overall": overall,
        "overall_all_clips_including_unsupported": overall_all_clips,
        "confidence_intervals_95_clip_bootstrap": {
            metric: bootstrap(supported_per_clip, metric)
            for metric in ("median_px", "p95_px", "recall", "absent_false_rate")},
        "cuts": {"tp": cut_tp, "fp": cut_fp, "fn": cut_fn, "precision": precision,
                 "recall": recall, "f1": cut_f1},
        "covered_minutes": round(covered_seconds / 60.0, 3),
        "unmatched_samples": total_unmatched,
        "by_variant": group("variant"),
        "by_template": group("template"),
        "by_source": group("source_id"),
        "by_state": {state: summarise(rows) for state, rows in sorted(by_state.items())},
        "determinism": determinism,
        "clips": report_clips,
    }
    with open(args.out, "w") as handle:
        json.dump(report, handle, indent=2)
    print(json.dumps({"split": args.split, "all_gates_pass": report["all_gates_pass"],
                      "gate_results": gates, "overall": overall,
                      "overall_all_clips": overall_all_clips,
                      "unsupported_clips": report["unsupported_clips"], "cuts": report["cuts"],
                      "covered_minutes": report["covered_minutes"]}, indent=2))


if __name__ == "__main__":
    sys.exit(main())

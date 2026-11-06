#!/usr/bin/env python3
"""Label a ChromaScape recording from micro6's Bot Mouse trace (SC-2150 FA12 native labels).

Inputs, all from the same machine and session:
  * the recording `<id>.mp4` and its `<id>.frames.jsonl` (per-frame capture clock, written by
    RecordingService when `recording.frame-timing-sidecar` is on, the default);
  * the micro6 Bot Mouse trace: by default `<id>.mouse-trace.jsonl`, which ChromaScape stores
    next to the recording when micro6 posts it to /api/micro6/mouse-trace; or, with --trace,
    micro6's own `~/.runelite/mouse-trace/mouse-trace-*.jsonl`.

For each encoded frame, the label is the last crosshair render at or before the frame's capture
time, mapped from canvas to captured pixels (stretched mode scales them). A frame with no render
within --max-age-ms of its capture (while it is shown), or captured before the trace started, is
marked `dropped`
and excluded from scoring rather than guessed; so is a frame whose sidecar brackets the grab
(`requestNanos`, written by `record_x11_canvas.py`) when a render falls inside that bracket. CFR duplicate frames carry the label of the
image they repeat. Output follows the corpus label schema read by the FA12 scorer; it never
copies media.

Both clocks are System.nanoTime() of two JVMs on one host, which share the OS monotonic clock on
Windows and Linux; the epoch-millisecond pairs in both headers are checked as a cross-check and
a disagreement larger than --max-skew-ms aborts instead of producing shifted labels.
"""

import argparse
import bisect
import hashlib
import json
from pathlib import Path
import subprocess


def read_jsonl(path):
    with open(path, encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def video_size(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "stream=width,height", "-of", "csv=p=0", str(path)],
                         check=True, capture_output=True, text=True).stdout.strip()
    width, height = out.split(",")[:2]
    return int(width), int(height)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--recording", type=Path, required=True)
    parser.add_argument("--trace", type=Path,
                        help="trace file (default: <recording>.mouse-trace.jsonl beside the video)")
    parser.add_argument("--out", type=Path, required=True, help="labels JSON to write")
    parser.add_argument("--split", required=True, choices=["dev", "heldout"])
    parser.add_argument("--max-age-ms", type=float, default=50.0)
    parser.add_argument("--max-skew-ms", type=float, default=50.0)
    args = parser.parse_args()

    frames_path = args.recording.with_name(args.recording.stem + ".frames.jsonl")
    frames = read_jsonl(frames_path)
    header, frames = frames[0], [f for f in frames[1:] if "frame" in f]
    assert header.get("schema") == "chromascape-recording-frames/v1", header
    trace_path = args.trace or args.recording.with_name(args.recording.stem + ".mouse-trace.jsonl")
    trace = read_jsonl(trace_path)
    # One header per micro6 process that contributed (a client restart adds another).
    trace_headers = [t for t in trace if t.get("kind") == "header"]
    assert trace_headers and all(h.get("schema") == "micro6-mouse-trace/v1"
                                 for h in trace_headers), trace_headers
    renders = sorted((t for t in trace if "seq" in t), key=lambda t: t["nanos"])
    assert renders, "trace has no rendered frames"

    # Cross-check the shared monotonic clock against wall clock in every header pair.
    skews = [(h["startNanos"] - header["startNanos"]) / 1e6
             - (h["startEpochMs"] - header["startEpochMs"]) for h in trace_headers]
    skew_ms = max(skews, key=abs)
    if abs(skew_ms) > args.max_skew_ms:
        raise SystemExit(f"clock cross-check failed: monotonic vs wall skew {skew_ms:.1f} ms")

    width, height = video_size(args.recording)
    fps = header["fps"]
    times = [r["nanos"] for r in renders]
    labels = []
    counts = {"visible": 0, "absent": 0, "dropped": 0}
    for frame in frames:
        capture = frame["captureNanos"]
        index = bisect.bisect_right(times, capture) - 1
        # Current ChromaScape writes each frame's media time (frames sit on capture-time slots and
        # empty slots are skipped); older sidecars and the X11 recorder are constant frame rate.
        pts = frame["ptsMicros"] / 1e6 if "ptsMicros" in frame else frame["frame"] / fps
        entry = {"compose_ordinal": frame["frame"], "pts_time": pts,
                 "capture_nanos": capture, "duplicate": frame["duplicate"]}
        render = renders[index] if index >= 0 else None
        # A sidecar that also records when the grab was requested (the Linux X11 recorder) brackets
        # the instant the image was taken: a render inside (request, capture] may or may not be in
        # the image, so that frame is left out rather than labelled with either render.
        request = frame.get("requestNanos")
        if request is not None and index >= 0 and bisect.bisect_right(times, request) - 1 != index:
            entry.update(state="UNKNOWN", visible=False, dropped=True,
                         drop_reason="render during capture")
            counts["dropped"] += 1
            labels.append(entry)
            continue
        # A hidden marker is written once per transition, so it holds until the next render;
        # a visible render is only trusted for --max-age-ms.
        if render is None or (render["visible"]
                              and (capture - render["nanos"]) / 1e6 > args.max_age_ms):
            entry.update(state="UNKNOWN", visible=False, dropped=True)
            counts["dropped"] += 1
        elif not render["visible"]:
            entry.update(state="ABSENT", visible=False)
            counts["absent"] += 1
        else:
            canvas_w, canvas_h = render["canvas"]
            if render["stretched"]:
                scale_x = render["stretchedSize"][0] / canvas_w
                scale_y = render["stretchedSize"][1] / canvas_h
            else:
                scale_x = scale_y = 1.0
            x, y = render["x"] * scale_x, render["y"] * scale_y
            inside = 0 <= x < width and 0 <= y < height
            entry.update(state="VISIBLE" if inside else "ABSENT", visible=inside,
                         x=round(x, 2), y=round(y, 2), render_seq=render["seq"],
                         render_age_ms=round((capture - render["nanos"]) / 1e6, 2))
            counts["visible" if inside else "absent"] += 1
        labels.append(entry)

    media_sha = hashlib.sha256(args.recording.read_bytes()).hexdigest()
    document = {
        "schema": "sc2150-fa12-labels/v2",
        "label_source": "micro6-mouse-trace/v1 aligned by capture clock",
        "trace_sha256": hashlib.sha256(trace_path.read_bytes()).hexdigest(),
        "clip_id": f"{args.split}--trace--{args.recording.stem}",
        "split": args.split,
        "source_id": media_sha[:8],
        "media_sha256": media_sha,
        "template": "micro6-bot-mouse-crosshair",
        "hotspot": "crosshair centre",
        "compose_size": [width, height],
        "output_size": [width, height],
        "px_to_1080p": 1080.0 / height,
        "fps_compose": fps,
        "cut_compose_ordinals": [],
        "unobservable_cut_compose_ordinals": [],
        "clock_skew_ms": round(skew_ms, 2),
        "counts": counts,
        "labels": labels,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(document, indent=1))
    print(json.dumps({"clip_id": document["clip_id"], "frames": len(labels), **counts,
                      "clock_skew_ms": document["clock_skew_ms"]}))


if __name__ == "__main__":
    main()

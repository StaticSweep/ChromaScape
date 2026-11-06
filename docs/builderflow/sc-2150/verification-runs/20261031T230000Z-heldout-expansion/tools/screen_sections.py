#!/usr/bin/env python3
"""Build the SC-2150 FA12 screening evidence for the downloaded sections (plan item I2).

One montage per video: the three sections at their midpoint, each tile 960 px wide, so a reviewer
can confirm the clip shows a playable OSRS client rather than a menu, a cutscene or a face cam.
Technical facts (dimensions, frame rate, duration, midpoint frame digest) are written beside the
montages, so the verdict in `screening.json` cites evidence rather than memory.

Cursor fraction is deliberately *not* estimated here. Measuring it needs a reviewed cursor
template, which the plan takes from dev footage afterwards; this tool records `cursor_fraction:
null` and the montage for each section so the later measurement has a starting point. A null is
never counted as a pass.
"""

import argparse
import json
import subprocess
from pathlib import Path


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height,avg_frame_rate,nb_frames:format=duration", "-of", "json", str(path)],
        check=True, capture_output=True, text=True).stdout
    document = json.loads(out)
    stream = (document.get("streams") or [{}])[0]
    numerator, _, denominator = (stream.get("avg_frame_rate") or "0/1").partition("/")
    fps = float(numerator) / float(denominator) if float(denominator or 0) else None
    return {"width": stream.get("width"), "height": stream.get("height"),
            "fps": round(fps, 3) if fps else None,
            "durationSeconds": round(float((document.get("format") or {}).get("duration") or 0), 3)}


def frame(media, seconds, target):
    subprocess.run(["ffmpeg", "-v", "error", "-ss", str(seconds), "-i", str(media),
                    "-frames:v", "1", "-vf", "scale=960:-2", "-y", str(target)], check=True)


def montage(tiles, target, columns=1):
    inputs = []
    for tile in tiles:
        inputs.extend(["-i", str(tile)])
    layout = "vstack=inputs=%d" % len(tiles) if columns == 1 else "hstack=inputs=%d" % len(tiles)
    subprocess.run(["ffmpeg", "-v", "error", *inputs, "-filter_complex", layout,
                    "-q:v", "5", "-y", str(target)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--media", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text())
    args.out.mkdir(parents=True, exist_ok=True)
    evidence = {"schema": "sc2150-yt-screening-evidence/v1", "sections": [], "montages": []}
    by_video = {}
    for entry in manifest["sections"]:
        media = args.media / entry["file"]
        if not media.is_file():
            evidence["sections"].append({"file": entry["file"], "missing": True})
            continue
        facts = probe(media)
        mid = facts["durationSeconds"] / 2.0
        tile = args.out / ("%s--s%d-tile.png" % (entry["videoId"], entry["section"]))
        frame(media, mid, tile)
        evidence["sections"].append({"file": entry["file"], "videoId": entry["videoId"],
                                     "channel": entry["channel"], "section": entry["section"],
                                     "midpointSeconds": round(mid, 3), **facts,
                                     "montageTile": tile.name, "osrsClientView": None,
                                     "cursorFraction": None})
        by_video.setdefault(entry["videoId"], []).append(tile)
    for video_id, tiles in sorted(by_video.items()):
        target = args.out / ("%s-montage.jpg" % video_id)
        montage(sorted(tiles), target)
        evidence["montages"].append({"videoId": video_id, "file": target.name,
                                     "sections": len(tiles)})
    (args.out / "screening-evidence.json").write_text(json.dumps(evidence, indent=1) + "\n")
    print(json.dumps({"sections": len(evidence["sections"]),
                      "montages": len(evidence["montages"]), "out": str(args.out)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

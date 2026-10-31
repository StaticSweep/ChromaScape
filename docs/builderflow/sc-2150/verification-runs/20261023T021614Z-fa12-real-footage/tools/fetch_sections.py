#!/usr/bin/env python3
"""Download the SC-2150 FA12 real-footage sections with the pinned yt-dlp (plan item I1).

Media stays on this machine under `build/sc2150-yt/raw/` (gitignored) and is never committed.
Each video contributes three 60-second sections at fixed fractions of its duration, chosen before
any footage was watched, so section times cannot be picked to flatter the detector.

The manifest records, per section, the URL, the requested window, the downloaded file, its
SHA-256, container and pixel height, plus the pinned downloader identity. It is written
incrementally so an interrupted run resumes without re-downloading completed sections.
"""

import argparse
import datetime
import hashlib
import json
import subprocess
import sys
from pathlib import Path

DOWNLOADER = Path.home() / ".local/share/sc2150-tools/yt-dlp-2026.08.19/yt-dlp_linux"
DOWNLOADER_SHA256 = "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
SECTION_SECONDS = 60
SECTION_FRACTIONS = (0.2, 0.5, 0.8)

# Six no-commentary longplays from different channels plus two edited guides, as listed in the
# completion plan. Durations were read from the provider before any download.
VIDEOS = {
    "l7dv8lbS87E": {"channel": "Sleepy Longplays", "duration": 40073},
    "OqSngAyh1rY": {"channel": "Blizzard0199", "duration": 39788},
    "U1uiLTv6quo": {"channel": "shaa rin", "duration": 26445},
    "33MkV_p4jvs": {"channel": "Langsamt", "duration": 32961},
    "xckk8Rft2lc": {"channel": "Kelzayeee", "duration": 32741},
    "SNc0I1V0GXw": {"channel": "420slayerxpdrop", "duration": 3609},
    "9YFTgB78bDY": {"channel": "Theoatrix OSRS", "duration": 1463},
    "trm4e1ON4HM": {"channel": "EVScape", "duration": 1340},
}


def clock(seconds):
    seconds = int(seconds)
    return "%02d:%02d:%02d" % (seconds // 3600, seconds % 3600 // 60, seconds % 60)


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=codec_name,width,height:format=format_name,duration",
         "-of", "json", str(path)],
        check=True, capture_output=True, text=True).stdout
    document = json.loads(out)
    stream = (document.get("streams") or [{}])[0]
    container = document.get("format") or {}
    return {"codec": stream.get("codec_name"), "width": stream.get("width"),
            "height": stream.get("height"), "container": container.get("format_name"),
            "durationSeconds": round(float(container.get("duration") or 0.0), 3)}


def download(video_id, index, start, out_dir):
    target = out_dir / ("%s--s%d.%%(ext)s" % (video_id, index))
    command = [
        str(DOWNLOADER), "--ignore-config", "--no-cache-dir", "--no-warnings",
        "--no-playlist", "-f", "bv*[height<=1080]/b[height<=1080]",
        "--download-sections", "*%s-%s" % (clock(start), clock(start + SECTION_SECONDS)),
        "-o", str(target), "https://www.youtube.com/watch?v=" + video_id,
    ]
    completed = subprocess.run(command, capture_output=True, text=True)
    if completed.returncode != 0:
        raise RuntimeError("yt-dlp failed for %s s%d: %s"
                           % (video_id, index, completed.stderr.strip()[-400:]))
    produced = sorted(out_dir.glob("%s--s%d.*" % (video_id, index)))
    if len(produced) != 1:
        raise RuntimeError("expected one file for %s s%d, got %s"
                           % (video_id, index, [p.name for p in produced]))
    return produced[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, required=True, help="media directory")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--only", action="append", default=[], help="restrict to these video ids")
    args = parser.parse_args()
    if sha256(DOWNLOADER) != DOWNLOADER_SHA256:
        raise SystemExit("pinned yt-dlp content hash does not match the qualified downloader")
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = {"schema": "sc2150-yt-sections/v1",
                "downloader": {"path": str(DOWNLOADER), "sha256": DOWNLOADER_SHA256,
                               "version": "2026.08.19"},
                "retrievedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "sectionSeconds": SECTION_SECONDS, "sections": []}
    if args.manifest.exists():
        manifest["sections"] = json.loads(args.manifest.read_text()).get("sections", [])
    done = {(entry["videoId"], entry["section"]) for entry in manifest["sections"]}
    for video_id, meta in VIDEOS.items():
        if args.only and video_id not in args.only:
            continue
        for index, fraction in enumerate(SECTION_FRACTIONS, start=1):
            if (video_id, index) in done:
                print("skip", video_id, index, flush=True)
                continue
            start = int(meta["duration"] * fraction)
            file = download(video_id, index, start, args.out)
            media = probe(file)
            if media["height"] and media["height"] > 1080:
                raise SystemExit("downloaded %s above the 1080p cap" % file)
            entry = {"videoId": video_id, "channel": meta["channel"], "section": index,
                     "url": "https://www.youtube.com/watch?v=" + video_id,
                     "requestedStartSeconds": start,
                     "requestedEndSeconds": start + SECTION_SECONDS,
                     "file": file.name, "sha256": sha256(file),
                     "sizeBytes": file.stat().st_size, **media}
            manifest["sections"].append(entry)
            manifest["sections"].sort(key=lambda item: (item["videoId"], item["section"]))
            args.manifest.parent.mkdir(parents=True, exist_ok=True)
            args.manifest.write_text(json.dumps(manifest, indent=1) + "\n")
            done.add((video_id, index))
            print("saved", file.name, media["width"], media["height"],
                  media["durationSeconds"], "s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())

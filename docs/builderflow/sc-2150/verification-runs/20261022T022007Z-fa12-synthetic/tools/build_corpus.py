#!/usr/bin/env python3
"""SC-2150 FA12 synthetic-composite cursor corpus builder.

Draws a known cursor sprite onto real, cursor-free OSRS recordings at scripted positions, so every
output frame has an exact ground-truth hotspot. Standard library + ffmpeg only.

Split is by whole source recording: a source belongs to exactly one split, and every variant
derived from it stays in that split. The held-out split must never be used to tune thresholds.

Usage: build_corpus.py --recordings DIR --runelite-cursors DIR --out DIR [--split dev|heldout|all]
"""

import argparse
import hashlib
import json
import math
import os
import random
import subprocess
import sys

FPS = 30

# Whole-source split. Segments are (start seconds, duration seconds, kind) of cursor-free footage
# found by inspecting contact sheets; "game" is in-game viewport, "login" is the animated login
# screen (moving fire background).
SOURCES = {
    "fa5228cb": {"split": "dev", "game": (656.0, 66.0), "login": (120.0, 240.0)},
    "f9f78ab0": {"split": "dev", "game": None, "login": (600.0, 600.0)},
    "78cde7ca": {"split": "heldout", "game": (582.0, 50.0), "login": (120.0, 240.0)},
    "87a88065": {"split": "heldout", "game": (52.5, 13.0), "login": (2.0, 36.0)},
}

# Classic arrow drawn for this corpus (own work). B=outline, W=fill, .=transparent. Hotspot (0,0).
ARROW = [
    "B...........",
    "BB..........",
    "BWB.........",
    "BWWB........",
    "BWWWB.......",
    "BWWWWB......",
    "BWWWWWB.....",
    "BWWWWWWB....",
    "BWWWWWWWB...",
    "BWWWWWWWWB..",
    "BWWWWWWWWWB.",
    "BWWWWWWBBBBB",
    "BWWWBWWB....",
    "BWWBBWWB....",
    "BWB..BWWB...",
    "BB...BWWB...",
    "B.....BWWB..",
    "......BWWB..",
    ".......BB...",
]

TEMPLATES = ["arrow", "rs3-gold", "dragon-dagger"]

# Variant recipe applied identically to every source/segment. capture_scale scales the background
# before the cursor is drawn (cursor keeps native size, as when recording at a higher resolution);
# post_scale scales after drawing (the whole video, cursor included, is resized).
VARIANTS = [
    {"id": "native-4m", "kind": "game", "kbps": 4000},
    {"id": "compressed-300k", "kind": "game", "kbps": 300},
    {"id": "capture-1.5x", "kind": "game", "kbps": 2500, "capture_scale": 1.5},
    {"id": "resized-0.7x", "kind": "game", "kbps": 2500, "post_scale": 0.7},
    {"id": "vfr-drop", "kind": "game", "kbps": 2500, "vfr": True},
    {"id": "cut", "kind": "game+login", "kbps": 2500, "cut": True},
    {"id": "overlay", "kind": "game", "kbps": 2500, "overlay": True},
    {"id": "login-moving", "kind": "login", "kbps": 2500},
]

CLIP_SECONDS = 16.0


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def probe_size(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height", "-of", "csv=p=0", path],
        check=True, capture_output=True, text=True).stdout.strip()
    width, height = out.split(",")[:2]
    return int(width), int(height)


def even(value):
    return int(value) // 2 * 2


def load_template(name, cursor_dir):
    """Returns (width, height, rgba bytes)."""
    if name == "arrow":
        height = len(ARROW)
        width = len(ARROW[0])
        data = bytearray()
        for row in ARROW:
            for cell in row:
                if cell == "B":
                    data += bytes((0, 0, 0, 255))
                elif cell == "W":
                    data += bytes((255, 255, 255, 255))
                else:
                    data += bytes((0, 0, 0, 0))
        return width, height, bytes(data)
    path = os.path.join(cursor_dir, "cursor-%s.png" % name)
    width, height = probe_size(path)
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "rawvideo", "-pix_fmt", "rgba", "-"],
        check=True, capture_output=True).stdout
    return width, height, raw


def write_template_png(name, template, path):
    width, height, raw = template
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s",
         "%dx%d" % (width, height), "-i", "-", "-frames:v", "1", path],
        input=raw, check=True)


class Path2D:
    """Deterministic human-like cursor script: minimum-jerk moves, dwells, hidden spans."""

    def __init__(self, rng, width, height, sprite_w, sprite_h):
        self.rng = rng
        self.width = width
        self.height = height
        self.sprite_w = sprite_w
        self.sprite_h = sprite_h
        self.x = rng.uniform(0.2, 0.8) * width
        self.y = rng.uniform(0.2, 0.8) * height
        self.segment = None
        self.hidden_until = -1
        self.dwell_until = -1

    def target(self):
        rng = self.rng
        if rng.random() < 0.12:
            # Visit an edge so the sprite is clipped by the frame; the hotspot stays inside.
            return (rng.uniform(self.width - self.sprite_w + 1, self.width - 1),
                    rng.uniform(0.1, 0.9) * self.height) if rng.random() < 0.5 else (
                rng.uniform(0.1, 0.9) * self.width,
                rng.uniform(self.height - self.sprite_h + 1, self.height - 1))
        return rng.uniform(2, self.width - 3), rng.uniform(2, self.height - 3)

    def step(self, frame):
        rng = self.rng
        if frame < self.hidden_until:
            return None
        if frame < self.dwell_until:
            return self.x, self.y
        if self.segment is None:
            roll = rng.random()
            if roll < 0.06:
                self.hidden_until = frame + rng.randint(15, 75)
                self.x, self.y = self.target()
                return None
            if roll < 0.40:
                self.dwell_until = frame + rng.randint(4, 36)
                return self.x, self.y
            tx, ty = self.target()
            distance = math.hypot(tx - self.x, ty - self.y)
            frames = max(3, int(round((0.12 + 0.0009 * distance + rng.uniform(0, 0.25)) * FPS)))
            bend = rng.uniform(-0.18, 0.18) * distance
            self.segment = (frame, frames, self.x, self.y, tx, ty, bend)
        start, frames, x0, y0, x1, y1, bend = self.segment
        tau = min(1.0, (frame - start + 1) / frames)
        s = 10 * tau ** 3 - 15 * tau ** 4 + 6 * tau ** 5
        dx, dy = x1 - x0, y1 - y0
        length = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / length, dx / length
        arc = math.sin(math.pi * s) * bend
        self.x = x0 + dx * s + nx * arc
        self.y = y0 + dy * s + ny * arc
        self.x = min(max(self.x, 0.0), self.width - 1.0)
        self.y = min(max(self.y, 0.0), self.height - 1.0)
        if tau >= 1.0:
            self.segment = None
        return self.x, self.y


def composite(frame, width, height, template, hx, hy, cover=None):
    """Alpha-blends the sprite with its hotspot at integer (hx, hy).

    Returns the fraction of opaque sprite pixels that remain visible: inside the frame and, when
    ``cover`` (an opaque box drawn later) is given, outside it.
    """
    tw, th, raw = template
    opaque = 0
    drawn = 0
    for ty in range(th):
        y = hy + ty
        for tx in range(tw):
            offset = (ty * tw + tx) * 4
            alpha = raw[offset + 3]
            if alpha == 0:
                continue
            opaque += 1
            x = hx + tx
            if x < 0 or y < 0 or x >= width or y >= height:
                continue
            if cover is None or not (cover[0] <= x < cover[2] and cover[1] <= y < cover[3]):
                drawn += 1
            index = (y * width + x) * 3
            if alpha == 255:
                frame[index:index + 3] = raw[offset:offset + 3]
            else:
                for channel in range(3):
                    frame[index + channel] = (raw[offset + channel] * alpha
                                              + frame[index + channel] * (255 - alpha)) // 255
    return drawn / opaque if opaque else 0.0


def draw_overlay(frame, width, height, box, opacity):
    x0, y0, x1, y1 = box
    keep = 1.0 - opacity
    for y in range(max(0, y0), min(height, y1)):
        row = y * width * 3
        for x in range(max(0, x0), min(width, x1)):
            index = row + x * 3
            frame[index] = int(frame[index] * keep + 24 * opacity)
            frame[index + 1] = int(frame[index + 1] * keep + 28 * opacity)
            frame[index + 2] = int(frame[index + 2] * keep + 40 * opacity)


def reader(path, start, seconds, scale):
    width, height = probe_size(path)
    out_w, out_h = even(width * scale), even(height * scale)
    vf = "fps=%d" % FPS
    if scale != 1.0:
        vf += ",scale=%d:%d:flags=bicubic" % (out_w, out_h)
    process = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-ss", "%.3f" % start, "-i", path, "-t", "%.3f" % seconds,
         "-an", "-vf", vf, "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        stdout=subprocess.PIPE)
    return process, out_w, out_h


def build_clip(args, source_id, variant, clip_index, templates):
    meta = SOURCES[source_id]
    path = os.path.join(args.recordings, "recording_%s.mp4" % source_id)
    seed = int(hashlib.sha256(("%s/%s" % (source_id, variant["id"])).encode()).hexdigest()[:8], 16)
    rng = random.Random(seed)
    template_name = TEMPLATES[clip_index % len(TEMPLATES)]
    template = templates[template_name]
    capture_scale = variant.get("capture_scale", 1.0)
    post_scale = variant.get("post_scale", 1.0)

    kind = variant["kind"]
    if kind == "game" and meta["game"] is None:
        return None
    pieces = []
    if variant.get("cut"):
        if meta["game"] is None:
            return None
        # game -> login -> game: two hard cuts per clip.
        game_start, game_len = meta["game"]
        login_start, login_len = meta["login"]
        third = CLIP_SECONDS / 3
        pieces.append((game_start + rng.uniform(0, max(0.0, game_len - third)), third))
        pieces.append((login_start + rng.uniform(0, max(0.0, login_len - third)), third))
        pieces.append((game_start + rng.uniform(0, max(0.0, game_len - third)), third))
    else:
        start, length = meta["login" if kind == "login" else "game"]
        seconds = min(CLIP_SECONDS, length)
        pieces.append((start + rng.uniform(0, max(0.0, length - seconds)), seconds))

    clip_id = "%s--%s--%s" % (meta["split"], source_id, variant["id"])
    out_dir = os.path.join(args.out, meta["split"])
    os.makedirs(out_dir, exist_ok=True)
    media = os.path.join(out_dir, clip_id + ".mp4")

    first, width, height = reader(path, pieces[0][0], pieces[0][1], capture_scale)
    first.kill()
    first.wait()
    out_w, out_h = even(width * post_scale), even(height * post_scale)
    overlay_box = None
    if variant.get("overlay"):
        # Opaque "webcam" box drawn over the cursor, plus a translucent caption band.
        overlay_box = (int(width * 0.02), int(height * 0.50), int(width * 0.30), int(height * 0.78))
        band_box = (0, int(height * 0.04), width, int(height * 0.12))

    vf = []
    if post_scale != 1.0:
        vf.append("scale=%d:%d:flags=area" % (out_w, out_h))
    if variant.get("vfr"):
        # Deterministic irregular drop pattern (~35% of frames), timestamps preserved.
        vf.append("select='lt(mod(n*7919\\,100)\\,65)'")
    encode = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
              "%dx%d" % (width, height), "-r", str(FPS), "-i", "-"]
    if vf:
        encode += ["-vf", ",".join(vf)]
    if variant.get("vfr"):
        encode += ["-fps_mode", "vfr"]
    # Fedora's ffmpeg ships OpenH264 (no libx264): H.264 with a bitrate target.
    encode += ["-c:v", "libopenh264", "-b:v", "%dk" % variant["kbps"], "-maxrate",
               "%dk" % (variant["kbps"] * 2), "-pix_fmt", "yuv420p", "-g", "60",
               "-movflags", "+faststart", media]
    encoder = subprocess.Popen(encode, stdin=subprocess.PIPE)

    cursor = Path2D(rng, width, height, template[0], template[1])
    labels = []
    frame_size = width * height * 3
    composed = 0
    cut_ordinals = []
    for piece_index, (start, seconds) in enumerate(pieces):
        process, _, _ = reader(path, start, seconds, capture_scale)
        if piece_index > 0:
            cut_ordinals.append(composed)
            # A cut also teleports the cursor, as an edit would.
            cursor.x, cursor.y = cursor.target()
            cursor.segment = None
        while True:
            buffer = process.stdout.read(frame_size)
            if len(buffer) < frame_size:
                break
            frame = bytearray(buffer)
            position = cursor.step(composed)
            entry = {"n": composed, "pts_time": round(composed / FPS, 6)}
            if position is None:
                entry.update({"state": "ABSENT", "visible": False})
            else:
                hx, hy = int(round(position[0])), int(round(position[1]))
                fraction = composite(frame, width, height, template, hx, hy, overlay_box)
                # Scoring classes, fixed before any held-out evaluation:
                #   >= 0.5 of the sprite visible -> visible (counts for recall/error)
                #   0 < fraction < 0.5           -> don't care (counts for neither)
                #   nothing visible              -> absent (counts for false detections)
                visible = fraction >= 0.5
                state = "VISIBLE" if fraction >= 0.999 else "VISIBLE_CLIPPED"
                if overlay_box is not None:
                    x0, y0, x1, y1 = overlay_box
                    if x0 <= hx < x1 and y0 <= hy < y1:
                        state = "HOTSPOT_OCCLUDED"
                    elif (hx + template[0] > x0 and hx < x1
                          and hy + template[1] > y0 and hy < y1):
                        state = "PARTIALLY_OCCLUDED"
                    elif band_box[1] <= hy < band_box[3]:
                        state = "UNDER_TRANSLUCENT_OVERLAY"
                if fraction <= 0.0:
                    state = "OCCLUDED"
                elif not visible:
                    state = "PARTIAL_DONT_CARE"
                # Hotspot pixel centre mapped through the post-capture resize.
                sx = (hx + 0.5) * out_w / width - 0.5
                sy = (hy + 0.5) * out_h / height - 0.5
                entry.update({"state": state, "visible": visible,
                              "x": round(sx, 3), "y": round(sy, 3),
                              "sprite_visible_fraction": round(fraction, 4)})
            if overlay_box is not None:
                draw_overlay(frame, width, height, overlay_box, 1.0)
                draw_overlay(frame, width, height, band_box, 0.45)
            if variant.get("vfr") and not ((composed * 7919) % 100 < 65):
                entry["dropped"] = True
            labels.append(entry)
            encoder.stdin.write(frame)
            composed += 1
        process.stdout.close()
        process.wait()
    encoder.stdin.close()
    if encoder.wait() != 0:
        raise RuntimeError("encoder failed for " + clip_id)

    document = {
        "schema": "sc2150-fa12-labels/v1",
        "clip_id": clip_id,
        "split": meta["split"],
        "source_id": source_id,
        "source_sha256": args.source_hashes[source_id],
        "variant": variant,
        "pieces": [{"start_seconds": round(s, 3), "seconds": round(d, 3)} for s, d in pieces],
        "template": template_name,
        "template_size": [template[0], template[1]],
        "hotspot": [0, 0],
        # Displayed size of one template pixel in output-video pixels.
        "cursor_source_scale": post_scale,
        "compose_size": [width, height],
        "output_size": [out_w, out_h],
        "px_to_1080p": 1080.0 / out_h,
        "cut_compose_ordinals": cut_ordinals,
        "fps_compose": FPS,
        "seed": seed,
        "media_sha256": sha256(media),
        "labels": labels,
    }
    with open(os.path.join(out_dir, clip_id + ".labels.json"), "w") as handle:
        json.dump(document, handle, separators=(",", ":"))
    return clip_id


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--recordings", required=True)
    parser.add_argument("--runelite-cursors", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--split", default="all", choices=["dev", "heldout", "all"])
    args = parser.parse_args()
    args.source_hashes = {
        source: sha256(os.path.join(args.recordings, "recording_%s.mp4" % source))
        for source in SOURCES}
    templates = {name: load_template(name, args.runelite_cursors) for name in TEMPLATES}
    template_dir = os.path.join(args.out, "templates")
    os.makedirs(template_dir, exist_ok=True)
    for name, template in templates.items():
        write_template_png(name, template, os.path.join(template_dir, name + ".png"))
    built = []
    for source_id, meta in SOURCES.items():
        if args.split != "all" and meta["split"] != args.split:
            continue
        for clip_index, variant in enumerate(VARIANTS):
            clip = build_clip(args, source_id, variant, clip_index, templates)
            if clip:
                built.append(clip)
                print("built", clip, flush=True)
    manifest = {
        "schema": "sc2150-fa12-corpus/v1",
        "sources": {s: {"split": m["split"], "sha256": args.source_hashes[s]}
                    for s, m in SOURCES.items()},
        "templates": {n: sha256(os.path.join(template_dir, n + ".png")) for n in TEMPLATES},
        "clips": built,
    }
    with open(os.path.join(args.out, "corpus-manifest-%s.json" % args.split), "w") as handle:
        json.dump(manifest, handle, indent=2)


if __name__ == "__main__":
    sys.exit(main())

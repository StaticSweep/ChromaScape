#!/usr/bin/env python3
"""SC-2150 FA12 v3 synthetic-composite cursor corpus builder.

Draws a known cursor sprite onto real, cursor-free OSRS recordings at scripted positions, so every
output frame has an exact ground-truth hotspot. Standard library + ffmpeg only.

Differences from run 20261022T022007Z:
  * sources and their dev/held-out split come from results/split.json (fixed before tuning);
  * segments are chosen automatically and pre-screened on raw pixel statistics only (no detector):
    a segment is rejected if any consecutive 480-px grey frame pair changes by more than
    SCREEN_MAX_PAIR_DELTA (interface pops, teleports, logins) or if it is mostly black;
  * HiDPI variants draw the sprite 1.5x / 2x (nearest-neighbour), as at 150% / 200% display scale;
  * a cut whose boundary frames differ by less than CUT_OBSERVABLE_DELTA is labelled unobservable.

Usage: build_corpus.py --root IDEAPROJECTS_DIR --split-file results/split.json
                       --runelite-cursors DIR --out DIR --split dev|heldout [--shard i/n]
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
CLIP_SECONDS = 10.0
SCREEN_WIDTH = 480
SCREEN_MAX_PAIR_DELTA = 0.04
SCREEN_MIN_MEAN_LUMA = 0.06
CUT_OBSERVABLE_DELTA = 0.02
MAX_SCREEN_ATTEMPTS = 24

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

# sprite_scale enlarges the sprite before drawing (HiDPI cursor); post_scale resizes the whole
# video after drawing. The displayed size of one template pixel is sprite_scale * post_scale.
VARIANTS = [
    {"id": "native", "kbps": 4000},
    {"id": "hidpi-1.5", "kbps": 2500, "sprite_scale": 1.5},
    {"id": "hidpi-2.0", "kbps": 2500, "sprite_scale": 2.0},
    {"id": "hidpi-1.5-compressed-300k", "kbps": 300, "sprite_scale": 1.5},
    {"id": "hidpi-2.0-resized-0.7x", "kbps": 2500, "sprite_scale": 2.0, "post_scale": 0.7},
    {"id": "hidpi-1.5-vfr-drop", "kbps": 2500, "sprite_scale": 1.5, "vfr": True},
    {"id": "hidpi-1.5-cut", "kbps": 2500, "sprite_scale": 1.5, "cut": True},
    {"id": "hidpi-1.5-overlay", "kbps": 2500, "sprite_scale": 1.5, "overlay": True},
]


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height:format=duration", "-of", "json", path],
        check=True, capture_output=True, text=True).stdout
    doc = json.loads(out)
    stream = doc["streams"][0]
    return int(stream["width"]), int(stream["height"]), float(doc["format"]["duration"])


def even(value):
    return int(value) // 2 * 2


def load_template(name, cursor_dir):
    """Returns (width, height, rgba bytes)."""
    if name == "arrow":
        data = bytearray()
        for row in ARROW:
            for cell in row:
                data += {"B": bytes((0, 0, 0, 255)), "W": bytes((255, 255, 255, 255))}.get(
                    cell, bytes((0, 0, 0, 0)))
        return len(ARROW[0]), len(ARROW), bytes(data)
    path = os.path.join(cursor_dir, "cursor-%s.png" % name)
    width, height, _ = probe_image(path)
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "rawvideo", "-pix_fmt", "rgba", "-"],
        check=True, capture_output=True).stdout
    return width, height, raw


def probe_image(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0", path],
        check=True, capture_output=True, text=True).stdout.strip()
    width, height = out.split(",")[:2]
    return int(width), int(height), 0.0


def scale_sprite(template, factor):
    """Nearest-neighbour enlargement, as an OS draws a larger cursor."""
    if factor == 1.0:
        return template
    tw, th, raw = template
    nw, nh = int(round(tw * factor)), int(round(th * factor))
    out = bytearray(nw * nh * 4)
    for y in range(nh):
        sy = min(th - 1, int(y / factor))
        for x in range(nw):
            sx = min(tw - 1, int(x / factor))
            src = (sy * tw + sx) * 4
            dst = (y * nw + x) * 4
            out[dst:dst + 4] = raw[src:src + 4]
    return nw, nh, bytes(out)


def write_template_png(template, path):
    width, height, raw = template
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgba", "-s",
         "%dx%d" % (width, height), "-i", "-", "-frames:v", "1", path],
        input=raw, check=True)


def grey_frames(path, start, seconds):
    """Yields 480-px-wide grey frames (bytes) for screening."""
    width, height, _ = probe(path)
    out_h = even(height * SCREEN_WIDTH / width)
    size = SCREEN_WIDTH * out_h
    process = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-ss", "%.3f" % start, "-i", path, "-t", "%.3f" % seconds,
         "-an", "-vf", "fps=%d,scale=%d:%d:flags=area,format=gray" % (FPS, SCREEN_WIDTH, out_h),
         "-f", "rawvideo", "-"], stdout=subprocess.PIPE)
    while True:
        buffer = process.stdout.read(size)
        if len(buffer) < size:
            break
        yield buffer
    process.stdout.close()
    process.wait()


def mean_delta(a, b):
    return sum(abs(x - y) for x, y in zip(a[::4], b[::4])) / (len(a[::4]) * 255.0)


def screen(path, start, seconds):
    """Raw-pixel screen: returns (accepted, max_pair_delta, mean_luma)."""
    previous = None
    worst = 0.0
    luma_total = 0.0
    count = 0
    for frame in grey_frames(path, start, seconds):
        luma_total += sum(frame[::16]) / (len(frame[::16]) * 255.0)
        count += 1
        if previous is not None:
            worst = max(worst, mean_delta(previous, frame))
        previous = frame
    mean_luma = luma_total / max(1, count)
    ok = count >= int(seconds * FPS * 0.9) and worst <= SCREEN_MAX_PAIR_DELTA \
        and mean_luma >= SCREEN_MIN_MEAN_LUMA
    return ok, worst, mean_luma


def pick_segment(path, duration, seconds, rng, avoid):
    for attempt in range(MAX_SCREEN_ATTEMPTS):
        start = rng.uniform(0.5, max(0.5, duration - seconds - 0.5))
        if any(abs(start - other) < seconds for other in avoid):
            continue
        ok, worst, luma = screen(path, start, seconds)
        if ok:
            return start, {"attempt": attempt, "max_pair_delta": round(worst, 4),
                           "mean_luma": round(luma, 4)}
    return None, None


class Path2D:
    """Deterministic human-like cursor script: minimum-jerk moves, stops, hidden spans."""

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
            if rng.random() < 0.5:
                return (rng.uniform(self.width - self.sprite_w + 1, self.width - 1),
                        rng.uniform(0.1, 0.9) * self.height)
            return (rng.uniform(0.1, 0.9) * self.width,
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
                # Stops include long ones (up to 4 s) so the stop/hold rule is exercised.
                long_stop = rng.random() < 0.15
                self.dwell_until = frame + (rng.randint(60, 120) if long_stop
                                            else rng.randint(4, 36))
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
        self.x = min(max(x0 + dx * s + nx * arc, 0.0), self.width - 1.0)
        self.y = min(max(y0 + dy * s + ny * arc, 0.0), self.height - 1.0)
        if tau >= 1.0:
            self.segment = None
        return self.x, self.y


def composite(frame, width, height, template, hx, hy, cover=None):
    """Alpha-blends the sprite with its top-left at (hx, hy); returns visible opaque fraction."""
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


def reader(path, start, seconds, width, height):
    process = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-ss", "%.3f" % start, "-i", path, "-t", "%.3f" % seconds,
         "-an", "-vf", "fps=%d,scale=%d:%d" % (FPS, width, height), "-f", "rawvideo",
         "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    return process


def build_clip(args, source_id, meta, variant, clip_index, templates):
    path = os.path.join(args.root, meta["path"])
    seed = int(hashlib.sha256(("v3/%s/%s" % (source_id, variant["id"])).encode()).hexdigest()[:8],
               16)
    rng = random.Random(seed)
    template_name = TEMPLATES[(clip_index + int(meta["sha256"][:2], 16)) % len(TEMPLATES)]
    sprite_scale = variant.get("sprite_scale", 1.0)
    template = scale_sprite(templates[template_name], sprite_scale)
    post_scale = variant.get("post_scale", 1.0)
    src_w, src_h, duration = probe(path)
    width, height = even(src_w), even(src_h)

    pieces = []
    screening = []
    if variant.get("cut"):
        third = CLIP_SECONDS / 3
        starts = []
        for _ in range(3):
            start, info = pick_segment(path, duration, third, rng, starts)
            if start is None:
                return None, "no screened segment for cut piece"
            starts.append(start)
            pieces.append((start, third))
            screening.append(info)
    else:
        start, info = pick_segment(path, duration, min(CLIP_SECONDS, duration - 1.0), rng, [])
        if start is None:
            return None, "no screened segment"
        pieces.append((start, min(CLIP_SECONDS, duration - 1.0)))
        screening.append(info)

    clip_id = "%s--%s--%s" % (meta["split"], source_id, variant["id"])
    out_dir = os.path.join(args.out, meta["split"])
    os.makedirs(out_dir, exist_ok=True)
    media = os.path.join(out_dir, clip_id + ".mp4")
    out_w, out_h = even(width * post_scale), even(height * post_scale)
    overlay_box = band_box = None
    if variant.get("overlay"):
        overlay_box = (int(width * 0.02), int(height * 0.50), int(width * 0.30), int(height * 0.78))
        band_box = (0, int(height * 0.04), width, int(height * 0.12))

    vf = []
    if post_scale != 1.0:
        vf.append("scale=%d:%d:flags=area" % (out_w, out_h))
    if variant.get("vfr"):
        vf.append("select='lt(mod(n*7919\\,100)\\,65)'")
    encode = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
              "%dx%d" % (width, height), "-r", str(FPS), "-i", "-"]
    if vf:
        encode += ["-vf", ",".join(vf)]
    if variant.get("vfr"):
        encode += ["-fps_mode", "vfr"]
    encode += ["-c:v", "libopenh264", "-b:v", "%dk" % variant["kbps"], "-maxrate",
               "%dk" % (variant["kbps"] * 2), "-pix_fmt", "yuv420p", "-g", "60",
               "-movflags", "+faststart", media]
    encoder = subprocess.Popen(encode, stdin=subprocess.PIPE)

    cursor = Path2D(rng, width, height, template[0], template[1])
    labels = []
    frame_size = width * height * 3
    composed = 0
    cuts = []
    last_grey = None
    for piece_index, (start, seconds) in enumerate(pieces):
        process = reader(path, start, seconds, width, height)
        first_of_piece = True
        while True:
            buffer = process.stdout.read(frame_size)
            if len(buffer) < frame_size:
                break
            if piece_index > 0 and first_of_piece:
                # Observability of the edit from raw pixels of the clean frames on either side.
                grey = bytes(buffer[1::3 * 7])
                observable = last_grey is not None and mean_delta(last_grey, grey) \
                    >= CUT_OBSERVABLE_DELTA
                cuts.append({"ordinal": composed, "observable": observable})
                cursor.x, cursor.y = cursor.target()
                cursor.segment = None
            first_of_piece = False
            last_grey = bytes(buffer[1::3 * 7])
            frame = bytearray(buffer)
            position = cursor.step(composed)
            entry = {"n": composed, "pts_time": round(composed / FPS, 6)}
            if position is None:
                entry.update({"state": "ABSENT", "visible": False})
            else:
                hx, hy = int(round(position[0])), int(round(position[1]))
                fraction = composite(frame, width, height, template, hx, hy, overlay_box)
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
                # Centre of the hotspot sprite pixel (sprite pixel (0,0) spans sprite_scale px),
                # mapped through the post-capture resize, in output pixel-index coordinates.
                cx = hx + sprite_scale * 0.5
                cy = hy + sprite_scale * 0.5
                entry.update({"state": state, "visible": visible,
                              "x": round(cx * out_w / width - 0.5, 3),
                              "y": round(cy * out_h / height - 0.5, 3),
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
        "schema": "sc2150-fa12-labels/v2",
        "clip_id": clip_id,
        "split": meta["split"],
        "source_id": source_id,
        "source_sha256": meta["sha256"],
        "variant": variant,
        "pieces": [{"start_seconds": round(s, 3), "seconds": round(d, 3)} for s, d in pieces],
        "screening": screening,
        "template": template_name,
        "template_size": [templates[template_name][0], templates[template_name][1]],
        "hotspot": [0, 0],
        "cursor_source_scale": sprite_scale * post_scale,
        "compose_size": [width, height],
        "output_size": [out_w, out_h],
        "px_to_1080p": 1080.0 / out_h,
        "cut_compose_ordinals": [c["ordinal"] for c in cuts if c["observable"]],
        "unobservable_cut_compose_ordinals": [c["ordinal"] for c in cuts if not c["observable"]],
        "fps_compose": FPS,
        "seed": seed,
        "media_sha256": sha256(media),
        "labels": labels,
    }
    with open(os.path.join(out_dir, clip_id + ".labels.json"), "w") as handle:
        json.dump(document, handle, separators=(",", ":"))
    return clip_id, None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--split-file", required=True)
    parser.add_argument("--runelite-cursors", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--split", required=True, choices=["dev", "heldout"])
    parser.add_argument("--shard", default="0/1")
    args = parser.parse_args()
    shard, shards = (int(v) for v in args.shard.split("/"))
    sources = json.load(open(args.split_file))["sources"]
    templates = {name: load_template(name, args.runelite_cursors) for name in TEMPLATES}
    template_dir = os.path.join(args.out, "templates")
    os.makedirs(template_dir, exist_ok=True)
    for name, template in templates.items():
        write_template_png(template, os.path.join(template_dir, name + ".png"))
    jobs = [(sid, meta, variant, index)
            for sid, meta in sorted(sources.items()) if meta["split"] == args.split
            for index, variant in enumerate(VARIANTS)]
    report = []
    for number, (sid, meta, variant, index) in enumerate(jobs):
        if number % shards != shard:
            continue
        clip, reason = build_clip(args, sid, meta, variant, index, templates)
        report.append({"source": sid, "variant": variant["id"], "clip": clip, "skipped": reason})
        print("built" if clip else "skipped", sid, variant["id"], reason or "", flush=True)
    with open(os.path.join(args.out, "build-%s-%d-of-%d.json" % (args.split, shard, shards)),
              "w") as handle:
        json.dump(report, handle, indent=2)


if __name__ == "__main__":
    sys.exit(main())

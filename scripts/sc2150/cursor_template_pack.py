#!/usr/bin/env python3
"""Seed and verify a reviewed cursor-template pack for SC-2150 assisted review.

A template pack is one private PNG whose opaque pixels are the cursor sprite plus its dark
outline, derived from an exact source frame and crop. The server pins the PNG's SHA-256 so a
changed file fails closed instead of silently producing different suggestions.

Commands:
  seed    Extract one source frame, crop it, build the reviewed alpha mask and write the PNG
          plus a provenance manifest.
  verify  Check a provisioned PNG against its expected SHA-256 (and optionally its manifest).

The source media and the resulting template are private: keep them outside Git. The manifest is
safe to retain with the evidence because it records identities, not pixels.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter

SCHEMA = "sc2150-cursor-template-pack/v1"
WHITE_MIN_CHANNEL = 150
WHITE_CHANNEL_RANGE = 45
OUTLINE_MAX_CHANNEL = 80
OUTLINE_DILATION = 1


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_pair(value: str, name: str) -> tuple[int, int]:
    parts = value.split(",")
    if len(parts) != 2:
        raise SystemExit(f"{name} must be X,Y")
    return int(parts[0]), int(parts[1])


def parse_crop(value: str) -> tuple[int, int, int, int]:
    parts = value.split(",")
    if len(parts) != 4:
        raise SystemExit("--crop must be X,Y,WIDTH,HEIGHT")
    x, y, width, height = (int(part) for part in parts)
    if width <= 0 or height <= 0:
        raise SystemExit("--crop width and height must be positive")
    return x, y, width, height


def extract_frame(source: Path, ordinal: int, destination: Path) -> None:
    command = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-nostdin",
        "-y",
        "-i",
        str(source),
        "-vf",
        f"select=eq(n\\,{ordinal})",
        "-vsync",
        "0",
        "-frames:v",
        "1",
        str(destination),
    ]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0 or not destination.is_file():
        raise SystemExit(f"ffmpeg could not extract frame {ordinal}: {result.stderr.strip()}")


def reviewed_mask(frame: Image.Image) -> Image.Image:
    """White cursor body plus its dark outline, dilated to cover anti-aliased edges."""
    rgb = frame.convert("RGB")
    red, green, blue = rgb.split()
    maximum = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    minimum = ImageChops.darker(ImageChops.darker(red, green), blue)
    white = ImageChops.subtract(maximum, minimum).point(
        lambda value: 255 if value < WHITE_CHANNEL_RANGE else 0
    )
    # Every channel must be bright: a single bright channel is background, not the cursor body.
    white = ImageChops.multiply(
        white, minimum.point(lambda value: 255 if value > WHITE_MIN_CHANNEL else 0)
    )
    outline = maximum.point(lambda value: 255 if value < OUTLINE_MAX_CHANNEL else 0)
    if OUTLINE_DILATION > 1:
        outline = outline.filter(ImageFilter.MaxFilter(OUTLINE_DILATION))
    return ImageChops.lighter(white, outline)


def seed(args: argparse.Namespace) -> int:
    source = Path(args.source).expanduser()
    if not source.is_file():
        raise SystemExit(f"source media does not exist: {source}")
    crop = parse_crop(args.crop)
    hotspot = parse_pair(args.hotspot, "--hotspot") if args.hotspot else (0, 0)
    out = Path(args.out).expanduser()
    manifest_path = Path(args.manifest).expanduser()

    with tempfile.TemporaryDirectory(prefix="sc2150-template-") as scratch:
        frame_path = Path(scratch) / "frame.png"
        extract_frame(source, args.ordinal, frame_path)
        frame = Image.open(frame_path)
        x, y, width, height = crop
        if x + width > frame.width or y + height > frame.height:
            raise SystemExit(
                f"crop {crop} exceeds the {frame.width}x{frame.height} source frame"
            )
        cropped = frame.crop((x, y, x + width, y + height)).convert("RGB")
        mask = reviewed_mask(cropped)
        if mask.getbbox() is None:
            raise SystemExit("the reviewed mask selected no pixels; adjust the crop")
        template = cropped.convert("RGBA")
        template.putalpha(mask)
        out.parent.mkdir(parents=True, exist_ok=True)
        template.save(out, format="PNG")

    digest = sha256_file(out)
    manifest = {
        "schema": SCHEMA,
        "packId": args.pack_id,
        "sourcePath": str(source),
        "sourceSha256": sha256_file(source),
        "sourceFrameOrdinal": args.ordinal,
        "crop": list(crop),
        "hotspot": list(hotspot),
        "masks": {
            "whiteMinChannel": WHITE_MIN_CHANNEL,
            "whiteChannelRange": WHITE_CHANNEL_RANGE,
            "outlineMaxChannel": OUTLINE_MAX_CHANNEL,
            "outlineDilation": OUTLINE_DILATION,
        },
        "templateSha256": digest,
        "independentQualification": False,
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"pack: {args.pack_id}")
    print(f"template: {out}")
    print(f"sha256: {digest}")
    print(f"manifest: {manifest_path}")
    return 0


def verify(args: argparse.Namespace) -> int:
    template = Path(args.template).expanduser()
    if not template.is_file():
        print(f"MISSING {template}", file=sys.stderr)
        return 1
    actual = sha256_file(template)
    expected = args.expected_sha256
    if expected is None and args.manifest:
        manifest = json.loads(Path(args.manifest).expanduser().read_text(encoding="utf-8"))
        expected = manifest.get("templateSha256")
    if expected is not None:
        expected = expected.strip().lower()
        if expected.startswith("sha256:"):
            expected = expected[len("sha256:") :]
        if actual != expected:
            print(f"DIGEST_MISMATCH expected={expected} actual={actual}", file=sys.stderr)
            return 1
    print(f"AVAILABLE {template} sha256={actual}")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    seed_parser = subparsers.add_parser("seed", help="derive a template pack from a source frame")
    seed_parser.add_argument("--source", required=True, help="private source video file")
    seed_parser.add_argument("--ordinal", required=True, type=int, help="0-based source frame")
    seed_parser.add_argument("--crop", required=True, help="X,Y,WIDTH,HEIGHT in source pixels")
    seed_parser.add_argument("--pack-id", required=True, help="effective pack identity")
    seed_parser.add_argument("--out", required=True, help="template PNG to write")
    seed_parser.add_argument("--manifest", required=True, help="provenance manifest to write")
    seed_parser.add_argument("--hotspot", help="hotspot X,Y inside the template")
    seed_parser.set_defaults(handler=seed)

    verify_parser = subparsers.add_parser("verify", help="verify a provisioned template PNG")
    verify_parser.add_argument("--template", required=True)
    verify_parser.add_argument("--expected-sha256")
    verify_parser.add_argument("--manifest")
    verify_parser.set_defaults(handler=verify)

    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

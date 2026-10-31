#!/usr/bin/env python3
"""Bounded ffprobe wrapper for external video import (SC-2150).

Reads stream/format metadata for a single local media file and writes canonical JSON. It performs
no policy decisions: limits are applied by the Java job service. The probe is deliberately
constrained so an untrusted container cannot trigger network access or unbounded analysis:

  * ``-protocol_whitelist file`` refuses any non-local protocol referenced by the media;
  * ``-probesize`` / ``-analyzeduration`` bound how much data ffprobe inspects;
  * the child process runs under a hard wall-clock timeout.

Output (stdout path given by ``--output``):

    {"ok": true, "hasVideo": true, "container": "mov,mp4", "durationSeconds": 12.34,
     "width": 1920, "height": 1080, "fps": 30.0, "frameCount": 369, "videoCodec": "h264",
     "rotationDegrees": 0, "audioStreams": 0}

On failure it writes ``{"ok": false, "code": <stable code>, "message": <bounded>}`` and exits 2.
No raw media content, title, or path is written to the output.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time

PROBE_TIMEOUT_SECONDS = 25
MAX_OUTPUT_BYTES = 4 * 1024 * 1024


def _parse_rate(value: str | None) -> float:
    if not value or value in ("0/0", "N/A"):
        return 0.0
    if "/" in value:
        numerator, _, denominator = value.partition("/")
        try:
            den = float(denominator)
            return float(numerator) / den if den else 0.0
        except ValueError:
            return 0.0
    try:
        return float(value)
    except ValueError:
        return 0.0


def _rotation(stream: dict) -> int:
    for side in stream.get("side_data_list", []) or []:
        if "rotation" in side:
            try:
                return int(round(float(side["rotation"]))) % 360
            except (TypeError, ValueError):
                pass
    tags = stream.get("tags") or {}
    if "rotate" in tags:
        try:
            return int(round(float(tags["rotate"]))) % 360
        except (TypeError, ValueError):
            return 0
    return 0


def _run_bounded(ffprobe: str, media_path: str) -> tuple[int, bytes, bytes]:
    """Run ffprobe and drain stdout/stderr into bounded buffers.

    ``capture_output`` followed by slicing is not a memory cap: ffprobe can still allocate an
    unbounded buffer before the slice. This helper reads fixed-size chunks, stops and kills the
    child once either buffer exceeds its bound, and therefore bounds actual memory use.
    """
    command = [
        ffprobe,
        "-v",
        "error",
        "-protocol_whitelist",
        "file",
        "-probesize",
        "5M",
        "-analyzeduration",
        "5M",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        media_path,
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout = bytearray()
    stderr = bytearray()
    overflow = False
    try:
        import selectors

        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        selector.register(process.stderr, selectors.EVENT_READ)
        deadline = time.monotonic() + PROBE_TIMEOUT_SECONDS
        while selector.get_map():
            if time.monotonic() > deadline:
                process.kill()
                break
            for key, _ in selector.select(timeout=0.2):
                chunk = key.fileobj.read(65536)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                if key.fileobj is process.stdout:
                    stdout.extend(chunk)
                    if len(stdout) > MAX_OUTPUT_BYTES:
                        overflow = True
                elif len(stderr) < 4096:
                    stderr.extend(chunk[: 4096 - len(stderr)])
                if overflow:
                    break
            if overflow:
                process.kill()
                break
    finally:
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
        try:
            process.stdout.close()
            process.stderr.close()
        except OSError:
            pass
    if overflow:
        return -1, bytes(stdout[:MAX_OUTPUT_BYTES]), bytes(stderr)
    return process.returncode, bytes(stdout), bytes(stderr)


def probe(ffprobe: str, media_path: str) -> dict:
    """Run ffprobe with bounded, network-disabled analysis and return normalized facts."""
    returncode, stdout, stderr = _run_bounded(ffprobe, media_path)
    if returncode == -1:
        return {
            "ok": False,
            "code": "MEDIA_LIMIT",
            "message": "ffprobe output exceeded the bounded probe buffer",
        }
    if returncode != 0 or not stdout:
        message = stderr.decode("utf-8", "replace").strip()[:400]
        return {
            "ok": False,
            "code": "DECODER_FAILED",
            "message": message or "ffprobe could not read the media",
        }
    try:
        document = json.loads(stdout)
    except json.JSONDecodeError:
        return {"ok": False, "code": "DECODER_FAILED", "message": "ffprobe produced invalid JSON"}

    streams = document.get("streams") or []
    video = next((s for s in streams if s.get("codec_type") == "video"), None)
    audio_streams = sum(1 for s in streams if s.get("codec_type") == "audio")
    fmt = document.get("format") or {}
    if video is None:
        return {
            "ok": True,
            "hasVideo": False,
            "container": str(fmt.get("format_name") or ""),
            "durationSeconds": float(fmt.get("duration") or 0.0),
            "width": 0,
            "height": 0,
            "fps": 0.0,
            "frameCount": 0,
            "videoCodec": "",
            "rotationDegrees": 0,
            "audioStreams": audio_streams,
        }

    duration = _as_float(fmt.get("duration")) or _as_float(video.get("duration"))
    frame_count = _as_int(video.get("nb_frames"))
    fps = _parse_rate(video.get("avg_frame_rate")) or _parse_rate(video.get("r_frame_rate"))
    return {
        "ok": True,
        "hasVideo": True,
        "container": str(fmt.get("format_name") or ""),
        "durationSeconds": duration,
        "width": _as_int(video.get("width")),
        "height": _as_int(video.get("height")),
        "fps": fps,
        "frameCount": frame_count,
        "videoCodec": str(video.get("codec_name") or ""),
        "rotationDegrees": _rotation(video),
        "audioStreams": audio_streams,
    }


def _as_float(value) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _as_int(value) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Bounded external video probe")
    parser.add_argument("--ffprobe", required=True)
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args(argv)

    if not os.path.isfile(args.input):
        result = {"ok": False, "code": "NOT_FOUND", "message": "Input media is not a readable file"}
    else:
        try:
            result = probe(args.ffprobe, args.input)
        except subprocess.TimeoutExpired:
            result = {"ok": False, "code": "WORKER_TIMEOUT", "message": "Probe exceeded its time limit"}
        except OSError:
            result = {
                "ok": False,
                "code": "TOOL_UNAVAILABLE",
                "message": "ffprobe is not available to the worker",
            }

    _atomic_write(args.output, json.dumps(result, sort_keys=True))
    return 0 if result.get("ok") else 2


def _atomic_write(path: str, payload: str) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    handle, temporary = tempfile.mkstemp(dir=directory, suffix=".tmp")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(payload)
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except OSError:
            pass
        raise


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

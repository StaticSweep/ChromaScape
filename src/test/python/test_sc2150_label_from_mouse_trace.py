"""Bounded tests for scripts/sc2150/label_from_mouse_trace.py (SC-2150 W12 native labels).

The converter turns micro6's Bot Mouse trace into per-frame labels by the shared monotonic clock.
These tests pin the alignment rule (last render at or before the capture), the stale-render drop,
and the X11 recorder's bracket rule: a render inside (requestNanos, captureNanos] may or may not
be in the image, so the frame is dropped. They run on synthetic files; video_size is stubbed, so
no media or ffprobe is needed.
"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "sc2150" / "label_from_mouse_trace.py"

SPEC = importlib.util.spec_from_file_location("label_from_mouse_trace", SCRIPT)
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)

MS = 1_000_000
START_NANOS = 1_000_000 * MS
START_EPOCH = 1_793_000_000_000


def render(seq, at_ms, x, y, visible=True):
    return {"seq": seq, "epochMs": START_EPOCH + at_ms, "nanos": START_NANOS + at_ms * MS,
            "visible": visible, "x": x, "y": y, "trail": 0, "canvas": [765, 503],
            "stretched": False, "stretchedSize": [765, 503], "human": [-1, -1]}


def write_jsonl(path, rows):
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


class LabelFromMouseTraceTest(unittest.TestCase):
    def convert(self, frames, renders):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            video = root / "rec.mp4"
            video.write_bytes(b"not decoded: video_size is stubbed")
            header = {"schema": "chromascape-recording-frames/v1", "kind": "header",
                      "startEpochMs": START_EPOCH, "startNanos": START_NANOS, "fps": 30}
            write_jsonl(root / "rec.frames.jsonl", [header] + frames)
            trace_header = {"schema": "micro6-mouse-trace/v1", "kind": "header",
                            "startEpochMs": START_EPOCH, "startNanos": START_NANOS}
            write_jsonl(root / "rec.mouse-trace.jsonl", [trace_header] + renders)
            out = root / "labels.json"
            argv = ["label_from_mouse_trace.py", "--recording", str(video), "--out", str(out),
                    "--split", "dev"]
            with mock.patch.object(sys, "argv", argv), \
                    mock.patch.object(CONVERTER, "video_size", return_value=(765, 503)), \
                    redirect_stdout(StringIO()):
                CONVERTER.main()
            return json.loads(out.read_text())

    def frame(self, number, capture_ms, request_ms=None, duplicate=False):
        row = {"frame": number, "captureNanos": START_NANOS + capture_ms * MS,
               "duplicate": duplicate}
        if request_ms is not None:
            row["requestNanos"] = START_NANOS + request_ms * MS
        return row

    def test_labels_each_frame_with_the_last_render_before_its_capture(self):
        document = self.convert(
            [self.frame(0, 15), self.frame(1, 35)],
            [render(0, 10, 100, 200), render(1, 30, 110, 210), render(2, 40, 120, 220)])
        first, second = document["labels"]
        self.assertEqual((first["state"], first["x"], first["y"]), ("VISIBLE", 100, 200))
        self.assertEqual((second["x"], second["y"], second["render_seq"]), (110, 210, 1))

    def test_drops_a_frame_whose_last_visible_render_is_too_old(self):
        document = self.convert([self.frame(0, 200)], [render(0, 10, 100, 200)])
        label = document["labels"][0]
        self.assertTrue(label["dropped"])
        self.assertEqual(document["counts"]["dropped"], 1)

    def test_drops_a_frame_when_a_render_falls_inside_its_grab_bracket(self):
        document = self.convert(
            [self.frame(0, 34, request_ms=28), self.frame(1, 50, request_ms=45)],
            [render(0, 20, 100, 200), render(1, 30, 110, 210), render(2, 40, 120, 220)])
        uncertain, settled = document["labels"]
        self.assertTrue(uncertain["dropped"])
        self.assertEqual(uncertain["drop_reason"], "render during capture")
        self.assertEqual((settled["state"], settled["x"], settled["render_seq"]),
                         ("VISIBLE", 120, 2))
        self.assertEqual(document["counts"], {"visible": 1, "absent": 0, "dropped": 1})

    def test_without_request_stamps_the_bracket_rule_is_off(self):
        # Windows sidecars carry only captureNanos; their labelling is unchanged.
        document = self.convert([self.frame(0, 34)],
                                [render(0, 20, 100, 200), render(1, 30, 110, 210)])
        self.assertEqual(document["labels"][0]["render_seq"], 1)

    def test_uses_the_per_frame_media_time_when_the_sidecar_records_it(self):
        frames = [self.frame(0, 15), self.frame(1, 35)]
        frames[1]["ptsMicros"] = 100_000  # a skipped slot: frame 1 is shown at 0.1 s, not 1/30 s
        document = self.convert(frames, [render(0, 10, 100, 200), render(1, 30, 110, 210)])
        first, second = document["labels"]
        self.assertAlmostEqual(first["pts_time"], 0.0)
        self.assertAlmostEqual(second["pts_time"], 0.1)

    def test_hidden_marker_labels_absent(self):
        document = self.convert(
            [self.frame(0, 500)],
            [render(0, 10, 100, 200), render(1, 20, 0, 0, visible=False)])
        self.assertEqual(document["labels"][0]["state"], "ABSENT")


if __name__ == "__main__":
    unittest.main()

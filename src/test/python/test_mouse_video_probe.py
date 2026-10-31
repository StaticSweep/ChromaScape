"""Bounded tests for the SC-2150 external-video probe worker.

These tests exercise the packaged probe.py against a real ffmpeg-generated clip. They are skipped
when ffmpeg or ffprobe are unavailable rather than being reported as passing.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PROBE = Path(__file__).resolve().parents[2] / "main" / "resources" / "mouse-video" / "probe.py"


@unittest.skipUnless(shutil.which("ffprobe") and shutil.which("ffmpeg"), "ffmpeg/ffprobe required")
class MouseVideoProbeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.clip = self.root / "clip.mp4"
        subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-f",
                "lavfi",
                "-i",
                "testsrc=duration=1:size=64x48:rate=10",
                "-pix_fmt",
                "yuv420p",
                "-y",
                str(self.clip),
            ],
            check=True,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def _run(self, media: Path) -> dict:
        output = self.root / "probe.json"
        completed = subprocess.run(
            [
                sys.executable,
                str(PROBE),
                "--ffprobe",
                shutil.which("ffprobe"),
                "--input",
                str(media),
                "--output",
                str(output),
            ],
            capture_output=True,
            timeout=60,
            check=False,
        )
        self.assertTrue(output.is_file(), completed.stderr.decode("utf-8", "replace"))
        return json.loads(output.read_text("utf-8"))

    def test_probes_valid_clip(self) -> None:
        result = self._run(self.clip)
        self.assertTrue(result["ok"])
        self.assertTrue(result["hasVideo"])
        self.assertEqual(result["width"], 64)
        self.assertEqual(result["height"], 48)
        self.assertGreater(result["durationSeconds"], 0)

    def test_missing_file_is_reported(self) -> None:
        result = self._run(self.root / "absent.mp4")
        self.assertFalse(result["ok"])
        self.assertEqual(result["code"], "NOT_FOUND")


if __name__ == "__main__":
    unittest.main()

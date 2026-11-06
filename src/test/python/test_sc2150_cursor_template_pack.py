"""Tests for scripts/sc2150/cursor_template_pack.py (SC-2150 assisted review provisioning).

The script derives a private cursor template from one exact source frame. These tests pin the
reviewed mask rule, digest verification and the manifest identity so a provisioning run can be
reproduced and a drifted template fails closed.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from PIL import Image

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "sc2150" / "cursor_template_pack.py"
SPEC = importlib.util.spec_from_file_location("cursor_template_pack", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReviewedMaskTest(unittest.TestCase):
    def test_white_body_and_dark_outline_are_opaque_and_background_is_not(self):
        image = Image.new("RGB", (4, 1))
        image.putpixel((0, 0), (240, 245, 250))  # white cursor body
        image.putpixel((1, 0), (20, 25, 30))  # dark outline
        image.putpixel((2, 0), (240, 120, 120))  # coloured art, not the cursor
        image.putpixel((3, 0), (100, 140, 160))  # mid-tone background
        alpha = [pixel for pixel in MODULE.reviewed_mask(image).convert("L").getdata()]
        self.assertEqual(alpha, [255, 255, 0, 0])

    def test_bright_but_chromatic_pixel_is_not_the_white_body(self):
        image = Image.new("RGB", (1, 1), (250, 120, 200))
        self.assertIsNone(MODULE.reviewed_mask(image).getbbox())


class VerifyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.template = self.root / "template.png"
        self.template.write_bytes(b"reviewed-bytes")
        self.digest = hashlib.sha256(b"reviewed-bytes").hexdigest()

    def test_matching_digest_is_available(self):
        self.assertEqual(MODULE.main(["verify", "--template", str(self.template),
                                      "--expected-sha256", self.digest]), 0)
        self.assertEqual(MODULE.main(["verify", "--template", str(self.template),
                                      "--expected-sha256", "sha256:" + self.digest]), 0)

    def test_changed_bytes_fail_closed(self):
        self.assertEqual(MODULE.main(["verify", "--template", str(self.template),
                                      "--expected-sha256", "0" * 64]), 1)

    def test_manifest_digest_is_used_when_no_explicit_pin_is_given(self):
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps({"templateSha256": self.digest}), encoding="utf-8")
        self.assertEqual(MODULE.main(["verify", "--template", str(self.template),
                                      "--manifest", str(manifest)]), 0)


@unittest.skipUnless(shutil.which("ffmpeg"), "ffmpeg is required to extract source frames")
class SeedTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source.mp4"
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
             "-i", "color=c=white:s=16x16:d=0.2", "-frames:v", "3", str(self.source)],
            check=True,
        )

    def test_seed_records_identity_and_verify_accepts_it(self):
        out = self.root / "cursor.png"
        manifest_path = self.root / "manifest.json"
        code = MODULE.main([
            "seed", "--source", str(self.source), "--ordinal", "1",
            "--crop", "0,0,4,4", "--pack-id", "test-pack/v1",
            "--out", str(out), "--manifest", str(manifest_path),
        ])
        self.assertEqual(code, 0)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(manifest["schema"], MODULE.SCHEMA)
        self.assertEqual(manifest["packId"], "test-pack/v1")
        self.assertEqual(manifest["sourceFrameOrdinal"], 1)
        self.assertEqual(manifest["crop"], [0, 0, 4, 4])
        self.assertEqual(manifest["templateSha256"],
                         hashlib.sha256(out.read_bytes()).hexdigest())
        self.assertEqual(Image.open(out).size, (4, 4))
        self.assertEqual(MODULE.main(["verify", "--template", str(out),
                                      "--manifest", str(manifest_path)]), 0)


if __name__ == "__main__":
    unittest.main()

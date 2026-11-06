"""Tests for scripts/sc2150/verify_candidate_extraction.py (SC-2150 delivery bundle, W1).

The R28 review found a staged bundle that matched the working tree but omitted an untracked
browser module and carried a stale test file. These tests build a throwaway Git repository and
pin the three ways a delivery can diverge from the tested candidate (untracked, unstaged, and
committed-but-different), plus Windows-style manifest paths and malformed manifests.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "sc2150" / "verify_candidate_extraction.py"
SPEC = importlib.util.spec_from_file_location("verify_candidate_extraction", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def sha(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


class VerifyCandidateExtractionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "test")
        self.write("a/core.js", b"core-v1")
        self.write("b/test.py", b"test-v1")
        self.git("add", "a/core.js", "b/test.py")
        self.git("commit", "-q", "-m", "base")

    def tearDown(self):
        self.tmp.cleanup()

    def git(self, *args):
        subprocess.run(["git", "-C", str(self.repo), *args], check=True, capture_output=True)

    def write(self, path, data):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def manifest(self, files):
        path = self.repo / "candidate.json"
        path.write_text(json.dumps({"schema": "x", "files": files}), encoding="utf-8")
        return path

    def test_index_matching_every_candidate_file_passes(self):
        manifest = self.manifest({"a/core.js": sha(b"core-v1"), "b/test.py": sha(b"test-v1")})
        self.assertEqual((2, []), MODULE.verify(self.repo, manifest))
        self.assertEqual(0, MODULE.main([str(manifest), "--repo", str(self.repo)]))

    def test_untracked_candidate_file_is_absent(self):
        self.write("a/review-core.js", b"new")
        manifest = self.manifest({"a/core.js": sha(b"core-v1"), "a/review-core.js": sha(b"new")})
        self.assertEqual((1, [("a/review-core.js", "absent")]), MODULE.verify(self.repo, manifest))

    def test_unstaged_edit_is_a_mismatch_until_staged(self):
        self.write("b/test.py", b"test-v2")
        manifest = self.manifest({"b/test.py": sha(b"test-v2")})
        matched, mismatches = MODULE.verify(self.repo, manifest)
        self.assertEqual(0, matched)
        self.assertEqual("b/test.py", mismatches[0][0])
        self.git("add", "b/test.py")
        self.assertEqual((1, []), MODULE.verify(self.repo, manifest))

    def test_rev_mode_checks_the_commit_not_the_index(self):
        self.write("b/test.py", b"test-v2")
        self.git("add", "b/test.py")
        manifest = self.manifest({"b/test.py": sha(b"test-v2")})
        self.assertEqual((1, []), MODULE.verify(self.repo, manifest))
        self.assertEqual(1, len(MODULE.verify(self.repo, manifest, rev="HEAD")[1]))
        self.assertEqual(1, MODULE.main([str(manifest), "--repo", str(self.repo), "--rev", "HEAD"]))

    def test_windows_manifest_paths_are_normalised(self):
        manifest = self.manifest({"a\\core.js": sha(b"core-v1")})
        self.assertEqual((1, []), MODULE.verify(self.repo, manifest))

    def test_malformed_manifests_are_refused(self):
        for document in ({"files": {}}, {"files": {"a/core.js": "md5:00"}}, {"schema": "x"}):
            path = self.repo / "bad.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.subTest(document=document):
                with self.assertRaises(ValueError):
                    MODULE.verify(self.repo, path)


if __name__ == "__main__":
    unittest.main()

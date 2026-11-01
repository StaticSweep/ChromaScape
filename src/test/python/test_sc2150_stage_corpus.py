"""Bounded tests for scripts/sc2150/stage_corpus.py (SC-2150 W10 corpus staging).

The stager is the only step between sealed converter output and the frozen scorer, so it must
refuse substituted media, missing templates, colliding clip ids and a non-empty destination
instead of producing a corpus that silently scores the wrong bytes.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "sc2150" / "stage_corpus.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TOOL = load("stage_corpus", SCRIPT)

SOURCE_A = "11111111-2222-3333-4444-555555555555"
SOURCE_B = "66666666-7777-8888-9999-aaaaaaaaaaaa"


class StageCorpusTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.templates = self.root / "templates"
        self.templates.mkdir()
        (self.templates / "arrow.png").write_bytes(b"\x89PNG\r\n\x1a\narrow")

    def media(self, name, content):
        path = self.root / name
        path.write_bytes(content)
        return path

    def label(self, name, clip_id, source_id, media_path, template="arrow", split="heldout",
              schema="sc2150-fa12-labels/v3"):
        document = {
            "schema": schema,
            "clip_id": clip_id,
            "split": split,
            "source_id": source_id,
            "media_sha256": hashlib.sha256(media_path.read_bytes()).hexdigest(),
            "template": template,
            "labels": [{"ordinal": 0, "pts_time": 0.0, "state": "ABSENT"}],
        }
        path = self.root / name
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def stage(self, labels, media, out):
        return TOOL.stage(labels, media, self.templates, "heldout", out)

    def test_stages_the_layout_the_harness_and_scorer_expect(self):
        first = self.media("a.mp4", b"section-a")
        second = self.media("b.mp4", b"section-b")
        labels = [
            self.label("a.labels.json", "heldout--a--0", SOURCE_A, first),
            self.label("b.labels.json", "heldout--b--1", SOURCE_B, second,
                       schema="sc2150-fa12-labels/v1"),
        ]
        out = self.root / "corpus"

        manifest = self.stage(
            labels, ["%s=%s" % (SOURCE_A, first), "%s=%s" % (SOURCE_B, second)], out
        )

        self.assertEqual([entry["clip_id"] for entry in manifest["clips"]],
                         ["heldout--a--0", "heldout--b--1"])
        for clip_id, media in (("heldout--a--0", b"section-a"), ("heldout--b--1", b"section-b")):
            self.assertEqual((out / "heldout" / (clip_id + ".mp4")).read_bytes(), media)
            self.assertTrue((out / "heldout" / (clip_id + ".labels.json")).is_file())
        self.assertEqual((out / "templates" / "arrow.png").read_bytes(),
                         b"\x89PNG\r\n\x1a\narrow")
        written = json.loads((out / "staging-manifest.json").read_text())
        self.assertEqual(written["schema"], "sc2150-fa12-staging/v1")
        self.assertEqual(written["templates"],
                         {"arrow": hashlib.sha256(b"\x89PNG\r\n\x1a\narrow").hexdigest()})
        for entry in written["clips"]:
            self.assertEqual(entry["media_sha256"],
                             hashlib.sha256((out / "heldout" / (entry["clip_id"] + ".mp4"))
                                            .read_bytes()).hexdigest())

    def test_resolves_media_next_to_the_labels_when_one_source_has_many_clips(self):
        # Synthetic corpora share one source id across variants, so <clip_id>.mp4 is the only
        # unambiguous resolution.
        first = self.media("dev--same--one.mp4", b"variant-one")
        second = self.media("dev--same--two.mp4", b"variant-two")
        labels = [
            self.label("one.labels.json", "dev--same--one", SOURCE_A, first),
            self.label("two.labels.json", "dev--same--two", SOURCE_A, second),
        ]
        out = self.root / "corpus"

        manifest = TOOL.stage(labels, [], self.templates, "heldout", out, self.root)

        self.assertEqual([entry["clip_id"] for entry in manifest["clips"]],
                         ["dev--same--one", "dev--same--two"])
        self.assertEqual((out / "heldout" / "dev--same--one.mp4").read_bytes(), b"variant-one")
        self.assertEqual((out / "heldout" / "dev--same--two.mp4").read_bytes(), b"variant-two")

    def test_refuses_media_that_does_not_match_the_label_digest(self):
        media = self.media("a.mp4", b"section-a")
        labels = [self.label("a.labels.json", "heldout--a--0", SOURCE_A, media)]
        substituted = self.media("swapped.mp4", b"different-bytes")

        with self.assertRaises(TOOL.StagingError):
            self.stage(labels, ["%s=%s" % (SOURCE_A, substituted)], self.root / "corpus")
        self.assertFalse((self.root / "corpus").exists())

    def test_refuses_a_template_the_label_names_but_nothing_provides(self):
        media = self.media("a.mp4", b"section-a")
        labels = [self.label("a.labels.json", "heldout--a--0", SOURCE_A, media,
                             template="dragon-dagger")]

        with self.assertRaises(TOOL.StagingError):
            self.stage(labels, ["%s=%s" % (SOURCE_A, media)], self.root / "corpus")
        self.assertFalse((self.root / "corpus").exists())

    def test_refuses_colliding_clip_ids_and_wrong_splits(self):
        media = self.media("a.mp4", b"section-a")
        duplicate = self.label("a.labels.json", "heldout--a--0", SOURCE_A, media)
        copy = self.label("a-again.labels.json", "heldout--a--0", SOURCE_A, media)
        with self.assertRaises(TOOL.StagingError):
            self.stage([duplicate, copy], ["%s=%s" % (SOURCE_A, media)], self.root / "corpus")

        wrong_split = self.label("dev.labels.json", "heldout--a--1", SOURCE_A, media,
                                 split="dev")
        with self.assertRaises(TOOL.StagingError):
            self.stage([wrong_split], ["%s=%s" % (SOURCE_A, media)], self.root / "corpus-2")

    def test_refuses_an_unmapped_source_and_a_non_empty_destination(self):
        media = self.media("a.mp4", b"section-a")
        labels = [self.label("a.labels.json", "heldout--a--0", SOURCE_A, media)]
        with self.assertRaises(TOOL.StagingError):
            self.stage(labels, ["%s=%s" % (SOURCE_B, media)], self.root / "corpus")

        occupied = self.root / "occupied"
        occupied.mkdir()
        (occupied / "leftover.txt").write_text("x")
        with self.assertRaises(TOOL.StagingError):
            self.stage(labels, ["%s=%s" % (SOURCE_A, media)], occupied)

    def test_manifest_is_byte_identical_across_runs(self):
        media = self.media("a.mp4", b"section-a")
        labels = [self.label("a.labels.json", "heldout--a--0", SOURCE_A, media)]
        assignment = ["%s=%s" % (SOURCE_A, media)]
        self.stage(labels, assignment, self.root / "one")
        self.stage(labels, assignment, self.root / "two")

        def normalized(name):
            document = json.loads((self.root / name / "staging-manifest.json").read_text())
            for entry in document["clips"]:
                entry.pop("label_document")
                entry.pop("media")
            return document

        self.assertEqual(normalized("one"), normalized("two"))


if __name__ == "__main__":
    unittest.main()

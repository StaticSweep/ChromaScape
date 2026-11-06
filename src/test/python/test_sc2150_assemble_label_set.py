"""Bounded tests for scripts/sc2150/assemble_label_set.py (SC-2150 multi-session truth).

The assembler must never certify edited, removed or internally invalid labels. Every fixture here
starts from genuine ``labels_from_review_export.py`` v3 output and its frozen derivation entry, so
the tests exercise the real converter-to-assembler seam rather than hand-authored provenance.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
CONVERTER_SCRIPT = ROOT / "scripts" / "sc2150" / "labels_from_review_export.py"
ASSEMBLER_SCRIPT = ROOT / "scripts" / "sc2150" / "assemble_label_set.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CONVERTER = load("labels_from_review_export_for_assembler", CONVERTER_SCRIPT)
ASSEMBLER = load("assemble_label_set", ASSEMBLER_SCRIPT)

SOURCE_ID = "11111111-2222-3333-4444-555555555555"
ANALYSIS_ID = "66666666-7777-8888-9999-aaaaaaaaaaaa"
SPLIT = "heldout"
FRAME_COUNT = 30
PLANE = {
    "contract": "coordinate-plane/v1",
    "codedWidth": 192,
    "codedHeight": 108,
    "rotationDegrees": 0,
    "displayWidth": 192,
    "displayHeight": 108,
    "origin": "top-left",
    "pixelCenter": True,
}


def visible(ordinal, x=10.0, y=20.0):
    return {
        "kind": "CURSOR_VISIBILITY",
        "frameOrdinal": ordinal,
        "visibility": "VISIBLE",
        "x": x,
        "y": y,
    }


def hidden(ordinal):
    return {
        "kind": "CURSOR_VISIBILITY",
        "frameOrdinal": ordinal,
        "visibility": "HIDDEN",
        "unknownReason": "offscreen",
    }


def ambiguous(ordinal):
    return {
        "kind": "CURSOR_VISIBILITY",
        "frameOrdinal": ordinal,
        "visibility": "AMBIGUOUS",
        "unknownReason": "obscured",
    }


ORIGIN_FOR = {
    "VISIBLE": "MANUAL_VISIBLE",
    "HIDDEN": "MANUAL_HIDDEN",
    "AMBIGUOUS": "MANUAL_AMBIGUOUS",
    "UNSUPPORTED": "MANUAL_AMBIGUOUS",
}


def frame_index_page(frame_count=FRAME_COUNT):
    return {
        "contract": "frame-index-page/v1",
        "schema": "mouse-video-frame-index/v1",
        "artifactChecksum": "sha256:frame-index",
        "complete": True,
        "timeBase": {"numerator": 1, "denominator": 10, "raw": "1/10"},
        "frameCount": frame_count,
        "offset": 0,
        "limit": 100,
        "items": [
            {
                "ordinal": ordinal,
                "pts": str(ordinal),
                "ptsTime": repr(ordinal / 10.0),
                "duration": "1",
                "key": ordinal == 0,
            }
            for ordinal in range(frame_count)
        ],
        "nextCursor": None,
        "boundedByEncodedBytes": False,
    }


def sealed_envelope(session_id, start, end, corrections, plane=None, split=SPLIT):
    plane = plane or PLANE
    session = {
        "id": session_id,
        "sourceId": SOURCE_ID,
        "analysisId": ANALYSIS_ID,
        "state": "SEALED",
        "mode": "WITHOUT_SUGGESTIONS",
        "split": split,
        "policyDigest": "sha256:policy",
        "originalDigest": "sha256:media",
        "frameIndexChecksum": "sha256:frame-index",
        "frameCount": FRAME_COUNT,
        "startOrdinal": start,
        "endOrdinal": end,
        "coordinatePlane": plane,
        "coordinatePlaneDigest": "sha256:plane",
    }
    revision = {
        "id": "revision-" + session_id,
        "sourceId": SOURCE_ID,
        "analysisId": ANALYSIS_ID,
        "actor": "owner",
        "reason": "sealed protocol test",
        "expectedBaseRevision": 0,
        "sourceUse": "INSPECTION_ONLY",
        "state": "REVIEWED",
        "contract": "corrections/v1",
        "corrections": corrections,
        "sessionId": session_id,
        "appliedSourceRevision": 1,
        "provenance": {
            "session": dict(session),
            "origins": {
                str(entry["frameOrdinal"]): ORIGIN_FOR[entry["visibility"]]
                for entry in corrections
            },
            "suggestionsExposed": False,
        },
    }
    revision_json = json.dumps(revision, separators=(",", ":"))
    return {
        "contract": "mouse-video-review-export/v2",
        "protocol": "review-provenance/v1",
        "session": session,
        "revision": revision,
        "revisionJson": revision_json,
        "contentDigest": "sha256:" + hashlib.sha256(revision_json.encode()).hexdigest(),
        "independentProtocol": True,
        "attestation": True,
    }


def converter_options():
    return SimpleNamespace(
        split=SPLIT,
        labeller="owner",
        labelling_protocol="without-suggestions",
        source_size=(192, 108),
        output_size=(96, 54),
        fps=10.0,
        clip_id=None,
        template="reviewed-cursor",
        hotspot="cursor hotspot",
        label_stride=1,
        export_id=None,
        verify_server="http://127.0.0.1:18190",
        session_export=None,
        export=None,
        review=None,
        frame_index=[],
    )


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def convert(self, name, session_id, start, end, corrections, plane=None):
        envelope = sealed_envelope(session_id, start, end, corrections, plane=plane)
        export_path = self.root / (name + ".export.json")
        export_path.write_text(json.dumps(envelope), encoding="utf-8")
        pages = [self.root / (name + ".frames.json")]
        pages[0].write_text(json.dumps(frame_index_page()), encoding="utf-8")
        with patch.object(
            CONVERTER, "fetch_sealed_export", return_value=export_path.read_bytes()
        ):
            document = CONVERTER.convert_session_export(export_path, pages, converter_options())
        labels_path = self.root / (name + ".labels.json")
        serialized = (json.dumps(document, indent=1) + "\n").encode("utf-8")
        labels_path.write_bytes(serialized)
        entry = CONVERTER.derivation_entry(
            document, "sha256:" + hashlib.sha256(serialized).hexdigest()
        )
        return labels_path, document, entry

    def freeze(self, entries):
        manifest_path = self.root / "derivation.json"
        manifest = {"schema": "sc2150-fa12-derivation/v1", "inputs": entries}
        raw = (json.dumps(manifest, indent=1) + "\n").encode("utf-8")
        manifest_path.write_bytes(raw)
        return manifest_path, "sha256:" + hashlib.sha256(raw).hexdigest()

    def assemble(self, paths, entries=None, **kwargs):
        if entries is None:
            entries = [self.entry_for(path) for path in paths]
        manifest, digest = self.freeze(entries)
        return ASSEMBLER.assemble(
            [str(path) for path in paths], manifest=str(manifest), manifest_digest=digest, **kwargs
        )

    def entry_for(self, path):
        manifest = json.loads((self.root / "derivation.json").read_text(encoding="utf-8"))
        for entry in manifest["inputs"]:
            if entry["label_document_sha256"] == "sha256:" + hashlib.sha256(
                path.read_bytes()
            ).hexdigest():
                return entry
        raise AssertionError("no entry")

    def refreeze(self, paths):
        # Rebuild every entry from the current on-disk document (used to prove intrinsic checks).
        entries = []
        for path in paths:
            document = json.loads(path.read_text(encoding="utf-8"))
            entries.append(
                CONVERTER.derivation_entry(
                    document, "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
                )
            )
        return entries

    def test_adjacent_sessions_assemble_into_one_sorted_label_set(self):
        first, _, first_entry = self.convert(
            "s1", "s1", 0, 14, [hidden(0), visible(2), visible(3), ambiguous(5)]
        )
        second, _, second_entry = self.convert("s2", "s2", 15, 29, [hidden(15), visible(18)])
        result = self.assemble([second, first], [first_entry, second_entry])
        self.assertEqual(result["schema"], "sc2150-fa12-labelset/v1")
        self.assertEqual([entry["ordinal"] for entry in result["labels"]], [0, 2, 3, 5, 15, 18])
        self.assertEqual(result["counts"], {"VISIBLE": 3, "ABSENT": 2, "UNKNOWN": 1})
        self.assertEqual(
            [session["range"] for session in result["sessions"]],
            [[0, 14], [15, 29]],
        )
        self.assertFalse(result["training_eligible"])
        self.assertFalse(result["human_input_verified"])
        self.assertTrue(result["integrity"]["label_index_sha256"].startswith("sha256:"))
        self.assertTrue(result["provenance"]["derivation_manifest_sha256"].startswith("sha256:"))

    def test_edited_label_cannot_keep_its_provenance(self):
        path, document, entry = self.convert("s1", "s1", 0, 14, [hidden(0), visible(2)])
        document["labels"][0].update(state="VISIBLE", visible=True, x=1_000_000_000, y=-3)
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "differs from its frozen derivation"):
            self.assemble([path], [entry])

    def test_stale_label_index_is_refused_even_when_the_file_digest_is_updated(self):
        path, document, entry = self.convert("s1", "s1", 0, 14, [hidden(0), visible(2)])
        document["labels"][0].update(state="VISIBLE", visible=True, x=10.0, y=20.0)
        path.write_text(json.dumps(document), encoding="utf-8")
        entry = dict(entry)
        entry["label_document_sha256"] = "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "edited or removed labels"):
            self.assemble([path], [entry])

    def test_removed_labels_are_refused(self):
        path, document, entry = self.convert("s1", "s1", 0, 14, [hidden(0), hidden(1)])
        document["labels"] = []
        document["counts"].update(visible=0, absent=0, dropped=0)
        document["coverage"]["labelled_frames"] = 0
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "differs from its frozen derivation"):
            self.assemble([path], [entry])

    def test_empty_session_is_refused_even_with_a_refrozen_manifest(self):
        path, document, _ = self.convert("s1", "s1", 0, 14, [hidden(0)])
        document["labels"] = []
        document["counts"].update(visible=0, absent=0, dropped=0)
        document["coverage"]["labelled_frames"] = 0
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "cannot be empty"):
            self.assemble([path], self.refreeze([path]), require_ranges=["0:14"])

    def test_out_of_bounds_and_non_finite_points_are_refused(self):
        path, document, _ = self.convert("s1", "s1", 0, 14, [visible(0)])
        document["labels"][0].update(x=1_000_000_000, y=-3)
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "outside the 96x54 output plane"):
            self.assemble([path], self.refreeze([path]))
        document["labels"][0].update(x=float("nan"), y=1.0)
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "non-finite or missing x"):
            self.assemble([path], self.refreeze([path]))

    def test_state_and_origin_must_agree(self):
        path, document, _ = self.convert("s1", "s1", 0, 14, [hidden(0)])
        document["labels"][0]["label_origin"] = "MANUAL_VISIBLE"
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "contradicts origin"):
            self.assemble([path], self.refreeze([path]))

    def test_visible_label_cannot_be_dropped(self):
        path, document, _ = self.convert("s1", "s1", 0, 14, [visible(0)])
        document["labels"][0]["dropped"] = True
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "contradictory dropped"):
            self.assemble([path], self.refreeze([path]))

    def test_non_boolean_dropped_is_refused(self):
        for dropped in ("true", 1, None):
            with self.subTest(dropped=dropped):
                path, document, _ = self.convert("s1", "s1", 0, 14, [visible(0)])
                document["labels"][0]["dropped"] = dropped
                path.write_text(json.dumps(document), encoding="utf-8")
                with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "non-boolean dropped"):
                    self.assemble([path], self.refreeze([path]))

    def test_absent_label_cannot_be_dropped(self):
        path, document, _ = self.convert("s1", "s1", 0, 14, [hidden(0)])
        document["labels"][0]["dropped"] = True
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "contradictory dropped"):
            self.assemble([path], self.refreeze([path]))

    def test_unknown_label_must_be_dropped(self):
        path, document, _ = self.convert("s1", "s1", 0, 14, [ambiguous(0)])
        document["labels"][0].pop("dropped")
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "contradictory dropped"):
            self.assemble([path], self.refreeze([path]))
        path, document, _ = self.convert("s2", "s2", 15, 29, [ambiguous(15)])
        document["labels"][0]["dropped"] = False
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "contradictory dropped"):
            self.assemble([path], self.refreeze([path]))

    def test_malformed_range_and_duplicate_session_are_refused(self):
        first, document, entry = self.convert("s1", "s1", 0, 14, [hidden(0)])
        document["provenance"]["review_range"] = [14, 0]
        first.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "malformed review range"):
            self.assemble([first], self.refreeze([first]))
        second, _, second_entry = self.convert("s2", "s2", 15, 29, [hidden(15)])
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "repeats a session"):
            self.assemble([second, second], [second_entry])

    def test_mismatched_projection_and_overlap_are_refused(self):
        first, _, first_entry = self.convert("s1", "s1", 0, 20, [hidden(0)])
        second, _, second_entry = self.convert("s2", "s2", 10, 29, [hidden(10)])
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "overlap"):
            self.assemble([first, second], [first_entry, second_entry])
        rotated = dict(PLANE, origin="bottom-left")
        other, _, other_entry = self.convert("s3", "s3", 21, 29, [hidden(21)], plane=rotated)
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "coordinate_plane"):
            self.assemble([first, other], [first_entry, other_entry])

    def test_missing_or_untrusted_manifest_is_refused(self):
        path, _, _ = self.convert("s1", "s1", 0, 14, [hidden(0)])
        manifest, digest = self.freeze(self.refreeze([path]))
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "trusted --manifest-digest"):
            ASSEMBLER.assemble([str(path)])
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "does not match the trusted digest"):
            ASSEMBLER.assemble(
                [str(path)], manifest=str(manifest), manifest_digest="sha256:" + "0" * 64
            )

    def test_required_range_and_stride_must_be_covered(self):
        first, _, first_entry = self.convert("s1", "s1", 0, 14, [hidden(0), hidden(3)])
        second, _, second_entry = self.convert("s2", "s2", 15, 29, [hidden(15), hidden(18)])
        result = self.assemble(
            [first, second], [first_entry, second_entry], require_ranges=["0:29"]
        )
        self.assertEqual(len(result["sessions"]), 2)
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "required range"):
            self.assemble([first], [first_entry], require_ranges=["0:29"])
        with self.assertRaisesRegex(ASSEMBLER.AssemblyError, "required stride ordinals"):
            self.assemble([first], [first_entry], require_stride=1)


if __name__ == "__main__":
    unittest.main()

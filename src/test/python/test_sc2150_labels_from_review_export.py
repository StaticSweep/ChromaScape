"""Bounded tests for scripts/sc2150/labels_from_review_export.py (SC-2150 T2).

The converter is the only path from what a labeller did in the review screen to what the FA12
scorer reads, so these tests pin three things: the mapping of review corrections onto scored
states, the exclusion of unlabelled frames, and the provenance that makes a held-out label
auditable (RF2). They run on synthetic documents and need no media.
"""

from __future__ import annotations

import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "sc2150" / "labels_from_review_export.py"

SPEC = importlib.util.spec_from_file_location("labels_from_review_export", SCRIPT)
CONVERTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONVERTER)

SOURCE_ID = "11111111-2222-3333-4444-555555555555"
ANALYSIS_ID = "66666666-7777-8888-9999-aaaaaaaaaaaa"
REVISION_ID = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"


def export_manifest(previews=None):
    return {
        "contract": "mouse-video-evidence/v1",
        "contractVersion": "export/v1",
        "exportVersion": "sha256:manifest-version",
        "source": {"id": SOURCE_ID, "kind": "UPLOAD", "owner": "owner", "lifecycle": "READY"},
        "acquisition": {"originalChecksum": "sha256:media-digest", "originalQualified": True},
        "media": {"originalDigest": "media-digest", "status": "READY"},
        "analysis": {"available": True, "id": ANALYSIS_ID, "algorithmId": "visual/v1",
                     "configDigest": "sha256:config"},
        "artifacts": [],
        "previews": previews or [],
        "review": {"revision": 1, "count": 1, "latestId": REVISION_ID, "state": "OPEN"},
        "capabilities": {"nativeTelemetry": False},
        "notes": [],
    }


def review_document(corrections, revision_id=REVISION_ID):
    return {
        "id": revision_id,
        "sourceId": SOURCE_ID,
        "analysisId": ANALYSIS_ID,
        "predecessorId": None,
        "supersededBy": None,
        "actor": "owner",
        "reason": "labelled from the review screen",
        "expectedBaseRevision": 0,
        "sourceUse": "INSPECTION_ONLY",
        "state": "OPEN",
        "contract": "corrections/v1",
        "corrections": corrections,
        "createdAt": "2026-10-23T00:00:00Z",
    }


def frame_index_pages(frame_count=4, fps=10.0, times=None):
    items = []
    for ordinal in range(frame_count):
        pts_time = times[ordinal] if times else ordinal / fps
        items.append({"ordinal": ordinal, "pts": str(ordinal), "ptsTime": repr(pts_time),
                      "duration": "1", "key": ordinal == 0})
    page = {
        "contract": "frame-index-page/v1",
        "schema": "mouse-video-frame-index/v1",
        "artifactChecksum": "sha256:frame-index",
        "complete": True,
        "timeBase": {"numerator": 1, "denominator": 10, "raw": "1/10"},
        "frameCount": frame_count,
        "offset": 0,
        "limit": 100,
        "items": items,
        "nextCursor": None,
        "boundedByEncodedBytes": False,
    }
    return [page]


def visible(ordinal, x, y, **extra):
    return dict({"kind": "CURSOR_VISIBILITY", "frameOrdinal": ordinal, "visibility": "VISIBLE",
                 "x": x, "y": y}, **extra)


def hidden(ordinal, reason="offscreen"):
    return {"kind": "CURSOR_VISIBILITY", "frameOrdinal": ordinal, "visibility": "HIDDEN",
            "unknownReason": reason}


def ambiguous(ordinal):
    return {"kind": "CURSOR_VISIBILITY", "frameOrdinal": ordinal, "visibility": "AMBIGUOUS",
            "unknownReason": "cursor obscured by chat"}


class ConverterTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, name, document):
        path = self.root / name
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def options(self, **overrides):
        values = {
            "export_id": "export-1",
            "split": "dev",
            "labeller": "claude",
            "labelling_protocol": "without-suggestions",
            "source_size": (192, 108),
            "output_size": (96, 54),
            "fps": None,
            "clip_id": "dev--review--clip",
            "template": "rs3-gold",
            "hotspot": "cursor hotspot",
            "label_stride": 3,
        }
        values.update(overrides)
        return type("Options", (), values)()

    def convert(self, corrections, pages=None, export=None, review=None, **overrides):
        export_path = self.write("export.json", export if export is not None
                                 else export_manifest())
        review_path = self.write("review.json", review if review is not None
                                 else review_document(corrections))
        page_paths = [self.write("frame-index-%d.json" % index, page)
                      for index, page in enumerate(pages or frame_index_pages())]
        return CONVERTER.convert_paths(export_path, review_path, page_paths,
                                       self.options(**overrides))

    def test_v2_preserves_assertions_but_refuses_heldout_qualification(self):
        corrections = [visible(0, 10, 10, provenance="BLIND_UNVERIFIED")]
        review = review_document(corrections)
        review["contract"] = "corrections/v2"
        document = self.convert(corrections, review=review)
        self.assertFalse(document["provenance"]["independence_verified"])
        self.assertEqual(document["provenance"]["label_provenance"], ["BLIND_UNVERIFIED"])
        with self.assertRaisesRegex(CONVERTER.ConversionError, "trusted server review-session"):
            self.convert(corrections, review=review, split="heldout")
        corrections[0]["provenance"] = "INDEPENDENT"
        with self.assertRaisesRegex(CONVERTER.ConversionError, "uncertified independent"):
            self.convert(corrections, review=review)

    def test_visible_hidden_and_ambiguous_map_onto_scored_states(self):
        document = self.convert([visible(0, 100, 40), hidden(1), ambiguous(2)])
        labels = {label["ordinal"]: label for label in document["labels"]}
        self.assertEqual(sorted(labels), [0, 1, 2])
        self.assertEqual(labels[0]["state"], "VISIBLE")
        self.assertTrue(labels[0]["visible"])
        self.assertAlmostEqual(labels[0]["x"], (100 + 0.5) * 96 / 192 - 0.5, places=4)
        self.assertAlmostEqual(labels[0]["y"], (40 + 0.5) * 54 / 108 - 0.5, places=4)
        self.assertEqual(labels[1]["state"], "ABSENT")
        self.assertFalse(labels[1]["visible"])
        self.assertEqual(labels[2]["state"], "UNKNOWN")
        self.assertTrue(labels[2]["dropped"])
        self.assertNotIn("x", labels[2])
        self.assertEqual(document["px_to_1080p"], 20.0)
        self.assertEqual(document["compose_size"], [96, 54])

    def test_unlabelled_frames_are_excluded_not_interpolated(self):
        document = self.convert([visible(0, 10, 10), visible(2, 20, 20)])
        self.assertEqual([label["ordinal"] for label in document["labels"]], [0, 2])
        self.assertEqual(document["coverage"]["labelled_frames"], 2)
        self.assertEqual(document["coverage"]["frame_count"], 4)

    def test_last_correction_for_a_frame_wins(self):
        document = self.convert([hidden(0), visible(0, 30, 30)])
        self.assertEqual(len(document["labels"]), 1)
        self.assertEqual(document["labels"][0]["state"], "VISIBLE")
        self.assertEqual(document["counts"]["superseded_cursor_corrections"], 1)

    def test_cuts_and_additional_spans_are_preserved_for_w8(self):
        corrections = [
            {"kind": "SCENE_CUT", "beforeFrameOrdinal": 1, "afterFrameOrdinal": 2},
            {"kind": "PAUSE", "startFrameOrdinal": 0, "endFrameOrdinal": 2},
            {"kind": "TARGET_REGION", "frameOrdinal": 1, "x": 4, "y": 4, "width": 20,
             "height": 20, "label": "bank booth"},
            {"kind": "CAMERA_ACTIVITY", "frameOrdinal": 1, "activity": "VIEWPORT_MOTION"},
        ]
        document = self.convert(corrections)
        self.assertEqual(document["cut_compose_ordinals"], [2])
        kinds = [span["kind"] for span in document["additional_spans"]]
        self.assertEqual(sorted(kinds), ["CAMERA_ACTIVITY", "PAUSE", "TARGET_REGION"])
        pause = next(span for span in document["additional_spans"] if span["kind"] == "PAUSE")
        self.assertEqual(pause["pts_times"]["2"], 0.2)

    def test_provenance_names_the_labeller_export_and_revision(self):
        document = self.convert([visible(0, 5, 5)], labeller="owner")
        provenance = document["provenance"]
        self.assertEqual(provenance["labeller"], "owner")
        self.assertEqual(provenance["labelling_protocol"], "without-suggestions")
        self.assertEqual(provenance["export_id"], "export-1")
        self.assertEqual(provenance["review_revision_id"], REVISION_ID)
        self.assertEqual(provenance["review_pinned_revision"], 1)
        self.assertEqual(provenance["analysis_id"], ANALYSIS_ID)
        self.assertEqual(len(provenance["export_manifest_sha256"]), 64)
        self.assertEqual(len(provenance["corrections_sha256"]), 64)
        self.assertEqual(len(provenance["frame_index_checksum"]), 64)
        self.assertEqual(document["labels"][0]["labeller"], "owner")

    def test_refuses_a_revision_the_export_does_not_pin(self):
        later = review_document([visible(0, 5, 5)], "dddddddd-eeee-ffff-0000-111111111111")
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([visible(0, 5, 5)], review=later)
        self.assertIn("not the revision the export pins", str(raised.exception))

    def test_legacy_without_provenance_cannot_bypass_qualification_gate(self):
        with self.assertRaisesRegex(CONVERTER.ConversionError, "trusted server review-session"):
            self.convert([visible(0, 5, 5)], split="heldout")

    def test_refuses_detector_assisted_heldout_labels(self):
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([visible(0, 5, 5)], split="heldout",
                         labelling_protocol="detector-assisted")
        self.assertIn("without detector suggestions", str(raised.exception))

    def test_allows_detector_assisted_dev_labels(self):
        document = self.convert([visible(0, 5, 5)], labelling_protocol="detector-assisted")
        self.assertEqual(document["provenance"]["labelling_protocol"], "detector-assisted")

    def test_refuses_a_blank_labeller(self):
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([visible(0, 5, 5)], labeller="  ")
        self.assertIn("--labeller is required", str(raised.exception))

    def test_refuses_a_cursor_outside_the_declared_source_size(self):
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([visible(0, 500, 5)])
        self.assertIn("outside the declared source size", str(raised.exception))

    def test_refuses_an_incomplete_frame_index(self):
        pages = frame_index_pages()
        pages[0]["items"] = pages[0]["items"][:2]
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([visible(0, 5, 5)], pages=pages)
        self.assertIn("frame index is incomplete", str(raised.exception))

    def test_refuses_a_variable_rate_index_without_an_explicit_rate(self):
        pages = frame_index_pages(times=[0.0, 0.1, 0.25, 0.3])
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([visible(0, 5, 5)], pages=pages)
        self.assertIn("variable-rate", str(raised.exception))

    def test_uses_an_explicit_rate_for_a_variable_rate_index(self):
        pages = frame_index_pages(times=[0.0, 0.1, 0.25, 0.3])
        document = self.convert([visible(0, 5, 5)], pages=pages, fps=30.0)
        self.assertEqual(document["fps_compose"], 30.0)

    def test_refuses_preview_geometry_that_contradicts_the_declared_source_size(self):
        export = export_manifest(previews=[{"frameOrdinal": 0, "sourceWidth": 1280,
                                            "sourceHeight": 720, "outputWidth": 480,
                                            "outputHeight": 270}])
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([visible(0, 5, 5)], export=export)
        self.assertIn("disagrees with the export's preview geometry", str(raised.exception))

    def test_refuses_an_unknown_correction_kind(self):
        with self.assertRaises(CONVERTER.ConversionError) as raised:
            self.convert([{"kind": "NATIVE_CLICK", "frameOrdinal": 0}])
        self.assertIn("unsupported kind", str(raised.exception))

    def test_cli_writes_a_document_that_the_scorer_schema_accepts(self):
        export_path = self.write("export.json", export_manifest())
        review_path = self.write("review.json", review_document([visible(1, 60, 30)]))
        page_path = self.write("frame-index.json", frame_index_pages()[0])
        out = self.root / "labels" / "clip.labels.json"
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            status = CONVERTER.main([
                "--export", str(export_path), "--export-id", "export-1",
                "--review", str(review_path), "--frame-index", str(page_path),
                "--out", str(out), "--split", "dev", "--labeller", "owner",
                "--labelling-protocol", "without-suggestions",
                "--source-size", "192x108", "--output-size", "96x54",
            ])
        self.assertEqual(status, 0)
        document = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(document["schema"], "sc2150-fa12-labels/v2")
        for key in ("labels", "fps_compose", "px_to_1080p", "cut_compose_ordinals", "split",
                    "media_sha256", "output_size"):
            self.assertIn(key, document)
        self.assertEqual(document["labels"][0]["pts_time"], 0.1)
        self.assertEqual(json.loads(buffer.getvalue())["frames"], 1)

    def test_a_frame_index_directory_contributes_every_page(self):
        page = frame_index_pages()[0]
        head = dict(page, items=page["items"][:2], offset=0, nextCursor="cursor-1")
        tail = dict(page, items=page["items"][2:], offset=2, nextCursor=None)
        directory = self.root / "frame-index"
        directory.mkdir()
        (directory / "page-000.json").write_text(json.dumps(head), encoding="utf-8")
        (directory / "page-001.json").write_text(json.dumps(tail), encoding="utf-8")
        export_path = self.write("export.json", export_manifest())
        review_path = self.write("review.json", review_document([visible(3, 5, 5)]))
        document = CONVERTER.convert_paths(export_path, review_path, [directory],
                                           self.options())
        self.assertEqual([label["ordinal"] for label in document["labels"]], [3])
        self.assertEqual(document["coverage"]["frame_count"], 4)

    def test_conversion_is_deterministic(self):
        first = self.convert([visible(0, 7, 9), hidden(1), ambiguous(2)])
        second = self.convert([visible(0, 7, 9), hidden(1), ambiguous(2)])
        self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))


SCORER_RUNS = sorted((Path(__file__).resolve().parents[3] / "docs" / "builderflow" / "sc-2150"
                      / "verification-runs").glob("*/tools/score.py"))
SCORER = SCORER_RUNS[-1] if SCORER_RUNS else None


@unittest.skipUnless(SCORER and SCORER.is_file(), "no frozen FA12 scorer in this checkout")
class ScorerIntegrationTest(unittest.TestCase):
    """A converted export must score, unmodified, with the frozen FA12 scorer."""

    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.corpus = self.root / "corpus" / "dev"
        self.predictions = self.root / "predictions"
        self.corpus.mkdir(parents=True)
        self.predictions.mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, name, document):
        path = self.root / name
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def predictions_for(self, clip_id):
        return {
            "schema": "sc2150-fa12-predictions/v1",
            "clip_id": clip_id,
            "media_sha256": "media-digest",
            "step": 1,
            "sampled_frames": 4,
            "sampled_pts_time": [0.0, 0.1, 0.2, 0.3],
            "scene_cut_ordinals": [2],
            "observations_sha256": "sha256:observations",
            "geometry_resolvable": True,
            "cursor": [
                {"ordinal": 0, "visibility": "VISIBLE", "x": 49.75, "y": 19.75,
                 "confidence": 0.9},
                {"ordinal": 1, "visibility": "VISIBLE", "x": 29.75, "y": 17.75,
                 "confidence": 0.8},
                {"ordinal": 2, "visibility": "VISIBLE", "x": 10.0, "y": 10.0,
                 "confidence": 0.4},
            ],
        }

    def test_converted_export_scores_with_the_frozen_scorer(self):
        corrections = [visible(0, 100, 40), visible(1, 60, 30), hidden(2),
                       {"kind": "SCENE_CUT", "beforeFrameOrdinal": 1, "afterFrameOrdinal": 2}]
        export_path = self.write("export.json", export_manifest())
        review_path = self.write("review.json", review_document(corrections))
        page_path = self.write("frame-index.json", frame_index_pages()[0])
        document = CONVERTER.convert_paths(
            export_path, review_path, [page_path],
            type("Options", (), {"export_id": "export-1", "split": "dev", "labeller": "owner",
                                 "labelling_protocol": "without-suggestions",
                                 "source_size": (192, 108), "output_size": (96, 54),
                                 "fps": None, "clip_id": "dev--review--clip",
                                 "template": "rs3-gold", "hotspot": "cursor hotspot",
                                 "label_stride": 3})())
        (self.corpus / (document["clip_id"] + ".labels.json")).write_text(
            json.dumps(document), encoding="utf-8")
        (self.predictions / (document["clip_id"] + ".predictions.json")).write_text(
            json.dumps(self.predictions_for(document["clip_id"])), encoding="utf-8")
        out = self.root / "score.json"
        completed = subprocess.run(
            [sys.executable, str(SCORER), "--corpus", str(self.root / "corpus"),
             "--split", "dev", "--predictions", str(self.predictions), "--out", str(out)],
            capture_output=True, text=True)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        report = json.loads(out.read_text(encoding="utf-8"))
        clip = report["clips"][0]
        self.assertEqual(clip["clip_id"], "dev--review--clip")
        self.assertEqual(clip["visible_frames"], 2)
        self.assertEqual(clip["recall"], 1.0)
        # The second detection is 3 output pixels off; at 96x54 that is 60 px at 1080p, so the
        # two-error median is 30 px. This is the same px_to_1080p scaling the gates use.
        self.assertEqual(clip["median_px"], 30.0)
        self.assertEqual(clip["absent_frames"], 1)
        self.assertEqual(clip["absent_false_rate"], 1.0)
        self.assertEqual(clip["unmatched_samples"], 1)
        self.assertEqual(clip["cuts"], {"tp": 1, "fp": 0, "fn": 0})


if __name__ == "__main__":
    unittest.main()

class SealedSessionConverterTest(unittest.TestCase):
    setUp = ConverterTest.setUp
    tearDown = ConverterTest.tearDown
    write = ConverterTest.write
    options = ConverterTest.options
    def envelope(self):
        from hashlib import sha256
        session = {"id": "12345678-1234-4234-8234-123456789012", "sourceId": SOURCE_ID,
                   "analysisId": ANALYSIS_ID, "state": "SEALED", "mode": "WITHOUT_SUGGESTIONS",
                   "split": "heldout", "policyDigest": "sha256:policy", "originalDigest": "sha256:media",
                   "frameIndexChecksum": "sha256:frame-index", "frameCount": 4,
                   "startOrdinal": 0, "endOrdinal": 3,
                   "coordinatePlane": {"contract": "coordinate-plane/v1",
                                       "codedWidth": 192, "codedHeight": 108,
                                       "rotationDegrees": 0, "displayWidth": 192,
                                       "displayHeight": 108, "origin": "top-left",
                                       "pixelCenter": True},
                   "coordinatePlaneDigest": "sha256:plane"}
        review = review_document([hidden(0), hidden(1), hidden(2), hidden(3)])
        review.update(sessionId=session["id"], appliedSourceRevision=1,
                      provenance={"session": dict(session), "origins": {str(i): "MANUAL_HIDDEN" for i in range(4)},
                                  "suggestionsExposed": False})
        revision_json = json.dumps(review, separators=(",", ":"))
        return {"contract": "mouse-video-review-export/v2", "protocol": "review-provenance/v1",
                "session": session, "revision": review, "revisionJson": revision_json,
                "contentDigest": "sha256:" + sha256(revision_json.encode()).hexdigest(),
                "independentProtocol": True, "attestation": True}

    def convert_sealed(self, envelope, server_bytes=None, **overrides):
        from unittest.mock import patch
        path = self.write("session.json", envelope)
        pages = [self.write("frames.json", frame_index_pages()[0])]
        raw = path.read_bytes() if server_bytes is None else server_bytes
        class Response:
            def __enter__(self): return self
            def __exit__(self, *ignored): return False
            def read(self, *ignored): return raw
        with patch.object(CONVERTER, "fetch_sealed_export", return_value=raw):
            return CONVERTER.convert_session_export(path, pages, self.options(
                split="heldout", verify_server="http://127.0.0.1:18190", **overrides))

    def test_sealed_protocol_emits_v3_but_never_human_or_training_claims(self):
        document = self.convert_sealed(self.envelope())
        self.assertEqual(document["schema"], "sc2150-fa12-labels/v3")
        self.assertTrue(document["provenance"]["independent_protocol_verified"])
        self.assertFalse(document["human_input_verified"])
        self.assertFalse(document["training_eligible"])
        self.assertEqual(document["labels"][0]["label_origin"], "MANUAL_HIDDEN")

    def test_local_independent_claim_cannot_override_server_bytes(self):
        with self.assertRaisesRegex(CONVERTER.ConversionError, "server's immutable"):
            self.convert_sealed(self.envelope(), server_bytes=b"different")

    def test_missing_or_mismatched_coordinate_plane_is_refused(self):
        envelope = self.envelope()
        del envelope["session"]["coordinatePlane"]
        with self.assertRaisesRegex(CONVERTER.ConversionError, "authoritative coordinate plane"):
            self.convert_sealed(envelope)
        envelope = self.envelope()
        with self.assertRaisesRegex(CONVERTER.ConversionError, "differs from the sealed coordinate"):
            self.convert_sealed(envelope, source_size=(128, 72))

    def test_assisted_or_unsealed_sessions_are_refused(self):
        envelope = self.envelope()
        envelope["session"]["mode"] = "DETECTOR_ASSISTED"
        with self.assertRaisesRegex(CONVERTER.ConversionError, "detector-assisted"):
            self.convert_sealed(envelope)
        envelope = self.envelope()
        envelope["session"]["state"] = "OPEN"
        with self.assertRaisesRegex(CONVERTER.ConversionError, "sealed protocol"):
            self.convert_sealed(envelope)

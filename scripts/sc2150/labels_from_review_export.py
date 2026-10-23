#!/usr/bin/env python3
"""Convert one SC-2150 review export into FA12 corpus labels (sc2150-fa12-labels/v2).

Inputs are the three documents the review screen already produces, all fetched over HTTP from a
disposable ChromaScape instance:

  * the export manifest (`GET /api/debug/mouse-videos/exports/{exportId}/manifest`, contract
    `mouse-video-evidence/v1`), which pins the source, the analysis and the review revision;
  * that revision (`GET /api/debug/mouse-videos/reviews/{revisionId}?sourceId=...`, contract
    `corrections/v1`), which carries the corrections the labeller submitted;
  * the frozen frame index pages (`GET .../frame-index`), which carry each ordinal's exact
    presentation time.

Output is one label document per clip, in the coordinate space the FA12 scorer compares against,
so `score.py --split dev|heldout` consumes it without a second conversion.

Provenance is enforced, not recorded on trust (RF2):

  * the review revision must be the one the export pins, for the export's source and analysis;
  * `--labeller` and `--labelling-protocol` are mandatory, and a held-out export may only declare
    `without-suggestions` -- a held-out label that started from detector output would measure
    agreement with the detector being evaluated;
  * the manifest, review revision, corrections payload and frame index checksums are all recorded
    so the conversion can be re-derived and audited.

Only frames a labeller explicitly marked are emitted. Unlabelled frames are absent from `labels`,
which excludes them from scoring; nothing is interpolated. `AMBIGUOUS` and `UNSUPPORTED` frames are
emitted as `dropped` so the exclusion is visible in the count, never scored as absent.

Coordinates: corrections carry exact source-frame pixels of the cursor hotspot, as validated
against the frozen frame index. They are mapped to the analysis output plane -- the plane the
harness predicts in -- by the inverse of pixel-centre scaling, `(c + 0.5) * s - 0.5`, the same
convention the synthetic corpus builder uses. `px_to_1080p` then converts output pixels to the
1080p-equivalent scale the gates are stated in.
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

CORPUS_SCHEMA = "sc2150-fa12-labels/v2"
EXPORT_CONTRACT = "mouse-video-evidence/v1"
CORRECTION_CONTRACT = "corrections/v1"
CURSOR_KIND = "CURSOR_VISIBILITY"
CUT_KIND = "SCENE_CUT"
ADDITIONAL_KINDS = ("PAUSE", "ACTION_INDICATOR", "TARGET_REGION", "CAMERA_ACTIVITY")
KNOWN_KINDS = (CURSOR_KIND, CUT_KIND) + ADDITIONAL_KINDS
PROTOCOLS = ("without-suggestions", "detector-assisted")
VISIBLE_VISIBILITY = "VISIBLE"
ABSENT_VISIBILITY = "HIDDEN"
DROPPED_VISIBILITY = ("AMBIGUOUS", "UNSUPPORTED")
FRAME_INDEX_PAGE_CONTRACT = "frame-index-page/v1"
MAX_VFR_DEVIATION_SECONDS = 0.001


class ConversionError(Exception):
    """A conversion input that cannot honestly produce corpus labels."""


def read_json(path):
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except OSError as failure:
        raise ConversionError("cannot read %s: %s" % (path, failure))
    except ValueError as failure:
        raise ConversionError("%s is not valid JSON: %s" % (path, failure))


def digest_of(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def digest_of_object(value):
    canonical = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def parse_size(text, name):
    parts = str(text).lower().split("x")
    if len(parts) != 2 or not all(part.isdigit() and int(part) > 0 for part in parts):
        raise ConversionError("%s must be WIDTHxHEIGHT in pixels, got %r" % (name, text))
    return int(parts[0]), int(parts[1])


def to_output(value, source_extent, output_extent):
    """Maps a source pixel-centre coordinate to the output plane."""
    return (value + 0.5) * output_extent / source_extent - 0.5


def frame_times(pages):
    """Merges frozen frame-index pages into ordinal -> presentation-time seconds."""
    times = {}
    frame_count = None
    for page in pages:
        if page.get("contract") != FRAME_INDEX_PAGE_CONTRACT:
            raise ConversionError(
                "frame index page has contract %r, expected %r"
                % (page.get("contract"), FRAME_INDEX_PAGE_CONTRACT)
            )
        count = page.get("frameCount")
        if not isinstance(count, int) or count < 0:
            raise ConversionError("frame index page does not declare a frame count")
        if frame_count is not None and frame_count != count:
            raise ConversionError("frame index pages disagree on the frame count")
        frame_count = count
        for item in page.get("items") or []:
            ordinal = item.get("ordinal")
            pts_time = item.get("ptsTime")
            if not isinstance(ordinal, int) or ordinal < 0:
                raise ConversionError("frame index item without an ordinal")
            if pts_time is None:
                raise ConversionError("frame %d has no presentation time" % ordinal)
            try:
                seconds = float(pts_time)
            except (TypeError, ValueError):
                raise ConversionError("frame %d has a non-numeric presentation time" % ordinal)
            if ordinal in times and abs(times[ordinal] - seconds) > 1e-9:
                raise ConversionError("frame %d appears twice with different times" % ordinal)
            times[ordinal] = seconds
    if frame_count is None:
        raise ConversionError("no frame index pages supplied")
    missing = [ordinal for ordinal in range(frame_count) if ordinal not in times]
    if missing:
        raise ConversionError(
            "frame index is incomplete: %d of %d ordinals are missing (first %s)"
            % (len(missing), frame_count, missing[:5])
        )
    return times, frame_count


def nominal_fps(times, frame_count):
    """Nominal frame rate plus the largest deviation from it, in seconds (VFR evidence)."""
    if frame_count < 2:
        raise ConversionError("a label clip needs at least two frames")
    deltas = [times[ordinal + 1] - times[ordinal] for ordinal in range(frame_count - 1)]
    ordered = sorted(deltas)
    median = ordered[len(ordered) // 2]
    if median <= 0:
        raise ConversionError("frame index has no positive inter-frame interval")
    deviation = max(abs(delta - median) for delta in deltas)
    return 1.0 / median, deviation


def require_contract(document, key, expected, name):
    if document.get(key) != expected:
        raise ConversionError(
            "%s declares %s %r, expected %r" % (name, key, document.get(key), expected)
        )


def bind_provenance(export, review, export_id):
    """Checks that the revision labelled is exactly the one the export pins."""
    require_contract(export, "contract", EXPORT_CONTRACT, "export manifest")
    require_contract(review, "contract", CORRECTION_CONTRACT, "review revision")
    source = export.get("source") or {}
    analysis = export.get("analysis") or {}
    pinned = export.get("review") or {}
    if not source.get("id"):
        raise ConversionError("export manifest does not identify its source")
    if not analysis.get("available") or not analysis.get("id"):
        raise ConversionError("export manifest pins no completed analysis; labels need its index")
    if review.get("sourceId") != source.get("id"):
        raise ConversionError("review revision belongs to a different source than the export")
    if review.get("analysisId") != analysis.get("id"):
        raise ConversionError("review revision belongs to a different analysis than the export")
    if review.get("id") != pinned.get("latestId"):
        raise ConversionError(
            "review revision %r is not the revision the export pins (%r); export again"
            % (review.get("id"), pinned.get("latestId"))
        )
    if not export_id:
        raise ConversionError("an export identifier is required for label provenance")
    return source, analysis, pinned


def corrections_of(review):
    corrections = review.get("corrections")
    if corrections is None:
        return []
    if not isinstance(corrections, list):
        raise ConversionError("review corrections must be a list")
    for index, entry in enumerate(corrections):
        if not isinstance(entry, dict) or not entry.get("kind"):
            raise ConversionError("correction %d has no kind" % index)
        if entry.get("kind") not in KNOWN_KINDS:
            # A kind this converter does not know would be dropped silently, taking a label with
            # it; refuse instead so a contract mismatch is visible.
            raise ConversionError(
                "correction %d has unsupported kind %r" % (index, entry.get("kind"))
            )
    return corrections


def cursor_labels(corrections, times, frame_count, options):
    """One scored label per labelled frame; the last correction for a frame wins."""
    by_ordinal = {}
    superseded = 0
    for entry in corrections:
        if entry.get("kind") != CURSOR_KIND:
            continue
        ordinal = entry.get("frameOrdinal")
        if not isinstance(ordinal, int) or not 0 <= ordinal < frame_count:
            raise ConversionError(
                "cursor correction references frame %r outside 0..%d"
                % (ordinal, frame_count - 1)
            )
        if ordinal in by_ordinal:
            superseded += 1
        by_ordinal[ordinal] = entry
    labels = []
    counts = {"visible": 0, "absent": 0, "dropped": 0}
    source_width, source_height = options.source_size
    output_width, output_height = options.output_size
    for ordinal in sorted(by_ordinal):
        entry = by_ordinal[ordinal]
        visibility = str(entry.get("visibility") or "").upper()
        label = {"ordinal": ordinal, "pts_time": round(times[ordinal], 6),
                 "labeller": options.labeller, "visibility": visibility}
        confidence = entry.get("confidence")
        if isinstance(confidence, (int, float)):
            label["confidence"] = float(confidence)
        if visibility == VISIBLE_VISIBILITY:
            x, y = entry.get("x"), entry.get("y")
            if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
                raise ConversionError("frame %d is VISIBLE without numeric x/y" % ordinal)
            if not 0 <= x < source_width or not 0 <= y < source_height:
                raise ConversionError(
                    "frame %d cursor %r,%r is outside the declared source size %dx%d"
                    % (ordinal, x, y, source_width, source_height)
                )
            label.update(state="VISIBLE", visible=True,
                         x=round(to_output(x, source_width, output_width), 4),
                         y=round(to_output(y, source_height, output_height), 4))
            counts["visible"] += 1
        elif visibility == ABSENT_VISIBILITY:
            label.update(state="ABSENT", visible=False)
            counts["absent"] += 1
        elif visibility in DROPPED_VISIBILITY:
            label.update(state="UNKNOWN", visible=False, dropped=True,
                         dropped_reason="labeller marked " + visibility.lower())
            counts["dropped"] += 1
        else:
            raise ConversionError(
                "frame %d has unsupported visibility %r" % (ordinal, visibility)
            )
        note = entry.get("note")
        if isinstance(note, str) and note.strip():
            label["note"] = note.strip()
        labels.append(label)
    return labels, counts, superseded


def cut_ordinals(corrections, frame_count):
    after = set()
    for entry in corrections:
        if entry.get("kind") != CUT_KIND:
            continue
        before, after_ordinal = entry.get("beforeFrameOrdinal"), entry.get("afterFrameOrdinal")
        if not isinstance(before, int) or not isinstance(after_ordinal, int):
            raise ConversionError("a scene-cut correction is missing its frame pair")
        if after_ordinal != before + 1:
            raise ConversionError(
                "scene cut %r->%r is not an adjacent frame pair" % (before, after_ordinal)
            )
        if not 0 <= after_ordinal < frame_count:
            raise ConversionError("scene cut references frame %d outside the index" % after_ordinal)
        after.add(after_ordinal)
    return sorted(after)


def additional_spans(corrections, times):
    """Non-cursor labels preserved for the W8 gates, with their presentation times."""
    spans = []
    for entry in corrections:
        kind = entry.get("kind")
        if kind not in ADDITIONAL_KINDS:
            continue
        span = {key: value for key, value in entry.items() if key != "kind"}
        span["kind"] = kind
        ordinals = [span.get("frameOrdinal"), span.get("startFrameOrdinal"),
                    span.get("endFrameOrdinal")]
        for ordinal in ordinals:
            if isinstance(ordinal, int) and ordinal in times:
                span.setdefault("pts_times", {})[str(ordinal)] = round(times[ordinal], 6)
        spans.append(span)
    return spans


def coverage(labels, frame_count, stride):
    labelled = {label["ordinal"] for label in labels}
    expected = range(0, frame_count, max(1, stride))
    missing = [ordinal for ordinal in expected if ordinal not in labelled]
    return {"frame_count": frame_count, "labelled_frames": len(labelled),
            "coverage_fraction": round(len(labelled) / frame_count, 4) if frame_count else None,
            "label_stride": stride, "missing_at_stride": len(missing),
            "first_missing_at_stride": missing[:20]}


def build_document(export, review, pages, options, digests):
    source, analysis, pinned = bind_provenance(export, review, options.export_id)
    corrections = corrections_of(review)
    times, frame_count = frame_times(pages)
    fps, deviation = nominal_fps(times, frame_count)
    if options.fps:
        fps = options.fps
    if not options.fps and deviation > MAX_VFR_DEVIATION_SECONDS:
        raise ConversionError(
            "frame index is variable-rate (largest interval deviation %.4f s); pass --fps and use a"
            " constant-rate section, or the cut tolerance is not comparable" % deviation
        )
    previews = export.get("previews") or []
    for preview in previews:
        declared = (preview.get("sourceWidth"), preview.get("sourceHeight"))
        if None not in declared and declared != options.source_size:
            raise ConversionError(
                "declared source size %dx%d disagrees with the export's preview geometry %dx%d"
                % (options.source_size + declared)
            )
    labels, counts, superseded = cursor_labels(corrections, times, frame_count, options)
    cuts = cut_ordinals(corrections, frame_count)
    spans = additional_spans(corrections, times)
    checksum = (export.get("acquisition") or {}).get("originalChecksum") or ""
    document = {
        "schema": CORPUS_SCHEMA,
        "label_source": "review-export/corrections/v1 mapped to analysis output pixels",
        "clip_id": options.clip_id or "%s--review--%s" % (options.split, str(source["id"])[:8]),
        "split": options.split,
        # Real footage has no synthetic variant; the scorer groups by this id, so it must exist.
        "variant": {"id": "review-export"},
        "source_id": source["id"],
        "media_sha256": checksum.split(":")[-1],
        "template": options.template,
        "hotspot": options.hotspot,
        "compose_size": list(options.output_size),
        "output_size": list(options.output_size),
        "px_to_1080p": 1080.0 / options.output_size[1],
        "fps_compose": round(fps, 6),
        "cut_compose_ordinals": cuts,
        "unobservable_cut_compose_ordinals": [],
        "additional_spans": spans,
        "counts": dict(counts, superseded_cursor_corrections=superseded),
        "coverage": coverage(labels, frame_count, options.label_stride),
        "provenance": {
            "labeller": options.labeller,
            "labelling_protocol": options.labelling_protocol,
            "export_id": options.export_id,
            "export_version": export.get("exportVersion"),
            "export_manifest_sha256": digests["export"],
            "review_revision_id": review.get("id"),
            "review_contract": review.get("contract"),
            "review_expected_base_revision": review.get("expectedBaseRevision"),
            "review_pinned_revision": pinned.get("revision"),
            "corrections_sha256": digest_of_object(corrections),
            "review_sha256": digests["review"],
            "frame_index_checksum": digests["frame_index"],
            "analysis_id": analysis.get("id"),
            "analysis_config_digest": analysis.get("configDigest"),
        },
        "labels": labels,
    }
    return document


def frame_index_files(paths):
    """Expands --frame-index arguments: a directory contributes every page JSON it holds."""
    files = []
    for path in paths:
        path = Path(path)
        if path.is_dir():
            files.extend(sorted(path.glob("*.json")))
        else:
            files.append(path)
    if not files:
        raise ConversionError("no frame index pages found")
    return files


def convert_paths(export_path, review_path, frame_index_paths, options):
    if options.labelling_protocol not in PROTOCOLS:
        raise ConversionError("--labelling-protocol must be one of %s" % ", ".join(PROTOCOLS))
    if options.split == "heldout" and options.labelling_protocol != "without-suggestions":
        raise ConversionError(
            "held-out labels must be made without detector suggestions; refusing a "
            "detector-assisted held-out export (RF2)"
        )
    if not options.labeller or not options.labeller.strip():
        raise ConversionError("--labeller is required for every corpus label")
    export = read_json(export_path)
    review = read_json(review_path)
    files = frame_index_files(frame_index_paths)
    pages = [read_json(path) for path in files]
    digests = {"export": digest_of(export_path), "review": digest_of(review_path),
               "frame_index": digest_of_object(
                   sorted(str(page.get("artifactChecksum")) for page in pages))}
    return build_document(export, review, pages, options, digests)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--export", type=Path, required=True, help="export manifest JSON")
    parser.add_argument("--export-id", required=True, help="export UUID the manifest came from")
    parser.add_argument("--review", type=Path, required=True,
                        help="review revision JSON for the revision the export pins")
    parser.add_argument("--frame-index", type=Path, action="append", required=True,
                        help="frozen frame-index page JSON, or a directory of pages; repeat as"
                             " needed")
    parser.add_argument("--out", type=Path, required=True, help="label document to write")
    parser.add_argument("--split", required=True, choices=["dev", "heldout"])
    parser.add_argument("--labeller", required=True,
                        help="who labelled this clip (an owner identity or 'claude')")
    parser.add_argument("--labelling-protocol", required=True, choices=list(PROTOCOLS),
                        help="held-out clips must be 'without-suggestions'")
    parser.add_argument("--source-size", required=True,
                        help="coordinate space of the corrections, e.g. 1920x1080")
    parser.add_argument("--output-size", required=True,
                        help="analysis output frame size the harness predicts in, e.g. 480x270")
    parser.add_argument("--fps", type=float, default=None,
                        help="nominal frame rate; derived from the frame index when omitted")
    parser.add_argument("--clip-id", default=None)
    parser.add_argument("--template", default="reviewed-cursor",
                        help="cursor template class of this clip, for per-template reporting")
    parser.add_argument("--hotspot", default="cursor hotspot",
                        help="which sprite pixel the label point represents")
    parser.add_argument("--label-stride", type=int, default=3,
                        help="frames between labels the plan targets (default 3, i.e. 10 Hz)")
    options = parser.parse_args(argv)
    options.source_size = parse_size(options.source_size, "--source-size")
    options.output_size = parse_size(options.output_size, "--output-size")
    try:
        document = convert_paths(options.export, options.review, options.frame_index, options)
    except ConversionError as failure:
        raise SystemExit("conversion refused: %s" % failure)
    options.out.parent.mkdir(parents=True, exist_ok=True)
    options.out.write_text(json.dumps(document, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({"clip_id": document["clip_id"], "split": document["split"],
                      "frames": len(document["labels"]), **document["counts"],
                      "cuts": len(document["cut_compose_ordinals"]),
                      "out": str(options.out)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())

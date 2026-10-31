#!/usr/bin/env python3
"""Assemble one server-sealed multi-session label set without inventing labels (SC-2150 F3).

Each input must be a ``sc2150-fa12-labels/v3`` document produced by
``labels_from_review_export.py`` from a sealed, independent-protocol review session. The
assembler pins the immutable identities it can prove, refuses overlapping or conflicting
sessions, and reports gaps instead of interpolating them.

Provenance is bound, not trusted. Self-set ``independence_verified`` booleans, copied
sealed-export hash strings and fresh hashes of a possibly edited input are not evidence that
the supplied labels still represent the sealed corrections. Every input must therefore be
matched to an entry in a frozen ``sc2150-fa12-derivation/v1`` manifest whose own SHA-256 is
pinned out of band (``--manifest-digest``). The manifest records the expected label document
hash, label-index hash, label count and every identity/projection field, so edited, removed or
extra labels and mismatched projections are refused before any output is written.

Output schema: ``sc2150-fa12-labelset/v1``. Assembly never grants training admission,
human-input proof or exact native-tick claims.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

INPUT_SCHEMA = "sc2150-fa12-labels/v3"
OUTPUT_SCHEMA = "sc2150-fa12-labelset/v1"
BINDING_SCHEMA = "sc2150-fa12-derivation/v1"
MAX_INPUT_BYTES = 64 * 1024 * 1024
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
MAX_FRAMES = 150_000
MANUAL_ORIGINS = {"MANUAL_VISIBLE", "MANUAL_HIDDEN", "MANUAL_AMBIGUOUS"}
STATE_ORIGIN = {
    "VISIBLE": "MANUAL_VISIBLE",
    "ABSENT": "MANUAL_HIDDEN",
    "UNKNOWN": "MANUAL_AMBIGUOUS",
}
IDENTITY_FIELDS = (
    "session_id",
    "export_id",
    "revision_id",
    "analysis_id",
    "source_id",
    "media_sha256",
    "frame_index_checksum",
    "sealed_export_sha256",
    "coordinate_plane_digest",
    "frame_count",
    "review_range",
    "output_size",
)


class AssemblyError(Exception):
    """Refusal with a precise reason; never a partially written label set."""


def read_json(path: Path):
    try:
        if path.stat().st_size > MAX_INPUT_BYTES:
            raise AssemblyError("%s exceeds the bounded input size" % path)
        return json.loads(path.read_text(encoding="utf-8"))
    except AssemblyError:
        raise
    except (OSError, ValueError) as failure:
        raise AssemblyError("%s is not readable JSON: %s" % (path, failure))


def digest_of(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(chunk)
    return "sha256:" + hasher.hexdigest()


def digest_of_object(value) -> str:
    return "sha256:" + hashlib.sha256(
        json.dumps(value, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def parse_range(value: str):
    try:
        start, end = value.split(":", 1)
        start, end = int(start), int(end)
    except (AttributeError, ValueError):
        raise AssemblyError("range must be START:END with inclusive integer ordinals")
    if start < 0 or end < start:
        raise AssemblyError("range must satisfy 0 <= START <= END")
    return start, end


def load_bindings(manifest_path, manifest_digest):
    """Loads the frozen derivation manifest and verifies its out-of-band digest."""
    if not manifest_path or not manifest_digest:
        raise AssemblyError(
            "assembly requires a frozen %s manifest and its trusted --manifest-digest; "
            "self-set verification flags are not provenance evidence" % BINDING_SCHEMA
        )
    path = Path(manifest_path)
    if not path.is_file() or path.stat().st_size > MAX_MANIFEST_BYTES:
        raise AssemblyError("derivation manifest is missing or exceeds its size bound")
    actual = digest_of(path)
    if actual != manifest_digest:
        raise AssemblyError(
            "derivation manifest digest %s does not match the trusted digest %s"
            % (actual, manifest_digest)
        )
    manifest = read_json(path)
    if manifest.get("schema") != BINDING_SCHEMA:
        raise AssemblyError("derivation manifest is not %s" % BINDING_SCHEMA)
    entries = manifest.get("inputs")
    if not isinstance(entries, list) or not entries or len(entries) > 256:
        raise AssemblyError("derivation manifest has no bounded input list")
    bindings = {}
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            raise AssemblyError("derivation manifest input %d is malformed" % index)
        session_id = entry.get("session_id")
        if not isinstance(session_id, str) or not session_id:
            raise AssemblyError("derivation manifest input %d has no session identity" % index)
        if session_id in bindings:
            raise AssemblyError("derivation manifest lists the same session twice")
        if not isinstance(entry.get("label_document_sha256"), str) or not entry.get(
            "label_document_sha256"
        ).startswith("sha256:"):
            raise AssemblyError("derivation manifest input %d has no label document digest" % index)
        if not isinstance(entry.get("label_index_sha256"), str) or not entry.get(
            "label_index_sha256"
        ):
            raise AssemblyError("derivation manifest input %d has no label index digest" % index)
        if entry.get("protocol") != "without-suggestions":
            raise AssemblyError(
                "derivation manifest input %d is not without-suggestions protocol truth" % index
            )
        bindings[session_id] = entry
    return bindings


def identity_of(document, path):
    provenance = document.get("provenance") or {}
    if not provenance.get("independence_verified") or not provenance.get(
        "independent_protocol_verified"
    ):
        raise AssemblyError("%s is not an independently verified sealed session" % path)
    if provenance.get("labelling_protocol") != "without-suggestions":
        raise AssemblyError("%s is not without-suggestions protocol truth" % path)
    range_value = provenance.get("review_range")
    if not (
        isinstance(range_value, list)
        and len(range_value) == 2
        and all(isinstance(value, int) and not isinstance(value, bool) for value in range_value)
    ):
        raise AssemblyError("%s does not pin its review range" % path)
    start, end = range_value
    if start < 0 or end < start:
        raise AssemblyError("%s has a malformed review range" % path)
    plane = provenance.get("coordinate_plane")
    if not isinstance(plane, dict) or plane.get("contract") != "coordinate-plane/v1":
        raise AssemblyError("%s does not pin an authoritative coordinate plane" % path)
    for key in ("displayWidth", "displayHeight"):
        if not isinstance(plane.get(key), int) or plane.get(key) < 1:
            raise AssemblyError("%s has an invalid coordinate plane" % path)
    index = provenance.get("frame_index_checksum")
    if not isinstance(index, str) or not index:
        raise AssemblyError("%s does not pin its frame index" % path)
    if not provenance.get("session_id"):
        raise AssemblyError("%s does not identify its sealed session" % path)
    if not provenance.get("sealed_export_sha256"):
        raise AssemblyError("%s does not pin its sealed export digest" % path)
    if not isinstance(provenance.get("coordinate_plane_digest"), str) or not provenance.get(
        "coordinate_plane_digest"
    ):
        raise AssemblyError("%s does not pin its coordinate plane digest" % path)
    output_size = document.get("output_size")
    if (
        not isinstance(output_size, list)
        or len(output_size) != 2
        or not all(isinstance(value, int) and value > 0 for value in output_size)
    ):
        raise AssemblyError("%s does not declare a valid output plane" % path)
    return {
        "source_id": document.get("source_id"),
        "media_sha256": document.get("media_sha256"),
        "template": document.get("template"),
        "hotspot": document.get("hotspot"),
        "split": document.get("split"),
        "output_size": output_size,
        "fps_compose": document.get("fps_compose"),
        "frame_index_checksum": index,
        "coordinate_plane": plane,
        "analysis_id": provenance.get("analysis_id"),
        "review_range": (start, end),
        "session_id": provenance.get("session_id"),
        "export_id": provenance.get("export_id"),
        "sealed_export_sha256": provenance.get("sealed_export_sha256"),
        "coordinate_plane_digest": provenance.get("coordinate_plane_digest"),
        "revision_id": provenance.get("review_revision_id"),
    }


def bind(document, path, identity, bindings):
    """Refuses any label projection or identity that differs from the frozen derivation."""
    entry = bindings.get(identity["session_id"])
    if entry is None:
        raise AssemblyError(
            "%s has no frozen derivation entry; an unlisted session cannot claim provenance" % path
        )
    if digest_of(path) != entry.get("label_document_sha256"):
        raise AssemblyError(
            "%s label document differs from its frozen derivation (edited file)" % path
        )
    labels = document.get("labels")
    if not isinstance(labels, list):
        raise AssemblyError("%s has an invalid label list" % path)
    if digest_of_object(labels) != entry.get("label_index_sha256"):
        raise AssemblyError(
            "%s label projection differs from its frozen derivation (edited or removed labels)"
            % path
        )
    if entry.get("label_count") != len(labels):
        raise AssemblyError("%s label count differs from its frozen derivation" % path)
    expected = {
        "session_id": identity["session_id"],
        "export_id": identity["export_id"],
        "revision_id": identity["revision_id"],
        "analysis_id": identity["analysis_id"],
        "source_id": identity["source_id"],
        "media_sha256": identity["media_sha256"],
        "frame_index_checksum": identity["frame_index_checksum"],
        "sealed_export_sha256": identity["sealed_export_sha256"],
        "coordinate_plane_digest": identity["coordinate_plane_digest"],
        "frame_count": (document.get("coverage") or {}).get("frame_count"),
        "review_range": list(identity["review_range"]),
        "output_size": identity["output_size"],
    }
    for key in IDENTITY_FIELDS:
        if entry.get(key) != expected[key]:
            raise AssemblyError(
                "%s %s differs from its frozen derivation (%r != %r)"
                % (path, key, entry.get(key), expected[key])
            )
    plane = identity["coordinate_plane"]
    source_size = entry.get("source_size")
    if source_size != [plane.get("displayWidth"), plane.get("displayHeight")]:
        raise AssemblyError("%s projection disagrees with its sealed coordinate plane" % path)
    return entry


def covered(ranges, start, end):
    """True when the union of sealed ranges covers every ordinal in [start, end]."""
    merged = []
    for value in sorted(ranges):
        if merged and value[0] <= merged[-1][1] + 1:
            merged[-1] = (merged[-1][0], max(merged[-1][1], value[1]))
        else:
            merged.append(value)
    return any(left <= start and end <= right for left, right in merged)


def compatible(baseline, candidate, path):
    for key in (
        "source_id",
        "media_sha256",
        "template",
        "hotspot",
        "split",
        "output_size",
        "fps_compose",
        "frame_index_checksum",
        "coordinate_plane",
    ):
        if candidate[key] != baseline[key]:
            raise AssemblyError(
                "%s is incompatible on %s (%r != %r)"
                % (path, key, candidate[key], baseline[key])
            )


def label_value(entry, key, path, ordinal):
    value = entry.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise AssemblyError("%s ordinal %d has a non-finite or missing %s" % (path, ordinal, key))
    return float(value)


def validate_label(entry, path, frame_count, start, end, output_width, output_height):
    """Validates one label's state/origin agreement, coordinates and range membership."""
    ordinal = entry.get("ordinal")
    if isinstance(ordinal, bool) or not isinstance(ordinal, int) or not 0 <= ordinal < frame_count:
        raise AssemblyError("%s has a label outside the frame index" % path)
    if not start <= ordinal <= end:
        raise AssemblyError(
            "%s labels ordinal %d outside its sealed range %d:%d" % (path, ordinal, start, end)
        )
    origin = entry.get("label_origin")
    if origin not in MANUAL_ORIGINS:
        raise AssemblyError("%s ordinal %d has assisted or unknown origin" % (path, ordinal))
    state = entry.get("state")
    if state not in STATE_ORIGIN:
        raise AssemblyError("%s ordinal %d has an unsupported state" % (path, ordinal))
    if STATE_ORIGIN[state] != origin:
        raise AssemblyError(
            "%s ordinal %d state %s contradicts origin %s" % (path, ordinal, state, origin)
        )
    label_value(entry, "pts_time", path, ordinal)
    visible = entry.get("visible")
    if not isinstance(visible, bool):
        raise AssemblyError("%s ordinal %d has no boolean visible flag" % (path, ordinal))
    dropped = entry.get("dropped", False)
    if not isinstance(dropped, bool):
        raise AssemblyError("%s ordinal %d has a non-boolean dropped flag" % (path, ordinal))
    if dropped != (state == "UNKNOWN"):
        raise AssemblyError(
            "%s ordinal %d has contradictory dropped/visibility fields" % (path, ordinal)
        )
    if state == "VISIBLE":
        if not visible:
            raise AssemblyError("%s ordinal %d is VISIBLE but not visible" % (path, ordinal))
        x = label_value(entry, "x", path, ordinal)
        y = label_value(entry, "y", path, ordinal)
        if not 0.0 <= x < output_width or not 0.0 <= y < output_height:
            raise AssemblyError(
                "%s ordinal %d cursor %r,%r is outside the %dx%d output plane"
                % (path, ordinal, x, y, output_width, output_height)
            )
        return ordinal, state
    if visible:
        raise AssemblyError(
            "%s ordinal %d is %s but marked visible" % (path, ordinal, state)
        )
    if entry.get("x") is not None or entry.get("y") is not None:
        raise AssemblyError(
            "%s ordinal %d is %s but carries coordinates" % (path, ordinal, state)
        )
    return ordinal, state


def assemble(
    paths,
    require_ranges=(),
    require_stride=0,
    split=None,
    clip_id=None,
    manifest=None,
    manifest_digest=None,
):
    if not paths:
        raise AssemblyError("at least one sealed label document is required")
    bindings = load_bindings(manifest, manifest_digest)
    inputs, baseline, frame_count = [], None, None
    seen_ordinals = {}
    ranges = []
    sessions = []
    labels = []
    session_ids = set()
    counts = {"VISIBLE": 0, "ABSENT": 0, "UNKNOWN": 0}
    for raw in paths:
        path = Path(raw)
        document = read_json(path)
        if document.get("schema") != INPUT_SCHEMA:
            raise AssemblyError("%s is not %s" % (path, INPUT_SCHEMA))
        if document.get("human_input_verified") or document.get("training_eligible"):
            raise AssemblyError("%s makes an unsupported human-input or training claim" % path)
        if document.get("control_allowed"):
            raise AssemblyError("%s makes an unsupported control claim" % path)
        identity = identity_of(document, path)
        bind(document, path, identity, bindings)
        if identity["session_id"] in session_ids:
            raise AssemblyError("%s repeats a session already supplied" % path)
        session_ids.add(identity["session_id"])
        if split is not None and identity["split"] != split:
            raise AssemblyError(
                "%s has split %r, expected %r" % (path, identity["split"], split)
            )
        if baseline is None:
            baseline = identity
        else:
            compatible(baseline, identity, path)
        count = (document.get("coverage") or {}).get("frame_count")
        if isinstance(count, bool) or not isinstance(count, int) or not 0 < count <= MAX_FRAMES:
            raise AssemblyError("%s has no bounded frame count" % path)
        if frame_count is None:
            frame_count = count
        elif count != frame_count:
            raise AssemblyError(
                "%s has a different frame count (%d != %d)" % (path, count, frame_count)
            )
        start, end = identity["review_range"]
        if end >= frame_count:
            raise AssemblyError("%s review range exceeds the frame index" % path)
        for other in ranges:
            if start <= other[1] and other[0] <= end:
                raise AssemblyError(
                    "review ranges overlap (%d:%d and %d:%d); refuse ambiguous truth"
                    % (other[0], other[1], start, end)
                )
        ranges.append((start, end))
        entries = document.get("labels")
        if not isinstance(entries, list) or len(entries) > MAX_FRAMES:
            raise AssemblyError("%s has an invalid label list" % path)
        if not entries:
            raise AssemblyError("%s has no labels; a sealed session cannot be empty" % path)
        output_width, output_height = identity["output_size"]
        ordered = []
        for entry in entries:
            if not isinstance(entry, dict):
                raise AssemblyError("%s has a malformed label" % path)
            ordinal, state = validate_label(
                entry, path, frame_count, start, end, output_width, output_height
            )
            if ordinal in seen_ordinals:
                raise AssemblyError(
                    "duplicate labelled ordinal %d across sessions; refuse conflicting truth"
                    % ordinal
                )
            seen_ordinals[ordinal] = path
            ordered.append((ordinal, float(entry["pts_time"])))
            labels.append(entry)
            counts[state] += 1
        times = [value for _, value in sorted(ordered)]
        if any(later <= earlier for earlier, later in zip(times, times[1:])):
            raise AssemblyError("%s has non-increasing presentation times" % path)
        declared = document.get("counts") or {}
        session_counts = {
            "visible": sum(1 for entry in entries if entry.get("state") == "VISIBLE"),
            "absent": sum(1 for entry in entries if entry.get("state") == "ABSENT"),
            "dropped": sum(1 for entry in entries if entry.get("state") == "UNKNOWN"),
        }
        for key, value in session_counts.items():
            if declared.get(key) != value:
                raise AssemblyError(
                    "%s declares %s=%r but its labels disagree"
                    % (path, key, declared.get(key))
                )
        declared_coverage = document.get("coverage") or {}
        if declared_coverage.get("labelled_frames") != len(entries):
            raise AssemblyError("%s declares a different labelled frame count" % path)
        if declared_coverage.get("frame_count") != count:
            raise AssemblyError("%s declares a different frame count" % path)
        sessions.append(
            {
                "session_id": identity["session_id"],
                "export_id": identity["export_id"],
                "revision_id": identity["revision_id"],
                "sealed_export_sha256": identity["sealed_export_sha256"],
                "analysis_id": identity["analysis_id"],
                "range": [start, end],
                "labels": len(entries),
                "input_sha256": digest_of(path),
            }
        )
        inputs.append({"path": str(path), "sha256": digest_of(path)})
    labels.sort(key=lambda entry: entry["ordinal"])
    for wanted in require_ranges:
        start, end = parse_range(wanted)
        if end >= frame_count:
            raise AssemblyError("required range %s exceeds the frame index" % wanted)
        if not covered(ranges, start, end):
            raise AssemblyError("required range %s is not covered by the sealed sessions" % wanted)
    if require_stride:
        expected_ordinals = set()
        for start, end in ranges:
            expected_ordinals.update(range(start, end + 1, max(1, require_stride)))
        missing = sorted(expected_ordinals - set(seen_ordinals))
        if missing:
            raise AssemblyError(
                "%d required stride ordinals are unlabelled; first %s"
                % (len(missing), missing[:20])
            )
    if clip_id is None:
        clip_id = "%s--assembly--%s" % (baseline["split"], str(baseline["source_id"])[:8])
    return {
        "schema": OUTPUT_SCHEMA,
        "clip_id": clip_id,
        "split": baseline["split"],
        "source_id": baseline["source_id"],
        "media_sha256": baseline["media_sha256"],
        "template": baseline["template"],
        "hotspot": baseline["hotspot"],
        "output_size": baseline["output_size"],
        "fps_compose": baseline["fps_compose"],
        "coordinate_plane": baseline["coordinate_plane"],
        "frame_index_checksum": baseline["frame_index_checksum"],
        "sessions": sorted(sessions, key=lambda item: item["range"][0]),
        "labels": labels,
        "counts": counts,
        "coverage": {
            "frame_count": frame_count,
            "labelled_frames": len(labels),
            "ranges": sorted([list(value) for value in ranges]),
            "duplicate_ordinals": 0,
            "missing_required_ranges": [],
            "require_stride": require_stride,
        },
        "integrity": {
            "label_index_sha256": digest_of_object(labels),
            "inputs": sorted(inputs, key=lambda item: item["path"]),
        },
        "human_input_verified": False,
        "training_eligible": False,
        "control_allowed": False,
        "provenance": {
            "assembler": "sc2150-fa12-labelset/v1",
            "derivation_manifest_sha256": digest_of(Path(manifest)),
            "independent_protocol_verified": True,
            "outside_application_exposure_verified": False,
            "exact_native_tick_verified": False,
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--input",
        action="append",
        default=[],
        required=True,
        help="sealed v3 label document; repeat for each session",
    )
    parser.add_argument(
        "--manifest",
        required=True,
        help="frozen sc2150-fa12-derivation/v1 manifest covering every input",
    )
    parser.add_argument(
        "--manifest-digest",
        required=True,
        help="trusted sha256:... of the frozen manifest, recorded outside the label documents",
    )
    parser.add_argument(
        "--require-range",
        action="append",
        default=[],
        help="inclusive START:END that some session must cover; repeatable",
    )
    parser.add_argument(
        "--require-stride",
        type=int,
        default=0,
        help="require a label every N ordinals inside every sealed range",
    )
    parser.add_argument("--split", choices=["dev", "heldout"])
    parser.add_argument("--clip-id")
    parser.add_argument("--out", type=Path, required=True)
    options = parser.parse_args(argv)
    try:
        document = assemble(
            options.input,
            options.require_range,
            options.require_stride,
            options.split,
            options.clip_id,
            options.manifest,
            options.manifest_digest,
        )
    except AssemblyError as failure:
        raise SystemExit("assembly refused: %s" % failure)
    options.out.parent.mkdir(parents=True, exist_ok=True)
    options.out.write_text(json.dumps(document, indent=1) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "clip_id": document["clip_id"],
                "sessions": len(document["sessions"]),
                "labels": len(document["labels"]),
                **document["counts"],
                "out": str(options.out),
            }
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

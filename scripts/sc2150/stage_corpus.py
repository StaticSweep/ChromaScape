#!/usr/bin/env python3
"""Stage a frozen FA12 scoring corpus from sealed converter label documents (SC-2150 W10).

`MouseVideoCursorCorpusHarnessTest` reads a corpus directory: ``<split>/<clip_id>.labels.json``,
a sibling ``<clip_id>.mp4`` and ``templates/<template>.png``. Sealed held-out labels come out of
``labels_from_review_export.py`` as one document per sealed session, and the media lives wherever
the library keeps the original source. This tool copies both into that exact layout and proves the
media still matches the digest the label document pins, so scoring never runs against substituted
media.

It never edits a label document, never chooses a template, and never marks anything scoreable on
its own authority: a document that fails any check aborts the whole staging run and leaves no
partial corpus behind.

Usage: stage_corpus.py --labels DOC_OR_DIR [--labels ...] --media SOURCE_ID=PATH [--media ...]
       --templates DIR --split heldout --out CORPUS_DIR
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
import tempfile
from pathlib import Path

MANIFEST_SCHEMA = "sc2150-fa12-staging/v1"
LABEL_SCHEMAS = {
    "sc2150-fa12-labels/v1",  # synthetic corpus builder output
    "sc2150-fa12-labels/v2",
    "sc2150-fa12-labels/v3",
}
MAX_DOC_BYTES = 64 * 1024 * 1024


class StagingError(Exception):
    """Refusal with a precise reason; never a partially written corpus."""


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def normalize_digest(value: object, where: str) -> str:
    if not isinstance(value, str) or not value:
        raise StagingError("%s does not pin a media digest" % where)
    text = value.split(":")[-1].strip().lower()
    if len(text) != 64 or any(character not in "0123456789abcdef" for character in text):
        raise StagingError("%s pins a malformed media digest" % where)
    return text


def label_documents(paths: list[Path]) -> list[Path]:
    files: list[Path] = []
    for path in paths:
        if path.is_dir():
            files.extend(sorted(path.glob("*.labels.json")))
        elif path.is_file():
            files.append(path)
        else:
            raise StagingError("label path does not exist: %s" % path)
    if not files:
        raise StagingError("no label documents found")
    return files


def read_document(path: Path) -> dict:
    if path.stat().st_size > MAX_DOC_BYTES:
        raise StagingError("%s exceeds the label document size cap" % path)
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise StagingError("%s is not readable JSON: %s" % (path, failure))
    if not isinstance(document, dict):
        raise StagingError("%s is not a label document" % path)
    if document.get("schema") not in LABEL_SCHEMAS:
        raise StagingError("%s is not a converter label document (%r)" % (path, document.get("schema")))
    for key in ("clip_id", "source_id", "template"):
        if not isinstance(document.get(key), str) or not document[key].strip():
            raise StagingError("%s has no %s" % (path, key))
    return document


def media_map(assignments: list[str]) -> dict[str, Path]:
    mapping: dict[str, Path] = {}
    for assignment in assignments:
        source, separator, raw = assignment.partition("=")
        if not separator or not source.strip() or not raw.strip():
            raise StagingError("--media expects SOURCE_ID=PATH, got %r" % assignment)
        path = Path(raw)
        if not path.is_file():
            raise StagingError("media for %s is not a file: %s" % (source.strip(), path))
        mapping[source.strip()] = path
    return mapping


def stage(paths, media_assignments, templates, split, out, media_directory=None) -> dict:
    documents = [(path, read_document(path)) for path in label_documents(paths)]
    media = media_map(media_assignments)
    templates = Path(templates)
    if not split:
        raise StagingError("--split is required")
    if out.exists() and any(out.iterdir()):
        raise StagingError("output directory is not empty: %s" % out)

    seen: set[str] = set()
    clip_entries = []
    staged_templates: dict[str, str] = {}
    for path, document in documents:
        clip_id = document["clip_id"]
        if clip_id in seen:
            raise StagingError("duplicate clip_id %r; score would silently overwrite" % clip_id)
        seen.add(clip_id)
        if document.get("split") != split:
            raise StagingError(
                "%s split is %r, not %r" % (path, document.get("split"), split)
            )
        source_id = document["source_id"]
        media_path = media.get(source_id)
        if media_path is None and media_directory is not None:
            candidate = Path(media_directory) / (clip_id + ".mp4")
            if candidate.is_file():
                media_path = candidate
        if media_path is None:
            raise StagingError(
                "%s has no --media entry for source %s and no %s.mp4 under --media-dir"
                % (path, source_id, clip_id)
            )
        declared = normalize_digest(document.get("media_sha256"), str(path))
        actual = digest(media_path)
        if declared != actual:
            raise StagingError(
                "%s media digest differs from %s (label %s, file %s)"
                % (path, media_path, declared[:12], actual[:12])
            )
        template_name = document["template"]
        template_path = templates / (template_name + ".png")
        if not template_path.is_file():
            raise StagingError(
                "%s names template %r, but %s does not exist" % (path, template_name, template_path)
            )
        staged_templates[template_name] = digest(template_path)
        clip_entries.append(
            {
                "clip_id": clip_id,
                "source_id": source_id,
                "label_document": str(path),
                "label_document_sha256": digest(path),
                "label_schema": document["schema"],
                "media": str(media_path),
                "media_sha256": declared,
                "template": template_name,
                "template_sha256": staged_templates[template_name],
            }
        )

    manifest = {
        "schema": MANIFEST_SCHEMA,
        "split": split,
        "clips": sorted(clip_entries, key=lambda entry: entry["clip_id"]),
        "templates": staged_templates,
    }
    # Build beside the destination and move it into place, so a failed copy can never leave a
    # corpus that looks complete enough to score.
    out.parent.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix=".staging-", dir=out.parent))
    try:
        (work / split).mkdir()
        (work / "templates").mkdir()
        for entry in clip_entries:
            shutil.copyfile(entry["label_document"], work / split / (entry["clip_id"] + ".labels.json"))
            shutil.copyfile(entry["media"], work / split / (entry["clip_id"] + ".mp4"))
        for name in staged_templates:
            shutil.copyfile(templates / (name + ".png"), work / "templates" / (name + ".png"))
        (work / "staging-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        if out.exists():
            out.rmdir()
        work.rename(out)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--labels", action="append", required=True, type=Path)
    parser.add_argument("--media", action="append", default=[], metavar="SOURCE_ID=PATH")
    parser.add_argument(
        "--media-dir",
        type=Path,
        help="directory holding <clip_id>.mp4 next to the labels (one media per clip)",
    )
    parser.add_argument("--templates", required=True, type=Path)
    parser.add_argument("--split", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        manifest = stage(
            args.labels, args.media, args.templates, args.split, args.out, args.media_dir
        )
    except (StagingError, OSError) as failure:
        print("refused: %s" % failure, file=sys.stderr)
        return 2
    print(
        json.dumps(
            {
                "split": manifest["split"],
                "clips": [entry["clip_id"] for entry in manifest["clips"]],
                "templates": manifest["templates"],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Import the SC-2150 YouTube corpus into the mouse video library ("From others").

Every manifest section is uploaded through the real multipart route after its SHA-256 has been
verified locally, with a deterministic request id so an interrupted run resumes instead of
duplicating work, and with a receipt carrying only hashes and ids.

Where the clips land matters. This imports into the separate mouse video library
(`/api/debug/mouse-videos/sources`), never into the recordings table: imported media has no tick
snapshots, is never training-eligible, and must not be mixed into recording replay. The target must
be a loopback verification instance; the script refuses anything else before it sends a byte.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path

LIBRARY_PATH = "/api/debug/mouse-videos"
TERMINAL = ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "NEEDS_RECONCILIATION")
MAX_LABEL = 120
TASK_KEY = "sc2150-yt-corpus"
RECEIPT_SCHEMA = "sc2150-yt-import/v1"
LOOPBACK_HOSTS = frozenset({"localhost", "127.0.0.1", "::1"})
CHUNK_BYTES = 1024 * 1024
HTTP_TIMEOUT_SECONDS = 300.0
DEFAULT_JOB_TIMEOUT_SECONDS = 900.0
DEFAULT_RUN_DIR = "docs/builderflow/sc-2150/verification-runs/20261023T021614Z-fa12-real-footage"
DEFAULT_MANIFEST = "build/sc2150-yt/manifest.json"
DEFAULT_RAW_DIR = "build/sc2150-yt/raw"
MEDIA_TYPES = {
    ".mp4": "video/mp4",
    ".webm": "video/webm",
    ".mkv": "video/x-matroska",
    ".mov": "video/quicktime",
    ".avi": "video/x-msvideo",
}


class ImportTransportError(RuntimeError):
    """The server could not be reached, or stopped answering while a job was running."""


class ChecksumMismatch(RuntimeError):
    """The file on disk is not the file the manifest describes."""


def require_loopback_base_url(value: str) -> str:
    """Return a normalized base URL, or raise when it is not a plain loopback http(s) target."""
    candidate = (value or "").strip()
    parsed = urllib.parse.urlsplit(candidate)
    if parsed.scheme not in ("http", "https"):
        raise ValueError("base URL must be an http(s) URL")
    if parsed.username or parsed.password:
        raise ValueError("base URL must not carry credentials")
    if parsed.query or parsed.fragment:
        raise ValueError("base URL must not carry a query or fragment")
    host = (parsed.hostname or "").lower()
    if host not in LOOPBACK_HOSTS:
        raise ValueError("base URL must be a loopback address, not %r" % (host or candidate))
    try:
        parsed.port
    except ValueError as error:
        raise ValueError("base URL has an invalid port: %s" % error) from error
    return candidate.rstrip("/")


def sha256_file(path, chunk_bytes: int = CHUNK_BYTES) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(chunk_bytes), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_sha256(path, expected: str, chunk_bytes: int = CHUNK_BYTES) -> str:
    """Hash the file and return the digest, refusing to continue when it does not match."""
    actual = sha256_file(path, chunk_bytes)
    wanted = (expected or "").strip().lower()
    if actual != wanted:
        raise ChecksumMismatch(
            "%s: manifest sha256 %s, file sha256 %s" % (Path(path).name, wanted or "(missing)", actual)
        )
    return actual


def request_id_for(sha256_hex: str) -> str:
    """Deterministic upload identity: the same bytes always reuse the same request id."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "sc2150-yt:" + (sha256_hex or "").strip().lower()))


def _fit_label(value: str) -> str:
    return (value or "").strip()[:MAX_LABEL]


def build_metadata(section: dict, channel: str, split: str, kind: str) -> dict:
    """Declared import metadata for one section: HUMAN, corpus task, and readable provenance."""
    video_id = str(section.get("videoId") or "")
    index = int(section.get("section") or 0)
    start = int(section.get("requestedStartSeconds") or 0)
    url = str(section.get("url") or "https://www.youtube.com/watch?v=" + video_id)
    if not url.startswith("https://"):
        raise ValueError("section %s s%d: provenance URL is not https" % (video_id, index))
    joiner = "&" if "?" in url else "?"
    place = "%s%st=%d" % (url, joiner, start)
    if len(place) > MAX_LABEL:
        raise ValueError("section %s s%d: provenance URL exceeds %d characters" % (video_id, index, MAX_LABEL))
    return {
        "declaredOrigin": "HUMAN",
        "taskKey": TASK_KEY,
        "skill": None,
        "context": _fit_label(
            "YouTube \u00b7 %s \u00b7 %s s%d \u00b7 %s \u00b7 %s" % (channel, video_id, index, split, kind)
        ),
        "place": place,
    }


def section_kind(split_source: dict, section_index: int, verdict: str | None = None) -> str | None:
    """'full' or 'cut_only' from the frozen split, falling back to the screening verdict."""
    sections = (split_source or {}).get("sections") or {}
    if section_index in tuple(sections.get("full") or ()):
        return "full"
    if section_index in tuple(sections.get("cutOnly") or ()):
        return "cut_only"
    if verdict in ("full", "cut_only"):
        return verdict
    return None


def build_plan(manifest: dict, split_document: dict, raw_dir, only=None) -> tuple[list, list]:
    """Sections to import and sections that cannot be imported, in manifest order."""
    sources = (split_document or {}).get("sources") or {}
    wanted = {item.strip() for item in (only or ()) if item and item.strip()}
    plans: list = []
    skipped: list = []
    for section in manifest.get("sections") or ():
        video_id = str(section.get("videoId") or "")
        index = int(section.get("section") or 0)
        if wanted and video_id not in wanted:
            continue
        split_source = sources.get(video_id)
        if not split_source:
            skipped.append(
                {"videoId": video_id, "section": index, "reason": "video is not assigned to a split"}
            )
            continue
        kind = section_kind(split_source, index)
        if not kind:
            skipped.append(
                {"videoId": video_id, "section": index, "reason": "section is not assigned to a split"}
            )
            continue
        channel = str(split_source.get("channel") or section.get("channel") or "unknown channel")
        split = str(split_source.get("split") or "unassigned")
        plans.append(
            {
                "videoId": video_id,
                "section": index,
                "split": split,
                "kind": kind,
                "file": str(section.get("file") or ""),
                "path": Path(raw_dir) / str(section.get("file") or ""),
                "sha256": str(section.get("sha256") or "").strip().lower(),
                "sizeBytes": section.get("sizeBytes"),
                "metadata": build_metadata(section, channel, split, kind),
            }
        )
    return plans, skipped


def _decode(response) -> tuple[int, object]:
    body = response.read()
    status = response.status
    content_type = response.headers.get("Content-Type", "")
    if "json" in content_type:
        try:
            body = json.loads(body)
        except ValueError:
            body = body.decode("utf-8", "replace")
    return status, body


class Client:
    """Minimal client for the write-nonce protected mouse video routes."""

    def __init__(self, base_url: str, timeout: float = HTTP_TIMEOUT_SECONDS):
        self.base = base_url.rstrip("/") + LIBRARY_PATH
        self.timeout = timeout

    def request(self, path: str, method: str = "GET", raw=None, headers=None) -> tuple[int, object]:
        headers = dict(headers or {})
        if method != "GET":
            _status, nonce = self.request("/write-nonce")
            if not isinstance(nonce, dict) or not nonce.get("nonce"):
                raise ImportTransportError("the server did not issue a write nonce")
            headers["X-Mouse-Video-Nonce"] = nonce["nonce"]
        request = urllib.request.Request(self.base + path, data=raw, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            response = error
        except urllib.error.URLError as error:
            raise ImportTransportError("%s: %s" % (path, error.reason)) from error
        with response:
            return _decode(response)

    def poll_job(self, job_id: str, timeout_seconds: float) -> dict:
        deadline = time.monotonic() + max(1.0, timeout_seconds)
        delay = 0.5
        while True:
            status, job = self.request("/jobs/" + urllib.parse.quote(str(job_id)))
            if status != 200 or not isinstance(job, dict):
                raise ImportTransportError("job %s returned HTTP %s" % (job_id, status))
            if job.get("status") in TERMINAL:
                return job
            if time.monotonic() >= deadline:
                raise ImportTransportError("job %s did not finish within %.0fs" % (job_id, timeout_seconds))
            time.sleep(delay)
            delay = min(delay * 1.5, 5.0)


def upload(client: Client, plan: dict, request_id: str | None = None) -> tuple[int, object]:
    path = Path(plan["path"])
    media = path.read_bytes()
    boundary = "sc2150-" + uuid.uuid4().hex
    filename = path.name.replace('"', "").replace("\r", "").replace("\n", "")
    media_type = MEDIA_TYPES.get(path.suffix.lower(), "application/octet-stream")
    metadata = json.dumps(plan["metadata"], ensure_ascii=False, sort_keys=True).encode("utf-8")
    body = b"".join(
        [
            (
                '--%s\r\nContent-Disposition: form-data; name="metadata"\r\n'
                "Content-Type: application/json\r\n\r\n" % boundary
            ).encode("utf-8"),
            metadata,
            (
                '\r\n--%s\r\nContent-Disposition: form-data; name="file"; filename="%s"\r\n'
                "Content-Type: %s\r\n\r\n" % (boundary, filename, media_type)
            ).encode("utf-8"),
            media,
            ("\r\n--%s--\r\n" % boundary).encode("utf-8"),
        ]
    )
    headers = {
        "Content-Type": "multipart/form-data; boundary=" + boundary,
        "X-Mouse-Video-Request-Id": request_id or request_id_for(plan["sha256"]),
    }
    return client.request("/imports/upload", "POST", raw=body, headers=headers)


def import_section(client: Client, plan: dict, job_timeout_seconds: float = DEFAULT_JOB_TIMEOUT_SECONDS) -> dict:
    """Verify, upload and await one section. Returns its receipt; never raises for a section fault."""
    receipt = {
        "videoId": plan["videoId"],
        "section": plan["section"],
        "file": plan["file"],
        "sha256": plan["sha256"],
        "sourceId": None,
        "jobId": None,
        "status": "PENDING",
        "httpStatus": None,
    }
    try:
        verify_sha256(plan["path"], plan["sha256"])
    except ChecksumMismatch as error:
        receipt["status"] = "CHECKSUM_MISMATCH"
        receipt["error"] = str(error)
        return receipt
    except OSError as error:
        receipt["status"] = "MISSING_FILE"
        receipt["error"] = str(error)
        return receipt

    try:
        status, body = upload(client, plan)
    except ImportTransportError as error:
        receipt["status"] = "TRANSPORT_ERROR"
        receipt["error"] = str(error)
        return receipt
    receipt["httpStatus"] = status

    if status == 409:
        receipt["status"] = "DUPLICATE"
        receipt["error"] = "request id already used with different bytes or metadata"
        return receipt
    if status != 202 or not isinstance(body, dict):
        receipt["status"] = "REJECTED"
        receipt["error"] = body if isinstance(body, str) else json.dumps(body, sort_keys=True)[:400]
        return receipt

    receipt["sourceId"] = body.get("sourceId")
    receipt["jobId"] = body.get("jobId")
    if not receipt["jobId"]:
        receipt["status"] = "REJECTED"
        receipt["error"] = "the upload response carried no job id"
        return receipt

    try:
        job = client.poll_job(receipt["jobId"], job_timeout_seconds)
    except ImportTransportError as error:
        receipt["status"] = "POLL_INCOMPLETE"
        receipt["error"] = str(error)
        return receipt
    receipt["status"] = str(job.get("status") or "UNKNOWN")
    if job.get("errorCode"):
        receipt["error"] = str(job["errorCode"])
    return receipt


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_json(path) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path, document: dict) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = target.with_suffix(target.suffix + ".tmp")
    with open(staging, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2, sort_keys=False)
        handle.write("\n")
    staging.replace(target)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True, help="loopback verification server, e.g. http://127.0.0.1:8090")
    parser.add_argument("--run-dir", default=DEFAULT_RUN_DIR, help="split.json / screening.json / receipts directory")
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST, help="section manifest with sha256 values")
    parser.add_argument("--raw-dir", default=DEFAULT_RAW_DIR, help="downloaded section files")
    parser.add_argument("--only", action="append", default=[], metavar="VIDEO_ID", help="import one video id; repeatable")
    parser.add_argument("--dry-run", action="store_true", help="print the metadata that would be sent")
    parser.add_argument(
        "--job-timeout",
        type=float,
        default=DEFAULT_JOB_TIMEOUT_SECONDS,
        help="seconds to wait for one ingestion job",
    )
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    try:
        base_url = require_loopback_base_url(args.base_url)
    except ValueError as error:
        print("refusing to import: %s" % error, file=sys.stderr)
        return 2

    run_dir = Path(args.run_dir)
    try:
        manifest = load_json(args.manifest)
    except (OSError, ValueError) as error:
        print("cannot read manifest %s: %s" % (args.manifest, error), file=sys.stderr)
        return 2
    try:
        split_document = load_json(run_dir / "split.json")
    except (OSError, ValueError) as error:
        print("cannot read split %s: %s" % (run_dir / "split.json", error), file=sys.stderr)
        return 2

    try:
        plans, skipped = build_plan(manifest, split_document, args.raw_dir, args.only)
    except ValueError as error:
        print("cannot build import metadata: %s" % error, file=sys.stderr)
        return 2
    if not plans:
        print("nothing to import: no manifest section matches the split", file=sys.stderr)
        return 1

    if args.dry_run:
        for plan in plans:
            print(
                json.dumps(
                    {
                        "videoId": plan["videoId"],
                        "section": plan["section"],
                        "split": plan["split"],
                        "kind": plan["kind"],
                        "requestId": request_id_for(plan["sha256"]),
                        "metadata": plan["metadata"],
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
            )
        print("%d section(s) planned, %d skipped" % (len(plans), len(skipped)))
        return 0

    client = Client(base_url)
    receipts = {
        "schema": RECEIPT_SCHEMA,
        "createdAt": utc_now(),
        "baseUrl": base_url,
        "manifest": str(args.manifest),
        "runDir": str(run_dir),
        "items": [],
        "skipped": skipped,
    }
    receipts_path = run_dir / "import-receipts.json"
    failures = 0
    for plan in plans:
        item = import_section(client, plan, args.job_timeout)
        receipts["items"].append(item)
        write_json(receipts_path, receipts)
        label = "%s s%d" % (plan["videoId"], plan["section"])
        detail = item["status"]
        if item["sourceId"]:
            detail += " source=%s" % item["sourceId"]
        if item.get("error"):
            detail += " (%s)" % item["error"]
        print("%s: %s" % (label, detail), flush=True)
        if item["status"] not in ("SUCCEEDED", "DUPLICATE"):
            failures += 1

    succeeded = sum(1 for item in receipts["items"] if item["status"] == "SUCCEEDED")
    duplicates = sum(1 for item in receipts["items"] if item["status"] == "DUPLICATE")
    print(
        "%d succeeded, %d already imported, %d failed, %d skipped; receipts: %s"
        % (succeeded, duplicates, failures, len(skipped), receipts_path)
    )
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())

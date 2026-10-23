"""Bounded tests for scripts/sc2150/import_corpus_to_library.py (SC-2150 Part B).

The importer is the only path from the downloaded YouTube corpus into the mouse video library, so
these tests pin the parts that decide what production receives: the declared metadata, the
deterministic request id that makes a retry idempotent, the loopback guard, the refusal to upload a
file whose SHA-256 does not match the manifest, and the receipts written for a successful import.
A local stub server stands in for the real multipart route; no media leaves the test.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "sc2150" / "import_corpus_to_library.py"

SPEC = importlib.util.spec_from_file_location("import_corpus_to_library", SCRIPT)
IMPORTER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMPORTER)

SOURCE_ID = "11111111-2222-4333-8444-555555555555"
JOB_ID = "66666666-7777-4888-9999-aaaaaaaaaaaa"


class StubLibrary(BaseHTTPRequestHandler):
    """Serves the write-nonce, upload and job routes the importer uses."""

    seen: list = []
    job_status = "SUCCEEDED"

    def do_GET(self) -> None:  # noqa: N802 - http.server naming
        if self.path.endswith("/write-nonce"):
            self._json(200, {"nonce": "stub-nonce", "expiresAt": "2026-10-23T00:00:00Z"})
        elif "/jobs/" in self.path:
            self._json(200, {"id": JOB_ID, "status": self.job_status, "stage": "PROBE"})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802 - http.server naming
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length)
        StubLibrary.seen.append(
            {"path": self.path, "headers": dict(self.headers), "body": body}
        )
        self._json(202, {"sourceId": SOURCE_ID, "jobId": JOB_ID, "status": "QUEUED"})

    def _json(self, status: int, document: dict) -> None:
        payload = json.dumps(document).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args) -> None:  # silence the stub
        return


class ImporterTest(unittest.TestCase):
    def setUp(self) -> None:
        StubLibrary.seen = []
        StubLibrary.job_status = "SUCCEEDED"
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.raw_dir = self.root / "raw"
        self.raw_dir.mkdir()
        self.run_dir = self.root / "run"
        self.run_dir.mkdir()

        self.media = self.raw_dir / "abc12345678--s2.webm"
        self.media.write_bytes(b"stub-webm-bytes" * 8)
        self.digest = hashlib.sha256(self.media.read_bytes()).hexdigest()

        self.section = {
            "videoId": "abc12345678",
            "channel": "Stub Channel",
            "section": 2,
            "url": "https://www.youtube.com/watch?v=abc12345678",
            "requestedStartSeconds": 90,
            "file": self.media.name,
            "sha256": self.digest,
            "sizeBytes": self.media.stat().st_size,
        }
        self.manifest = {"schema": "sc2150-yt-sections/v1", "sections": [self.section]}
        self.split = {
            "schema": "sc2150-yt-split/v1",
            "sources": {
                "abc12345678": {
                    "split": "dev",
                    "kind": "longplay",
                    "channel": "Stub Channel",
                    "url": "https://www.youtube.com/watch?v=abc12345678",
                    "sections": {"full": [], "cutOnly": [2]},
                }
            },
        }
        self.manifest_path = self.root / "manifest.json"
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")
        (self.run_dir / "split.json").write_text(json.dumps(self.split), encoding="utf-8")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), StubLibrary)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = "http://127.0.0.1:%d" % self.server.server_address[1]

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def argv(self, *extra: str) -> list:
        return [
            "--base-url",
            self.base_url,
            "--run-dir",
            str(self.run_dir),
            "--manifest",
            str(self.manifest_path),
            "--raw-dir",
            str(self.raw_dir),
            *extra,
        ]

    def test_metadata_is_bounded_and_names_the_section(self) -> None:
        metadata = IMPORTER.build_metadata(self.section, "Stub Channel", "dev", "cut_only")
        self.assertEqual(metadata["declaredOrigin"], "HUMAN")
        self.assertEqual(metadata["taskKey"], "sc2150-yt-corpus")
        self.assertIsNone(metadata["skill"])
        self.assertEqual(metadata["context"], "YouTube \u00b7 Stub Channel \u00b7 abc12345678 s2 \u00b7 dev \u00b7 cut_only")
        self.assertEqual(metadata["place"], "https://www.youtube.com/watch?v=abc12345678&t=90")
        for value in metadata.values():
            if isinstance(value, str):
                self.assertLessEqual(len(value), IMPORTER.MAX_LABEL)

    def test_long_channel_names_are_truncated_but_urls_are_refused_not_cut(self) -> None:
        metadata = IMPORTER.build_metadata(self.section, "c" * 400, "dev", "full")
        self.assertEqual(len(metadata["context"]), IMPORTER.MAX_LABEL)
        self.assertTrue(metadata["place"].endswith("&t=90"))
        long_url = dict(self.section, url="https://www.youtube.com/watch?v=abc12345678&list=" + "x" * 200)
        with self.assertRaises(ValueError):
            IMPORTER.build_metadata(long_url, "chan", "dev", "full")

    def test_request_id_is_deterministic_per_content(self) -> None:
        first = IMPORTER.request_id_for(self.digest)
        self.assertEqual(first, IMPORTER.request_id_for(self.digest.upper()))
        self.assertEqual(uuid.UUID(first).version, 5)
        self.assertNotEqual(first, IMPORTER.request_id_for("0" * 64))

    def test_loopback_guard_refuses_non_local_targets(self) -> None:
        self.assertEqual(
            IMPORTER.require_loopback_base_url("http://127.0.0.1:8090/"), "http://127.0.0.1:8090"
        )
        self.assertEqual(
            IMPORTER.require_loopback_base_url("http://localhost:18190"), "http://localhost:18190"
        )
        for rejected in (
            "http://192.168.1.6:8090",
            "http://example.com",
            "https://www.youtube.com",
            "file:///tmp/socket",
            "http://user:pass@127.0.0.1:8090",
        ):
            with self.subTest(rejected=rejected):
                with self.assertRaises(ValueError):
                    IMPORTER.require_loopback_base_url(rejected)

    def test_main_refuses_a_remote_base_url_before_any_request(self) -> None:
        code = IMPORTER.main(["--base-url", "http://192.168.1.6:8090", "--run-dir", str(self.run_dir)])
        self.assertEqual(code, 2)
        self.assertEqual(StubLibrary.seen, [])

    def test_sha_mismatch_is_refused_without_uploading(self) -> None:
        with self.assertRaises(IMPORTER.ChecksumMismatch):
            IMPORTER.verify_sha256(self.media, "0" * 64)

        self.manifest["sections"][0]["sha256"] = "0" * 64
        self.manifest_path.write_text(json.dumps(self.manifest), encoding="utf-8")
        code = IMPORTER.main(self.argv())
        self.assertEqual(code, 1)
        self.assertEqual(StubLibrary.seen, [], "a mismatched file must never be uploaded")

        receipts = json.loads((self.run_dir / "import-receipts.json").read_text(encoding="utf-8"))
        self.assertEqual(receipts["schema"], "sc2150-yt-import/v1")
        self.assertEqual(receipts["items"][0]["status"], "CHECKSUM_MISMATCH")
        self.assertIsNone(receipts["items"][0]["sourceId"])

    def test_upload_records_a_succeeded_receipt(self) -> None:
        code = IMPORTER.main(self.argv())
        self.assertEqual(code, 0, "a 202 plus a SUCCEEDED job is a successful import")

        self.assertEqual(len(StubLibrary.seen), 1)
        upload = StubLibrary.seen[0]
        self.assertEqual(upload["path"], "/api/debug/mouse-videos/imports/upload")
        self.assertEqual(
            upload["headers"].get("X-Mouse-Video-Request-Id"),
            IMPORTER.request_id_for(self.digest),
        )
        self.assertEqual(upload["headers"].get("X-Mouse-Video-Nonce"), "stub-nonce")
        body = upload["body"]
        self.assertIn(b'name="metadata"', body)
        self.assertIn(b"sc2150-yt-corpus", body)
        self.assertIn(b'"declaredOrigin": "HUMAN"', body)
        self.assertIn(b'name="file"; filename="abc12345678--s2.webm"', body)
        self.assertIn(self.media.read_bytes(), body)

        receipts = json.loads((self.run_dir / "import-receipts.json").read_text(encoding="utf-8"))
        item = receipts["items"][0]
        self.assertEqual(item["videoId"], "abc12345678")
        self.assertEqual(item["section"], 2)
        self.assertEqual(item["sha256"], self.digest)
        self.assertEqual(item["sourceId"], SOURCE_ID)
        self.assertEqual(item["jobId"], JOB_ID)
        self.assertEqual(item["status"], "SUCCEEDED")
        self.assertEqual(item["httpStatus"], 202)
        dumped = json.dumps(receipts)
        self.assertNotIn(str(self.raw_dir), dumped, "receipts never carry media paths")
        self.assertNotIn(str(self.media), dumped)

    def test_a_failed_job_makes_the_run_fail(self) -> None:
        StubLibrary.job_status = "FAILED"
        code = IMPORTER.main(self.argv())
        self.assertEqual(code, 1)
        receipts = json.loads((self.run_dir / "import-receipts.json").read_text(encoding="utf-8"))
        self.assertEqual(receipts["items"][0]["status"], "FAILED")

    def test_dry_run_sends_nothing_and_prints_the_metadata(self) -> None:
        import contextlib
        import io

        stream = io.StringIO()
        with contextlib.redirect_stdout(stream):
            code = IMPORTER.main(self.argv("--dry-run"))
        self.assertEqual(code, 0)
        self.assertEqual(StubLibrary.seen, [])
        lines = [line for line in stream.getvalue().splitlines() if line.startswith("{")]
        self.assertEqual(len(lines), 1)
        planned = json.loads(lines[0])
        self.assertEqual(planned["requestId"], IMPORTER.request_id_for(self.digest))
        self.assertEqual(planned["kind"], "cut_only")
        self.assertFalse((self.run_dir / "import-receipts.json").exists())

    def test_only_filter_and_unassigned_sections_are_reported(self) -> None:
        plans, skipped = IMPORTER.build_plan(self.manifest, self.split, self.raw_dir, ["otherVideo"])
        self.assertEqual(plans, [])
        self.assertEqual(skipped, [], "a filtered-out section is not a skipped section")

        plans, skipped = IMPORTER.build_plan(self.manifest, {"sources": {}}, self.raw_dir)
        self.assertEqual(plans, [])
        self.assertEqual(len(skipped), 1)
        self.assertIn("not assigned", skipped[0]["reason"])

        plans, _ = IMPORTER.build_plan(self.manifest, self.split, self.raw_dir)
        self.assertEqual(len(plans), 1)
        self.assertEqual(plans[0]["kind"], "cut_only")
        self.assertEqual(plans[0]["split"], "dev")


if __name__ == "__main__":
    unittest.main()

"""Bounded tests for scripts/sc2150/explain_cursor_track.py (SC-2150 Part A1).

The script is the only way to read a cursor sample's evidence while diagnosing the
confident-wrong lock, so these tests pin the parts that decide what a diagnosis sees: the
artifact-pinned paging, the ordinal range filter, the bounded evidence passthrough, and the
two refusals (non-loopback target, evidence written outside `build/`). A local stub server
stands in for the real read route; no media and no production server is involved.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[3] / "scripts" / "sc2150" / "explain_cursor_track.py"

SPEC = importlib.util.spec_from_file_location("explain_cursor_track", SCRIPT)
EXPLAIN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EXPLAIN)

SOURCE_ID = "11111111-2222-4333-8444-555555555555"
ANALYSIS_ID = "66666666-7777-4888-9999-aaaaaaaaaaaa"
CHECKSUM = "sha256:" + "c" * 64


def sample(ordinal, visibility="VISIBLE", x=10, y=20, evidence=None):
    item = {
        "kind": "CURSOR_VISIBILITY",
        "frameOrdinal": str(ordinal),
        "pts": str(ordinal * 30),
        "visibility": visibility,
        "confidence": "0.87",
        "method": "cursor-template/v5",
        "methodVersion": "1",
    }
    if visibility == "VISIBLE":
        item["x"] = str(x)
        item["y"] = str(y)
    if evidence:
        item["evidence"] = evidence
    return item


class StubObservations(BaseHTTPRequestHandler):
    """Serves one two-page observation artifact, the second page behind a cursor."""

    requests: list = []

    def do_GET(self) -> None:  # noqa: N802 - http.server naming
        StubObservations.requests.append(self.path)
        first = {
            "contract": "observations-read/v1",
            "available": True,
            "artifactChecksum": CHECKSUM,
            "items": [
                sample(330, evidence={"rule": "held", "continuation": True, "score": 0.86}),
                sample(375, x=4, y=600, evidence={"rule": "held", "continuation": True}),
            ],
            "nextCursor": "page-2",
        }
        second = {
            "contract": "observations-read/v1",
            "available": True,
            "artifactChecksum": CHECKSUM,
            "items": [
                {"kind": "SCENE_CUT", "frameOrdinal": "390", "confidence": "0.99"},
                sample(415, visibility="AMBIGUOUS", evidence={"rule": "competing placement"}),
            ],
            "nextCursor": None,
        }
        self._json(200, first if "cursor" not in self.path else second)

    def _json(self, status: int, document: dict) -> None:
        payload = json.dumps(document).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args) -> None:  # silence the stub
        return


class ExplainCursorTrackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), StubObservations)
        cls.base_url = "http://127.0.0.1:%d" % cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self) -> None:
        StubObservations.requests = []

    def test_range_filter_keeps_only_requested_ordinals_and_sorts(self) -> None:
        client = EXPLAIN.Client(self.base_url)
        path = "/sources/%s/analyses/%s/observations" % (SOURCE_ID, ANALYSIS_ID)

        available, checksum, rows = EXPLAIN.collect(
            EXPLAIN.pages(client, path, {"limit": 2}), 340, 400
        )

        self.assertTrue(available)
        self.assertEqual(CHECKSUM, checksum)
        self.assertEqual([375], [row["ordinal"] for row in rows])
        self.assertEqual(
            {"rule": "held", "continuation": True}, rows[0]["evidence"]
        )

    def test_other_kinds_are_not_reported_as_cursor_samples(self) -> None:
        client = EXPLAIN.Client(self.base_url)
        path = "/sources/%s/analyses/%s/observations" % (SOURCE_ID, ANALYSIS_ID)

        _, _, rows = EXPLAIN.collect(EXPLAIN.pages(client, path, {"limit": 2}), None, None)

        self.assertEqual([330, 375, 415], [row["ordinal"] for row in rows])
        self.assertEqual("AMBIGUOUS", rows[-1]["visibility"])

    def test_printed_line_names_the_evidence(self) -> None:
        line = EXPLAIN.format_row(
            {
                "ordinal": 375,
                "visibility": "VISIBLE",
                "x": "4",
                "y": "600",
                "confidence": "0.87",
                "evidence": {"rule": "held", "continuation": True},
            }
        )

        self.assertIn("375 VISIBLE", line)
        self.assertIn('rule="held"', line)
        self.assertIn("continuation=true", line)

    def test_writes_json_under_build_and_prints_each_sample(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            status = EXPLAIN.main(
                [
                    "--base-url", self.base_url,
                    "--source", SOURCE_ID,
                    "--analysis", ANALYSIS_ID,
                    "--from", "330",
                    "--to", "380",
                    "--limit", "2",
                ]
            )

        self.assertEqual(0, status)
        text = output.getvalue()
        self.assertIn("samples=2", text)
        self.assertIn("rule=", text)
        self.assertNotIn("competing placement", text)

    def test_json_output_is_confined_to_build(self) -> None:
        previous = os.getcwd()
        with tempfile.TemporaryDirectory() as directory:
            os.chdir(directory)
            try:
                outside = io.StringIO()
                with contextlib.redirect_stdout(outside):
                    with self.assertRaises(SystemExit):
                        EXPLAIN.main(
                            [
                                "--base-url", self.base_url,
                                "--source", SOURCE_ID,
                                "--analysis", ANALYSIS_ID,
                                "--out", "docs/evidence.json",
                            ]
                        )
                inside = io.StringIO()
                with contextlib.redirect_stdout(inside):
                    status = EXPLAIN.main(
                        [
                            "--base-url", self.base_url,
                            "--source", SOURCE_ID,
                            "--analysis", ANALYSIS_ID,
                            "--out", "build/explain.json",
                        ]
                    )
                self.assertEqual(0, status)
                written = json.loads(Path("build/explain.json").read_text())
                self.assertEqual(CHECKSUM, written["artifactChecksum"])
                self.assertEqual(3, written["samples"])
            finally:
                os.chdir(previous)

    def test_refuses_the_owners_service_port(self) -> None:
        with self.assertRaises(SystemExit):
            EXPLAIN.main(
                [
                    "--base-url", "http://127.0.0.1:8090",
                    "--source", SOURCE_ID,
                    "--analysis", ANALYSIS_ID,
                ]
            )

    def test_refuses_a_non_loopback_target(self) -> None:
        with self.assertRaises(SystemExit):
            EXPLAIN.main(
                [
                    "--base-url", "http://192.168.1.6:18191",
                    "--source", SOURCE_ID,
                    "--analysis", ANALYSIS_ID,
                ]
            )


if __name__ == "__main__":
    unittest.main()

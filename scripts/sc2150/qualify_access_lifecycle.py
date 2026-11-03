#!/usr/bin/env python3
"""Access checks and explicitly seeded lost-worker states in a disposable SC-2150 database.

prepare: verifies independent-owner/anonymous HTTP isolation, seeds two unexpired lost-worker
claims and one expired claim for browser/reconciliation tests. capacity: fills the remaining
slots, verifies rejection without transmitting a body, retries an existing request, and removes
only this script's synthetic job rows. This does not substitute for the abrupt-JVM-death test.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "capacity"))
    parser.add_argument("--database", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--owner-port", type=int, default=18191,
                        help="independent-owner instance port (expects the source to be invisible)")
    parser.add_argument("--anonymous-port", type=int, default=18192,
                        help="anonymous verification-profile instance port (expects 401)")
    args = parser.parse_args()
    assert args.database.startswith("sc2150_") and args.database.replace("_", "").isalnum()
    evidence = args.evidence
    fixtures = json.loads((evidence / "fixture-results.json").read_text())
    fixture = fixtures[0]
    source_id = str(uuid.UUID(fixture["sourceId"]))
    base = "http://127.0.0.1:18190/api/debug/mouse-videos"

    def sql(statement):
        return subprocess.check_output(["docker", "exec", "-i", "chromascape-postgres", "psql", "-X", "-qAt",
            "-v", "ON_ERROR_STOP=1", "-U", "chromascape", "-d", args.database], input=statement.encode()).decode().strip()

    def request(path, port=18190, method="GET", body=None, raw=None, headers=None):
        headers = dict(headers or {})
        if method == "POST":
            headers["X-Mouse-Video-Nonce"] = request("/write-nonce", port)[1]["nonce"]
        if body is not None:
            raw = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(f"http://127.0.0.1:{port}/api/debug/mouse-videos" + path,
            data=raw, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            status = response.status
            if "json" in response.headers.get("Content-Type", ""):
                data = json.loads(data)
        return status, data

    def seed(expired=False):
        job_id = str(uuid.uuid4())
        now = datetime.datetime.now(datetime.timezone.utc)
        changes = {"id": job_id, "request_id": job_id, "request_digest": "sha256:" + hashlib.sha256(job_id.encode()).hexdigest(),
            "kind": "IMPORT", "stage": "PROBE", "status": "RUNNING", "generation": 5, "attempt": 1,
            "cancellation_revision": 0, "progress": 0, "analysis_id": None, "preview_id": None,
            "error_code": None, "error_message": None, "cleanup_reason": None, "version": 0,
            "lease_owner": "sc2150-seeded-lost-worker", "created_at": now.isoformat(), "updated_at": now.isoformat(),
            "lease_expires_at": (now + datetime.timedelta(seconds=-30 if expired else 3600)).isoformat()}
        payload = json.dumps(changes).replace("'", "''")
        sql("INSERT INTO mouse_video_jobs SELECT (jsonb_populate_record(NULL::mouse_video_jobs, to_jsonb(j) || '"
            + payload + "'::jsonb)).* FROM mouse_video_jobs j WHERE source_id='" + source_id + "' AND kind='IMPORT' LIMIT 1;")
        return job_id

    if args.phase == "prepare":
        source = request("/sources/" + source_id)[1]
        reviews = request("/sources/" + source_id + "/reviews")[1]["items"]
        exports = request("/sources/" + source_id + "/exports")[1]["items"]
        paths = [f"/sources/{source_id}", f"/sources/{source_id}/media/ORIGINAL",
            f"/sources/{source_id}/media/PROXY?analysisId={fixture['analysisId']}",
            f"/sources/{source_id}/analyses/{fixture['analysisId']}/frame-index",
            f"/sources/{source_id}/analyses/{fixture['analysisId']}/observations",
            f"/frame-previews/{fixture['previewId']}", f"/frame-previews/{fixture['previewId']}/media",
            f"/sources/{source_id}/reviews", f"/sources/{source_id}/exports", "/jobs/" + source["jobs"][0]["id"]]
        if reviews:
            paths.append("/reviews/" + reviews[0]["id"] + "?sourceId=" + source_id)
        if exports:
            paths.append("/exports/" + exports[0]["id"])
        results = []
        for path in paths:
            for port, expected in ((args.owner_port, 404), (args.anonymous_port, 401)):
                status, body = request(path, port)
                assert status == expected, (path, port, status, body)
                assert source["originalChecksum"] not in json.dumps(body), (path, body)
                results.append({"path": path, "port": port, "status": status})
        assert request("/sources", args.owner_port)[1]["items"] == []
        (evidence / "access-results.json").write_text(json.dumps(results, indent=2) + "\n")
        ledger = {"unexpired": [seed(), seed()], "expired": seed(expired=True)}
        (evidence / "lifecycle-fixtures.json").write_text(json.dumps(ledger, indent=2) + "\n")
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            expired = request("/jobs/" + ledger["expired"])[1]
            if expired["status"] == "NEEDS_RECONCILIATION":
                break
            time.sleep(.2)
        assert expired["status"] == "NEEDS_RECONCILIATION" and expired["generation"] > 5, expired
        for job_id in ledger["unexpired"]:
            assert request("/jobs/" + job_id)[1]["status"] == "RUNNING"
        fixture["reconciliationJobIds"] = ledger["unexpired"]
        (evidence / "fixture-results.json").write_text(json.dumps(fixtures, indent=2) + "\n")
        (evidence / "expiry-result.json").write_text(json.dumps(expired, indent=2) + "\n")
        print("PASS", len(results), "access checks; expired claim fenced, two unexpired claims preserved")
    else:
        ledger = json.loads((evidence / "lifecycle-fixtures.json").read_text())
        count = int(sql("SELECT count(*) FROM mouse_video_jobs WHERE owner='sc2150-fedora-a' AND status IN ('QUEUED','RUNNING','CANCELLING','NEEDS_RECONCILIATION');"))
        assert count <= 9
        added = []
        try:
            for _ in range(9 - count):
                added.append(seed())
            nonce = request("/write-nonce")[1]["nonce"]
            request_id = str(uuid.uuid4())
            headers = (f"POST /api/debug/mouse-videos/imports/upload HTTP/1.1\r\nHost: 127.0.0.1:18190\r\n"
                f"Content-Length: 1000000\r\nContent-Type: multipart/form-data; boundary=sc2150\r\n"
                f"X-Mouse-Video-Nonce: {nonce}\r\nX-Mouse-Video-Request-Id: {request_id}\r\n"
                "Expect: 100-continue\r\nConnection: close\r\n\r\n")
            with socket.create_connection(("127.0.0.1", 18190), timeout=5) as connection:
                connection.sendall(headers.encode())
                response = b""
                while b"HTTP/1.1 429" not in response:
                    block = connection.recv(4096)
                    if not block:
                        break
                    response += block
            assert b"HTTP/1.1 429" in response, response
            existing = sql("SELECT request_id FROM mouse_video_jobs WHERE source_id='" + source_id + "' AND kind='IMPORT' ORDER BY created_at LIMIT 1;")
            boundary = "sc2150-" + uuid.uuid4().hex
            raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'
                '{"declaredOrigin":"GENERATED","taskKey":"synthetic"}\r\n'
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.mp4"\r\nContent-Type: video/mp4\r\n\r\n').encode()
            raw += (evidence / "fixtures/cfr.mp4").read_bytes() + f"\r\n--{boundary}--\r\n".encode()
            headers = {"Content-Type": "multipart/form-data; boundary=" + boundary, "X-Mouse-Video-Request-Id": existing}
            status, receipt = request("/imports/upload", method="POST", raw=raw, headers=headers)
            assert status == 202 and receipt["sourceId"] == source_id, receipt
            status, conflict = request("/imports/upload", method="POST",
                raw=raw.replace(b'"taskKey":"synthetic"', b'"taskKey":"different"'), headers=headers)
            assert status == 409, conflict
            (evidence / "admission-results.json").write_text(json.dumps({"noBodyBytesSent": True,
                "fullOwnerEarlyStatus": 429, "identicalRetryStatus": 202, "changedRetryStatus": 409}, indent=2) + "\n")
            print("PASS full owner rejects before body; identical retry accepted; changed retry conflicts")
        finally:
            ids = added + ledger["unexpired"] + [ledger["expired"]]
            assert all(str(uuid.UUID(value)) == value for value in ids)
            sql("DELETE FROM mouse_video_jobs WHERE id IN (" + ",".join("'" + value + "'" for value in ids) + ");")


if __name__ == "__main__":
    main()

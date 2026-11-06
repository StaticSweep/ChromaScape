#!/usr/bin/env python3
"""Measure one exhausted assisted analysis on a disposable loopback SC-2150 server.

Records the per-sample job trajectory, the bounded workspace footprint, the PNG sample count and the
JVM high-water mark; optionally cancels at a named stage and proves the workspace, the volume image,
the child processes and the admission capacity are all released. Refuses the normal 8090 service
unless --allow-8090 is passed. Uses only owner-scoped API calls plus read-only /proc and du probes.
"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "NEEDS_RECONCILIATION"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--media", required=True, type=Path)
    parser.add_argument("--media-root", required=True, type=Path)
    parser.add_argument("--server-output", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--cancel-at", choices=("none", "samples", "tracking"), default="none")
    parser.add_argument("--expect", choices=("SUCCEEDED", "QUOTA_EXCEEDED"), default="SUCCEEDED")
    parser.add_argument("--timeout", type=float, default=900)
    parser.add_argument("--allow-8090", action="store_true")
    args = parser.parse_args()

    url = urllib.parse.urlparse(args.base_url)
    assert url.hostname in ("127.0.0.1", "localhost", "::1"), "Disposable loopback server required"
    assert url.port is not None, "An explicit port is required"
    if url.port == 8090:
        assert args.allow_8090, "Refusing the normal 8090 service without --allow-8090"
    if not args.allow_8090:
        assert 18190 <= url.port <= 18199, "Disposable verification ports are 18190-18199"
    assert args.media.is_file(), args.media
    media_root = args.media_root.resolve()
    assert media_root.is_dir(), media_root
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    server_pid = int((args.server_output / "server.pid").read_text().strip())
    assert b"ChromaScapeApplication" in Path(f"/proc/{server_pid}/cmdline").read_bytes(), server_pid
    base = args.base_url.rstrip("/") + "/api/debug/mouse-videos"

    def request(path, method="GET", body=None, raw=None, headers=None):
        headers = dict(headers or {})
        if method == "POST":
            headers["X-Mouse-Video-Nonce"] = request("/write-nonce")[1]["nonce"]
        if body is not None:
            raw = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(base + path, data=raw, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=120)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            status = response.status
            content_type = response.headers.get("Content-Type", "")
        return status, (json.loads(data) if "json" in content_type else data)

    def poll(job_id, timeout=150):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status, job = request("/jobs/" + job_id)
            assert status == 200, job
            if job["status"] in TERMINAL:
                return job
            time.sleep(0.2)
        raise AssertionError("import job deadline exceeded")

    def workspace_bytes(execution):
        workspace = media_root / "jobs" / execution
        if not workspace.exists():
            return 0
        return int(subprocess.check_output(["du", "-sb", str(workspace)]).split()[0])

    def png_count(execution):
        visual = media_root / "jobs" / execution / "visual"
        if not visual.is_dir():
            return 0
        return sum(1 for entry in visual.iterdir() if entry.suffix.lower() == ".png")

    def vm_hwm():
        status = Path(f"/proc/{server_pid}/status")
        try:
            text = status.read_text()
        except OSError:
            return 0
        match = re.search(r"^VmHWM:\s+(\d+) kB$", text, re.MULTILINE)
        return int(match.group(1)) * 1024 if match else 0

    def job_processes(job_id):
        found = []
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            try:
                cmdline = (entry / "cmdline").read_bytes()
            except OSError:
                continue
            if not cmdline:
                continue
            if b"ffmpeg" not in cmdline and b"fuse2fs" not in cmdline:
                continue
            if job_id.encode() in cmdline:
                found.append(entry.name)
        return found

    started = time.monotonic()
    request_id = str(uuid.uuid4())
    boundary = "sc2150-" + uuid.uuid4().hex
    raw = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
        'Content-Type: application/json\r\n\r\n'
        '{"declaredOrigin":"GENERATED","taskKey":"assisted-measurement"}\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
        f'filename="{args.media.name}.mp4"\r\nContent-Type: video/mp4\r\n\r\n'
    ).encode() + args.media.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    status, receipt = request(
        "/imports/upload",
        "POST",
        raw=raw,
        headers={
            "Content-Type": "multipart/form-data; boundary=" + boundary,
            "X-Mouse-Video-Request-Id": request_id,
        },
    )
    assert status == 202, receipt
    imported = poll(receipt["jobId"])
    assert imported["status"] == "SUCCEEDED", imported
    source_id = receipt["sourceId"]

    status, scheduled = request(
        f"/sources/{source_id}/analyses",
        "POST",
        {"requestId": str(uuid.uuid4()), "algorithmId": "visual/v1"},
    )
    assert status in (200, 202), scheduled
    job_id = scheduled["jobId"]
    analysis_id = scheduled["analysisId"]

    samples = []
    max_bytes = 0
    max_pngs = 0
    max_hwm = 0
    cancel_receipt = None
    cancelled_at = None
    deadline = time.monotonic() + args.timeout
    while True:
        status, job = request("/jobs/" + job_id)
        assert status == 200, job
        execution = f"{job_id}-{job['generation']}"
        snapshot = {
            "atMillis": int((time.monotonic() - started) * 1000),
            "stage": job["stage"],
            "status": job["status"],
            "progress": job["progress"],
            "createdAt": job["createdAt"],
            "updatedAt": job["updatedAt"],
            "workspaceBytes": workspace_bytes(execution),
            "pngCount": png_count(execution),
            "vmHwmBytes": vm_hwm(),
        }
        samples.append(snapshot)
        max_bytes = max(max_bytes, snapshot["workspaceBytes"])
        max_pngs = max(max_pngs, snapshot["pngCount"])
        max_hwm = max(max_hwm, snapshot["vmHwmBytes"])
        if args.cancel_at != "none" and cancelled_at is None:
            ready = args.cancel_at == "samples" and job["stage"] == "ANALYSIS_SAMPLES"
            tracking = (
                args.cancel_at == "tracking"
                and job["stage"] == "ANALYSIS_TRACKING"
                and job["progress"] >= 50
            )
            if ready or tracking:
                status, cancel_receipt = request(
                    f"/jobs/{job_id}/cancel",
                    "POST",
                    {"expectedRevision": job["cancellationRevision"]},
                )
                assert status in (200, 202), cancel_receipt
                cancelled_at = snapshot
        if job["status"] in TERMINAL:
            terminal = job
            break
        assert time.monotonic() < deadline, "analysis job deadline exceeded"
        time.sleep(0.5)

    execution = f"{job_id}-{terminal['generation']}"
    workspace = media_root / "jobs" / execution
    image = media_root / "work-volumes" / (execution + ".ext2")
    cleanup_deadline = time.monotonic() + 60
    while time.monotonic() < cleanup_deadline and (workspace.exists() or image.exists() or job_processes(job_id)):
        time.sleep(0.25)
    assert not workspace.exists(), str(workspace)
    assert not image.exists(), str(image)
    leftovers = job_processes(job_id)
    assert not leftovers, leftovers

    status, source = request("/sources/" + source_id)
    assert status == 200, source
    analysis = next(item for item in source["analyses"] if item["id"] == analysis_id)
    observations_published = any(
        artifact["role"] == "OBSERVATIONS" and artifact["analysisId"] == analysis_id
        for artifact in source["artifacts"]
    )
    elapsed_ms = int((time.monotonic() - started) * 1000)

    if args.cancel_at != "none":
        assert cancelled_at is not None, f"the {args.cancel_at} cancellation window was never observed"
        assert terminal["status"] == "CANCELLED", terminal
    if args.expect == "QUOTA_EXCEEDED":
        assert terminal["status"] == "FAILED", terminal
        assert terminal["errorCode"] == "QUOTA_EXCEEDED", terminal
        # Quota exhaustion must not be presented as malformed media (stable public contract).
        assert "could not be decoded" not in (terminal["errorMessage"] or ""), terminal
    elif args.cancel_at == "none":
        assert terminal["status"] == "SUCCEEDED", terminal
    if args.cancel_at != "none" or args.expect == "QUOTA_EXCEEDED":
        assert analysis["status"] != "COMPLETE", analysis
        assert not observations_published, observations_published

    coverage = None
    visibility = {}
    observation_count = 0
    if terminal["status"] == "SUCCEEDED":
        coverage = analysis["coverage"]
        cursor = None
        while True:
            query = "?limit=500&kind=CURSOR_VISIBILITY"
            if cursor:
                query += "&cursor=" + urllib.parse.quote(cursor)
            status, page = request(
                f"/sources/{source_id}/analyses/{analysis_id}/observations" + query
            )
            assert status == 200, page
            assert page["available"], page
            for item in page["items"]:
                observation_count += 1
                key = item.get("visibility") or "UNKNOWN"
                visibility[key] = visibility.get(key, 0) + 1
            cursor = page.get("nextCursor")
            if not cursor:
                break
        assert observation_count > 0, "a completed analysis must publish cursor observations"

    capacity_released = False
    if args.cancel_at != "none" or args.expect == "QUOTA_EXCEEDED":
        # Admission is what must be free again. A freshly accepted schedule is the proof; it is
        # cancelled at once so the harness never runs a second full analysis.
        status, probe = request(
            f"/sources/{source_id}/analyses",
            "POST",
            {"requestId": str(uuid.uuid4()), "algorithmId": "visual/v1"},
        )
        assert status in (200, 202), probe
        assert probe["jobId"], probe
        probe_job = request("/jobs/" + probe["jobId"])[1]
        cancel_status, cancel_receipt = request(
            f"/jobs/{probe['jobId']}/cancel",
            "POST",
            {"expectedRevision": probe_job["cancellationRevision"]},
        )
        assert cancel_status in (200, 202), cancel_receipt
        settled = poll(probe["jobId"], timeout=120)
        assert settled["status"] in ("CANCELLED", "FAILED", "SUCCEEDED"), settled
        capacity_released = True

    measurement = {
        "run": args.cancel_at,
        "expect": args.expect,
        "baseUrl": args.base_url,
        "mediaSha256": subprocess.check_output(
            ["sha256sum", str(args.media)]).decode().split()[0],
        "mediaBytes": args.media.stat().st_size,
        "sourceId": source_id,
        "analysisId": analysis_id,
        "job": {
            "id": job_id,
            "status": terminal["status"],
            "stage": terminal["stage"],
            "errorCode": terminal["errorCode"],
            "errorMessage": terminal["errorMessage"],
            "cleanupReason": terminal["cleanupReason"],
            "generation": terminal["generation"],
        },
        "samples": samples,
        "maxWorkspaceBytes": max_bytes,
        "maxPngCount": max_pngs,
        "maxVmHwmBytes": max_hwm,
        "elapsedMillis": elapsed_ms,
        "terminal": terminal,
        "cancelledAt": cancelled_at,
        "cancelReceiptStatus": None if cancel_receipt is None else cancel_receipt["status"],
        "cleanup": {
            "workspaceAbsent": True,
            "volumeImageAbsent": True,
            "jobProcessesAbsent": True,
            "analysisStatus": analysis["status"],
            "observationsArtifact": observations_published,
            "capacityReleased": capacity_released,
        },
        "coverage": coverage,
        "observations": {"count": observation_count, "visibility": visibility},
    }
    (evidence / "measurement.json").write_text(json.dumps(measurement, indent=2) + "\n")
    print(json.dumps({key: measurement[key] for key in (
        "run", "sourceId", "analysisId", "maxWorkspaceBytes", "maxPngCount", "maxVmHwmBytes",
        "elapsedMillis")}, indent=2), flush=True)
    print("PASS", flush=True)


if __name__ == "__main__":
    main()

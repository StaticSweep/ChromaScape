#!/usr/bin/env python3
"""Crash/restart a disposable verification JVM only; preserve queued work and fence lost claims.

Requires start_verification.py and qualify_http.py results. Uses a real database and real workers.
Refuses normal service PIDs and non-sc2150 databases. Leaves the restarted verification server up
for subsequent checks; stop its recorded PID and drop the disposable database after verification.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--server-output", type=Path, required=True)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--java", required=True)
    args = parser.parse_args()
    assert args.database.startswith("sc2150_") and args.database.replace("_", "").isalnum()
    base = "http://127.0.0.1:18190/api/debug/mouse-videos"
    fixture = json.loads((args.evidence / "fixture-results.json").read_text())[0]
    results = {}

    def sql(statement):
        return subprocess.check_output(["docker", "exec", "-i", "chromascape-postgres", "psql", "-X", "-qAt",
            "-v", "ON_ERROR_STOP=1", "-U", "chromascape", "-d", args.database], input=statement.encode()).decode().strip()

    def request(path, body=None, raw=None, headers=None, decode=True):
        headers = dict(headers or {})
        if body is not None or raw is not None:
            headers["X-Mouse-Video-Nonce"] = request("/write-nonce")["nonce"]
        if body is not None:
            headers["Content-Type"] = "application/json"
            raw = json.dumps(body).encode()
        req = urllib.request.Request(base + path, data=raw, headers=headers)
        with urllib.request.urlopen(req, timeout=30) as response:
            data = response.read()
            return json.loads(data) if decode and "json" in response.headers.get("Content-Type", "") else data

    def owned_pid():
        pid = int((args.server_output / "server.pid").read_text())
        command = Path(f"/proc/{pid}/cmdline").read_bytes()
        assert b"-Duser.name=sc2150-fedora-a" in command and b"ChromaScapeApplication" in command
        return pid

    def job_row(job_id):
        job_id = str(uuid.UUID(job_id))
        return json.loads(sql(f"SELECT json_build_object('status',status,'generation',generation,'leaseOwner',lease_owner) FROM mouse_video_jobs WHERE id='{job_id}';"))

    def restart(label):
        log = args.server_output / "server.log"
        if log.exists():
            log.rename(args.server_output / (label + "-previous.log"))
        command = ["python3", "scripts/sc2150/start_verification.py", "--classpath", str(args.classpath),
            "--java", args.java, "--output", str(args.server_output), "--database", args.database]
        with (args.evidence / (label + "-startup.log")).open("w") as output:
            subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, check=True, timeout=240)
        print(label, "READY", flush=True)

    exports = request(f"/sources/{fixture['sourceId']}/exports")["items"]
    export_path = "/exports/" + exports[0]["id"]
    export_before = request(export_path, decode=False)
    if not isinstance(export_before, bytes):
        export_before = json.dumps(export_before, sort_keys=True)
    # Crash only after SQL proves a real HTTP-scheduled preview is still QUEUED.
    for attempt in range(8):
        # Equivalent previews coalesce onto an already-published READY row, so each run asks for a
        # distinct size drawn from a wider band until it obtains a genuinely queued job.
        scheduled = request(f"/sources/{fixture['sourceId']}/analyses/{fixture['analysisId']}/frame-previews", {
            "requestId": str(uuid.uuid4()), "frameOrdinal": 12,
            "maxWidth": 96 + int(time.time() * 1000) % 60 + attempt, "maxHeight": 61 + attempt})
        if scheduled["state"] != "PENDING":
            continue
        pid = owned_pid()
        os.kill(pid, signal.SIGSTOP)
        row = job_row(scheduled["jobId"])
        if row["status"] == "QUEUED":
            os.kill(pid, signal.SIGKILL)
            results["queuedBeforeCrash"] = row
            break
        os.kill(pid, signal.SIGCONT)
        deadline = time.monotonic() + 30
        while job_row(scheduled["jobId"])["status"] in ("QUEUED", "RUNNING") and time.monotonic() < deadline:
            time.sleep(.1)
    else:
        raise AssertionError("Could not obtain a proven queued crash point")
    restart("queued-restart")
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        preview = request("/frame-previews/" + scheduled["previewId"])
        if preview["state"] != "PENDING":
            break
        time.sleep(.2)
    assert preview["state"] == "READY", preview
    assert job_row(scheduled["jobId"])["status"] == "SUCCEEDED"
    assert request("/frame-previews/" + scheduled["previewId"] + "/media").startswith(b"\x89PNG\r\n\x1a\n")
    results["queuedAfterRestart"] = job_row(scheduled["jobId"])
    print("Queued durable preview recovered", flush=True)

    # A longer generated clip provides a real decoder window for an active-worker crash.
    clip = args.evidence / "fixtures/crash-60s.mp4"
    subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
        "testsrc2=size=160x96:rate=30:duration=60", "-c:v", "libopenh264", "-threads", "2", str(clip)], check=True, timeout=60)
    boundary = "sc2150-" + uuid.uuid4().hex
    raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\nContent-Type: application/json\r\n\r\n'
        '{"declaredOrigin":"GENERATED","taskKey":"synthetic-crash"}\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="crash.mp4"\r\nContent-Type: video/mp4\r\n\r\n').encode()
    raw += clip.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    imported = request("/imports/upload", raw=raw, headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
        "X-Mouse-Video-Request-Id": str(uuid.uuid4())})
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        imported_job = request("/jobs/" + imported["jobId"])
        if imported_job["status"] not in ("QUEUED", "RUNNING"):
            break
        time.sleep(.2)
    assert imported_job["status"] == "SUCCEEDED", imported_job
    analysis = request(f"/sources/{imported['sourceId']}/analyses", {
        "requestId": str(uuid.uuid4()), "algorithmId": "visual/v1"})
    job_id = analysis["jobId"]
    deadline = time.monotonic() + 30
    decoder = []
    while time.monotonic() < deadline:
        lines = subprocess.check_output(["ps", "-eo", "pid=,ppid=,args="], text=True).splitlines()
        decoder = [line.strip() for line in lines if job_id in line and "ffmpeg" in line and "-i " in line]
        if decoder:
            break
        time.sleep(.02)
    assert decoder, "Did not observe a real analysis decoder; do not claim an active-stage crash"
    before = job_row(job_id)
    assert before["status"] == "RUNNING", before
    pid = owned_pid()
    os.kill(pid, signal.SIGKILL)
    results["activeDecoderBeforeCrash"] = decoder
    results["activeClaimBeforeCrash"] = before
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        after = job_row(job_id)
        if after["status"] == "NEEDS_RECONCILIATION":
            break
        time.sleep(.3)
    assert after["status"] == "NEEDS_RECONCILIATION" and after["generation"] > before["generation"], after
    remaining = subprocess.check_output(["ps", "-eo", "pid=,ppid=,args="], text=True).splitlines()
    assert not any(job_id in line and ("ffmpeg" in line or "fuse2fs" in line) for line in remaining), remaining
    assert sql("SELECT count(*) FROM mouse_video_artifact_refs WHERE analysis_id='" + str(uuid.UUID(analysis["analysisId"])) + "';") == "0"
    results["activeClaimAfterExpiry"] = after
    restart("active-restart")
    assert request("/jobs/" + job_id)["status"] == "NEEDS_RECONCILIATION"
    export_after = request(export_path, decode=False)
    if not isinstance(export_after, bytes):
        export_after = json.dumps(export_after, sort_keys=True)
    assert export_before == export_after
    results["originalFixtureChecksum"] = fixture["originalSha256"]
    results["noPartialAnalysisArtifacts"] = True
    results["oldExportUnchanged"] = True
    (args.evidence / "restart-results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("PASS queued recovery; active decoder crash; cross-instance expiry fence; no partial publication; old export unchanged", flush=True)


if __name__ == "__main__":
    main()

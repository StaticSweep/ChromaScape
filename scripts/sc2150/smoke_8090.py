#!/usr/bin/env python3
"""R3.7 write smoke for the deployed 8090 instance using one disposable synthetic clip.

Registers an owner-created fixture (never the independent held-out set), runs an assisted analysis,
reads observations and the frozen frame index, saves one human review correction, creates an
immutable export, then re-reads catalog, media, correction history and the export manifest to prove
the write path persisted. Refuses any host or port other than 127.0.0.1:8090.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

TERMINAL = {"SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "NEEDS_RECONCILIATION"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=900)
    args = parser.parse_args()
    parsed = urllib.parse.urlparse(args.base_url)
    assert parsed.hostname == "127.0.0.1" and parsed.port == 8090, "The write smoke targets 127.0.0.1:8090 only"
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    base = args.base_url.rstrip("/") + "/api/debug/mouse-videos"

    def request(path, method="GET", body=None, raw=None, headers=None, parse=True):
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
        return status, (json.loads(data) if parse and "json" in content_type else data)

    def poll(job_id, timeout=args.timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            status, job = request("/jobs/" + job_id)
            assert status == 200, job
            if job["status"] in TERMINAL:
                return job
            time.sleep(0.5)
        raise AssertionError("job deadline exceeded: " + job_id)

    clip = evidence / "r3-smoke.mp4"
    encoders = subprocess.check_output(["ffmpeg", "-hide_banner", "-encoders"],
                                      stderr=subprocess.DEVNULL).decode()
    encoder = "libx264" if "libx264" in encoders else "libopenh264"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i",
         "testsrc2=size=160x96:rate=30:duration=2", "-c:v", encoder, str(clip)],
        check=True, timeout=120)
    media_sha = hashlib.sha256(clip.read_bytes()).hexdigest()

    boundary = "sc2150-" + uuid.uuid4().hex
    raw = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
        'Content-Type: application/json\r\n\r\n'
        '{"declaredOrigin":"GENERATED","taskKey":"r3-smoke"}\r\n'
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="r3-smoke.mp4"\r\n'
        'Content-Type: video/mp4\r\n\r\n'
    ).encode() + clip.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    status, receipt = request(
        "/imports/upload", "POST", raw=raw,
        headers={"Content-Type": "multipart/form-data; boundary=" + boundary,
                 "X-Mouse-Video-Request-Id": str(uuid.uuid4())})
    assert status == 202, receipt
    imported = poll(receipt["jobId"])
    assert imported["status"] == "SUCCEEDED", imported
    source_id = receipt["sourceId"]

    status, source = request("/sources/" + source_id)
    assert status == 200 and source["originalQualified"], source
    assert source["originalChecksum"] == "sha256:" + media_sha, source["originalChecksum"]

    status, scheduled = request(
        f"/sources/{source_id}/analyses", "POST",
        {"requestId": str(uuid.uuid4()), "algorithmId": "visual/v1"})
    assert status in (200, 202), scheduled
    analysed = poll(scheduled["jobId"])
    assert analysed["status"] == "SUCCEEDED", analysed
    analysis_id = scheduled["analysisId"]
    status, analysis_source = request("/sources/" + source_id)
    analysis = next(item for item in analysis_source["analyses"] if item["id"] == analysis_id)
    assert analysis["status"] == "COMPLETE", analysis

    status, observations = request(
        f"/sources/{source_id}/analyses/{analysis_id}/observations?limit=500")
    assert status == 200 and observations["available"], observations
    cursor_items = [item for item in observations["items"] if item["kind"] == "CURSOR_VISIBILITY"]
    assert cursor_items, "the assisted analysis must publish cursor observations"

    status, index = request(f"/sources/{source_id}/analyses/{analysis_id}/frame-index?limit=500")
    assert status == 200 and index["items"], index
    ordinal = int(index["items"][min(3, len(index["items"]) - 1)]["ordinal"])

    preview_request = {"requestId": str(uuid.uuid4()), "frameOrdinal": ordinal, "maxWidth": 640, "maxHeight": 360}
    status, preview = request(
        f"/sources/{source_id}/analyses/{analysis_id}/frame-previews", "POST", preview_request)
    assert status in (200, 202), preview
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        status, preview_view = request("/frame-previews/" + preview["previewId"])
        if preview_view["state"] != "PENDING":
            break
        time.sleep(0.3)
    assert preview_view["state"] == "READY", preview_view

    correction = {"kind": "CURSOR_VISIBILITY", "frameOrdinal": ordinal, "visibility": "VISIBLE",
                  "x": int(preview_view["sourceWidth"] // 2), "y": int(preview_view["sourceHeight"] // 2)}
    status, review = request(
        f"/sources/{source_id}/reviews", "POST",
        {"requestId": str(uuid.uuid4()), "analysisId": analysis_id,
         "expectedBaseRevision": analysis_source["reviewRevision"],
         "reason": "SC-2150 R3.7 deployed write smoke", "sourceUse": "INSPECTION_ONLY",
         "corrections": [correction]})
    assert status == 201, review
    revision_id = review["id"] if "id" in review else review.get("revisionId")

    status, export = request(
        f"/sources/{source_id}/exports", "POST",
        {"requestId": str(uuid.uuid4()), "analysisId": analysis_id})
    assert status == 201, export
    status, manifest = request(f"/exports/{export['exportId']}/manifest")
    assert status == 200, manifest
    status, bundle = request(f"/exports/{export['exportId']}", parse=False)
    assert status == 200 and isinstance(bundle, bytes), type(bundle)
    assert hashlib.sha256(bundle).hexdigest() == export["manifestChecksum"].replace("sha256:", "")

    # Reload the persisted state the way an operator would.
    status, reloaded = request("/sources/" + source_id)
    assert status == 200, reloaded
    assert reloaded["reviewRevision"] >= 1, reloaded["reviewRevision"]
    status, reviews = request(f"/sources/{source_id}/reviews")
    assert status == 200, reviews
    stored = next(item for item in reviews["items"] if item["id"] == revision_id)
    stored_correction = next(
        item for item in stored["corrections"] if item["kind"] == "CURSOR_VISIBILITY")
    assert stored_correction["x"] == correction["x"] and stored_correction["y"] == correction["y"], stored_correction
    status, exports = request(f"/sources/{source_id}/exports")
    assert status == 200 and any(item["id"] == export["exportId"] for item in exports["items"]), exports
    status, media = request(f"/sources/{source_id}/media/ORIGINAL")
    assert status == 200, media
    assert hashlib.sha256(media).hexdigest() == media_sha, "served original bytes must match the upload"
    status, catalog = request("/sources")
    assert status == 200 and any(item["id"] == source_id for item in catalog["items"]), catalog

    result = {
        "baseUrl": args.base_url,
        "sourceId": source_id,
        "analysisId": analysis_id,
        "jobId": scheduled["jobId"],
        "coverage": analysis["coverage"],
        "cursorObservations": len(cursor_items),
        "previewId": preview["previewId"],
        "previewOrdinal": ordinal,
        "reviewRevisionId": revision_id,
        "reviewRevision": reloaded["reviewRevision"],
        "exportId": export["exportId"],
        "exportManifestChecksum": export["manifestChecksum"],
        "exportManifestKeys": sorted(manifest["manifest"].keys()),
        "trainingEligible": manifest["manifest"].get("capabilities", {}).get("trainingEligible"),
        "mediaSha256": media_sha,
    }
    (evidence / "smoke-8090.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2), flush=True)
    print("PASS", flush=True)


if __name__ == "__main__":
    main()

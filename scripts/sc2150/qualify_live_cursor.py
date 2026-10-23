#!/usr/bin/env python3
"""Live FA12 verification: score cursor/cut observations produced by a running SC-2150 server.

For every labelled corpus clip of one template, this uploads the clip through the real multipart
route, waits for ingestion, schedules the `visual/v1` analysis, pages the published observations
and frame index back over HTTP, and writes them in the harness predictions format so the frozen
`score.py` applies the same gates. When harness predictions for the same clips are given, it also
checks that the live path produced the same per-frame cursor/cut results (parity), which shows the
offline harness measures what production serves.

Refuses the normal 8090 service: the server must be a disposable loopback verification instance
whose `visual-cursor-template` is the clip template. Media is never copied into the evidence.
"""

import argparse
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

TERMINAL = ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "NEEDS_RECONCILIATION")


class Client:
    def __init__(self, base_url):
        self.base = base_url.rstrip("/") + "/api/debug/mouse-videos"
        self.records = []

    def request(self, path, method="GET", body=None, raw=None, headers=None, timeout=120):
        headers = dict(headers or {})
        if method == "POST":
            headers["X-Mouse-Video-Nonce"] = self.request("/write-nonce")[1]["nonce"]
        if body is not None:
            raw = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=raw, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            status = response.status
            content_type = response.headers.get("Content-Type", "")
        self.records.append({"method": method, "path": path.split("?")[0], "status": status,
                             "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
        if "json" in content_type:
            data = json.loads(data)
        return status, data

    def poll(self, job_id, deadline_seconds):
        deadline = time.monotonic() + deadline_seconds
        while time.monotonic() < deadline:
            status, job = self.request("/jobs/" + job_id)
            assert status == 200, job
            if job["status"] in TERMINAL:
                return job
            time.sleep(0.5)
        raise AssertionError("job %s exceeded %ss" % (job_id, deadline_seconds))

    def pages(self, path, params):
        cursor = None
        while True:
            query = dict(params)
            if cursor:
                query["cursor"] = cursor
            status, page = self.request(path + "?" + urllib.parse.urlencode(query))
            assert status == 200, page
            yield page
            cursor = page.get("nextCursor")
            if not cursor:
                return


def upload(client, media):
    boundary = "sc2150-" + uuid.uuid4().hex
    raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
           'Content-Type: application/json\r\n\r\n'
           '{"declaredOrigin":"GENERATED","taskKey":"sc2150-fa12-live"}\r\n'
           f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="clip.mp4"\r\n'
           'Content-Type: video/mp4\r\n\r\n').encode() + media.read_bytes() + \
        f"\r\n--{boundary}--\r\n".encode()
    headers = {"Content-Type": "multipart/form-data; boundary=" + boundary,
               "X-Mouse-Video-Request-Id": str(uuid.uuid4())}
    status, receipt = client.request("/imports/upload", "POST", raw=raw, headers=headers)
    assert status == 202, receipt
    return receipt


def live_predictions(client, label, media, deadline):
    started = time.monotonic()
    receipt = upload(client, media)
    job = client.poll(receipt["jobId"], deadline)
    assert job["status"] == "SUCCEEDED", job
    source_id = receipt["sourceId"]
    status, scheduled = client.request(f"/sources/{source_id}/analyses", "POST", {
        "requestId": str(uuid.uuid4()), "algorithmId": "visual/v1"})
    assert status in (200, 202), scheduled
    if scheduled.get("jobId"):
        job = client.poll(scheduled["jobId"], deadline)
        assert job["status"] == "SUCCEEDED", job
    analysis_id = scheduled["analysisId"]
    base = f"/sources/{source_id}/analyses/{analysis_id}"
    frames = [item for page in client.pages(base + "/frame-index", {"limit": 500})
              for item in page.get("items", [])]
    cursor, cuts, checksum = [], [], None
    for page in client.pages(base + "/observations", {"limit": 500}):
        assert page.get("available"), page
        checksum = page.get("artifactChecksum")
        for item in page["items"]:
            if item.get("kind") == "CURSOR_VISIBILITY":
                cursor.append({"ordinal": int(item["frameOrdinal"]),
                               "visibility": item.get("visibility"),
                               "x": int(item["x"]) if "x" in item else None,
                               "y": int(item["y"]) if "y" in item else None})
            elif item.get("kind") == "SCENE_CUT":
                cuts.append(int(item["frameOrdinal"]))
    ordinals = sorted(row["ordinal"] for row in cursor)
    gaps = {b - a for a, b in zip(ordinals, ordinals[1:])}
    step = min(gaps) if gaps else 1
    pts_by_ordinal = {int(frame["ordinal"]): frame["ptsTime"] for frame in frames}
    sampled = [pts_by_ordinal[o] for o in range(0, len(frames), step) if o in pts_by_ordinal]
    return {
        "schema": "sc2150-fa12-predictions/v1",
        "clip_id": label["clip_id"],
        "path": "live-http",
        "source_id": source_id,
        "analysis_id": analysis_id,
        "observations_artifact_checksum": checksum,
        "template": label["template"],
        "step": step,
        "sampled_frames": len(sampled),
        "indexed_frames": len(frames),
        "sampled_pts_time": sampled,
        "scene_cut_ordinals": sorted(cuts),
        "cursor": sorted(cursor, key=lambda row: row["ordinal"]),
        "wall_seconds": round(time.monotonic() - started, 2),
    }


def parity(live, harness):
    """Per-frame agreement between the live and harness cursor/cut results."""
    expected = {row["ordinal"]: row for row in harness["cursor"]}
    mismatches = []
    for row in live["cursor"]:
        other = expected.get(row["ordinal"])
        same = other is not None and other["visibility"] == row["visibility"] and (
            row["visibility"] != "VISIBLE" or (other["x"], other["y"]) == (row["x"], row["y"]))
        if not same:
            mismatches.append(row["ordinal"])
    missing = sorted(set(expected) - {row["ordinal"] for row in live["cursor"]})
    return {"frames": len(live["cursor"]), "mismatched_frames": len(mismatches),
            "first_mismatches": mismatches[:10], "missing_frames": len(missing),
            "cuts_equal": sorted(live["scene_cut_ordinals"]) == sorted(harness["scene_cut_ordinals"])}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--split", default="dev")
    parser.add_argument("--template", required=True, help="only clips labelled with this template")
    parser.add_argument("--clips", nargs="*", help="clip id substrings (default: every clip)")
    parser.add_argument("--harness", type=Path, help="harness predictions dir for parity")
    parser.add_argument("--out", type=Path, required=True, help="live predictions directory")
    parser.add_argument("--deadline", type=int, default=900)
    args = parser.parse_args()
    url = urllib.parse.urlparse(args.base_url)
    assert url.hostname == "127.0.0.1" and url.port not in (None, 8090), \
        "Disposable loopback verification server required"
    args.out.mkdir(parents=True, exist_ok=True)
    client = Client(args.base_url)
    status, capabilities = client.request("/capabilities")
    assert status == 200, capabilities
    results = []
    for label_file in sorted((args.corpus / args.split).glob("*.labels.json")):
        label = json.loads(label_file.read_text())
        if label["template"] != args.template:
            continue
        if args.clips and not any(part in label["clip_id"] for part in args.clips):
            continue
        media = label_file.with_name(label["clip_id"] + ".mp4")
        live = live_predictions(client, label, media, args.deadline)
        entry = {"clip_id": label["clip_id"], "source_id": live["source_id"],
                 "analysis_id": live["analysis_id"], "wall_seconds": live["wall_seconds"],
                 "media_sha256": hashlib.sha256(media.read_bytes()).hexdigest()}
        # score.py refuses predictions that do not name the media they were computed from.
        live["media_sha256"] = entry["media_sha256"]
        # The server's artifact checksum, not the harness's JSON hash: parity() compares frames.
        live["observations_sha256"] = live["observations_artifact_checksum"]
        if args.harness:
            harness_file = args.harness / (label["clip_id"] + ".predictions.json")
            if harness_file.exists():
                harness = json.loads(harness_file.read_text())
                # The supported-case rule is geometric; take it from the harness run.
                live["geometry_resolvable"] = harness.get("geometry_resolvable", True)
                entry["parity"] = parity(live, harness)
        (args.out / (label["clip_id"] + ".predictions.json")).write_text(json.dumps(live, indent=1))
        results.append(entry)
        print(json.dumps(entry), flush=True)
    (args.out / "live-run.json").write_text(json.dumps({
        "capabilities_sha256": hashlib.sha256(json.dumps(capabilities, sort_keys=True).encode()).hexdigest(),
        "template": args.template, "split": args.split, "clips": results,
        "http": client.records}, indent=1))


if __name__ == "__main__":
    main()

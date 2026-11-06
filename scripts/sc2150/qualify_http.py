#!/usr/bin/env python3
"""Exercise a disposable SC-2150 server using generated, non-licensed synthetic fixtures.

Requires ffmpeg/ffprobe and Pillow. Refuses the normal 8090 service. Does not start servers,
change configuration, use YouTube, or imply held-out accuracy qualification.
"""

import argparse
import hashlib
import io
from fractions import Fraction
import json
import re
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    url = urllib.parse.urlparse(args.base_url)
    assert url.hostname == "127.0.0.1" and url.port not in (None, 8090), "Disposable loopback server required"
    root = args.evidence
    root.mkdir(parents=True, exist_ok=True)
    base = args.base_url.rstrip("/") + "/api/debug/mouse-videos"
    records = []

    def request(path, method="GET", body=None, raw=None, headers=None):
        headers = dict(headers or {})
        if method == "POST":
            headers["X-Mouse-Video-Nonce"] = request("/write-nonce")[1]["nonce"]
        if body is not None:
            raw = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(base + path, data=raw, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            data = response.read()
            status = response.status
            response_headers = dict(response.headers)
        records.append({"method": method, "path": path, "status": status,
                        "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
        if "json" in response_headers.get("Content-Type", ""):
            data = json.loads(data)
        return status, data, response_headers

    def poll(job_id):
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            status, job, _ = request("/jobs/" + job_id)
            assert status == 200, job
            if job["status"] in ("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "NEEDS_RECONCILIATION"):
                return job
            time.sleep(.2)
        raise AssertionError("job deadline exceeded")

    def ffmpeg(*options):
        subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-y", "-threads", "2",
                        "-filter_threads", "1", *map(str, options)], check=True, timeout=60)

    encoders = subprocess.check_output(["ffmpeg", "-hide_banner", "-encoders"], stderr=subprocess.DEVNULL).decode()
    encoder = "libx264" if "libx264" in encoders else "libopenh264"

    fixtures = root / "fixtures"
    fixtures.mkdir(exist_ok=True)
    cfr = fixtures / "cfr.mp4"
    ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=30:duration=2", "-c:v", encoder, cfr)
    vfr = fixtures / "vfr.mp4"
    ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=60:duration=2",
           "-vf", "select='not(mod(n,2))+not(mod(n,5))'", "-fps_mode", "vfr", "-c:v", encoder, vfr)
    rotated = fixtures / "rotated.mp4"
    ffmpeg("-display_rotation", "90", "-i", cfr, "-c", "copy", rotated)
    rotation = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream_side_data", "-of", "json", str(rotated)]))
    assert any(abs(item.get("rotation", 0)) == 90 for item in rotation["streams"][0].get("side_data_list", [])), rotation
    cropped = fixtures / "cropped.mp4"
    ffmpeg("-i", cfr, "-vf", "crop=112:64:16:16", "-c:v", encoder, cropped)
    bitstream_crop = fixtures / "bitstream-crop.mp4"
    ffmpeg("-i", cfr, "-c", "copy", "-bsf:v", "h264_metadata=crop_left=16:crop_right=32:crop_top=16:crop_bottom=16", bitstream_crop)
    burst = fixtures / "burst-vfr.mp4"
    ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=120:duration=2",
           "-vf", "select='lt(n,60)+eq(n,120)+eq(n,180)+eq(n,239)'", "-fps_mode", "vfr", "-c:v", encoder, burst)
    results = []
    for fixture in (cfr, vfr, rotated, cropped, bitstream_crop, burst):
        request_id = str(uuid.uuid4())
        boundary = "sc2150-" + uuid.uuid4().hex
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
               'Content-Type: application/json\r\n\r\n{"declaredOrigin":"GENERATED","taskKey":"synthetic"}\r\n'
               f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.mp4"\r\n'
               'Content-Type: video/mp4\r\n\r\n').encode() + fixture.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
        headers = {"Content-Type": "multipart/form-data; boundary=" + boundary,
                   "X-Mouse-Video-Request-Id": request_id}
        status, receipt, _ = request("/imports/upload", "POST", raw=raw, headers=headers)
        assert status == 202, receipt
        assert poll(receipt["jobId"])["status"] == "SUCCEEDED", receipt
        status, retry, _ = request("/imports/upload", "POST", raw=raw, headers=headers)
        assert status == 202 and retry["jobId"] == receipt["jobId"], retry
        source_id = receipt["sourceId"]
        source = request("/sources/" + source_id)[1]
        assert source["originalQualified"]
        analysis_id = source["selectedAnalysisId"]
        # Exercise the visual worker on one fixture; exact previews on all four source types.
        if fixture in (cfr, burst):
            status, scheduled, _ = request(f"/sources/{source_id}/analyses", "POST", {
                "requestId": str(uuid.uuid4()), "algorithmId": "visual/v1"})
            assert status in (200, 202), scheduled
            assert poll(scheduled["jobId"])["status"] == "SUCCEEDED"
            analysis_id = scheduled["analysisId"]
            observations = request(f"/sources/{source_id}/analyses/{analysis_id}/observations?limit=500")[1]
            assert observations["available"]
            if fixture == burst:
                # Without an approved template the producer emits one explicit cursor unknown,
                # not one position per sample. Verify its declared sampling plan against PTS.
                coverage = request(f"/sources/{source_id}")[1]["coverage"]
                step = int(re.search(r"\(step (\d+)\)", coverage).group(1))
                source_frames = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames", "-show_entries", "frame=pts_time", "-of", "json", str(fixture)]))["frames"]
                times = [Fraction(frame["pts_time"]) for frame in source_frames[::step]]
                assert len(times) > 3 and all(b-a >= Fraction(1,30)-Fraction(1,1000000) for a,b in zip(times,times[1:])), times
        index = request(f"/sources/{source_id}/analyses/{analysis_id}/frame-index?limit=500")[1]
        indexed_pts = [int(frame["pts"]) for frame in index["items"]]
        frames = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0",
            "-show_frames", "-show_entries", "frame=pts", "-of", "json", str(fixture)]))["frames"]
        assert indexed_pts == [int(frame["pts"]) for frame in frames], (fixture.name, index)
        if fixture == vfr:
            assert len(set(b-a for a, b in zip(indexed_pts, indexed_pts[1:]))) > 1
        preview_body = {"requestId": str(uuid.uuid4()), "frameOrdinal": 5, "maxWidth": 640, "maxHeight": 360}
        status, scheduled, _ = request(f"/sources/{source_id}/analyses/{analysis_id}/frame-previews", "POST", preview_body)
        assert status in (200, 202), scheduled
        deadline = time.monotonic() + 150
        while time.monotonic() < deadline:
            preview = request("/frame-previews/" + scheduled["previewId"])[1]
            if preview["state"] != "PENDING":
                break
            time.sleep(.2)
        assert preview["state"] == "READY", preview
        status, actual, _ = request("/frame-previews/" + scheduled["previewId"] + "/media")
        assert status == 200
        expected = fixtures / (fixture.stem + "-expected.png")
        ffmpeg("-i", fixture, "-vf", "select=eq(n\\,5)", "-frames:v", "1", expected)
        actual_image = Image.open(io.BytesIO(actual)).convert("RGB")
        expected_image = Image.open(expected).convert("RGB")
        assert actual_image.size == expected_image.size
        assert actual_image.tobytes() == expected_image.tobytes(), fixture.name
        assert (preview["sourceWidth"], preview["sourceHeight"]) == actual_image.size
        if fixture == rotated:
            assert actual_image.size == (96, 160) and abs(preview["rotationDegrees"]) == 90, preview
        if fixture in (cropped, bitstream_crop):
            assert actual_image.size == (112, 64)
        assert int(preview["pts"]) == indexed_pts[5]
        if fixture == cfr:
            media_path = f"/sources/{source_id}/media/ORIGINAL"
            assert request(media_path)[0] == 200
            assert request(media_path, headers={"Range": "bytes=0-9"})[0] == 206
            assert request(media_path, headers={"Range": "bytes=999999999-"})[0] == 416
            def review(correction, expected):
                current = request(f"/sources/{source_id}")[1]
                code, payload, _ = request(f"/sources/{source_id}/reviews", "POST", {
                    "requestId": str(uuid.uuid4()), "analysisId": analysis_id,
                    "expectedBaseRevision": current["reviewRevision"], "reason": "Synthetic geometry qualification",
                    "sourceUse": "INSPECTION_ONLY", "corrections": [correction]})
                assert code == expected, (code, payload)
            review({"kind":"CURSOR_VISIBILITY", "frameOrdinal":5, "visibility":"VISIBLE", "x":1000, "y":10},400)
            review({"kind":"TARGET_REGION", "frameOrdinal":5, "x":150, "y":10, "width":20, "height":10},400)
            review({"kind":"CURSOR_VISIBILITY", "frameOrdinal":4, "visibility":"VISIBLE", "x":10, "y":10},422)
            review({"kind":"CURSOR_VISIBILITY", "frameOrdinal":5, "visibility":"VISIBLE", "x":159, "y":95},201)
        preview_body["requestId"] = str(uuid.uuid4())
        repeat = request(f"/sources/{source_id}/analyses/{analysis_id}/frame-previews", "POST", preview_body)[1]
        assert repeat["previewId"] == preview["previewId"] and repeat["state"] == "READY"
        result = {"fixture": fixture.name, "sourceId": source_id, "analysisId": analysis_id,
                  "previewId": preview["previewId"], "frames": len(frames), "size": actual_image.size,
                  "originalSha256": hashlib.sha256(fixture.read_bytes()).hexdigest(), "previewSha256": hashlib.sha256(actual).hexdigest()}
        results.append(result)
        print(json.dumps(result), flush=True)
    (root / "http-records.json").write_text(json.dumps(records, indent=2) + "\n")
    (root / "fixture-results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("HTTP synthetic qualification PASS", flush=True)


if __name__ == "__main__":
    main()

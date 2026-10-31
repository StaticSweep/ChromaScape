"""Cursor presence per YouTube section: upload to the disposable copy, run visual/v1 (arrow
template), count VISIBLE / AMBIGUOUS / ABSENT samples and scene cuts. Hashes and counts only."""
import collections, hashlib, importlib.util, json, pathlib, sys, time, uuid

repo = pathlib.Path(sys.argv[1])
base_url, out = sys.argv[2], pathlib.Path(sys.argv[3])
assert base_url.startswith("http://127.0.0.1:1819")
spec = importlib.util.spec_from_file_location("q", repo / "scripts/sc2150/qualify_live_cursor.py")
q = importlib.util.module_from_spec(spec); spec.loader.exec_module(q)
client = q.Client(base_url)
manifest = json.loads((repo / "build/sc2150-yt/manifest.json").read_text())
run = repo / "docs/builderflow/sc-2150/verification-runs/20261023T021614Z-fa12-real-footage"
verdicts = {(s["videoId"], s["section"]): s for s in json.loads((run / "screening.json").read_text())["sections"]}
split = json.loads((run / "split.json").read_text())["sources"]
results = []
for s in manifest["sections"]:
    media = repo / "build/sc2150-yt/raw" / s["file"]
    data = media.read_bytes()
    assert hashlib.sha256(data).hexdigest() == s["sha256"], s["file"]
    started = time.monotonic()
    receipt = q.upload(client, media)
    job = client.poll(receipt["jobId"], 1800)
    row = {"videoId": s["videoId"], "section": s["section"], "channel": s["channel"],
           "split": split[s["videoId"]]["split"], "verdict": verdicts[(s["videoId"], s["section"])]["verdict"],
           "fps": verdicts[(s["videoId"], s["section"])]["fps"], "importStatus": job["status"]}
    if job["status"] == "SUCCEEDED":
        sid = receipt["sourceId"]
        status, sched = client.request(f"/sources/{sid}/analyses", "POST",
                                       {"requestId": str(uuid.uuid4()), "algorithmId": "visual/v1"})
        assert status in (200, 202), sched
        if sched.get("jobId"):
            row["analysisStatus"] = client.poll(sched["jobId"], 1800)["status"]
        counts, cuts = collections.Counter(), 0
        if row.get("analysisStatus", "SUCCEEDED") == "SUCCEEDED":
            for page in client.pages(f"/sources/{sid}/analyses/{sched['analysisId']}/observations", {"limit": 500}):
                for item in page["items"]:
                    if item.get("kind") == "CURSOR_VISIBILITY":
                        counts[item.get("visibility")] += 1
                    elif item.get("kind") == "SCENE_CUT":
                        cuts += 1
        total = sum(counts.values())
        row.update(samples=total, visibility=dict(counts), sceneCuts=cuts,
                   visibleFraction=round(counts.get("VISIBLE", 0) / total, 3) if total else None)
    row["wallSeconds"] = round(time.monotonic() - started, 1)
    results.append(row)
    print(json.dumps(row), flush=True)
    out.write_text(json.dumps({"schema": "sc2150-yt-cursor-presence/v1", "template": "arrow",
                               "sections": results}, indent=1))

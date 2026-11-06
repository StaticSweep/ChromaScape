# FA12 live verification — cursor-template/v5, arrow dev clips

- Server: disposable loopback copy of this checkout on 127.0.0.1:18190, database
  `sc2150_fa12_live`, started with `scripts/sc2150/start_verification.py` from a runtime snapshot of
  `./gradlew classes` (tracker compiled as `cursor-template/v5`). Cursor template `arrow.png`,
  hotspot 0,0; `capabilities.features.cursorTrackingAvailable == true`. Stopped after the run.
- Override: `CHROMASCAPE_MOUSE_VIDEO_PROBE_BUDGET_SECONDS=180` (default 30). See "Probe budget".
- Client: `scripts/sc2150/qualify_live_cursor.py --template arrow --split dev`, parity against the
  offline harness run `build/sc2150-fa12-v3-out/v5-dev-1`. Clips go through the real multipart
  upload, ingestion, `visual/v1` analysis and the paged observation/frame-index reads.
- Scorer: frozen `../20261023T163000Z-fa12-v5/tools/score.py`
  (sha256 `43e2c40a1cbdb9aba292d921c6ba9272e767a409686c3ac024b377329386c238`), run on an
  arrow-only view of the dev split. Held-out was **not** used.

## Parity (live HTTP path vs offline harness)

| clips | frames | mismatched | missing | cuts equal | HTTP records (non-2xx) |
|---|---|---|---|---|---|
| 29 | 8243 | 0 | 0 | 29/29 | 5552 (0) |

The live and harness score reports for these 29 clips are identical in every metric (the only
differing fields are timing and the observation hash, which the two paths compute differently).

## Scores (arrow dev, 28 supported clips)

| recall | median px | p95 px | absent false rate | cut F1 (tp/fp/fn) |
|---|---|---|---|---|
| 0.966 (CI 0.920–0.994) | 0.76 | 1.70 | 0.0 | 1.0 (2/0/0) |

Gates: median, p95, absent false rate and cut F1 pass; **recall (0.99) fails**. This is the arrow
subset only; the full dev split result is in `../20261023T163000Z-fa12-v5/`.

## Probe budget

The first attempt (default budget) failed one clip with `NEEDS_RECONCILIATION /
CLEANUP_UNCONFIRMED` at the PUBLISH stage while a second server was importing. The frame-index
ffprobe shares `probeBudgetSeconds` (30 s); a 60 s 1080p60 section takes ~41 s of ffprobe on this
host, so real 60 fps footage cannot be imported at the default. The rerun used 180 s and passed.

## Script fixes made during the run

`qualify_live_cursor.py` wrote prediction files without `media_sha256` and `observations_sha256`,
which `score.py` requires. It now writes the uploaded media's hash and the server's observation
artifact checksum. The 29 prediction files of this run were backfilled from `live-run.json`
(the hashes the script computed from the uploaded files).

## Files

- `results/live-arrow-dev.score.json`, `results/harness-arrow-dev.score.json` — score outputs.
- `results/live-run-summary.json` — per-clip parity, ids and hashes; the full HTTP record log
  stays in `build/sc2150-live/arrow-dev/live-run.json` (sha256 in the summary). No media.

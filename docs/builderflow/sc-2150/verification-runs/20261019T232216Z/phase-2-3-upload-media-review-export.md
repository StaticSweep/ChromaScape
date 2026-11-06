# SC-2150 Phases 2-3 — real upload, media, lifecycle, review and export

Run ID: `20261019T232216Z`
Isolated instance: `http://127.0.0.1:8190`, profile `local-development`, DB
`chromascape_sc2150_verify` on `127.0.0.1:5433`, media root `/tmp/sc2150-media`,
`MOUSE_VIDEO_ENABLED=true`, `YOUTUBE_ENABLED=false`. The pre-existing instance on 8090
was not stopped, replaced or attached; it was still listening after this phase.
Test principal resolved by the same-host bridge: `ikiddoi` (single owner — see limitations).

Fixtures (synthetic, generated locally with ffmpeg/libopenh264):

| Fixture | Frames | Codec | SHA-256 (prefix) |
|---|---|---|---|
| cfr30h.mp4 (320x240, 30fps, 2.0s) | 60 | h264 | `c2aba1cb2d1042f7` |
| vfrh.mp4 (20 of 60 frames, VFR) | 20 | h264 | `8fbc867b429fb241` |
| tinyh.mp4 | 5 | h264 | `c3192854cfb580ca` |
| cfr30.mp4 (mpeg4) | 60 | mpeg4 | `f5f58985fcf0ff48` |
| disguised.mp4 (text) | — | — | `0a835f30fa1f3ecb` |

## Results

| Case | Expected | Observed | State |
|---|---|---|---|
| Small valid upload | one owner-scoped source/job, digest/length, playable media, resolvable refs | 202 → source `3aaddc92…`, job `bfdb2602…`; `originalChecksum` = fixture SHA-256; 50049 bytes; `originalQualified=true`; ORIGINAL served 200 | PASS |
| Frame index accuracy | declared PTS/timebase correct | `frame-index/v2`, `timeBase 1/15360`, 60 frames, PTS 0…30208 step 512 (=1/30 s), `complete=true`; coverage `60 frames, indexed=60` matches ffprobe | PASS |
| Original range requests | 200/206/416 | 200 + `nosniff` + `Cache-Control: no-store, private`; `Range 0-99` → 206 `bytes 0-99/50049`; out-of-range → 416 | PASS |
| Identical retry (same request id + bytes) | same source/job, no duplicate charge | same source `3aaddc92…` and job; sources/jobs counts unchanged; one owner budget row | PASS |
| Same request id, changed bytes | 409 conflict | `CONFLICT`, HTTP 409 | PASS |
| Missing write nonce | typed refusal | `FORBIDDEN`, HTTP 403 | PASS |
| Forged nonce | typed refusal | `FORBIDDEN` "malformed", HTTP 403 | PASS |
| Malformed request id | typed 400 | `INVALID_REQUEST`, HTTP 400 | PASS |
| Unsupported codec (mpeg4 mp4) | truthful bounded refusal | job FAILED `UNSUPPORTED_MEDIA` at PROBE; original stays QUARANTINED | PASS |
| Disguised non-video | typed refusal, no complete evidence | job FAILED `DECODER_FAILED`; source FAILED, `originalQualified=false`, no analyses | PASS (with finding F4) |
| Proxy / thumbnail retrievable | resolvable refs | **HTTP 422 `SOURCE_UNAVAILABLE`** for both, although DB rows exist FINALIZED | **FAIL (F1)** |
| Cancel during probe | stops, CANCELLED after cleanup | job reached `CANCELLED`, revision 1 | PASS (with finding F2) |
| Cancel HTTP response | success/conflict response | **HTTP 500** `ObjectOptimisticLockingFailureException` / `StaleStateException` | **FAIL (F2)** |
| Malformed metadata (unknown field) | typed bounded refusal | **HTTP 500** `IOException` surfaced as servlet error | **FAIL (F3)** |
| Append-only review | create, stable retry, stale conflict | 201 rev1; identical retry same id 201; stale base → 409 `CONFLICT` | PASS |
| Immutable export | old bytes identical, pinned lineage | export1 sha `8cbceaad…` = `manifestChecksum`; after review rev2 export2 sha `3e6e3a40…`; export1 re-download byte-identical; `GET /manifest` created no export (count 2 → 2) | PASS |
| Import must not grant training admission | no admission | analysis flags `nativeTelemetry=false`, `exactTicksAvailable=false`, `controlAllowed=false`, `trainingEligible=false` | PASS |
| Unauthenticated 401 | 401 | not reachable: instance binds loopback and the `local-development` bridge authenticates every local request | BLOCKED (L1) |
| Two distinct owners / foreign-owner 404 | 404 | only one principal source exists in this build (`user.name` bridge) | BLOCKED (L2) |

## Findings (reproducers retained under `http/`)

**F1 — PROXY/THUMBNAIL media unresolvable (FAIL, affects VA5).**
`GET /sources/{id}/media/PROXY` and `/THUMBNAIL` return 422 `SOURCE_UNAVAILABLE`, while
`mouse_video_artifact_refs` holds both roles `FINALIZED` (proxy 286982 B `video/mp4`,
thumbnail 15120 B `image/png`) and the analysis coverage says `proxy=true`. Cause: both
rows carry a non-null `analysis_id`, but `MouseVideoCatalogService.media()` looks up with
`findBySourceIdAndRoleAndAnalysisIdIsNullAndState(..., FINALIZED)`. The source detail
`artifacts` array likewise omits them. Smallest repair: resolve derivative roles against
the latest completed analysis (or drop the `analysisId IS NULL` predicate for PROXY /
THUMBNAIL) and expose them in the source view.

**F2 — Cancellation returns 500 (FAIL, affects VA4).**
`POST /jobs/{id}/cancel` produced HTTP 500 from
`ObjectOptimisticLockingFailureException: Batch update returned unexpected row count …
update mouse_video_jobs … where id=? and version=?`, yet the job did reach `CANCELLED`.
Smallest repair: treat the optimistic-lock conflict as a lost race — re-read and return
the current job state (202/409) instead of propagating a 500.

**F3 — Malformed upload metadata returns 500, not a typed refusal (FAIL, affects VA3/VA6).**
`metadata` containing an unsupported field (`contextLabel`) yields
`java.io.IOException: upload metadata contains an unsupported field: contextLabel` surfaced
as HTTP 500. Smallest repair: map reader `IOException` to a typed 4xx
(`INVALID_REQUEST`/`MEDIA_LIMIT` as appropriate).

**F4 — Absolute server path disclosed in job error (risk, affects VA6).**
`DECODER_FAILED` `errorMessage` includes `/tmp/sc2150-media/bytes/0a/0a835f30…`. Smallest
repair: report a bounded client-facing reason and keep the path in server logs only.

## Limitations recorded

- **L1/L2** — Outside `local-development` there is no principal provider, so 401 and a
  second real owner cannot be exercised on this build without inventing identity. Recorded
  as BLOCKED, not PASS.
- Concurrency races (concurrent identical retry, second-instance startup, lease
  expiry/replacement across processes) remain covered by the automated PostgreSQL races in
  Phase 1 (9/9 passed); they were not re-driven manually against 8190.
- Frame cap / cap+1 and oversized upload were not exercised (generating a >512 MiB fixture
  and a 150 001-frame clip was out of scope for this pass). Disposition: NOT RUN.

# External video import and human-behavior analysis (SC-2150)

Bounded import of uploaded video and supported YouTube links into Mouse Research, with immutable
media/provenance, durable idempotent jobs, a bounded probe, owner-scoped catalog inspection,
append-only review and immutable versioned exports.

**Video-derived observations are estimates. Importing a clip does not verify human input. Native
ticks, physical clicks, hidden world state and training eligibility are never derived from external
media.**

## Ownership boundaries

- SC-2150 owns import, media artifacts, video observations and the minimal review needed to inspect
  them.
- SC-2151 owns video-to-native-tick alignment (not implemented here).
- SC-309 owns bad/unnatural movement detection and calibrated verdicts (not implemented here).
- SC-712 owns published curation and dataset admission. Imported sources default to
  inspection-only; `training_eligible` is constrained `false` in SQL.

## Trusted ownership

- Every protected route requires a server-established servlet `Principal`. Missing identity is
  `401`; there is no owner header, anonymous, or `local-operator` fallback.
- The existing same-host principal bridge is extended to `/api/debug/mouse-videos/*` only in the
  sole `local-development` profile for direct local peers. It is never enabled for mixed profiles
  and never trusts forwarding headers.
- Mutations require a server-issued nonce bound to the resolved owner (`GET /write-nonce`,
  `X-Mouse-Video-Nonce`) and are rejected for cross-origin/`cross-site` browser requests.
- Foreign object IDs return `404`; media, jobs, analyses, reviews and exports are owner-scoped.

## Endpoints (`/api/debug/mouse-videos`)

| Method/route | Purpose |
|---|---|
| `GET /capabilities` | Enabled flags, tools, formats, methods, features, limits and typed unknowns |
| `GET /write-nonce` | Short-lived write nonce bound to the authenticated owner |
| `GET /observability` | Bounded counters (refusals by error code, intake/media refusals, reconciliations fenced and resolved) and owned-worker count (no media, credentials, actor, source or URL labels) |
| `POST /jobs/{id}/reconcile` | Owner settles a `NEEDS_RECONCILIATION` job as `FAILED` after a cleanup proof (no worker, no mounted workspace, no unattributed `chromascape-video-*` scope); 409 with the blocker otherwise |
| `POST /imports/upload` | Bounded streaming multipart (`file` + `metadata`), `X-Mouse-Video-Request-Id` UUID header |
| `POST /imports/youtube` | Supported single-video link through the pinned constrained acquirer |
| `GET /sources` | Owner-scoped catalog (`cursor`, `limit` 1–25, `kind`) |
| `GET /sources/{id}` | Source, jobs, artifacts, analysis summary and qualification state |
| `GET /sources/{id}/media/{role}` | Verified inline `ORIGINAL`/`PROXY`/`THUMBNAIL` with HTTP Range |
| `GET /jobs/{id}` | Durable status/stage/progress/generation/cancellation revision |
| `POST /jobs/{id}/cancel` | Fenced cancellation; `CANCELLED` only after owned cleanup is confirmed |
| `GET /sources/{id}/exports` | Immutable export versions |
| `POST /sources/{id}/exports` | Create/pin an immutable snapshot; identical retry reuses it |
| `GET /exports/{id}` | Repeatable download of the immutable manifest |
| `GET /exports/{id}/manifest` | Manifest JSON without mutation |
| `GET /sources/{id}/reviews` | Append-only review revisions |
| `POST /sources/{id}/reviews` | Append a correction with expected base revision |
| `GET /reviews/{id}?sourceId=…` | Retrieve one review revision |
| `GET /sources/{id}/analyses/{analysisId}/frame-index` | Bounded page of the frozen frame index |
| `GET /sources/{id}/analyses/{analysisId}/observations` | Bounded page of published observations (`kind`, `sincePts`, `untilPts`) |

`GET /capabilities` also carries `readiness`: one ladder per capability (`media-import`,
`cursor-tracking`, `youtube-acquisition`, `review-protocol`) with `configured` → `available` →
`runnable` → `isolated` → `qualified`. Each rung implies the ones before it and `limitedBy` names
the first that fails. `qualified` is never inferred from the host: the operator records it in
`chromascape.mouse-video.qualified-capabilities` (env `CHROMASCAPE_MOUSE_VIDEO_QUALIFIED_CAPABILITIES`)
once that capability's evidence has been accepted.

## Upload contract

Global eager multipart parsing remains disabled (`spring.servlet.multipart.enabled: false`) so
authentication and limits run before the servlet stream is consumed. The upload route uses its own
bounded reader:

- one `file` part and one `metadata` (`application/json`) part; duplicate/unknown parts rejected;
- request ≤ 520 MiB, file ≤ 512 MiB, metadata ≤ 16 KiB, bounded headers/part count;
- limits are enforced while bytes are read, including chunked bodies;
- hashing happens while streaming; the caller filename is never used as a storage path;
- the caller `Content-Type` is untrusted metadata and is never reflected into response headers.

Request identity binds authenticated owner, request UUID, kind, exact file SHA-256 + length,
normalized metadata and ingestion contract version. Identical retries return the same source/job;
different bytes or metadata return `409`. Registration of source, quarantined original ref, job,
event and quota charge is one transaction; scheduling happens only after commit. Intake capacity is
reserved before streaming and swept when it expires.

## Media safety

- Uploaded originals are stored as `application/octet-stream` and quarantined.
- A successful probe chooses a server-side allowlisted type (`video/mp4`, `video/webm`,
  `video/ogg`; `image/png`/`image/jpeg` for thumbnails) from the actual container/codec.
- Unqualified originals are never served inline. Media responses are `nosniff`, private/no-store,
  `Content-Security-Policy: sandbox; default-src 'none'`, and support single-range requests.
- HTML/SVG/XML disguised as video cannot be served as trusted inline content.

## Job concurrency

- Claims use a row lock; queued jobs and expired unexpired-lease reclaims acquire a fresh generation.
- Heartbeats compare generation, RUNNING status, lease owner and the database clock.
- Finalization locks the job, re-validates generation/lease and registers analysis, artifact refs,
  success and event in one transaction. A cancellation that wins first bumps the generation and
  prevents every completed analysis/ref.
- Cancellation of a running job enters `CANCELLING`, signals the owned worker, and reports
  `CANCELLED` only after cleanup confirmation; ambiguity is `NEEDS_RECONCILIATION`.
- A reference-aware sweeper deletes only unreferenced published byte objects after a grace period.

## Exports

Exports are explicit immutable snapshots. Identity pins source/original digest, optional
analysis + manifest hash, review revision, artifact roles/checksums and contract version. Identical
retries return the existing export (`200`); a new snapshot returns `201`; the same request key with
different pinned content returns `409`. Old snapshots remain downloadable after newer
analysis/review evidence creates a distinct version. The manifest contains no private filesystem
paths.

## Review export to FA12 corpus labels

`scripts/sc2150/labels_from_review_export.py` is the only path from review-screen corrections to
the labels the FA12 scorer reads (`sc2150-fa12-labels/v2`). It takes the export manifest, the
review revision the export pins, and the frozen frame-index pages:

```
python3 scripts/sc2150/labels_from_review_export.py \
  --export export.json --export-id <export-uuid> \
  --review review.json --frame-index pages/ \
  --out <corpus>/dev/<clip-id>.labels.json \
  --split dev --labeller <owner|claude> --labelling-protocol without-suggestions \
  --source-size 1920x1080 --output-size 480x270
```

- **Frame index:** `--frame-index` takes a page JSON or a directory of pages (a 60 s 1080p60
  section is about 36 pages); every ordinal must be present, so an incomplete index aborts.
- **Coordinates:** corrections carry exact source-frame pixels of the cursor hotspot; `--source-size`
  is that space and `--output-size` is the analysis plane the harness predicts in. Points map by
  `(c + 0.5) * output / source - 0.5`, and `px_to_1080p` converts output pixels to the 1080p scale
  the gates are stated in.
- **States:** `VISIBLE` becomes `VISIBLE` with `x`/`y`; `HIDDEN` becomes `ABSENT`; `AMBIGUOUS` and
  `UNSUPPORTED` are emitted as `dropped`, never scored as absent. Frames the labeller did not mark
  are absent from `labels`, which excludes them from scoring; nothing is interpolated.
- **Provenance (RF2):** the review revision must be the one the export pins, `--labeller` and
  `--labelling-protocol` are mandatory, and a held-out clip may only declare
  `without-suggestions`, so a held-out score cannot measure agreement with the detector being
  evaluated. Manifest, revision, corrections and frame-index digests are recorded.
- **Cuts and W8 spans:** `SCENE_CUT` corrections become `cut_compose_ordinals`; pause, indicator,
  target-region and camera-activity corrections are preserved under `additional_spans`.
- **Refusals:** a variable-rate index without `--fps`, a missing or unlabeled frame index,
  corrections outside `--source-size`, an unknown correction kind, and preview geometry that
  contradicts `--source-size` all abort instead of producing labels.

## Catalog: My recordings vs From others

The replay page (`/replay`) lists recordings in two tabs:

- **My recordings**: ChromaScape's own captures (`/api/recordings/v1/sessions`), unchanged.
- **From others (YouTube / uploads)**: sources from this video library (`GET /sources`), with
  a YouTube provenance link (only whole `https://` YouTube URLs are linked), declared origin,
  playability (`ORIGINAL` when qualified, else `PROXY` of the selected analysis), and an *Open in
  Mouse Video* link (`/mouse/video?source=<id>`).

External videos are never written to the recordings table, never feed tick replay, and are never
training eligible. Either tab fails independently: a missing library (404) or sign-in (401) shows
a message in its own tab only.

`scripts/sc2150/import_corpus_to_library.py --base-url http://127.0.0.1:<port> [--dry-run]` uploads
the FA12 YouTube corpus (`build/sc2150-yt/`) through the normal upload route: loopback targets only,
SHA-256 checked against the manifest, deterministic request ids (re-runs are idempotent), and
receipts with hashes and ids only. Because the request ids are deterministic, a section whose job
failed returns that same failed job when re-run; re-import it into a fresh database.

The import's frame index (an ffprobe over every frame) decodes the whole stream, so it runs under
`analysis-budget-seconds` (1200), like the thumbnail and proxy encodes. `probe-budget-seconds` (30)
covers only the header probe. Before 2026-10-24 the frame index used the probe budget, and every
60 fps section timed out (a 60 s 1080p60 section takes about 41 s).

## Visual analysis (`visual/v1` schedule method)

Samples are decoded once with the fixed command in `MouseVideoAnalysisWorker.samplingCommand`.
They are grayscale, at most `visual-sample-max-width` px wide (default 480), with a PTS-window
stride.

**Cursor: `cursor-template/v8`** (`MouseVideoCursorTracker`). This runs only when a reviewed
cursor template PNG is configured (`visual-cursor-template`, hotspot `visual-cursor-hotspot-x/-y`).
The template belongs to one configured pack (`visual-cursor-template-pack-id`) and may be pinned
by `visual-cursor-template-sha256`; see
[assisted profile and cursor-template provisioning](builderflow/sc-2150/assisted-profile-provisioning.md)
for the private seeding, digest verification and restart flow. `GET /capabilities` exposes the
resolved `analysisProfile`, and a request may only name the configured pack.
`cursor-template/v6` was an unpublished intermediate without the guarded re-check takeover. The
method id is part of the analysis identity, so each bump keeps older local analyses from being reused.
- The template is rendered at its displayed scale with 4×4 sub-pixel phases. Only analysis pixels
  the sprite fully covers are compared, using zero-mean normalized correlation, with a luma-gain
  range and a minimum patch contrast.
- The displayed size is calibrated once per analysis from 24 spread samples, by summed Fisher z
  over a 2^(1/8) scale grid. If the chosen size is the smallest one that can be rendered, the
  cursor is reported as **too small to resolve** and every frame stays ambiguous.
- Static interface art that resembles the cursor is rejected by comparing each placement with the
  samples about 1.5 s before and after it, and with the preceding sample. A cursor that stops is
  held only where it was detected in the **previous sample** and only while it is unchanged.
  **(v8)** A missed sample ends the hold: in FA12 dev `compressed-300k` the cursor left over
  cursor-like art during one missed sample and v7 resumed that art as a "held" cursor for 87
  samples (all 47 absent-frame false detections of R28).
- Hysteresis: a detection that does not continue the previous one must reach
  `visual-cursor-start-score`; a continuation needs `visual-cursor-min-score`. While a track
  exists, only the area near it is searched, falling back to the whole frame when nothing there
  qualifies.
- A frame with a distinct runner-up within `visual-cursor-ambiguity-margin` is `AMBIGUOUS`. A
  detection never implies a click or a physical input event.
- Two passes: the analyzer tracks the samples forward, then backward from the last sample with
  the track restarted and the calibration kept. A forward miss takes the backward detection only
  when it continues a backward track, so it needs `visual-cursor-min-score` exactly like a forward
  continuation. This recovers track starts and bridges short gaps from their far side; a forward
  detection is never replaced. Upgraded observations carry `evidence.pass = "backward"` and the
  forward `forwardReason`.
- **Start must be an appearance (v7).** A placement that would start a track is rejected unless its
  match beats the same patch at the same location in the preceding sample by more than
  `visual-cursor-ambiguity-margin`. Interface
  art that only looked new — because a competing placement closed, or because the world moved
  behind a semi-transparent panel — is what made v5 resume on static marks; a cursor that arrives
  leaves background at that location one sample earlier.
- **Whole-frame re-check (v7).** While a track exists the search stays near it, which keeps a wrong
  track wrong. Every 2 s of video the whole frame is searched too, and a placement away from the
  track replaces the windowed one only when it scores higher by more than the ambiguity margin
  and would start a track on its own: at least `visual-cursor-start-score`, an appearance, and no
  other placement within the margin. Otherwise the windowed result stands, so the check can never
  turn a kept continuation into a miss. The cadence comes from the sampled interval, so it is 2 s
  of video rather than a fixed frame count.
- The evidence map names the admitting rule in `evidence.rule` (`changed`, `arrival` or `held`)
  and, for a rejected sample, the cause in `evidence.reason`. `evidence.windowed` marks a search
  kept near the track, `evidence.rechecked` a sample that also ran the whole-frame search, and
  `evidence.takeover` a re-check that moved the track.

Supported cases are defined by geometry: at every sub-pixel phase, the displayed cursor and the
next smaller calibration scale must render at least 12 fully covered analysis pixels.

### Observation reads

`GET /sources/{id}/analyses/{analysisId}/observations` pages one immutable published artifact. A
`CURSOR_VISIBILITY` item carries a bounded `evidence` object so a read can say *why* a sample was
accepted, held or rejected. Only these keys are ever passed through, in this order: `rule`,
`reason`, `continuation`, `windowed`, `rechecked`, `takeover`, `pass`, `score`, `runnerUpScore`,
`sourceScale`, `centreDelta`, `meanDelta`, `windowMaxDelta`, `forwardReason`, `missedSamples`. The projection happens while streaming the
artifact, so an unlisted key is skipped without being materialized, and other observation kinds
keep their evidence internal. `scripts/sc2150/explain_cursor_track.py` prints this projection per
sample.

**Cuts: `scene-diff/v3`.** A sampled pair is a cut when its mean luma difference is at least
`cut-min-delta`, or at least `cut-spike-min-delta` and `cut-spike-ratio` times every other pair
difference within ±5 samples. That catches an isolated edit, while duplicated-frame stutter
during camera motion stays below the rule.

| Key | Default | Origin |
|---|---|---|
| `visual-sample-max-width` | 480 | unchanged; 960 was measured worse and about 16× slower |
| `visual-cursor-min-score` / `-start-score` | 0.85 / 0.88 | FA12 development splits |
| `visual-cursor-min-gain` / `-max-gain` | 0.35 / 1.6 | FA12 v3 development split |
| `visual-cursor-min-contrast` | 12 (grey levels, s.d.) | initial |
| `visual-cursor-ambiguity-margin` | 0.05 | initial |
| `visual-cursor-static-max-delta` | 6 (+ noise floor, capped at 10) | initial |
| `cut-spike-min-delta` / `cut-spike-ratio` | 0.04 / 4 | FA12 v3 development split |

Every key above is part of the analysis config digest. **FA12 is not qualified.** Status and
evidence are in `docs/builderflow/sc-2150/verification-runs/20261022T022007Z-fa12-synthetic/`
(v2, which failed held-out), `.../20261022T103825Z-fa12-v3/` (v3), `.../20261023T013000Z-fa12-v4/`
(v4, dev recall 88.8 %) and `.../20261023T163000Z-fa12-v5/` (v5, dev recall 92.95 %). The recall
gate is 99 %, so every version still fails development and held-out stays unspent. Keep cursor
results inspection-only.

## Configuration (`chromascape.mouse-video.*`)

| Key | Default |
|---|---|
| `enabled` | `false` |
| `youtube-enabled` | `false` |
| `directory` | `data/mouse-video` |
| `python` / `ffprobe` / `ffmpeg` | `python3` / `ffprobe` / `ffmpeg` |
| `youtube-downloader` / `youtube-downloader-sha256` | empty (unavailable) |
| `write-nonce-secret` / `write-nonce-ttl-seconds` | ephemeral / `600` |
| `max-upload-bytes` | 512 MiB |
| `max-duration-seconds` | 1200 |
| `max-width` / `max-height` | 3840 / 2160 |
| `max-fps` | 120 |
| `max-decode-frames` | 150000 |
| `max-source-disk-bytes` | 4 GiB |
| `proxy-max-height` | 720 |
| `orphan-grace-seconds` | 86400 |
| `probe-budget-seconds` | 30 |
| `lease-expiry-seconds` | 30 |
| `waiting-slots` | 8 |

Override with environment variables using relaxed binding, e.g.
`CHROMASCAPE_MOUSE_VIDEO_ENABLED=true`. Both feature flags stay disabled until the corrected
increment passes its safety gates.

## Persistence

Additive migrations create `mouse_video_sources`, `mouse_video_jobs`, `mouse_video_job_events`,
`mouse_video_analyses`, `mouse_video_artifact_refs` and `mouse_video_review_revisions` (V42), plus
owner budgets, intake reservations, export snapshots, job row versioning, original qualification and
export-specific artifact references (V43). V42 is never rewritten. Media bytes live in a
content-addressed filesystem byte store; SQL rows register verified publication. Capability flags
are constrained `false` in SQL.

## Safety

- Bytes stream through a bounded digest with a hard cap; publication uses hard-link or
  fsync+atomic no-replace move, never an in-progress final pathname.
- The probe runs a frozen, hash-verified `probe.py` with `ffprobe`, a file-only protocol whitelist,
  bounded probe size, bounded output buffers and a wall-clock timeout; owned process descendants
  are always cleaned up and cleanup is confirmed.
- Frame-index extraction drains bounded output into a capped buffer and terminates on overflow.
- YouTube links are canonicalized to a validated provider ID; acquisition requires a pinned
  downloader hash, fixed flags, no cookies/config/exec, and explicit provider failure outcomes.

## Rollback

Disable both `enabled` and `youtube-enabled`, stop/reconcile only owned video jobs, and retain
original media/manifests under access controls. The additive tables and byte objects are safe to
leave in place; do not drop byte objects referenced by other owners. Resolve cleanup ambiguity
before releasing a job slot or retrying.

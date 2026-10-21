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
| `GET /observability` | Bounded counters and owned-worker count (no media or credentials) |
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

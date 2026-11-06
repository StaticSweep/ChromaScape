---
schema_version: 1
offline_record_id: 7525d302-dadc-46fc-b850-eb09d64e6ea9
sync_status: synced
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: 2150
shortcut_tag: shortcut:sc-2150
felix_task_id: null
operation: create_note
target_note_id: null
title: SC-2150 API Contract and Runtime Bounds
note_type: documentation
stable_tags:
- shortcut:sc-2150
- sc2150:api-contract
parent_id: null
task_selector:
  stable_tag: shortcut:sc-2150
  relevant_projects:
  - C:\Dev\IdeaProjects\ChromaScape
  - C:\Dev\IdeaProjects\micro6
entity_links:
- entity_type: shortcut_story
  entity_id: '2150'
  link_strength: primary
created_at: '2026-10-19T03:59:16.091364Z'
intended_felix_task_state: planning
intended_felix_spec_state: spec_in_progress
intended_shortcut_transition: null
intended_shortcut_marker: null
---

## Task resolution

Workflow: shortcut_delivery. Classification: change:ui (exactly one). Source: https://app.shortcut.com/leeuwbot/story/2150. User requested full plan only. Search stable tag across both recorded roots; never duplicate. Complete scope/exclusions and linked rules appear in the authoritative plan payload. Exact task creation payload:

```json
{
  "action": "create",
  "project": "C:\\Dev\\IdeaProjects\\ChromaScape",
  "title": "SC-2150 External video upload, YouTube import and human-behavior analysis",
  "description": "Plan and deliver bounded uploaded-video and supported YouTube import into Mouse Research, with immutable media/provenance, durable idempotent jobs, bounded probe/decode/cursor analysis, source-linked estimates and unknowns, append-only review, catalog inspection and evidence export. SC-2151 owns exact video-to-tick alignment; SC-309 owns movement diagnosis; SC-712 owns published training admission. This request authorizes planning only: retain planning/spec_in_progress, all acceptance/implementation/test items unchecked and no spec_ready or implementation-start marker. Full authoritative scope, rules, architecture, ERD, API, security, UX, observability, acceptance, implementation and test checklists are in the linked documentation notes. No Micro6 source/runtime, model activation or gameplay changes.",
  "workflow": "shortcut_delivery",
  "task_type": "feature",
  "task_status": "planning",
  "spec_state": "spec_in_progress",
  "stable_tags": [
    "shortcut:sc-2150",
    "change:ui"
  ],
  "entity_links": [
    {
      "entity_type": "shortcut_story",
      "entity_id": "2150",
      "entity_name": "SC-2150",
      "link_strength": "primary"
    },
    {
      "entity_type": "file",
      "entity_id": "C:\\Dev\\IdeaProjects\\ChromaScape\\docs\\builderflow\\sc-2150\\full-plan.md",
      "entity_name": "SC-2150 full delivery plan",
      "link_strength": "reference"
    }
  ]
}
```

## Note payload

## API Contract


Proposed prefix `/api/debug/mouse-videos`; validate unknown fields, ownership and bounded inputs. Reuse existing local replay principal infrastructure where suitable, verified by dedicated tests. State changes require authenticated server-established actor and existing CSRF policy; caller audit headers are labels, not authentication.

| Method/route | Request | Result |
|---|---|---|
| GET `/capabilities` | none | Available tools/formats/limits/methods; no private paths |
| POST `/imports/upload` | multipart file, request UUID, declared source/context/use metadata | 202 sourceId/jobId/statusUrl; bytes streamed to owned staging |
| POST `/imports/youtube` | request UUID, supported URL, declared source/use metadata | 202 source/job; download occurs only in worker |
| GET `/sources` | cursor, limit 1–25, source/status/context filters | Paginated external catalog; opaque source-kind adapter for SC-303 |
| GET `/sources/{id}` | owner access | Source/probe/analysis/review status and capability reasons |
| GET `/sources/{id}/media/{role}` | controlled media role, HTTP Range | Authorized 200/206, 416 invalid range; no filesystem URL |
| POST `/sources/{id}/analyses` | UUID, crop/scale/cursor-pack and bounded versioned config | 202 analysis job; identical retry returns same identity |
| GET `/jobs/{id}` | owner access | Stage/status/progress, typed error, attempt and cleanup state |
| POST `/jobs/{id}/cancel` | UUID, expected revision | 202 cancelling or 200 settled; no false cancellation success |
| GET `/analyses/{id}/observations` | time range/page cursor/limit ≤500 | Bounded points/events, gap/coverage and exact source refs |
| POST `/analyses/{id}/reviews` | UUID, expected revision, corrections/reason/use decision | 201 new review; old predictions preserved |
| GET `/sources/{id}/export` | analysis and review identity | Exact authorized manifest/refs; checksums and eligibility reasons |

Errors use `{code,message,retryable,jobId,sourceId,details}` with safe details: 400 invalid URL/schema/range/config; 401 unauthenticated; 403 unauthorized operation; 404 inaccessible/missing source; 409 idempotency/revision conflict; 413 byte limit; 415 unsupported container/codec; 422 undecodable/missing video/unsupported protected/live source; 429 queue/storage quota; 503 configured tool/provider unavailable. Asynchronous provider/worker errors are persisted with equivalent stable codes (`SOURCE_UNAVAILABLE`, `SOURCE_AUTH_REQUIRED`, `SOURCE_RATE_LIMITED`, `MEDIA_LIMIT`, `CURSOR_UNSUPPORTED`, `NO_VISIBLE_CURSOR`, `DECODER_FAILED`, `CHECKSUM_MISMATCH`, `WORKER_TIMEOUT`, `INTERRUPTED`, `CLEANUP_UNCONFIRMED`). A valid video with no visible cursor remains inspectable with unavailable tracking, rather than a false failed import or fabricated points.


## Runtime bounds and errors


Initial reviewed defaults (configurable only by server operator and recorded per attempt): upload/download 512 MiB; decoded duration ≤20 minutes; ≤3840×2160 and ≤120 fps; decode at most 150,000 frames; ≤60 fps cursor analysis sampling with exact selected PTS; one active video worker within the research admission boundary, eight waiting slots; source/job disk quota 4 GiB; 30-second network connect/read limits, 10-minute acquire budget, 30-second probe budget, 20-minute decode/analysis budget, 5-second child shutdown budget. Account for transient decoder frame buffers; process RSS bound 2 GiB and CPU/process limits enforced by the worker launcher. Limit violations retain reasons and cannot publish a “complete” analysis. No unbounded extracted-frame directory. User-facing limits are shown before import; defaults must be tested on supported hosts before release.

States: `QUEUED → RUNNING → SUCCEEDED|FAILED|TIMED_OUT|CANCELLING`; `CANCELLING → CANCELLED|NEEDS_RECONCILIATION`. Stage is independent of status. Lease heartbeat every 5 seconds, expiry 30 seconds, DB clock for ownership; generation and expected revision guard progress/publication. No external video work begins from a GET.

On restart/lease loss, preserve source bytes and stage evidence; do not assume old process success. Recover a fully verified publication using manifest and fenced DB reconciliation; otherwise settle interrupted attempt and offer an explicit new bounded attempt. No blind reacquisition/analysis on startup. Late workers cannot publish after cancellation or generation change. Confirm only owned process descendants stopped; if cleanup cannot be proved, mark reconciliation required and hold its worker slot until resolved. Retries consume a new attempt budget and never overwrite prior evidence.



## Checklist reconciliation

All items are unchecked; there is no implementation/test completion evidence. Preserve unrelated existing items. Authoritative checklist payload:

```json
[
  {
    "name": "Scope Definition",
    "items": [
      {
        "text": "Include bounded upload/YouTube import, immutable media, durable bounded analysis, visible cursor evidence, manual review, catalog inspection and export within ChromaScape.",
        "checked": false
      },
      {
        "text": "Exclude live input, Micro6 changes, exact tick alignment, physical-click inference, causal simulation, detector verdicts and model activation.",
        "checked": false
      }
    ]
  },
  {
    "name": "Acceptance Criteria",
    "items": [
      {
        "text": "AC1 Given a valid bounded upload, when submitted, then one immutable source/original hash and playable proxy/index become inspectable.",
        "checked": false
      },
      {
        "text": "AC2 Given a supported accessible YouTube video, when imported, then the canonical provider ID/acquisition metadata and exact downloaded digest are retained; unavailable/protected/live sources fail explicitly.",
        "checked": false
      },
      {
        "text": "AC3 Given identical request UUID/body, when concurrently retried, then one source/job exists; conflicting content receives 409 without partial publication.",
        "checked": false
      },
      {
        "text": "AC4 Given malformed/oversized/overlong/pathological media, when processed, then bounded failure occurs with no unbounded memory/disk/CPU or complete-artifact claim.",
        "checked": false
      },
      {
        "text": "AC5 Given a qualified visible cursor clip and pinned tools/config/templates, when analyzed, then source-linked tracks/metrics meet the declared held-out threshold and repeated outputs have the same normalized hash.",
        "checked": false
      },
      {
        "text": "AC6 Given hidden/unsupported cursors, overlays, crop/resize, cuts or frame-rate variation, when analyzed, then gaps and feature eligibility are accurate and unsupported observations remain unknown.",
        "checked": false
      },
      {
        "text": "AC7 Given manual point/event corrections, when saved, then the original video/predictions remain immutable and the new reviewer/reason/base-revision lineage is resolvable.",
        "checked": false
      },
      {
        "text": "AC8 Given imported footage, when training is requested, then no import/source declaration alone grants human or training eligibility; published curation and independent policy gates are required.",
        "checked": false
      },
      {
        "text": "AC9 Given cancel/restart/lease loss at each stage, when reconciled, then no late/duplicate publication occurs, source evidence is preserved and cleanup/outcome is truthful.",
        "checked": false
      },
      {
        "text": "AC10 Given another owner or unauthenticated caller, when requesting media/status/review/export, then access is denied without private metadata disclosure.",
        "checked": false
      },
      {
        "text": "AC11 Given no native telemetry/alignment, when inspecting/exporting, then PTS/frame evidence is available but native ticks/physical clicks/hidden state are not invented.",
        "checked": false
      },
      {
        "text": "AC12 Given catalog filters, refresh, keyboard navigation and error states, when used, then source/status/coverage are clear and existing native mouse capture/inspection still works.",
        "checked": false
      },
      {
        "text": "AC13 Given browser/worker differing representations, when seeking/exporting, then original/proxy time mapping and hashes identify the actual frame evidence, within one source-frame interval on qualified ranges.",
        "checked": false
      },
      {
        "text": "AC14 Given a bad-movement or human-origin claim, when shown, then it is supported by a separate reviewed source/report identity; tracker confidence never substitutes for detector calibration or human verification.",
        "checked": false
      }
    ]
  },
  {
    "name": "Implementation Checklist",
    "items": [
      {
        "text": "I1 Inventory scoped changes/base and prerequisites; read nested rules; reserve the migration version on the actual target base.",
        "checked": false
      },
      {
        "text": "I2 Freeze v1 schemas for import, source, evidence, job/error, review and SC-2151 handoff; add golden manifests.",
        "checked": false
      },
      {
        "text": "I3 Add PostgreSQL source/job/analysis/review/ref/event schema, unique constraints, fenced transitions and migration tests.",
        "checked": false
      },
      {
        "text": "I4 Extract/reuse bounded streaming artifact bytes with checksum/publication/recovery; keep replay adapters working.",
        "checked": false
      },
      {
        "text": "I5 Add typed video handlers to research runtime with limits/leases/cancellation and no silent startup rerun.",
        "checked": false
      },
      {
        "text": "I6 Implement authenticated streaming upload and source/use metadata with UUID/body idempotency.",
        "checked": false
      },
      {
        "text": "I7 Implement pinned YouTube adapter, canonicalization and constrained acquisition environment; persist truthful provider outcomes.",
        "checked": false
      },
      {
        "text": "I8 Implement probe/proxy/thumbnail/frame PTS index; verify original/proxy mapping and bounded temporary storage.",
        "checked": false
      },
      {
        "text": "I9 Add reviewed cursor-template pack provenance and tracker, quality/gap report and descriptive feature/annotation capability manifest.",
        "checked": false
      },
      {
        "text": "I10 Add append-only manual corrections/source review and export adapter; training defaults remain false.",
        "checked": false
      },
      {
        "text": "I11 Add external-source catalog adapter and video inspector/API/media Range handling with ownership.",
        "checked": false
      },
      {
        "text": "I12 Add accessible import/progress/cancel/inspection/review UX and explicit uncertainty/error states.",
        "checked": false
      },
      {
        "text": "I13 Add observability, reference-aware cleanup, operator configuration/tool lock/setup and rollback runbook.",
        "checked": false
      },
      {
        "text": "I14 Run focused contracts, real PostgreSQL, worker media tests and native regression; fix scope-related failures.",
        "checked": false
      },
      {
        "text": "I15 Run browser and supported-host public-source smoke with exact tool hashes; archive results and all unrun gates.",
        "checked": false
      },
      {
        "text": "I16 Link Implementation Evidence with changed files/tests/remaining work; check only proved items before in_review. Link Verification Evidence with acceptance/tests/risks/rollback before done. Synchronize only successful Felix states using repository mapping.",
        "checked": false
      }
    ]
  },
  {
    "name": "Test Verification",
    "items": [
      {
        "text": "T1 Contract: valid/invalid/unknown fields, stable typed errors, rational PTS and canonical manifest hashes.",
        "checked": false
      },
      {
        "text": "T2 PostgreSQL: clean migration plus upgrade from actual base, uniqueness, concurrent retry/conflict, fenced publication, revision races and immutable history.",
        "checked": false
      },
      {
        "text": "T3 Artifact: streaming byte limits without full-file allocation, digest mismatch, simultaneous reuse, failure between move/DB bind, orphan/ref-aware cleanup and denied traversal/symlink.",
        "checked": false
      },
      {
        "text": "T4 Upload: real small valid media, truncated/no-video/unsupported codec, false extension, quota/rate limit, disconnect and Range 200/206/416.",
        "checked": false
      },
      {
        "text": "T5 Provider: fake adapter for deterministic success/removal/auth/rate limit/redirect/download overflow; real accessible controlled public YouTube video on release host, exact version/log proof. Never make network availability a passing mocked live test.",
        "checked": false
      },
      {
        "text": "T6 Isolation: arbitrary URL/host/port/userinfo, private IP/redirect/media protocol and config/plugin/cookie/exec injection refusal; no decoder egress or secret reads.",
        "checked": false
      },
      {
        "text": "T7 Real-tool media corpus: fixed and variable FPS, dropped/duplicate frames, rotation/crop/resize, cuts, speed changes and proxy mapping. Synthetic fixture results are labeled synthetic.",
        "checked": false
      },
      {
        "text": "T8 Tracking: manually annotated owned OSRS clips with visible standard/OSRS cursor, menu/overlay, occlusion and unsupported styles. Split whole source videos before tuning. On declared supported held-out visible spans target median error \u22644 source pixels (at 1080p-equivalent scale), p95 \u226412 px and \u226590% visible-frame recall; on explicitly absent spans false detections \u22641%. Report counts/CIs and domain-specific failures; unavailable data means unverified, not relaxed thresholds. These qualify tracking only, not detector or engine quality.",
        "checked": false
      },
      {
        "text": "T9 Repeatability: identical tool/config/media on same supported build produces same normalized observations/manifests; cross-platform tool differences get distinct toolchain identities and no false byte-identical claim.",
        "checked": false
      },
      {
        "text": "T10 Review: correction precedence, revision conflict/idempotency, reviewer authority, raw immutability, source-use/curation rejection and export lineage.",
        "checked": false
      },
      {
        "text": "T11 Lifecycle: cancel/timeout/restart/lease theft before/during each stage/publication; owned process cleanup and slot release, late-worker refusal and reconciliation-only ambiguity.",
        "checked": false
      },
      {
        "text": "T12 API ownership: anonymous, foreign owner, forged audit header, authorized owner, private logs/title escaping, media/export access.",
        "checked": false
      },
      {
        "text": "T13 JS/browser: import fields/limits, empty/progress/error/no-cursor/gap states, durable refresh, inspect/overlay/seek/review/export, keyboard and narrow viewport.",
        "checked": false
      },
      {
        "text": "T14 Regression: existing MouseResearchService/worker/controller, MouseRecordingInspection, catalog JS, campaign/training/capture and replay artifact tests, plus Spotless/Checkstyle and scoped diff check.",
        "checked": false
      },
      {
        "text": "T15 Supported-host smoke: upload and public-source import, inspect/correct/export, cancel mid-job, restart recovery and deny cross-owner media; record native commands and actual binary hashes. No Micro6 build/login required.",
        "checked": false
      }
    ]
  }
]
```

## State reconciliation

Resolve/reuse task; attach rules and all complete plan/design notes with offline-sync:7525d302-dadc-46fc-b850-eb09d64e6ea9. Create/update full checklists; validate shortcut_intake and delivery_spec without advancing status. Retain planning/spec_in_progress for this planning-only request. No spec_ready, in_progress, in_review or done transition is intended. Missing gate artifacts must be supplied, never waived.

## Shortcut reconciliation

Keep existing Backlog state. No Felix-state marker is eligible. For task-reconciliation record only, post once after checking existing comments for [sc-2150:full-plan:20261019]; payload:

No Shortcut mutation for this note; see task-reconciliation record.

Remaining blockers: Felix unavailable; duplicate-task tag audit is incomplete (ChromaScape search returned no matches; micro6 search timed out). All software implementation and verification are future work.

## Sync result

Pending. No Felix task/note/rule/checklist write succeeded or was attempted. Two configured calls returned 300-second transport timeouts. Other pending read-only requests were cancelled.


## Sync result (replayed 2026-10-19T04:38:52Z)

Synced. Felix task task_1792382767281_cd8dbu69 created in workflow shortcut_delivery under C:\Dev\IdeaProjects\ChromaScape with tags shortcut:sc-2150, change:ui and offline-sync:d824c1a2-03e0-4a7e-9ec8-b5a96efed667. Design notes (Architecture, ERD, API Contract, Observability, UX Mockup, Security Decision Record), the sc2150:delivery-rule constraint, the Scope Definition / Acceptance Criteria / Implementation Checklist / Test Verification checklists, and the Implementation + Verification Evidence notes were written. Task advanced planning -> spec_ready -> in_progress. Shortcut story 2150 moved Backlog -> In Progress with markers [felix-sync:task_1792382767281_cd8dbu69:planned] and [:started] (comment 2154). No in_review/done transition is claimed: I7/I8/I9/I10/I13/I15 remain outstanding.

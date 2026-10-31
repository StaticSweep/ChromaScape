---
schema_version: 1
offline_record_id: 6b422d7e-24e3-4675-9a62-6ec9dfc73d21
sync_status: synced
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: sc-2150
shortcut_tag: shortcut:sc-2150
felix_task_id: task_1792382767281_cd8dbu69
operation: create_note
target_note_id: null
felix_note_id: note_1792566515662_c449eeb6
synced_at: 2026-10-21T07:08:55.519890Z
title: "SC-2150 analysis, exact previews, visual producer and restricted launcher evidence"
note_type: documentation
stable_tags:
  - shortcut:sc-2150
  - sc2150
  - implementation-evidence
parent_id: null
entity_links:
  - entity_type: task
    entity_id: task_1792382767281_cd8dbu69
  - entity_type: shortcut_story
    entity_id: sc-2150
created_at: 2026-10-21T03:21:59Z
intended_felix_task_state: in_progress
intended_shortcut_transition: null
---

## Task resolution

Reuse `task_1792382767281_cd8dbu69` (workflow `shortcut_delivery`, source `shortcut_story`
`sc-2150`, tag `shortcut:sc-2150`, classification `change:ui`). No duplicate task was created.
The two Felix transport calls made on 2026-10-21 timed out (`-32001`), so this note is an outbox
record. Do not claim the note, checklist, workflow state or Shortcut state was written.

## Note payload

# SC-2150 — Analysis, exact previews and visual producer evidence

Date: 2026-10-21.
Project: C:\Dev\IdeaProjects\ChromaScape.
Felix task: task_1792382767281_cd8dbu69.
Scope: durable analysis/preview scheduling, exact-frame previews, visual observation producer,
restricted launcher, and the review-panel increment. This is implementation evidence, not a claim
that every SC-2150 acceptance or qualification gate passed.

## Changed files

New:
- `src/main/resources/db/migration/V45__sc2150_analysis_preview_scheduling.sql`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/entity/MouseVideoFramePreview.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/entity/MouseVideoFramePreviewRepository.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoSchedulingService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoPreviewService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoPreviewWorker.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoPreviewFinalizationService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoAnalysisWorker.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoVisualAnalyzer.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoScheduledJobRunner.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoRestrictedLauncher.java`
- `src/test/java/.../service/MouseVideoSchedulingServiceTest.java`
- `src/test/java/.../service/MouseVideoPreviewWorkerTest.java`
- `src/test/java/.../service/MouseVideoVisualAnalyzerTest.java`
- `src/test/java/.../service/MouseVideoRestrictedLauncherTest.java`

Modified:
- `MouseVideoJob` (ANALYSIS/PREVIEW binding, PREVIEW stage)
- `MouseVideoAnalysis` (reopen/fail for a retried identity)
- `MouseVideoJobRepository` (kind/preview/analysis queries)
- `MouseVideoArtifactRefRepository` (reuse an existing frozen index)
- `MouseVideoImportService` (import poller no longer claims ANALYSIS/PREVIEW jobs)
- `MouseVideoProcessRunner` (systemd resource properties; optional bwrap wrapper)
- `MouseVideoOrphanSweeper` (preview checksums are live references)
- `MouseVideoCatalogService` (truthful methods/features/limits)
- `MouseVideoController` (analysis/preview scheduling, preview read/media, sandbox readiness)
- `MouseVideoExportService` (pins analysis artifacts and READY previews)
- `mouse-video.html`, `mouse-video.js`, `mouse-research.css` (exact preview, point/region
  correction, prediction/reviewed overlays, pending/failed states, keyboard and narrow layout)
- Tests updated for V45 and the new controller dependencies.

## What is implemented

1. **Durable scheduling.** POST analysis and POST frame-preview create durable work before
   dispatch. Request identity is compared before any side effect; equivalent content coalesces on
   one analysis/preview row and one active job. V45 adds job bindings, the PREVIEW kind/stage and
   the preview identity table.
2. **Exact previews.** The ordinal is resolved against the frozen frame-index checksum, then ffmpeg
   selects `eq(n,ordinal)`. The preview row pins index checksum, ordinal, PTS, timebase, method,
   output bounds, source/output dimensions, rotation and transform metadata. GET reads only; it
   cannot start a decoder. READY bytes are content-addressed and protected from the sweeper.
3. **Visual producer.** A bounded, deterministic analyzer samples source frames at <=60 fps (frame
   cap 3,600), derives pause, cut, regional camera/activity and visible action-indicator candidates
   with source ordinals/PTS and confidence, and reports cursor/target/physical/native capabilities
   as explicit unknowns when unsupported. OBSERVATIONS, QUALITY and MANIFEST are published
   atomically with the analysis.
4. **Restricted launcher.** When
   `chromascape.mouse-video.restricted-launcher=true`, every decoder/analysis child runs under
   bwrap (`--unshare-all`, cleared environment, read-only runtime, writable owned root, private
   tmpfs) inside a systemd cgroup (`MemoryMax`, `CPUQuota`, `TasksMax`). Missing facilities fail
   closed with a truthful readiness state.
5. **Review increment.** The panel requests an exact preview for the selected ordinal, never draws
   an older image over a newer selection, shows pending/failed/ready states, supports source-pixel
   point and region selection, renders original predictions separately from reviewed overlays, and
   keeps drafts through conflicts.

## Tests actually run

Java (isolated build, JDK 17, offline):

```
JAVA_HOME=/home/ikiddoi/.jdk/jdk-17.0.20.1+1 ./gradlew test --offline \
  -Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json \
  --tests "com.chromascape.web.debug.tickdebugger.video.*"
```

Result: **200 tests, 0 failures, 0 errors, 16 skipped**. All 16 skips are the existing
Testcontainers classes (`MouseVideoMigrationTest`, `MouseVideoPostgresConcurrencyTest`,
`MouseVideoServicePostgresRaceTest`): Testcontainers reported Docker unavailable to that test JVM.
Those PostgreSQL gates are **not** claimed as run.

JavaScript:

```
node --test src/test/js/mouse-video.test.js
```

Result: **13 tests, 0 failures.**

V45 real-PostgreSQL check (manual, against `chromascape-postgres` on 5433): applied V42, V43, V44
and V45 in order to a fresh `sc2150_v45_test` database. V45 created
`mouse_video_frame_previews`; a `PREVIEW`/`PREVIEW` job row with analysis and preview bindings
inserted successfully; a second preview with the same
`(analysis_id, index_checksum, frame_ordinal, method, max_width, max_height)` was rejected by
`uq_mouse_video_frame_previews_identity`.

Booted-server end-to-end (loopback 18090, disposable database `chromascape_sc2150`, media root
`/tmp/sc2150-media`, `local-development` profile):

- upload -> import `SUCCEEDED`, original qualified, frozen index 90 frames;
- POST analysis -> durable `ANALYSIS` job `SUCCEEDED`, analysis `visual/v1` COMPLETE with
  OBSERVATIONS/QUALITY/MANIFEST;
- observations read `available=true` with an explicit cursor `UNSUPPORTED` observation;
- POST frame-preview -> `PENDING` -> `READY`; media returned `image/png` with a valid PNG signature;
- an identical second preview body under a new request id returned the same `previewId` and
  `READY`;
- review `corrections/v1` with visible point, target region, pause and cut -> 201;
- export -> 201, downloaded manifest contains pinned `previews`.
  Script output ended `E2E PASS`.

Access smoke:
- no `local-development` profile: `GET /write-nonce` -> **401** `UNAUTHENTICATED`.
- independent owner instance on 18091 with `-Duser.name=owner-b`: known source -> **404**,
  catalog -> empty.

Restricted launcher:
- `GET /sandbox` on 18092 -> `required=true`, `available=true`, `networkNamespaceIsolated=true`,
  `cpuQuotaPercent=200`, `tasksMax=64`, `memoryMaxBytes=2147483648`;
- upload/import on that instance -> job `SUCCEEDED`, so ffprobe/ffmpeg ran under bwrap;
- `MouseVideoRestrictedLauncherTest` proved a direct TCP attempt is isolated and the wrapper
  contains no shell.

Browser (Playwright Chromium, headless, 1440x1000):
- `/mouse/video` loaded with status `Ready.`, 4 catalog rows, 90 frozen frames;
- selecting frame 3 showed the exact preview (frame 3, pts 1536, timebase 1/15360);
- clicking the image produced a source point (128, 108);
- `CURSOR_VISIBILITY` then exposed `VISIBLE`, and the preview identity was displayed;
- **0 page/console errors**; at 720px the page had **no horizontal overflow**.
- Screenshot: `/tmp/sc2150-browser.png` (temporary, not committed).

Formatting: repository-wide `spotlessJavaCheck` still fails on unrelated pre-existing files (29+
files, e.g. `CandidateExecutionClosureV1`). All files under the video tree pass
`google-java-format 1.17.0 --dry-run --set-exit-if-changed`. `checkstyleMain` completes with
warnings only (pre-existing repository-wide count).

## Acceptance status

| Gate | Status | Note |
|---|---|---|
| RA1 isolated harness | PASS (this run) | Disposable DB/media/ports, 8090 untouched |
| RA2 launcher boundaries | PARTIAL | bwrap/systemd limits and network isolation observed; hostile CPU/PID/disk/output attack matrix not run |
| RA3 request identity/coalescing | PARTIAL | Identity/conflict/coalescing unit + e2e; cross-instance PostgreSQL races not run |
| RA4 exact previews | PARTIAL | Ordinal/PTS pinning, coalescing, media, no-work GET and e2e pass; crash/GC and VFR/rotation matrices not run |
| RA5 review conflicts/immutability | PARTIAL | corrections/v1 e2e and export pinning; browser conflict/history/export interaction not exercised |
| RA6 visual observations | PARTIAL | Synthetic golden + durable e2e; cursor template absent, held-out accuracy not run |
| RA7 access/lifecycle | PARTIAL | 401/404 proven; cancellation/crash/expiry/quota/competing-instance matrix outstanding |
| RA8 held-out accuracy | NOT RUN | Requires rights-approved templates and annotated clips |
| RA9 YouTube | NOT RUN | Requires approved pins, public clip and constrained egress |
| RA10 final delivery | NOT RUN | Full regressions, rollback rehearsal and Windows containment outstanding |

## Remaining work

1. Run the Testcontainers PostgreSQL classes with a working Docker strategy and add cross-instance
   scheduling/finalization races (RT2).
2. Exercise cancellation at each stage, crash before/after byte publication, lease expiry, sweeper
   grace/shared hashes, quota exhaustion and two-instance admission (RT6).
3. Add browser coverage for 409 conflict/reload, history navigation, keyboard-only operation,
   source-use and download, plus axe checks (RT4).
4. Add VFR, repeated PTS, rotation/crop, shared-byte GC and failure-publication cases for previews
   (RT3).
5. Enforce a real per-job disk quota; the current volume bound is configuration/readiness only.
6. Obtain approved source-disjoint clips/templates for cursor/action/pause/region/camera accuracy
   (RT7) and approved downloader/public clip/egress for YouTube (RT8).
7. Run the full native/video/JS/Python/browser/style matrix and the operator/rollback rehearsal
   (RT9); Windows containment remains a separate required-host decision.

## Risks

- The visual producer is deterministic and bounded, but its thresholds have not been tuned or
  validated on approved held-out data. Cursor tracking is unavailable without a reviewed template.
- Preview extraction decodes from the start of the stream by ordinal; it is exact but can be
  expensive on long clips. It is bounded by the analysis wall clock, not by a decode-frame counter.
- Admission is advisory (count plus database uniqueness); a race can admit one extra job before a
  slot frees.
- The restricted launcher is opt-in. Operators must set `restricted-launcher=true` and verify the
  `/sandbox` readiness state.


## Checklist reconciliation

No checklist state is changed by this record. The implementation items RI0-RI5 are implemented but
not fully qualified; RI6-RI9 are outstanding. Acceptance items RA2-RA7 are partial; RA8-RA10 are
not run. Test items RT0 was exercised; RT1-RT9 are not fully run. Prior checked states are
preserved.

## State reconciliation

Intended order when Felix recovers:
1. Search `shortcut:sc-2150` and reuse `task_1792382767281_cd8dbu69`.
2. Create this documentation note with tag `offline-sync:6b422d7e-24e3-4675-9a62-6ec9dfc73d21`.
3. Link it to the task and the Shortcut source; keep `in_progress` / draft.
4. Do not check RI/RA/RT items that the evidence marks partial or not run.

## Shortcut reconciliation

No Shortcut transition or comment is intended. No `[felix-sync:...]` marker may be posted because
the Felix note and state were not written.

## Sync result

Synced at 2026-10-21T07:08:55.519890Z. Created and read-verified Felix note note_1792566515662_c449eeb6 with offline-sync:6b422d7e-24e3-4675-9a62-6ec9dfc73d21 under existing task task_1792382767281_cd8dbu69. Canonical Shortcut entity link normalized to numeric story ID 2150 from the recorded sc-2150 label; original payload retained. No checklist or state mutation was requested or performed. Shortcut state/comment result: unchanged by this replay; no transition or marker intended.

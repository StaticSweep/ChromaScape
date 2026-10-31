# SC-2150 — Independent analysis/preview/visual implementation review

Date: 2026-10-21. Project: C:\Dev\IdeaProjects\ChromaScape.
Task: task_1792382767281_cd8dbu69, in_progress / draft.

## Acceptance results

Not ready for acceptance. The new routes/workers and reported smoke evidence establish implementation progress, but this review reproduced five functional defects plus a broken PostgreSQL test fixture. R1–R5 must not be described as fully complete/qualified.

### NR1 — P1: sandbox makes all media writable to each worker

Location: src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoRestrictedLauncher.java:96–100.

wrap() bind-mounts properties.rootPath() read-write and sets it as HOME/current directory. That is the whole video store, including originals and other owners' job data, not one execution's workspace. The wrapper hides unrelated host paths/network but does not protect sibling sources or immutable bytes.

Real bwrap probe: created a fake sibling original under a disposable root, launched a sandboxed child invoking truncate, then asserted the original stayed intact. It was truncated to an empty file. No operational data was touched.

Fix: pass an explicit per-execution context; mount only that job's writable scratch/output and its exact approved inputs/templates/runtime read-only. Keep shared byte-store publication in the trusted parent. Add sibling read/write and original-integrity attack regressions. Real per-job disk quotas remain an additional unimplemented requirement.

### NR2 — P1: raced scheduling returns the winner without validating the requested body

Locations: MouseVideoSchedulingService.java:114–115, 233–250 and 357–369.

The DataIntegrityViolationException recovery branch accepts any job found by owner/requestId without comparing the expected digest, kind or requested source/analysis. Preview recovery checks source but not the expected preview digest. Analysis recovery can alternatively choose an arbitrary completed visual analysis for the source rather than the requested identity.

Probe: the initial request lookup found no row; an insert then lost a simulated database uniqueness race; recovery found the winner for another source/body under the same owner/requestId. Instead of CONFLICT, it returned that winner's receipt.

This probe uses actual scheduling/recovery logic with a mocked repository uniqueness failure, not a claimed real-PostgreSQL two-request race.

Fix: carry the full normalized expected identity into recovery, distinguish request/content conflicts, validate the winning result completely and return 409 for a changed body. Persist identities for coalesced request aliases as well; returning an existing result without recording the new request ID does not provide durable changed-body retry protection. Add barrier-controlled real-PostgreSQL request/content race coverage.

### NR3 — P1: admission check does not enforce the owner limit atomically

Location: MouseVideoSchedulingService.java:372–378.

Counting jobs and inserting later under no shared budget lock permits many distinct analyses to pass the same stale count. Per-subject uniqueness does not protect a total owner/global budget.

Real PostgreSQL probe: one existing running job, waitingSlots=1 (cap two), four simultaneous distinct analysis identities. A barrier after each actual database count forced all contenders to observe the same count. All four commits succeeded; five active/queued jobs remained against a cap of two. Counts/inserts were real; the repository wrapper only inserted the barrier.

Fix: serialize reserve/check/insert under a database-backed owner/global admission authority, with documented lock order and correct occupancy for reconciliation. Apply admission consistently across import and scheduled workers/instances. Do not characterize the possible overshoot as only one extra job.

### NR4 — P2: cancelled preview job leaves the public preview PENDING

Locations: MouseVideoScheduledJobRunner.java:280–285; MouseVideoPreviewService.java:49–51; the queued cancellation path in MouseVideoJobService.

Cancellation settles only the job; it does not settle its bound preview. The queued path never runs a worker that could repair the row. Receipt mapping also treats CANCELLED as PENDING.

Real PostgreSQL probe on V45: created a pending preview and queued bound PREVIEW job, cancelled the job and confirmed CANCELLED, then read the public preview service. It still returned PENDING.

Fix: settle bound preview state atomically under the correct execution/cancellation fence, including queued jobs; return truthful CANCELLED/reconciliation receipts and stop polling terminal work. Verify retry/replacement generations cannot be overwritten by stale settlement.

### NR5 — P2: template/output configuration is absent from the actual analysis identity

Locations: MouseVideoSchedulingService.java:403–437; analysis worker reads current configuration/template on execution.

The digest records templatePackId and only a boolean cursorTemplateConfigured; it does not hash template contents or cursor hotspot/matching threshold. toolchainDigest hashes a configured label and executable paths, not verified binary contents. Work can be coalesced/reused under an identity that no longer describes its output.

Probe: scheduled against a valid PNG cursor template, changed its PNG bytes at the same configured path and scheduled again. Both request digests were identical.

Fix: freeze/hash approved template contents, all output-affecting settings and verified executable/runtime identities before scheduling. Execute that frozen snapshot, not mutable current paths/configuration. Test queued work across configuration changes and same-path replacement. Qualification cannot be reproducible from path names and a boolean.

### NR6 — P2: PostgreSQL service-race fixture still stops at V43

Location: src\test\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoServicePostgresRaceTest.java:70.

After Docker access was restored to the test JVM, all nine service-race cases failed before their intended assertions because the fixture migrates only to V43 while the entity now queries V45 analysis_id/preview_id/subject fields. Actual error: column mvj1_0.analysis_id does not exist.

Fix: migrate the fixture through the current required schema, retain explicit historical upgrade cases separately, and add the new scheduling/preview/finalization races. MouseVideoPostgresConcurrencyTest also targets V43: passing old-schema SQL concurrency tests do not qualify V45.

## Tests

Independently executed with Temurin 17.0.20.1+1, isolated Gradle output/project cache and process-local JAVA_TOOL_OPTIONS=-Dapi.version=1.44:
- Video-tree baseline: 29 suites / 200 tests / 191 passed / 9 failed / 0 errors / 0 skips. The 16 PostgreSQL cases were attempted: migration/concurrency cases passed; the nine service-race cases failed as NR6 describes.
- Four initial independent probes: four failures, zero skips. These demonstrate NR1/NR2/NR4/NR5.
- Additional real-PostgreSQL admission probe: one failure, zero skips, demonstrating NR3 (five jobs versus cap two).
- Focused JavaScript: 13/13 passed.
- Did not rerun full npm, full native/repository suites, Python, Spotless/Checkstyle or live application/browser workflows. Submitted server/browser evidence was inspected as narrative, not independently re-executed here.

The external PostgreSQL probe clone alone changes its setup target to V45 so NR3/NR4 can exercise current entities. The production test fixture is unchanged; its original nine failures remain recorded. No probe is presented as fixing the baseline.

Retained here: source-sha256.json, commands.json, baseline-result.json, baseline-junit, probe-junit, admission-junit, baseline.log, probes.log, admission.log, js.log, exact two probe sources and isolated review.init.gradle. Probe source stayed outside repository src/test and was removed afterward.

## Changed files

No application or permanent test source changed. All 93 fingerprinted source files remained byte-identical.

Added only this review evidence directory; replayed the existing Felix outbox note and moved its record from pending to synced after verifying the write. Existing dirty/untracked work was preserved. Disposable probe originals/databases were isolated from application data.

## Remaining work

Fix NR1–NR6 with permanent targeted regressions, then rerun the corrected PostgreSQL baseline and new real concurrency/lifecycle tests. Maintain the existing unqualified matrix: real disk quota, hostile resource/output tests, crash/expiry/reconciliation/GC, VFR/rotation/crop previews, full browser conflicts/history/exports/keyboard/a11y, approved held-out methods, qualified YouTube egress/pins/live clip, final native/global checks, operations/rollback and required Windows containment.

Visual source timing/sampling, cursor/target support and approved accuracy remain partial. Smoke import/preview success does not establish confinement, global admission, immutable identity or full visual qualification.

## Risks

NR1 permits cross-job/owner data corruption within the sandbox's shared mounted store. NR2 can return the wrong logical result. NR3 can over-admit substantially under concurrent requests. NR4 leaves false pending status; NR5 invalidates reproducibility/coalescing. No actual operational corruption or production concurrent exploit is claimed; probes used controlled fixtures.

## Rollback

No application patch was applied by this review. Retain evidence and use isolated corrective patches; never reset the dirty tree. Testcontainers handled disposable databases, and the sandbox write probe touched only its temporary fake original. The process-local Docker API setting was not persisted.

## Felix outage recovery and state

Recovered and read-verified the original outbox payload as note_1792566515662_c449eeb6 with offline-sync:6b422d7e-24e3-4675-9a62-6ec9dfc73d21. The original request's sc-2150 Shortcut entity label was normalized to canonical numeric ID 2150; no task duplication occurred. The record moved to docs\builderflow\felix-sync\synced\sc-2150\20261021T032159Z--sc-2150--create-note--analysis-preview-visual-evidence.md with actual sync metadata.

The original implementation note is retained as supplied, not rewritten to hide these findings. No checklist transition, Felix workflow transition or Shortcut mutation/marker occurred. Task remains in_progress / draft.


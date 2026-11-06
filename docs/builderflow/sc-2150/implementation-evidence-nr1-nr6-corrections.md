# SC-2150 — Implementation Evidence: NR1–NR6 analysis/preview review corrections

Date: 2026-10-21. Project: C:\Dev\IdeaProjects\ChromaScape.
Felix task: task_1792382767281_cd8dbu69 (in_progress / draft — unchanged by this work).
Review corrected: docs\builderflow\sc-2150\verification-runs\20261021T070835Z-analysis-preview-review\review.md.
Evidence: docs\builderflow\sc-2150\verification-runs\20261021T091303Z-nr1-nr6-corrections\.

Scope: correct only the six reproduced findings NR1–NR6 and regressions discovered while verifying
them. This does not complete SC-2150; the remaining story gates are listed below.

## Provenance of the corrections

An earlier, unattributed session (file times 09:23–09:41 local, after the review) had already
applied part of the corrections: V46 (template_checksum, mouse_video_scheduling_requests), the
per-owner advisory admission lock, request aliases, per-job sandbox mounts, queued-preview
settlement and V46 test fixtures. No running session owned those edits. This session verified that
partial work against the review's own probes, completed the gaps below and verified the result.

## Finding status

| Finding | Status | What closes it |
|---|---|---|
| NR1 sandbox cross-job writes | Fixed (earlier session); verified | Launcher mounts only the job workspace writable and exact inputs read-only; `sandboxedChildCannotTruncateASiblingOriginalButCanWriteItsOwnJob` and review probe `sandboxCannotOverwriteAnotherJobsOriginal` pass on real bwrap. |
| NR2 raced recovery accepts wrong body | Fixed (earlier session) + hardened | Durable alias table; recovery validates digest/kind/source/bound analysis. This session made the alias repository mandatory (the null fallback silently disabled protection). Review probe `racedRequestMustNotReturnAnotherBodyOrSource` and `coalescedAliasKeepsChangedBodyConflictProtection` pass. |
| NR3 non-atomic admission | Fixed + completed | New `MouseVideoAdmission` (advisory lock + cap) shared by scheduling **and** import registration (upload and YouTube previously bypassed it). Deterministic barrier race and shared import/schedule limit pass on real PostgreSQL. |
| NR4 cancelled preview stays PENDING | Fixed + completed | Cancellation settles the bound preview in the same transaction (earlier session); this session made the preview repository mandatory (the 3-arg constructor silently skipped settlement — the exact reason the review probe still failed), reports RECONCILIATION_REQUIRED for stuck previews, and stops browser polling on CANCELLED/FAILED/RECONCILIATION_REQUIRED with a fresh request identity for retry. |
| NR5 template/config not in identity | Fixed + completed | New `MouseVideoAnalysisIdentity` hashes template content, hotspot/threshold/sampling settings and executable contents. The worker now re-verifies the identity before executing and fails closed with IDENTITY_CHANGED instead of running mutable live configuration; scheduling freezes exactly the hashed template bytes (fails closed as a retryable conflict if the file changed mid-request). |
| NR6 fixture at V43 | Fixed (earlier session); verified | All PostgreSQL fixtures target V46; all 16+ PostgreSQL cases run with zero skips. |
| Found while verifying | Fixed | Video PostgreSQL test configs were `@TestConfiguration` stereotypes and were component-scanned into full application contexts (duplicate `dataSource`), breaking context-load tests. Stereotype removed; configs remain explicitly registered. |

## Changed files

Main (this session):
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoAnalysisIdentity.java (new)
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoAdmission.java (new)
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoSchedulingService.java
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoAnalysisWorker.java
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoRegistrationService.java
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobService.java
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoPreviewService.java
- src\main\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoException.java
- src\main\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoPublicErrors.java
- src\main\resources\static\js\mouse-video.js

Tests (this session):
- src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoAnalysisIdentityTest.java (new, 7 cases)
- src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoPreviewServiceTest.java (new, 3 cases)
- src\test\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoSchedulingPostgresTest.java (+2 real-PostgreSQL cases)
- src\test\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoServicePostgresRaceTest.java
- src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobServiceTest.java
- src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoSchedulingServiceTest.java
- src\test\js\mouse-video.test.js

The full list and SHA-256 of every source file modified after the review (including the earlier
session's) is in the evidence directory as touched.txt / post-fix.sha. No migration was added by
this session. No unrelated dirty or untracked work was modified or reformatted (Spotless was run
per file with spotlessIdeHook).

## Tests

Executed on Fedora with Temurin 17.0.20.1+1, isolated build/project-cache directories,
process-local JAVA_TOOL_OPTIONS=-Dapi.version=1.44 (Docker 29.7.2), postgres:16 Testcontainers:

- Video tree (`com.chromascape.web.debug.tickdebugger.video.*`): **220 tests, 0 failed, 0 skipped**
  (review baseline: 200 / 9 failed).
- Review probes, adapted only to the new mandatory constructor arguments, migration target V46 and a
  timeout-tolerant barrier: **19 / 19 pass**. Run in isolation exactly as the review did, the
  admission probe passes; run with its shared-owner siblings it records before=6 after=6, i.e. zero
  admissions over the limit. Adapted copies retained under probes\.
- JavaScript `node --test src\test\js\mouse-video.test.js`: **14 / 14 pass**.
- spotlessCheck on every changed file: clean. checkstyleMain/checkstyleTest: pass (warnings only,
  matching existing package patterns; none of the new-code warnings remain).
- Full repository suite: **2841 tests, 72 failed, 15 skipped**. Re-run of all 26 failing classes plus
  the video tree after the test-config fix: 415 tests, the same 72 failed, 0 video failures. None of
  the 72 are in SC-2150 code. Causes: a second, unrelated component-scanned test config
  (`BootstrapRecordingCoordinatorTest$JsonConfiguration`, gameplay bootstrap) that now blocks the same
  context-load tests; missing `contracts\sc967\execution-closure*` fixtures; missing
  `chromascape_test` database; Windows-only micro6 paths; and other non-video assertions in
  concurrently modified stories. Not run: Python tests, live application, browser E2E.

## Remaining work

These SC-2150 completion gates remain open and are not claimed:
- Held-out cursor accuracy/coverage qualification (NV10/VP4) — needs approved annotated held-out clips.
- Pinned, approved live YouTube acquisition and provider-fault corpus (N8/NV11/VP5) — needs an
  approved public source and egress decision.
- Real browser E2E: keyboard/narrow layout/correction/source-use/export flows (NV7/NV12/VP3).
- Containment on the supported host incl. fast-parent/reparented descendants (QI1/QV1–QV3), real
  per-job disk quotas, crash/restart/second-instance recovery (NV3), VFR/rotation/crop corpus (NV6).
- Upload pre-check: admission is enforced authoritatively at registration; an over-limit upload is
  still streamed before rejection (reservation is released). A pre-read check is a follow-up.
- Repository-wide: the gameplay bootstrap test-config collision and other non-video failures belong
  to other stories.

## Risks

- Analyses queued before this change were scheduled with a different config-digest shape; they will
  now fail with IDENTITY_CHANGED and must be rescheduled. This is intentional fail-closed behaviour.
- Import registration now returns QUOTA_EXCEEDED when the owner's job slots are full, which previously
  succeeded. Clients already handle QUOTA_EXCEEDED.
- The advisory lock is PostgreSQL-specific; any non-PostgreSQL deployment of these services fails.

## Rollback

The video tree is untracked in Git and no pre-change copies were retained, so rollback is a
targeted code revert, not a checkout. The review's source-sha256.json records the tree as reviewed
(before both correction sessions). This session introduced no migration or data change (V46 belongs
to the earlier session and is additive). To back out individual corrections: remove the
`identity.requireUnchanged` call in MouseVideoAnalysisWorker (restores NR5 execution drift); remove
the two `admission.lock/requireCapacity` pairs in MouseVideoRegistrationService (restores the NR3
import bypass); restore `@TestConfiguration(proxyBeanMethods = false)` on the two video
PostgreSQL test configs (restores the context-load collision). Commit the tree before further
work so later rollback is a Git operation.

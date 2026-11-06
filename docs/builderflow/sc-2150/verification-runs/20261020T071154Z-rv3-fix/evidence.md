# SC-2150 — RV3 null-code remediation evidence

Date: 2026-10-20. Project: C:\Dev\IdeaProjects\ChromaScape.
Task: task_1792382767281_cd8dbu69. Scope: fix the remaining nullable-error-code review defect, add permanent regressions and replay the PostgreSQL verification. Broader SC-2150 completion remains governed by the existing full plan.

## Changed files

- C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoPublicErrors.java
- C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobServiceTest.java

jobFailure() normalizes a missing code to CODE_DECODER_FAILED before querying the immutable message map. Null keys can no longer cause an NPE. Existing known/unknown non-null code handling, bounded public messages and correlation IDs are preserved. No stored diagnostics or database schema changed.

Added separate FAILED and TIMED_OUT public-view regressions. Both assert a safe fallback message, correlation reference, 320-character bound, absent private diagnostic text, and unchanged private stored message. Public errorCode remains null for a legacy null-coded row, preserving its previous representation.

Only these two of the 75 fingerprinted source files changed. rv3.patch was reconstructed and checked against the pre-edit hashes. Unrelated dirty/untracked work was preserved.

## Acceptance results

- RV3: fixed and verified. The retained real-PostgreSQL probe that previously failed now passes after persisting and reloading a FAILED job with no error code.
- Both permanent missing-code public-view regressions pass.
- RV1/RV2 and both retained F2 probes remain passing.
- This increment is not full feature or release acceptance.

## Tests

Actually run using Temurin 17.0.20.1+1, an isolated Gradle build directory and project cache, and process-local JAVA_TOOL_OPTIONS=-Dapi.version=1.44:
- Video/service/controller plus MouseResearchPageTest selection: 24 suites, 153 tests, 0 failures, 0 errors, 0 skips (151 previous + 2 new regressions). Includes real PostgreSQL 16 migration/concurrency/service-race suites.
- Retained independent probe selection: 5/5 passed, 0 errors/skips. Covers retained-context service/controller cancellation, filesystem-read classification, legacy path sanitization and the previously failing null-code persisted-job case.
- Spotless on the two changed Java files: passed.
- checkstyleMain: successful, 0 errors / 4,561 warnings.
- Full repository spotlessJavaCheck: FAILED on unrelated aiplayer formatting. The Java tests completed successfully before that task failed; the combined command therefore returned nonzero. The follow-up probe/scoped-format/Checkstyle command succeeded.
- JS and Python were not rerun: this patch changes only the Java public-message helper and Java tests.

Unrelated global Spotless violations:
- src\main\java\com\chromascape\web\aiplayer\contract\CurrentSliceIdentity.java
- src\main\java\com\chromascape\web\aiplayer\routine\service\CandidateExecutionClosureV1.java
- src\main\java\com\chromascape\web\aiplayer\routine\service\PatchCandidateWorkflowService.java
- src\test\java\com\chromascape\web\aiplayer\routine\service\CandidateExecutionClosureV1Test.java
- src\test\java\com\chromascape\web\aiplayer\routine\service\PatchCandidateWorkflowServiceTest.java

Those files were left untouched. Scoped formatting verification does not claim the repository-wide gate passed.

Probe source was added only in a temporary external source directory through the isolated init script, then removed. No temporary probe was placed in repository src/test.

## Evidence

This directory retains source-before.json, source-after.json, changed-files.json, rv3.patch, commands.json, baseline-result.json, baseline-junit, probe-junit, checkstyle-main.xml, baseline-and-global-style.log, probes-and-scoped-style.log, the exact probe source and review.init.gradle.

The independent re-review that identified RV3 is note_1792480235451_10922d4b, backed by docs\builderflow\sc-2150\verification-runs\20261020T070245Z-rv1-rv2-rereview.

## Remaining work

No remaining RV3 code work was identified by these checks. Repository-wide formatting remains failed on the five unrelated files above.

Broader gates remain open: browser correction/source-use controls and full browser flows, cursor implementation/approved held-out qualification, approved YouTube pins/clip and qualification, anonymous/independent-owner operator checks, wider lifecycle/resource/restart/sweep/multi-instance/required-host qualification, full native acceptance, operations and rollback rehearsal. No feature enablement, running-instance restart or browser test occurred here.

## Risks

Legacy rows retain private diagnostics by design. This fix protects their public message mapping; it does not change their stored codes or rewrite immutable evidence. Scoped green checks do not establish full repository or feature readiness.

## Rollback

Reverse only the retained RV3 patch after checking for subsequent edits. Do not reset/restore whole dirty files or rewrite diagnostics. No schema migration or runtime configuration rollback is required. Docker API compatibility was process-local.

## Tracker reconciliation

Felix remains in_progress / draft. No checklist mutation, workflow transition, Shortcut state update or synchronization marker was emitted. The existing task and its previous evidence/checklists were preserved.


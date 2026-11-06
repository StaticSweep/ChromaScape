# SC-2150 — RV1/RV2 independent re-review

Date: 2026-10-20. Project: C:\Dev\IdeaProjects\ChromaScape.
Task: task_1792382767281_cd8dbu69, in_progress / draft.
Reviewed implementation evidence: note_1792474597384_9725f428 and docs\builderflow\sc-2150\f3-f4-independent-review-remediation-evidence.md.

## Acceptance results

RV1, RV2 and the thumbnail fixture correction are verified for their reported cases. One additional P2 availability defect, RV3, was reproduced in the new public read-boundary path. This is not feature/release acceptance.

- RV1: filesystem reads now occur before the JSON catch; only JsonProcessingException is reclassified. The retained missing-file probe passes, as do the new storage/JSON regressions and reservation/staging cleanup regression.
- RV2: the public view derives allowlisted failure/reconciliation messages instead of returning stored diagnostics. The retained PostgreSQL legacy-path probe passes. Raw diagnostics remain stored privately; no destructive rewrite was applied.
- Thumbnail coverage: the fixture now selects a completed analysis and supplies an analysis-scoped FINALIZED text/html thumbnail. The verified repository lookup precedes the actual MIME refusal; the old early-rejection coverage defect is corrected.
- Both retained F2 service/controller probes still pass with real PostgreSQL and a stale request persistence context. Controller cleanup signalling is mocked; this does not qualify browser or live-server authentication behavior.

### RV3 — P2: nullable stored error code crashes public job views

Locations:
- src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobService.java:399
- src\main\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoPublicErrors.java:78
- src\main\resources\db\migration\V42__sc2150_mouse_video_sources.sql:59

For FAILED and TIMED_OUT jobs, view() passes getErrorCode() to jobFailure(). MESSAGES is an immutable Map.ofEntries map. Its getOrDefault(null, fallback) throws NullPointerException before the fallback can be used.

The error_code database column is nullable, and the service/entity allow a null code. A new probe persisted a FAILED job with a null code through the real job service, reloaded it from PostgreSQL, confirmed its code was null, then invoked the public view. It failed with:
~~~text
java.lang.NullPointerException: Cannot invoke "Object.hashCode()" because "pk" is null
  ImmutableCollections$MapN.probe
  ImmutableCollections$MapN.get
  MouseVideoPublicErrors.jobFailure(MouseVideoPublicErrors.java:78)
  MouseVideoJobService.view(MouseVideoJobService.java:399)
~~~

Trigger is specifically a failed/timed-out row without a code; this review does not claim such rows currently exist in the user's operational database. A valid persisted row with that shape was sufficient to reproduce the failure in the disposable test database. Normal coded failures remain verified.

Required correction: normalize a null code to the safe default before immutable-map lookup, then add permanent public-view tests for both FAILED and TIMED_OUT with missing codes. Preserve bounded messages, correlation IDs and private stored diagnostics. No migration or raw-diagnostic rewrite is needed for this correction.

## Tests

Independent isolated reruns:
- Video/service/controller plus MouseResearchPageTest: 24 suites, 151 tests, 0 failures, 0 errors, 0 skips.
- This includes the real PostgreSQL 16 migration, concurrency and service-race suites.
- Retained independent probes: all four passed (RV1, RV2 and two F2 probes).
- Added null-code probe: one failed with the NPE above. Combined probe selection: 5 tests / 4 passed / 1 failed / 0 errors / 0 skips.
- JS, Python, Spotless and Checkstyle were not rerun during this re-review. Their results in the submitted evidence are prior runs, not new independent results here.

Toolchain: Temurin 17.0.20.1+1. Docker compatibility used process-local JAVA_TOOL_OPTIONS=-Dapi.version=1.44, with no user-home/repository Docker configuration changes.

The first shared-output run produced missing-class/context errors while compiled output timestamps changed underneath it. Its log is retained as shared-build-attempt-invalid.log; no application defect is inferred from that run. A fresh isolated build directory and separate project cache were used for the authoritative baseline and probes. The baseline compiled fresh, then actually ran the 151 tests. --rerun forced test execution.

Probe source was configured from a temporary directory outside src/test, using the retained review.init.gradle. The extra source was removed afterward. No repository application/test file was temporarily edited in this re-review.

Evidence retained in this directory:
- source-sha256.json: current fingerprints for 75 relevant files.
- changed-since-prior-review.json: exactly six intended files differ from the prior independent-review baseline.
- baseline-result.json and baseline-junit: independent 151-test result and XML.
- probe-junit: five-probe XML, including the RV3 stack.
- isolated-baseline.log, isolated-probes.log and shared-build-attempt-invalid.log.
- MouseVideoIndependentReviewProbeTest.java and review.init.gradle: exact replay/fault-probe source and isolated build configuration.

## Changed files

Only this re-review evidence directory and its Felix documentation note were added by the reviewer. All 75 source fingerprints remained unchanged throughout the review. The six source files supplied by the implementer are exactly the two production files and four tests recorded in the submitted evidence.

## Remaining work

Fix RV3 and add its permanent regression, then run the affected job-service/public-view tests and retained null-code probe. Do not reopen the fixed RV1/RV2 findings solely because RV3 exists; retain their passing evidence.

Broader SC-2150 gates remain open under the existing full completion plan: browser correction/source-use controls and full real-browser flows, cursor implementation plus approved held-out qualification, approved YouTube pins/clip and qualification, anonymous/independent-owner operator checks, broader lifecycle/resource/restart/sweep/multi-instance and required-host containment, full native qualification, operations and rollback rehearsal.

## Risks

The new sanitizer safely hides legacy raw messages for coded failures, but currently fails when a failure code is missing. Full browser/live-server qualification was not performed. Passing scoped tests do not establish complete feature readiness.

## Rollback

No application changes were applied by this review. Retain the evidence and avoid resetting or cleaning the dirty checkout. The isolated Gradle output/cache and disposable Testcontainers database are separate from the running application. The process-local Docker API override expires with the test process.

## Tracker reconciliation

PI3 and PI4 were already checked by the implementation increment; this review preserved their states. No checklist mutations, workflow transitions or Shortcut synchronization markers were emitted. Felix remains in_progress / draft. The Shortcut state was not changed by this review.


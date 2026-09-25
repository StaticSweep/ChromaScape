# ChromaScape Phase 3-8 Test Results (WSL diagnostic, 2026-08-14)

Environment: WSL, JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64, gradle 8.14.2
Gate: `./gradlew --no-daemon --max-workers=1 test` (1373 tests, 32 failed, 11 skipped)
JS gate: `node --test src/test/js/account-task-planner.test.js` (103 pass) and
`node --test src/test/js/fishing-shadow-core.test.js` (36 pass)

## Phase 3 — identity/readiness (implemented + tests)
- Registry: hash authority, name fallback only w/o hash, missing identity rejected, expectedWorld
  exact-equality against observed current_world (never heartbeat world_matched flag), fail-closed
  dispatch_hold/login/startup/pathfinder, missing login state, runnable==dispatchReady agreement.
- New evaluator unit test (14 cases): login precedence, fail-closed missing/blank/unknown login,
  world mismatch, dead loop, non-RUNNING lifecycle, hold, startup, pathfinder.
- Production fix: `findDispatchReadyClientForRecovery` now rejects unspecified identity.

## Phase 4 — settings normalization
- Strip set now includes `taskName` and `task_metadata` (contract completeness).
- Nested legacy task_metadata gets same reserved-field stripping via shared helper.
- Tests: empty {} authoritative, non-empty envelope, nested settings, legacy raw stripping,
  nested legacy stripping, non-object rejection, imported/persisted mismatch (reflection into
  publishIteration), empty-to-empty round trip.

## Phase 5 — structured duplicate/conflict attachment
- `Micro6ConflictIdentity` record + `Micro6PublishResult.conflictingDispatch`.
- parseConflictIdentity requires existing_entry_id/task_key/existing_status (else fail closed).
- resolveConflictAttachment: exact task-key match -> poll referenced entry -> verify returned
  entry id == conflict id, task identity == requested, status in attachable whitelist
  (RECEIVED/PENDING/ACCEPTED/ASSIGNED/QUEUED/RUNNING/PAUSED) -> attach; timeout -> RECONCILING;
  confirmed mismatch -> DUPLICATE_OR_CONFLICTING_DISPATCH.
- Micro6 producer fix: 409 conflict `task_key` now identifies the EXISTING entry
  (existingIdentity.canonicalTaskKey()); requested identity stays in `requested_task`.
- Producer test asserts existing_entry_id, task_key==existing, existing_task, requested_task, status.
- Consumer tests (8): verified attach, mismatch task, missing fields, invalid entry id, polled id
  mismatch, polled task mismatch, unattachable status, ambiguous timeout, PAUSED attach.

## Phase 6 — PAUSED preservation
- ScriptExecutionService.mapPublishStatusForTransport: PAUSED -> PAUSED (not RUNNING).
- isAcceptedPublishStatus includes PAUSED.
- BuilderGoalRunnerService.mapPublishStatus: PAUSED -> PAUSED.
- BuilderGoalRunStateMutationService.mapPublishStatus: PAUSED -> PAUSED.
- JS planner: PAUSED label "Task paused; the executor suspended the task." + active-state membership.
- Tests: mutation-service paused survives reconciliation + stays non-terminal; run-summary paused
  (status==PAUSED, canStop true, no RUNNING text); JS label/active assertions.

## Phase 7 — Micro6 lifecycle (verified, no change needed)
- mainLoopInProgress liveness, heartbeat refresh at entry/completion/recovery, diagnostics exposed
  on /health (line 1055) and heartbeat (line 3502), lifecycle integration test proves executor A
  terminated on shutdown + fresh executor B used on restart + both closed in teardown.

## Phase 8 — canonical Fishing harness
- Harness plans/fixtures/golden manifests use FISHING_TRAINING throughout.
- HarnessCanonicalIdentityRegressionTest asserts canonical plan key, direct resolution, and
  FISH_TRAINING demoted to alias.
- Micro6 producer conflict test updated to assert all mandatory identity fields.

## Phase 2 — data-drift (provenance added)
- Provenance comments added to TreeLocationDBTest, HunterLocationDBTest, FishingLocationDBTest.
- ShortestPathCore resources verified loading with nonzero extents; SHA-256 recorded.
- HunterLocationDBTest already enforces east/west catch-bound separation.

## Pre-existing failures (32) — classified, unchanged
- contextLoads cascade (14, environment-only: `local-development` profile set in Windows host env)
- remaining 18 classified test-seam/mock drift or data-drift in unrelated subsystems
  (patchloop validators, simulator, recording, aiplayer, tickdebugger, stabilization).
- Failure table: /tmp/chromascape-remediation/chromascape/failure-table.md

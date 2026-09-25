# Phase 2 ChromaScape full-gate remediation verification pass

Date: 2026-08-15 (Linux/WSL iteration; final gate runs on Windows)

## Summary

Implemented deterministic fixes for the previously RED ChromaScape full Java gate. All
non-database unit tests that were failing in the prior run now pass locally with Java 17.

## Verification

Environment: WSL, Temurin 17, `./gradlew --no-daemon` (Linux wrapper; the authoritative Windows
gate is `gradlew.bat`).

- `compileJava compileTestJava`: PASS (fresh rerun, no errors).
- Previously failing non-DB unit tests: PASS — 182 tests completed, 0 failures across
  `SimulatorReplayControlControllerTest`, `FishingShadowDecisionWriterTest`,
  `FishingShadowDecisionServiceTest`, `AnalysisV1ValidatorTest`, `AnalysisV2ValidatorTest`,
  `AnalysisV2ContractSerializationTest`, `PatchLoopAsyncWorkerTest`,
  `DebugSessionServiceTest`, `RecordingServiceInitialFrameReadinessTest`.
- Builder Goal focused suite (`Micro6TargetRelaunchServiceTest`, `Micro6ExecutorReadinessEvaluatorTest`,
  `AccountIdentityContractTest`, `BuilderGoalRunnerServiceTest`): PASS.

## Root causes fixed

1. Spring auth/profile bootstrap — `ChromaScapeApplicationTests` now uses `@ActiveProfiles("test")`
   and `application-test.yml` supplies inert read/write stabilization tokens so integration
   contexts boot under the production security config.
2. Mockito strict-stubbing drift — removed/realigned unnecessary stubs in
   `PatchOutcomeEvaluationServiceTest`, `SimulatorVerifyServiceTest`,
   `StabilizationIngestionServiceTest`.
3. Manifest-hash fixture drift — `StabilizationManifestIntegrityTest` recomputes the declared
   manifest hash after mutating `evidence_complete`.
4. Shadow JSONL contract drift — `FishingShadowDecision` now ignores unknown fields on read
   (forward-compatible `control_allowed`), fixing history reads and writer round-trips.
5. Analysis contract drift — `AnalysisV1/V2` DTO `getSchemaVersion()` no longer masks an explicit
   `null`, restoring validator `MISSING_REQUIRED_FIELD` detection; `AnalysisV2Target.SessionBounds`
   tolerates derived read-only properties (`durationSeconds`, `valid`); serialization test uses a
   JSR310-registered `ObjectMapper`.
6. Simulator preflight drift — controller tests supply frames satisfying the replayability
   preflight (schemaVersion>=2 + position telemetry).
7. Test-seam drift — `RecordingServiceInitialFrameReadinessTest` unwraps reflection
   `InvocationTargetException`; `DebugSessionServiceTest` accounts for the second persisted save;
   `PatchLoopAsyncWorkerTest` asserts the `markIterationFailed` contract instead of a
   single-`updateIterationStatus` call.
8. Compile errors in the working tree — `Micro6CanonicalHash` mixed `Object`/`String` node values;
   `compileMicro6ProductionClasses` now reaps the owned process tree on the `RelaunchException`
   (nonzero-exit) path so the redirected log is released (fixes Windows `@TempDir` teardown).

## Remaining before release completion

- Full Windows gate: `gradlew.bat --no-daemon compileJava compileTestJava test` + `npm test`.
- Real Fishing operational acceptance on a matching-identity account (catalog
  `3720a9a3b5c9f57b` == executor canonical hash; do not bypass with account name).
- Micro6 local changes remain uncommitted on `sc-584a-health-contract`; upstream delivery requires
  a fork branch and PR (chsami/Microbot is read-only for this account).

# ChromaScape Full-Test Baseline Failure Table (WSL diagnostic run, 2026-08-14)

Gate: `gradlew --no-daemon test` (1331 tests, 32 failed, 11 skipped).
WSL evidence only; Windows gate is authoritative for acceptance.

| # | Test | Failure | Classification | Production change? | Expected-value change? | In remediation scope? |
|---|------|---------|----------------|-------------------|------------------------|------------------------|
| 1 | ChromaScapeApplicationTests.contextLoads | stabilization read/write tokens required outside local-development | environment-only (profile/tokens set in Windows host env, absent in WSL) | no | no | no |
| 2 | SimulatorVerifyServiceIntegrationTest.* (14) | ApplicationContext failure threshold (cascade of #1) | environment-only (cascade) | no | no | no |
| 3 | StabilizationIngestionServiceTest.ingestHarnessManifest | Mockito PotentialStubbingProblem (strict stub mismatch) | test-seam/mock drift | no | stub | no |
| 4 | StabilizationManifestIntegrityTest.aPASSManifest... | harness manifest hash mismatch assertion | test-seam/data-drift (declared vs recomputed hash) | no | fixture/hash | no |
| 5 | PatchOutcomeEvaluationServiceTest.evaluate_reportsEligibilityAndSuccessMetrics | UnnecessaryStubbingException | test-seam/mock drift | no | stub | no |
| 6 | AnalysisV1ValidatorTest.* (2) | Expected AnalysisException not thrown (missing schema version) | test-seam/mock drift | no | assertion | no |
| 7 | AnalysisV2ContractSerializationTest.* (4) | Java 8 date/time LocalDateTime not supported by test ObjectMapper | test-seam/mock drift (JSR310 module) | no | mapper | no |
| 8 | AnalysisV2ValidatorTest.testMissingSchemaVersionFails | Expected AnalysisException not thrown | test-seam/mock drift | no | assertion | no |
| 9 | PatchLoopAsyncWorkerTest.Failed analysis FAILURE... | TooManyActualInvocations on updateIterationStatus | test-seam/mock drift | no | mock | no |
| 10 | DebugSessionServiceTest.startSession_unknownPreferredSessionId... | TooManyActualInvocations on sessionRepository.save | test-seam/mock drift | no | mock | no |
| 11 | RecordingServiceInitialFrameReadinessTest.* (2) | Expected FrameCaptureException not thrown | test-seam/mock drift | no | assertion | no |
| 12 | SimulatorReplayControlControllerTest.* (2) | expected 200 OK but was 409 CONFLICT | test-seam/production behavior drift | no | assertion | no |
| 13 | SimulatorVerifyServiceTest.ExecuteJobBranchTests.* (5) | UnnecessaryStubbingException | test-seam/mock drift | no | stub | no |
| 14 | SimulatorVerifyServiceTest.MissingReportErrorTests.* | passed=false expected true but was false | test-seam/production behavior drift | no | assertion | no |

## Decisions

- All 32 failures are pre-existing in the working tree; none occur in the builder-goal
  identity/readiness area changed during this remediation (Micro6OnlineClientRegistryService,
  Micro6ExecutorReadinessEvaluator, BuilderGoalRunnerService).
- Per operating rules: do not modify expected values to make tests pass. Failures 3-14 are
  classified test-seam/mock drift or data-drift requiring provenance-backed review before any
  assertion change, and lie outside the Phases 3-8 remediation contract.
- Failure 1 is environment-only: production activates the `local-development` Spring profile via
  the Windows host environment (`chroma_bootrun.log` line 22 shows `1 profile is active:
  "local-development"`). The WSL run has no profile and no stabilization tokens. On the Windows
  host the `contextLoads` gate and its simulator integration cascade are expected to pass.
- No remediation-contract test failure remains in the buildergoal cluster (340 tests green,
  including new identity/readiness coverage).

## Micro6 focused-cluster failures (Windows toolchain, 2026-08-14)

| Test | Failure | Classification | Production change? | Expected-value change? |
|---|---|---|---|---|
| AccountBuilderScriptLifecycleIntegrationTest.recoverableErrorDoesNotFalselyReportDeadScheduler | awaitFirstHeartbeat timeout; lifecycle stuck STARTING | environment-only (recovery path needs live game client Rs2Walker) | no | no |
| Micro6BuilderExecutorServerTest.tickOnlyTransitionCarriesDegradationReason | malformed assertEquals arg order (message/expected swapped) | test-seam defect | no | yes (fixed arg order to correct expected value) |

Note: `tickOnlyTransitionCarriesDegradationReason` assertion was fixed (production already
set the correct failure reason at Micro6BuilderExecutorServer line 611). The recoverable-error
test remains as-is per "do not weaken assertions"; full Micro6 `:client:test` gate must be run
on the Windows host (Phase 9) where the game-facing executor harness is available.

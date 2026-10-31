# SC-2150 — W4 full-suite failure dispositions (2026-10-31)

Plan item W4 / R28 review P2 #3: every failing test gets an evidenced disposition; no blanket attribution.

## Method

Two clean detached worktrees, same toolchain (JDK 21 launcher, Java 17 toolchain, `DOCKER_API_VERSION=1.44`,
repo-disk tmpdir), full `gradlew test --continue`:

- **Candidate** `0b81af7c` — PR #70 head (exactly what SC-2150 delivers).
- **Baseline** `df6eff61` — merge base with `main`.

A failure in both is pre-existing. A candidate-only failure is attributed to the commit that introduced the
failing test and the code under test, and checked against the files SC-2150 commits touch
(`git log --grep="^SC-2150"`).

## Totals

| Tree | Tests | Skipped | Failed |
|---|---|---|---|
| Candidate `0b81af7c` | 2752 | 15 | 54 |
| Baseline `df6eff61` | 2104 | 14 | 51 |

- Failing in both (pre-existing): **44**
- Candidate-only: **10** — all attributed below; **0 attributable to SC-2150**
- Baseline-only (no longer failing on the candidate): **7**
- Mouse video package (`tickdebugger.video`): **0 failures**

## Candidate-only failures

| Test | Disposition |
|---|---|
| `web.aiplayer.contract.CanonicalJsonHasherTest#equivalentDocumentsHaveSameHash` | Code and test added by `71e5131a`/`60b5b5f8` (preserved SC-260 checkout); no SC-2150 commit touches `CanonicalJsonHasher`. |
| `web.aiplayer.routine.source.SourceRunOrchestrationIntegrationTest#captureCompletionRollsBackSourceRunAndCaptureWritesBeforeRecordingUncertain` | Context fails: duplicate `objectMapper` bean from `BootstrapRecordingCoordinatorTest$JsonConfiguration` (preserved SC-260 tests). |
| `web.aiplayer.routine.source.TaskSourceManifestVerifierTest#rejectsUnsignedV1EvenWhenItsCanonicalHashIsValid` | Test added by `60b5b5f8`; source-manifest verification is not touched by SC-2150. |
| `web.debug.buildergoal.service.Micro6TargetRelaunchServiceTest#verifyLauncherContract_v3AdvertisementIsRejected` | Test added by `390f6eb9`/`60b5b5f8` (executor routing baseline); builder-goal relaunch is not touched by SC-2150. |
| `web.debug.patchloop.service.PatchApplyServiceIsolationTest#Valid patch applies in staging only, production tree untouched` | Test added by `60b5b5f8`; fails on a staging path missing in its own temp tree; patch loop not touched by SC-2150. |
| `web.gameplay.bootstrap.BootstrapApprovalStorePostgresTest#conflictingReuseIsStableConflictAndBindIsSingleUse` | Gameplay bootstrap tests added by `60b5b5f8`; not touched by SC-2150. |
| `web.gameplay.bootstrap.BootstrapRecordingCoordinatorTest#startIsIdempotentAndFinalizeIsWriteOnce` | Gameplay bootstrap tests added by `60b5b5f8` (TransactionRequiredException in its own fixture); not touched by SC-2150. |
| `web.gameplay.bootstrap.GameplayBootstrapControllerHttpTest#initializationError` | Multiple @SpringBootConfiguration classes among the preserved gameplay bootstrap tests (`60b5b5f8`). |
| `web.gameplay.bootstrap.Sc1498FlywaySchemaTest#v35TablesExistWithSafetyAndImmutabilityGuards` | Counts tables by name across every schema in a shared container (expects 1, finds 4): test isolation defect in `60b5b5f8`. |
| `web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationRestartTest#committedEvidenceSurvivesContextRestartAndRecoveryNeverRedispatches` | Hard-codes Flyway version "33"; V34–V40 come from SC-811/1498/1444/275/1976, so it fails on any tree carrying them. |

Commits `60b5b5f8`, `71e5131a` ("Preserve uncommitted … from sc260 checkout") and `390f6eb9` ("Preserve
executor routing and source binding baseline") carry other stories' work preserved on this branch; none of
the 18 `SC-2150:` commits touch the code under these tests.

## Pre-existing failures (failing on baseline and candidate)

| Package | Count |
|---|---|
| `web.aiplayer.routine` | 14 |
| `web.gameplay.provider` | 6 |
| `web.gameplay.contract` | 4 |
| `web.simulator.SimulatorVerifyServiceIntegrationTest$JobLifecycleBehavioralTests` | 4 |
| `web.aiplayer.prototype` | 3 |
| `web.gameplay.orchestration` | 3 |
| `web.debug.startup` | 2 |
| `web.simulator.SimulatorVerifyServiceIntegrationTest$BridgeReachabilityBehavioralTests` | 2 |
| `web.simulator.SimulatorVerifyServiceIntegrationTest$ReportRetrievalBehavioralTests` | 2 |
| `ChromaScapeApplicationTests` | 1 |
| `web.gameplay.authority` | 1 |
| `web.replay.kernel` | 1 |
| `web.simulator.SimulatorVerifyServiceIntegrationTest$JobCancellationBehavioralTests` | 1 |

<details><summary>All pre-existing failing tests</summary>

- `ChromaScapeApplicationTests#contextLoadsAndUsesCurrentRepositoryMigrations()`
- `web.aiplayer.prototype.FishingShadowDecisionServiceTest#run discovery survives a newly opened run before its first tick`
- `web.aiplayer.prototype.FishingShadowDecisionServiceTest#run summaries with nullable timestamps serialize cleanly through the controller mapper`
- `web.aiplayer.prototype.FishingShadowDecisionServiceTest#the first in-memory decision supplies firstTick and firstObservedAt`
- `web.aiplayer.routine.service.RoutineAssistantGenerationContextIT#aDocumentLargerThanAnyVarcharBoundRoundTrips()`
- `web.aiplayer.routine.service.RoutineAssistantGenerationContextIT#jsonColumnsAreReadableOutsideATransaction()`
- `web.aiplayer.routine.service.RoutineAssistantGenerationContextIT#resolvesTheRunsPublishedGraphFromOffRequestThread()`
- `web.aiplayer.routine.service.RoutineAssistantGenerationContextIT#theColumnHoldsTheJsonSoSqlOverItWorks()`
- `web.aiplayer.routine.source.SourceContractSchemaEqualityTest#canonicalSchemaIsByteIdenticalAcrossRepositories()`
- `web.aiplayer.routine.source.SourceFetchTransactionSuspensionTest#fetchForRunRunsUpstreamOutsideAnyDatabaseTransaction()`
- `web.aiplayer.routine.source.SourceManifestConcurrentPersistenceIntegrationTest#immutableTriggerRejectsAnyMutationOfAPersistedReference()`
- `web.aiplayer.routine.source.SourceManifestConcurrentPersistenceIntegrationTest#sameManifestHashWithDifferentBytesIsRejectedAtTheDatabase()`
- `web.aiplayer.routine.source.SourceManifestConcurrentPersistenceIntegrationTest#twoThreadsRacingTheSameIdentityConvergeOnOneImmutableRow()`
- `web.aiplayer.routine.source.SourceRunOrchestrationIntegrationTest#bindingConflictRollsBackRunAndManifest()`
- `web.aiplayer.routine.source.SourceRunOrchestrationIntegrationTest#fetchFailurePersistsNothing()`
- `web.aiplayer.routine.source.SourceRunOrchestrationIntegrationTest#manifestRunBindingAndStageCommitTogether()`
- `web.aiplayer.routine.source.SourceRunOrchestrationIntegrationTest#retriesAreIdempotent()`
- `web.aiplayer.routine.source.SourceRunOrchestrationIntegrationTest#runInsertFailureLeavesNoManifest()`
- `web.debug.startup.CdiStartupRegressionTest#contextCreatesExactlyOneSourceDebuggerReadServiceWithAllCollaborators()`
- `web.debug.startup.CdiStartupRegressionTest#repairIncidentServiceReceivesIntendedDependencies()`
- `web.gameplay.authority.HumanGameplayAuthorityServiceTest#bindsOneUseAuthorityToTheExactEnvelopeAndScope()`
- `web.gameplay.contract.GameplayContractConformanceTest#canonicalFixturesValidateAndPinDigests()`
- `web.gameplay.contract.GameplayContractConformanceTest#canonicalResourcesHaveFrozenByteDigests()`
- `web.gameplay.contract.GameplayContractConformanceTest#claudeAndCodexUseTheSameClosedDownstreamShape()`
- `web.gameplay.contract.GameplayContractConformanceTest#securitySensitiveObjectsRejectUnknownOrUnsupportedFields()`
- `web.gameplay.orchestration.BootstrapGameplayOrchestratorTest#fakeAccountBuilderCoversTerminalAndUncertainPathsWithAtomicEvidence()`
- `web.gameplay.orchestration.BootstrapGameplayOrchestratorTest#identicalRequestReconcilesAndConflictingIdempotencyFailsBeforeDispatch()`
- `web.gameplay.orchestration.BootstrapGameplayOrchestratorTest#staleObservationFailsBeforeAccountBuilderReservation()`
- `web.gameplay.provider.runtime.GameplayProviderRuntimeTest#identicalRetryInvokesProviderOnceAndPublishesOnlyPersistedDecision()`
- `web.gameplay.provider.runtime.ProviderOutputNormalizerTest#acceptsOnlyThePinnedAbstainAction()`
- `web.gameplay.provider.runtime.ProviderOutputNormalizerTest#extractsOneClaudeTerminalResultFromBoundedTransportEvents()`
- `web.gameplay.provider.runtime.ProviderOutputNormalizerTest#normalizesClaudeAndCodexGoldenProposalsToEquivalentDecisions()`
- `web.gameplay.provider.runtime.ProviderOutputNormalizerTest#rejectsEvidenceOutsidePinnedInput()`
- `web.gameplay.provider.runtime.ProviderOutputNormalizerTest#rejectsProseMultipleDecisionsAndUnsupportedActions()`
- `web.replay.kernel.XrspsProcessLaunchDiagnosticsTest#resolvedNodeProcessBuilderAndKernelLaunchesShareTheSameWindowsPaths()`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$BridgeReachabilityBehavioralTests#Should accept various bridge URL formats`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$BridgeReachabilityBehavioralTests#Should return false for unreachable bridge endpoint`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$JobCancellationBehavioralTests#Should cancel running job via REST API`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$JobLifecycleBehavioralTests#Should create job with QUEUED status via REST API`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$JobLifecycleBehavioralTests#Should reach terminal FAILED status with reportAvailable when exitCode=2`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$JobLifecycleBehavioralTests#Should return 404 for non-existent job`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$JobLifecycleBehavioralTests#Should transition job from QUEUED to RUNNING to terminal status`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$ReportRetrievalBehavioralTests#Should include reportAvailable field in status response`
- `web.simulator.SimulatorVerifyServiceIntegrationTest$ReportRetrievalBehavioralTests#Should return synthetic failure report when verifier cannot start`

</details>

## Shared working tree run (for reference)

The shared checkout (with other stories' uncommitted work) gave 2,951 tests, 67 failed, 16 skipped, 0 in the
video package; its context-load failures come from the same duplicate `objectMapper` test configuration.

## Decision needed (D6)

SC-2150 introduces no failure. Recommended: record these as pre-existing/other-story failures and do not
block SC-2150 delivery on them; the owning stories (SC-260 preservation, gameplay bootstrap SC-1498) should
fix the duplicate `objectMapper` test configuration, the cross-schema table count and the hard-coded
Flyway version.

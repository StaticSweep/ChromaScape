# Micro6 Focused Test Results (Windows toolchain, 2026-08-14)

Toolchain: `gradlew.bat --no-daemon --max-workers=1 ... --offline` (Gradle 8.8, Windows cmd)

## Phase 5 conflict producer (Micro6BuilderExecutorServerTest) — PASS
- 55 tests, 1 fixed assertion, 0 failures after fix.
- `conflictResponseCarriesMandatoryStructuredIdentityFields` asserts:
  existing_entry_id==1001, task_key==FISHING_TRAINING (EXISTING entry),
  existing_task==FISHING_TRAINING, requested_task==WOODCUTTING_TRAINING,
  existing_status==RUNNING.
- Production fix verified: 409 `task_key` now = existingIdentity.canonicalTaskKey().

## Phase 7 lifecycle (AccountBuilderScriptLifecycleTest + IntegrationTest) — 12/13 PASS
- Core contract proven:
  - schedulerExecutionProducesFirstHeartbeatAndPromotesLifecycle (heartbeat + success diagnostics)
  - realShutdownInvokesPluginCallbackAndDropsReadinessToWaitingForScript
  - shutdownTerminatesInjectedExecutorAndRestartRunsOnFreshCallerOwnedExecutor
    (executor A used, A terminated on shutdown, fresh executor B injected, B used, both closed)
  - longInProgressPassKeepsDispatchLoopAlive (mainLoopInProgress keeps liveness)
  - successfulPassDiagnosticsNeverSubstituteForLiveness
- 1 pre-existing environment-only failure: recoverableErrorDoesNotFalselyReportDeadScheduler
  requires a live game client (Rs2Walker recovery path); cannot pass headless. Not a
  production defect; assertion not weakened.

## Compilation evidence
- `:client:compileJava` BUILD SUCCESSFUL (58m45s under load) — producer change compiles.
- `:client:compileTestJava` BUILD SUCCESSFUL on Windows toolchain.

## Pathfinder resource hashes (Phase 2)
See /tmp/chromascape-remediation/micro6/resource-hashes.md
collision-map.zip sha256 06728bb9...  transport files recorded. ShortestPathCoreTest
asserts SplitFlagMap/Transport/Restriction loading with nonzero region extents.

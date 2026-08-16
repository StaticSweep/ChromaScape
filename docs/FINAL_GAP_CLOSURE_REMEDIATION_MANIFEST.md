# ChromaScape–Micro6 Final Gap-Closure Remediation Manifest

Created: 2026-08-14
Updated: 2026-08-16

## Repository scope

- ChromaScape: this repository (`chromascape`), production and test changes.
- Micro6: the configured sibling path is `C:\Dev\IdeaProjects\micro6`, checked out on local branch
  `sc-584a-health-contract`. Micro6 production and test changes were made and verified on the
  Windows host; see "Micro6 changes" below.

## Final state summary

- Micro6 canonical identity, launch, health/heartbeat, lifecycle, and account-hash producer changes
  are implemented and verified on Windows (`:client:test` 949 passing / 0 failing / 4 skipped,
  `:client:prepareBuilderGoal` passing, launcher `-CompileOnly` passing).
- ChromaScape Builder Goal focused suite passes (93 tests) and JavaScript passes (140 tests).
- The ChromaScape full Java gate still has unrelated failures that must be fixed or explicitly
  reviewed before any release-completion claim (see "ChromaScape full Java gate").
- Real Fishing operational acceptance is still blocked by the account identity mismatch between the
  ChromaScape catalog (`3720a9a3b5c9f57b`) and the executor's canonical hash (`b01c7f006bace664`).
  The fail-closed `WAITING_FOR_ACCOUNT` behavior is correct and is not bypassed.

## Evidence commands

ChromaScape:

```bash
./gradlew --no-daemon compileJava compileTestJava test
npm test
./gradlew --no-daemon diffCheck
```

Micro6 and operational acceptance (Windows only):

```cmd
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat --no-daemon --no-watch-fs :client:test
gradlew.bat --no-daemon --no-watch-fs :client:prepareBuilderGoal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File launch-buildergoal-client.ps1 -CompileOnly

cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat --no-daemon compileJava compileTestJava test
npm test
scripts\restart-chromascape.bat
```

## ChromaScape production changes

Builder Goal dispatch hardening (SC-964, linked to SC-572/SC-574):

- Account-hash authority resolution and fail-closed readiness
  (`AccountIdentityContract`, `Micro6ExecutorReadinessEvaluator`).
- Expected-world selection and launch/recovery path wiring
  (`BuilderGoalRunnerService`, `BuilderGoalLauncherControlService`).
- Compile-before-stop contract and process ownership
  (`Micro6TargetRelaunchService`, `Micro6TargetPreparationService`).
- Identity-verified duplicate attachment and status reconciliation
  (`BuilderGoalRunStateMutationService`, `Micro6OnlineClientRegistryService`).
- Script execution, analysis V1/V2 contract reconciliation, and shadow-history
  forward compatibility (`ScriptExecutionService`, `AnalysisV1Validator`,
  `AnalysisV2Validator`, `FishingShadowDecision`).

## ChromaScape test changes

- `AccountIdentityContractTest`, `BuilderGoalLauncherControlServiceTest`,
  `Micro6OnlineClientRegistryServiceTest`, `Micro6TargetRelaunchServiceTest`,
  `Micro6TargetPreparationServiceTest`, `BuilderGoalRunnerServiceTest`,
  `BuilderGoalRunStateMutationServiceTest`, `ScriptExecutionServiceTest`,
  `AnalysisV1ValidatorTest`, `AnalysisV2ValidatorTest`,
  `AnalysisV2ContractSerializationTest`, `PatchLoopAsyncWorkerTest`,
  `DebugSessionServiceTest`, `RecordingServiceInitialFrameReadinessTest`,
  `SimulatorReplayControlControllerTest`, `FishingShadowDecisionWriterTest`,
  `FishingShadowDecisionServiceTest`.
- JavaScript: `src/test/js/*.test.js` (140 passing).

## Micro6 changes

Implemented on Windows on local branch `sc-584a-health-contract`:

- `AccountHashGenerator` golden-vector tests for the `accountconnector/v1` scheme.
- Invalid/blank identity and placeholder names (e.g. `unknown_user`) return no hash; they are never
  published as a canonical identity and keep the executor in fail-closed readiness.
- Health and heartbeat publish `account_hash`, `account_hash_scheme`,
  `requested_account_hash`, login/startup/pathfinder/world, and launch attempt identity
  consistently.
- `PrimitiveIntHashMap.rehash()` fix and regression test (route failures).
- Launcher `-CompileOnly` preflight and `--launch-attempt-id` argument registration.

Not yet committed or pushed: these changes are on the local `sc-584a-health-contract` branch only.
GitHub `ikiddoi/micro6` default branch is stale; the `chsami/Microbot` upstream repository is
read-only for this account, so upstream delivery requires a fork branch and PR.

## Chsami upstream review (do not merge wholesale)

`chsami/Microbot/development` is CI-green at `846185d8`, but these PRs remain open and must be
reviewed and cherry-picked (not merged) before syncing:

- #1837: hardcoded reached distance, stale return documentation, whitespace, missing focused/live
  tests, incorrect base branch.
- #1838: broad walker overhaul.
- #1839: transport/collision data refresh.
- #1840: upstream planner source.

## ChromaScape full Java gate

- Focused Builder Goal suite: 93 passing.
- JavaScript: 140 passing.
- Full `gradlew.bat --no-daemon compileJava compileTestJava test`: 1,409 tests, 44 failures,
  16 skips (RED). The failures are outside the focused remediation suite and must be fixed by root
  cause or explicitly reviewed and isolated before claiming release completion:
  - Spring profile/configuration bootstrap (auth interceptor requires test credentials; integration
    contexts must use the `test` profile).
  - Outdated Mockito stubs, stale manifest-hash fixtures, and formatting-sensitive JSON assertions.
  - Shadow JSONL reader/writer mismatch including forward-compatible fields such as
    `control_allowed`.
  - Analysis/simulator validator and contract drift.

## Current blockers

1. Real Fishing operational acceptance is blocked by the account identity mismatch (catalog hash
   `3720a9a3b5c9f57b` vs executor canonical hash `b01c7f006bace664`). Resolution is one of:
   - correct the catalog account record to the executor's canonical hash; or
   - launch the account that really owns `3720a9a3b5c9f57b`.
   Hash matching must not be bypassed with the account name.
2. The ChromaScape full Java gate remains RED at 44 failures. No Shortcut story may be marked Done
   or In Review on the basis of the focused suite alone.
3. Windows operational acceptance (pause/resume/stop, no-duplicate-executor relaunch, real
   `FISHING_TRAINING` publication with `{}` and non-empty settings) still requires a
   matching-identity account on the Windows host. Evidence must be captured under this repository
   (`docs/evidence/...`), not only `/tmp`.

## Shortcut status

- SC-572, SC-574, SC-189, SC-964 remain open until the real Fishing run and cleanup evidence exist.
- SC-671 remains blocked.

## 2026-08-16 continuation hardening (static review only)

The following post-verification gaps were corrected without claiming a new build or operational
result:

- `Micro6OnlineClientRegistryService` now treats `runnable`/`dispatchReady` as generic canonical
  executor readiness, keeps selected-account hash equality in `selectedAccountEligible` and
  dispatch lookup, rejects malformed identities even on name fallback, and uses observed
  requested/current world values rather than a stale `world_matched` heartbeat flag. Registry
  fixtures now include the fail-closed identity, launch, and process fields.
- `CatalogV2Publication.validateSettings` and ChromaScape catalog validation now enforce required
  fields for an actual empty settings envelope, `text`/`enum`/`multi_select` types, non-empty
  string-only multi-select values, and the active `options_by_dependency` branch. Schema-default
  validation does not incorrectly reject a required field that has no default.
- `ScriptExecutionService` accepts `builder_goal_iteration_id` as the conflict identity fallback;
  Micro6 promotes that field to transport `iteration_id` before idempotency and ownership checks.
- Micro6 `RunControlHandler` rejects pause/resume for terminal records before mutating the global
  task manager or dispatch hold, while retaining stop/reconciliation for paused entries.
- `Micro6TargetRelaunchService` carries the requested hash into the ready `TargetClient`; the
  checkstyle configuration had merge markers removed and line endings normalized, and the
  malformed `runelite-gradle-plugin/pmd-ruleset.xml` merge artifact was restored to a valid PMD
  ruleset.

Only `git diff --check` and exact merge-marker scans were run in this continuation. The native
Windows Gradle/launcher gates and real-account Fishing acceptance must be rerun after these edits;
no new evidence is being implied by this section.

## 2026-08-16 local contract continuation

The following additional implementation gaps were closed in the ChromaScape working tree:

- `BuilderGoalRunnerController` no longer injects an empty `settings` object into legacy raw
  `/run` payloads. Explicit settings objects (including `{}`) remain authoritative, while raw
  settings reach `BuilderGoalRunnerService.normalizePayload()` for reserved-field stripping.
- Legacy settings filtering now excludes the remaining controller transport fields, including
  `displayName`, `allowLaunchFallback`, `forceRelaunch`, `builderPayload`, and evidence-control
  aliases, so they cannot become task configuration.
- `ScriptExecutionService.getMicro6RunStatus()` validates persisted canonical `task_key`, exact
  `entry_id`, and exact iteration identity when supplied. Missing identity returns
  `RECONCILING`; confirmed identity mismatches are surfaced to duplicate attachment as
  `DUPLICATE_OR_CONFLICTING_DISPATCH`. Generic callers with no persisted identity remain
  compatible, and receipt parsing retains the task-name fallback.
- Regression coverage was added for legacy `/run` forwarding and matching, missing, and
  mismatched status identities.

Safe local verification for this continuation: `node --test src/test/js/*.test.js` passed 152/152,
and scoped `git diff --check` passed. No Java/Gradle, launcher, restart, or operational command
was run in WSL; those gates remain Windows-host requirements.

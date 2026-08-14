# ChromaScape–Micro6 Final Gap-Closure Remediation Manifest

Created: 2026-08-14

## Repository scope

- ChromaScape: this repository (`chromascape`), production and test changes are permitted only in the focused files listed below.
- Micro6: the configured sibling path is `C:\Dev\IdeaProjects\micro6`, but that repository is not available under this execution root. No Micro6 files will be edited or represented as verified in this pass.
- Existing dirty worktree changes are pre-existing and must remain untouched unless a listed file is directly required for this remediation.

## ChromaScape intended production scope

- `src/main/java/com/chromascape/web/debug/buildergoal/service/AccountIdentityContract.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6CanonicalHash.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalLauncherControlService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalAccountCatalogService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6ExecutorReadinessEvaluator.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6OnlineClientRegistryService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6TargetRelaunchService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6TargetPreparationService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunStateMutationService.java`
- `src/main/java/com/chromascape/web/debug/patchloop/service/ScriptExecutionService.java`
- `src/main/resources/static/js/account-task-planner.js`
- `src/main/resources/static/js/patchloop.js`

Files outside this list require an additional scope note before editing. The list may be narrowed after audit.

## ChromaScape intended test scope

- `src/test/java/com/chromascape/web/debug/buildergoal/service/AccountIdentityContractTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalLauncherControlServiceTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6CanonicalHashTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6ExecutorReadinessEvaluatorTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6OnlineClientRegistryServiceTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6TargetRelaunchServiceTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6TargetPreparationServiceTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerServiceTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunStateMutationServiceTest.java`
- `src/test/java/com/chromascape/web/debug/patchloop/service/ScriptExecutionServiceTest.java`
- `src/test/js/account-task-planner.test.js`
- `src/test/js/patchloop.test.js`

## Micro6 required scope (blocked until repository is available)

- AccountHashGenerator and shared golden-vector tests/resources.
- Launch argument, health, heartbeat, lifecycle, scheduler, duplicate conflict, PAUSED, and pathfinder/game-data production/test/resource files.
- Exact Windows gate outputs for `:client:test`, `:client:prepareBuilderGoal`, and `launch-buildergoal-client.ps1 -CompileOnly`.

## Evidence commands

ChromaScape (available environment):

```bash
./gradlew --no-daemon compileJava compileTestJava test
npm test
./gradlew --no-daemon diffCheck
```

Micro6 and operational acceptance (Windows only; not runnable from this environment):

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

## Evidence locations

- Focused and full test output: to be recorded here after execution; stable project-owned logs may be added under `docs/evidence/final-gap-closure/` if available.
- Operational evidence: must be captured on Windows under a stable project-owned path or attached to the delivery record; no `/tmp`-only evidence is accepted.

## Current blockers

1. The Micro6 repository is unavailable in this execution root, so cross-repository implementation and required Windows gates cannot be completed or verified here.
2. Windows operational acceptance cannot be run on the Linux agent.
3. Any remaining pre-existing ChromaScape failures are still gate failures until deterministically fixed or explicitly reviewed.

## Implementation result (this pass)

### ChromaScape changed production files

- `src/main/java/com/chromascape/web/debug/buildergoal/service/AccountIdentityContract.java` (new)
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalLauncherControlService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalAccountCatalogService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunnerService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalRunStateMutationService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6OnlineClientRegistryService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6TargetPreparationService.java`
- `src/main/java/com/chromascape/web/debug/buildergoal/service/Micro6TargetRelaunchService.java`
- `src/main/java/com/chromascape/web/debug/patchloop/service/ScriptExecutionService.java`
- `src/main/resources/static/js/account-task-planner.js`

### ChromaScape changed test files

- `src/test/java/com/chromascape/web/debug/buildergoal/service/AccountIdentityContractTest.java` (new)
- `src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalLauncherControlServiceTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6OnlineClientRegistryServiceTest.java`
- `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6TargetRelaunchServiceTest.java`
- `src/test/js/account-task-planner.test.js`

### Evidence

- `node --test src/test/js/*.test.js`: PASS, 140 tests.
- Scoped `git diff --check` over the remediation files: PASS.
- `./gradlew --no-daemon compileJava compileTestJava`: NOT RUNNABLE in this Linux agent; configured JVM is Java 11 while the project requires Java 17 (`spring-boot-gradle-plugin:3.5.3` rejected the runtime).
- `npm test`: BLOCKED because this repository has no `package.json`; the equivalent available Node test command above passed.
- Micro6 Windows gates and ChromaScape Windows operational acceptance: NOT RUNNABLE here; no Micro6 sibling checkout or Windows runtime is available.

### Micro6 changed files

- None; the Micro6 repository is outside the available project root and was not edited.

### Remaining blockers

- Cross-repository AccountHashGenerator/golden-vector implementation, Micro6 health/heartbeat/launcher producer changes, lifecycle/game-data fixes, and all required Windows gates remain outstanding until the Micro6 repository is available on Windows.
- ChromaScape Java compilation/tests must be rerun with Java 17+; no completion claim is made from this Linux pass.

## Post-implementation update

Windows verification is now available and has been recorded below. The historical Linux-only notes above remain as the original audit snapshot.

### Windows verification (2026-08-14)

- Micro6 `:client:test`: PASS — 949 tests, 0 failures, 4 skipped.
- Micro6 `:client:prepareBuilderGoal`: PASS — `[buildergoal] preparation-complete`.
- Micro6 `launch-buildergoal-client.ps1 -CompileOnly`: PASS — `phase=SUCCESS` (the launcher emitted only its non-fatal Java-version query warning).
- ChromaScape `BuilderGoalRunnerServiceTest`: PASS — 93 tests, including canonical account-hash-scheme fixtures and cached/live catalog lookup coverage.
- ChromaScape `npm test`: PASS — 140 tests.
- ChromaScape full `compileJava compileTestJava test`: compile PASS; test gate remains RED at 1,409 tests with 44 failures and 16 skips. The failures are outside the focused remediation suite (context/bootstrap, simulator, shadow-history, analysis-contract, and unrelated test-seam drift); they must not be represented as a full-release pass.

### Micro6/Chsami pathfinder finding

GitHub `chsami/Microbot` `main` and `development` collision maps are byte-identical to Micro6 and contain the same missing Isle-of-Souls region set, so no map resource was copied. The confirmed route failure was instead caused by `PrimitiveIntHashMap.rehash()` returning after the first grown bucket and silently dropping later packed transport entries. The fix removes that premature return and adds `PrimitiveIntHashMapTest`; the three Isle route tests pass on Windows after the fix.

### Current blockers

- Real Fishing operational acceptance is still blocked by the known account identity mismatch (catalog hash `3720a9a3b5c9f57b` versus the actual executor hash `b01c7f006bace664`). The fail-closed `WAITING_FOR_ACCOUNT` behavior is correct; publication must not be forced.
- The ChromaScape full Java test gate remains blocked by the 44 unrelated failures listed above. No Shortcut story may be marked Done/In Review on the basis of the focused suite alone.
- Windows pause/resume/stop and real `FISHING_TRAINING` publication evidence still require a matching-identity account on the Windows host.

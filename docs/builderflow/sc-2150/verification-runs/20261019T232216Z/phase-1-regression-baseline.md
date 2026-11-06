# SC-2150 Phase 1 — automated regression baseline reproduction

Run ID: `20261019T232216Z`
JDK: `/home/ikiddoi/.jdks/corretto-17.0.20.1` (17.0.20.1+10-LTS)
Command (verbatim):

```sh
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew test --rerun \
  --tests 'com.chromascape.web.debug.tickdebugger.video.*' \
  spotlessCheck checkstyleMain checkstyleTest \
  -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json --offline --console=plain
node --test src/test/js/mouse-video.test.js
python3 -m unittest discover -s src/test/python -p 'test_mouse_video*.py'
```

Static checks were additionally re-executed with `--rerun-tasks` because the first
invocation reported `spotlessCheck`/`checkstyleMain`/`checkstyleTest` as `UP-TO-DATE`
(no reports regenerated). The rerun executed all 9 tasks.

## Results

| Gate | Expected | Observed | State |
|---|---|---|---|
| Java tests | 123 | **123** (22 suites) | PASS |
| Java skipped | 0 | **0** | PASS |
| Java failures / errors | 0 / 0 | **0 / 0** | PASS |
| PostgreSQL tests | 15 | **15** | PASS |
| JavaScript | 6/6 | **6 tests, 6 pass, 0 fail** | PASS |
| Python | 2/2 | **Ran 2 tests, OK** | PASS |
| spotlessCheck | pass | **BUILD SUCCESSFUL**, no violations | PASS |
| checkstyleMain | 0 errors / 4552 warnings | **0 errors / 4552 warnings** | PASS |
| checkstyleTest | 0 errors / 694 warnings | **0 errors / 694 warnings** | PASS |

Gradle exit `0` (`BUILD SUCCESSFUL in 2m 30s`); static rerun exit `0`
(`BUILD SUCCESSFUL in 1m 48s`).

## PostgreSQL was real, not skipped

Three distinct Testcontainers instances of `postgres:16` started and reached
`Container is started`, each reporting `PostgreSQL 16.15`:

- `MouseVideoMigrationTest` — `jdbc:postgresql://localhost:32783`, container `50c5c3411643`
- `MouseVideoPostgresConcurrencyTest` — `jdbc:postgresql://localhost:32784`, container `461a3d661100`
- `MouseVideoServicePostgresRaceTest` — `jdbc:postgresql://localhost:32785`, container `87eb81cf6978`

15 PostgreSQL cases = 4 (migration) + 2 (concurrency) + 9 (service races). No DB test was
skipped or mocked.

## Named case verification

All cases below executed and passed (`build/test-results/test` → `reports/junit-xml`).

Process containment / session (9/9 `MouseVideoProcessRunnerTest`):
`unavailableContainmentRefusesBeforeLaunchingTheRequestedCommand`,
`fastParentExitStillStopsOwnedChild`, `sessionChangingChildRemainsInsideTheContainment`,
`deathAssertionFailsWithoutKillingASurvivingProcess`, `cancellationKillsRootAndTrackedDescendants`,
`silentProcessHitsIndependentDeadlineAndIsCleanedUp`, `floodedOutputIsBoundedAndDoesNotDeadlockCleanup`,
`delayedConsumerReceivesEveryLineBeforeReturn`, `callbackFailureIsExplicitAndNeverReportedComplete`.

All 9 PostgreSQL service races (`MouseVideoServicePostgresRaceTest`): startup-waiting
heartbeat, heartbeat↔progress both orders, cancellation fences finalization, heartbeat +
finalization commit one analysis, expiry reconciliation preserves healthy ownership,
expired claim fences waiting heartbeat, replacement wins against stale reconciliation,
replacement cancellation cannot be settled by the old execution.

Stale ownership / replacement fencing (`MouseVideoJobServiceTest`, 20/20) includes
`staleExecutionCannotReconcileAReplacementGeneration`,
`staleExecutionCannotConfirmCancellationOfAReplacement`,
`replacementWinningTheLockRefusesStaleWorkerReconciliation`,
`startupLeavesAnotherInstancesLiveLeaseUntouched`.

Lease heartbeat (`MouseVideoLeaseHeartbeatTest`, 6/6) includes
`renewsLeaseIndependentlyAcrossAStageLongerThanOneInterval` and
`reconciliationRequiredStopsTheWorkerInsteadOfAssumingSettlement`.

Timebase / timing integrity (`MouseVideoDerivativeWorkerTest`, 22/22) covers all 13 invalid
timebase forms (`null`, `0/0`, `1/0`, `0/1000`, negatives, `garbage`, empty, `1`, `1/2/3`,
`1.0/2`, overflow) and 6 non-finite time forms, alongside the positive case
`finiteNamedTimingWithPositiveRationalTimeBaseRemainsComplete` and
`nonIntegralPtsIsIncompleteAndSerializedAsJsonNull`.

## Evidence retained

- `logs/gradle-phase1.log`, `logs/gradle-static.log`, `logs/node-phase1.log`, `logs/python-phase1.log`
- `reports/junit-xml/` (22 suite XMLs), `reports/junit-html/`, `reports/checkstyle/`
- Baseline manifest `docs/builderflow/sc-2150/remaining-review-verification.json` left untouched

## Disposition

VA2: **PASS**. Baseline reproduced exactly; no application source was modified.
Remaining Phase 1 items (real-process containment against a live instance, manual process
liveness observation) roll into Phase 2.

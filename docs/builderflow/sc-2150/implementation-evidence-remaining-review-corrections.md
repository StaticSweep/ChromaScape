# SC-2150 Implementation Evidence — Remaining review corrections

Canonical project: `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69`, confirmed `in_progress / draft`.
Felix evidence note: `note_1792450504659_743eeeee`.
Shortcut SC-2150: confirmed workflow state `500000008` (In Progress); no transition, comment or synchronization marker emitted.

## Scope and exclusions

This increment implements the four findings from the latest independent review, repairs the PostgreSQL startup/expiry snapshot race exposed by new tests, and corrects an existing migration-test fixture. The full story and prior P5–P10 obligations remain in force. No schema, application configuration, feature flag, deployment or restart was changed. Windows containment, browser/operator acceptance, held-out cursor qualification, approved public YouTube acquisition and native regressions remain outside this increment.

## Linked rules and plan

- `C:\Dev\IdeaProjects\AGENTS.md` and `C:\Dev\IdeaProjects\ChromaScape\AGENTS.md`.
- Existing task-linked rules and authoritative completion plan `note_1792422547746_2a3a30c8`.
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\completion-plan-after-independent-review.md`.
- The pending outbox was scanned oldest-first. All 20 records are already marked conflict for other stories. No SC-2150 pending record exists. Those conflicts and their payloads were preserved without overwrite or replay.

## Implemented plan and acceptance results

- [x] Given unavailable Linux containment, when a tool is requested, then `CLEANUP_UNCONFIRMED` is raised before that command starts. Removed the process-group fallback entirely; Linux requires a systemd user scope backed by cgroup v2. Added a deterministic unavailability regression. The previous escaped-session reproduction now refuses launch and produces no child PID file.
- [x] Given a replacement generation or foreign lease owner, when a stale worker reports cleanup or cancellation settlement, then the locked execution identity check refuses the mutation. Both reconciliation and cancellation settlement carry the original generation, cancellation revision and lease owner. Cancellation of the original claim may advance generation/revision together; a new claim cannot regain the old worker's rights. Unit tests and real PostgreSQL contenders verify replacement rows remain running/cancelling with their leases intact.
- [x] Given missing, malformed, zero or negative rational timebases, or non-finite/non-integral timing fields, when frame evidence is built, then it is not complete and numeric JSON remains valid. Invalid timebases/PTS values serialize as null. Valid positive rational timebases and named integer PTS remain complete. The previous `0/0` reproduction now returns `complete=false` and `timeBase=null`.
- [x] Given a surviving child, when the process-test death assertion runs, then it fails before teardown kills anything. Removed killing from the wait/assertion helper and moved emergency cleanup to teardown. A live-process regression proves the assertion fails while the survivor remains alive. Real fast-parent and session-changing-child tests pass with the corrected helper.
- [x] Given overlapping healthy heartbeat/progress/startup/expiry transactions, when PostgreSQL serializes them, then no benign version conflict fails the job. Reconciliation now selects ordered candidate IDs without preloading managed entities, and reads each current entity under its row lock. Nine tests call actual transactional Spring/JPA services and confirm the contender is waiting on a PostgreSQL lock before release.
- [x] Given the reservation uniqueness test, when duplicate insertion is asserted, then a first reservation has actually been inserted. Corrected the previously skipped fixture; all migration assertions now run against PostgreSQL.

## Changed files

- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoProcessRunner.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoImportService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoDerivativeWorker.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\entity\MouseVideoJobRepository.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoProcessRunnerTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobServiceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoImportServiceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoDerivativeWorkerTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoMigrationTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoServicePostgresRaceTest.java`

Documentation and machine-readable evidence added:

- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\implementation-evidence-remaining-review-corrections.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\remaining-review-verification.json`

## Tests

Actually run on Fedora with JDK 17.0.20.1+1:

```sh
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 ./gradlew test --rerun \
  --tests 'com.chromascape.web.debug.tickdebugger.video.*' \
  spotlessCheck checkstyleMain checkstyleTest \
  -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json --offline --console=plain
node --test src/test/js/mouse-video.test.js
python3 -m unittest discover -s src/test/python -p 'test_mouse_video*.py'
```

`JAVA_HOME` selected JDK 17 for Gradle. The Docker API override is a test-process prerequisite for this host; no build file or Docker daemon configuration was changed by this increment.

- [x] Final combined Gradle invocation: BUILD SUCCESSFUL; **123 selected, 123 passed, zero skips, zero failures/errors**.
- [x] **15 PostgreSQL tests executed**: four migration, two existing claim/reservation tests and nine new service contention tests. None skipped.
- [x] JavaScript: **6/6 passed**. Python: **2/2 passed**.
- [x] Full `spotlessCheck`, `checkstyleMain` and `checkstyleTest` passed. Checkstyle has warning-level output; this is not a warning-free claim.
- [x] Owned-source whitespace/newline check passed for all 11 changed Java files, including untracked files. `git diff --check` also passed for the scoped paths; it alone does not cover untracked files.
- [x] The earlier escaped-child and invalid-timebase reproductions were rerun against compiled production classes and now produce the required refusal/incomplete outcomes.

The nine PostgreSQL service races are:

1. Heartbeat holding the lock, then progress.
2. Progress holding the lock, then heartbeat.
3. Heartbeat followed by finalization: one successful analysis.
4. Cancellation followed by finalization: publication refused, no analysis.
5. Replacement claim followed by stale reconciliation: replacement unchanged.
6. Replacement cancellation followed by stale cancellation confirmation: replacement stays cancelling.
7. Expiry reconciliation followed by heartbeat: stale heartbeat refused.
8. Heartbeat followed by startup reconciliation: healthy ownership preserved.
9. Heartbeat followed by expiry reconciliation: healthy ownership preserved.

The fixture verifies PostgreSQL `pg_stat_activity.wait_event_type = 'Lock'` for the contender before releasing the first transaction. It uses actual repositories and service proxies, not handwritten substitute mutation SQL. Its Spring configuration is marked `TestConfiguration` to keep it out of unrelated application tests.

### Failures found and corrected during verification

The first run with Docker available selected 114 tests with no skips and exposed one failure: the reservation fixture expected duplicate insertion to fail without first inserting a reservation. After correcting that fixture, the initial 12-test PostgreSQL run exposed one real startup race: `ObjectOptimisticLockingFailureException` after startup had cached a job entity before waiting on heartbeat's row lock. Selecting ordered candidate IDs before loading locked entities corrected that race. The final 123-test run and subsequent final combined invocation passed.

Source SHA-256 values, suite timestamps, counts and Checkstyle severity totals are retained in:
`C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\sc-2150\remaining-review-verification.json`.

## Risks

- Linux now deliberately refuses work when a usable systemd user scope/cgroup-v2 facility is unavailable; there is no process-group downgrade.
- Windows retains the previously documented process-tree escape limitation and was not qualified by these Linux tests.
- A callback stuck in user code cannot be force-killed by the JVM; broader drain-timeout/read-error qualification remains open.
- The nine contention cases qualify their stated orderings. They do not establish every reverse ordering, database-outage case, multi-instance recovery scenario or admission/quota/sweep race.

## Rollback

Keep feature enablement withheld and stop admitting new video work if rollback is needed. Restore only this increment's source changes through a reviewed inverse patch; the video tree includes pre-existing untracked work and must not be deleted or reset wholesale. No DDL or application-configuration rollback is needed. Existing reconciliation-required jobs still need explicit cleanup/recovery; reverting code is not cleanup evidence.

## Remaining work

The four scoped review findings and the newly exposed startup/expiry snapshot race are corrected and covered by retained regressions. Full SC-2150 acceptance remains incomplete: original/proxy frame mapping corpus, cap/read-error/drain/resource isolation cases, held-out cursor/quality qualification, browser/accessibility/operator flows, approved pinned public YouTube import, native mouse/replay/stabilization regressions, remaining database recovery/admission/quota cases and feature enablement. Historical plans/checklists/evidence remain intact.

Felix remains `in_progress / draft`. This increment does not satisfy the whole-story in_review/done handoff. Only a separate scoped completion checklist is reconciled against this evidence. Shortcut remains In Progress; no synchronization mutation was made.

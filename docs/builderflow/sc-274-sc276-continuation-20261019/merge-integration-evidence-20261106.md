# SC-274 — merge of main and integration evidence (6 November 2026)

W4 ran before W2 by owner go-ahead, so the capture and row 5 run on the final build. This updates
`full-plan-20261106.md`; where they differ, this file is current. Evidence root:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\merge-c29346b3\`.

## Changed files

- Merge `c29346b3` of `origin/main` (`6042ccf2`), 133 commits.
- `ScenarioManifestV2Compiler.java`: 7 real hunks, resolved by hand. Kept main's SC-2106 start-only
  path, `RecordedStart` and skill normalization; kept this branch's curation overrides, region
  identity, pinned-collision refusal and future-target rule. Collision order is captured value,
  pinned content revision, then the worker's collision model.
- `ScenarioManifestV2CompilerTest.java`: merged cleanly once line endings matched.
- `LocalReplayPrincipalConfig.java` and its test: union of both sides' URL patterns.
- `Sc274CompilerTransactionIntegrationTest.java`: main's two-argument filter constructor.
- `V71__sc276_native_outcome_artifacts.sql` renamed to `V88__…`, because it collided with main's
  `V71__sc2106_…`; git does not detect this. `V72` has no collision and stays. Precedent `cb06a545`.

Correction to the plan: the "3,255-line hunk" was a line-ending artifact. Main stores these files
with LF and the branch had CRLF. The compiler's main-side changes are SC-2106, not SC-2150.

## Tests

All on the merge commit, JDK 17, `--offline`, Windows host, Docker up.

| Lane | Result |
|---|---|
| Full `replayTest` with disposable PostgreSQL | 913 tests, 16 failed, 27 skipped |
| `Sc274CompilerTransactionIntegrationTest` | 21 of 21 passed |
| `debug.tickdebugger.*` filter (also matches main's `MouseVideo*`) | 665 tests, 15 failed, 16 skipped |
| Refusal stability, two fresh JVMs | byte-identical; 9 cases match `wp1\refusal-a.json` on code and message |

- The 16 `replayTest` failures match `wp0\baseline-failures.txt` on name and reason. The 10
  Docker-down failures of 3 November are gone.
- `Sc274CompilerTransactionIntegrationTest` needs `SC274_TEST_JDBC_URL`. Without it all its tests
  skip, so a plain `replayTest` does not cover it, including the 3 November run.
- `XrspsProcessLaunchDiagnosticsTest` needs `NoDefaultCurrentDirectoryInExePath` unset. With the
  flag set it fails on `gradlew.bat` instead of the baseline reason.
- The 15 `MouseVideo*` failures are identical on pristine `6042ccf2` on this host: same 15 of 37
  tests, same reasons. They arrived with main and most are Linux-only process tests.

## Runtime

- 8090 is PID 167752 on `sha256:566a34dc6030cc46946692223201f524e66e6546e5a5c7e02ab71b662532ee80`,
  started by `redeploy\start-8090-merged-20261106.ps1`.
- Database `sc274_capture_merged` is a clone of `sc274_capture`. Only the clone's V71 history row
  was changed, to V88 with the same checksum. Flyway then applied 14 migrations out of order with
  0 failures, and Hibernate validation passed. `sc274_capture` is unchanged.

## Remaining work

- W0 to W3: host clock, lease, owner login, capture, verification, row 5 genuine half, output.
- Check the first session's capture identity for `client.worldId`. The merged server accepts it;
  whether the pinned client sends it is unverified, and SC-2273's `compileStart` requires it.
- A new branch for `RECORDER_TICK_TIMEOUT` and `RECORDER_UNAVAILABLE`, the last two start failures.
- PR #108 review. It has no reviews and no CI checks.
- Shortcut: SC-273, SC-712 and SC-1498 still block SC-274. D6 is open.
- Any other database that applied the old `V71__sc276` needs the same history-row change.

## Rollback

Stop 8090 and restart `redeploy\start-8090-candidate-20261103.ps1` with
`redeploy\pre-merge-20261106\chromascape-3b82cee0.jar` restored to `build\libs`; `sc274_capture` is
intact. Code: `git revert -m 1 c29346b3`.

# SC-1664 verification evidence

This matrix separates deterministic local verification from host/client-specific qualification.

| Verification | Result | Evidence |
|---|---|---|
| Integration base contains SC-1662 and SC-1663 | PASS | Base `cc9de8c7` |
| Java compilation | PASS | JDK 17 focused Gradle run |
| Focused input/RemoteInput suite | PASS | 65 tests, 0 failures, 0 errors, 0 skipped |
| Exact PID, lease-before-side-effect and stale-generation regressions | PASS | `RemoteInputSessionTest` |
| Timeout poison and cleanup ownership | PASS | session/executor/coordinator tests |
| Windows adapter geometry, fencing and cleanup | PASS | fake KInput backend tests |
| Repeated unit lifecycle | PASS | 25 fake-native open/close cycles |
| Platform compiled-reference isolation | PASS | `InputLifecycleIsolationTest` |
| Changed-file Checkstyle | PASS | no warnings in the SC-1664 candidate set |
| Whitespace | PASS | `git diff --check` |
| Combined SC-1662/1663/1664 + launcher gate | PASS | 213 tests, 0 failures, 1 Windows-only skip |
| Ordinary regression identity | PASS | candidate 2,401/110/14; base 2,383/110/14; no new failure identity |
| Replay regression identity | PASS | candidate and base both 280 tests / same 13 failures |
| Disposable live Linux attachment/input | NOT RUN | no qualified disposable client running |
| Real native 25-cycle lifecycle | NOT RUN | requires the same qualified client |
| Windows runtime verification | NOT RUN | deferred to the Windows host |
| PR review/merge | NOT RUN | follows completed local verification |

The ordinary task remains red because the exact base is already red for unavailable
Postgres/Testcontainers services, contract-fixture drift and Windows-only path execution. One
unchanged process-cleanup timing test failed in an earlier candidate run, passed on an isolated
rerun, passed on the exact base, and did not recur in the final full run. It is recorded as
nondeterministic, not silently counted as a feature pass.

No host-specific acceptance criterion is marked PASS without execution on that host.

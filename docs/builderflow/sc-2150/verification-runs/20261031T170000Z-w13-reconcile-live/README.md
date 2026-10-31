# W13 — owner reconciliation, live on the dev library instance, 2026-10-31

Build: `10b31688` (compiled classes snapshotted by `start_verification.py`), task-owned instance
127.0.0.1:18191, database `sc2150_library`. Live 8090/8098 untouched.

## Incident that motivated it

Job `6e505115` (Langsamt s1 `visual/v1`) lost its lease during 72 s of host thread starvation
(Hikari: `Thread starvation or clock leap detected (housekeeper delta=1m12s)`), and
`reconcileExpiredClaims` fenced it to `NEEDS_RECONCILIATION`. Two defects showed up:

1. Its in-JVM analysis kept running for about 12 minutes and held its `fuse2fs` job volume until
   publication refused it. Fixed in `fcbdddd7` (the analyzer stops on cancellation).
2. Nothing could resolve the row, so it would have held an admission slot forever. Fixed in
   `10b31688`.

## Live result

| step | result |
|---|---|
| `systemctl --user list-units 'chromascape-video-*'` before | 0 units |
| `GET /jobs/6e505115…` | `NEEDS_RECONCILIATION` |
| `POST /jobs/6e505115…/reconcile` | **200**, status `FAILED`, error code `INTERRUPTED` kept |
| repeat `POST …/reconcile` | **409** `CONFLICT` "Job does not need reconciliation" |
| event | `RECONCILED` `{"code":"INTERRUPTED","proof":{"workerRegistered":false,"workspacesMounted":[],"activeScopes":0,"heldScopes":0,"unattributedScopes":[]}}` |

The section's analysis had already been re-run under job `c51cce1b` (`SUCCEEDED`). That analysis
was `COMPLETE`, so reconciliation did not change it.

## Tests

`MouseVideoCleanupProofTest` 7/7, which covers each fail-closed condition: a registered worker, a
mounted workspace of any generation, an unattributed scope, unreadable scope state, plus the
listing and mountinfo parsing. `MouseVideoJobServiceTest` 32/32, including settlement, the state
guard and owner scoping. `MouseVideoControllerHttpTest` 20/20 (200 and 409). Video package:
328 tests, 0 failures. Spotless and checkstyleMain pass.

Not exercised live: the blocking paths, because no live job could be put into
`NEEDS_RECONCILIATION` with a scope still alive without inducing a fault. They are covered by the
unit tests above.

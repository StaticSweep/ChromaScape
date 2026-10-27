# SC-2106 recovery-lineage ownership fix — 2026-10-26

## Scope and implementation

Resolve the P1 review finding: a failed recovery B allowed C to continue B while D continued original attempt A. The immediate-parent V73 index alone permits that fork.

Every recovery reservation now resolves its original attempt and locks that row for the transaction. Under READ COMMITTED, the next holder sees the previous reservation after acquiring that lock. A recursive query checks the whole recovery family and rejects any RESERVED, RUNNING, COMPLETED or UNCERTAIN attempt outside the requested source's ancestor chain. An uncertain ancestor permits continuation through itself; sibling or descendant claims block an ancestor retry. Existing idempotency keys reattach before a new claim is considered. All writers using the JDBC repository participate in this protocol. V73 remains a database constraint on immediate siblings; raw SQL that bypasses the repository is not protected across the full lineage.

No new tables, columns or migration versions are required. The V73 change clarifies its existing immediate-sibling guarantee. Existing histories are read from persisted parent links, including FAILED intermediates.

## Changed files

- `src/main/java/com/chromascape/web/aiplayer/routine/service/JdbcCandidateAttemptStore.java`: original-attempt row lock, full-family claim check, explicit READ COMMITTED and idempotent reattachment.
- `src/main/java/com/chromascape/web/aiplayer/routine/service/CandidateAttemptStore.java`: documented recovery-lineage ownership contract.
- `src/main/resources/db/migration/V73__sc2106_one_recovery_per_attempt.sql`: comment clarification only, preserving its immediate-parent index.
- `src/test/java/com/chromascape/web/aiplayer/routine/service/InMemoryCandidateAttemptStore.java`: match the production lineage rule for service tests.
- `src/test/java/com/chromascape/web/aiplayer/routine/service/CandidateAttemptStoreIntegrationTest.java`: four real-PostgreSQL regressions for descendant claims, the reverse branch order, a 12-caller mixed-parent race and valid uncertain continuation.
- `src/test/java/com/chromascape/web/aiplayer/routine/service/CandidateExecutionJobServiceTest.java`: reject ancestor launch while its failed recovery's descendant runs and after that descendant completes; preserve same-key reattachment.

The pre-existing R6 fault-matrix work in both repositories is preserved. This fix changes only ChromaScape.

## Tests

Before the fix, the new service regression failed, and three of the four new PostgreSQL regressions failed (including the mixed-parent race). The fourth is a positive continuation control. Retained red XML is under `red/`.

Tests used Corretto 17.0.20.1, native Fedora paths, offline Gradle and a dedicated disposable PostgreSQL 16 container. Final retained green XML and command logs are under `green/`:

| Suite | Final result |
| --- | --- |
| SC-2106 targeted suite | 95 tests, zero failures/errors, one existing workflow-test skip |
| Managed PostgreSQL ownership | 12 passed, no failures/errors/skips |
| Real host integration | 2 passed, no failures/errors/skips |
| Real owner-process kill and explicit recovery | 1 passed, no failures/errors/skips |
| Total | **109 passed, one existing skip; zero failures/errors** |
| Spotless, scoped to the five changed Java files | Passed |
| Full main-source Checkstyle | Passed, zero error-severity findings; 4,437 warnings before final SQL line wrapping |
| Final Checkstyle on the two changed production Java files | Passed, zero error-severity findings; seven warnings |
| `git diff --check` | Passed |

The first host/owner invocation failed because this session supplied a relative worker directory. Both passed after the command used the absolute native XRSPS package path. This was a verification-command correction, not a source workaround.

The final source-only SQL line wrapping was checked against the exact query constants in the already-tested class file. Production compilation, the 95-test suite and all 12 PostgreSQL tests then passed again. The real-process host/owner checks passed on those same query constants. Micro6 code was unchanged by this fix, so its previously verified packaged/fault matrix was not rerun in this fix session.

## Acceptance results

- PASS: ancestor retry does not launch while a failed intermediary's descendant is active.
- PASS: ancestor retry remains blocked after that descendant completes.
- PASS: the reverse order blocks continuation of an older failed branch.
- PASS: twelve simultaneous reservations split between original and failed-descendant sources produce one winner; all eleven refusals identify that winner.
- PASS: uncertain recovery may itself be recovered, and retries under the winning key only reattach.

## Risks

Recoveries in one lineage serialize on its original database row; different lineages use different locks. A lock timeout fails rather than launching without a claim. Direct SQL writers must use the same protocol to obtain the full-lineage guarantee. A previously forked history is not automatically repaired; protected branches cause new competing reservations to be refused.

## Rollback

Reverting this repository fix restores the reviewed fork defect. No schema rollback or data deletion is needed. Disabling candidate execution is the operational fallback if the fix must be removed.

## Remaining work and tracker state

The remaining review finding is fixed and verified. This is not SC-2106 closure or live-client certification. The production key/approval, recording/live-client evidence, R7/R9 review and R10 story closeout remain outside this fix.

Existing Felix task `task_1792376349625_gr4dsa48` was read and reused as the task identity; no duplicate task, state transition or Shortcut comment was created. A historical SC-2106 Felix outbox record remains pending in micro6; this fix does not claim that replay or tracker synchronization succeeded. Changes are uncommitted.

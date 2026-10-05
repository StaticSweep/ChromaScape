# SC-666 request identity correction: plan and verification

Project: `C:\Dev\IdeaProjects\ChromaScape`. Story: https://app.shortcut.com/leeuwbot/story/666.
Existing Felix task: `task_1791140821195_h9b2usv9k`, user-reported Done. Felix and CI monitoring remain skipped by the standing user instruction. No duplicate task, Felix transition, synchronization marker, or Shortcut completion is claimed.

## Scope and exclusions

Fix all three concrete review blockers in the uncommitted SC-666 start implementation: identity-blind deduplication, terminal retry creating another attempt, and JVM lock ending before database commit. Preserve unrelated checkout changes. Include existing request forwarding and Codex rebinding changes in an isolated current-main branch. Add native PostgreSQL evidence rather than relying on mock visibility or null recording identifiers.

This correction does not certify the whole cross-repository SC-666 lifecycle as Done. Current story also requires canonical run/allocation identity throughout Micro6, recording confirmation and finalization, execution bundle/lease fences, durable cumulative ACK/outbox quality, safe-boundary interruption and live bootstrap authority. SC-798/SC-800 handoff prerequisites and live qualification are separate delivery gates. The legacy publish-only endpoint and Codex chat-session persistence are outside the reviewed start endpoints; no request-id guarantee is claimed for publish-only. Calls without a key retain legacy behavior. No gameplay run or existing service restart/reset is performed.

## Linked rules

Shared IdeaProjects delivery contract, repository AGENTS.md, current SC-666 description, SC-609 immutable provenance, existing BuilderGoalRunStateMutationService publish/reconciliation/stop transitions. No fabricated evidence, no manually edited Felix mirrors, no duplicate Felix task.

## Given / When / Then plan

1. Given a committed request key, when the same normalized task/settings/account/machine/world/provenance is submitted again, then return the original run including its current terminal outcome and bound session/recording IDs. Preserve the key permanently; explicit rerun remains the separate new-attempt API.
2. Given a committed key, when any execution-identity field changes, then return HTTP 409 / `REQUEST_IDENTITY_CONFLICT`, without creating or dispatching another run. Object key ordering and equivalent numeric encoding are canonicalized before server-side SHA-256 computation; display text is not execution identity.
3. Given two app instances or concurrent transactions starting the same key, when the first has not committed, then the second waits for database visibility and cannot create another run. Reserve, create and bind in one transaction; a foreign key references the actual iteration, and dispatch remains after-commit.
4. Given an aborted creation transaction, when retried, then no abandoned request/run survives and the retry can create one attempt. Given a lost response after commit, a new service/connection resolves the original attempt.
5. Given request-id aliases or a nested Builder payload, when forwarding a start, then preserve the ID without mutating the input JSON. Reject conflicting aliases, wrong types and oversized IDs before dispatch; Codex returns the same structured conflict response.
6. Given persisted non-null session and recording identifiers, when a fresh runner instance handles the retry, then preserve those identifiers and one iteration. Re-run existing publish reservation, late acceptance, stop-race and status reconciliation tests.

## Implementation checklist

- [x] V65 `builder_goal_start_request`: unique request key, immutable fingerprint and unique FK-bound iteration ID.
- [x] `BuilderGoalStartRequestService`: INSERT-on-conflict followed by FOR UPDATE; active transaction on the same datasource required; bind joins the creation transaction.
- [x] Remove JVM lock/history scan; use durable request binding for every keyed start, including terminal attempts.
- [x] Canonical identity digest and stable conflict; preserve current run summary and expose `terminal` on deduplicated responses.
- [x] Forward both aliases and nested IDs; validate types/bounds/alias agreement; propagate structured Codex errors.
- [x] Add real PostgreSQL and strengthened non-null binding regressions plus a hard-gated focused test lane.

## Changed files

- `build.gradle.kts`: `sc666CompletionTest` lane and exclusion of its explicit disposable-DB test from the ordinary suite.
- `debug/buildergoal/service/BuilderGoalStartRequestService.java`: durable request claims, fingerprints and validation.
- `debug/buildergoal/service/BuilderGoalRunnerService.java`: keyed starts and terminal outcome reuse.
- `debug/buildergoal/service/BuilderGoalCodexOrchestrationService.java`: request-aware overload.
- `debug/buildergoal/controller/BuilderGoalRunnerController.java` and `BuilderGoalCodexController.java`: request forwarding and stable errors.
- `db/migration/V65__sc666_durable_start_requests.sql`.
- Runner/Codex controller tests; RunnerService test; new CodexOrchestration and StartRequestPostgres tests.
- This plan/evidence record. Files above are relative to `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web` or the corresponding resources/test trees.

## Acceptance results

All three concrete review bugs are fixed and regression-covered. Changed task/settings/account/machine/world/provenance reject with 409. A terminal request retains its original attempt. Real PostgreSQL contenders cannot pass an uncommitted winner; rollback and new-connection lost-response recovery preserve exactly one durable binding. Non-null recording/session recovery and request alias/nested forwarding pass.

Broader SC-666 acceptance status is deliberately not substituted with these six corrective checks: canonical cross-plane allocation/lease identity, every lifecycle crash phase, recording-required RUNNING gate, finalized/checksummed training eligibility, ACK/outbox overflow/gap behavior and one-use live bootstrap authority still require their complete integration evidence. The existing 39 state mutation tests and 112 runner tests provide bounded evidence for reservation, late acceptance, stop races and status handling, not universal live recovery certification.

## Tests

Verified on native Fedora with JDK 21 as Gradle runtime and the repository's Java 17 compile/test toolchain:

`bash ./gradlew -I /tmp/sc666-spotless.gradle spotlessJavaApply sc666CompletionTest spotlessJavaCheck -Psc666TestJdbcUrl=jdbc:postgresql://127.0.0.1:15466/sc666`

BUILD SUCCESSFUL. XML: **218 tests, 0 failures, 0 errors, 0 skips**: RunnerService 112, RunnerController 51, CodexController 8, CodexOrchestration 3, state mutation 39, actual PostgreSQL request tests 5. Production compilation, scoped Google Java Format/Spotless and `git diff --check` pass. The temporary init script restricts formatting to changed Java files; it is not a build prerequisite.

Database tests ran in a dedicated rootless Podman PostgreSQL 16 container, on test port 15466, using test-only credentials. They create the actual V65 table against a minimal FK target and exercise real SQL, locks and transaction commit/rollback; they do not migrate the running application's database. The harness requires an explicit disposable `/sc666` database and is not silently skipped. An initial repeat run exposed non-repeatable schema setup; corrected it to recreate the request table only in this disposable test database, then reran all checks successfully.

Full suite, complete application Flyway/JPA boot migration, native process-kill restart in every lifecycle phase, Micro6 live dispatch and SC-1498 Fishing qualification are unrun. Existing review results (154 pass plus two expected failing reproductions) remain distinct from this branch's final results.

## Risks

V65 must be applied through the application's normal Flyway migration before keyed starts can run. Request bindings intentionally persist for terminal outcomes; deleting bindings weakens idempotency. Client keys must be stable per logical request. Different keys and explicit reruns represent different attempts; machine/account allocation constraints remain owned by the existing handoff. Legacy publish-only requests have no new keyed guarantee. Codex chat wrappers remain in-memory; create a new wrapper and rebind after restart. A database transaction abort before commit cannot enqueue this start; committed dispatch continues through existing after-commit reconciliation.

## Rollback

Revert application changes without dropping `builder_goal_start_request` or deleting stored run/recording evidence. The additive table is inert to older readers. Disable/reject keyed starts before rollback because older code cannot honor their identity contract. No automatic data reset or runtime restart is included.

## Remaining work and tracker reconciliation

Review/integrate this corrective branch and apply V65 on deployment. Complete the cross-plane/live SC-666 qualification once SC-798/SC-800 are implemented and verified. Reconcile the earlier Felix Done claim against the full current story when Felix work is authorized; do not post a done marker based only on this correction. Shortcut remains unchanged by this run.

## Fedora deployment follow-up (2026-10-05)

PR #57 merged as `2353219811eb924696624c5646369689a39b02c0`. A clean native Fedora deployment started through the repository wrapper; normal Flyway startup successfully applied V63, V64 and V65. The debugger returned HTTP 200. Existing dirty checkouts and application data were preserved.

A synthetic terminal request fixture exposed HTTP 500 on matching retries: strict source-binding lookup threw for absent optional evidence, its caller caught the exception, and Spring nevertheless marked the surrounding transaction rollback-only. No gameplay or recording was dispatched.

### Changed files

`SourceManifestBindingService.java` adds nullable optional lookup while retaining the strict evidence API. `BuilderGoalSourceBindingService.java` uses that optional read for run summaries. `BuilderGoalStartRequestPostgresTest.java` adds real Spring transaction proxy regressions proving absent optional evidence permits commit and strict lookup still rejects missing evidence.

### Tests

The same focused command above passed **220 tests, zero failures/errors/skips**, including seven actual PostgreSQL tests. Scoped Spotless passed. Runtime verification after deployment will be recorded separately; complete cross-plane/live qualification remains outstanding.

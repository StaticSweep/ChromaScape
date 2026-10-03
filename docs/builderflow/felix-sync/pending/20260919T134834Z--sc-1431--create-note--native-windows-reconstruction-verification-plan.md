---
schema_version: 1
offline_record_id: 1720f6fc-f66e-40c5-8735-54edde459829
sync_status: pending
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1431
shortcut_tag: 'shortcut:sc-1431'
felix_task_id: 'task_1788122221223_uphlga0o'
operation: create_note
target_note_id: null
title: 'SC-1431 native Windows reconstruction, verification, and delivery plan'
note_type: documentation
stable_tags:
  - shortcut:sc-1431
  - change:runtime
  - offline-sync:1720f6fc-f66e-40c5-8735-54edde459829
parent_id: null
entity_links:
  - entity_type: task
    entity_id: task_1788122221223_uphlga0o
    link_strength: primary
created_at: '2026-09-19T13:48:34Z'
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_marker: null
---

## Task resolution

Reuse historical Felix parent `task_1788122221223_uphlga0o` for Shortcut SC-1431 under workflow `shortcut_delivery`, canonical project `C:\Dev\IdeaProjects\ChromaScape`, source entity `shortcut_story:1431`, stable tags `shortcut:sc-1431` and exactly one classification `change:runtime`. Never create a duplicate. Felix repeatedly returned `Project not found` for exact historical task IDs after its list endpoint returned an inconsistent reduced index, so this note is queued under the outage protocol.

Scope, exclusions, architecture, acceptance criteria, implementation phases, complete Windows file manifest, commands, test checklist, evidence rules, merge gates, and rollback are the complete payload in:

`C:\Dev\IdeaProjects\sc1431-verification\luna-verification-plan.md`

That file is incorporated here by exact identity for replay. During replay, read its complete current contents and use them as the note body; do not summarize or substitute chat history.

## Note payload

# SC-1431 native Windows reconstruction, verification, and delivery plan

Outcome: reconstruct the already-developed SC-1431 implementation as a reviewable current-main-only change; verify its default-off, bounded, source-neutral AccountBuilder orchestration contract without gameplay or live delivery; merge only after every required gate passes; rerun on the exact merge commit; then mark SC-1431 Done.

Current truth: `origin/main` includes SC-1430 merge `ce697df4` but no SC-1431 package/V23/V32. The canonical checkout is heavily dirty on `codex/sc-1618-chromascape` and is source/evidence only. Historical `ebe60104` and mixed `3fad0c49` may be inspected but never broadly cherry-picked. Shortcut is In Review with historical 34/34 evidence but no SC-1431 PR. Use isolated ChromaScape 18091 and PostgreSQL 55451 while preserving live 8090/8098.

Scope: reconstruct only SC-1431 paths; reconcile merged SC-1430 handoff and SC-1449 contract; prove default-off, HELD/SHADOW zero-dispatch, one-use authority, immutable identity/digest binding, concurrency/idempotency, lease fencing, restart recovery, rollback atomicity, terminal immutability, and no blind retry; prove protected HTTP through a closed/deterministic fake gateway; deliver a dedicated PR and exact-merge verification.

Exclusions: no login/gameplay/Fishing, Micro6 mutation/restart/task call, live delivery, raw input, packets, reflection executor, provider retry/switch after uncertainty, unrelated story/migration edits, broad historical commits, whole-tree formatting, destructive git cleanup, or `wsl --shutdown`.

Production manifest under `C:\Dev\IdeaProjects\ChromaScape-sc1431-review-main\src\main\java\com\chromascape\web\gameplay\orchestration\accountbuilder\`: `AccountBuilderAuthorityVerifier.java`, `AccountBuilderOrchestrationController.java`, `AccountBuilderOrchestrationEntities.java`, `AccountBuilderOrchestrationException.java`, `AccountBuilderOrchestrationGateway.java`, `AccountBuilderOrchestrationModels.java`, `AccountBuilderOrchestrationProperties.java`, `AccountBuilderOrchestrationService.java`, `AccountBuilderOrchestrationStore.java`, `AccountBuilderOrchestrationValidator.java`, `ClosedAccountBuilderOrchestrationGateway.java`, `InMemoryAccountBuilderOrchestrationStore.java`, `JpaAccountBuilderOrchestrationStore.java`, `ProviderDecisionOrchestrationSource.java`; migrations `src\main\resources\db\migration\V23__sc1431_accountbuilder_orchestration.sql` and `V32__sc1431_recovery_correlation.sql`; SC-1431-only additions to `src\main\resources\application.properties`.

Test manifest under `...\src\test\java\com\chromascape\web\gameplay\orchestration\accountbuilder\`: `AccountBuilderOrchestrationConcurrencyTest.java`, `AccountBuilderOrchestrationPostgresConcurrencyTest.java`, `AccountBuilderOrchestrationRestartTest.java`, `AccountBuilderOrchestrationRollbackTest.java`, `AccountBuilderOrchestrationServiceTest.java`, `AccountBuilderOrchestrationTestRunner.java`, `AccountBuilderPostgresTestFixtures.java`, `JpaAccountBuilderOrchestrationStoreTest.java`, `Sc1431FlywaySchemaTest.java`. Merged dependency paths (`ProviderDecisionHandoff` and SC-1449 v2 package) are read-only absent a focused compatibility failure.

Contract: HELD/SHADOW persist evidence but never consume authority, reserve, dispatch, or reach Micro6. Delivery-capable structure is tested only through a deterministic fake. Lifecycle is `CREATED → VALIDATED → AUTHORIZED → RESERVED → DISPATCHED → OBSERVING → VERIFYING → terminal`; uncertainty becomes interrupted/reconciliation-required and never retries. The fingerprint binds all request/idempotency/account/client/environment/run/session/recording identities, decision/action/plan/skill/catalog/tool/knowledge/policy/verifier digests, revisions, authority, lease/fence, task settings, mode, and budgets. One authority authorizes at most one delivery; HELD/SHADOW consume none; authority commits before reservation; no DB transaction spans a gateway call. Every receipt matches reservation, generation, fence digest, identities, and lease. Normalized rows are authoritative; `view_json` is projection only. Terminal data is immutable.

HTTP base `/api/v1/gameplay/accountbuilder`: readiness, create, get, stop, reconcile. Require `X-Gameplay-Operator-Key` of at least 32 characters when enabled. Missing/invalid is 401; disabled mutation is stable 503; conflict/uncertainty is 409. Never expose credentials, transcripts, commands, raw input, or authority.

Acceptance criteria: manifest-only reconstruction; disabled starts no gateway; HELD/SHADOW zero authority/delivery; committed-only SC-1430 handoff with exact digests; identical concurrent requests share one durable owner; conflicting identity returns idempotency conflict; one-use authority race has one winner; stale fence/generation/revision/catalog/capability/identity/digest receipts fail closed; uncertain delivery recovers without retry; kill/restart at every durable nonterminal reconstructs conservatively; injected store failures commit no partial aggregate; terminal replay remains immutable; isolated HTTP statuses/redaction match; exact-merge verification repeats all focused passes.

Execution:

1. Preflight: read both AGENTS files; record remote/main/JDK/Gradle/Docker/worktrees/listeners/health; record canonical dirty state and manifest hashes; scan pending outbox. Gate: 18091/55451 free and canonical untouched.
2. Reconstruction: `git fetch origin main`; `git worktree add -b codex/sc-1431-current-main-review-20260919 C:\Dev\IdeaProjects\ChromaScape-sc1431-review-main origin/main`; reconstruct manifest path-by-path, integrate only required shared-file hunks, preserve migration immutability/ordering. Gate: manifest-only diff, compile and diff-check pass.
3. Static audit: controller→validator→service→store/gateway, default-off, closed gateway, HELD/SHADOW early return, atomic authority/identity, no transaction around gateway, normalized restart, immutable terminal, uncertain no-retry, no raw-input/live client.
4. Compile/tests: run `compileJava compileTestJava`; then `test --rerun-tasks --tests "com.chromascape.web.gameplay.orchestration.accountbuilder.*"`; parse JUnit XML and require zero failures/errors (historical minimum 34 tests; current reconstructed count is authoritative).
5. PostgreSQL: isolated PostgreSQL 16 at 127.0.0.1:55451; prove empty and upgrade migrations; constraints/indexes/Flyway checksums; JPA reload; identity/authority races; fence boundaries; rollback; receipt replay; normalized recovery; terminal immutability.
6. Integration: consume only committed SC-1430 DECIDED fixture; preserve digests; reject nonterminal/uncommitted. Exercise SC-1449 via deterministic fake only for mismatched catalog/capability, ok=false, thrown failure, stale readiness, replacement rejection, unavailable delivery, normalized receipts, and no live credentials/network.
7. HTTP: isolated 18091 with isolated DB, dummy stabilization tokens, orchestration enabled, live delivery disabled, strong key, closed/fake gateway. Test auth, readiness, second-start disabled mode, HELD/SHADOW create/get/stop/reconcile, duplicate/conflict/not-found/invalid/uncertain/unavailable, response secrecy.
8. Kill/restart: kill only 18091 PID at authorized/authority-consumed/reserved/dispatched/observing/verifying/reconciliation/terminal checkpoints; restart same DB; count fake invocations and require no duplicate after uncertainty.
9. Quality: focused format/Checkstyle without apply-all, diff-check, secrets, forbidden capability, migration review, relevant SC-1429/1430/1449 tests. Classify full-suite baseline only by reproduction on untouched current main.
10. Delivery: commit manifest only; reconcile latest main; push/open SC-1431 PR with exact evidence. Merge only if every required gate passes. A no-runner GitHub check may be superseded only by complete native evidence; real failures block.
11. Postmerge: detached worktree at merge commit; rerun compile and focused/integration/migration tests; confirm 8090/8098 healthy; stop 18091/55451; comment evidence and move Shortcut Done only after connector success.

Implementation checklist: preserve canonical/live services; clean current-main worktree; manifest-only reconstruction; forward-only V23/V32; default-off/closed gateway; HELD/SHADOW zero delivery; immutable validation order; one-use authority/identity; lease fencing; no gateway-spanning transaction; normalized restart; terminal immutability/uncertain no-retry; SC-1430 handoff; SC-1449 fake conformance; HTTP/restart; PR/merge; exact-merge and Shortcut sync.

Test checklist: Windows JDK17 compile; focused JUnit XML; empty/upgrade Flyway; JPA reload; concurrent identical/conflicting claims; authority race/non-consumption; fence/generation/expiry; receipt mismatch/replay; rollback injection; terminal immutability; committed-only handoff; fake conformance; HTTP auth/status/redaction; OS restart/no duplicate; contract regressions; diff/secret/capability/format; exact-merge rerun.

Evidence root is `C:\Dev\IdeaProjects\sc1431-verification\evidence\` with `00-preflight`, `01-reconstruction`, `02-static`, `03-unit`, `04-postgres`, `05-handoff`, `06-http`, `07-restart`, `08-quality`, `09-pr`, `10-postmerge`, `completion`. Every check requires timestamp, exact commit, command, result, and artifact. Defects get a focused failing test and smallest scoped fix. Anything requiring live gameplay, credentials, authority expansion, or unrelated changes blocks rather than weakens boundaries.

Rollback: before merge close PR/delete only isolated branch/worktree; after merge revert merge commit, never manually mutate shared migration history; stop/remove only isolated processes/container. Definition of done: dedicated current-main PR merged, exact-merge focused verification, Shortcut Done with evidence, live processes untouched, no required item unchecked.

## Checklist reconciliation

Do not mark implementation or verification items complete from this planning record. Preserve existing task checklists. Add/reconcile the implementation and test checklist items from the authoritative plan as unchecked unless later evidence proves each item. Existing 34/34 historical evidence is context, not a substitute for current-main reconstruction and exact-merge verification.

## State reconciliation

1. Resolve historical task by ID; if unavailable, search `shortcut:sc-1431` across the exact canonical project identity.
2. Do not create a new task when any matching historical/recovered task exists.
3. Create this plan note idempotently by offline-sync tag.
4. Preserve `spec_ready` and current review status; this planning operation requests no transition.
5. Future `in_review → done` requires Implementation Evidence and Verification Evidence plus all checked acceptance/test items.

## Shortcut reconciliation

No Shortcut transition or comment is intended by this planning-only operation. SC-1431 remains In Review. A Done transition is permitted only after merge and exact merge-commit verification. Do not emit a Felix synchronization marker until the corresponding Felix transition genuinely succeeds.

## Sync result

Pending. Felix transport/index resolution failed repeatedly with `Project not found`; no note creation, checklist write, or transition is claimed.

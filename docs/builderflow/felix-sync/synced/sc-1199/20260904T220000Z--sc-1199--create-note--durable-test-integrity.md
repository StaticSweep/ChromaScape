---
schema_version: 1
offline_record_id: offline_sc1199_20260904T220000Z_durable_test_integrity_4b7e1c20
sync_status: synced
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1199
shortcut_tag: 'shortcut:sc-1199'
felix_task_id: task_1789077614858_lhuqlht1
operation: create_note
target_note_id: note_1789077724884_d1b11e36
felix_note_id: note_1789077724884_d1b11e36
title: 'SC-1199 durable-delivery test-integrity hardening — implementation evidence'
note_type: documentation
stable_tags:
  - 'shortcut:sc-1199'
  - 'change:multi-repo'
  - 'replay:durable-delivery'
  - 'offline-sync:offline_sc1199_20260904T220000Z_durable_test_integrity_4b7e1c20'
parent_id: null
intended_entity_links:
  - entity_type: task
    entity_id: task_1789077614858_lhuqlht1
    link_strength: primary
  - entity_type: shortcut_story
    entity_id: '1199'
    link_strength: primary
created_at: '2026-09-04T22:00:00Z'
synced_at: '2026-09-10T22:04:07.8379951Z'
intended_felix_task_state: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: none — do not advance SC-1199; xRSPS playback/viewer/fixture/certification remain incomplete
intended_shortcut_idempotency_marker: none
---

## Task resolution

Resolve the existing Felix task `task_1788264505764_479zgp12` by stable tag
`shortcut:sc-1199` under canonical project `C:\Dev\IdeaProjects\ChromaScape`.
Never create a second task with that tag. Preserve all existing notes and
checklists. This record carries ONLY the durable-delivery test-integrity
hardening evidence (Micro6 `C:\Dev\IdeaProjects\micro6` + ChromaScape
`C:\Dev\IdeaProjects\ChromaScape`). It replays after (older-first than) the
`20260904T174000Z` reconcile record; the reconcile record must NOT direct
newer-first replay of this note.

## Note payload

# SC-1199 durable-delivery test-integrity hardening — implementation evidence

## Changed files

ChromaScape commit `5e20618` — "Certify durable tick batch receipt ingestion" (5 files, +1244):

- `src/test/java/.../integration/ReplayTickBatchReceiptIntegrationTest.java` (new)
- `src/test/java/.../integration/TickBatchDurabilityIntegrationTest.java` (new)
- `src/test/java/.../service/TickBatchCanonicalChecksumServiceTest.java` (new)
- `src/test/resources/tick-batch-fixture-manifest.json` (new)
- `scripts/certify-tick-batch-fixtures.sh` (new)

Micro6 commit `7f061b9` — "Harden durable tick batch recovery and checksums" (2 files, +261):

- `runelite-client/src/test/java/.../TickBatchCanonicalChecksumTest.java` (new)
- `runelite-client/src/test/resources/tick-batch-fixture-manifest.json` (new)

Uncommitted (worktrees intact, overlapping user work present — see Remaining work):

- Micro6 `ChromaScapeWebSocketClient.java` — trigger deferred recovery stop after the
  final valid ACK (`message.completion.complete(true); maybeSendRequestedRecoveryStop();`)
  plus a test-only `OutboundSendObserver` veto hook consulted in `drainOutboundQueue`
  before `sendText` (production null/no-op).
- Micro6 `ChromaScapeWebSocketClientTest.java` (+393/−0 baseline plus this review's
  additions) — deterministic queued-retry cancellation via the observer veto
  (retry provably held queued while the ACK drains; veto cleared after the ACK), and
  reconnect-attempt verification (`connectionAttempts()` dispatcher counter plus
  `unexpectedConnectionAttempted()` flag; the ACK-deadline test asserts 2 attempts, 2 opens,
  no third attempt). `DebugRecorderPluginTest.java` (reconnect-stop rewrite + 1
  unrelated hunk), 4 vector/fishing fixtures (untracked, hash-verified identical).
- ChromaScape `TickStreamIngestionServiceTest.java` (+293/−10),
  `TickStreamWebSocketHandlerTest.java` (+111/−14 baseline plus this review's rename
  `commitTimeFailureThroughHandlerSendsNoAcknowledgment` →
  `dataIntegrityFailureFromServiceSendsNoAcknowledgment` with an explicit
  mocked-service-only comment), 4 fixtures (untracked, hash-verified identical).

Latest uncommitted certification-readiness fixes (this review, NOT RUN):

ChromaScape:

- `src/test/java/.../integration/ReplayTickBatchReceiptIntegrationTest.java`
  — token-aware isolated-schema migration rewriting and Flyway-history assertions;
  JDBC-only naming (`jdbcInsertIfAbsentMirror...`, `jdbcDuplicateAndConflict...`).
- `src/test/java/.../integration/TickBatchDurabilityIntegrationTest.java`
  — complete evidence-set assertions (receipt/action/log/overlay/snapshot/marker),
  exact stored-action checksum equality, real race evidence via `addRaceEvidence`
  (identical `duplicate` evidence for the exact-duplicate race; distinct `A`/`B`
  evidence for the conflict race), and winner-identity readback proving the
  snapshot/log/overlay all came from the same winning batch.
- `src/test/java/.../websocket/TickStreamWebSocketHandlerTest.java`
  — handler suppression of ACK after persistence exceptions (success ACK,
  generic-failure no-ACK, and the renamed mocked-service data-integrity no-ACK
  test that explicitly disclaims real end-to-end commit coverage).

Micro6:

- `runelite-client/src/main/java/.../ChromaScapeWebSocketClient.java`
  — trigger deferred recovery stop after the final valid ACK.
- `runelite-client/src/test/java/.../ChromaScapeWebSocketClientTest.java`
  — deterministic queued-retry cancellation and reconnect-attempt verification.
- `runelite-client/src/test/java/.../DebugRecorderPluginTest.java`
  — retained ACK drainage before `SESSION_STOP`.

Deferred-stop failure closure (this review, on Micro6 HEAD `eb8409a50dfd`):

- Micro6 `runelite-client/src/main/java/.../ChromaScapeWebSocketClient.java`
  — `maybeSendRequestedRecoveryStop()` now fails closed: a deferred
  `SESSION_STOP` that fails, times out, is cancelled, or completes `false`
  schedules `terminalIncomplete("deferred SESSION_STOP delivery failed for
  session <id>: <reason>")` instead of leaving the stop silently retryable.
  The transition runs on the scheduler (never under `outboundLock` on the
  completing thread); fail-closed is entered synchronously so no replacement
  recording can slip in; `terminalIncomplete` is idempotent so a later deadline
  cannot overwrite the reason; a dedicated cleanup drops deadline-abandoned stop
  frames, decrements `queuedBytes`, clears `stopQueued`, and settles stop
  futures exactly once; a late successful stop send after terminal is logged as
  ambiguous evidence and cannot restore a local stopped state. The test-only
  `OutboundSendObserver` veto is now exception-safe (a throwing observer holds
  the frame queued without corrupting queue accounting).
- Micro6 `runelite-client/src/test/java/.../ChromaScapeWebSocketClientTest.java`
  — new `deferredRecoveryStopFailureBecomesTerminalIncomplete` (retained batch
  ACKed while the deferred stop is vetoed, stop deadline settles false,
  asserts `TERMINAL_INCOMPLETE`, fail-closed reason naming the session, session
  not marked stopped, cleared stop/queue state, settled stop futures, refused
  replacement session, and no stale stop sent by a later drain); dispatcher flag
  renamed `thirdConnectionAttempted` → `unexpectedConnectionAttempted` (applies
  to any upgrade beyond the configured count; atomic `connectionAttempts`
  counter remains the source of truth).

## Tests

New/updated tests, with standalone verification status where available:

- `ReplayTickBatchReceiptIntegrationTest` (7 tests: fresh V35→V37→V38, V35
  RESTRICT→cascade upgrade with row preservation, JDBC rollback,
  exact-duplicate race, same-tick conflict race, JDBC mirror duplicate/conflict
  races, JDBC winner verification): compile-verified only, NOT RUN
  (Testcontainers). The token-aware isolated-schema rewrite and Flyway-history
  assertions added after that compile check are NOT RUN.
- `TickBatchDurabilityIntegrationTest` (7 tests through the proxied
  `TickStreamIngestionService`: success evidence set, overlay-failure full
  rollback, tampered-action rollback, commit-time constraint rollback of all
  evidence through the Spring-proxied service, duplicate convergence, conflict
  winner, cascade delete): compile-verified only, NOT RUN. The post-compile
  edits in this review (exact stored-action checksum equality,
  `addRaceEvidence` fixtures, winner-identity readback, handler-test rename
  reference) are NOT RUN.
- `TickStreamWebSocketHandlerTest` (renamed
  `dataIntegrityFailureFromServiceSendsNoAcknowledgment` plus success ACK,
  generic-failure no-ACK, wire-identity and schema tests): latest edited version
  NOT RUN. Compositional coverage statement: the PostgreSQL service test
  (`TickBatchDurabilityIntegrationTest.commitTimeConstraintFailureRollsBackAllEvidence`)
  and the mocked handler test (`TickStreamWebSocketHandlerTest.dataIntegrityFailureFromServiceSendsNoAcknowledgment`)
  provide compositional coverage; they are not one end-to-end
  handler-to-database commit test.
- Micro6 `ChromaScapeWebSocketClientTest` / `DebugRecorderPluginTest` (deferred
  recovery stop trigger, veto-hook queued-retry cancellation, reconnect-attempt
  counters, retained ACK drainage, deferred-stop failure → terminal-incomplete):
  latest edited versions NOT RUN.
- `TickBatchCanonicalChecksumServiceTest` (12 tests): standalone 11/11 PASS on
  the pre-edit version; post-edit version compile-verified only.
- Micro6 `TickBatchCanonicalChecksumTest` (11 tests): standalone 10/10 PASS on
  the pre-edit version; post-edit production-checksum + manifest versions
  compile-verified only.
- `certify-tick-batch-fixtures.sh`: PASS — all 5 fixtures byte-identical
  across both checkouts.
- Native Windows `gradlew` gates: NOT RUN (Micro6 AGENTS.md forbids WSL
  builds; no native runner in this session).

## Remaining work

Retained from the prior session plus the 2026-09-05 certification-readiness
review (review-time Micro6 verification baseline `1fb1738a141e`, durability
ancestor `7f061b931a`; ChromaScape HEAD `5e2061817df7`). Execution-time Micro6
HEAD at this evidence update is `eb8409a50dfd` (branch `sc-572-remediation`);
native verification of the fixes below must run against the then-current Micro6
HEAD, not the `7f061b931a` evidence baseline:

1. Run the focused native Windows + Testcontainers gates for the review fixes:
   Micro6 `ChromaScapeWebSocketClientTest` (ACK-deadline byte-identical resend,
   queued-retry ACK cancellation, deferred-stop failure → terminal-incomplete)
   and `DebugRecorderPluginTest` (reconnect-stop ACK drainage); ChromaScape
   `ReplayTickBatchReceiptIntegrationTest` (isolated-schema V35→V37→V38),
   `TickBatchDurabilityIntegrationTest` (full evidence sets),
   `TickStreamWebSocketHandlerTest` (mocked data-integrity failure suppresses
   `TICK_BATCH_ACK` in the real handler).
   Report discovered/executed/passed/failed/skipped per class.
2. Commit the uncommitted test deltas hunk-by-hunk:
   - Micro6 `ChromaScapeWebSocketClientTest.java` is purely additive (+393/−0)
     and separable; the 4 fixtures are untracked and separable.
   - Micro6 `DebugRecorderPluginTest.java` contains one unrelated hunk
     (`finalizeHarnessSession` success expectation) that must be resolved with
     the owner before staging.
   - ChromaScape `TickStreamIngestionServiceTest.java` / handler test overlap
     with pre-existing unrelated modifications in 4 other test files; stage
     only the two scoped files after owner sign-off on the 10
     `processTickBatch` → `assertThrows` conversions.
   - Never `git add -A`; the both-repo worktrees contain 500+ unrelated
     modified files.
3. Verify the 75 ms-deadline Micro6 tests natively (MockWebServer timing) and
   tune thresholds if flaky.
4. Decide V37/V38 disposition from authoritative deployment history: local
   `chromascape` database (PostgreSQL 16) already applied V35 → V37 → V38 with
   CASCADE active and zero receipt rows. If V37 never shipped elsewhere, V38
   is unnecessary; it is currently retained as the guard for applied
   databases. Never edit V35.
5. Keep SC-1199 in progress: xRSPS playback, viewer, fixtures, and
   certification remain outstanding. No Shortcut transition from this record.

## Checklist reconciliation

No Felix checklist was modified by this record. Suggested deltas (apply only
during oldest-first replay, after the `20260904T174000Z` reconcile record):
leave recovery, migration, race, native-gate, and commit items UNCHECKED until
the focused native/Testcontainers gates below report PASS.

## State reconciliation

Intended Felix state remains `in_progress/spec_ready`. Do NOT transition to
`in_review` (requires Implementation Evidence + all implementation items
checked) and do NOT transition to `done`. Do not post any `[felix-sync:…]`
marker from this record.

## Shortcut reconciliation

No Shortcut mutation intended. SC-1199 must NOT advance on this evidence
record. No comment posted; no marker searched.

## Sync result

Pending. Created from the 2026-09-05 certification-readiness review: the
evidence headings above form the note payload. Replay oldest-first — after the
`20260904T174000Z` reconcile-task record — searching the
`offline-sync:offline_sc1199_20260904T220000Z_durable_test_integrity_4b7e1c20`
tag before creation, then move this file to `synced/sc-1199/`.

## Sync result — 2026-09-10 reconciliation

Synced as Felix note `note_1789077724884_d1b11e36`, linked to task
`task_1789077614858_lhuqlht1` and Shortcut story 1199. The complete note payload above was
preserved; task and Shortcut remain `in_progress`/In Progress and no new synchronization marker
was posted.

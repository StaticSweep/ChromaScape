---
schema_version: 1
offline_record_id: offline_sc1199_20260904T000000Z_durable_delivery_closure_9f3c2a71
sync_status: pending
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1199
shortcut_tag: 'shortcut:sc-1199'
felix_task_id: task_1788264505764_479zgp12
operation: reconcile_task
target_note_id: null
title: 'SC-1199 durable tick-batch delivery closure — partial completion, native gates blocked'
note_type: documentation
stable_tags:
  - 'shortcut:sc-1199'
  - 'change:multi-repo'
  - 'replay:durable-delivery'
  - 'offline-sync:offline_sc1199_20260904T000000Z_durable_delivery_closure_9f3c2a71'
parent_id: null
intended_entity_links:
  - entity_type: task
    entity_id: task_1788264505764_479zgp12
    link_strength: primary
  - entity_type: shortcut_story
    entity_id: '1199'
    link_strength: primary
created_at: '2026-09-04T17:40:00Z'
intended_felix_task_state: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: none — do not advance SC-1199; xRSPS playback/viewer/fixture/certification remain incomplete
intended_shortcut_idempotency_marker: none
---

## Task resolution

Resolve the existing Felix task `task_1788264505764_479zgp12` by stable tag
`shortcut:sc-1199` under canonical project `C:\Dev\IdeaProjects\ChromaScape`.
Never create a second task with that tag. Preserve all existing notes and
checklists. This record covers ONLY the durable tick-batch delivery closure
(Micro6 `C:\Dev\IdeaProjects\micro6` + ChromaScape
`C:\Dev\IdeaProjects\ChromaScape`). No xRSPS worker, viewer, fixture,
playback, or certification work was started.

## Note payload

# SC-1199 durable-delivery closure — partial implementation report

## Prompt-vs-reality corrections (verified this session)

1. **Gap 1 (Gson `parseString`) is NOT a gap.** Micro6 pins Gson 2.8.5 in
   `libs.versions.toml:56` and every checksum/transport/test call site already
   uses the compatible instance API `new JsonParser().parse(...)`:
   `TickBatchCanonicalChecksum.java:41`,
   `ChromaScapeWebSocketClient.java:1339,1433,1837,2570,2788`, all
   `debugrecorder` tests. `git grep parseString` on the scoped trees returns
   only a code comment. No Gson upgrade was made.
2. **Gap 5 (`BATCH_WIRE_IDENTITY_MISMATCH` unclassified) is NOT a gap.**
   `ChromaScapeWebSocketClient.java:2607-2615` already routes it to
   `failPendingBatch` → terminal-incomplete alongside the other four permanent
   codes. A new focused test now locks this in.
3. **Stop-during-reconnect was already substantially implemented** in both
   `ChromaScapeWebSocketClient.stopSession` (recovery path, durable
   `stopRequestedDuringRecovery`, deferred `SESSION_STOP` after ACK drain) and
   `DebugRecorderPlugin.stopChromaScapeSessionNow` (preserved rebind identity,
   `FINAL_FLUSH_PENDING`), with an existing plugin test
   `explicitStopDuringReconnectPreservesOriginalSessionRebind`. New client tests
   extend recovery/stop-deadline coverage.
4. **V37/V38 migration state:** both files exist as untracked new files
   (V35 is also untracked — the whole `src/main/resources/db/migration`
   directory listing shows V32–V38 as untracked, and tracked migration files
   V9/V10/V12/V27–V31 show large modifications). V37 converts the receipt FK
   to `ON DELETE CASCADE` with schema/table-specific lookup and drops the
   redundant index; V38 repeats the guard for already-applied V35/V37
   databases. V35 itself was not altered. No Flyway history was available to
   inspect (`flyway_schema_history` check not possible from this environment);
   native Windows must confirm before any migration edit.
5. **Canonical parity was already byte-identical** for the tested shapes. A
   standalone Java 17 differential harness compiled the exact committed
   `TickBatchCanonicalChecksum` (Gson 2.8.5) and
   `TickBatchCanonicalChecksumService` (Jackson 2.15.2) and compared them
   against an independent Python oracle: 27/27 batch vectors PASS on both
   implementations, action checksums PASS including null-`recordingId`,
   empty target/arguments, confidence `1`/`1.0`/`0.1`, and HTML-sensitive
   wire text.

## Changed files

Micro6 (`C:\Dev\IdeaProjects\micro6`, HEAD `1dc6880c33`):

- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/TickBatchCanonicalChecksumTest.java` (M) — expanded from 1 to 10 tests: Gson 2.8.5 parser-path test, 27-vector file-driven parity, decimal/zero equivalence, nested-order/array-order, surrogate rejection, fishing golden, action-vector commitment, cross-repo hash check.
- `runelite-client/src/test/java/net/runelite/client/plugins/debugrecorder/ChromaScapeWebSocketClientTest.java` (M) — +6 tests: wire-identity terminal, persistence-failure retryable, ACK-removes-retry, recovery-exhaustion settles batch+stop, typed snapshot without payload bytes, stop deadline. Bounded helper now passes injectable 1200 ms recovery/stop windows.
- `runelite-client/src/test/resources/tick-batch-canonical-vectors.json` (new, untracked) — 27 oracle-generated vectors.
- `runelite-client/src/test/resources/recording-action-checksum-vectors.json` (new, untracked) — 4 oracle-generated action vectors.
- `runelite-client/src/test/resources/tick-batch-fishing-golden.json` (new, untracked) — realistic 2-action Fishing batch, self-consistent checksum.
- Pre-existing overlapping modifications NOT touched: `ChromaScapeWebSocketClient.java`, `DebugRecorderPlugin.java`, `TickBatchMessage.java`, `TickBatchCollector.java`, `ChromaScapePlayerActionCapture.java` and their tests.

ChromaScape (`C:\Dev\IdeaProjects\ChromaScape`, HEAD `7ab2a80`):

- `src/test/java/.../service/TickBatchCanonicalChecksumServiceTest.java` (new, untracked) — 11 tests mirroring Micro6 plus root/nested checksum-field and no-double tests.
- `src/test/java/.../service/TickStreamIngestionServiceTest.java` (M) — checksum helper now uses `TickBatchCanonicalChecksumService` (was `CanonicalProjectionService`, which has different numeric behavior and must stay replay-only); +6 tests: wire-identity rejection, checksum mismatch, Micro6 action-vector match, log/overlay rollback, no-ACK-on-failure note.
- `src/test/java/.../websocket/TickStreamWebSocketHandlerTest.java` (M) — +3 tests: original-`JsonNode` passthrough, exact ACK schema, all-five permanent codes.
- `src/test/java/.../integration/ReplayTickBatchReceiptIntegrationTest.java` (M) — upgrade path now asserts V35 RESTRICT pre-state and runs V35→V37→V38 with index/constraint checks; race tests run the full chain.
- Same three fixture files as Micro6 (new, untracked, byte-identical SHA-256).
- No production source was modified. No migration file was modified.

## Tests

Standalone (this session, Java 17 `/usr/lib/jvm/java-17-openjdk-amd64`, Gson
2.8.5 + Jackson 2.15.2 from Gradle cache — NOT the native Windows gates):

- Micro6 `TickBatchCanonicalChecksumTest`: 10/10 PASS via JUnit launcher.
- Chroma `TickBatchCanonicalChecksumServiceTest`: 11/11 PASS.
- Chroma `ReplayRecordingActionIngestionServiceTest` (unmodified): 6/6 PASS.
- Chroma `TickStreamIngestionServiceTest`: 32/32 PASS (26 pre-existing + 6 new).
- Chroma `TickStreamWebSocketHandlerTest`: 37/37 PASS (34 pre-existing + 3 new).
- Differential harness: 27/27 oracle vectors byte-identical on both
  implementations; action wire parity (HTML-sensitive, null recordingId,
  empty maps, confidence forms) PASS; fishing golden PASS on both.
- Fixture re-verification with a fresh independent Python oracle after LF
  normalization: ALL-FIXTURES-VERIFY PASS; cross-repo SHA-256 identical
  (vectors `97d60b68…`, actions `d1e270c7…`, fishing `56e48b37…`).
- `git diff --check` on all edited scoped paths: PASS in both repos.
- PostgreSQL `ReplayTickBatchReceiptIntegrationTest` (5 tests, postgres:16-alpine
  Testcontainers): NOT RUN — the sandbox harness stalled pulling/starting the
  container and the scratchpad was lost on session restart before a verdict.
  Must run natively.
- Native Windows gates (`gradlew` Micro6 + ChromaScape suites): NOT RUN —
  Micro6 `AGENTS.md` forbids WSL compilation and no native Windows runner was
  available in this session.

## Remaining work

1. Run the native gates from Windows and report counts:
   Micro6 `:runelite-client:test` (4 focused classes, then full module);
   ChromaScape `test` (4 focused classes incl. the Testcontainers
   integration test), then `replayTest test check`.
2. Re-run the 6 new Micro6 client tests natively (MockWebServer timing).
3. Decide the `TickStreamIngestionServiceTest` questHelper218-line
   `service.processTickBatch(batch)` → `assertThrows` change: the 1-arg
   overload without `batchId`/checksum now throws by design; confirm intent.
4. Confirm Flyway history for V35/V37 before any migration edit; never edit
   V35; V38 already guards applied databases.
5. Commit per Phase 13 with exact-file staging only — currently BLOCKED (see
   collisions below). Do not `git add -A`.
6. Keep replay/worker/viewer/control flags disabled; `controlAllowed=false`
   preserved (no flag was touched).

## Commit collisions (do NOT commit until resolved)

- Micro6: `ChromaScapeWebSocketClientTest.java` (M) also carries unrelated
  user work (only +301 lines are mine; file has other overlapping hunks).
  `TickBatchCanonicalChecksumTest.java` + all four fixtures are untracked, so
  they can be staged independently — BUT the new checksum test cannot pass
  without the untracked `TickBatchCanonicalChecksum.java` main source, which
  belongs to the overlapping main-source change set. Report as blocker.
- ChromaScape: `TickStreamIngestionServiceTest.java` and
  `TickStreamWebSocketHandlerTest.java` (M) have 293+/111+ real added lines
  but their full-file diffs are dominated by pre-existing unrelated
  modifications (other modified test files:
  `TickReplayControllerTest`, `TickBatchMessageTest`,
  `DebugSessionServiceTest`,
  `TickStreamWebSocketHandlerSessionStartTest`). The two new test files and
  three fixtures are untracked and independently stageable. The
  `git diff --check` passes, but per instructions no file containing
  inseparable unrelated work was committed.
- No commit was created in either repository. Worktrees left intact.

## Risks

- New Micro6 client tests are timing-sensitive (MockWebServer + 75 ms
  deadlines); native Windows may need threshold tuning.
- The Testcontainers integration test was compile-verified only; runtime
  behavior (row counts, barriers, V35→V38 upgrade) is unverified.
- Overlapping uncommitted work in both repos means any future rebase/merge
  must be reviewed hunk-by-hunk; bulk staging would sweep in unrelated
  changes.
- `TickStreamIngestionServiceTest.applyBatchChecksum` behavior change
  (projection → protocol checksum) alters pre-existing test semantics;
  intended but needs owner sign-off.

## Rollback

All changes are additive test/fixture files plus new test methods. Rollback =
delete the three untracked fixture files per repo, `git checkout --` the four
modified test files (after extracting the new tests), and drop this outbox
record. No production code, migration, flag, or dependency was changed.

## Checklist reconciliation

No Felix checklist was modified (Felix transport unavailable —
`SQLite package has not been found installed`). Suggested checklist deltas for
replay, oldest-first:

- Implementation: check ONLY “canonical checksum parity vectors” and
  “recording-action checksum vectors” (evidence: standalone 27/27 + 11/11 +
  10/10 runs above); leave recovery, migration, race, native-gate, and
  commit items UNCHECKED.
- Test checklist: record standalone counts as partial evidence; mark native
  Micro6, native ChromaScape, and Testcontainers integration items as
  UNRUN with this record as the blocker reference.

## State reconciliation

Intended Felix state remains `in_progress/spec_ready`. Do NOT transition to
`in_review` (requires Implementation Evidence + all implementation items
checked) and do NOT transition to `done` (requires Verification Evidence +
xRSPS playback/viewer/fixture/certification, all incomplete). Do not post any
`[felix-sync:…]` marker from this record.

## Shortcut reconciliation

No Shortcut mutation intended. SC-1199 must NOT advance on this partial
delivery. No comment posted; no marker searched (read-only session).

## Sync result

Pending. Replay strictly oldest-first: replay THIS (`20260904T174000Z`
reconcile-task) record first — resolve by `shortcut:sc-1199` (known ID
`task_1788264505764_479zgp12`), apply only its checklist deltas, keep state
`in_progress/spec_ready` — and only then replay the newer `create_note` record
`20260904T220000Z--sc-1199--create-note--durable-test-integrity.md` (offline ID
`offline_sc1199_20260904T220000Z_durable_test_integrity_4b7e1c20`) to create the
evidence note (searching its `offline-sync:` tag before creation). Then mark
synced and move this file to `synced/sc-1199/`.

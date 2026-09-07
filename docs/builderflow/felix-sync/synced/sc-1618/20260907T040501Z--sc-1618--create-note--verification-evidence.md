---
schema_version: 1
offline_record_id: "offline-20260907T040501Z-sc1618-verification-evidence-01"
sync_status: synced
project: "C:\\Dev\\IdeaProjects\\ChromaScape"
shortcut_story_id: 1618
shortcut_tag: "shortcut:sc-1618"
felix_task_id: "task_1788808364914_0z4klpcjk"
operation: create_note
target_note_id: "note_1788813793423_3e29f9cf"
felix_note_id: "note_1788813793423_3e29f9cf"
actual_felix_task_state: in_progress
actual_felix_spec_state: spec_ready
actual_shortcut_state_id: 500000008
actual_shortcut_state: "In Progress"
actual_shortcut_comment_ids: [1637, 1638]
synced_at: "2026-09-07T20:44:24.000Z"
title: "SC-1618 — Verification Evidence — v2 packet pipeline and replay recovery"
note_type: documentation
stable_tags: ["shortcut:sc-1618", "change:multi-repo", "evidence:verification", "offline-sync:offline-20260907T040501Z-sc1618-verification-evidence-01"]
parent_id: null
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1618"
    entity_name: "DBG-PACKETS: Stream RSProx packets through Micro6 Tick Debugger to the ChromaScape packet viewer"
    link_strength: primary
task_selector:
  stable_tag: "shortcut:sc-1618"
  known_task_id: "task_1788808364914_0z4klpcjk"
created_at: "2026-09-07T04:05:01Z"
intended_felix_task_state: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: "Remain 500000008 (In Progress) until native, browser, live, and restart gates pass"
intended_shortcut_idempotency_marker: "[felix-sync:task_1788808364914_0z4klpcjk:review]"
---

## Task resolution

Resolve the existing Felix task for `shortcut:sc-1618` under canonical project
`C:\Dev\IdeaProjects\ChromaScape`; do not create a duplicate. Felix task lookup and writes were
unavailable after two server failures (`SQLite package has not been found installed`). This is an
offline verification-note create operation, not proof that the note exists in Felix. The task uses
workflow `shortcut_delivery`, classification `change:multi-repo`, and source story
`https://app.shortcut.com/leeuwbot/story/1618`.

The verification record covers the coordinated changes in `C:\Dev\IdeaProjects\rsprox`,
`C:\Dev\IdeaProjects\micro6`, and `C:\Dev\IdeaProjects\ChromaScape`. It intentionally leaves
native Windows, PostgreSQL, and live integration gates open.

## Note payload

# Verification Evidence — SC-1618

## Historical acceptance results at implementation pass

1. **RSProx v2 producer:** Focused packet-stream implementation coverage passed in the earlier
   pass (14 tests). The source now uses separate observation and capture identities, one per-source
   allocator, stable requeue identities, source-scoped loss ranges, failed-GAP merging, bounded
   queue accounting, and socket-writer GAP draining. A fresh rerun was blocked by DrvFs memory
   exhaustion before tests; native/live reconnect and partial-write checks remain open.
2. **Micro6 v1/v2 compatibility:** The v1/v2 fields, explicit validation, checksum identity, and
   batch-owned diagnostics are implemented with focused regression coverage. Native Windows compile
   and tests were attempted but could not start because WSL-to-Windows interop returned
   `UtilAcceptVsock:271: accept4 failed 110`; this acceptance item is not verified.
3. **ChromaScape persistence/replay:** The earlier Java 17 focused run passed 122 tests covering
   DTO, ingestion, checksum, WebSocket, replay, and durability paths. V43 preserves V39 v1 rows and
   adds source/session/schema-aware v2 identity. PostgreSQL migration execution and live database
   gates still need an explicit successful run.
4. **Retry-safe diagnostics:** The Micro6 snapshot ownership patch captures serialized omissions and
   stripped-byte diagnostics, restores pre-accept rejection once, and keeps accepted retries owned
   by transport retry. Focused tests were added; native compilation is still required.
5. **Loss ranges:** RSProx and Micro6 keep ranges source-scoped, merge overlap/adjacency while
   retaining counts, and keep observation identity distinct from capture ranges. Full overflow and
   cross-session live verification remains open.
6. **Replay interval recovery:** Browser core coverage passed 7/7, including interval subtraction,
   middle/single-tick continuation, duplicate in-flight suppression, retry after failure, stale
   recording rejection, and distinct observation/capture display. Full browser runner and server
   pagination exercise remain open.
7. **Legacy compatibility:** v1 fixtures and explicit v1 handling are retained in the source and
   focused tests. Legacy/native end-to-end and restart/replay equality are not verified here.
8. **Safety and bounds:** Decoded/error text and queue/frame sizes are bounded and raw packet bytes
   remain disabled by default. Security/live validation of the complete running stack remains open.

## Acceptance results

1. RSProx focused packet-stream coverage is 14/14 against the current source fingerprints.
   The required live reconnect, partial-write, and overflow acceptance remains open.
2. Micro6 native focused full dependency-graph coverage is 120/120. The separate normal build and
   capture-contract tasks remain open because of the unrelated SC-968 identity mismatch.
3. ChromaScape focused Java/Testcontainers coverage is 128/128, including disposable PostgreSQL
   migration/constraint and durable ACK/rollback checks. Live restart/replay equality and the full
   database-backed pagination/search matrix remain open.
4. Batch-owned diagnostics, retry ownership, ACK settlement, stale-session protection, and
   malformed-frame handling are covered by the passing Micro6 focused tests; live delivery evidence
   remains open.
5. Source-scoped loss and identity behavior is covered by the passing RSProx/Micro6/ChromaScape
   focused tests; live overflow and cross-session acceptance remains open.
6. Browser interval recovery and production wiring are covered by 7/7 core and 227/227 full
   JavaScript tests plus a read-only debugger page HTTP 200 check. Interactive browser execution
   failed to initialize and is not claimed.
7. v1 fixtures and explicit compatibility paths pass focused checks; legacy live and restart/replay
   equality remains open.
8. Bounds, escaping, and raw-byte defaults pass focused/static checks; end-to-end live safety
   evidence remains open.

## Tests

### Verified in the implementation pass

- RSProx packet-stream focused suite: 14 passed.
- ChromaScape focused Java suite with Java 17 and
  `-Psc967VectorsPath=/mnt/c/Dev/IdeaProjects/contracts/sc967/ed25519-vectors.json`: 122 passed.
- Browser recovery core: `node --test src/test/js/debugger-replay-core.test.js`: 7 passed.
- Scoped `git diff --check` for the new Micro6 and ChromaScape changes: passed.

### Attempted but blocked or not run

- Native Micro6 command:
  `cmd.exe /c "cd /d C:\Dev\IdeaProjects\micro6 && gradlew.bat --no-daemon --console=plain :runelite-client:test --tests net.runelite.client.plugins.debugrecorder.RsproxPacketBridgeTest --tests net.runelite.client.plugins.debugrecorder.TickBatchMessageTest --tests net.runelite.client.plugins.debugrecorder.TickBatchCanonicalChecksumTest --tests net.runelite.client.plugins.debugrecorder.DebugRecorderPluginTest --tests net.runelite.client.plugins.debugrecorder.ChromaScapeWebSocketClientTest -x verifyCaptureContract"`
  was blocked before Gradle by `UtilAcceptVsock:271: accept4 failed 110`.
- A fresh WSL RSProx rerun failed before test execution while Gradle scanned a build directory with
  `java.nio.file.FileSystemException: Cannot allocate memory`.
- A fresh Java 17 ChromaScape rerun failed in `processResources` before tests because DrvFs could
  not read `src/main/resources/db`.
- PostgreSQL migration/uniqueness/retry/cursor/search tests, full Micro6 plugin compilation, live
  two-direction capture, restart/replay comparison, clean reconnect, stale socket, lost ACK, and
  queue-overflow checks were not successfully run.

## Historical risks at offline-record creation

- The Micro6 source graph has not been natively recompiled after the retry-ownership change.
- Native Windows interop and DrvFs memory failures prevent claiming the requested normal Windows
  build or full pipeline verification.
- The worktrees contain substantial unrelated existing modifications; only the scoped paths listed
  in the implementation evidence should be reviewed or committed for SC-1618.
- Felix and Shortcut state are not synchronized, and no task/checklist/acceptance item should be
  marked complete in the tracker based on this offline note alone.

## Current verification update (2026-09-07)

The current source was tested at the revisions recorded below. No SC-1618 source files changed
after the corresponding successful runs.

- RSProx focused packet-stream suite: 14/14 passed. The current report is
  rsprox\proxy\build\test-results\test\TEST-net.rsprox.proxy.packetstream.RsproxPacketStreamCodecTest.xml.
- Micro6 native focused full dependency-graph/test run: 120/120 passed across
  RsproxPacketBridgeTest (24), DebugRecorderPluginTest (27), TickBatchMessageTest (12),
  TickBatchCanonicalChecksumTest (10), and ChromaScapeWebSocketClientTest (47).
- ChromaScape focused Java/Testcontainers run: 128/128 passed. The disposable PostgreSQL
  migration/constraint tests passed 2/2 and durable batch/ACK tests passed 10/10; DTO,
  ingestion, WebSocket, replay, and cursor/filter tests also passed. Reports are under
  ChromaScape\build\test-results\test.
- Browser recovery core: node --test src/test/js/debugger-replay-core.test.js; 7/7 passed.
- Full existing ChromaScape JavaScript suite: node --test src/test/js/*.test.js; 227/227 passed,
  including the production debugger wiring regression.
- Read-only HTTP inspection of http://localhost:8090/debugger returned 200 and exposed the
  debugger CSS/scripts and Packets control. JavaScript syntax checks passed.
- Scoped git diff --check passed for task-owned paths.

Current tested revision anchors:

- RSProx HEAD 0d678a1ea029d21ec988efe751a3096fd68e3605; packet envelope SHA-256
  1A77FA8247A6D78F7DBDCDAD05472B4509F20D4AD456FC667FFC800B9CF84D91; codec SHA-256
  3316DE6F046274BFCBDA1D690A7347937B66405DB5BB4391FF35FE8B97023E23.
- Micro6 HEAD a78f237f81d3ca2c1655dce71d35512749c9a72a; packet client SHA-256
  C38C12666475C223CE9D64B6916059E0B2E69326F95B1FC3FD77BABDFE74C7BE; buffer SHA-256
  6E6084B45368B7270FEAA210ED8874AB0442355F3F844316836D4C10D391793B; WebSocket client
  SHA-256 C9550A7DB5EF0030F6126CDF7462BAE0E65C114D8E02A73547A88021ACA304CD.
- ChromaScape HEAD 471262bfeefa0c1d0a91a367227a3fd78586814e; ingestion SHA-256
  308EBCC1A10F9A7C6E34B96C9581E1461BB72DC041E82453476A345517F243A2; V43 SHA-256
  1A76F7823285BCD39F6DAB7C9E79EBFC9A84386191C815CACBF3B0146C200003; V44 SHA-256
  A6CE303806F24B373984DCC0399DF21F23EE7E86CE6870178249225779083F66; debugger.js SHA-256
  3C9597F84EB62295F164ED2D5C36F0A71F1C1B86AD73DBB5556B9D50FF89941B; replay core SHA-256
  6FDF62183DE322A388C4AC0E5011304C20598E57B1DEE80ACF20DA614614537A.

## Risks

- Micro6 focused native coverage is green, but the normal :client:build and
  :client:verifyCaptureContract gates fail on the unrelated SC-968 identity mismatch:
  1.0.0-sc1452-983d8efa4276 != 1.0.0-sc968-7c846c927520.
- ChromaScape normal build is not green because Spotless reports unrelated pre-existing worktree
  violations; no formatter was applied.
- In-app browser runtime initialization failed twice with stdout_eof and kernel exit code -1, so
  interactive page and screenshot verification is not claimed.
- Runtime launch authorization was not established. Live two-direction capture, reconnect,
  stale-socket, partial-write, lost-ACK, overflow, restart/replay identity comparison, and the
  full live database-backed pagination/search no-omission matrix remain unrun.
- The disposable PostgreSQL migration/durability subset passed, but it does not by itself prove
  the live/restart or full browser/runtime gates.

## Rollback

- Keep V39 unchanged; rollback database support only by reverting the not-yet-applied V43 migration
  through the normal reviewed Flyway procedure. Do not edit an already-applied migration.
- Disable RSProx v2 output and use the explicit v1 socket path if a downstream consumer cannot be
  upgraded; retain v1 sequence semantics.
- Revert the scoped source changes as one reviewed multi-repository change, preserving unrelated
  worktree edits and any durable recordings already written.
- Do not delete pending outbox records. If Felix recovers, replay them idempotently and record
  conflicts rather than overwriting existing notes.

## Checklist reconciliation

Keep the task's `Acceptance Criteria`, `Test Verification`, and `Regression Testing` groups
unchecked for every native, PostgreSQL, or live item without corresponding evidence. The focused
RSProx, ChromaScape, browser, and static checks may be reconciled only after Felix validates this
note and the exact test artifacts are linked. Do not check the final delivery item.

## Historical state reconciliation at offline-record creation

After the implementation evidence and all remaining native/live evidence exist, replay this note
with `offline-sync:offline-20260907T040501Z-sc1618-verification-evidence-01`, link it to the existing
task, and transition `in_progress -> in_review` only after the implementation checklist is complete.
Transition `in_review -> done` only after every acceptance/test item passes and the required
Verification Evidence note is linked. No transition is currently successful.

## Historical Shortcut reconciliation at offline-record creation

Shortcut remains `To Do` (`500000007`). Intended later markers are
`[felix-sync:<felix-task-id>:review]` and `[felix-sync:<felix-task-id>:done]`, each at most once and
only after its corresponding Felix transition succeeds. No Shortcut mutation was attempted.

## Current state reconciliation

Felix task task_1788808364914_0z4klpcjk is currently in_progress with spec_ready under the
shortcut_delivery workflow. The verification note may be linked now as evidence, but the task
must remain in_progress until the open native, browser, live, and restart gates are verified.

## Current Shortcut reconciliation

Shortcut story SC-1618 is currently In Progress (500000008). Existing comments 1637 and 1638
contain the planned and started markers for Felix task
task_1788808364914_0z4klpcjk. Do not emit review or done markers while the listed gates remain
open.

## Sync result

Synced. Felix task task_1788808364914_0z4klpcjk was resolved without creating a duplicate. The
Verification Evidence note was created as note_1788813793423_3e29f9cf at
2026-09-07T20:43:13.000Z and updated with the corrected current acceptance results at
2026-09-07T20:44:24.000Z, then linked to the task. Shortcut was already In Progress
(500000008); existing comments 1637 and 1638 contain the planned and started idempotency
markers. No review or done transition/comment was issued because the required gates remain open.

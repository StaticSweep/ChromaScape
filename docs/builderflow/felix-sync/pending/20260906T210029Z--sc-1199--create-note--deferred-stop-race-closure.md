---
schema_version: 1
offline_record_id: offline_sc1199_20260906T210029Z_deferred_stop_race_closure_a1c84e2f
sync_status: pending
project: 'C:\Dev\IdeaProjects\ChromaScape'
shortcut_story_id: 1199
shortcut_tag: 'shortcut:sc-1199'
felix_task_id: task_1788264505764_479zgp12
operation: create_note
target_note_id: null
title: 'SC-1199 deferred-stop cleanup race closure — implementation evidence'
note_type: documentation
stable_tags:
  - 'shortcut:sc-1199'
  - 'change:multi-repo'
  - 'replay:durable-delivery'
  - 'offline-sync:offline_sc1199_20260906T210029Z_deferred_stop_race_closure_a1c84e2f'
parent_id: null
intended_entity_links:
  - entity_type: task
    entity_id: task_1788264505764_479zgp12
    link_strength: primary
  - entity_type: shortcut_story
    entity_id: '1199'
    link_strength: primary
created_at: '2026-09-06T21:00:29Z'
intended_felix_task_state: in_progress
intended_felix_spec_state: spec_ready
intended_shortcut_transition: none — do not advance SC-1199; xRSPS playback/viewer/fixture/certification remain incomplete
intended_shortcut_idempotency_marker: none
---

## Task resolution

Resolve the existing Felix task `task_1788264505764_479zgp12` by stable tag
`shortcut:sc-1199` under canonical project `C:\Dev\IdeaProjects\ChromaScape`.
Never create a second task with that tag. Preserve all existing notes and
checklists. This record carries ONLY the deferred-stop cleanup race closure
evidence for Micro6 `C:\Dev\IdeaProjects\micro6`
(`ChromaScapeWebSocketClient.java` + `ChromaScapeWebSocketClientTest.java`).
Scope excludes: ChromaScape application code (unchanged this pass), Felix
state transitions (task stays `in_progress`/`spec_ready`), Shortcut state
(SC-1199 not advanced), the broader xRSPS playback/viewer/fixture/
certification work (separate and incomplete), and the concurrent SC-1449
accountbuilder/gameplay-v2 boundary work that shares the Micro6 tree.

## Note payload

# SC-1199 deferred-stop cleanup race closure — implementation evidence

## Changed files

Micro6 `C:\Dev\IdeaProjects\micro6` (uncommitted; HEAD moved from
`eb8409a50dfd` to `dd8a9ac60b` by concurrent SC-1449 work during this pass):

- `runelite-client/src/main/java/.../debugrecorder/ChromaScapeWebSocketClient.java`
  - `maybeSendRequestedRecoveryStop()` failure branch now, under one
    `outboundLock` critical section: enters fail-closed, clears
    `stopRequestedDuringRecovery`/`recoveryStopCompletion`, and detaches the
    deadline-abandoned queued `SESSION_STOP` via
    `detachQueuedDeferredStopsLocked(sessionId)`. Detached transport futures
    are settled outside the lock, then `terminalIncomplete(reason)` runs —
    with no scheduler round-trip. This closes the window in which a
    concurrent queue drain could physically send an already-failed stop.
  - `removeQueuedDeferredStopLocked` / `scheduleDeferredStopTerminalIncomplete`
    replaced by `detachQueuedDeferredStopsLocked`, which returns the removed
    messages so their completions run outside the lock.
  - `terminalIncomplete` now gathers every owned future
    (`pendingStopCompletions`, pending-batch completions, queued
    `TICK_BATCH` completions) inside the lock and completes them only after
    releasing `outboundLock`.
  - `enqueueOutbound` decisions are computed under the lock
    (`decideEnqueueLocked`, `EnqueueOutcome` record) and the caller-visible
    future is settled only after the lock is released, so transport-internal
    chained callbacks (including the deferred-stop failure handler) never run
    under the transport lock — including on enqueue-rejection paths that used
    to settle under the lock.
  - New test-only `OutboundSendTextOverride` hook consulted at the exact
    `WebSocket.sendText` call site in `drainOutboundQueue` (null in
    production falls back to the real transport).
- `runelite-client/src/test/java/.../debugrecorder/ChromaScapeWebSocketClientTest.java`
  — new tests:
  `deferredRecoveryStopSendFailureBecomesTerminalIncomplete`,
  `lateDeferredRecoveryStopSendSuccessStaysTerminalIncomplete`,
  `deferredRecoveryStopFailureSurvivesSchedulerShutdown`, plus the
  `awaitServerTickFrame` receipt helper and updated in-test comments.
- Micro6 `.../microbot/accountbuilder/integration/builder/Micro6BuilderExecutorServer.java`
  — one-line `GameplayV2BudgetEnforcer` import added (with user approval) so
  the module compiles; the file belongs to the concurrent SC-1449 session and
  was under active edit during this pass.
- ChromaScape `docs/builderflow/felix-sync/pending/20260904T220000Z--sc-1199--create-note--durable-test-integrity.md`
  — stale `thirdConnectionAttempted()` identifier corrected to
  `unexpectedConnectionAttempted()` in the earlier changed-files paragraph.

## Tests

Native Windows Gradle (`gradlew.bat :client:test` on `C:\Dev\IdeaProjects\micro6`),
run with `-x :client:verifyCaptureContract` (unrelated SC-968/SC-1452 contract
POM gate) and a temporary init script excluding the concurrently-broken
SC-1449 accountbuilder TEST sources (main sources fully included):

- `ChromaScapeWebSocketClientTest` — 44/44 pass, including the three new
  deferred-stop coverage tests.
- Full `net.runelite.client.plugins.debugrecorder.*` package — 208 tests,
  2 failures, BOTH proven pre-existing and outside this pass:
  - `DebugRecorderPluginTest.explicitStopDuringReconnectDrainsRetainedAckBeforeSessionStop`
    — reproduces identically with this pass's client changes reverted (A/B);
    fails at the reconnect retry-capture assertion before any deferred-stop
    logic executes.
  - `TaskExecutionLoggerTest.postCompletionStopExceptionRetainsFailureEvent`
    — source and test are unmodified from HEAD; fails in isolation
    (`Self-suppression not permitted` in `TaskExecutionLogger.stopTaskLogging`).
- Not run: ChromaScape PostgreSQL/Testcontainers suite (no ChromaScape code
  changed this pass; still required for the story), full Micro6 module gate
  (blocked by the in-flux SC-1449 accountbuilder sources and the capture
  contract POM gate), and ChromaScape full gate.

## Current workspace update — 2026-09-07

This section supersedes neither the historical results above nor the recorded
`dd8a9ac60b` baseline; it distinguishes the current unverified workspace from
those earlier runs.

Micro6 current scoped edits are on branch `sc-572-remediation` at workspace
HEAD `dd8a9ac60b02872de542a06fb08e8f3fe292d6f7`. ChromaScape remains on
`integration/chromascape-consolidated-20260901` at workspace HEAD
`7b522e6daa462669c706c64a32aa014c8ae8e33f`.

Micro6 current scoped edits add the remaining stop-submission durability gate:

- `ChromaScapeWebSocketClient.java` now establishes `stopIntent` atomically under
  `outboundLock` before taking the pending-batch snapshot. New `TICK_BATCH`
  submissions are rejected while that intent is active, and `SESSION_STOP` is
  enqueued only after the authoritative pending map is empty.
- `ChromaScapeWebSocketClientTest.java` adds a latch-controlled test that pauses
  after stop intent and submits a tick before stop enqueue. The test expects the
  tick to be rejected and absent from transport.
- The scheduler-rejection test now injects its rejecting scheduler through a
  package-private constructor instead of replacing the final scheduler field
  reflectively.

The earlier native result of `ChromaScapeWebSocketClientTest` **44/44 passed**
is historical evidence for the earlier implementation and is not a result for
this workspace. The current source contains the prior 46 tests plus the new
stop-intent race test (47 expected by source inspection); this current version
was **not executed** in this environment. The prior debug-recorder package
result remains historical as recorded above: 206/208 with
`DebugRecorderPluginTest.explicitStopDuringReconnectDrainsRetainedAckBeforeSessionStop`
and `TaskExecutionLoggerTest.postCompletionStopExceptionRetainsFailureEvent`
failing on that earlier version. Those failures remain unresolved here and
must not be relabeled as pre-existing certification evidence without a current
A/B run.

Current ChromaScape focused counts remain 7
`ReplayTickBatchReceiptIntegrationTest`, 7 `TickBatchDurabilityIntegrationTest`,
and 39 `TickStreamWebSocketHandlerTest`; no current PostgreSQL/Testcontainers
run was performed or recorded. The handler/service coverage remains
compositional rather than a single handler-to-real-database test. The unchanged
migration harness and Flyway baseline version 34 are present in the current
reviewed test design but are not represented by a current executed result in
this record; record them separately from the older historical migration-
rewriting description, which no longer describes the current harness.

No Felix or Shortcut synchronization was performed in this environment. Keep
SC-1199 at `in_progress/spec_ready`; do not emit a completion marker or advance
Shortcut until the current native Micro6 gates, reconnect-stop test, and current
ChromaScape focused PostgreSQL tests pass.

## Remaining work

- ChromaScape PostgreSQL/Testcontainers focused tests and the full Micro6 /
  ChromaScape gates.
- Resolve the two pre-existing Micro6 debugrecorder test failures above
  (plugin reconnect-stop capture; TaskExecutionLogger self-suppression) and
  the SC-1449 compile/contract gates.
- Exact-file commits for the Micro6 debugrecorder changes (shared tree; the
  concurrent session is actively committing).
- Replay this record and the existing SC-1199 outbox records oldest-first
  when Felix is available.
- Keep `task_1788264505764_479zgp12` at `in_progress/spec_ready`; do not
  advance Shortcut SC-1199. Broader SC-1199 xRSPS
  playback/viewer/fixture/certification work remains separate and incomplete.

## Checklist reconciliation

Implementation checklists verified by code inspection and the focused native
runs above:

- [x] Deferred-stop failure detaches queued `SESSION_STOP` atomically with
  fail-closed entry under `outboundLock`; queued bytes and `stopQueued` are
  corrected in the same critical section.
- [x] Detached transport futures complete outside the lock, then
  `TERMINAL_INCOMPLETE` is entered.
- [x] In-flight stops cannot be unsent; late success after terminal is logged
  as ambiguous and never marks the session stopped (covered by
  `lateDeferredRecoveryStopSendSuccessStaysTerminalIncomplete`).
- [x] Terminal transition does not depend on scheduler availability; futures
  complete outside the lock even when invoked from a thread that held it
  (covered by `deferredRecoveryStopFailureSurvivesSchedulerShutdown`).
- [x] Send-failure variant covered (`deferredRecoveryStopSendFailureBecomesTerminalIncomplete`).
- [x] Outbox stale identifier corrected.

## State reconciliation

No intended Felix transitions for this pass: task remains
`in_progress`/`spec_ready`. This note is the implementation evidence for the
deferred-stop race closure; link it under the existing task when replaying.

## Shortcut reconciliation

None — do not advance SC-1199 and post no synchronization marker. Blocker:
xRSPS playback/viewer/fixture/certification work remains incomplete.

## Sync result

Pending. Felix MCP was unreachable during this pass (two request timeouts).
No Shortcut mutation was attempted.

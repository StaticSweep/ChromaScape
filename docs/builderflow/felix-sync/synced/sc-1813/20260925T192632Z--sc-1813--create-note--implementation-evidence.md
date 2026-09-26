---
schema_version: 1
felix_note_id: note_1790390816183_d850dc17
synced_at: 2026-09-26T02:47:28.294707+00:00
offline_record_id: offline-sc1813-impl-evidence-20260925T192632Z
sync_status: synced
project: C:\Dev\IdeaProjects\ChromaScape
posix_checkout: /run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-fedora-03
micro6_checkout: /run/media/ikiddoi/dev-hdd/dev/IdeaProjects/micro6-sc1618-fedora
shortcut_story_id: 1813
shortcut_tag: shortcut:sc-1813
felix_task_id: task_1790373482776_czm675c9j
operation: create_note
target_note_id: null
title: SC-1813 Implementation Evidence (outbox)
note_type: documentation
stable_tags:
  - shortcut:sc-1813
  - change:multi-repo
  - offline-sync:offline-sc1813-impl-evidence-20260925T192632Z
parent_id: null
entity_links:
  - entity_type: shortcut_story
    entity_id: "1813"
    link_strength: primary
created_at: 2026-09-25T19:26:32Z
intended_felix_state: in_progress (supports in_progress -> in_review handoff)
intended_shortcut_transition: null
idempotency_marker: null
---

# SC-1813 Implementation Evidence (outbox)

Felix still in outage; no Felix state claimed. Implements the plan in
`20260925T191706Z--sc-1813--create-note--delivery-plan.md`.

## Changed files

ChromaScape (`ChromaScape-fedora-03`):
- NEW `web/debug/tickdebugger/dto/ScreenFrameMessage.java` — screen-frame/v1 DTO.
- NEW `web/debug/tickdebugger/service/ClientFrameMetrics.java` — received /
  forwarded / droppedAttached / droppedInvalid / lastRejection counters.
- EDIT `web/debug/tickdebugger/websocket/TickStreamWebSocketHandler.java` —
  `SCREEN_FRAME` dispatch, `handleScreenFrame` (binding/ACTIVE/session-match
  gates, 1.2M-char cap, dimension bounds, base64+JPEG decode, drop when a real
  attached source owns pixels, forward to `WebsocketViewport.publishFrame`),
  optional viewport/metrics wiring (5-arg ctor unchanged for tests).
- EDIT `web/debug/ViewportPipelineDebugController.java` — `clientFrames` block
  in `/api/debug/viewport/pipeline`.
- NEW `TickStreamWebSocketHandlerScreenFrameTest.java` — 6 tests (forward,
  unbound, mismatch, malformed, oversized, attached-drop).

micro6 (`micro6-sc1618-fedora`, code + static review ONLY per repo AGENTS.md):
- EDIT `debugrecorder/ChromaScapeWebSocketClient.java` —
  `OutboundKind.SCREEN_FRAME`, coalescing enqueue branch (latest-only, no
  pending-ack, no retry), `sendScreenFrame(sessionId, json)`. `completeOutbound`
  already settles non-tick kinds correctly; no change needed there.
- EDIT `debugrecorder/DebugRecorderPlugin.java` — 500 ms gated timer
  (feed + connected + confirmed session), canvas paint capture, downscale to
  960 wide, JPEG q70, 1.2M-char cap, throttled drop log, cancel on shutdown.
  Never throws; never touches game state.

## Tests

Ran (ChromaScape, JAVA_HOME JDK 17.0.20.1+1, SC967 vectors):
- `TickStreamWebSocketHandlerScreenFrameTest`: 6/6 pass.
- TargetFrameRuntimeTest 5/5, X11DesktopFrameSourceTest 2/2, Observe* 8/8 —
  all pass (Watch feature untouched and green).
- Focused packages (tickdebugger, observe, screen, viewport, qualification):
  330 non-integration tests, 0 failures. Only failures are Testcontainers
  integration tests requiring Docker (absent on this host) — environmental,
  pre-existing.
- `spotlessApply` run; `spotlessJavaCheck` clean. Checkstyle warnings on
  touched files are all pre-existing lines.
- micro6: NOT compiled/tested (repo rule: needs explicit per-action request).

## Remaining work

- [ ] micro6 `:runelite-client` compile + module tests (NEEDS explicit user
      authorization per micro6 AGENTS.md).
- [ ] Live FEDORA-03 verification: launch Builder Goal run (DogaAgi/world 360),
      confirm viewport renders client frames with no input session
      (NEEDS client-launch authorization + operator watching the debugger).
- [ ] Felix replay on recovery (task creation + plan + this evidence,
      oldest-first) and Shortcut `[felix-sync:<id>:*]` markers only after real
      transitions.
- Known limitation (not acceptance): Watch-desktop + client-streaming together
  render newest-wins (may alternate); operators use one or the other.

## Task resolution

Implements sc-1813 scope items 2–4 (server) and item 1 code (client, static
review only). No Felix task exists yet; replay must search
`offline-sync:offline-sc1813-task-creation-20260925T191601Z` first.

## Note payload

This record is the Implementation Evidence note content (see Changed
files / Tests / Remaining work above).

## Checklist reconciliation

Plan-record checklists: server items implemented and tested (checked, evidence
above); micro6 code written, build/test unchecked pending authorization; live
verification unchecked pending launch authorization. No checkmarks are claimed
in Felix.

## State reconciliation

No Felix transition claimed. Intended: create -> spec_ready -> in_progress ->
in_review (this evidence) -> done (after live verification + replay).

## Shortcut reconciliation

No Shortcut mutation in this record. sc-1813 stays in its creation state;
state/comment sync only after Felix transitions succeed post-recovery.

## Sync result

Pending.


## Recovery audit 2026-09-26

Felix is available. This historical payload conflicts with the newer authoritative SC-1813 Architecture/API notes and Implementation Evidence (note_1790374737868_5ad93394). The original plan uses 900 KiB and a narrower wire envelope; current Felix requires capability/session-stream validation and 768000-byte bounds. The old implementation evidence is superseded and must not mark present acceptance items complete. Preserved both versions; no overwrite or workflow transition performed. Reconcile this conflict before completing outbox replay.


## Completed recovery 2026-09-26

Reused task task_1790373482776_czm675c9j. Historical note note_1790390816183_d850dc17 preserves this payload with explicit supersession by the current specification; no authoritative content overwritten and no historical test claim used to check current acceptance. Scope Definition completed; Felix reached spec_ready and in_progress through real transitions. Shortcut state is In Progress, planned marker comment 1814 and started marker comment 1815 succeeded. All recorded note operations replayed; remaining implementation/live evidence stays on the active task.

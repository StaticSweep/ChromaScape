---
schema_version: 1
offline_record_id: offline-sc1813-plan-20260925T191706Z
sync_status: conflict
project: C:\Dev\IdeaProjects\ChromaScape
posix_checkout: /run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-fedora-03
micro6_checkout: /run/media/ikiddoi/dev-hdd/dev/IdeaProjects/micro6-sc1618-fedora
shortcut_story_id: 1813
shortcut_tag: shortcut:sc-1813
felix_task_id: task_1790373482776_czm675c9j
operation: create_note
target_note_id: null
title: SC-1813 delivery plan (scope, acceptance, checklists, design)
note_type: documentation
stable_tags:
  - shortcut:sc-1813
  - change:multi-repo
  - offline-sync:offline-sc1813-plan-20260925T191706Z
parent_id: null
entity_links:
  - entity_type: shortcut_story
    entity_id: "1813"
    link_strength: primary
created_at: 2026-09-25T19:17:06Z
intended_felix_state: planning (supports spec_ready gate)
intended_shortcut_transition: null
idempotency_marker: null
---

# SC-1813 delivery plan: client-pushed screen frames

Parent record: `20260925T191601Z--sc-1813--reconcile-task--task-creation.md`.
Felix remains in outage (3x MCP -32001); nothing here claims a Felix write.

## Scope and exclusions

In scope:
1. micro6 `debugrecorder` plugin (`ChromaScapeWebSocketClient.java`, 3498 lines):
   new `OutboundKind.SCREEN_FRAME`, lightweight enqueue (no pending-ack map, no
   durable retry; coalesce by dropping older queued frames), canvas capture via
   AWT paint into `BufferedImage`, downscale to max 960 wide, JPEG encode,
   2 fps timer gated on connected + feed-enabled + session active, 900 KiB cap
   with throttled drop log. Honors existing `FeedControlListener`.
2. New wire message `SCREEN_FRAME`, schema `screen-frame/v1`: type, sessionId,
   frameSeq, capturedAt, width, height, jpegBase64, quality. Additive only; no
   tick-schema change.
3. ChromaScape `TickStreamWebSocketHandler`: `SCREEN_FRAME` case with the same
   binding gates as ticks (bound, ACTIVE phase, session match; checksum envelope
   when present), base64 decode, `ImageIO.read`, forward into
   `WebsocketViewport.publishFrame` (existing latest-only broadcast, existing
   no-session drop). No persistence, no ack.
4. Observe panel: frame-feed state in `/api/observe/status` (frames received
   counter); no new buttons (Watch stays desktop-only until live-proven).
5. Tests: ChromaScape handler tests with synthetic JPEG frames (bound,
   mismatch, malformed, oversized); micro6 unit tests for capture/encode/drop
   logic with a fake canvas (module `runelite-client` tests, `*Test.java`).
6. Live verification on FEDORA-03 against a real Builder Goal run
   (DogaAgi/world 360): viewport renders client frames with no ChromaScape
   input session open and no attach.

Excluded: recording-lane MP4 changes, RPM/packaging recertification,
multi-client selection, tick-schema changes, executor (8098) work.

## Linked project rules

- ChromaScape Fedora checkout: POSIX paths; wrapper-owned lifecycle only;
  secrets never in repo; fail closed; google-java-format 1.17.0 + checkstyle.
- micro6 AGENTS.md: **no compile/test/launch without explicit per-action user
  request** — code + static review only until authorization is granted. Tabs,
  checkstyle `config/checkstyle/checkstyle.xml`, `*Test.java` placement.
- Shared contract: Windows-form paths in tracker artifacts (used where the
  mapping exists); `[felix-sync:<id>:<state>]` markers only after real
  transitions; never claim unrun checks.

## Acceptance criteria (Given/When/Then)

1. Given a Builder Goal run with an active tick session and feed enabled, when
   the client streams SCREEN_FRAME at ~2 fps, then the debugger viewport
   renders client frames with no ChromaScape input session open and no attach.
2. Given a frame with unknown/mismatched sessionId, when it arrives, then it is
   rejected with a stable code and the viewport is untouched.
3. Given malformed base64, undecodable JPEG, or >900 KiB payload, when it
   arrives, then it is rejected, counted, and logged throttled (no exception
   escapes the WS handler).
4. Given feed disabled, when the tick stream is connected, then the client stops
   sending frames within 2 s and `/api/observe/status` reflects the stall.
5. Given an active ChromaScape input session, when client frames arrive, then
   they are dropped in favor of the attached source (never two pixel writers).

## Implementation checklist

- [ ] micro6: `OutboundKind.SCREEN_FRAME` + lightweight enqueue/coalesce
- [ ] micro6: canvas capture + downscale + JPEG encode + 2 fps gated timer
- [ ] micro6: unit tests (fake canvas, cap/drop/gate behavior)
- [ ] ChromaScape: `ScreenFrameMessage` DTO + handler case + validation gates
- [ ] ChromaScape: forward to `WebsocketViewport.publishFrame`, drop when
      attached source owns frames
- [ ] ChromaScape: handler tests (bound/mismatch/malformed/oversized)
- [ ] ChromaScape: full build + focused suites green, spotless clean
- [ ] micro6: compile + module tests (NEEDS explicit user authorization)
- [ ] Live: Builder Goal run, viewport shows client frames, evidence captured
      (NEEDS client launch authorization)

## Test checklist

- [ ] New ChromaScape handler tests pass
- [ ] Existing viewport/tick/window/qualification suites still pass
- [ ] micro6 module tests pass (after authorization)
- [ ] Live FEDORA-03 verification with screenshot/log evidence (after launch
      authorization)

## Design artifacts

Wire contract (`screen-frame/v1`, additive, over `/ws/micro6/tick-stream`):
`{type:"SCREEN_FRAME", sessionId, frameSeq, capturedAt, width, height,
jpegBase64, quality}`. Server validates binding/ACTIVE/session-match, decodes,
publishes; no ack, no persistence. Client coalesces (latest-only), honors
FEED_CONTROL, caps at 900 KiB. Conflict rule: an open input session always wins;
client frames are dropped while attached (criterion 5).

## State reconciliation

Supports the `planning -> spec_ready` gate. No gate claimed. Replay order:
create task -> attach this plan + checklists -> spec_ready -> in_progress.

## Shortcut reconciliation

No Shortcut mutation in this record. Story sc-1813 (epic 1660) already created
while Shortcut MCP was healthy.

## Sync result

Pending.


## Recovery audit 2026-09-26

Felix is available. This historical payload conflicts with the newer authoritative SC-1813 Architecture/API notes and Implementation Evidence (note_1790374737868_5ad93394). The original plan uses 900 KiB and a narrower wire envelope; current Felix requires capability/session-stream validation and 768000-byte bounds. The old implementation evidence is superseded and must not mark present acceptance items complete. Preserved both versions; no overwrite or workflow transition performed. Reconcile this conflict before completing outbox replay.

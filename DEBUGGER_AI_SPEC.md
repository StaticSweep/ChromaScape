# ChromaScape AI Debugger Spec (Micro6 + Leeuwbotapi Integration)

## Goal
Implement the tick-based AI debugger described in the provided prompt inside the ChromaScape
`/debugger` experience, while integrating with existing ChromaScape services and the
Leeuwbot API stack. This is a spec and plan only; no code changes are included.

## Context Snapshot (Current State)
- ChromaScape debugger UI already exists (`/debugger`) with:
  - Live viewport via `/ws/viewport` and overlay via `/ws/overlay`.
  - Logs panel using `/ws/logs`.
  - Bot state panel via `/ws/bot-state`.
  - Replay video via `/api/recordings/*` and log timeline via `/api/logs/timeline`.
  - Basic AI inspector from `/api/debug/ai-inspector/latest`.
- Log storage is simple (timestamp, level, message, source, scriptName).
- Recording sessions are stored with start/end timestamps and video metadata.
- Micro6 client already ships logs to ChromaScape via `ChromaScapeAppender`
  (`/api/logs/external`) and has a Debug Recorder plugin that records tick-aware events
  to JSON locally.
- Leeuwbot API includes telemetry and log ingestion services (micro6 logs, websocket
  streaming) and a ChromaScape bridge service.

## Target Capabilities (From Prompt)
1. Tick-based synchronization across panels (live + replay).
2. Strict panel axis separation:
   - Perception: live stream + overlays
   - Time: logs
   - Truth: bot state snapshot
   - Meaning: AI inspector
   - Time control: replay controls
3. Live vs Replay modes with distinct controls and state behavior.
4. Structured log schema with subsystem and correlation IDs.
5. Replay timeline with markers, keyboard shortcuts, and tick jump.
6. Exportable sessions (logs/state/overlays/video).

## Architecture Overview
### Components
- **ChromaScape (Spring Boot)**
  - Hosts debugger UI and APIs.
  - Stores debugger sessions, logs, state snapshots, overlays.
  - Serves recorded video and log timeline sync.
- **Micro6 Client (Debugger Plugin)**
  - Emits structured tick events, overlays, and state snapshots.
  - Streams live data to ChromaScape.
- **Leeuwbot API (Go Services)**
  - Optional long-term storage and cross-instance log aggregation.
  - Micro6 logs already supported via telemetry-service.

### Live Flow (Target)
1. Micro6 client emits `TickSnapshot` + `LogEntry` + `Overlay` + `BotState` per tick.
2. ChromaScape ingests the batch and updates in-memory store and DB.
3. WebSockets broadcast to debugger UI.
4. `current_tick` drives UI updates.

### Replay Flow (Target)
1. User selects a session (recording + logs + state snapshots).
2. UI scrubber seeks by tick and time.
3. Backend returns logs/state/overlays for selected tick, and video frame if needed.

## Data Model (ChromaScape)
### New/Extended Entities
- `debug_sessions`
  - `id`, `instance_id`, `account_name`, `script_name`, `start_time`, `end_time`,
    `recording_id`, `mode`, `metadata_json`.
- `debug_logs`
  - `id`, `session_id`, `tick`, `timestamp`, `subsystem`, `severity`, `event_type`,
    `message`, `details_json`, `correlation_id`.
- `debug_state_snapshots`
  - `id`, `session_id`, `tick`, `timestamp`, `state_json`.
- `debug_overlays`
  - `id`, `session_id`, `tick`, `timestamp`, `overlay_json`.
- `debug_markers`
  - `id`, `session_id`, `tick`, `marker_type`, `label`, `severity`, `correlation_id`.
- `debug_inspections`
  - `id`, `session_id`, `tick`, `fault_type`, `root_cause`, `suggestion`,
    `confidence`, `evidence_json`.

### Log Entry Schema (Use Prompt Schema)
```
class LogEntry:
  tick: int
  timestamp: str  # ISO-8601
  subsystem: str  # Perception, Combat, Movement, Prayer, Prediction, Inventory, System
  severity: str   # Debug, Info, Warn, Error
  eventType: str
  message: str
  details: dict
  correlationId: Optional[str]
```

### Tick Mapping
- `tick` is the authoritative key for sync (600ms per tick).
- `timestamp` maps ticks to real time.
- `videoTimeMs = (tick - session.start_tick) * 600`.
- Store `start_tick` in `debug_sessions` for deterministic replay.

## API + WebSocket Surface (ChromaScape)
### Ingestion (Micro6 -> ChromaScape)
- `POST /api/debugger/session/start`
  - Start a debug session, returns `sessionId`, `startTick`.
- `POST /api/debugger/session/stop`
  - Ends session, closes recording, returns summary.
- `POST /api/debugger/batch`
  - Payload includes tick, log batch, state snapshot, overlay payload, markers.
- `POST /api/debugger/logs`
  - Optional single log entry fallback (for compatibility).

### Replay/Query
- `GET /api/debugger/sessions`
  - List sessions with metadata.
- `GET /api/debugger/sessions/{id}`
  - Session details, start/end ticks, recordingId, stats.
- `GET /api/debugger/tick/{tick}`
  - Returns logs + state + overlays + markers for a tick.
- `GET /api/debugger/logs`
  - Filtered logs (subsystem, severity, search).

### WebSocket (Live)
- `/ws/debugger/logs`
- `/ws/debugger/state`
- `/ws/debugger/overlay`
- `/ws/debugger/tick`
- `/ws/debugger/inspector`

### Compatibility
- Keep existing `/ws/logs`, `/ws/viewport`, `/ws/overlay`, `/ws/bot-state`
  during transition. Add a versioned adapter that maps new schema to old UI.

## Micro6 Debugger Plugin Integration
### Baseline (Existing)
- `ChromaScapeAppender` pushes log lines to `/api/logs/external`.
- Debug Recorder plugin already produces `DebugRecordedEvent` with
  `{tick, ts, type, data}` and collects game tick data.

### Required Additions
- Add a lightweight `DebuggerPublisher` inside the plugin that:
  - Buffers events per tick.
  - Emits `TickSnapshot` batch to ChromaScape (HTTP POST).
  - Provides `overlay` payloads (hitboxes, projectiles, tiles).
  - Sends `bot_state` snapshot (HP, prayer, location, cooldowns).
- Map debug recorder events into the new `LogEntry` schema:
  - `MENU_CLICK` -> subsystem `System`
  - `MOUSE_CLICK` -> subsystem `System`
  - `GAME_TICK` -> subsystem `System`
  - Script-specific events -> subsystem based on feature owner (Combat/Prayer/etc).
- Include `correlationId` for projectiles, threats, or actions so overlays and logs link.

### Transport
- HTTP POST batch every tick (or every N ticks with buffering).
- API key header shared with ChromaScape if enabled.
- Fall back to existing log appender when debugger is disabled.

## Leeuwbot API Integration (Optional, Not Blocking)
### Use Cases
- Store large-scale logs and state history.
- Multi-instance dashboards with shared sessions.

### Approach
- Add optional forwarding from ChromaScape to leeuwbot telemetry-service:
  - Mirror `debug_logs` into `micro6_logs` context fields.
  - Persist `debug_state_snapshots` as compressed JSON in a new table.
- Optionally query leeuwbot telemetry for replay logs when local data is missing.

## UI/UX Implementation (ChromaScape Debugger)
### Layout (Match Prompt)
- Top bar: session name, mode toggle, record/export controls, tick readout.
- Main band: viewport + overlays on left, logs on right.
- Middle band: full replay controls + scrubber + markers.
- Bottom band: bot state on left, AI inspector on right.

### UI Changes (Concrete)
- `debugger.html`
  - Add record/export controls.
  - Add marker legend and transport controls in scrubber band.
  - Add AI inspector sections: Fault, Root Cause, Instruction.
- `debugger.css`
  - Adjust grid to match 3-band layout.
  - Add marker colors and hover tooltips.
  - Add Live badge and mode-specific state.
- `debugger.js`
  - Replace current log rendering with structured LogEntry rendering.
  - Add tick-based playback controls + keyboard shortcuts.
  - Add sync logic: `current_tick` drives logs/state/overlays.
  - Handle Live vs Replay behaviors as specified.

## Mode Behavior
### Live Mode
- Only pause allowed; timeline locked to latest tick.
- Scrubber shows live marker at end.
- Small buffer (+/- 5 ticks) for quick inspection.

### Replay Mode
- Full transport controls.
- Scrubber supports tick jump and marker navigation.
- Video frame + logs + state + overlays are synced to tick.

## AI Inspector Logic
- Input: current tick state + last N logs + correlations.
- Output: `faultType`, `rootCause`, `suggestion`, `confidence`, `evidence`.
- Phase 1: rule-based (expand existing AIInspectorService).
- Phase 2: optional LLM backed service behind feature flag.

## Export
- Export button packages:
  - `session.json` (metadata)
  - `logs.json` (debug_logs)
  - `state.json` (snapshots)
  - `overlays.json` (overlay events)
  - `recording.mp4` (if present)
- Bundle as zip; store under `recordings/exports`.

## Performance and Retention
- Keep max 10k log entries per session in memory (prune Debug first).
- Persist all entries in DB for replay.
- Use batch ingest to reduce DB writes.
- Use JSONB or TEXT for details fields; keep indexes on `session_id`, `tick`, `severity`.

## Testing and Validation
- Live scenario: start session, receive ticks, view sync across panels.
- Replay scenario: load session, scrub, verify overlays and logs alignment.
- Marker validation: errors map to timeline markers.
- Export: verify zip includes all artifacts.

## Implementation Plan
1. **Schema + API Foundations**
   - Add new debug tables and repositories.
   - Create `debugger/session` endpoints and `debugger/batch` ingestion.
2. **Live Streaming**
   - Add WebSocket channels for tick/log/state/overlay.
   - Bridge existing DebugStateStore to new schema.
3. **Replay + Timeline**
   - Add tick-based replay endpoints.
   - Update `debugger.js` to drive by tick.
4. **UI Upgrade**
   - Update `debugger.html/css/js` to match prompt layout and controls.
5. **Micro6 Plugin Updates**
   - Add debugger publisher and batch emitter.
   - Map recorder events to LogEntry schema.
6. **AI Inspector Enhancements**
   - Expand rule-based analysis using new log/state data.
7. **Export**
   - Implement zip export with metadata and video.

## Open Questions
- Do we treat `tick` from Micro6 as authoritative, or sync to ChromaScape cycles?
- Do we store video frames per tick or map by timestamp only?
- Should Leeuwbot telemetry be required for replay in production?

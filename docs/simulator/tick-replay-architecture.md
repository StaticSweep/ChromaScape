# Tick Replay Architecture

## Overview

The Tick Replay system enables deterministic replay of recorded game sessions across three services:

1. **micro6** (RuneLite plugin) — Records per-tick game state snapshots and sends them to ChromaScape
2. **ChromaScape** (Spring Boot) — Stores tick snapshots in PostgreSQL and serves them via paginated HTTP API
3. **OpenRune** (Game server) — Fetches tick frames and replays them deterministically on an isolated game instance

This document describes the end-to-end data flow, schema contracts, and configuration for tick-mode replay.

---

## Data Flow

```
┌─────────┐  TICK_SNAPSHOT   ┌─────────────┐  GET /api/simulator/replay/source/frames  ┌──────────┐
│  micro6  │ ───────────────→ │ ChromaScape  │ ────────────────────────→│ OpenRune │
│ (record) │  WebSocket/HTTP  │  (store)     │  TickReplayClient        │ (replay) │
└─────────┘                   └─────────────┘                          └──────────┘
                                     │                                      │
                                     │  PostgreSQL                          │  instanced
                                     │  tick_snapshots                      │  game world
                                     │  debug_sessions                      │
```

### Recording Phase

1. micro6's `TickBatchCollector` captures player state every game tick
2. Each tick produces a `TickSnapshot` with playerState, inventory, equipment, npcs, etc.
3. Snapshots are batched and sent to ChromaScape via the debugger WebSocket
4. ChromaScape stores them in `tick_snapshots` keyed by `session_id` and `tick`

### Replay Phase

1. OpenRune's `TickReplayClient` calls ChromaScape `GET /api/simulator/replay/source/frames` to fetch frames
2. Frames are paginated (bounded `limit+1` strategy for `hasMore` detection)
3. `ReplayPlaybackService` applies each frame via `TickReplayStateApplier` on the game thread
4. On completion or failure, the session is cleaned up and the player is restored to their entry state

---

## API Contracts

### ChromaScape Endpoints (OpenRune → ChromaScape)

#### List Recordings

```
GET /api/simulator/replay/source/catalog?accountName={name}&limit={n}&offset={n}
```

Returns available recordings with tick counts.

#### Get Frames (Paginated)

```
GET /api/simulator/replay/source/frames?recordingId={id}&fromTick={tick}&limit={n}
```

Response (`ReplayFramePageResponse`):
```json
{
  "recordingId": "rec-abc123",
  "sessionId": "sess-xyz",
  "frames": [
    {
      "tick": 0,
      "timestamp": "2026-04-21T10:00:00",
      "playerState": { "x": 3200, "z": 3200, "hp": 99, "prayer": 70 },
      "inventory": [{ "slot": 0, "itemId": 4151, "quantity": 1 }],
      "equipment": null,
      "npcs": null,
      "threats": null,
      "projectiles": null,
      "cooldowns": null,
      "quest": null,
      "gameObjects": null,
      "groundObjects": null,
      "groundItems": null
    }
  ],
  "nextFromTick": 100,
  "hasMore": true,
  "accountName": "TestPlayer",
  "startTick": 0,
  "endTick": 5000
}
```

**Pagination**: Fetch `limit + 1` rows. If more than `limit` returned, set `hasMore = true` and trim to `limit`. `nextFromTick` = last returned tick + 1.

### OpenRune Endpoints (ChromaScape UI / micro6 → OpenRune)

```
POST /api/replay/control/start
POST /api/replay/control/pause
POST /api/replay/control/resume
POST /api/replay/control/stop
POST /api/replay/control/seek
POST /api/replay/control/speed
GET  /api/replay/control/status?player={name}
```

All control endpoints require `X-API-Key` header.

#### Start (Tick Mode)

```json
POST /api/replay/control/start
{
  "player": "TestPlayer",
  "recordingId": "rec-abc123",
  "speed": 1.0,
  "sourceMode": "tick"
}
```

When `recordingId` is present, tick-mode is auto-selected. `sourceMode` is optional.

---

## Schema Canonicalization

micro6 and OpenRune use different field name conventions. ChromaScape canonicalizes at response time in `SimulatorReplaySourceService`.

### Player State Field Mapping

micro6 sends a rich canonical playerState object with both nested containers and legacy flat aliases:

| micro6 canonical field | micro6 legacy field | OpenRune field |
|------------------------|--------------------|----------------|
| `playerState.position.x` | `worldX`, `x` | `x` |
| `playerState.position.y` | `worldY`, `z` | `z` |
| `playerState.hp.current` | `currentHp` | `hp` |
| `playerState.prayer.current` | `currentPrayer` | `prayer` |

ChromaScape normalizes incoming payloads so that both old flat and new nested payloads produce consistent output. For OpenRune replay, nested containers are flattened to numeric values (e.g., `hp.current` → `hp`). Unknown fields are passed through unchanged.

### Item Slot Fidelity

micro6 emits inventory and equipment items with real container slot indices from `ItemContainer.getItems()` (array index = slot position):

```json
[{"slot": 0, "itemId": 4151, "quantity": 1}, {"slot": 3, "itemId": 995, "quantity": 1000}]
```

ChromaScape passes items through without modification — slot must come from source (micro6). OpenRune validates at replay start that **all** frames contain slot metadata for inventory and equipment items, and rejects any recording where a frame is missing slot data.

---

## Tick Replay Lifecycle (OpenRune)

### Session States

```
IDLE → RUNNING → PAUSED → RUNNING (resume)
                → FAILED → IDLE (cleanup)
                → IDLE   (completion cleanup)
```

### Key Behaviors

- **Auto-cleanup on completion**: When all tick frames are consumed (`frameIndex >= frames.size`), `processTickFrame` calls `cleanupSession()` and removes the session from the map
- **IDLE session skip**: The `tick()` loop skips sessions in IDLE state to prevent double-cleanup
- **Break on state change**: The inner tick-processing loop breaks when session state changes from RUNNING (e.g., due to failure or external stop)
- **Entry snapshot restoration**: On cleanup, if an entry snapshot exists, it is restored via `ReplaySnapshotRestorer`. Falls back to exit tile on failure
- **Forward-only seek**: `seekTickMode` advances the frame index forward. Backward seek is not supported in tick mode

### Account Lock

When starting a tick replay, the player's username must match the recording's `accountName` from the frame page metadata. This prevents replaying another player's recording on a mismatched account.

---

## Configuration

### ChromaScape (`application.yml`)

```yaml
simulator:
  replay-control:
    enabled: true
    openrune-api-url: "http://127.0.0.1:8195"
    api-key: "dev-token"
    connection-timeout-ms: 5000
    read-timeout-ms: 10000
```

### OpenRune (`game.yml`)

```yaml
- class: org.alter.game.service.replay.playback.ReplayPlaybackService
  enabled: true
  chromascape-url: "http://localhost:8090"
  api-key: "dev-token"
  chromascape-api-key: "dev-token"
  fail-on-decode-error: false
  max-snapshots: 50
  max-fast-forward-ticks-per-seek: 500

- class: org.alter.game.service.replay.ReplayPublisherService
  enabled: true
  endpoint: "http://localhost:8092"
  batch-size: 50
  flush-interval-ms: 250
  queue-size: 10000
```

### micro6 (RuneLite Plugin)

- `chromaScapeBaseUrl` — ChromaScape server URL (auto-normalizes `ws://` → `http://`, strips `/ws/` paths)
- `apiKey` — API key for OpenRune control endpoints (default: `dev-token`)

---

## URL Normalization (micro6)

The `ReplayControlClient` normalizes ChromaScape URLs at configuration time:

| Input                              | Output                        |
|------------------------------------|-------------------------------|
| `ws://localhost:8090`              | `http://localhost:8090`       |
| `wss://example.com:8090`           | `https://example.com:8090`    |
| `http://localhost:8090/ws/viewport`| `http://localhost:8090`       |
| `ws://localhost:8090/ws/events`    | `http://localhost:8090`       |
| `http://localhost:8090///`         | `http://localhost:8090`       |

---

## See Also

- [TICK_MESSAGE_FORMAT.md](../TICK_MESSAGE_FORMAT.md) — Tick snapshot/diff wire format
- [replay-queue-behavior.md](../../../OpenRune-Server/docs/replay-queue-behavior.md) — Action capture queue tuning
- [SIM-01-SimulatorRunbook.md](../../../OpenRune-Server/docs/SIM-01-SimulatorRunbook.md) — Certified simulator run procedure

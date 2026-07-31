# Tick Message Format Reference

## Overview

The debugger supports two message formats for tick data transmission:
1. **Legacy Format** - Full data every tick (backward compatible)
2. **New Format** - Snapshots + Diffs (optimized, 80-95% bandwidth reduction)

---

## New Format (Recommended)

### TICK_SNAPSHOT (Full State)

Sent every N ticks (default: 15) to establish baseline state.

```json
{
  "type": "TICK_SNAPSHOT",
  "tick": 1234,
  "sessionId": "abc123",
  "snapshotType": "FULL",
  "state": {
    "player": {
      "hp": { "current": 99, "max": 99 },
      "prayer": { "current": 43, "max": 99 },
      "energy": 87,
      "animation": 808,
      "position": { "x": 3200, "y": 3200, "plane": 0 },
      "isRunning": true,
      "combatLevel": 126
    },
    "inventory": [
      { "slot": 0, "id": 556, "name": "Air rune", "quantity": 1000 },
      { "slot": 1, "id": 4151, "name": "Abyssal whip", "quantity": 1 },
      ...
    ],
    "npcs": [
      {
        "index": 0,
        "id": 1624,
        "name": "Goblin",
        "hp": { "current": 5, "max": 5 },
        "position": { "x": 3205, "y": 3205, "plane": 0 },
        "distance": 7,
        "animation": -1
      }
    ],
    "objects": [...],
    "groundItems": [...],
    "varbits": { "1234": 5, "5678": 10 },
    "quests": {
      "Dragon Slayer": { "step": 5, "status": "IN_PROGRESS" }
    }
  }
}
```

### TICK_DIFF (Incremental Changes)

Sent every tick (except snapshot ticks). Only includes changed fields.

```json
{
  "type": "TICK_DIFF",
  "tick": 1235,
  "sessionId": "abc123",
  "baseSnapshotTick": 1230,
  "changes": {
    "player": {
      "hp.current": 95,
      "animation": 1979
    },
    "inventory": {
      "slots": [
        { "slot": 0, "quantity": 995 }
      ]
    },
    "npcs": {
      "added": [
        { "index": 5, "id": 1625, "name": "Goblin", ... }
      ],
      "updated": [
        { "index": 0, "changes": { "hp.current": 3, "animation": 404 } }
      ],
      "removed": [2, 3]
    },
    "varbits": {
      "1234": 6
    }
  },
  "events": [
    {
      "type": "DAMAGE_TAKEN",
      "source": "Goblin",
      "amount": 4,
      "hitType": "MELEE"
    },
    {
      "type": "SPELL_CAST",
      "spellName": "Fire Blast",
      "target": "Goblin"
    }
  ]
}
```

---

## Legacy Format (Backward Compatible)

Full state sent every tick. No `type` field.

```json
{
  "tick": 1234,
  "sessionId": "abc123",
  "playerState": {
    "hp": { "current": 99, "max": 99 },
    "prayer": { "current": 43, "max": 99 },
    ...
  },
  "inventory": [...],
  "npcs": [...],
  "snapshot": {
    "playerState": {...},
    "threats": [...],
    "projectiles": [...],
    "cooldowns": {...}
  },
  "logs": [
    { "message": "...", "level": "INFO" }
  ]
}
```

---

## Canonical PlayerState Object

micro6 sends a rich `playerState` object under `TICK_BATCH` messages with both canonical nested fields and legacy flat aliases. ChromaScape normalizes old flat payloads internally.

### Current Canonical Shape

```json
{
  "playerState": {
    "schemaVersion": 1,
    "tick": 123,
    "name": "PlayerName",
    "position": {
      "x": 3200,
      "y": 3200,
      "z": 3200,
      "plane": 0,
      "regionId": 12850,
      "regionX": 50,
      "regionY": 50
    },
    "localPosition": {
      "x": 6400,
      "y": 6400,
      "sceneX": 50,
      "sceneY": 50
    },
    "hp": {
      "current": 99,
      "max": 99,
      "percent": 100
    },
    "prayer": {
      "current": 43,
      "max": 99,
      "percent": 43
    },
    "energy": 87,
    "weight": 0,
    "combatLevel": 126,
    "animation": 808,
    "poseAnimation": 813,
    "graphic": -1,
    "isRunning": true,
    "movementSpeed": 2,
    "isIdle": false,
    "isMoving": true,
    "overheadText": null,
    "interacting": {
      "type": "NPC",
      "id": 3010,
      "index": 12,
      "name": "Guard"
    },
    "skills": {
      "ATTACK": { "real": 99, "boosted": 99, "xp": 13034431 }
    },

    "x": 3200,
    "z": 3200,
    "worldX": 3200,
    "worldY": 3200,
    "plane": 0,
    "currentHp": 99,
    "maxHp": 99,
    "currentPrayer": 43,
    "maxPrayer": 99,
    "runEnergy": 87,
    "animationId": 808
  }
}
```

### Key Rules

- `position.y` and legacy `z` / `worldY` all represent the OSRS world Y coordinate.
- Legacy flat aliases (`x`, `z`, `currentHp`, `runEnergy`, `animationId`) are retained for backward compatibility with strict replay and OpenRune canonicalization.
- ChromaScape accepts old flat-only payloads but normalizes them internally to include nested containers.
- ChromaScape stores the full `playerState` JSON including both nested groups and legacy aliases.
- The UI displays the raw `playerState` as a collapsible JSON tree.
```

---

## Event Types

Events appear in the `events` array of TICK_DIFF messages.

### Combat Events

```json
{ "type": "DAMAGE_TAKEN", "source": "Goblin", "amount": 4, "hitType": "MELEE" }
{ "type": "DAMAGE_DEALT", "target": "Goblin", "amount": 8, "hitType": "MELEE" }
{ "type": "SPELL_CAST", "spellName": "Fire Blast", "target": "Goblin" }
```

### Item Events

```json
{ "type": "ITEM_DROPPED", "itemId": 556, "itemName": "Air rune", "quantity": 5 }
{ "type": "ITEM_PICKED_UP", "itemId": 526, "itemName": "Bones", "quantity": 1 }
{ "type": "ITEM_EQUIPPED", "itemId": 4151, "itemName": "Abyssal whip" }
{ "type": "ITEM_UNEQUIPPED", "itemId": 4151, "itemName": "Abyssal whip" }
```

### Prayer Events

```json
{ "type": "PRAYER_ACTIVATED", "prayerId": 5, "prayerName": "Protect from Melee" }
{ "type": "PRAYER_DEACTIVATED", "prayerId": 5, "prayerName": "Protect from Melee" }
```

### Dialogue Events

```json
{
  "type": "DIALOGUE",
  "npc": "Hans",
  "text": "Hello, adventurer!",
  "options": ["Hello!", "Goodbye"]
}
{ "type": "DIALOGUE_OPTION_SELECTED", "option": "Hello!" }
```

### World Events

```json
{ "type": "NPC_SPAWNED", "npcId": 1624, "npcName": "Goblin", "position": {...} }
{ "type": "NPC_DESPAWNED", "npcId": 1624, "npcName": "Goblin" }
{ "type": "PROJECTILE_SPAWNED", "projectileId": 123, "remainingCycles": 5 }
{ "type": "PLAYER_MOVED", "position": { "x": 3201, "y": 3201, "plane": 0 } }
```

### Quest/Progress Events

```json
{ "type": "QUEST_STEP_CHANGED", "questName": "Dragon Slayer", "oldStep": 4, "newStep": 5 }
{ "type": "VARBIT_CHANGED", "varbitId": 1234, "oldValue": 5, "newValue": 6 }
{ "type": "QUEST_HELPER_STEP_CHANGED", "questKey": "cooks_assistant", "oldStepId": "cooks_assistant.step_1", "newStepId": "cooks_assistant.step_2" }
```

---

## `questHelper` Field (Full Quest Helper Payload)

In addition to the compact `quest` summary, each tick batch MAY carry a `questHelper`
map containing the full Quest Helper structured state. The field is **optional** and
backward compatible: old Micro6 clients omit it, and ChromaScape falls back to the
simple Quest panel when `questHelper` is absent.

```json
{
  "quest": { "name": "Cook's Assistant", "stepText": "Talk to the Cook.", "changed": false },
  "questHelper": {
    "schemaVersion": 1,
    "source": "micro6.questhelper",
    "collectionMode": "questhelper",
    "activeQuest": {
      "questKey": "cooks_assistant",
      "questName": "Cook's Assistant",
      "questState": "IN_PROGRESS",
      "questVarType": "VARBIT",
      "questVarId": 29,
      "questVarValue": 2,
      "started": true,
      "finished": false
    },
    "currentStep": {
      "stepId": "cooks_assistant.step_001_talk_to_the_cook",
      "index": 1,
      "text": "Talk to the Cook in Lumbridge Castle.",
      "status": "ACTIVE",
      "changedThisTick": false,
      "startedAtTick": 42100,
      "lastChangedAtTick": 42100,
      "source": "questhelper"
    },
    "steps": [
      {
        "stepId": "cooks_assistant.step_001_talk_to_the_cook",
        "index": 1,
        "text": "Talk to the Cook in Lumbridge Castle.",
        "status": "ACTIVE",
        "source": "questhelper",
        "requirements": [],
        "targets": []
      }
    ],
    "requirements": {
      "met": [
        {
          "type": "ITEM",
          "name": "Pot",
          "text": "Pot x1",
          "itemId": 1931,
          "met": true,
          "quantityRequired": 1,
          "quantityOwned": 1
        }
      ],
      "missing": [
        {
          "type": "ITEM",
          "name": "Pot of flour",
          "text": "Pot of flour x1",
          "itemId": 1933,
          "met": false,
          "quantityRequired": 1,
          "quantityOwned": 0
        }
      ],
      "blockedBy": [
        "Missing item requirement: Pot of flour x1"
      ]
    },
    "dialogue": { "active": false, "npcName": null, "text": null, "options": [], "recommendedOption": null },
    "targets": [
      {
        "type": "NPC",
        "id": 4626,
        "name": "Cook",
        "worldPoint": { "x": 3209, "y": 3215, "plane": 0 },
        "sourceStepId": "cooks_assistant.step_001_talk_to_the_cook"
      }
    ],
    "overlays": [],
    "varbits": {},
    "events": [
      {
        "type": "QUEST_HELPER_STEP_CHANGED",
        "tick": 42100,
        "questKey": "cooks_assistant",
        "questName": "Cook's Assistant",
        "oldStepId": null,
        "oldStepText": null,
        "newStepId": "cooks_assistant.step_001_talk_to_the_cook",
        "newStepText": "Talk to the Cook in Lumbridge Castle."
      }
    ],
    "debug": {
      "questHelperPluginEnabled": true,
      "activeHelperClass": "net.runelite.client.plugins.microbot.questhelper.questhelpers.quests.cooksassistant.CooksAssistant",
      "collectionMode": "questhelper",
      "fullSnapshotDue": true,
      "lastCollectionError": null
    }
  }
}
```

### `collectionMode` values

| Mode | Meaning |
|------|---------|
| `questhelper` | Full Quest Helper introspection succeeded. |
| `logging_fallback` | Quest Helper internals unavailable; current state derived from existing logs / active step. |
| `unavailable` | No quest helper or current quest data available. |
| `error` | Collector failed; `debug.lastCollectionError` is populated. |

**`logging_fallback` example** (Quest Helper plugin disabled or not installed):

```json
{
  "questHelper": {
    "schemaVersion": 1,
    "source": "micro6.questhelper",
    "collectionMode": "logging_fallback",
    "activeQuest": {
      "questKey": "cooks_assistant",
      "questName": "Cook's Assistant",
      "questState": "IN_PROGRESS",
      "started": true,
      "finished": false
    },
    "currentStep": {
      "stepId": "cooks_assistant.step_001_cooks_assistant",
      "index": 1,
      "text": "Cook's Assistant",
      "status": "ACTIVE",
      "changedThisTick": false,
      "source": "logging_fallback"
    },
    "steps": [],
    "requirements": { "met": [], "missing": [], "blockedBy": [] },
    "debug": {
      "questHelperPluginEnabled": false,
      "activeHelperClass": null,
      "collectionMode": "logging_fallback",
      "fullSnapshotDue": false,
      "lastCollectionError": null
    }
  }
}
```

**`unavailable` example** (no active quest / non-quest task):

```json
{
  "quest": { "active": false, "changed": false, "questHelperState": "INACTIVE" },
  "questHelper": {
    "schemaVersion": 1,
    "source": "micro6.questhelper",
    "collectionMode": "unavailable",
    "activeQuest": {},
    "currentStep": null,
    "debug": { "questHelperPluginEnabled": false, "collectionMode": "unavailable" }
  }
}
```

**`error` example** (collection threw an exception):

```json
{
  "quest": { "active": false, "changed": false, "questHelperState": "ERROR" },
  "questHelper": {
    "schemaVersion": 1,
    "source": "micro6.questhelper",
    "collectionMode": "error",
    "activeQuest": {},
    "currentStep": null,
    "debug": { "questHelperPluginEnabled": false, "collectionMode": "error", "lastCollectionError": "java.lang.NullPointerException" }
  }
}
```

### Field Notes

- `requirements.blockedBy` is `string[]`, not object array. Each entry is a human-readable
  description like `"Missing item requirement: Pot of flour x1"`. The UI defensively renders
  object-form blockers for backward compatibility.
- `debug.fullSnapshotDue` is metadata-only: `true` when a full snapshot was produced this tick
  (used for downstream hash/diff optimization if payload sizes grow).
- `debug.activeHelperClass` contains the fully-qualified Quest Helper internal class name
  when introspection succeeds, `null` otherwise.
- Step IDs use the stable format `{questKey}.step_{zeroPaddedIndex}_{sanitizedTextPrefix}`
  (e.g. `cooks_assistant.step_001_talk_to_the_cook`) for reliable replay step diffing.

### Persistence & Replay

- `questHelper` is persisted as the `quest_helper` JSON column on `tick_snapshots`
  (added automatically via `ddl-auto: update`).
- Live WebSocket messages and replay tick payloads include `questHelper` when present.
- `questHelper` is NOT required by strict replay validation (`validateStrictV2`); only
  the compact `quest` field is required.

### UI Behavior

- When `questHelper` is present, the debugger Quest panel renders the rich Quest Helper
  UI: active step, missing-requirement blocker chips, dialogue, a filterable step list
  (search / status / requirement filters, missing-only toggle, raw JSON toggle).
- When `questHelper` is absent, the existing simple Quest panel is rendered from `quest`.
- A `Source:` badge labels the data origin (`questhelper`, `logging fallback`, etc.).
- Filter state (search text, status dropdown, requirement filter, missing-only, raw toggle)
  is persisted globally so it does not reset when jumping between ticks in replay.
- Search matches step text, step IDs, requirement names/types, target names, dialogue text,
  and the active quest name.

### `QUEST_HELPER_STEP_CHANGED` Event

Emitted (as a `TickLogEntry` with `subsystem=QUEST_HELPER`) when the active Quest Helper
step id or step text changes between ticks. It appears in the Actions/log stream and is
searchable in replay.

```json
{
  "subsystem": "QUEST_HELPER",
  "level": "INFO",
  "eventType": "QUEST_HELPER_STEP_CHANGED",
  "message": "Cook's Assistant step changed: cooks_assistant.step_1 -> cooks_assistant.step_2",
  "details": {
    "questKey": "cooks_assistant",
    "questName": "Cook's Assistant",
    "oldStepId": "cooks_assistant.step_1",
    "newStepId": "cooks_assistant.step_2"
  }
}
```

---

## UI Processing

### State Reconstruction

The `TickStateManager` class handles state reconstruction:

1. **TICK_SNAPSHOT**: Store as baseline, use as current state
2. **TICK_DIFF**: Apply changes to current state using baseline

```javascript
const stateManager = getTickStateManager();
const result = stateManager.processMessage(message);

// result = { tick, state, events, isSnapshot }
```

### Rendering Strategy

**Actions Section**: Shows formatted events from `events` array

```javascript
events.forEach(event => {
  const message = formatTickEvent(event);
  addLiveLogEntry({ tick, source: event.type, message, ... });
});
```

**State Sections**: Show reconstructed full state

```javascript
renderPlayerSection(tick, state.player);
renderInventorySection(tick, state.inventory);
renderNpcsSection(tick, state.npcs);
// ... etc
```

---

## Debugging

### Console Commands

```javascript
// Get state manager statistics
window.debugger_getStateManagerStats()

// Request full snapshot re-sync
window.debugger_requestFullSnapshot()

// Reset state manager
window.debugger_resetStateManager()
```

### Example Output

```javascript
> window.debugger_getStateManagerStats()
{
  mode: "live",
  baseSnapshotTick: 1230,
  currentTick: 1245,
  historySize: 15,
  hasBaseSnapshot: true,
  hasCurrentState: true
}
```

---

## Migration Guide

### Micro6 → ChromaScape

**Phase 1**: Implement snapshot/diff in micro6 with feature flag OFF
**Phase 2**: Enable flag, test with ChromaScape
**Phase 3**: Monitor for drift, adjust snapshot interval

### Testing Checklist

- [ ] Full snapshots received and stored
- [ ] Diffs applied correctly to state
- [ ] Events formatted and displayed in Actions
- [ ] State sections show correct reconstructed data
- [ ] Inventory shows item names (not just IDs)
- [ ] Player state shows HP/Prayer/Position
- [ ] NPCs update correctly (added/updated/removed)
- [ ] No state drift after 100+ ticks

---

## Performance Metrics

### Bandwidth Comparison

| Format | Avg Size/Tick | Bandwidth (60 ticks) |
|--------|---------------|----------------------|
| Legacy | ~10 KB | ~600 KB |
| Snapshot (every 15) | ~10 KB | ~40 KB (4 snapshots) |
| Diff (other 56) | ~1.5 KB | ~84 KB |
| **Total New** | - | **~124 KB (79% reduction)** |

### Processing Time

- Snapshot processing: ~2-5ms
- Diff application: ~0.5-1ms
- Event formatting: ~0.1ms per event

---

## Error Handling

### Drift Detection

If `baseSnapshotTick` mismatch detected:
```javascript
console.warn(`Diff base mismatch: expected ${this.baseSnapshotTick}, got ${message.baseSnapshotTick}`);
// Request re-sync via requestFullSnapshot()
```

### Missing Baseline

If diff received without baseline:
```javascript
console.error('Received diff without baseline snapshot');
// Request full snapshot
```

### State Validation

Periodically compare reconstructed state with fresh snapshot to detect drift.

---

## See Also

- [MICRO6_DIFF_SNAPSHOT_STRATEGY.md](./MICRO6_DIFF_SNAPSHOT_STRATEGY.md) - Full implementation guide
- Micro6 `TickStateTracker` class
- ChromaScape `TickStateManager` class

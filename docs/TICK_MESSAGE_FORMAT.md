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

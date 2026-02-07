# Micro6 Diff/Snapshot Strategy

## Overview

Optimize tick data transmission by sending:
- **Full snapshots** every N ticks (baseline state)
- **Diffs** every tick (only what changed)
- UI reconstructs full state from last snapshot + diffs

This reduces payload size by 80-95% while maintaining full state visibility.

---

## Data Model

### Full Snapshot (Every 10-20 Ticks)

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
      { "slot": 2, "id": null, "name": null, "quantity": 0 }
      // ... all 28 slots
    ],
    "npcs": [
      {
        "index": 0,
        "id": 1624,
        "name": "Goblin",
        "hp": { "current": 5, "max": 5 },
        "position": { "x": 3205, "y": 3205, "plane": 0 },
        "distance": 7,
        "interacting": null,
        "animation": -1
      }
      // ... all visible NPCs
    ],
    "objects": [
      { "id": 1234, "name": "Tree", "position": { "x": 3210, "y": 3210, "plane": 0 } }
    ],
    "groundItems": [
      { "id": 526, "name": "Bones", "quantity": 1, "position": { "x": 3201, "y": 3201, "plane": 0 } }
    ],
    "varbits": {
      "1234": 5,
      "5678": 10
    },
    "quests": {
      "Dragon Slayer": { "step": 5, "status": "IN_PROGRESS" }
    }
  }
}
```

### Diff (Every Tick)

```json
{
  "type": "TICK_DIFF",
  "tick": 1235,
  "sessionId": "abc123",
  "baseSnapshotTick": 1230,  // Last full snapshot tick
  "changes": {
    "player": {
      "hp.current": 95,        // Changed from 99 to 95
      "prayer.current": 41,    // Changed from 43 to 41
      "animation": 1979        // Changed from 808 to 1979
    },
    "inventory": {
      "slots": [
        { "slot": 0, "quantity": 995 }  // Air runes decreased from 1000 to 995
      ]
    },
    "npcs": {
      "added": [
        { "index": 5, "id": 1625, "name": "Goblin", "hp": { "current": 5, "max": 5 }, ... }
      ],
      "updated": [
        { "index": 0, "hp.current": 3, "animation": 404 }  // Goblin took damage, started attack animation
      ],
      "removed": [2, 3]  // NPC indices that despawned
    },
    "groundItems": {
      "added": [
        { "id": 556, "name": "Air rune", "quantity": 5, "position": { "x": 3201, "y": 3201, "plane": 0 } }
      ],
      "removed": [
        { "position": { "x": 3205, "y": 3205, "plane": 0 }, "id": 526 }  // Bones picked up
      ]
    },
    "varbits": {
      "1234": 6  // Changed from 5 to 6
    }
  },
  "events": [
    {
      "type": "DAMAGE_TAKEN",
      "tick": 1235,
      "source": "Goblin",
      "amount": 4,
      "hitType": "MELEE"
    },
    {
      "type": "ITEM_DROPPED",
      "tick": 1235,
      "itemId": 556,
      "itemName": "Air rune",
      "quantity": 5
    },
    {
      "type": "SPELL_CAST",
      "tick": 1235,
      "spellName": "Fire Blast",
      "target": "Goblin"
    }
  ]
}
```

---

## Implementation: Micro6 Side

### 1. State Tracker with Diffing

```java
public class TickStateTracker {
    private static final int FULL_SNAPSHOT_INTERVAL = 15; // Every 15 ticks

    private TickSnapshot lastSnapshot;
    private int ticksSinceSnapshot = 0;

    public TickMessage buildTickMessage(int tick) {
        ticksSinceSnapshot++;

        if (ticksSinceSnapshot >= FULL_SNAPSHOT_INTERVAL || lastSnapshot == null) {
            // Send full snapshot
            TickSnapshot snapshot = captureFullSnapshot(tick);
            lastSnapshot = snapshot;
            ticksSinceSnapshot = 0;
            return new FullSnapshotMessage(tick, snapshot);
        } else {
            // Send diff
            TickSnapshot current = captureFullSnapshot(tick);
            TickDiff diff = computeDiff(lastSnapshot, current, tick);
            return new DiffMessage(tick, lastSnapshot.getTick(), diff);
        }
    }

    private TickDiff computeDiff(TickSnapshot baseline, TickSnapshot current, int tick) {
        TickDiff diff = new TickDiff();

        // Player diffs
        diffPlayer(baseline.getPlayer(), current.getPlayer(), diff);

        // Inventory diffs (only changed slots)
        diffInventory(baseline.getInventory(), current.getInventory(), diff);

        // NPC diffs (added/updated/removed)
        diffNpcs(baseline.getNpcs(), current.getNpcs(), diff);

        // Varbit diffs (only changed varbits)
        diffVarbits(baseline.getVarbits(), current.getVarbits(), diff);

        // Ground items diffs
        diffGroundItems(baseline.getGroundItems(), current.getGroundItems(), diff);

        return diff;
    }

    private void diffPlayer(Player baseline, Player current, TickDiff diff) {
        Map<String, Object> changes = new HashMap<>();

        if (baseline.getHp().getCurrent() != current.getHp().getCurrent()) {
            changes.put("hp.current", current.getHp().getCurrent());
        }
        if (baseline.getPrayer().getCurrent() != current.getPrayer().getCurrent()) {
            changes.put("prayer.current", current.getPrayer().getCurrent());
        }
        if (baseline.getAnimation() != current.getAnimation()) {
            changes.put("animation", current.getAnimation());
        }
        if (!baseline.getPosition().equals(current.getPosition())) {
            changes.put("position", current.getPosition());
        }

        if (!changes.isEmpty()) {
            diff.setPlayerChanges(changes);
        }
    }

    private void diffInventory(List<InventoryItem> baseline, List<InventoryItem> current, TickDiff diff) {
        List<InventorySlotChange> changes = new ArrayList<>();

        for (int i = 0; i < 28; i++) {
            InventoryItem baseItem = baseline.get(i);
            InventoryItem currItem = current.get(i);

            if (!Objects.equals(baseItem, currItem)) {
                changes.add(new InventorySlotChange(i, currItem));
            }
        }

        if (!changes.isEmpty()) {
            diff.setInventoryChanges(changes);
        }
    }

    private void diffNpcs(List<Npc> baseline, List<Npc> current, TickDiff diff) {
        // Build index maps
        Map<Integer, Npc> baselineMap = baseline.stream()
            .collect(Collectors.toMap(Npc::getIndex, Function.identity()));
        Map<Integer, Npc> currentMap = current.stream()
            .collect(Collectors.toMap(Npc::getIndex, Function.identity()));

        // Find added NPCs
        List<Npc> added = currentMap.values().stream()
            .filter(npc -> !baselineMap.containsKey(npc.getIndex()))
            .collect(Collectors.toList());

        // Find removed NPCs
        List<Integer> removed = baselineMap.keySet().stream()
            .filter(idx -> !currentMap.containsKey(idx))
            .collect(Collectors.toList());

        // Find updated NPCs (only send changed fields)
        List<NpcUpdate> updated = new ArrayList<>();
        for (Integer idx : currentMap.keySet()) {
            if (baselineMap.containsKey(idx)) {
                Npc baseNpc = baselineMap.get(idx);
                Npc currNpc = currentMap.get(idx);

                Map<String, Object> npcChanges = new HashMap<>();
                if (baseNpc.getHp().getCurrent() != currNpc.getHp().getCurrent()) {
                    npcChanges.put("hp.current", currNpc.getHp().getCurrent());
                }
                if (baseNpc.getAnimation() != currNpc.getAnimation()) {
                    npcChanges.put("animation", currNpc.getAnimation());
                }
                if (!baseNpc.getPosition().equals(currNpc.getPosition())) {
                    npcChanges.put("position", currNpc.getPosition());
                }

                if (!npcChanges.isEmpty()) {
                    updated.add(new NpcUpdate(idx, npcChanges));
                }
            }
        }

        if (!added.isEmpty() || !removed.isEmpty() || !updated.isEmpty()) {
            diff.setNpcChanges(new NpcDiff(added, updated, removed));
        }
    }
}
```

### 2. Event Tracking (Actions)

```java
public class TickEventCollector {
    private final List<TickEvent> events = new ArrayList<>();

    public void recordDamage(String source, int amount, HitType type) {
        events.add(new DamageEvent(source, amount, type));
    }

    public void recordItemDropped(int itemId, String itemName, int quantity) {
        events.add(new ItemDroppedEvent(itemId, itemName, quantity));
    }

    public void recordSpellCast(String spellName, String target) {
        events.add(new SpellCastEvent(spellName, target));
    }

    public void recordDialogue(String npc, String text, List<String> options) {
        events.add(new DialogueEvent(npc, text, options));
    }

    public List<TickEvent> consumeEvents() {
        List<TickEvent> result = new ArrayList<>(events);
        events.clear();
        return result;
    }
}
```

### 3. Message Types

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FullSnapshotMessage.class, name = "TICK_SNAPSHOT"),
    @JsonSubTypes.Type(value = DiffMessage.class, name = "TICK_DIFF")
})
public abstract class TickMessage {
    protected int tick;
    protected String sessionId;
}

public class FullSnapshotMessage extends TickMessage {
    private String snapshotType = "FULL";
    private TickSnapshot state;
}

public class DiffMessage extends TickMessage {
    private int baseSnapshotTick;
    private TickDiff changes;
    private List<TickEvent> events;
}
```

---

## Implementation: ChromaScape UI Side

### 1. State Reconstruction

```javascript
class TickStateManager {
  constructor() {
    this.baseSnapshot = null;
    this.baseSnapshotTick = null;
    this.currentState = null;
  }

  processMessage(message) {
    if (message.type === 'TICK_SNAPSHOT') {
      // Full snapshot - use as new baseline
      this.baseSnapshot = message.state;
      this.baseSnapshotTick = message.tick;
      this.currentState = JSON.parse(JSON.stringify(message.state)); // Deep copy
      return {
        tick: message.tick,
        state: this.currentState,
        events: []
      };
    } else if (message.type === 'TICK_DIFF') {
      // Diff - apply changes to current state
      if (!this.currentState) {
        console.error('Received diff without baseline snapshot');
        return null;
      }

      this.applyDiff(this.currentState, message.changes);

      return {
        tick: message.tick,
        state: this.currentState,
        events: message.events || []
      };
    }
  }

  applyDiff(state, changes) {
    // Apply player changes
    if (changes.player) {
      for (const [key, value] of Object.entries(changes.player)) {
        this.setNestedValue(state.player, key, value);
      }
    }

    // Apply inventory changes
    if (changes.inventory?.slots) {
      changes.inventory.slots.forEach(change => {
        state.inventory[change.slot] = change;
      });
    }

    // Apply NPC changes
    if (changes.npcs) {
      // Add new NPCs
      if (changes.npcs.added) {
        state.npcs.push(...changes.npcs.added);
      }

      // Update existing NPCs
      if (changes.npcs.updated) {
        changes.npcs.updated.forEach(update => {
          const npc = state.npcs.find(n => n.index === update.index);
          if (npc) {
            for (const [key, value] of Object.entries(update.changes)) {
              this.setNestedValue(npc, key, value);
            }
          }
        });
      }

      // Remove NPCs
      if (changes.npcs.removed) {
        state.npcs = state.npcs.filter(npc =>
          !changes.npcs.removed.includes(npc.index)
        );
      }
    }

    // Apply varbit changes
    if (changes.varbits) {
      Object.assign(state.varbits, changes.varbits);
    }

    // Apply ground item changes
    if (changes.groundItems) {
      if (changes.groundItems.added) {
        state.groundItems.push(...changes.groundItems.added);
      }
      if (changes.groundItems.removed) {
        changes.groundItems.removed.forEach(removed => {
          const idx = state.groundItems.findIndex(item =>
            item.position.x === removed.position.x &&
            item.position.y === removed.position.y &&
            item.position.plane === removed.position.plane &&
            item.id === removed.id
          );
          if (idx >= 0) {
            state.groundItems.splice(idx, 1);
          }
        });
      }
    }
  }

  setNestedValue(obj, path, value) {
    const parts = path.split('.');
    let current = obj;
    for (let i = 0; i < parts.length - 1; i++) {
      current = current[parts[i]];
    }
    current[parts[parts.length - 1]] = value;
  }
}
```

### 2. Rendering Strategy

```javascript
function updatePanelsForTick(message) {
  const stateManager = getOrCreateStateManager();
  const result = stateManager.processMessage(message);

  if (!result) return;

  const { tick, state, events } = result;

  // Store full reconstructed state as snapshot
  storeTickSnapshot(tick, state);

  // Render events in Actions section
  if (events.length > 0) {
    events.forEach(event => {
      addLiveLogEntry({
        tick: tick,
        source: event.type,
        level: 'INFO',
        message: formatEvent(event),
        kind: 'tick'
      });
    });
  }

  // Render state sections (use full reconstructed state)
  renderPlayerSection(tick, state.player);
  renderInventorySection(tick, state.inventory);
  renderNpcsSection(tick, state.npcs);
  renderObjectsSection(tick, state.objects);
  renderGroundItemsSection(tick, state.groundItems);
  renderVarbitsSection(tick, state.varbits);
}

function formatEvent(event) {
  switch (event.type) {
    case 'DAMAGE_TAKEN':
      return `Took ${event.amount} damage from ${event.source} (${event.hitType})`;
    case 'ITEM_DROPPED':
      return `Dropped ${event.quantity}x ${event.itemName}`;
    case 'SPELL_CAST':
      return `Cast ${event.spellName} on ${event.target}`;
    case 'DIALOGUE':
      return `${event.npc}: "${event.text}"`;
    default:
      return JSON.stringify(event);
  }
}
```

---

## Benefits

1. **Bandwidth Reduction**: 80-95% smaller payloads
   - Full snapshot: ~10KB
   - Diff: ~500 bytes - 2KB
   - Average over 15 ticks: ~1.2KB/tick vs 10KB/tick

2. **UI Responsiveness**: Smaller messages = faster processing

3. **State Consistency**: Full snapshots every N ticks ensure sync

4. **Event Visibility**: Separate events array shows what happened this tick

5. **Backward Compatible**: Old clients can ignore diffs, request full snapshots

---

## Rollout Plan

### Phase 1: Add Diff Support (No Breaking Changes)
- Implement TickStateTracker in micro6
- Send both FULL snapshots and DIFFs (feature flag)
- UI ignores DIFFs initially

### Phase 2: UI Diff Support
- Implement TickStateManager in ChromaScape
- UI processes both message types
- Verify state reconstruction accuracy

### Phase 3: Optimize Snapshot Interval
- Start with FULL every 15 ticks
- Monitor state drift
- Adjust interval based on accuracy vs bandwidth

### Phase 4: Enable by Default
- Flip feature flag
- Monitor for issues
- Fallback to FULL-only if needed

---

## Configuration

```yaml
# micro6 config
tick-streaming:
  snapshot-interval: 15  # Send full snapshot every N ticks
  enable-diffs: true
  compress-snapshots: true  # Optional: gzip large snapshots
```

---

## Testing Strategy

1. **Unit Tests**: Verify diff computation accuracy
2. **Integration Tests**: Send 1000 ticks, verify state reconstruction matches
3. **Payload Size Tests**: Measure bandwidth savings
4. **Stress Tests**: 100+ NPCs, verify performance
5. **Drift Detection**: Compare reconstructed state vs actual state every full snapshot

---

## Next Steps

1. Implement `TickStateTracker` in micro6
2. Implement diff computation for all entity types
3. Implement `TickStateManager` in ChromaScape UI
4. Add configuration and feature flags
5. Test with real game scenarios
6. Monitor and tune snapshot interval

This strategy gives you **massive bandwidth savings** while maintaining **full state visibility** in the debugger.

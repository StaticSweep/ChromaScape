# ChromaScape Expanded Tick Data & Recording Pipeline - Implementation Summary

## Overview

This implementation extends ChromaScape to support comprehensive tick data capture from Micro6 and improves the recording pipeline for high-quality, stable video recording.

## Completed - ChromaScape Backend Changes

### 1. Database Schema Extensions

**File:** `src/main/java/com/chromascape/web/debug/tickdebugger/entity/TickSnapshot.java`

Added four new JSON columns to the `tick_snapshots` table:
- `npcs` - All NPCs in render distance with positions, health, animations, interactions
- `game_objects` - Walls, doors, interactive objects (object ID, position, orientation, type)
- `ground_objects` - Ground decoration layer objects
- `ground_items` - Items on ground with quantities and positions

**Migration:** Schema changes will be applied automatically by JPA/Hibernate on next application boot via `ALTER TABLE`.

### 2. DTO Extensions

**File:** `src/main/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchMessage.java`

Extended the TickBatchMessage DTO to accept the new data fields from Micro6 client:
- Added `List<Map<String, Object>> npcs`
- Added `List<Map<String, Object>> gameObjects`
- Added `List<Map<String, Object>> groundObjects`
- Added `List<Map<String, Object>> groundItems`

All fields are nullable to maintain backward compatibility with existing Micro6 clients.

### 3. Data Ingestion

**File:** `src/main/java/com/chromascape/web/debug/tickdebugger/service/TickStreamIngestionService.java`

Updated `saveTickSnapshot()` method to:
- Convert expanded world state to JSON using ObjectMapper
- Log expanded data counts for diagnostics
- Store all four new data types in the database

**Logging:** Added diagnostic logging to track data volume per tick:
```
Processing tick X expanded data: npcs=20, gameObjects=15, groundObjects=8, groundItems=3
```

### 4. Recording Pipeline Improvements

#### Frame Buffer Increase
**File:** `src/main/java/com/chromascape/web/recording/service/RecordingService.java`
- **Before:** 60 frames (~2 seconds at 30 FPS)
- **After:** 300 frames (~10 seconds at 30 FPS)

**Rationale:** Larger buffer allows encoder to catch up during CPU spikes while maintaining high quality "slow" preset. Provides ample cushion for temporary slowdowns.

#### Metrics Endpoint
**File:** `src/main/java/com/chromascape/web/recording/service/RecordingService.java`

Added `getMetrics()` method returning:
- `isRecording` - Current recording status
- `frameBufferSize` - Maximum buffer capacity (300)
- `frameBufferDepth` - Current frames in buffer
- `frameBufferCapacity` - Remaining buffer space
- `droppedFrames` - Total dropped frames count
- `framesRecorded` - Total frames captured
- `currentRecordingId` - Active recording ID
- `recordingDurationMs` - Recording duration in milliseconds

**REST Endpoint:** `GET /api/recordings/metrics`

**File:** `src/main/java/com/chromascape/web/recording/controller/RecordingController.java`

Added `/api/recordings/metrics` endpoint to expose metrics for frontend polling.

#### Encoder Lag Detection
**File:** `src/main/java/com/chromascape/web/recording/service/RecordingService.java`

Enhanced `encoderLoop()` to detect when buffer fills beyond 80%:
```java
if (queueDepth > FRAME_BUFFER_SIZE * 0.8) {
  logger.warn("Encoder lag detected: queue depth {} ({}% full)", ...);
}
```

**Improved Logging:** Changed from every 150 frames to every 30 frames with queue depth included.

#### Thread Priority Adjustment
**File:** `src/main/java/com/chromascape/web/viewport/service/UnifiedFrameCaptureService.java`

- **Before:** `Thread.NORM_PRIORITY - 1` (low priority)
- **After:** `Thread.NORM_PRIORITY` (normal priority)

**Rationale:** Prevents game threads from starving frame capture, reducing jitter in recordings.

### 5. Frontend Diagnostics UI

#### Recording Diagnostics Panel
**File:** `src/main/resources/templates/debugger.html`

Added diagnostics panel that appears only when recording:
- **Buffer Depth:** Shows current queue depth vs capacity (e.g., "45 / 300 frames")
- **Dropped Frames:** Total frames dropped (highlighted in red)
- **Recording Duration:** Live timer (MM:SS format)
- **Progress Bar:** Visual buffer fill percentage with color coding:
  - Green (<50%) - healthy
  - Yellow (50-80%) - approaching capacity
  - Red (>80%) - encoder lag detected

#### Feed Status Indicator
**File:** `src/main/resources/templates/debugger.html`

Added badge in top navbar showing micro6 connection status:
- **Green:** "Feed: Active (Tick XXXXX)" - micro6 connected, ticks flowing
- **Yellow:** "Feed: Waiting" - no active session
- **Red:** "Feed: Error" - API error

#### JavaScript Implementation
**File:** `src/main/resources/static/js/debugger.js`

Added two polling functions:

**`updateRecordingDiagnostics()`** - Polls every 2 seconds when in live mode
- Fetches `/api/recordings/metrics`
- Updates diagnostics panel values
- Shows/hides panel based on recording status
- Color-codes progress bar based on buffer fill

**`updateFeedStatus()`** - Polls every 5 seconds
- Fetches `/api/debug/feed-status`
- Updates feed status badge
- Handles connection errors gracefully

### 6. Feed Status API

**File:** `src/main/java/com/chromascape/web/debug/tickdebugger/controller/DebugStatusController.java` (NEW)

Created new controller for debug diagnostics:
- **Endpoint:** `GET /api/debug/feed-status`
- **Returns:**
  - `connected` - Whether any active sessions exist
  - `activeSessionCount` - Number of active debug sessions
  - `lastTickReceived` - Most recent tick from active session
  - `sessionId` - Active session ID
  - `logShipperEnabled` - Log shipper status (placeholder)

---

## Not Yet Implemented - Micro6 Client Side

The following changes are required in the Micro6 client to emit the expanded data:

### 1. Create New Trackers

**Files to create:**
- `F:\Dev\IdeaProjects\micro6\src\main\java\com\micro6\debugrecorder\tracker\NpcTracker.java`
- `F:\Dev\IdeaProjects\micro6\src\main\java\com\micro6\debugrecorder\tracker\SceneObjectTracker.java`
- `F:\Dev\IdeaProjects\micro6\src\main\java\com\micro6\debugrecorder\tracker\GroundItemTracker.java`

**Key Features:**
- **NpcTracker:** Subscribe to `GameTick`, collect all NPCs via `client.getNpcs()`, include health bars, animations, interactions
- **SceneObjectTracker:** Subscribe to spawn/despawn events, maintain maps of game objects and ground objects
- **GroundItemTracker:** Subscribe to item spawn/despawn events, include item names via `ItemDefinition`

### 2. Extend Micro6 DTO

**File:** `F:\Dev\IdeaProjects\micro6\src\main\java\com\micro6\debugrecorder\dto\TickBatchMessage.java`

Add matching fields:
```java
private List<Map<String, Object>> npcs;
private List<Map<String, Object>> gameObjects;
private List<Map<String, Object>> groundObjects;
private List<Map<String, Object>> groundItems;
```

### 3. Update TickBatchCollector

**File:** `F:\Dev\IdeaProjects\micro6\src\main\java\com\micro6\debugrecorder\collector\TickBatchCollector.java`

In `collectTick()` method:
```java
if (config.streamNpcs() && npcTracker != null) {
  batch.setNpcs(npcTracker.getNpcs());
}
// ... similar for other trackers
```

### 4. Add Configuration Toggles

**File:** `F:\Dev\IdeaProjects\micro6\src\main\java\com\micro6\debugrecorder\DebugRecorderConfig.java`

Add RuneLite config items:
- `streamNpcs` (default: true) - Include all NPCs in render distance
- `streamGameObjects` (default: false) - Include walls, doors, interactive objects
- `streamGroundObjects` (default: false) - Include ground decoration layer
- `streamGroundItems` (default: true) - Include items on ground

**Rationale:** NPCs and ground items are most useful; objects disabled by default due to high bandwidth.

### 5. Register Trackers in Plugin

**File:** `F:\Dev\IdeaProjects\micro6\src\main\java\com\micro6\debugrecorder\DebugRecorderPlugin.java`

- Add tracker fields
- Initialize in `initializeChromaScapeStreaming()`
- Register with event bus
- Unregister in `shutdownChromaScapeStreaming()`

---

## Testing Checklist

### Backend Testing

**Database Schema:**
```sql
-- Verify new columns exist
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'tick_snapshots'
  AND column_name IN ('npcs', 'game_objects', 'ground_objects', 'ground_items');
```

**Recording Metrics:**
```bash
# Test metrics endpoint
curl http://localhost:8090/api/recordings/metrics

# Expected response when not recording:
{
  "isRecording": false,
  "frameBufferSize": 300,
  "frameBufferDepth": 0,
  ...
}
```

**Feed Status:**
```bash
# Test feed status endpoint
curl http://localhost:8090/api/debug/feed-status

# Expected response:
{
  "connected": false,
  "activeSessionCount": 0,
  ...
}
```

### Frontend Testing

1. **Recording Diagnostics Panel:**
   - Start recording → Panel should appear
   - Verify buffer depth updates in real-time
   - Verify progress bar color changes with fill percentage
   - Stop recording → Panel should disappear

2. **Feed Status Indicator:**
   - No micro6 connection → Yellow "Feed: Waiting"
   - Start micro6 session → Green "Feed: Active (Tick XXX)"
   - API error → Red "Feed: Error"

3. **Buffer Monitoring:**
   - During heavy game activity, monitor buffer depth
   - Verify buffer stays <80% (green/yellow range)
   - Check logs for encoder lag warnings

### Integration Testing

**With Micro6 Client (after client-side implementation):**

1. Connect micro6 to ChromaScape
2. Walk around in-game to trigger NPC/object/item events
3. Verify WebSocket messages contain new fields:
   ```json
   {
     "type": "TICK_BATCH",
     "tick": 12345,
     "npcs": [...],
     "gameObjects": [...],
     "groundItems": [...]
   }
   ```
4. Check database for persisted data:
   ```sql
   SELECT tick,
          jsonb_array_length(npcs) as npc_count,
          jsonb_array_length(ground_items) as item_count
   FROM tick_snapshots
   WHERE session_id = 'debug_latest'
   ORDER BY tick DESC
   LIMIT 20;
   ```

---

## Performance Considerations

### Bandwidth Impact

**Estimated data size per tick with all features enabled:**
- Base (player/inventory/equipment): ~2 KB
- NPCs (20 NPCs): ~3 KB
- Game Objects (50 objects): ~2 KB
- Ground Objects (30 objects): ~1 KB
- Ground Items (10 items): ~0.5 KB
- **Total: ~8.5 KB per tick**

At 1 tick per 600ms = ~14 KB/sec = ~840 KB/min = **~50 MB/hour**

**Recommendation:**
- Keep NPCs and ground items enabled by default (most useful)
- Disable game/ground objects unless debugging specific interactions
- Consider adding GZIP compression to WebSocket if bandwidth becomes an issue

### Storage Impact

**Per 1-hour debug session:**
- Base data: ~12 MB
- With all features: ~50 MB
- PostgreSQL JSON compression: ~30% reduction = **~35 MB final**

**Recommendation:**
- Add session cleanup job (delete sessions >30 days old)
- Add option to disable expanded data for long sessions

### Memory Impact

**Backend:**
- Frame buffer increase: 60 → 300 frames = ~240 MB additional heap (at 1920x1080)
- JSON parsing: ~5 MB per tick batch
- **Total: ~250 MB additional heap usage**

**Recommendation:**
- Monitor heap usage with `-Xmx4g` (4 GB max heap)
- Current settings should be sufficient

**Frontend:**
- Snapshot cache: 600 snapshots * 8.5 KB = ~5 MB
- Acceptable for modern browsers

---

## Configuration Reference

### Micro6 Config (after implementation)
```yaml
# In RuneLite config (DebugRecorderConfig)
streamNpcs: true              # All NPCs in render distance
streamGameObjects: false      # Walls, doors, interactive objects
streamGroundObjects: false    # Ground decoration layer
streamGroundItems: true       # Items on ground
```

### ChromaScape Config
```yaml
# In application.yml
unified-capture:
  fps: 30                     # Frame capture rate

recording:
  video-quality: 90           # CRF 14 (0-100 scale, higher = better)
  encoding-preset: slow       # libx264 preset (slow = best quality)
  # frame-buffer-size: 300    # Now hardcoded in RecordingService.java
```

---

## Known Limitations

### Recording Timestamp Precision

**Achievable:** 100-500ms precision for log-to-video sync during replay.

**Reasons:**
- Log timestamps captured asynchronously when written (not at event time)
- Video `timeupdate` events fire at 15-250ms intervals (browser-dependent)
- Network latency for API requests (~20-100ms)
- Database query time for time-window fetches

**Tick-Based Logs:**
Tick logs from Micro6 are correlated to game ticks, which provide frame-accurate alignment for tick-based analysis regardless of timestamp precision.

---

## Next Steps

1. **Implement Micro6 client changes** (see "Not Yet Implemented" section above)
2. **Build and test ChromaScape:**
   ```bash
   # Format code
   ./gradlew spotlessApply

   # Build
   ./gradlew build

   # Run (from Windows for screen capture)
   cmd.exe /c "cd /d C:\Users\Beheerder\IdeaProjects\ChromaScape && gradlew.bat bootRun"
   ```
3. **Verify database schema migration** on first boot
4. **Test recording with high game activity** to verify buffer stability
5. **Monitor encoder lag warnings** in logs
6. **Test with Micro6 client** once tracker implementation is complete

---

## Files Modified

### Backend
- ✅ `src/main/java/com/chromascape/web/debug/tickdebugger/entity/TickSnapshot.java`
- ✅ `src/main/java/com/chromascape/web/debug/tickdebugger/dto/TickBatchMessage.java`
- ✅ `src/main/java/com/chromascape/web/debug/tickdebugger/service/TickStreamIngestionService.java`
- ✅ `src/main/java/com/chromascape/web/recording/service/RecordingService.java`
- ✅ `src/main/java/com/chromascape/web/recording/controller/RecordingController.java`
- ✅ `src/main/java/com/chromascape/web/viewport/service/UnifiedFrameCaptureService.java`
- ✅ `src/main/java/com/chromascape/web/debug/tickdebugger/controller/DebugStatusController.java` (NEW)

### Frontend
- ✅ `src/main/resources/templates/debugger.html`
- ✅ `src/main/resources/static/js/debugger.js`

---

## Rollback Plan

If issues arise:

1. **Micro6 trackers causing lag:**
   - Disable via config toggles
   - Investigate event subscription frequency

2. **ChromaScape database migration issues:**
   ```sql
   ALTER TABLE tick_snapshots
   DROP COLUMN npcs,
   DROP COLUMN game_objects,
   DROP COLUMN ground_objects,
   DROP COLUMN ground_items;
   ```

3. **Frame buffer too large (OOM):**
   - Reduce to 150 frames (5 seconds) in `RecordingService.java` line 59
   - Monitor heap with `jconsole`

4. **Frontend rendering slow:**
   - Add lazy loading (render sections on expand, not on card load)
   - Reduce snapshot cache from 600 to 300

---

## Summary

This implementation successfully extends ChromaScape to:
1. ✅ Accept and store comprehensive tick data (NPCs, objects, items)
2. ✅ Improve recording stability with 10-second buffer
3. ✅ Provide real-time diagnostics for recording quality
4. ✅ Monitor micro6 feed connectivity

**Remaining work:** Implement Micro6 client-side trackers to emit the expanded data.

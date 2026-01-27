# Plan: Fix Viewport Synchronization, FPS Issues, and Add FPS Control

## Problem Summary

The ChromaScape debugger viewport is experiencing:
- Frame desynchronization - old/stale frames appearing after newer frames
- Very low FPS (15 FPS) causing laggy viewport
- No UI control to adjust FPS
- No frame sequencing to identify frame order

## Root Causes Identified

1. **Low default FPS** - 15 FPS is too low for smooth viewport experience
2. **No runtime FPS control** - Users cannot adjust FPS without restarting application
3. **No frame sequencing** - Client cannot identify frame order
4. **WebSocket message reordering** - Cached thread pool sends frames out of order
5. **No render cycle sync** - Updates happen immediately on WebSocket message
6. **Async decode race conditions** - createImageBitmap resolves out of order

## Prerequisites (IMPORTANT)

Database and application must be running:
```bash
docker-compose up postgres
cmd.exe /c "cd /d C:\Users\Beheerder\IdeaProjects\ChromaScape && gradlew.bat bootRun"
```

## Implementation Plan

### Phase 1: Increase Default FPS and Add Runtime FPS Control

#### 1.1 Update application.yml
**File:** `src/main/resources/application.yml`

**Changes:**
- Change `unified-capture.fps` default from 15 to 30:
  ```yaml
  unified-capture:
    enabled: ${UNIFIED_CAPTURE_ENABLED:true}
    fps: ${UNIFIED_CAPTURE_FPS:30}  # Changed from 15 to 30
  ```

#### 1.2 Add FPS Control API Endpoint
**File:** `src/main/java/com/chromascape/web/viewport/ViewportController.java`

**Create new controller or add to existing:**
```java
package com.chromascape.web.viewport;

import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/viewport")
public class ViewportController {

  @Autowired private UnifiedCaptureConfig captureConfig;
  @Autowired private UnifiedFrameCaptureService captureService;

  @GetMapping("/fps")
  public ResponseEntity<Integer> getCurrentFps() {
    return ResponseEntity.ok(captureConfig.getFps());
  }

  @PostMapping("/fps")
  public ResponseEntity<String> setFps(@RequestParam int fps) {
    try {
      captureConfig.setFps(fps); // Validates 1-60 range
      captureService.restartCapture(); // Restart with new FPS
      return ResponseEntity.ok("FPS updated to " + fps);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }
}
```

#### 1.3 Add Restart Method to UnifiedFrameCaptureService
**File:** `src/main/java/com/chromascape/web/viewport/service/UnifiedFrameCaptureService.java`

**Add method:**
```java
/**
 * Restarts the capture scheduler with updated FPS.
 * Call this after changing UnifiedCaptureConfig.fps at runtime.
 */
public synchronized void restartCapture() {
  if (!capturing) {
    return; // Not running, nothing to restart
  }

  logger.info("Restarting frame capture with new FPS: {}", config.getFps());
  stopCapture();
  startCapture();
}
```

#### 1.4 Add FPS Dropdown to Debugger UI
**File:** `src/main/resources/templates/debugger.html`

**Add after window selector dropdown (around line 44):**
```html
<!-- FPS Control -->
<div class="dropdown ms-2">
  <button class="btn btn-outline-secondary dropdown-toggle" type="button" id="fpsDropdown"
    data-bs-toggle="dropdown" aria-expanded="false">
    <i class="bi bi-speedometer2 me-1"></i><span id="currentFpsLabel">30 FPS</span>
  </button>
  <ul class="dropdown-menu" aria-labelledby="fpsDropdown">
    <li><h6 class="dropdown-header">Viewport FPS</h6></li>
    <li><a class="dropdown-item" href="#" onclick="setViewportFps(60)">60 FPS (Smooth)</a></li>
    <li><a class="dropdown-item active" href="#" onclick="setViewportFps(30)">30 FPS (Balanced)</a></li>
    <li><a class="dropdown-item" href="#" onclick="setViewportFps(15)">15 FPS (Low CPU)</a></li>
    <li><a class="dropdown-item" href="#" onclick="setViewportFps(10)">10 FPS (Very Low)</a></li>
  </ul>
</div>
```

#### 1.5 Add FPS Control JavaScript
**File:** `src/main/resources/static/js/debugger.js`

**Add after connectViewportWebSocket() function:**
```javascript
/**
 * Changes the viewport capture FPS at runtime
 */
async function setViewportFps(fps) {
  try {
    const response = await fetch(`/api/viewport/fps?fps=${fps}`, {
      method: 'POST'
    });

    if (response.ok) {
      document.getElementById('currentFpsLabel').textContent = `${fps} FPS`;

      // Update active state in dropdown
      document.querySelectorAll('#fpsDropdown + .dropdown-menu .dropdown-item').forEach(item => {
        item.classList.remove('active');
        if (item.textContent.includes(`${fps} FPS`)) {
          item.classList.add('active');
        }
      });

      console.log(`Viewport FPS changed to ${fps}`);
    } else {
      const error = await response.text();
      console.error('Failed to change FPS:', error);
      alert(`Failed to change FPS: ${error}`);
    }
  } catch (error) {
    console.error('Error changing FPS:', error);
    alert('Error changing FPS. Check console for details.');
  }
}

/**
 * Load current FPS setting on page load
 */
async function loadCurrentFps() {
  try {
    const response = await fetch('/api/viewport/fps');
    if (response.ok) {
      const fps = await response.json();
      document.getElementById('currentFpsLabel').textContent = `${fps} FPS`;

      // Set active state
      document.querySelectorAll('#fpsDropdown + .dropdown-menu .dropdown-item').forEach(item => {
        if (item.textContent.includes(`${fps} FPS`)) {
          item.classList.add('active');
        }
      });
    }
  } catch (error) {
    console.warn('Could not load current FPS:', error);
  }
}
```

**Add to DOMContentLoaded:**
```javascript
// In DOMContentLoaded, after existing initialization
loadCurrentFps();
```

### Phase 2: Server-Side Desync Fixes

#### 2.1 Update WebsocketViewport.java
**File:** `src/main/java/com/chromascape/web/viewport/WebsocketViewport.java`

**Changes:**
- Replace `AtomicReference<BufferedImage>` with `BlockingQueue<BufferedImage>` (capacity: 3)
- Add `AtomicLong frameCounter` for sequence numbering
- Change `volatile boolean isProcessing` to `AtomicBoolean workerRunning`
- Modify `updateState()`:
  - If queue is full, drop oldest frame via `frameQueue.poll()`
  - Add new frame via `frameQueue.offer(image)`
  - Start worker if not running
- Modify `processPendingUpdate()`:
  - Process all frames in queue
  - Assign sequence number: `long seq = frameCounter.incrementAndGet()`
  - Send JSON payload: `{"seq": N, "data": "data:image/png;base64,..."}`

**Imports to add:**
```java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
```

#### 2.2 Update ViewportWebSocketHandler.java
**File:** `src/main/java/com/chromascape/web/viewport/ViewportWebSocketHandler.java`

**Changes:**
- Replace `Executors.newCachedThreadPool()` with single-threaded executor:
  ```java
  private final ExecutorService wsExecutor =
      Executors.newSingleThreadExecutor(r -> {
          Thread t = new Thread(r, "ViewportBroadcast");
          t.setDaemon(true);
          return t;
      });
  ```

**Rationale:** WebSocketSession.sendMessage is not thread-safe. Single-threaded executor guarantees frame ordering.

### Phase 3: Client-Side HTML Changes

#### 3.1 Update debugger.html
**File:** `src/main/resources/templates/debugger.html`

**Changes:**
- Replace `<img id="viewport-image">` with:
  ```html
  <canvas id="viewport-canvas" class="viewport-content" style="display: none;"></canvas>
  ```
- Keep overlay canvas in same stacking order

### Phase 4: Client-Side CSS Changes

#### 4.1 Update debugger.css
**File:** `src/main/resources/static/css/debugger.css`

**Changes:**
- Add viewport canvas to existing rules:
  ```css
  #viewport-canvas,
  #viewport-image,
  #replayVideo {
    max-width: 100%;
    max-height: 100%;
    display: none;
    object-fit: contain;
  }

  #viewport-canvas,
  #overlay-canvas {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    pointer-events: none;
  }
  ```

### Phase 5: Client-Side JavaScript Changes

#### 5.1 Add Frame Sync State Variables
**File:** `src/main/resources/static/js/debugger.js`

After the existing WebSocket declarations, add:
```javascript
// Viewport frame synchronization state
let lastDrawnSeq = 0;
let latestSeq = 0;
let pendingBitmap = null;
let pendingSeq = 0;
let rafScheduled = false;
let decodeInFlight = false;
let viewportCanvas = null;
let viewportCtx = null;
```

#### 5.2 Update cacheElements()
Replace `elements.viewportImage` with:
```javascript
elements.viewportCanvas = document.getElementById('viewport-canvas');
viewportCanvas = elements.viewportCanvas;
viewportCtx = elements.viewportCanvas?.getContext('2d');
```

#### 5.3 Update switchMode()
Replace all references to `elements.viewportImage` with `elements.viewportCanvas`

#### 5.4 Replace viewportWebSocket.onmessage
**Location:** Around line 2031

Replace the existing onmessage handler with:
```javascript
viewportWebSocket.onmessage = async (event) => {
  if (currentMode !== 'live') return;
  elements.viewportLoader.style.display = 'none';
  elements.viewportCanvas.style.display = 'block';

  let payload, seq, dataUrl;

  // Try parse JSON first (new server with metadata)
  try {
    payload = JSON.parse(event.data);
    seq = payload.seq;
    dataUrl = payload.data;
  } catch {
    // Fallback: raw data URI (old server without metadata)
    seq = ++latestSeq;
    dataUrl = event.data;
  }

  // Track highest seq seen
  if (seq > latestSeq) latestSeq = seq;

  // Drop immediately if already drawn
  if (seq <= lastDrawnSeq) return;

  // Wait for existing decode to finish
  if (decodeInFlight) return;

  decodeInFlight = true;
  try {
    const bitmap = await decodeFrame(dataUrl);

    // Discard if stale (out-of-order decode completion)
    if (seq < latestSeq || seq <= lastDrawnSeq) {
      if (bitmap?.close) bitmap.close();
      return;
    }

    pendingBitmap = bitmap;
    pendingSeq = seq;

    // Schedule single RAF render
    if (!rafScheduled) {
      rafScheduled = true;
      requestAnimationFrame(renderViewportFrame);
    }
  } catch (err) {
    console.warn('Frame decode failed:', err);
  } finally {
    decodeInFlight = false;
  }
};
```

#### 5.5 Add Helper Functions
**Location:** After connectViewportWebSocket(), before overlay section

Add these new functions:
```javascript
/**
 * Decode a data URL into an ImageBitmap or Image element.
 * Tries createImageBitmap first (faster, off-main-thread), falls back to Image.
 */
async function decodeFrame(dataUrl) {
  // Try createImageBitmap first (faster, off-main-thread)
  if (window.createImageBitmap) {
    try {
      const response = await fetch(dataUrl);
      const blob = await response.blob();
      return await createImageBitmap(blob);
    } catch (e) {
      console.warn('createImageBitmap failed, using fallback:', e);
    }
  }

  // Fallback: Image element with Promise wrapper
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = reject;
    img.src = dataUrl;
  });
}

/**
 * Render the pending frame to the viewport canvas.
 * Called via requestAnimationFrame for smooth rendering.
 */
function renderViewportFrame() {
  rafScheduled = false;

  if (!pendingBitmap || !viewportCtx) return;

  const bitmap = pendingBitmap;
  const seq = pendingSeq;
  pendingBitmap = null;
  pendingSeq = 0;

  // Final staleness check
  if (seq < latestSeq) {
    if (bitmap?.close) bitmap.close();
    return;
  }

  // Resize canvas to match bitmap
  if (elements.viewportCanvas.width !== bitmap.width) {
    elements.viewportCanvas.width = bitmap.width;
    elements.viewportCanvas.height = bitmap.height;
  }

  // Clear and draw
  viewportCtx.clearRect(0, 0, bitmap.width, bitmap.height);
  viewportCtx.drawImage(bitmap, 0, 0);

  lastDrawnSeq = seq;

  // Resize overlay to match
  const metrics = getViewportMetrics();
  if (metrics) {
    resizeOverlayCanvas(metrics);
  }

  // Close bitmap to free memory
  if (bitmap?.close) bitmap.close();
}
```

#### 5.6 Update getViewportMetrics()
**Location:** Around line 2116

Replace with:
```javascript
function getViewportMetrics() {
  const activeElement = currentMode === 'replay' ? elements.replayVideo : elements.viewportCanvas;
  if (!activeElement) return null;

  const width = activeElement.clientWidth;
  const height = activeElement.clientHeight;
  if (!width || !height) return null;

  // For canvas, source dims are the actual canvas dimensions
  const sourceWidth = activeElement.width || width;
  const sourceHeight = activeElement.height || height;

  return { width, height, sourceWidth, sourceHeight };
}
```

#### 5.7 Remove Old Event Listener
**Location:** Around line 3665 in DOMContentLoaded

Remove this block:
```javascript
if (elements.viewportImage) {
  elements.viewportImage.addEventListener('load', () => renderOverlays({ clicks: [], entities: [], mousePosition: null }));
}
```

The canvas rendering now handles overlay resizing automatically.

### Phase 6: Code Formatting

Run code formatting:
```bash
# Must be run from Windows, not WSL due to I/O issues
cmd.exe /c "cd /d C:\Users\Beheerder\IdeaProjects\ChromaScape && gradlew.bat spotlessApply"
```

## How It Works

1. **Server Queuing:** Frames are added to 3-frame queue. If full, oldest is dropped (newest frames win)
2. **Sequence Numbers:** Each frame gets incrementing sequence number before broadcast
3. **Ordered Broadcast:** Single-threaded executor ensures frames are sent in exact order
4. **Client Decode:** Async decode with staleness checks - only latest frames are kept
5. **RAF Rendering:** requestAnimationFrame syncs rendering with browser refresh cycle
6. **Memory Management:** ImageBitmap.close() frees memory after rendering

## Performance Characteristics

- **Default FPS:** 30 FPS (balanced performance, can be adjusted to 10-60 FPS)
- **Queue Latency:** ~100ms max at 30 FPS (3-frame buffer), ~50ms max at 60 FPS
- **Decode Performance:** createImageBitmap decodes off-main-thread
- **Ordering Overhead:** Single-threaded broadcast adds ~1-2ms but guarantees correctness
- **Frame Drops:** Automatic when queue is full (graceful degradation)
- **Runtime FPS Change:** Near-instant (restarts capture scheduler)

## Verification Steps

### Step 1: Start Database
```bash
cd /mnt/c/Users/Beheerder/IdeaProjects/ChromaScape
docker-compose up postgres
```

Wait for: `database system is ready to accept connections`

### Step 2: Build Application
```bash
# From Windows (not WSL)
cmd.exe /c "cd /d C:\Users\Beheerder\IdeaProjects\ChromaScape && gradlew.bat build"
```

Verify: No compilation errors

### Step 3: Start ChromaScape
```bash
# Must run from Windows for screen capture
cmd.exe /c "cd /d C:\Users\Beheerder\IdeaProjects\ChromaScape && gradlew.bat bootRun"
```

Wait for: `Started ChromaScapeApplication` log message

### Step 4: Open Debugger
Navigate to: `http://localhost:8090/debugger`

### Step 5: Test FPS Control

**Test FPS Dropdown:**
1. Verify FPS dropdown shows "30 FPS" by default
2. Click dropdown and select "60 FPS (Smooth)"
3. Verify label updates to "60 FPS"
4. Watch viewport - should update noticeably faster
5. Switch to "15 FPS (Low CPU)"
6. Verify viewport updates slower
7. Return to "30 FPS (Balanced)"

**Test FPS API:**
```bash
# Get current FPS
curl http://localhost:8090/api/viewport/fps

# Set FPS to 45
curl -X POST "http://localhost:8090/api/viewport/fps?fps=45"

# Verify change
curl http://localhost:8090/api/viewport/fps  # Should return 45
```

**Check Logs:**
Look for log messages: `Restarting frame capture with new FPS: 45`

### Step 6: Verify Viewport Sync

**Open Browser DevTools:**
1. Network tab → WebSocket filter
2. Click on `/ws/viewport` connection
3. Verify messages contain: `{"seq": N, "data": "..."}`
4. Verify seq numbers always increase (no rewinds)

**Observe Viewport:**
1. Watch for smooth frame updates
2. Confirm no old frames appear after newer ones
3. Resize browser window - verify overlay stays aligned
4. Check console for no decode errors

**Test Mode Switching:**
1. Switch to Replay mode
2. Switch back to Live mode
3. Verify viewport continues updating smoothly

### Step 7: Validate Frame Ordering

**Console Test:**
```javascript
// In browser console
window.DEBUG_FRAME_SEQ = true;

// Then modify viewportWebSocket.onmessage to log:
console.log('Frame seq:', seq, 'lastDrawn:', lastDrawnSeq, 'latest:', latestSeq);
```

Expected: seq values always increase, no drops of newer frames

## Rollback Plan

If issues occur:

1. **Server-side:** Revert to previous AtomicReference approach
2. **Client-side:** Revert to `<img>` element with direct src assignment
3. **Temporary fix:** Use old codebase while debugging

## Critical Files Modified

**New files:**
- `src/main/java/com/chromascape/web/viewport/ViewportController.java` (NEW - FPS control API)

**Modified files:**
- `src/main/resources/application.yml` (FPS: 15→30)
- `src/main/java/com/chromascape/web/viewport/service/UnifiedFrameCaptureService.java` (add restartCapture)
- `src/main/java/com/chromascape/web/viewport/WebsocketViewport.java` (frame sequencing)
- `src/main/java/com/chromascape/web/viewport/ViewportWebSocketHandler.java` (single-threaded executor)
- `src/main/resources/templates/debugger.html` (add FPS dropdown, canvas element)
- `src/main/resources/static/css/debugger.css` (canvas styles)
- `src/main/resources/static/js/debugger.js` (desync fixes, FPS control)

## Success Criteria

✅ Default FPS is 30 (smooth viewport experience)
✅ FPS dropdown appears in UI and shows current FPS
✅ Changing FPS from dropdown updates capture rate immediately
✅ Viewport displays smooth frame updates without lag
✅ No old/stale frames appear after newer frames
✅ Frame sequence numbers always increase
✅ Overlay alignment remains correct during resize
✅ Mode switching works without errors
✅ No JavaScript errors in browser console
✅ WebSocket frame ordering is maintained
✅ API endpoints `/api/viewport/fps` (GET/POST) work correctly

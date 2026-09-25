# Plan: Fix Viewport Frame Streaming Issue

## Executive Summary

**Problem:** Viewport blank/frozen on both dashboard (/) and debugger (/debugger) pages. No frames being displayed despite RuneLite running and window detected.

**Approach:** Evidence-based diagnostics → targeted fix

**Strategy:**
1. Run API diagnostics to determine if issue is backend (capture paused/disabled) or frontend (WebSocket/rendering)
2. Apply specific fix based on evidence collected
3. No speculative code changes without confirming root cause

**Most Likely Root Cause:** ScreenCaptureService paused (affects both pages identically)

**Alternative Causes:** Unified capture disabled, WebSocket protocol mismatch, GDI capture failures

---

## Problem Analysis ✓

### User Report
"The viewport is not working - frames are not being sent from micro6 to chroma"

### User Clarifications (via AskUserQuestion)
1. **Issue Type:** "Browser viewport shows no frames (blank/frozen)"
2. **Error Messages:** "No error messages, just debug output"
3. **Affected Pages:** "On both pages" (Dashboard at / AND Debugger at /debugger)

### Architecture Discovery

**Important Finding:** Micro6 is NOT in the viewport frame streaming path!

The system has two separate streaming paths:

1. **Screen Capture Frames** (Windows → ChromaScape → `/debugger` viewport)
   - Source: `ScreenManager.captureWindow()` via Windows GDI BitBlt
   - Pipeline: UnifiedFrameCaptureService → ScreenCaptureService → ViewportManager → WebsocketViewport → ViewportWebSocketHandler → `/ws/viewport` → **debugger.js (NOT viewport.js)**
   - Purpose: Live viewport display in browser debugger page

2. **Tick Data** (Micro6 → ChromaScape → Database)
   - Source: Micro6 bot client via `/ws/micro6/tick-stream`
   - Data: Game state snapshots, logs, overlays (NO frames/images)
   - Purpose: Frame-accurate debugging with game state

### Frontend Discovery

**The `/debugger` page uses `debugger.js`, NOT `viewport.js`**

Key findings from `debugger.js`:
- Line 2065-2099: WebSocket `onmessage` handler **correctly parses JSON** and extracts `data` field
- Line 130: Uses `<canvas id="viewport-canvas">` for rendering (not `<img>` tag)
- Line 2228-2283: `renderViewportFrame()` renders to canvas using `drawImage()`
- Line 2197-2222: `decodeFrame()` uses `createImageBitmap()` for fast decoding
- Line 4028: `connectViewportWebSocket()` called during initialization

### Debug Log Analysis

The user's logs show continuous output:
```
[VIEWPORT DEBUG] windowSelectionService is NOT null, checking selection...
[VIEWPORT DEBUG] Found selected HWND: native@0x677043e
[VIEWPORT DEBUG] Using SELECTED window, canvas HWND: native@0xb30426
```

**Analysis:**
- Window selection service is working
- Window handle (HWND) is found correctly
- Canvas HWND is detected
- These are from `ScreenManager.getCanvasHwnd()` lines 71-114
- Being called repeatedly means `ScreenManager.captureWindow()` is being invoked
- **BUT:** No error messages means frames are likely being captured successfully on the backend

## Root Cause Analysis

### Critical Finding: Both Pages Affected

**Affected:**
- Dashboard (`/`) - uses `viewport.js` (NO JSON parsing)
- Debugger (`/debugger`) - uses `debugger.js` (HAS JSON parsing)

**Implication:** Since debugger.js already parses JSON correctly (line 2074), the problem is NOT a JSON format mismatch. Both pages being blank indicates a **backend/server-side issue** preventing frames from being sent at all.

### Architecture Differences Between Pages

| Feature | Dashboard (`viewport.js`) | Debugger (`debugger.js`) |
|---------|---------------------------|--------------------------|
| **WebSocket URL** | Hard-coded `ws://` (line 45) | Dynamic `ws://` or `wss://` (line 2058) |
| **JSON Parsing** | ❌ NO - sets `img.src = event.data` | ✅ YES - parses with fallback (line 2074) |
| **Rendering** | `<img>` tag | `<canvas>` with `drawImage()` |
| **Frame Decoding** | Browser native | `createImageBitmap()` |

### Root Cause Hypotheses (ordered by probability)

Given both pages are blank and debugger.js has correct JSON handling:

#### 1. **ScreenCaptureService is Paused ⭐⭐⭐ (HIGHEST PROBABILITY)**
   - **Why:** Affects both pages identically - no frames sent to WebSocket at all
   - **Mechanism:** When paused, `isActive()` returns false → unified capture still runs but ScreenCaptureService ignores frames → ViewportManager never receives frames → no WebSocket broadcast
   - **Evidence Check:** `/api/screencapture/status` returns `{"paused": true}`
   - **Fix:** Resume via API or UI button
   - **Confidence:** Very High - explains "no errors" + "both pages blank"

#### 2. **UnifiedFrameCaptureService Not Running ⭐⭐**
   - **Why:** No frames being captured at all
   - **Possible Causes:**
     - Config disabled: `unified-capture.enabled: false` in application.yml
     - No subscribers registered (ScreenCaptureService failed to start)
     - Service initialization failed
   - **Evidence Check:**
     - `/api/viewport/fps` returns 400 (disabled)
     - Logs missing: "Registering ScreenCaptureService with unified capture"
   - **Fix:** Enable config and restart
   - **Confidence:** Medium - would log errors if truly disabled

#### 3. **ScreenManager.captureWindow() Failing Silently ⭐**
   - **Why:** Exceptions being caught and rate-limited
   - **Mechanism:** UnifiedFrameCaptureService.captureTick() catches all exceptions (line 214-221) → logs only every 5 seconds → appears as "no errors"
   - **Evidence:** User logs show repeated HWND lookups but no capture errors
   - **Possible Causes:**
     - Invalid window dimensions (width/height <= 0)
     - BitBlt failure (GDI issue)
     - Window closed/unavailable
   - **Evidence Check:** Look for rare "Frame capture failed" logs (every 5sec)
   - **Fix:** Depends on specific GDI error
   - **Confidence:** Low - debug logs show window found successfully

#### 4. **WebSocket Connection Failing on Both Pages**
   - **Why:** Hard-coded `ws://` in viewport.js breaks on HTTPS
   - **Mechanism:**
     - Dashboard: Hard-coded `ws://` (line 45) fails if served over HTTPS
     - Debugger: Dynamic protocol (line 2058) should work, but both failing suggests network issue
   - **Evidence Check:**
     - Browser console shows WebSocket errors
     - Chrome DevTools Network tab shows WS connection failed
   - **Fix:** Change viewport.js to dynamic protocol detection
   - **Confidence:** Low - debugger.js should work even if dashboard breaks

#### 5. **Debugger Not in Live Mode** (Debugger page only)
   - **Why:** Viewport canvas only renders in Live mode (line 2066)
   - **Check:** "Live" button should be active in UI
   - **Fix:** Click "Live" button
   - **Confidence:** N/A - doesn't explain dashboard being blank too

## Evidence-Based Decision Tree

```
1. Check /api/screencapture/status
   ├─ {"paused": true} → Resume capture [FIX 1]
   └─ {"paused": false, "running": true} → Continue to 2

2. Check /api/viewport/fps
   ├─ Returns 400 → Enable unified capture config [FIX 3]
   └─ Returns number (1-60) → Continue to 3

3. Check browser console on both pages
   ├─ "Connected to viewport WebSocket" on both → Continue to 4
   ├─ WebSocket errors → Check protocol mismatch [FIX 4]
   └─ No connection message → WebSocket not attempting connection

4. Check application logs for capture errors
   ├─ "Frame capture failed" (rare, every 5sec) → Investigate GDI error
   ├─ No "Registering ScreenCaptureService" → Service failed to start
   └─ Everything looks normal → Enable frame debug logging [DEBUG MODE]

5. Enable debug logging in browser console
   window.DEBUG_FRAME_SEQ = true
   ├─ See "[FRAME] Decoding frame X" → Frames arriving, rendering issue
   ├─ See "[FRAME] Buffer rejected" → Frames arriving but being dropped
   └─ No frame logs → Frames not arriving from WebSocket
```

## Diagnostic Plan

### Phase 1: Collect Server-Side Diagnostics

**1. Check Screen Capture Status**
```bash
curl http://localhost:8090/api/screencapture/status
```
Expected:
```json
{"running": true, "paused": false}
```

If `"paused": true`, this is the issue!

**2. Check Viewport FPS Configuration**
```bash
curl http://localhost:8090/api/viewport/fps
```
Expected: `30` (or some number 1-60)

If returns 400, unified capture is disabled.

**3. Check Application Logs**
Look for:
- `"Registering ScreenCaptureService with unified capture at X FPS"` - Service started
- `"Screen capture paused via API"` - Service was paused
- `"Frame capture failed"` - Capture errors (rate-limited to every 5 seconds)

### Phase 2: Collect Client-Side Diagnostics

**1. Open Browser Console (F12)**
Check for:
- `"Connected to viewport WebSocket"` - WebSocket connected successfully
- `"Viewport WebSocket error:"` - Connection failed
- `"[VIEWPORT] Canvas element not found"` - Canvas initialization failed
- `"[FRAME] Decoding frame X"` - Frames being received and processed (if `window.DEBUG_FRAME_SEQ = true`)

**2. Check Debugger UI Mode**
- "Live" button should be active (highlighted)
- "Replay" button should be inactive
- If Replay is active, switch to Live mode

**3. Check Pause Button State**
- Look at pause button icon in viewport panel header
- If shows "play" icon (▶), capture is paused
- If shows "pause" icon (⏸), capture is running

**4. Enable Frame Debug Logging (Optional)**
In browser console:
```javascript
window.DEBUG_FRAME_SEQ = true
```
Then watch console for frame processing messages.

### Phase 3: Quick Fixes

**If Screen Capture is Paused:**
```bash
curl -X POST http://localhost:8090/api/screencapture/resume
```
Or click the pause button in the debugger UI.

**If WebSocket Not Connected:**
- Reload the `/debugger` page (F5)
- Check Chrome DevTools → Network → WS filter
- Verify `/ws/viewport` connection exists and is "Open"

**If Unified Capture Disabled:**
1. Edit `src/main/resources/application.yml`
2. Set `unified-capture.enabled: true`
3. Restart ChromaScape

**If Not in Live Mode:**
- Click the "Live" button in the debugger topbar

## Implementation Plan

**CRITICAL:** Execute diagnostics FIRST, then apply fixes based on evidence.

### STEP 1: Run Diagnostics (REQUIRED)

Execute all diagnostic commands to determine root cause:

```bash
# 1. Check if screen capture is paused
curl http://localhost:8090/api/screencapture/status

# 2. Check if unified capture is enabled
curl http://localhost:8090/api/viewport/fps

# 3. Check browser console on BOTH pages
# Open http://localhost:8090/ → F12 → Console
# Open http://localhost:8090/debugger → F12 → Console
# Look for: "Connected to viewport WebSocket" or errors
```

### STEP 2: Apply Fix Based on Evidence

#### Fix 1: Resume Screen Capture (If `{"paused": true}`)

**Evidence Required:** `/api/screencapture/status` returns `{"paused": true}`

**No code changes needed.** Choose one:

**Option A: Via UI**
1. Open http://localhost:8090/debugger
2. Look at viewport panel header (pause button)
3. If shows play icon (▶), click it to resume
4. Frames should immediately appear on both pages

**Option B: Via API**
```bash
curl -X POST http://localhost:8090/api/screencapture/resume
```

Then refresh both pages.

---

#### Fix 2: Enable Unified Capture (If FPS returns 400)

**Evidence Required:** `/api/viewport/fps` returns 400 or error

**File to modify:** `src/main/resources/application.yml`

**Line 74-77:**
```yaml
unified-capture:
  enabled: false  # ← Change to true
  fps: 30
```

**After change:**
```yaml
unified-capture:
  enabled: true
  fps: 30
```

**Restart ChromaScape:**
```bash
# WSL/Linux:
./gradlew bootRun

# Windows CMD:
cmd.exe /c "cd /d C:\Users\Beheerder\IdeaProjects\ChromaScape && gradlew.bat bootRun"
```

---

#### Fix 3: Fix viewport.js WebSocket + JSON Parsing (If dashboard fails but debugger works)

**Evidence Required:**
- Debugger shows frames, dashboard doesn't
- OR browser console shows WebSocket errors on dashboard only

**File to modify:** `src/main/resources/static/js/viewport.js`

**Current code (lines 45-72):**
```javascript
const socket = new WebSocket('ws://' + window.location.host + '/ws/viewport');

socket.onmessage = (event) => {
    try {
        if (loaderContainer.style.display !== 'none') {
            loaderContainer.style.display = 'none';
            img.style.display = 'inline-block';
        }
        img.src = event.data;
    } catch (e) {
        console.error('Failed to process viewport message', e);
    }
};
```

**New code (with dynamic protocol + JSON parsing + fallback):**
```javascript
// Use dynamic protocol (ws:// for HTTP, wss:// for HTTPS)
const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
const socket = new WebSocket(`${wsProtocol}://${window.location.host}/ws/viewport`);

socket.onmessage = (event) => {
    try {
        if (loaderContainer.style.display !== 'none') {
            loaderContainer.style.display = 'none';
            img.style.display = 'inline-block';
        }

        // Parse JSON message from backend with fallback
        let dataUrl;
        try {
            const payload = JSON.parse(event.data);
            dataUrl = payload.data;
        } catch {
            // Fallback: raw data URI (legacy server or old recordings)
            dataUrl = event.data;
        }

        // Validate and set image source
        if (dataUrl && dataUrl.startsWith('data:image/')) {
            img.src = dataUrl;
        } else {
            console.warn('Received invalid viewport message:', dataUrl);
        }
    } catch (e) {
        console.error('Failed to process viewport message', e);
    }
};
```

**Changes made:**
1. Dynamic protocol selection (fixes HTTPS WebSocket issues)
2. JSON parsing with try/catch fallback (handles both JSON and raw formats)
3. Data URI validation (prevents invalid src assignment)
4. Maintains backward compatibility with raw data URIs

---

#### Fix 4: Investigate GDI Capture Failures (If no other issues found)

**Evidence Required:**
- Status shows `{"paused": false, "running": true}`
- FPS returns valid number
- No WebSocket errors
- No frames appearing on either page
- Application logs show "Frame capture failed" (rare, every 5 seconds)

**Investigation steps:**

1. **Check for window dimension errors**
   - Look in logs for "Invalid dimensions"
   - Ensure RuneLite window has valid size (not minimized)

2. **Verify window selection**
   - Open http://localhost:8090/debugger
   - Use window selector dropdown
   - Try selecting window manually
   - Check logs for "Using SELECTED window" messages

3. **Check for BitBlt failures**
   - Windows GDI issue
   - Try restarting RuneLite
   - Try restarting ChromaScape

4. **Enable detailed frame logging**
   - In browser console on both pages:
     ```javascript
     window.DEBUG_FRAME_SEQ = true
     ```
   - Watch for frame decoding messages
   - Check if frames are arriving but failing to decode

---

#### Fix 5: Switch to Live Mode (Debugger only, unlikely if dashboard also blank)

**Evidence Required:** Debugger page blank, dashboard works fine

**No code changes needed.**
1. Click "Live" button in debugger topbar
2. Canvas should become visible

This doesn't explain dashboard being blank, so not the primary issue.

## Testing Plan

### Pre-Test: Verify Prerequisites
- [ ] ChromaScape running on Windows (not WSL)
- [ ] RuneLite running with visible game window
- [ ] Window selected (auto-detect or manual selection)
- [ ] PostgreSQL running (for debugger features)

### Test 1: API Status Checks
```bash
# Should return: {"running": true, "paused": false}
curl http://localhost:8090/api/screencapture/status

# Should return: 30 (or configured FPS)
curl http://localhost:8090/api/viewport/fps
```

### Test 2: Dashboard Viewport (/)

1. **Open Dashboard**
   - Navigate to http://localhost:8090/
   - F12 → Console

2. **Visual Verification**
   - Viewport container shows live game frames
   - Frames update continuously (~30 FPS)
   - No "Waiting for Visual Input..." loader

3. **Console Verification**
   - See: "Viewport WebSocket connected"
   - No WebSocket errors
   - No "Failed to process viewport message" errors

4. **Pause/Resume Test**
   - Click pause button (⏸ icon)
   - Viewport freezes on last frame
   - Click resume button (▶ icon)
   - Viewport unfreezes and updates

### Test 3: Debugger Viewport (/debugger)

1. **Open Debugger**
   - Navigate to http://localhost:8090/debugger
   - F12 → Console

2. **Visual Verification**
   - Viewport canvas shows live game frames
   - Frames update continuously (~30 FPS)
   - No "Waiting for visual input..." loader
   - "Live" button is active (highlighted)

3. **Console Verification**
   - See: "Connected to viewport WebSocket"
   - No WebSocket errors
   - No canvas initialization errors

4. **Mode Switch Test**
   - Click "Replay" button
     - Canvas hides (`display: none`)
     - Video element shows
     - No frames on canvas
   - Click "Live" button
     - Canvas shows (`display: block`)
     - Video hides
     - Frames resume on canvas

5. **Pause/Resume Test**
   - Click pause button in viewport panel header
   - Canvas freezes on last frame
   - Click pause button again
   - Canvas unfreezes and updates

6. **FPS Change Test**
   - Use FPS dropdown (60/30/15/10)
   - See: "Viewport FPS changed to X"
   - Verify via API:
     ```bash
     curl http://localhost:8090/api/viewport/fps
     ```

### Test 4: Frame Debug Logging (Optional)

Enable detailed frame logging in browser console:
```javascript
window.DEBUG_FRAME_SEQ = true
```

**Expected logs:**
- `[FRAME] Buffered frame X`
- `[FRAME] Decoding frame X`
- `[FRAME] Rendering frame X, lastDrawn: Y, latest: Z`

**No errors:**
- `[FRAME] Dropped frame X` (occasional, normal)
- `[VIEWPORT] Canvas element not found` (BAD)
- `[VIEWPORT] Failed to get canvas 2D context` (BAD)

### Test 5: Window Selection Test

1. **Open Debugger**
   - http://localhost:8090/debugger

2. **Open Window Dropdown**
   - Click "Auto-detect" dropdown button
   - Should see list of RuneLite/Leeuwbot windows

3. **Select Window**
   - Click a specific window from list
   - Label updates to show selected window
   - Viewport switches to selected window

4. **Verify in Logs**
   - Application logs show: `[WINDOW SELECTION] getSelectedHwnd() called - returning: native@0xXXXXXX`
   - `[VIEWPORT DEBUG] Using SELECTED window, canvas HWND: native@0xXXXXXX`

### Test 6: Protocol Mismatch Test (If fixed viewport.js)

**HTTPS Test:**
1. Access via HTTPS (if configured)
2. Open dashboard (/)
3. Browser console should show: "Viewport WebSocket connected"
4. WebSocket should use `wss://` protocol
5. No mixed-content warnings

**HTTP Test:**
1. Access via HTTP (default)
2. Open dashboard (/)
3. WebSocket should use `ws://` protocol
4. Viewport should show frames

### Test 7: Regression Tests

**JSON Format Compatibility:**
- Backend sends: `{"seq":123,"data":"data:image/jpeg;base64,..."}`
- Dashboard parses correctly and displays
- Debugger parses correctly and displays

**Raw Data URI Fallback:**
- If server ever sends raw: `data:image/jpeg;base64,...`
- Both pages should handle gracefully (fallback parsing)

**Multi-Client Test:**
- Open dashboard in one tab
- Open debugger in another tab
- Both should receive and display frames
- No frame drops or conflicts

### Success Criteria

✅ **All tests pass if:**
1. Both pages show live frames simultaneously
2. No errors in browser console
3. API status shows running + not paused
4. Pause/resume works on both pages
5. FPS changes apply correctly
6. Window selection works
7. No GDI capture errors in logs
8. Frame sequence numbers incrementing (if debug enabled)

## Critical Files Reference

### Frontend
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/resources/static/js/debugger.js` - Viewport WebSocket client, canvas rendering
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html` - Debugger page HTML

### Backend - Viewport Streaming
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/viewport/service/UnifiedFrameCaptureService.java` - Single-capture, multi-consumer frame capture
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/viewport/service/ScreenCaptureService.java` - Subscribes to unified capture, forwards to viewport
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/viewport/WebsocketViewport.java` - Encodes frames to Base64 JPEG
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/viewport/ViewportWebSocketHandler.java` - Broadcasts frames to WebSocket clients

### Backend - Screen Capture
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/utils/core/screen/window/ScreenManager.java` - Windows GDI BitBlt screen capture
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/viewport/ScreenCaptureController.java` - REST API for pause/resume/status

### Configuration
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/resources/application.yml` - Unified capture config (line 74-77)

## Constraints & Requirements

### System Requirements
1. **Windows Only** - Screen capture uses Windows GDI, won't work on WSL/Linux
2. **RuneLite Running** - Target window must exist and be accessible
3. **Window Selected** - Either auto-detected or manually selected via UI dropdown

### Performance Notes
1. Default FPS: 30 (configurable 1-60)
2. Frame encoding: JPEG (3-8ms per frame, faster than PNG)
3. Frame coalescing: Latest frame wins to prevent backlog
4. Unified capture: Single capture thread shared by viewport, recording, and streaming

### Known Limitations (from CLAUDE.md)
- Viewport requires Windows (not WSL)
- Screen capture FPS cannot be changed during recording
- Pause state persists across page reloads (server-side state)

## Root Cause Identified ✓

**Bug Found:** Frontend viewport.js does not parse JSON messages from backend!

### The Issue

**Backend** (`WebsocketViewport.java` line 104):
```java
String jsonPayload = String.format(
    "{\"seq\":%d,\"data\":\"data:image/jpeg;base64,%s\"}",
    seq, base64Image);
handler.broadcast(jsonPayload);
```

**Frontend** (`viewport.js` line 68):
```javascript
socket.onmessage = (event) => {
    img.src = event.data; // ❌ Sets src to entire JSON string!
};
```

**Problem:** The frontend tries to use the entire JSON object as an image src instead of parsing it and extracting the `data` field.

**Expected behavior:**
```javascript
socket.onmessage = (event) => {
    const message = JSON.parse(event.data);
    img.src = message.data; // ✓ Extract data field
};
```

### Why This Breaks the Viewport

1. Browser receives: `{"seq":123,"data":"data:image/jpeg;base64,/9j/4AAQ..."}`
2. Frontend sets: `<img src='{"seq":123,"data":"data:image/jpeg;base64,/9j/4AAQ..."}'>`
3. Browser cannot render this as an image (invalid data URI)
4. Viewport shows blank/frozen image with "Waiting for Visual Input..." loader

### Verification

- Window selection is working (debug logs show HWND found)
- Screen capture is working (no error logs)
- WebSocket is likely connected (otherwise frontend would log error)
- Frames are being sent (backend is broadcasting JSON)
- Frontend is receiving messages but parsing them incorrectly

## Implementation Plan

### File to Modify

- `src/main/resources/static/js/viewport.js` (lines 59-72)

### Changes Required

1. Parse JSON message in `socket.onmessage` handler
2. Extract `data` field from parsed object
3. Add error handling for malformed messages
4. Add console logging for debugging

### Code Change

**Before:**
```javascript
socket.onmessage = (event) => {
    try {
        if (loaderContainer.style.display !== 'none') {
            loaderContainer.style.display = 'none';
            img.style.display = 'inline-block';
        }
        img.src = event.data;
    } catch (e) {
        console.error('Failed to process viewport message', e);
    }
};
```

**After:**
```javascript
socket.onmessage = (event) => {
    try {
        if (loaderContainer.style.display !== 'none') {
            loaderContainer.style.display = 'none';
            img.style.display = 'inline-block';
        }

        // Parse JSON message from backend
        const message = JSON.parse(event.data);

        // Extract and validate data field
        if (message.data && message.data.startsWith('data:image/')) {
            img.src = message.data;
        } else {
            console.warn('Received invalid viewport message:', message);
        }
    } catch (e) {
        console.error('Failed to process viewport message', e);
    }
};
```

## Testing Plan

1. **Manual Testing:**
   - Start ChromaScape
   - Open browser to http://localhost:8090/debugger
   - Verify viewport container shows game frames
   - Check browser console for WebSocket connection logs
   - Verify no console errors

2. **Verification Steps:**
   - WebSocket connects: `Viewport WebSocket connected`
   - First frame received: Loader disappears, image appears
   - Frames update continuously (check sequence numbers in JSON if logging enabled)

3. **Regression Testing:**
   - Test with window not selected (should show loader)
   - Test with RuneLite not running (should show loader, no errors)
   - Test WebSocket reconnection (close/reopen browser tab)

## Alternative Approaches Considered

### Option 1: Change Backend to Send Raw Data URI
**Pros:** No frontend change needed
**Cons:**
- Breaks sequence numbering (useful for debugging frame drops)
- Less extensible (can't add metadata like timestamp, dimensions)
- Backend already has this pattern in other places

### Option 2: Fix Frontend (RECOMMENDED)
**Pros:**
- Preserves sequence numbering for debugging
- Maintains extensible JSON format
- Simple one-line fix
- Aligns with other WebSocket handlers (debugger.js likely uses similar pattern)

**Cons:** None

## Critical Files Reference

- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/resources/static/js/viewport.js` - Frontend WebSocket client (FIX HERE)
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/viewport/WebsocketViewport.java` - Backend broadcaster
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/java/com/chromascape/web/viewport/ViewportWebSocketHandler.java` - WebSocket handler
- `/mnt/c/Users/Beheerder/IdeaProjects/ChromaScape/src/main/resources/templates/debugger.html` - Page containing viewport container

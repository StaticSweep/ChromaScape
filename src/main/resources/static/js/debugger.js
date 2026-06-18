/**
 * ChromaScape Debugger Page
 * Unified layout with live viewport, logs, scrubber, bot state, and AI inspector.
 */

let currentMode = 'live';
let currentRecording = null;
let sessionHealthMap = {};
let sessionMetadataMap = {};
let currentRecordingSessionId = null;
let currentRecordingFps = 30; // Default FPS, updated when loading recording
let currentLogLevel = 'all';
let logWebSocket = null;
let overlayWebSocket = null;
let botStateWebSocket = null;
let viewportWebSocket = null;
let tickPoller = null;
let aiInspectorPoller = null;

// Viewport frame synchronization state
let lastDrawnSeq = 0;
let latestSeq = 0;
let pendingBitmap = null;
let pendingSeq = 0;
let rafScheduled = false;
let decodeInFlight = false;
let viewportCanvas = null;
let viewportCtx = null;

// Multi-frame buffer for smoother playback
const FRAME_BUFFER_SIZE = 1;
let frameBuffer = [];  // Array of {seq, dataUrl}
let draining = false;  // Prevents concurrent drain loops

// Recording state variables
let isRecording = false;
let currentRecordingId = null;
let currentSessionId = null;
let recordingStartTime = null;
let recordingDurationInterval = null;

// Debug session state (without recording)
let currentDebugSessionId = null;
let isDebugSessionActive = false;
let debugFeedEnabled = false;
const DEBUG_FEED_KEY = 'debugger_debug_feed_enabled';

const elements = {};
const MAX_LOG_ENTRIES = 3000;
const liveLogHistory = [];
const replayLogHistory = [];

/**
 * Manages tick state reconstruction from snapshots and diffs.
 * Maintains baseline snapshot and applies incremental diffs to reconstruct full state.
 */
class TickStateManager {
  constructor() {
    this.baseSnapshot = null;
    this.baseSnapshotTick = null;
    this.currentState = null;
    this.stateHistory = new Map(); // tick -> reconstructed state
    this.maxHistorySize = 500;
  }

  /**
   * Process a tick message (either TICK_SNAPSHOT or TICK_DIFF).
   * Returns: { tick, state, events, isSnapshot } or null on error.
   */
  processMessage(message) {
    if (!message || !message.type) {
      console.error('Invalid message:', message);
      return null;
    }

    if (message.type === 'TICK_SNAPSHOT') {
      return this.processSnapshot(message);
    } else if (message.type === 'TICK_DIFF') {
      return this.processDiff(message);
    } else {
      console.warn('Unknown message type:', message.type);
      return null;
    }
  }

  /**
   * Process a full snapshot message.
   */
  processSnapshot(message) {
    // Store as new baseline
    this.baseSnapshot = this.deepCopy(message.state);
    this.baseSnapshotTick = message.tick;
    this.currentState = this.deepCopy(message.state);

    // Store in history
    this.storeState(message.tick, this.currentState);

    console.log(`[TickStateManager] Received full snapshot at tick ${message.tick}`);

    return {
      tick: message.tick,
      state: this.currentState,
      events: [],
      isSnapshot: true
    };
  }

  /**
   * Process a diff message and reconstruct state.
   */
  processDiff(message) {
    if (!this.currentState) {
      console.error('Received diff without baseline snapshot, tick:', message.tick);
      return null;
    }

    if (message.baseSnapshotTick !== this.baseSnapshotTick) {
      console.warn(
        `Diff base mismatch: expected ${this.baseSnapshotTick}, got ${message.baseSnapshotTick}`
      );
      // Could request re-sync here
    }

    // Apply changes to current state
    if (message.changes) {
      this.applyDiff(this.currentState, message.changes);
    }

    // Store in history
    this.storeState(message.tick, this.currentState);

    return {
      tick: message.tick,
      state: this.currentState,
      events: message.events || [],
      isSnapshot: false
    };
  }

  /**
   * Apply diff changes to state object.
   */
  applyDiff(state, changes) {
    // Apply player changes
    if (changes.player) {
      if (!state.player) state.player = {};
      for (const [key, value] of Object.entries(changes.player)) {
        this.setNestedValue(state.player, key, value);
      }
    }

    // Apply inventory changes
    if (changes.inventory?.slots) {
      if (!state.inventory) state.inventory = [];
      changes.inventory.slots.forEach(change => {
        // Merge change into existing slot or create new
        const existing = state.inventory[change.slot] || {};
        state.inventory[change.slot] = { ...existing, ...change };
      });
    }

    // Apply NPC changes
    if (changes.npcs) {
      if (!state.npcs) state.npcs = [];

      // Add new NPCs
      if (changes.npcs.added) {
        state.npcs.push(...changes.npcs.added);
      }

      // Update existing NPCs
      if (changes.npcs.updated) {
        changes.npcs.updated.forEach(update => {
          const npc = state.npcs.find(n => n.index === update.index);
          if (npc) {
            // Apply each change field
            for (const [key, value] of Object.entries(update.changes || update)) {
              if (key !== 'index') {
                this.setNestedValue(npc, key, value);
              }
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
      if (!state.varbits) state.varbits = {};
      Object.assign(state.varbits, changes.varbits);
    }

    // Apply ground item changes
    if (changes.groundItems) {
      if (!state.groundItems) state.groundItems = [];

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

    // Apply object changes
    if (changes.objects) {
      if (!state.objects) state.objects = [];

      if (changes.objects.added) {
        state.objects.push(...changes.objects.added);
      }

      if (changes.objects.removed) {
        changes.objects.removed.forEach(removed => {
          const idx = state.objects.findIndex(obj =>
            obj.position.x === removed.position.x &&
            obj.position.y === removed.position.y &&
            obj.position.plane === removed.position.plane &&
            obj.id === removed.id
          );
          if (idx >= 0) {
            state.objects.splice(idx, 1);
          }
        });
      }
    }

    // Apply quest changes
    if (changes.quests) {
      if (!state.quests) state.quests = {};
      Object.assign(state.quests, changes.quests);
    }
  }

  /**
   * Set a nested value using dot notation (e.g., "hp.current").
   */
  setNestedValue(obj, path, value) {
    const parts = path.split('.');
    let current = obj;

    for (let i = 0; i < parts.length - 1; i++) {
      const part = parts[i];
      if (!current[part]) {
        current[part] = {};
      }
      current = current[part];
    }

    current[parts[parts.length - 1]] = value;
  }

  /**
   * Store state in history with LRU eviction.
   */
  storeState(tick, state) {
    this.stateHistory.set(tick, this.deepCopy(state));

    // Evict old states if over limit
    if (this.stateHistory.size > this.maxHistorySize) {
      const oldestTick = Array.from(this.stateHistory.keys())[0];
      this.stateHistory.delete(oldestTick);
    }
  }

  /**
   * Get state for a specific tick from history.
   */
  getStateForTick(tick) {
    return this.stateHistory.get(tick);
  }

  /**
   * Deep copy an object.
   */
  deepCopy(obj) {
    if (!obj) return obj;
    return JSON.parse(JSON.stringify(obj));
  }

  /**
   * Reset state manager (e.g., when switching sessions).
   */
  reset() {
    this.baseSnapshot = null;
    this.baseSnapshotTick = null;
    this.currentState = null;
    this.stateHistory.clear();
    console.log('[TickStateManager] Reset');
  }
}

// Global state manager instance
let liveTickStateManager = null;
let replayTickStateManager = null;

/**
 * Get or create the state manager for current mode.
 */
function getTickStateManager() {
  if (currentMode === 'live') {
    if (!liveTickStateManager) {
      liveTickStateManager = new TickStateManager();
    }
    return liveTickStateManager;
  } else {
    if (!replayTickStateManager) {
      replayTickStateManager = new TickStateManager();
    }
    return replayTickStateManager;
  }
}

/**
 * Request a full snapshot re-sync from micro6 (for drift recovery).
 * Only works in live mode with active debug session.
 */
async function requestFullSnapshot() {
  if (currentMode !== 'live' || !currentDebugSessionId) {
    console.warn('Full snapshot request only available in live mode with active session');
    return;
  }

  try {
    const response = await fetch('/api/debug/request-snapshot', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: currentDebugSessionId })
    });

    if (response.ok) {
      console.log('Full snapshot requested');
      showNotification('Full snapshot requested', 'info');
    } else {
      throw new Error(`HTTP ${response.status}`);
    }
  } catch (error) {
    console.error('Failed to request snapshot:', error);
    showNotification('Failed to request snapshot: ' + error.message, 'error');
  }
}

/**
 * Get current state manager statistics for debugging.
 */
function getStateManagerStats() {
  const manager = getTickStateManager();
  return {
    mode: currentMode,
    baseSnapshotTick: manager.baseSnapshotTick,
    currentTick: currentTick,
    historySize: manager.stateHistory.size,
    hasBaseSnapshot: manager.baseSnapshot !== null,
    hasCurrentState: manager.currentState !== null
  };
}

// Expose debugging functions to window for console access
window.debugger_requestFullSnapshot = requestFullSnapshot;
window.debugger_getStateManagerStats = getStateManagerStats;
window.debugger_resetStateManager = () => {
  const manager = getTickStateManager();
  manager.reset();
  console.log('State manager reset');
};

// Throttling for log sync to prevent request spam
let lastSyncTimeMs = 0;
let currentSyncAbortController = null;
// Dynamic sync interval: slower during active playback to reduce UI overhead
const SYNC_INTERVAL_PAUSED_MS = 200;
const SYNC_INTERVAL_PLAYING_MS = 400;

// Source filter state
const hiddenSources = new Set();
const discoveredSources = new Map(); // source -> count
const sourceLogTypes = new Map(); // source -> Set of log kinds ('tick', 'external', 'standard')
const HIDDEN_SOURCES_KEY = 'debugger_hiddenSources';

// Pattern filter state
const hiddenPatterns = new Set();
const discoveredPatterns = new Map(); // pattern -> count

// Log type toggles
let showTickLogs = true;
let showExternalLogs = true;
let showVerboseLogs = true;

// Collapsed tick state
const collapsedTicks = new Set();

// Tick snapshot storage for structured sections
const liveTickSnapshots = new Map();  // tick -> snapshot data
const replayTickSnapshots = new Map(); // tick -> snapshot data

// Replay snapshot state
let currentReplayDebugSessionId = null;
const replaySnapshotOrder = [];  // LRU tracking for snapshot cache
const replaySnapshotFetchInFlight = new Set();
const MAX_REPLAY_SNAPSHOTS = 600;
let autoFollowReplay = false; // Default: Auto-follow OFF
const AUTO_FOLLOW_KEY = 'debugger_auto_follow_replay';
let userScrolledRecently = false; // Track if user manually scrolled in last 2s
let lastProgrammaticScrollTime = 0;
let programmaticScrollUntil = 0; // Timestamp until which scroll events are considered programmatic
let lastHighlightedTick = null; // Track last highlighted tick to skip redundant DOM updates
let userScrollResetTimeout = null;

// Snapshot tick timeline (authoritative source for tick sync)
const replaySnapshotTimeline = []; // Sorted array of ticks with snapshots
let replaySessionStartTick = 0; // Starting tick for the current replay session

// Global section toggles
const globalSectionToggles = {
  actions: true,
  quest: true,
  dialogue: true,
  inventory: true,
  npcs: true,
  projectiles: true,
  player: true,
  varbits: true,
  objects: true,
  groundObjects: true,
  groundItems: true,
  // Action-log type filters
  animation: true,
  walkhere: true,
  menu: true,
  click: true,
  npcFirstOption: true,
  cancel: true,
  network: true,
  raw: true
};

// Per-tick section overrides: tick -> {section: boolean}
const perTickSectionToggles = new Map();
const MAX_PER_TICK_OVERRIDES = 200; // Rolling window for per-tick overrides

// Enable filter debug logging (toggle in browser console)
window.DEBUG_FILTER = false;

// Enable tick debug logging (toggle in browser console)
window.DEBUG_TICK = false;

function cacheElements() {
  elements.liveBtn = document.getElementById('liveModeBtn');
  elements.replayBtn = document.getElementById('replayModeBtn');
  elements.logsContainer = document.getElementById('logsContainer');
  elements.viewportCanvas = document.getElementById('viewport-canvas');
  viewportCanvas = elements.viewportCanvas;
  viewportCtx = elements.viewportCanvas?.getContext('2d');
  elements.replayVideo = document.getElementById('replayVideo');
  elements.viewportLoader = document.getElementById('viewport-loader');
  elements.overlayCanvas = document.getElementById('overlay-canvas');
  elements.sessionSelect = document.getElementById('sessionSelect');
  elements.refreshSessionsBtn = document.getElementById('refreshSessionsBtn');
  elements.editReplayMetadataBtn = document.getElementById('editReplayMetadataBtn');
  elements.replayMetadataEditor = document.getElementById('replayMetadataEditor');
  elements.replayNameInput = document.getElementById('replayNameInput');
  elements.replayTagsInput = document.getElementById('replayTagsInput');
  elements.replayTagsPreview = document.getElementById('replayTagsPreview');
  elements.saveMetadataBtn = document.getElementById('saveMetadataBtn');
  elements.cancelMetadataBtn = document.getElementById('cancelMetadataBtn');
  elements.replayMetadataPreview = document.getElementById('replayMetadataPreview');
  elements.replayDisplayName = document.getElementById('replayDisplayName');
  elements.replayTagsDisplay = document.getElementById('replayTagsDisplay');
  elements.scrubberRange = document.getElementById('scrubberRange');
  elements.scrubberTimecode = document.getElementById('scrubberTimecode');
  elements.scrubberMode = document.getElementById('scrubberMode');
  elements.scrubberPlayBtn = document.getElementById('scrubberPlayBtn');
  elements.scrubberPrevBtn = document.getElementById('scrubberPrevBtn');
  elements.scrubberNextBtn = document.getElementById('scrubberNextBtn');
  elements.scrubberSpeed = document.getElementById('scrubberSpeed');
  elements.tickDisplay = document.getElementById('tickDisplay');
  elements.tickJumpInput = document.getElementById('tickJumpInput');
  elements.tickJumpBtn = document.getElementById('tickJumpBtn');
  elements.botStateContent = document.getElementById('botStateContent');
  elements.aiInspectorContent = document.getElementById('aiInspectorContent');
  // New tick-based elements
  elements.currentTick = document.getElementById('currentTick');
  elements.currentTime = document.getElementById('currentTime');
  elements.totalTime = document.getElementById('totalTime');
  elements.currentFrame = document.getElementById('currentFrame');
  elements.totalFrame = document.getElementById('totalFrame');
  elements.scrubberStartBtn = document.getElementById('scrubberStartBtn');
  elements.scrubberEndBtn = document.getElementById('scrubberEndBtn');
  // Recording control elements
  elements.recordBtn = document.getElementById('recordBtn');
  elements.recordBtnText = document.getElementById('recordBtnText');
  elements.recordingStatus = document.getElementById('recordingStatus');
  elements.recordingDuration = document.getElementById('recordingStatusDuration');
  // Task recording elements
  elements.taskRecordingPanel = document.getElementById('taskRecordingPanel');
  elements.taskToggles = document.getElementById('taskToggles');
  elements.resetTasksBtn = document.getElementById('resetTasksBtn');
  elements.autoFollowTicks = document.getElementById('autoFollowTicks');
  // Debug session elements
  elements.startDebugSessionBtn = document.getElementById('startDebugSessionBtn');
  elements.debugSessionStatus = document.getElementById('debugSessionStatus');
  elements.resetFiltersBtn = document.getElementById('resetFiltersBtn');
  elements.debugFeedToggleBtn = document.getElementById('debugFeedToggleBtn');
}

function switchMode(mode) {
  if (currentMode === mode) return; // Don't clear logs if already in this mode
  currentMode = mode;
  elements.liveBtn.classList.toggle('active', mode === 'live');
  elements.replayBtn.classList.toggle('active', mode === 'replay');

  // Reset state manager for the new mode
  if (mode === 'live' && liveTickStateManager) {
    liveTickStateManager.reset();
  } else if (mode === 'replay' && replayTickStateManager) {
    replayTickStateManager.reset();
  }

  // Clear all frame state on mode switch
  clearFrameState();

  const isReplay = mode === 'replay';
  elements.scrubberMode.textContent = isReplay ? 'Replay' : 'Live';

  if (isReplay) {
    elements.viewportCanvas.style.display = 'none';
    elements.replayVideo.style.display = 'block';
    elements.viewportLoader.style.display = 'none';
    clearOverlayCanvas();
    loadRecordingSessions();
    setScrubberEnabled(true);
    currentReplayDebugSessionId = null;
    replaySnapshotOrder.length = 0;
    replaySnapshotFetchInFlight.clear();
    // Clear replay snapshots when entering replay mode
    replayTickSnapshots.clear();
    replaySnapshotTimeline.length = 0;
    replaySessionStartTick = 0;
    // Clear replay log history to prevent old replay logs from leaking
    replayLogHistory.length = 0;
    // Reset source filter state for clean replay slate
    discoveredSources.clear();
    sourceLogTypes.clear();
    const sourceFilters = document.getElementById('sourceFilters');
    if (sourceFilters) {
      sourceFilters.innerHTML = '';
    }
    updateSourceFilterSummary();
    // Clear the logs container to remove live logs from display
    clearLogs();
    // Show replay-specific placeholder
    if (elements.logsContainer) {
      elements.logsContainer.innerHTML =
        '<div class="text-muted fst-italic log-placeholder">Select a recording to view logs</div>';
    }
  } else {
    history.replaceState({}, '', '/debugger');
    elements.replayVideo.style.display = 'none';
    elements.viewportCanvas.style.display = 'block';
    elements.viewportLoader.style.display = 'flex';
    currentRecordingFps = 30; // Reset to default when leaving replay
    currentReplayDebugSessionId = null;
    replaySnapshotOrder.length = 0;
    replaySnapshotFetchInFlight.clear();
    clearOverlayCanvas();
    setScrubberEnabled(false);
    // Hide replay metadata UI when leaving replay mode
    if (elements.replayMetadataEditor) elements.replayMetadataEditor.style.display = 'none';
    if (elements.replayMetadataPreview) elements.replayMetadataPreview.style.display = 'none';
    if (elements.editReplayMetadataBtn) elements.editReplayMetadataBtn.style.display = 'none';
    if (elements.replayTagsPreview) elements.replayTagsPreview.innerHTML = '';
    if (elements.replayNameInput) elements.replayNameInput.value = '';
    if (elements.replayTagsInput) elements.replayTagsInput.value = '';
    // Re-discover sources from live history to restore filter chips
    discoveredSources.clear();
    sourceLogTypes.clear();
    const sourceFilters = document.getElementById('sourceFilters');
    if (sourceFilters) {
      sourceFilters.innerHTML = '';
    }
    liveLogHistory.forEach(entry => {
      if (entry.source) {
        const kindSource = entry.originalSource || entry.source;
        const logKind = entry.kind === 'tick'
          ? 'tick'
          : (isSystemSource(kindSource) ? 'standard' : 'external');
        discoverSource(entry.source, logKind);
      }
    });
    updateSourceFilterSummary();
    // Re-render live logs when returning to live mode
    renderLogHistory();
  }

  const sessionControls = document.querySelector('.session-controls');
  if (sessionControls) {
    sessionControls.style.opacity = isReplay ? '1' : '0.5';
    sessionControls.querySelectorAll('select, button').forEach(el => {
      el.disabled = !isReplay;
    });
  }
}

function clearOverlayCanvas() {
  if (!elements.overlayCanvas) return;
  const ctx = elements.overlayCanvas.getContext('2d');
  ctx.clearRect(0, 0, elements.overlayCanvas.width, elements.overlayCanvas.height);
}

function setScrubberEnabled(enabled) {
  elements.scrubberRange.disabled = !enabled;
  elements.scrubberPlayBtn.disabled = !enabled;
  elements.scrubberPrevBtn.disabled = !enabled;
  elements.scrubberNextBtn.disabled = !enabled;
  elements.scrubberSpeed.disabled = !enabled;
}

function clearLogs() {
  if (!elements.logsContainer) return;
  elements.logsContainer.innerHTML = '<div class="text-muted fst-italic log-placeholder">Waiting for logs...</div>';
}

/**
 * Clear live log history and reset the UI.
 */
function clearLiveLogHistory() {
  // Clear history array
  liveLogHistory.length = 0;

  // Clear tick snapshots
  liveTickSnapshots.clear();

  // Clear discovered sources and their counts
  discoveredSources.clear();
  sourceLogTypes.clear();

  // Clear source filter chips
  const sourceFilters = document.getElementById('sourceFilters');
  if (sourceFilters) {
    sourceFilters.innerHTML = '';
  }

  // Clear the logs container and show placeholder
  clearLogs();
}

// ==================== LOGGER ====================

function getActiveLogHistory() {
  return currentMode === 'replay' ? replayLogHistory : liveLogHistory;
}

function getLogSearchText() {
  return document.getElementById('logSearchInput')?.value.toLowerCase() || '';
}

function clampLogHistory(history) {
  if (history.length > MAX_LOG_ENTRIES) {
    const removedCount = history.length - MAX_LOG_ENTRIES;

    // Collect ticks from entries being removed
    const ticksToRemove = new Set();
    for (let i = 0; i < removedCount; i++) {
      if (history[i].tick !== null && history[i].tick !== undefined) {
        ticksToRemove.add(history[i].tick);
      }
    }

    // Clamp history
    history.splice(0, removedCount);

    // Check which ticks are still in history (don't remove those snapshots)
    const remainingTicks = new Set();
    history.forEach(entry => {
      if (entry.tick !== null && entry.tick !== undefined) {
        remainingTicks.add(entry.tick);
      }
    });

    // Prune snapshots and per-tick toggles for removed ticks
    const snapshots = currentMode === 'live' ? liveTickSnapshots : replayTickSnapshots;
    ticksToRemove.forEach(tick => {
      if (!remainingTicks.has(tick)) {
        snapshots.delete(tick);
        perTickSectionToggles.delete(tick);
      }
    });
  }
}

function ensureLogPlaceholder(message) {
  if (!elements.logsContainer) return;
  elements.logsContainer.innerHTML = `<div class="text-muted fst-italic log-placeholder">${escapeHtml(message)}</div>`;
}

function clearLogPlaceholder(outputContainer) {
  if (!outputContainer) return;
  const placeholder = outputContainer.querySelector('.log-placeholder');
  if (placeholder) {
    placeholder.remove();
    return;
  }
  if (outputContainer.children.length === 1 && outputContainer.firstElementChild?.classList.contains('text-muted')) {
    outputContainer.innerHTML = '';
  }
}

function isSystemSource(source) {
  if (!source) return false;

  // ChromaScape subsystems from WebSocketLogAppender.extractSource()
  if (/^(utils|web|scripts)(\.|$)/.test(source)) return true;
  if (source === 'System' || source === 'unknown') return true;

  // Backend/ChromaScape server logs (should always be visible)
  if (
    source === 'chromascape' ||
    source === 'server' ||
    source === 'backend' ||
    source === 'core'
  ) {
    return true;
  }

  // Backend/framework logs should not appear when Client Logs are enabled
  if (
    source.startsWith('org.') ||
    source.startsWith('com.') ||
    source.startsWith('jdk.') ||
    source.startsWith('java.')
  ) {
    return true;
  }

  return false;
}

function matchesCurrentFilters(entry, searchText) {
  const levelMatch =
    currentLogLevel === 'all' || entry.level.toLowerCase() === currentLogLevel.toLowerCase();

  // Check log type toggles
  if (entry.kind === 'tick' && !showTickLogs) {
    if (window.DEBUG_TICK) console.log('[Tick Debug] Filtered: kind=tick, showTickLogs=false');
    return false;
  }

  // Client Logs toggle: Exclusive behavior
  // ON (checked): Show ONLY client logs (non-system), hide everything else
  // OFF (unchecked): Show only system/standard logs, hide client logs
  if (entry.kind !== 'tick') {
    const sourceForTypeCheck = entry.originalSource || entry.source;
    const isClientSource = !isSystemSource(sourceForTypeCheck);

    if (showExternalLogs) {
      // Client Logs ON: Show ONLY client sources, hide everything else
      if (!isClientSource) return false;
    } else {
      // Client Logs OFF: Hide client sources, show everything else
      if (isClientSource) return false;
    }
  }

  // Check ActionLogger log type toggles via unified section toggles
  if (entry.logType) {
    const logTypeMap = {
      DIALOGUE: 'dialogue',
      INVENTORY: 'inventory',
      VARBIT: 'varbits',
      QUEST: 'quest',
      QUEST_STEP_CHANGED: 'quest',
      ANIMATION: 'animation',
      WALKHERE: 'walkhere',
      MENU: 'menu',
      CLICK: 'click',
      NPC_FIRST_OPTION: 'npcFirstOption',
      CANCEL: 'cancel',
      NETWORK: 'network',
      RAW: 'raw'
    };
    const toggleKey = logTypeMap[entry.logType];
    if (toggleKey !== undefined && !globalSectionToggles[toggleKey]) return false;
  }

  // Filter by message content (exclude patterns)
  const excludePatterns = document.getElementById('logExcludePatterns')?.value.toLowerCase() || '';
  if (excludePatterns) {
    const patterns = excludePatterns.split(',').map(p => p.trim()).filter(p => p);
    for (const pattern of patterns) {
      if (entry.message.toLowerCase().includes(pattern)) return false;
    }
  }

  // Filter verbose logs (DEBUG level) unless enabled
  if (!showVerboseLogs && entry.level.toUpperCase() === 'DEBUG') return false;

  // Check if source is hidden
  if (hiddenSources.has(entry.source) && discoveredSources.has(entry.source)) return false;

  // Check if any hidden patterns match the message
  if (hiddenPatterns.size > 0) {
    const lowerMessage = entry.message.toLowerCase();
    for (const pattern of hiddenPatterns) {
      if (lowerMessage.includes(pattern.toLowerCase())) {
        return false;
      }
    }
  }

  const searchTarget = `${entry.message} ${entry.source} ${entry.level} ${entry.subsystem || ''} ${entry.tick || ''} ${entry.logType || ''}`.toLowerCase();
  const searchMatch = searchTarget.includes(searchText);

  const passes = levelMatch && searchMatch;
  if (window.DEBUG_TICK && !passes) {
    console.log('[Tick Debug] Filter check: levelMatch=', levelMatch, 'searchMatch=', searchMatch, 'entry:', entry.source, entry.tick);
  }

  return passes;
}

function buildLogLine(entry) {
  const line = document.createElement('div');
  line.className = `log-line log-${entry.level.toLowerCase()}`;
  line.dataset.source = entry.source || '';
  line.dataset.level = entry.level.toLowerCase();

  // All logs now use consistent format: [source] [level] message
  // No timestamp, no T<tick> prefix - tick grouping provides context
  line.innerHTML = `<span class="log-source">[${escapeHtml(entry.source)}]</span> <span class="log-level">[${escapeHtml(entry.level)}]</span> <span class="log-message">${escapeHtml(entry.message)}</span>`;

  // Keep tick hover overlay for tick-kind logs
  if (entry.kind === 'tick' && entry.tick) {
    line.dataset.tick = entry.tick;
    line.title = entry.message;
    line.addEventListener('mouseenter', () => highlightTickOverlay(entry.tick));
    line.addEventListener('mouseleave', () => clearHighlightOverlay());
  }

  // Add quick hide button
  const hideBtn = document.createElement('span');
  hideBtn.className = 'quick-hide-btn';
  hideBtn.innerHTML = '<i class="bi bi-eye-slash"></i>';
  hideBtn.title = `Hide logs from "${entry.source}"`;
  hideBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    hideSource(entry.source);
  });
  line.appendChild(hideBtn);

  return line;
}

async function copyTickGroupToClipboard(group, tickLabel) {
  const content = group.querySelector('.tick-group-content');
  const body = content ? content.innerText.trim() : '';
  const text = body ? `${tickLabel}\n${body}` : tickLabel;

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.setAttribute('readonly', '');
      textarea.style.position = 'absolute';
      textarea.style.left = '-9999px';
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      textarea.remove();
    }
    showNotification('Copied tick', 'success');
  } catch (e) {
    console.warn('Failed to copy tick', e);
    showNotification('Copy failed', 'error');
  }
}

function buildTickGroup(tick, entries) {
  const group = document.createElement('div');
  group.className = 'tick-group';
  group.dataset.tick = tick;

  // DEBUG: Log group creation
  if (window.DEBUG_TICK) {
    const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
    console.log('[Tick Debug] Creating tick group:', tickLabel, 'with', entries.length, 'entries');
    entries.slice(0, 3).forEach(e => {
      const msg = e.message ? String(e.message).slice(0, 30) : '';
      console.log('  -', e.source, e.tick, msg);
    });
  }

  const isCollapsed = tick !== null && collapsedTicks.has(tick);
  if (isCollapsed) {
    group.classList.add('collapsed');
  }

  const header = document.createElement('div');
  header.className = 'tick-group-header';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;

  // Build header with tick label, count, and per-tick section chips
  const headerLeft = document.createElement('span');
  headerLeft.innerHTML = `
    <i class="bi bi-chevron-down tick-group-chevron"></i>
    <span class="tick-group-title">${escapeHtml(tickLabel)}</span>
  `;

  const headerCount = document.createElement('span');
  headerCount.className = 'tick-group-count';
  headerCount.textContent = entries.length;

  const seekBtn = document.createElement('button');
  seekBtn.type = 'button';
  seekBtn.className = 'tick-seek-btn';
  seekBtn.title = `Seek to ${tickLabel}`;
  seekBtn.innerHTML = '<i class="bi bi-clock"></i>';
  seekBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (currentMode !== 'replay' || tick === null || !elements.replayVideo) return;
    const videoTime = (Number(tick) * 0.6);
    elements.replayVideo.currentTime = Math.max(0, videoTime);
    updateScrubberFromVideo();
  });

  const copyBtn = document.createElement('button');
  copyBtn.type = 'button';
  copyBtn.className = 'tick-copy-btn';
  copyBtn.title = `Copy ${tickLabel}`;
  copyBtn.innerHTML = '<i class="bi bi-clipboard"></i>';
  copyBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    copyTickGroupToClipboard(group, tickLabel);
  });

  // Per-tick section chips
  const headerChips = document.createElement('div');
  headerChips.className = 'tick-header-chips';

  const sectionNames = [
    'actions',
    'quest',
    'dialogue',
    'inventory',
    'npcs',
    'projectiles',
    'player',
    'varbits',
    'objects',
    'groundObjects',
    'groundItems'
  ];
  sectionNames.forEach(section => {
    const chip = document.createElement('span');
    chip.className = 'tick-section-chip';
    chip.dataset.section = section;
    const labelMap = {
      actions: 'A',
      quest: 'Q',
      dialogue: 'D',
      inventory: 'I',
      npcs: 'N',
      projectiles: 'P',
      player: 'Pl',
      varbits: 'V',
      objects: 'O',
      groundObjects: 'GO',
      groundItems: 'GI'
    };
    chip.textContent = labelMap[section] || section.charAt(0).toUpperCase();
    chip.title = `Toggle ${section} for this tick`;

    // Set initial state
    const isVisible = isSectionVisible(tick, section);
    chip.classList.toggle('active', isVisible);
    chip.classList.toggle('hidden', !isVisible);

    chip.addEventListener('click', (e) => {
      e.stopPropagation();
      togglePerTickSection(tick, section);
      // Re-render this tick group
      rerenderTickGroup(tick);
    });

    headerChips.appendChild(chip);
  });

  header.appendChild(headerLeft);
  header.appendChild(headerCount);
  header.appendChild(seekBtn);
  header.appendChild(copyBtn);
  header.appendChild(headerChips);

  header.addEventListener('click', (e) => {
    // Ignore if clicking on chips
    if (e.target.closest('.tick-header-chips')) return;
    if (e.target.closest('.tick-seek-btn')) return;
    if (e.target.closest('.tick-copy-btn')) return;
    if (window.getSelection && window.getSelection().toString()) return;

    group.classList.toggle('collapsed');
    const chevron = header.querySelector('.tick-group-chevron');

    // Toggle collapsed state
    if (tick !== null) {
      if (group.classList.contains('collapsed')) {
        collapsedTicks.add(tick);
        chevron.classList.remove('bi-chevron-down');
        chevron.classList.add('bi-chevron-right');
      } else {
        collapsedTicks.delete(tick);
        chevron.classList.remove('bi-chevron-right');
        chevron.classList.add('bi-chevron-down');
      }
    }
  });

  // Set initial chevron state
  if (isCollapsed) {
    const chevron = header.querySelector('.tick-group-chevron');
    chevron.classList.remove('bi-chevron-down');
    chevron.classList.add('bi-chevron-right');
  }

  const content = document.createElement('div');
  content.className = 'tick-group-content';

  // Get snapshot for this tick
  const snapshot = getTickSnapshot(tick);

  // Build structured sections
  if (isSectionVisible(tick, 'actions') && entries.length > 0) {
    content.appendChild(buildActionsSection(tick, entries));
  }

  if (isSectionVisible(tick, 'quest') && snapshot?.quest) {
    content.appendChild(buildQuestSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'dialogue') && hasDialogueData(entries, snapshot)) {
    content.appendChild(buildDialogueSection(tick, entries, snapshot));
  }

  // Always render inventory section if snapshot exists (show empty state if no inventory data)
  if (isSectionVisible(tick, 'inventory') && snapshot) {
    content.appendChild(buildInventorySection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'npcs') && snapshot) {
    content.appendChild(buildNpcsSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'projectiles') && snapshot) {
    content.appendChild(buildProjectilesSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'player') && snapshot?.playerState) {
    content.appendChild(buildPlayerSection(tick, snapshot));
  }

  // Always render varbits section if visible (show empty state if no varbit data)
  if (isSectionVisible(tick, 'varbits')) {
    content.appendChild(buildVarbitsSection(tick, entries, snapshot));
  }

  if (isSectionVisible(tick, 'objects') && snapshot) {
    content.appendChild(buildObjectsSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'groundObjects') && snapshot) {
    content.appendChild(buildGroundObjectsSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'groundItems') && snapshot) {
    content.appendChild(buildGroundItemsSection(tick, snapshot));
  }

  group.appendChild(header);
  group.appendChild(content);
  return group;
}

/**
 * Build a tick group with filter awareness (shows both visible and total counts).
 * Always renders the tick group even if all entries are filtered out.
 */
function buildTickGroupWithFilter(tick, allEntries, visibleEntries) {
  const group = document.createElement('div');
  group.className = 'tick-group';
  group.dataset.tick = tick;

  const isCollapsed = tick !== null && collapsedTicks.has(tick);
  if (isCollapsed) {
    group.classList.add('collapsed');
  }

  const header = document.createElement('div');
  header.className = 'tick-group-header';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;

  // Build header with tick label, count, and per-tick section chips
  const headerLeft = document.createElement('span');
  headerLeft.innerHTML = `
    <i class="bi bi-chevron-down tick-group-chevron"></i>
    <span class="tick-group-title">${escapeHtml(tickLabel)}</span>
  `;

  const headerCount = document.createElement('span');
  headerCount.className = 'tick-group-count';
  // Show "visible / total" if different, otherwise just show count
  if (visibleEntries.length < allEntries.length) {
    headerCount.textContent = `${visibleEntries.length} / ${allEntries.length} logs`;
  } else {
    headerCount.textContent = allEntries.length;
  }

  const seekBtn = document.createElement('button');
  seekBtn.type = 'button';
  seekBtn.className = 'tick-seek-btn';
  seekBtn.title = `Seek to ${tickLabel}`;
  seekBtn.innerHTML = '<i class="bi bi-clock"></i>';
  seekBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    if (currentMode !== 'replay' || tick === null || !elements.replayVideo) return;
    const videoTime = (Number(tick) * 0.6);
    elements.replayVideo.currentTime = Math.max(0, videoTime);
    updateScrubberFromVideo();
  });

  const copyBtn = document.createElement('button');
  copyBtn.type = 'button';
  copyBtn.className = 'tick-copy-btn';
  copyBtn.title = `Copy ${tickLabel}`;
  copyBtn.innerHTML = '<i class="bi bi-clipboard"></i>';
  copyBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    copyTickGroupToClipboard(group, tickLabel);
  });

  // Per-tick section chips
  const headerChips = document.createElement('div');
  headerChips.className = 'tick-header-chips';

  const sectionNames = [
    'actions',
    'quest',
    'dialogue',
    'inventory',
    'npcs',
    'projectiles',
    'player',
    'varbits',
    'objects',
    'groundObjects',
    'groundItems'
  ];
  sectionNames.forEach(section => {
    const chip = document.createElement('span');
    chip.className = 'tick-section-chip';
    chip.dataset.section = section;
    const labelMap = {
      actions: 'A',
      quest: 'Q',
      dialogue: 'D',
      inventory: 'I',
      npcs: 'N',
      projectiles: 'P',
      player: 'Pl',
      varbits: 'V',
      objects: 'O',
      groundObjects: 'GO',
      groundItems: 'GI'
    };
    chip.textContent = labelMap[section] || section.charAt(0).toUpperCase();
    chip.title = `Toggle ${section} for this tick`;

    // Set initial state
    const isVisible = isSectionVisible(tick, section);
    chip.classList.toggle('active', isVisible);
    chip.classList.toggle('hidden', !isVisible);

    chip.addEventListener('click', (e) => {
      e.stopPropagation();
      togglePerTickSection(tick, section);
      // Re-render this tick group
      rerenderTickGroup(tick);
    });

    headerChips.appendChild(chip);
  });

  header.appendChild(headerLeft);
  header.appendChild(headerCount);
  header.appendChild(seekBtn);
  header.appendChild(copyBtn);
  header.appendChild(headerChips);

  header.addEventListener('click', (e) => {
    // Ignore if clicking on chips
    if (e.target.closest('.tick-header-chips')) return;
    if (e.target.closest('.tick-seek-btn')) return;
    if (e.target.closest('.tick-copy-btn')) return;
    if (window.getSelection && window.getSelection().toString()) return;

    group.classList.toggle('collapsed');
    const chevron = header.querySelector('.tick-group-chevron');

    // Toggle collapsed state
    if (tick !== null) {
      if (group.classList.contains('collapsed')) {
        collapsedTicks.add(tick);
        chevron.classList.remove('bi-chevron-down');
        chevron.classList.add('bi-chevron-right');
      } else {
        collapsedTicks.delete(tick);
        chevron.classList.remove('bi-chevron-right');
        chevron.classList.add('bi-chevron-down');
      }
    }
  });

  // Set initial chevron state
  if (isCollapsed) {
    const chevron = header.querySelector('.tick-group-chevron');
    chevron.classList.remove('bi-chevron-down');
    chevron.classList.add('bi-chevron-right');
  }

  const content = document.createElement('div');
  content.className = 'tick-group-content';

  // Get snapshot for this tick (always, even if no visible entries)
  const snapshot = getTickSnapshot(tick);

  // Show placeholder for filtered action logs ONLY if there are no visible entries
  if (visibleEntries.length === 0 && allEntries.length > 0) {
    const placeholder = document.createElement('div');
    placeholder.className = 'text-muted fst-italic px-3 py-2';
    placeholder.textContent = `All ${allEntries.length} action log(s) hidden by filters`;
    content.appendChild(placeholder);
  }

  // Build structured sections - ALWAYS render snapshot sections regardless of visible entries
  // Only the "actions" section depends on visible entries
  if (isSectionVisible(tick, 'actions') && visibleEntries.length > 0) {
    content.appendChild(buildActionsSection(tick, visibleEntries));
  }

  if (isSectionVisible(tick, 'quest') && snapshot?.quest) {
    content.appendChild(buildQuestSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'dialogue') && hasDialogueData(visibleEntries, snapshot)) {
    content.appendChild(buildDialogueSection(tick, visibleEntries, snapshot));
  }

  // Always render inventory section if snapshot exists (show empty state if no inventory data)
  if (isSectionVisible(tick, 'inventory') && snapshot) {
    content.appendChild(buildInventorySection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'npcs') && snapshot) {
    content.appendChild(buildNpcsSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'projectiles') && snapshot) {
    content.appendChild(buildProjectilesSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'player') && snapshot?.playerState) {
    content.appendChild(buildPlayerSection(tick, snapshot));
  }

  // Always render varbits section if visible (show empty state if no varbit data)
  if (isSectionVisible(tick, 'varbits')) {
    content.appendChild(buildVarbitsSection(tick, visibleEntries, snapshot));
  }

  if (isSectionVisible(tick, 'objects') && snapshot) {
    content.appendChild(buildObjectsSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'groundObjects') && snapshot) {
    content.appendChild(buildGroundObjectsSection(tick, snapshot));
  }

  if (isSectionVisible(tick, 'groundItems') && snapshot) {
    content.appendChild(buildGroundItemsSection(tick, snapshot));
  }

  group.appendChild(header);
  group.appendChild(content);
  return group;
}

/**
 * Check if a section should be visible for a tick.
 */
function isSectionVisible(tick, section) {
  // Check per-tick override first
  const tickOverrides = perTickSectionToggles.get(tick);
  if (tickOverrides && tickOverrides.hasOwnProperty(section)) {
    return tickOverrides[section];
  }
  // Fall back to global toggle
  return globalSectionToggles[section];
}

/**
 * Toggle a section for a specific tick.
 */
function togglePerTickSection(tick, section) {
  if (!perTickSectionToggles.has(tick)) {
    perTickSectionToggles.set(tick, {});
  }

  const overrides = perTickSectionToggles.get(tick);
  const currentlyVisible = isSectionVisible(tick, section);
  overrides[section] = !currentlyVisible;

  // Prune old overrides if over limit
  if (perTickSectionToggles.size > MAX_PER_TICK_OVERRIDES) {
    const keysToDelete = [...perTickSectionToggles.keys()].slice(0, perTickSectionToggles.size - MAX_PER_TICK_OVERRIDES);
    keysToDelete.forEach(k => perTickSectionToggles.delete(k));
  }

  savePerTickToggles();
}

/**
 * Get tick snapshot from storage.
 */
function getTickSnapshot(tick) {
  if (tick === null) return null;
  return currentMode === 'live' ? liveTickSnapshots.get(tick) : replayTickSnapshots.get(tick);
}

/**
 * Re-render a specific tick group.
 */
function rerenderTickGroup(tick) {
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  const tickSelector = tick === null ? '[data-tick="null"]' : `[data-tick="${tick}"]`;
  const existingGroup = outputContainer.querySelector(`.tick-group${tickSelector}`);
  if (!existingGroup) return;

  // Get entries for this tick from history
  const history = getActiveLogHistory();
  const searchText = getLogSearchText();
  const entries = history.filter(entry => {
    const entryTick = entry.tick === null || entry.tick === undefined || isNaN(parseInt(entry.tick)) ? null : entry.tick;
    return entryTick === tick; // Don't re-filter by content when re-rendering for toggle
  });

  // Build new group
  const newGroup = buildTickGroup(tick, entries);

  // Preserve collapsed state
  if (existingGroup.classList.contains('collapsed')) {
    newGroup.classList.add('collapsed');
    const chevron = newGroup.querySelector('.tick-group-chevron');
    if (chevron) {
      chevron.classList.remove('bi-chevron-down');
      chevron.classList.add('bi-chevron-right');
    }
  }

  existingGroup.replaceWith(newGroup);
}

/**
 * Check if entries contain dialogue data.
 */
function hasDialogueData(entries, snapshot) {
  return entries.some(e =>
    e.logType === 'DIALOGUE' || e.logType === 'DIALOGUE_OPTION_SELECTED'
    || (e.message && e.message.toLowerCase().includes('dialogue'))
  );
}

/**
 * Check if entries contain varbit data.
 */
function hasVarbitData(entries) {
  return entries.some(e =>
    e.logType === 'VARBIT' || e.logType === 'VARBIT_CHANGED' || e.logType === 'VARBIT_CHANGE'
    || (e.message && e.message.toLowerCase().includes('varbit'))
  );
}

/**
 * Build actions section showing log entries.
 */
function buildActionsSection(tick, entries) {
  const section = document.createElement('div');
  section.className = 'tick-section section-actions';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-lightning"></i> Actions ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  entries.forEach(entry => {
    content.appendChild(buildLogLine(entry));
  });

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build quest section showing quest step, helper state, and current varbits.
 */
function resolveQuestVarbits(quest) {
  if (!quest || typeof quest !== 'object') return null;

  const providedVarbits = quest.questVarbits;
  if (providedVarbits && typeof providedVarbits === 'object' && !Array.isArray(providedVarbits)) {
    return providedVarbits;
  }

  const hasQuestVar = quest.questVar !== undefined && quest.questVar !== null;
  const hasQuestVarId = quest.questVarId !== undefined && quest.questVarId !== null;
  if (hasQuestVar && hasQuestVarId) {
    return { [String(quest.questVarId)]: quest.questVar };
  }

  return null;
}

function buildQuestSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-quest';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-book"></i> Quest ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const quest = snapshot.quest;
  if (quest) {
    const questName = quest.name || 'Unknown Quest';
    const stepText = quest.stepText || 'No active step';
    const changed = quest.changed === true;
    const active = quest.active === true;
    const questHelperState = quest.questHelperState || null;
    const questKey = quest.questKey || null;
    const questId = quest.questId;
    const questVar = quest.questVar;
    const questVarId = quest.questVarId;
    const questVarType = quest.questVarType || null;
    const questVarbits = resolveQuestVarbits(quest);
    const varbitCount = questVarbits ? Object.keys(questVarbits).length : 0;

    const nameRow = document.createElement('div');
    nameRow.style.cssText = 'display: flex; align-items: center; flex-wrap: wrap; gap: 4px; margin-bottom: 4px;';

    if (active) {
      const activeDot = document.createElement('span');
      activeDot.className = 'quest-active-indicator';
      activeDot.title = 'Quest is active';
      nameRow.appendChild(activeDot);
    }

    const nameSpan = document.createElement('strong');
    nameSpan.textContent = questName;
    nameRow.appendChild(nameSpan);

    if (questHelperState) {
      const badge = document.createElement('span');
      badge.className = 'quest-state-badge ' + getQuestStateBadgeClass(questHelperState);
      badge.textContent = questHelperState.replace(/_/g, ' ');
      nameRow.appendChild(badge);
    }

    content.appendChild(nameRow);

    if (questKey || questId !== undefined || questVar !== undefined) {
      const metaDiv = document.createElement('div');
      metaDiv.style.cssText = 'display:flex; align-items:center; flex-wrap:wrap; gap:8px; margin-bottom:4px; font-size:0.72rem; color:#abb2bf;';

      if (questKey) {
        const keySpan = document.createElement('span');
        keySpan.textContent = `Key: ${questKey}`;
        metaDiv.appendChild(keySpan);
      }
      if (questId !== undefined && questId !== null) {
        const idSpan = document.createElement('span');
        idSpan.textContent = `ID: ${questId}`;
        metaDiv.appendChild(idSpan);
      }
      if (questVar !== undefined && questVar !== null) {
        const varSpan = document.createElement('span');
        const varLabel = questVarType === 'VARP' ? 'Varp' : 'Var';
        const varIdText = questVarId !== undefined && questVarId !== null ? ` #${questVarId}` : '';
        varSpan.textContent = `${varLabel}${varIdText}: ${questVar}`;
        metaDiv.appendChild(varSpan);
      }

      content.appendChild(metaDiv);
    }

    const stepDiv = document.createElement('div');
    stepDiv.className = changed ? 'quest-step-changed' : '';
    stepDiv.style.fontSize = '0.8rem';
    let stepHtml = `<span>${escapeHtml(stepText)}</span>`;
    if (changed) {
      stepHtml += ' <span style="color: #98c379; font-size: 0.7rem;">(Step Changed)</span>';
    }
    stepDiv.innerHTML = stepHtml;
    content.appendChild(stepDiv);

    if (varbitCount > 0) {
      const toggleRow = document.createElement('div');
      toggleRow.className = 'quest-varbits-toggle';
      toggleRow.innerHTML = `<i class="bi bi-chevron-right"></i> Quest Varbits <span style="color: #abb2bf;">(${varbitCount})</span>`;

      const varbitList = document.createElement('div');
      varbitList.className = 'quest-varbits-list';
      varbitList.style.display = 'none';

      Object.entries(questVarbits).forEach(([id, value]) => {
        const entry = document.createElement('div');
        entry.className = 'quest-varbit-entry';
        entry.innerHTML = `
          <span class="varbit-id">${escapeHtml(String(id))}</span>
          <span class="varbit-value">${escapeHtml(String(value))}</span>
        `;
        varbitList.appendChild(entry);
      });

      toggleRow.addEventListener('click', () => {
        const isHidden = varbitList.style.display === 'none';
        varbitList.style.display = isHidden ? 'block' : 'none';
        const chevron = toggleRow.querySelector('i');
        if (chevron) {
          chevron.classList.toggle('bi-chevron-right', !isHidden);
          chevron.classList.toggle('bi-chevron-down', isHidden);
        }
      });

      content.appendChild(toggleRow);
      content.appendChild(varbitList);
    }
  } else {
    content.innerHTML = '<span class="section-empty">No quest data</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Map questHelperState string to CSS badge modifier class.
 */
function getQuestStateBadgeClass(state) {
  switch ((state || '').toUpperCase()) {
    case 'IN_PROGRESS': return 'state-in-progress';
    case 'FINISHED':    return 'state-finished';
    case 'NOT_STARTED': return 'state-not-started';
    default:            return 'state-other';
  }
}

/**
 * Build dialogue section showing NPC dialogue and options.
 */
function buildDialogueSection(tick, entries, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-dialogue';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-chat-dots"></i> Dialogue ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  // Find dialogue entries
  const dialogueEntries = entries.filter(e =>
    e.logType === 'DIALOGUE' || e.logType === 'DIALOGUE_OPTION_SELECTED'
  );

  if (dialogueEntries.length > 0) {
    dialogueEntries.forEach(entry => {
      const data = entry.data || {};
      const speaker = data.speaker || data.npcName || 'Unknown';
      const text = data.text || data.message || entry.message || '';
      const options = data.options || [];
      const chosenOption = data.chosenOption || data.chosen;

      const dialogueDiv = document.createElement('div');
      dialogueDiv.style.marginBottom = '6px';

      let html = `<strong style="color: #61afef;">${escapeHtml(speaker)}:</strong> ${escapeHtml(text)}`;

      if (options.length > 0) {
        html += '<div style="margin-left: 12px; margin-top: 4px;">';
        options.forEach((opt, i) => {
          const isChosen = chosenOption === opt || chosenOption === i;
          html += `<div class="dialogue-option${isChosen ? ' chosen' : ''}">${escapeHtml(opt)}</div>`;
        });
        html += '</div>';
      }

      dialogueDiv.innerHTML = html;
      content.appendChild(dialogueDiv);
    });
  } else {
    content.innerHTML = '<span class="section-empty">No dialogue data</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build inventory section showing items.
 */
function buildInventorySection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-inventory';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-grid-3x3-gap"></i> Inventory ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const inventory = snapshot.inventory;
  if (inventory === null || inventory === undefined) {
    content.innerHTML = '<span class="section-empty">Inventory data not present</span>';
  } else if (inventory.length > 0) {
    const grid = document.createElement('div');
    grid.className = 'inventory-grid';

    // Show first 28 slots (standard inventory)
    for (let i = 0; i < 28; i++) {
      const item = inventory[i];
      const slot = document.createElement('div');
      slot.className = 'inventory-slot' + (item ? '' : ' empty');

      if (item) {
        const id = item.id || item.itemId || '?';
        const qty = item.quantity || item.amount || 1;
        slot.textContent = qty > 1 ? `${id}x${qty}` : id;
        slot.title = item.name || `Item ${id}`;
      } else {
        slot.textContent = '-';
      }

      grid.appendChild(slot);
    }

    content.appendChild(grid);
  } else {
    content.innerHTML = '<span class="section-empty">Empty inventory</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build NPCs/threats section.
 */
function buildNpcsSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-npcs';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-person-badge"></i> NPCs/Threats ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const npcs = snapshot.npcs && snapshot.npcs.length ? snapshot.npcs : (snapshot.threats || []);
  if (npcs.length > 0) {
    npcs.slice(0, 10).forEach(threat => {
      const item = document.createElement('div');
      item.className = 'threat-item';

      const name = threat.name || threat.npcName || `NPC ${threat.id || '?'}`;
      const distance = threat.distance || threat.dist;

      item.innerHTML = `
        <span class="threat-name">${escapeHtml(name)}</span>
        ${distance !== undefined ? `<span class="threat-distance">${distance} tiles</span>` : ''}
      `;
      content.appendChild(item);
    });

    if (npcs.length > 10) {
      const more = document.createElement('div');
      more.className = 'section-empty';
      more.textContent = `...and ${npcs.length - 10} more`;
      content.appendChild(more);
    }
  } else {
    content.innerHTML = '<span class="section-empty">No NPCs</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build game objects section.
 */
function buildObjectsSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-objects';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-box-seam"></i> Objects ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const objects = snapshot.gameObjects || [];
  if (objects.length > 0) {
    objects.slice(0, 12).forEach(obj => {
      const item = document.createElement('div');
      item.className = 'threat-item';
      const name = obj.name || `Object ${obj.id || '?'}`;
      const pos = obj.position || obj.tile || obj.world || {};
      const posText =
        pos.x !== undefined && pos.y !== undefined ? `(${pos.x}, ${pos.y})` : '';
      item.innerHTML = `
        <span class="threat-name">${escapeHtml(name)}</span>
        ${posText ? `<span class="threat-distance">${posText}</span>` : ''}
      `;
      content.appendChild(item);
    });
    if (objects.length > 12) {
      const more = document.createElement('div');
      more.className = 'section-empty';
      more.textContent = `...and ${objects.length - 12} more`;
      content.appendChild(more);
    }
  } else {
    content.innerHTML = '<span class="section-empty">No objects</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build ground objects section.
 */
function buildGroundObjectsSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-ground-objects';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-box"></i> Ground Objects ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const objects = snapshot.groundObjects || [];
  if (objects.length > 0) {
    objects.slice(0, 12).forEach(obj => {
      const item = document.createElement('div');
      item.className = 'threat-item';
      const name = obj.name || `Ground Object ${obj.id || '?'}`;
      const pos = obj.position || obj.tile || obj.world || {};
      const posText =
        pos.x !== undefined && pos.y !== undefined ? `(${pos.x}, ${pos.y})` : '';
      item.innerHTML = `
        <span class="threat-name">${escapeHtml(name)}</span>
        ${posText ? `<span class="threat-distance">${posText}</span>` : ''}
      `;
      content.appendChild(item);
    });
    if (objects.length > 12) {
      const more = document.createElement('div');
      more.className = 'section-empty';
      more.textContent = `...and ${objects.length - 12} more`;
      content.appendChild(more);
    }
  } else {
    content.innerHTML = '<span class="section-empty">No ground objects</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build ground items section.
 */
function buildGroundItemsSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-ground-items';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-box2"></i> Ground Items ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const items = snapshot.groundItems || [];
  if (items.length > 0) {
    items.slice(0, 12).forEach(it => {
      const item = document.createElement('div');
      item.className = 'threat-item';
      const name = it.name || `Item ${it.id || it.itemId || '?'}`;
      const qty = it.quantity || it.amount || it.qty;
      const pos = it.position || it.tile || it.world || {};
      const posText =
        pos.x !== undefined && pos.y !== undefined ? `(${pos.x}, ${pos.y})` : '';
      const qtyText = qty !== undefined ? `x${qty}` : '';
      item.innerHTML = `
        <span class="threat-name">${escapeHtml(name)} ${escapeHtml(String(qtyText))}</span>
        ${posText ? `<span class="threat-distance">${posText}</span>` : ''}
      `;
      content.appendChild(item);
    });
    if (items.length > 12) {
      const more = document.createElement('div');
      more.className = 'section-empty';
      more.textContent = `...and ${items.length - 12} more`;
      content.appendChild(more);
    }
  } else {
    content.innerHTML = '<span class="section-empty">No ground items</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build projectiles section.
 */
function buildProjectilesSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-projectiles';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-arrow-right-circle"></i> Projectiles ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const projectiles = snapshot.projectiles || [];
  if (projectiles.length > 0) {
    projectiles.forEach(proj => {
      const item = document.createElement('div');
      item.className = 'threat-item';

      const id = proj.id || proj.projectileId || '?';
      const remaining = proj.remainingCycles || proj.remaining;

      item.innerHTML = `
        <span class="threat-name">Projectile ${escapeHtml(String(id))}</span>
        ${remaining !== undefined ? `<span class="threat-distance">${remaining} cycles</span>` : ''}
      `;
      content.appendChild(item);
    });
  } else {
    content.innerHTML = '<span class="section-empty">No projectiles</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build player state section.
 */
function buildPlayerSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-player';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-person"></i> Player ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const player = snapshot.playerState;
  if (player) {
    const grid = document.createElement('div');
    grid.className = 'player-state-grid';

    const hp = player.hp || {};
    const prayer = player.prayer || {};
    const position = player.position || player.worldPosition || player.worldPoint
      || (player.tile ? player.tile : null) || {};
    const prayerCurrent = prayer.current ?? prayer.points;
    const prayerMax     = prayer.max    ?? prayer.maxPoints;

    const stats = [
      { label: 'HP', value: hp.current !== undefined ? `${hp.current}/${hp.max}` : '--' },
      { label: 'Prayer', value: prayerCurrent !== undefined ? `${prayerCurrent}/${prayerMax}` : '--' },
      { label: 'Energy', value: player.energy !== undefined ? `${player.energy}%` : '--' },
      { label: 'Animation', value: player.animation || '--' },
      { label: 'Position', value: position.x !== undefined ? `(${position.x}, ${position.y})` : '--' },
      { label: 'Running', value: player.isRunning !== undefined ? (player.isRunning ? 'Yes' : 'No') : '--' }
    ];

    stats.forEach(stat => {
      const statDiv = document.createElement('div');
      statDiv.className = 'player-stat';
      statDiv.innerHTML = `
        <span class="player-stat-label">${stat.label}</span>
        <span class="player-stat-value">${escapeHtml(String(stat.value))}</span>
      `;
      grid.appendChild(statDiv);
    });

    content.appendChild(grid);
  } else {
    // More informative empty state
    const emptyDiv = document.createElement('div');
    emptyDiv.className = 'section-empty';
    emptyDiv.innerHTML = `
      <div style="color: #fbbf24; margin-bottom: 4px;">
        <i class="bi bi-exclamation-triangle me-1"></i>Player state not available for this tick
      </div>
      <div style="font-size: 0.7rem; color: #94a3b8;">
        This may indicate the tick was captured before player login,
        or player state streaming is disabled in micro6 config.
      </div>
    `;
    content.appendChild(emptyDiv);
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

/**
 * Build varbits section.
 */
function buildVarbitsSection(tick, entries, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-varbits';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-braces"></i> Varbits ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const varbitEntries = entries.filter(e =>
    e.logType === 'VARBIT' || e.logType === 'VARBIT_CHANGED' || e.logType === 'VARBIT_CHANGE'
  );

  // Build set of event varbit IDs for cross-tagging
  const eventVarbitIds = new Set(
    varbitEntries.map(e => String((e.data || {}).varbitId || (e.data || {}).id || ''))
  );

  // Subsection 1: Current Quest Varbits (from snapshot)
  const questVarbits = resolveQuestVarbits(snapshot?.quest);
  if (questVarbits && Object.keys(questVarbits).length > 0) {
    const snapshotHeader = document.createElement('div');
    snapshotHeader.className = 'varbits-subsection-header quest-varbits-header';
    snapshotHeader.textContent = 'Current Quest Varbits';
    content.appendChild(snapshotHeader);

    Object.entries(questVarbits).forEach(([id, value]) => {
      const item = document.createElement('div');
      item.className = 'varbit-item';
      const changedTag = eventVarbitIds.has(String(id))
        ? '<span class="varbit-quest-tag">changed</span>' : '';
      item.innerHTML = `
        <span class="varbit-id">${escapeHtml(String(id))}${changedTag}</span>
        <span class="varbit-value">${escapeHtml(String(value))}</span>
      `;
      content.appendChild(item);
    });
  }

  // Subsection 2: Change Events header (only shown when both subsections present)
  if (varbitEntries.length > 0 && questVarbits && Object.keys(questVarbits).length > 0) {
    const eventsHeader = document.createElement('div');
    eventsHeader.className = 'varbits-subsection-header';
    eventsHeader.style.marginTop = '8px';
    eventsHeader.textContent = 'Change Events';
    content.appendChild(eventsHeader);
  }

  if (varbitEntries.length > 0) {
    varbitEntries.forEach(entry => {
      const data = entry.data || {};
      const item = document.createElement('div');
      item.className = 'varbit-item';

      const varbitId = data.varbitId || data.id || '?';
      const oldValue = data.oldValue;
      const newValue = data.newValue || data.value;
      const questTag = questVarbits && questVarbits.hasOwnProperty(String(varbitId))
        ? '<span class="varbit-quest-tag">quest</span>' : '';

      if (oldValue !== undefined && newValue !== undefined) {
        item.innerHTML = `
          <span class="varbit-id">${escapeHtml(String(varbitId))}${questTag}</span>
          <span class="varbit-changed">${oldValue} → ${newValue}</span>
        `;
      } else {
        item.innerHTML = `
          <span class="varbit-id">${escapeHtml(String(varbitId))}${questTag}</span>
          <span class="varbit-value">${escapeHtml(String(newValue || '--'))}</span>
        `;
      }

      content.appendChild(item);
    });
  } else if (!questVarbits || Object.keys(questVarbits).length === 0) {
    content.innerHTML = '<span class="section-empty">No varbit changes</span>';
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

function addLiveLogEntryToTickGroup(entry) {
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  const tick = entry.tick === null || entry.tick === undefined || isNaN(parseInt(entry.tick))
    ? null
    : entry.tick;

  const tickSelector = tick === null ? '[data-tick="null"]' : `[data-tick="${tick}"]`;
  let tickGroup = outputContainer.querySelector(`.tick-group${tickSelector}`);

  if (!tickGroup) {
    // Create new tick group
    tickGroup = buildTickGroup(tick, [entry]);

    clearLogPlaceholder(outputContainer);

    // Insert in correct tick order
    const allGroups = Array.from(outputContainer.querySelectorAll('.tick-group'));
    let insertBefore = null;

    // Find where to insert (numeric ticks first, null last)
    for (const group of allGroups) {
      const groupTick = group.dataset.tick;
      const groupTickNum = groupTick === 'null' ? null : parseInt(groupTick);

      if (tick === null) {
        // Current tick is null, always goes after numeric
        insertBefore = group;
        break;
      } else if (groupTickNum === null) {
        // Current tick is numeric, goes before null
        insertBefore = group;
        break;
      } else if (parseInt(tick) < groupTickNum) {
        insertBefore = group;
        break;
      }
    }

    if (insertBefore) {
      outputContainer.insertBefore(tickGroup, insertBefore);
    } else {
      outputContainer.appendChild(tickGroup);
    }

    // Prune if over limit (count actual log lines, not groups)
    pruneLogEntries();
  } else {
    // Add to existing group - check if we need to re-render with snapshot data
    const snapshot = getTickSnapshot(tick);
    if (snapshot && (snapshot.playerState || snapshot.inventory || snapshot.quest ||
        snapshot.threats || snapshot.projectiles || snapshot.cooldowns ||
        snapshot.npcs || snapshot.gameObjects || snapshot.groundObjects || snapshot.groundItems)) {
      // Re-render entire tick group with all sections
      const history = getActiveLogHistory();
      const searchText = getLogSearchText();
      const allEntries = history.filter(histEntry => {
        const entryTick = histEntry.tick === null || histEntry.tick === undefined || isNaN(parseInt(histEntry.tick)) ? null : histEntry.tick;
        return entryTick === tick; // Don't re-filter by content when re-rendering for snapshot
      });

      const newGroup = buildTickGroup(tick, allEntries);
      // Preserve collapsed state and replace
      if (tickGroup.classList.contains('collapsed')) {
        newGroup.classList.add('collapsed');
        const chevron = newGroup.querySelector('.tick-group-chevron');
        if (chevron) {
          chevron.classList.remove('bi-chevron-down');
          chevron.classList.add('bi-chevron-right');
        }
      }
      tickGroup.replaceWith(newGroup);
    } else {
      // No snapshot data - just append log line
      const content = tickGroup.querySelector('.tick-group-content');
      content.appendChild(buildLogLine(entry));

      // Update count
      const countSpan = tickGroup.querySelector('.tick-group-count');
      const currentCount = parseInt(countSpan.textContent) || 0;
      countSpan.textContent = currentCount + 1;

      // Respect collapsed state
      if (tickGroup.classList.contains('collapsed')) {
        const line = content.lastElementChild;
        if (line) line.style.display = 'none';
      }
    }
  }
}

function ensureTickGroupForSnapshot(tick) {
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  const tickSelector = tick === null ? '[data-tick="null"]' : `[data-tick="${tick}"]`;
  let tickGroup = outputContainer.querySelector(`.tick-group${tickSelector}`);
  if (tickGroup) return;

  tickGroup = buildTickGroup(tick, []);
  clearLogPlaceholder(outputContainer);

  const allGroups = Array.from(outputContainer.querySelectorAll('.tick-group'));
  let insertBefore = null;

  for (const group of allGroups) {
    const groupTick = group.dataset.tick;
    const groupTickNum = groupTick === 'null' ? null : parseInt(groupTick, 10);

    if (tick === null) {
      insertBefore = group;
      break;
    } else if (groupTickNum === null) {
      insertBefore = group;
      break;
    } else if (parseInt(tick, 10) < groupTickNum) {
      insertBefore = group;
      break;
    }
  }

  if (insertBefore) {
    outputContainer.insertBefore(tickGroup, insertBefore);
  } else {
    outputContainer.appendChild(tickGroup);
  }

  if (currentMode === 'live') {
    pruneTickGroups();
  }
}

function pruneLogEntries() {
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  // Count total log lines across all groups
  let totalLines = 0;
  const groups = outputContainer.querySelectorAll('.tick-group');

  for (const group of groups) {
    const content = group.querySelector('.tick-group-content');
    totalLines += content.children.length;
  }

  // Remove oldest entries if over limit
  while (totalLines > MAX_LOG_ENTRIES && groups.length > 0) {
    const oldestGroup = groups[0];
    const content = oldestGroup.querySelector('.tick-group-content');

    if (content.children.length > 0) {
      content.removeChild(content.firstChild);
      totalLines--;

      // Update count
      const countSpan = oldestGroup.querySelector('.tick-group-count');
      const currentCount = parseInt(countSpan.textContent) || 0;
      countSpan.textContent = Math.max(0, currentCount - 1);

      // Remove empty group
      if (content.children.length === 0) {
        oldestGroup.remove();
        groups.shift();
      }
    } else {
      // Empty group, remove it
      oldestGroup.remove();
      groups.shift();
    }
  }

  // Also prune history array
  clampLogHistory(getActiveLogHistory());

  // Cap visible tick groups in DOM
  pruneTickGroups();
}

// ==================== SOURCE FILTER MANAGEMENT ====================

/**
 * Extract source name from external log messages in format [SourceName] message.
 * @param {string} message - The log message
 * @param {string} defaultSource - Default source if extraction fails
 * @returns {string} Extracted source name or default
 */
function extractSourceFromMessage(message, defaultSource = 'external') {
  if (!message || typeof message !== 'string') return defaultSource;

  // Match pattern: [SourceName] at the start of the message
  // Handles: [SourceName] message text
  const match = message.match(/^\[([^\]]+)\]/);
  if (match) {
    return match[1];
  }

  return defaultSource;
}

/**
 * Extract and discover common patterns from log messages.
 * Looks for common keywords like: heartbeat, polling, error, warning, etc.
 * @param {string} message - The log message
 * @returns {string[]} Array of discovered patterns
 */
function discoverPatterns(message) {
  if (!message || typeof message !== 'string') return [];

  const patterns = [];
  const commonPatterns = [
    'heartbeat', 'polling', 'error', 'warning', 'stats', 'account',
    'menu', 'click', 'inventory', 'dialogue', 'varbit', 'animation'
  ];

  const lowerMessage = message.toLowerCase();
  commonPatterns.forEach(pattern => {
    if (lowerMessage.includes(pattern)) {
      // Update count
      const count = discoveredPatterns.get(pattern) || 0;
      discoveredPatterns.set(pattern, count + 1);
      patterns.push(pattern);
      updatePatternChip(pattern);
    }
  });

  return patterns;
}

/**
 * Discover a source from a log entry and update the filter chips.
 * @param {string} source - The source identifier
 * @param {string} logKind - The log kind ('tick', 'external', 'standard')
 */
function discoverSource(source, logKind = 'standard') {
  if (!source) return;

  const count = discoveredSources.get(source) || 0;
  discoveredSources.set(source, count + 1);

  // Track which log types this source has
  if (!sourceLogTypes.has(source)) {
    sourceLogTypes.set(source, new Set());
  }
  sourceLogTypes.get(source).add(logKind);

  // Update or create chip
  updateSourceChip(source);
}

/**
 * Check if a source should be visible based on current log type filters.
 * Exclusive behavior:
 * - When Client Logs is ON: shows ONLY client sources (non-system).
 * - When Client Logs is OFF: shows all system sources, hides client sources.
 */
function isSourceVisibleByFilters(source) {
  const logTypes = sourceLogTypes.get(source);
  if (!logTypes || logTypes.size === 0) return false; // No type info = hide

  const isClientSrc = !isSystemSource(source);
  const hasTickLogs = logTypes.has('tick');

  if (showExternalLogs) {
    // Client Logs ON: Show client sources and tick sources if tick logs are enabled
    if (hasTickLogs && showTickLogs) return true;
    return isClientSrc;
  } else {
    // Client Logs OFF: Hide client sources, show everything else (respect tick toggle for tick-only sources)
    if (isClientSrc) return false;
    if (!showTickLogs && hasTickLogs && logTypes.size === 1) return false;
    return true;
  }
}

/**
 * Update or create a source filter item in the dropdown.
 */
function updateSourceChip(source) {
  const container = document.getElementById('sourceFilters');
  if (!container) return;

  let item = container.querySelector(`[data-source="${source}"]`);
  const count = discoveredSources.get(source) || 0;
  const isHidden = hiddenSources.has(source);
  const isVisibleByFilters = isSourceVisibleByFilters(source);

  // If source shouldn't be shown due to log type filters, hide the item
  if (!isVisibleByFilters) {
    if (item) {
      item.style.display = 'none';
    }
    updateSourceFilterSummary();
    return;
  }

  if (!item) {
    item = document.createElement('div');
    item.className = 'source-filter-item';
    item.dataset.source = source;
    container.appendChild(item);
  }

  item.style.display = ''; // Make sure it's visible
  item.className = 'source-filter-item' + (isHidden ? ' hidden' : '');
  item.innerHTML = `
    <label class="source-name">
      <input type="checkbox" ${isHidden ? '' : 'checked'}>
      <span title="${escapeHtml(source)}">${escapeHtml(source)}</span>
    </label>
    <span class="source-count">${count}</span>
  `;

  // Add event listener for the checkbox
  const checkbox = item.querySelector('input[type="checkbox"]');
  checkbox.addEventListener('change', (e) => {
    e.stopPropagation();
    if (e.target.checked) {
      showSource(source);
    } else {
      hideSource(source);
    }
  });

  updateSourceFilterSummary();
}

/**
 * Update the source filter summary text.
 */
function updateSourceFilterSummary() {
  const summary = document.getElementById('sourceFiltersSummary');
  if (!summary) return;

  const totalSources = discoveredSources.size;
  let hiddenCount = 0;
  for (const source of hiddenSources) {
    if (discoveredSources.has(source)) {
      hiddenCount += 1;
    }
  }

  if (totalSources === 0) {
    summary.innerHTML = 'Sources';
  } else if (hiddenCount === 0) {
    summary.innerHTML = `Sources (${totalSources})`;
  } else {
    summary.innerHTML = `Sources (${totalSources}) <span class="filter-badge">${hiddenCount} hidden</span>`;
  }
}

/**
 * Reset all log filters to default state.
 */
function resetAllFilters() {
  // Clear exclude patterns
  const excludeInput = document.getElementById('logExcludePatterns');
  if (excludeInput) {
    excludeInput.value = '';
  }

  // Unhide all sources
  hiddenSources.clear();
  saveHiddenSources();
  updateAllSourceChips();

  // Unhide all patterns
  hiddenPatterns.clear();
  updateAllPatternChips();

  // Enable all log type toggles
  showTickLogs = true;
  showExternalLogs = true;
  showVerboseLogs = true;

  const tickLogsCheckbox = document.getElementById('showTickLogs');
  const externalLogsCheckbox = document.getElementById('showExternalLogs');
  const verboseLogsCheckbox = document.getElementById('showVerboseLogs');

  if (tickLogsCheckbox) tickLogsCheckbox.checked = true;
  if (externalLogsCheckbox) externalLogsCheckbox.checked = true;
  if (verboseLogsCheckbox) verboseLogsCheckbox.checked = true;

  // Reset all action-type section toggles
  ['animation', 'walkhere', 'menu', 'click', 'npcFirstOption', 'cancel', 'network', 'raw',
    'dialogue', 'inventory', 'varbits', 'quest'].forEach(key => {
    globalSectionToggles[key] = true;
  });
  saveSectionToggles();
  // Re-sync button active states
  document.querySelectorAll('#sectionToggles .section-toggle').forEach(btn => {
    const section = btn.dataset.section;
    if (section) btn.classList.toggle('active', globalSectionToggles[section]);
  });

  // Re-render logs
  renderLogHistory();

  showNotification('All filters reset', 'success');
}

/**
 * Initialize the source filter dropdown.
 */
function initSourceFilterDropdown() {
  const toggle = document.getElementById('sourceFiltersToggle');
  const dropdown = document.getElementById('sourceFiltersContainer');
  const menu = document.getElementById('sourceFiltersMenu');
  const showAllBtn = document.getElementById('showAllSources');
  const hideAllBtn = document.getElementById('hideAllSources');

  if (!toggle || !dropdown) return;

  // Toggle dropdown on click
  toggle.addEventListener('click', (e) => {
    e.stopPropagation();
    dropdown.classList.toggle('open');
  });

  // Close dropdown when clicking outside
  document.addEventListener('click', (e) => {
    if (!dropdown.contains(e.target)) {
      dropdown.classList.remove('open');
    }
  });

  // Prevent closing when clicking inside the menu
  if (menu) {
    menu.addEventListener('click', (e) => {
      e.stopPropagation();
    });
  }

  // Show all sources
  if (showAllBtn) {
    showAllBtn.addEventListener('click', () => {
      hiddenSources.clear();
      saveHiddenSources();
      updateAllSourceChips();
      filterLogs();
    });
  }

  // Hide all sources
  if (hideAllBtn) {
    hideAllBtn.addEventListener('click', () => {
      discoveredSources.forEach((count, source) => {
        hiddenSources.add(source);
      });
      saveHiddenSources();
      updateAllSourceChips();
      filterLogs();
    });
  }
}

/**
 * Toggle source visibility.
 */
function toggleSource(source) {
  if (hiddenSources.has(source)) {
    showSource(source);
  } else {
    hideSource(source);
  }
}

/**
 * Hide a source.
 */
function hideSource(source) {
  hiddenSources.add(source);
  saveHiddenSources();
  updateSourceChip(source);
  updateSourceFilterSummary();
  filterLogs();
}

/**
 * Show a source.
 */
function showSource(source) {
  hiddenSources.delete(source);
  saveHiddenSources();
  updateSourceChip(source);
  updateSourceFilterSummary();
  filterLogs();
}

/**
 * Update all source filter items (e.g., when log type filters change).
 */
function updateAllSourceChips() {
  discoveredSources.forEach((count, source) => {
    updateSourceChip(source);
  });
  updateSourceFilterSummary();
}

/**
 * Save hidden sources to localStorage.
 */
function saveHiddenSources() {
  try {
    const relevant = [...hiddenSources].filter(source => discoveredSources.has(source));
    localStorage.setItem(HIDDEN_SOURCES_KEY, JSON.stringify(relevant));
  } catch (e) {
    console.warn('Failed to save hidden sources:', e);
  }
}

/**
 * Load hidden sources from localStorage.
 */
function loadHiddenSources() {
  try {
    const saved = localStorage.getItem(HIDDEN_SOURCES_KEY);
    if (saved) {
      const sources = JSON.parse(saved);
      sources.forEach(s => hiddenSources.add(s));
    }
  } catch (e) {
    console.warn('Failed to load hidden sources:', e);
  }
}

// ==================== PATTERN FILTER MANAGEMENT ====================

/**
 * Update or create a pattern filter item in the dropdown.
 */
function updatePatternChip(pattern) {
  const container = document.getElementById('patternFilters');
  if (!container) return;

  let item = container.querySelector(`[data-pattern="${pattern}"]`);
  const count = discoveredPatterns.get(pattern) || 0;
  const isHidden = hiddenPatterns.has(pattern);

  if (!item) {
    item = document.createElement('div');
    item.className = 'source-filter-item'; // Reuse existing styles
    item.dataset.pattern = pattern;
    container.appendChild(item);
  }

  item.className = 'source-filter-item' + (isHidden ? ' hidden' : '');
  item.innerHTML = `
    <label class="source-name">
      <input type="checkbox" ${isHidden ? '' : 'checked'}>
      <span title="${escapeHtml(pattern)}">#${escapeHtml(pattern)}</span>
    </label>
    <span class="source-count">${count}</span>
  `;

  const checkbox = item.querySelector('input[type="checkbox"]');
  checkbox.addEventListener('change', (e) => {
    e.stopPropagation();
    if (e.target.checked) {
      showPattern(pattern);
    } else {
      hidePattern(pattern);
    }
  });
}

/**
 * Hide a pattern.
 */
function hidePattern(pattern) {
  hiddenPatterns.add(pattern);
  updatePatternChip(pattern);
  filterLogs();
}

/**
 * Show a pattern.
 */
function showPattern(pattern) {
  hiddenPatterns.delete(pattern);
  updatePatternChip(pattern);
  filterLogs();
}

/**
 * Update all pattern filter items (e.g., when pattern filters change).
 */
function updateAllPatternChips() {
  discoveredPatterns.forEach((count, pattern) => {
    updatePatternChip(pattern);
  });
}

/**
 * Initialize the pattern filter dropdown.
 */
function initPatternFilterDropdown() {
  const toggle = document.getElementById('patternFiltersToggle');
  const dropdown = document.getElementById('patternFiltersContainer');
  const menu = document.getElementById('patternFiltersMenu');
  const showAllBtn = document.getElementById('showAllPatterns');
  const hideAllBtn = document.getElementById('hideAllPatterns');

  if (!toggle || !dropdown) return;

  // Toggle dropdown on click
  toggle.addEventListener('click', (e) => {
    e.stopPropagation();
    dropdown.classList.toggle('open');
  });

  // Close dropdown when clicking outside
  document.addEventListener('click', (e) => {
    if (!dropdown.contains(e.target)) {
      dropdown.classList.remove('open');
    }
  });

  // Prevent closing when clicking inside the menu
  if (menu) {
    menu.addEventListener('click', (e) => {
      e.stopPropagation();
    });
  }

  // Show all patterns
  if (showAllBtn) {
    showAllBtn.addEventListener('click', () => {
      hiddenPatterns.clear();
      updateAllPatternChips();
      filterLogs();
    });
  }

  // Hide all patterns
  if (hideAllBtn) {
    hideAllBtn.addEventListener('click', () => {
      discoveredPatterns.forEach((count, pattern) => {
        hiddenPatterns.add(pattern);
      });
      updateAllPatternChips();
      filterLogs();
    });
  }
}

/**
 * Initialize source filters on page load.
 */
function initSourceFilters() {
  loadHiddenSources();

  // Initialize the dropdown UI
  initSourceFilterDropdown();

  // Rebuild items from history
  const history = getActiveLogHistory();
  history.forEach(entry => {
    if (entry.source) {
      const count = discoveredSources.get(entry.source) || 0;
      discoveredSources.set(entry.source, count + 1);
    }
  });

  // Create items for all discovered sources
  discoveredSources.forEach((count, source) => {
    updateSourceChip(source);
  });

  // Update summary
  updateSourceFilterSummary();
}

/**
 * Initialize log type toggles.
 */
function initLogTypeToggles() {
  const tickLogsToggle = document.getElementById('showTickLogs');
  const externalLogsToggle = document.getElementById('showExternalLogs');
  const verboseLogsToggle = document.getElementById('showVerboseLogs');

  // Load saved preferences
  try {
    const savedPrefs = localStorage.getItem('debugger_logTypePrefs');
    if (savedPrefs) {
      const prefs = JSON.parse(savedPrefs);
      showTickLogs = prefs.showTickLogs ?? true;
      showExternalLogs = prefs.showExternalLogs ?? true;
      showVerboseLogs = prefs.showVerboseLogs ?? true;
    }
  } catch (e) {
    console.warn('Failed to load log type preferences:', e);
  }

  // DEBUG: Log initial state
  if (window.DEBUG_FILTER) {
    console.log('[Filter Init] Loaded state:', {
      showTickLogs,
      showExternalLogs,
      showVerboseLogs,
      checkboxStates: {
        tick: tickLogsToggle?.checked,
        external: externalLogsToggle?.checked,
        verbose: verboseLogsToggle?.checked
      }
    });
  }

  // Set initial checkbox states
  if (tickLogsToggle) tickLogsToggle.checked = showTickLogs;
  if (externalLogsToggle) externalLogsToggle.checked = showExternalLogs;
  if (verboseLogsToggle) verboseLogsToggle.checked = showVerboseLogs;

  // Add event listeners
  if (tickLogsToggle) {
    tickLogsToggle.addEventListener('change', (e) => {
      showTickLogs = e.target.checked;
      saveLogTypePrefs();
      updateAllSourceChips(); // Update chip visibility
      filterLogs();
    });
  }

  if (externalLogsToggle) {
    externalLogsToggle.addEventListener('change', (e) => {
      showExternalLogs = e.target.checked; // Explicit sync
      if (window.DEBUG_FILTER) {
        console.log('[Filter Toggle] showExternalLogs changed to:', showExternalLogs);
      }
      saveLogTypePrefs();
      updateAllSourceChips(); // Update chip visibility
      filterLogs();
    });
  }

  if (verboseLogsToggle) {
    verboseLogsToggle.addEventListener('change', (e) => {
      showVerboseLogs = e.target.checked;
      saveLogTypePrefs();
      filterLogs();
    });
  }
}

/**
 * Initialize replay "Sync Scroll to Time" toggle.
 * When enabled, automatically scrolls the log view to match the current replay time.
 * When disabled, allows free scrolling without jumping to current tick.
 */
function initAutoFollowToggle() {
  if (!elements.autoFollowTicks) return;

  // Default to OFF if no saved preference (free scroll by default)
  const saved = localStorage.getItem(AUTO_FOLLOW_KEY);
  autoFollowReplay = saved === 'true'; // Only true if explicitly set to 'true'
  elements.autoFollowTicks.checked = autoFollowReplay;

  elements.autoFollowTicks.addEventListener('change', (e) => {
    autoFollowReplay = e.target.checked;
    localStorage.setItem(AUTO_FOLLOW_KEY, String(autoFollowReplay));

    // Reset scroll lock when sync is toggled on
    if (autoFollowReplay) {
      userScrolledRecently = false;
      if (userScrollResetTimeout) {
        clearTimeout(userScrollResetTimeout);
        userScrollResetTimeout = null;
      }

      // Immediately jump to current tick
      let jumpTick = null;
      if (elements.replayVideo && currentRecording) {
        jumpTick = calculateTickFromVideoTime(elements.replayVideo.currentTime * 1000);
      }
      if (jumpTick == null) {
        // Fallback: highest rendered tick group
        const groups = elements.logsContainer
          ? [...elements.logsContainer.querySelectorAll('.tick-group[data-tick]')]
          : [];
        if (groups.length) {
          jumpTick = Math.max(...groups.map(g => parseInt(g.dataset.tick)));
        }
      }
      if (jumpTick != null) {
        lastHighlightedTick = null; // Force re-highlight
        highlightCurrentTickGroup(jumpTick);
        scrollToCurrentTick(jumpTick);
      }
    }
  });

  // Detect manual scrolling to suppress auto-follow for 2 seconds
  if (elements.logsContainer) {
    elements.logsContainer.addEventListener('scroll', () => {
      const now = Date.now();
      // Only consider it a manual scroll if outside both the legacy threshold and the explicit programmatic window
      const isProgrammatic = now <= programmaticScrollUntil || now - lastProgrammaticScrollTime <= 200;
      if (window.DEBUG_TICK) {
        console.log('[AutoFollow] scroll event isProgrammatic:', isProgrammatic,
          'until:', programmaticScrollUntil - now, 'ms remaining',
          'sinceLastProg:', now - lastProgrammaticScrollTime, 'ms');
      }
      if (!isProgrammatic) {
        userScrolledRecently = true;

        // Reset the flag after 2 seconds of no manual scrolling
        if (userScrollResetTimeout) {
          clearTimeout(userScrollResetTimeout);
        }
        userScrollResetTimeout = setTimeout(() => {
          userScrolledRecently = false;
          userScrollResetTimeout = null;
        }, 2000);
      }
    });
  }
}

/**
 * Save log type preferences to localStorage.
 */
function saveLogTypePrefs() {
  try {
    localStorage.setItem('debugger_logTypePrefs', JSON.stringify({
      showTickLogs,
      showExternalLogs,
      showVerboseLogs
    }));
  } catch (e) {
    console.warn('Failed to save log type preferences:', e);
  }
}

// ==================== SECTION TOGGLES ====================

/**
 * Initialize section toggles for tick cards.
 */
function initSectionToggles() {
  // Load saved preferences
  loadSectionToggles();
  loadPerTickToggles();

  // Get toggle buttons
  const toggleContainer = document.getElementById('sectionToggles');
  if (!toggleContainer) return;

  const toggleButtons = toggleContainer.querySelectorAll('.section-toggle');

  toggleButtons.forEach(btn => {
    const section = btn.dataset.section;
    if (!section) return;

    // Set initial state from loaded preferences
    btn.classList.toggle('active', globalSectionToggles[section]);

    // Add click handler
    btn.addEventListener('click', () => {
      globalSectionToggles[section] = !globalSectionToggles[section];
      btn.classList.toggle('active', globalSectionToggles[section]);
      saveSectionToggles();
      filterLogs(); // Re-render with new toggle states
    });
  });

  const excludePatternsInput = document.getElementById('logExcludePatterns');
  if (excludePatternsInput) {
    excludePatternsInput.addEventListener('input', debounce(filterLogs, 300));
  }
}

/**
 * Save global section toggle preferences to localStorage.
 */
function saveSectionToggles() {
  try {
    localStorage.setItem('debugger_globalSectionToggles', JSON.stringify(globalSectionToggles));
  } catch (e) {
    console.warn('Failed to save section toggles:', e);
  }
}

/**
 * Load global section toggle preferences from localStorage.
 */
function loadSectionToggles() {
  try {
    const saved = localStorage.getItem('debugger_globalSectionToggles');
    if (saved) {
      const parsed = JSON.parse(saved);
      Object.keys(parsed).forEach(key => {
        if (globalSectionToggles.hasOwnProperty(key)) {
          globalSectionToggles[key] = parsed[key];
        }
      });

      // One-time migration from legacy filter_show* keys
      const legacyMap = {
        filter_showDialogue: 'dialogue',
        filter_showInventory: 'inventory',
        filter_showVarbit: 'varbits',
        filter_showAnimation: 'animation',
        filter_showWalkhere: 'walkhere',
        filter_showMenu: 'menu',
        filter_showClick: 'click',
        filter_showNpcFirstOption: 'npcFirstOption',
        filter_showCancel: 'cancel',
        filter_showNetwork: 'network',
        filter_showRaw: 'raw'
      };
      const newKeys = Object.values(legacyMap);
      const hasAnyNewKey = newKeys.some(k => k in parsed);
      if (!hasAnyNewKey) {
        for (const [legacyKey, newKey] of Object.entries(legacyMap)) {
          const legacyVal = localStorage.getItem(legacyKey);
          if (legacyVal !== null) {
            globalSectionToggles[newKey] = legacyVal !== 'false';
          }
        }
      }
    }
  } catch (e) {
    console.warn('Failed to load section toggles:', e);
  }
}

/**
 * Save per-tick section overrides to localStorage.
 */
function savePerTickToggles() {
  try {
    // Convert Map to array of entries for JSON serialization
    const entries = [...perTickSectionToggles.entries()].slice(-MAX_PER_TICK_OVERRIDES);
    localStorage.setItem('debugger_perTickSectionToggles', JSON.stringify(entries));
  } catch (e) {
    console.warn('Failed to save per-tick toggles:', e);
  }
}

/**
 * Load per-tick section overrides from localStorage.
 */
function loadPerTickToggles() {
  try {
    const saved = localStorage.getItem('debugger_perTickSectionToggles');
    if (saved) {
      const entries = JSON.parse(saved);
      entries.forEach(([tick, overrides]) => {
        perTickSectionToggles.set(tick, overrides);
      });
    }
  } catch (e) {
    console.warn('Failed to load per-tick toggles:', e);
  }
}


/**
 * Debounce helper for input handlers.
 */
function debounce(func, wait) {
  let timeout;
  return function executedFunction(...args) {
    const later = () => {
      clearTimeout(timeout);
      func(...args);
    };
    clearTimeout(timeout);
    timeout = setTimeout(later, wait);
  };
}

function renderLogHistory(emptyMessage) {
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  const history = getActiveLogHistory();
  const searchText = getLogSearchText();

  outputContainer.innerHTML = '';

  // Handle completely empty logs
  if (history.length === 0) {
    const defaultMsg = currentMode === 'replay'
      ? 'Select a recording to view logs'
      : 'Waiting for logs...';
    ensureLogPlaceholder(emptyMessage || defaultMsg);
    return;
  }

  // Build tick groups from ALL entries (not filtered)
  const tickGroups = new Map();
  history.forEach(entry => {
    const tick = entry.tick === null || entry.tick === undefined || isNaN(parseInt(entry.tick))
      ? null
      : entry.tick;
    if (!tickGroups.has(tick)) {
      tickGroups.set(tick, []);
    }
    tickGroups.get(tick).push(entry);
  });

  // Log tick groups ONCE when rendering (not continuously)
  if (window.DEBUG_TICK && !window._loggedTickGroups) {
    window._loggedTickGroups = true;
    const tickGroupKeys = Array.from(tickGroups.keys()).map(k => k === null ? 'null' : k);
    console.log('[Tick Debug] Total tick groups on render:', tickGroups.size, 'ticks:', tickGroupKeys.join(', '));
  }

  // Sort ticks (numeric first asc, null last)
  const sortedTicks = [...tickGroups.keys()].sort((a, b) => {
    if (a === null) return 1;  // null goes last
    if (b === null) return -1;
    return parseInt(a) - parseInt(b);
  });

  // Render tick groups with filtering applied per-group
  let hasVisibleEntries = false;
  sortedTicks.forEach(tick => {
    const allEntries = tickGroups.get(tick);
    const visibleEntries = allEntries.filter(entry => matchesCurrentFilters(entry, searchText));

    if (visibleEntries.length > 0) {
      hasVisibleEntries = true;
    }

    // Build tick group with both visible and total counts
    outputContainer.appendChild(buildTickGroupWithFilter(tick, allEntries, visibleEntries));
  });

  // Show message if NO entries are visible across ALL ticks
  if (!hasVisibleEntries) {
    const message = document.createElement('div');
    message.className = 'text-warning fst-italic p-3';
    message.textContent = 'No logs match the current filters';
    outputContainer.insertBefore(message, outputContainer.firstChild);
  }
}

// Batched rendering state for live log entries
let pendingLiveEntries = [];
let liveRenderScheduled = false;
const MAX_VISIBLE_TICK_GROUPS = 50;

function addLiveLogEntry(entry) {
  // Guard - only add to live history in live mode to prevent replay logs from polluting live history
  if (currentMode !== 'live') return;
  liveLogHistory.push(entry);

  // DEBUG: Log tick before filters
  if (window.DEBUG_TICK) {
    console.log('[Tick Debug] addLiveLogEntry - tick:', entry.tick, 'source:', entry.source, 'kind:', entry.kind);
  }

  // Extract actual source from client log messages (lightweight, keep synchronous)
  let actualSource = entry.source;
  const sourceIsSystem = isSystemSource(entry.source);
  if (!sourceIsSystem && entry.kind !== 'tick') {
    entry.originalSource = entry.source;
    actualSource = extractSourceFromMessage(entry.message, entry.source);
  }

  if (actualSource) {
    // Use originalSource (pre-extraction) for logKind classification
    const kindSource = entry.originalSource || entry.source;
    const logKind = entry.kind === 'tick' ? 'tick' : (isSystemSource(kindSource) ? 'standard' : 'external');
    discoverSource(actualSource, logKind);
    entry.source = actualSource;  // Display name (e.g., "TeleportOptimizer")
  }

  discoverPatterns(entry.message);

  if (window.DEBUG_FILTER) {
    const msg = typeof entry.message === 'string' ? entry.message.slice(0, 50) : String(entry.message || '').slice(0, 50);
    const isExternalSource = !isSystemSource(entry.originalSource || entry.source);
    const passesFilter = matchesCurrentFilters(entry, getLogSearchText());

    console.log('[Filter Debug]',
      'source:', entry.source,
      'kind:', entry.kind,
      'isExternal:', isExternalSource,
      'showExternalLogs:', showExternalLogs,
      'message:', msg,
      'passesFilter:', passesFilter,
      'currentMode:', currentMode
    );
  }

  if (currentMode !== 'live') return;

  if (!matchesCurrentFilters(entry, getLogSearchText())) {
    if (window.DEBUG_TICK) {
      console.log('[Tick Debug] Entry FILTERED OUT - tick:', entry.tick, 'source:', entry.source);
    }
    return;
  }

  if (window.DEBUG_TICK) {
    console.log('[Tick Debug] Entry PASSED filters - tick:', entry.tick, 'adding to tick group');
  }

  // Batch rendering: queue entry and schedule a single rAF flush
  pendingLiveEntries.push(entry);
  if (!liveRenderScheduled) {
    liveRenderScheduled = true;
    requestAnimationFrame(flushPendingLiveEntries);
  }
}

function flushPendingLiveEntries() {
  liveRenderScheduled = false;
  const batch = pendingLiveEntries;
  pendingLiveEntries = [];
  batch.forEach(entry => addLiveLogEntryToTickGroup(entry));
  pruneTickGroups();
}

function pruneTickGroups() {
  const container = elements.logsContainer;
  if (!container) return;
  const groups = container.querySelectorAll('.tick-group');
  const excess = groups.length - MAX_VISIBLE_TICK_GROUPS;
  for (let i = 0; i < excess; i++) {
    container.removeChild(groups[i]);
  }
}

/**
 * Safely parse structuredData which may or may not be JSON.
 * @param {any} structuredData - Raw structured data
 * @returns {any} Parsed object or original value
 */
function safeParseStructuredData(structuredData) {
  if (!structuredData) return null;
  if (typeof structuredData === 'object') return structuredData;
  if (typeof structuredData !== 'string') return null;

  const trimmed = structuredData.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return structuredData; // Keep as string if not JSON-like
  }

  try {
    return JSON.parse(trimmed);
  } catch (e) {
    return structuredData; // Return original string if parse fails
  }
}

function normalizeStandardLog(log) {
  return {
    kind: 'standard',
    level: String(log.level || 'INFO').toUpperCase(),
    message: log.message ? String(log.message) : '',
    source: log.source ? String(log.source) : 'unknown',
    timestamp: log.timestamp || null,
    // Fix: Use nullish coalescing to preserve tick 0
    tick: log.tick ?? currentTick ?? null
  };
}

function normalizeTickLog(log) {
  // FIX: Use hasOwnProperty check to preserve tick=0
  const tickValue = log.hasOwnProperty('tick') && log.tick !== null && log.tick !== undefined
    ? log.tick
    : (currentTick ?? null);
  // Normalize '--' or non-numeric to null for proper sorting
  const normalizedTick = (tickValue === null || tickValue === '--' || isNaN(parseInt(tickValue))) ? null : tickValue;

  // Support both logType/data (batch) and eventType/details (live DTO) field names
  const resolvedLogType = log.logType || log.eventType || null;
  const resolvedData    = log.data    || log.details    || null;

  let resolvedMessage = log.message ? String(log.message) : '';
  if (!resolvedMessage && resolvedLogType && resolvedData) {
    resolvedMessage = formatEventFromTypeAndData(resolvedLogType, resolvedData);
  }
  if (!resolvedMessage && resolvedLogType) {
    resolvedMessage = resolvedLogType;
  }

  return {
    kind: 'tick',
    tick: normalizedTick,
    level: String(log.severity || log.level || 'INFO').toUpperCase(),
    subsystem: log.subsystem ? String(log.subsystem) : 'tick',
    source: log.subsystem ? String(log.subsystem) : 'tick',
    message: resolvedMessage,
    timestamp: log.timestamp || null,
    // PRESERVE rich fields for UI sections
    logType: resolvedLogType,  // Preserve for Dialogue/Varbit/etc. sections
    data: resolvedData          // Preserve for structured display
  };
}

/**
 * Format a tick event message from a type string and data object.
 * Used by normalizeTickLog and formatTickEvent to share formatting logic.
 * @param {string} type - The event type
 * @param {Object} data - The event data
 * @returns {string} Formatted message
 */
function formatEventFromTypeAndData(type, data) {
  if (!type) return '';
  const d = data || {};
  switch (type) {
    case 'DAMAGE_TAKEN':
      return `Took ${d.damage ?? d.amount ?? '?'} damage from ${d.source ?? '?'}${d.type || d.hitType ? ` (${d.type || d.hitType})` : ''}`;
    case 'DAMAGE_DEALT':
      return `Dealt ${d.damage ?? d.amount ?? '?'} damage to ${d.target ?? '?'}${d.type || d.hitType ? ` (${d.type || d.hitType})` : ''}`;
    case 'ITEM_DROPPED':
      return `Dropped ${d.quantity != null ? d.quantity + 'x ' : ''}${d.itemName ?? d.item ?? '?'}`;
    case 'ITEM_PICKED_UP':
      return `Picked up ${d.quantity != null ? d.quantity + 'x ' : ''}${d.itemName ?? d.item ?? '?'}`;
    case 'PRAYER_ACTIVATED':
      return `Activated prayer: ${d.prayerName ?? d.name ?? d.prayerId ?? '?'}`;
    case 'PRAYER_DEACTIVATED':
      return `Deactivated prayer: ${d.prayerName ?? d.name ?? d.prayerId ?? '?'}`;
    case 'PLAYER_MOVED': {
      const x = d.x ?? d.position?.x ?? '?';
      const y = d.y ?? d.position?.y ?? '?';
      const plane = d.plane ?? d.position?.plane;
      return `Moved to (${x}, ${y}${plane != null ? `, ${plane}` : ''})`;
    }
    case 'VARBIT_CHANGED':
    case 'VARBIT_CHANGE':
    case 'VARBIT':
      return `Varbit ${d.varbitId ?? d.id ?? '?'}: ${d.oldValue ?? '?'} → ${d.newValue ?? d.value ?? '?'}`;
    case 'QUEST':
    case 'QUEST_STEP_CHANGED': {
      const name = d.name ?? d.questName ?? '?';
      const step = d.stepText ?? d.step ?? d.newStep ?? d.message ?? '?';
      const state = d.questHelperState ?? null;
      const oldStep = d.oldStep ?? null;
      if (oldStep != null && d.newStep != null) {
        return `Quest "${name}" step changed: ${oldStep} → ${d.newStep}`;
      }
      return `Quest "${name}": ${step}${state ? ` [${state.replace(/_/g, ' ')}]` : ''}`;
    }
    case 'DIALOGUE':
      return `Dialogue: ${d.speaker ?? d.npcName ?? d.npc ?? ''}: "${d.text ?? ''}"`;
    case 'DIALOGUE_OPTION_SELECTED':
      return `Selected dialogue option: "${d.option ?? d.text ?? ''}"`;
    default:
      if (Object.keys(d).length > 0) return `${type}: ${JSON.stringify(d)}`;
      return type;
  }
}

/**
 * Normalize ActionLogger log format.
 * ActionLogger format: {tick, ts, type, data}
 * @param {Object} log - Raw action log
 * @returns {Object} Normalized log entry
 */
function normalizeActionLog(log) {
  return {
    kind: 'action',
    level: String(log.level || 'INFO').toUpperCase(),
    message: log.message ? String(log.message) : '',
    source: log.source ? String(log.source) : 'external',
    timestamp: log.timestamp || null,
    logType: log.logType || null, // DIALOGUE, INVENTORY, VARBIT, ANIMATION
    // Fix: Use nullish coalescing to preserve tick 0
    tick: log.tick ?? currentTick ?? null,
    data: log.data || null // Structured data
  };
}

function connectLogWebSocket() {
  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${location.host}/ws/logs`;

  let reconnectTimeout;

  function connect() {
    logWebSocket = new WebSocket(wsUrl);

    logWebSocket.onopen = () => {
      console.log('[Debugger] Log WebSocket connected to', wsUrl);
      clearTimeout(reconnectTimeout);
    };

    logWebSocket.onmessage = (event) => {
      if (currentMode !== 'live') return;
      handleLiveLogPayload(event.data);
    };

    logWebSocket.onclose = () => {
      logWebSocket = null;
      reconnectTimeout = setTimeout(connect, 3000);
    };

    logWebSocket.onerror = (error) => {
      console.error('Log WebSocket error:', error);
    };
  }

  connect();
}

function handleLiveLogPayload(data) {
  try {
    const log = JSON.parse(data);

    // DEBUG: Log tick values BEFORE normalization
    if (window.DEBUG_TICK) {
      const msg = log.message ? String(log.message).slice(0, 50) : '';
      console.log('[Tick Debug] Raw log - tick:', log.tick, 'source:', log.source, 'logType:', log.logType, 'message:', msg);
    }

    // 2-way routing: ActionLogger first (has logType/type), then standard
    // Note: Tick-stream logs arrive via /ws/debugger/tick-stream (renderTickLogs),
    // not here. Spring Boot logs always have tick from getCycles() but are standard logs.
    if (log.logType || (log.type && log.hasOwnProperty('tick') && log.data)) {
      // ActionLogger format: has logType or (type + tick + data)
      const actionLog = {
        level: log.level || 'INFO',
        message: log.message || `${log.logType || log.type}`,
        source: log.source || 'external',
        timestamp: log.timestamp,
        logType: log.logType || log.type,
        tick: log.tick,
        data: log.data
      };
      const normalized = normalizeActionLog(actionLog);

      // DEBUG: Log AFTER normalization
      if (window.DEBUG_TICK) {
        console.log('[Tick Debug] After normalizeActionLog - tick:', normalized.tick, 'kind:', normalized.kind);
      }

      addLiveLogEntry(normalized);
    } else {
      // Standard log (tick preserved as metadata for grouping)
      const normalized = normalizeStandardLog(log);

      // DEBUG: Log AFTER normalization
      if (window.DEBUG_TICK) {
        console.log('[Tick Debug] After normalizeStandardLog - tick:', normalized.tick, 'kind:', normalized.kind);
      }

      addLiveLogEntry(normalized);
    }
  } catch (e) {
    console.warn('[Debugger] Failed to parse log payload:', e, data);
    addLiveLogEntry({
      kind: 'standard',
      level: 'INFO',
      message: String(data || ''),
      source: 'unknown',
      timestamp: null
    });
  }
}

function escapeHtml(text) {
  const div = document.createElement('div');
  div.textContent = text;
  return div.innerHTML;
}

// ==================== VIEWPORT ====================

function connectViewportWebSocket() {
  // Ensure canvas and context are ready
  if (!elements.viewportCanvas) {
    console.error('[VIEWPORT] Canvas element not found');
    return;
  }

  if (!viewportCtx) {
    viewportCtx = elements.viewportCanvas.getContext('2d');
    if (!viewportCtx) {
      console.error('[VIEWPORT] Failed to get canvas 2D context');
      return;
    }
  }

  if (viewportWebSocket && viewportWebSocket.readyState === WebSocket.OPEN) {
    return;
  }

  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  viewportWebSocket = new WebSocket(`${wsProtocol}://${location.host}/ws/viewport`);

  viewportWebSocket.onopen = () => {
    console.log('Connected to viewport WebSocket');
  };

  viewportWebSocket.onmessage = async (event) => {
    if (currentMode !== 'live') return;
    elements.viewportLoader.style.display = 'none';
    elements.viewportCanvas.style.display = 'block';

    let seq, dataUrl;

    // Parse frame payload
    try {
      const payload = JSON.parse(event.data);
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
    if (seq <= lastDrawnSeq) {
      if (window.DEBUG_FRAME_SEQ) {
        console.log(`[FRAME] Dropped frame ${seq} - already drawn ${lastDrawnSeq}`);
      }
      return;
    }

    // Buffer the frame
    bufferFrame(seq, dataUrl);

    // Trigger drain (idempotent, safe to call always)
    drainFrameBuffer();
  };

  viewportWebSocket.onerror = (error) => {
    console.error('Viewport WebSocket error:', error);
  };

  viewportWebSocket.onclose = () => {
    viewportWebSocket = null;
  };
}

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

/**
 * Converts a data URL to a Blob without using fetch().
 * Handles both base64 and non-base64 (URL-encoded) data URLs.
 */
function dataUrlToBlob(dataUrl) {
  const commaIndex = dataUrl.indexOf(',');
  if (commaIndex === -1) throw new Error('Invalid data URL format');

  const header = dataUrl.substring(0, commaIndex);
  const mimeMatch = header.match(/data:([^;,]+)/);
  const mimeType = mimeMatch ? mimeMatch[1] : 'application/octet-stream';
  const rawData = dataUrl.substring(commaIndex + 1);

  // Check if base64 encoded
  if (header.includes(';base64')) {
    const binaryString = atob(rawData);
    const bytes = new Uint8Array(binaryString.length);
    for (let i = 0; i < binaryString.length; i++) {
      bytes[i] = binaryString.charCodeAt(i);
    }
    return new Blob([bytes], { type: mimeType });
  } else {
    // URL-encoded data URL
    const decoded = decodeURIComponent(rawData);
    const encoder = new TextEncoder();
    return new Blob([encoder.encode(decoded)], { type: mimeType });
  }
}

/**
 * Decode a data URL into an ImageBitmap or Image element.
 * Uses direct conversion (faster), with fallbacks for edge cases.
 */
async function decodeFrame(dataUrl) {
  if (window.createImageBitmap) {
    try {
      const blob = dataUrlToBlob(dataUrl);
      return await createImageBitmap(blob);
    } catch (e) {
      // Fallback to fetch for edge cases (e.g., malformed data URLs)
      console.warn('dataUrlToBlob failed, trying fetch fallback:', e);
      try {
        const response = await fetch(dataUrl);
        const blob = await response.blob();
        return await createImageBitmap(blob);
      } catch (e2) {
        console.warn('fetch fallback also failed:', e2);
      }
    }
  }

  // Final fallback: Image element
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

  if (!viewportCanvas) {
    console.error('[FRAME] viewportCanvas is null - canvas not initialized');
    return;
  }

  if (!viewportCtx) {
    console.error('[FRAME] viewportCtx is null - canvas context not initialized');
    return;
  }

  if (!pendingBitmap) {
    return; // No frame to render
  }

  const bitmap = pendingBitmap;
  const seq = pendingSeq;
  pendingBitmap = null;
  pendingSeq = 0;

  // Only drop if we've already drawn a newer frame
  if (seq <= lastDrawnSeq) {
    if (window.DEBUG_FRAME_SEQ) {
      console.log(`[FRAME] Skipping frame ${seq} - already drawn ${lastDrawnSeq}`);
    }
    if (bitmap?.close) bitmap.close();
    return;
  }

  if (window.DEBUG_FRAME_SEQ) {
    console.log(`[FRAME] Rendering frame ${seq}, lastDrawn: ${lastDrawnSeq}, latest: ${latestSeq}`);
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

/**
 * Add a frame to the buffer.
 * - Drops frames already drawn (seq <= lastDrawnSeq)
 * - De-duplicates by seq (removes existing same seq)
 * - Keeps only newest FRAME_BUFFER_SIZE frames
 */
function bufferFrame(seq, dataUrl) {
  // Drop frames already rendered
  if (seq <= lastDrawnSeq) {
    if (window.DEBUG_FRAME_SEQ) {
      console.log(`[FRAME] Buffer rejected ${seq} - already drawn ${lastDrawnSeq}`);
    }
    return;
  }

  // De-dup: remove any existing entry with same seq
  frameBuffer = frameBuffer.filter(f => f.seq !== seq);

  // Add new frame
  frameBuffer.push({ seq, dataUrl });

  // Sort by seq ascending (oldest first)
  frameBuffer.sort((a, b) => a.seq - b.seq);

  // Trim oldest frames if over capacity (keep newest N)
  while (frameBuffer.length > FRAME_BUFFER_SIZE) {
    const dropped = frameBuffer.shift();
    if (window.DEBUG_FRAME_SEQ) {
      console.log(`[FRAME] Buffer full, dropped oldest frame ${dropped.seq}`);
    }
  }

  if (window.DEBUG_FRAME_SEQ) {
    console.log(`[FRAME] Buffered frame ${seq}, buffer: [${frameBuffer.map(f => f.seq).join(', ')}]`);
  }
}

/**
 * Get the next frame to decode from buffer.
 * Removes stale frames (seq <= lastDrawnSeq) and returns newest valid frame.
 * For live streaming, we always want the latest frame to minimize lag.
 */
function getNextBufferedFrame() {
  // Remove stale frames before checking
  frameBuffer = frameBuffer.filter(f => f.seq > lastDrawnSeq);

  if (frameBuffer.length === 0) return null;

  // Return newest frame, drop the rest (latest-only strategy for live viewport)
  const newest = frameBuffer.pop();
  frameBuffer = [];  // Clear older frames
  return newest;
}

/**
 * Clear all buffer and frame state (for mode switches, reconnects).
 */
function clearFrameState() {
  // Clear buffer
  frameBuffer = [];
  draining = false;

  // Close and clear pending bitmap
  if (pendingBitmap?.close) pendingBitmap.close();
  pendingBitmap = null;
  pendingSeq = 0;

  // Reset decode state
  decodeInFlight = false;
  rafScheduled = false;

  // Reset sequence tracking to prevent stale drops
  lastDrawnSeq = 0;
  latestSeq = 0;

  if (window.DEBUG_FRAME_SEQ) {
    console.log('[FRAME] Frame state cleared');
  }
}

/**
 * Drain the frame buffer one frame at a time.
 * Idempotent - safe to call multiple times, only one drain loop runs.
 */
async function drainFrameBuffer() {
  // Prevent concurrent drain loops
  if (draining || decodeInFlight) return;

  const frame = getNextBufferedFrame();
  if (!frame) return;

  draining = true;
  decodeInFlight = true;

  try {
    if (window.DEBUG_FRAME_SEQ) {
      console.log(`[FRAME] Decoding frame ${frame.seq}`);
    }

    const bitmap = await decodeFrame(frame.dataUrl);

    // Check if still valid after decode (could have been superseded)
    // Also check latestSeq - a newer frame may have arrived while decoding
    if (frame.seq <= lastDrawnSeq || frame.seq < latestSeq) {
      if (window.DEBUG_FRAME_SEQ) {
        console.log(`[FRAME] Discarded decoded frame ${frame.seq} - superseded by ${latestSeq}`);
      }
      if (bitmap?.close) bitmap.close();
    } else {
      // Replace any existing pending bitmap
      if (pendingBitmap?.close) pendingBitmap.close();
      pendingBitmap = bitmap;
      pendingSeq = frame.seq;

      // Schedule RAF render
      if (!rafScheduled) {
        rafScheduled = true;
        requestAnimationFrame(renderViewportFrame);
      }
    }
  } catch (err) {
    console.warn('Frame decode failed:', err);
  } finally {
    decodeInFlight = false;
    draining = false;

    // Continue draining if more frames in buffer
    if (frameBuffer.length > 0) {
      // Use setTimeout(0) to yield to UI thread, then continue
      setTimeout(drainFrameBuffer, 0);
    }
  }
}

// ==================== OVERLAY ====================

function connectOverlayWebSocket() {
  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${location.host}/ws/overlay`;

  overlayWebSocket = new WebSocket(wsUrl);

  overlayWebSocket.onmessage = (event) => {
    if (currentMode !== 'live') return;
    try {
      const payload = JSON.parse(event.data);
      renderOverlays(payload);
    } catch (e) {
      console.error('Failed to parse overlay payload', e);
    }
  };

  overlayWebSocket.onerror = (error) => {
    console.error('Overlay WebSocket error:', error);
  };
}

function renderOverlays(overlayData) {
  if (!elements.overlayCanvas) return;
  const ctx = elements.overlayCanvas.getContext('2d');
  const metrics = getViewportMetrics();
  if (!metrics) return;

  resizeOverlayCanvas(metrics);
  ctx.clearRect(0, 0, elements.overlayCanvas.width, elements.overlayCanvas.height);

  const scaleX = metrics.width / metrics.sourceWidth;
  const scaleY = metrics.height / metrics.sourceHeight;

  if (overlayData.clicks) {
    overlayData.clicks.forEach(click => {
      ctx.fillStyle = click.success ? '#00ff88' : '#ff4d4d';
      ctx.beginPath();
      ctx.arc(click.x * scaleX, click.y * scaleY, 5, 0, Math.PI * 2);
      ctx.fill();
    });
  }

  if (overlayData.entities) {
    overlayData.entities.forEach(entity => {
      ctx.strokeStyle = '#ffe66d';
      ctx.strokeRect(
        entity.x * scaleX,
        entity.y * scaleY,
        entity.width * scaleX,
        entity.height * scaleY
      );
    });
  }

  if (overlayData.mousePosition) {
    const x = overlayData.mousePosition.x * scaleX;
    const y = overlayData.mousePosition.y * scaleY;
    ctx.strokeStyle = '#00f5ff';
    ctx.beginPath();
    ctx.moveTo(x - 10, y);
    ctx.lineTo(x + 10, y);
    ctx.moveTo(x, y - 10);
    ctx.lineTo(x, y + 10);
    ctx.stroke();
  }
}

function getViewportMetrics() {
  const activeElement = currentMode === 'replay' ? elements.replayVideo : elements.viewportCanvas;
  if (!activeElement) return null;

  // Get display dimensions (CSS size after object-fit: contain)
  const displayWidth = activeElement.clientWidth;
  const displayHeight = activeElement.clientHeight;
  if (!displayWidth || !displayHeight) return null;

  // Get source dimensions (actual canvas/video resolution)
  const sourceWidth = activeElement.width || activeElement.videoWidth || displayWidth;
  const sourceHeight = activeElement.height || activeElement.videoHeight || displayHeight;

  // Canvas uses object-fit: contain, so compute actual displayed size accounting for letterboxing
  const sourceAspect = sourceWidth / sourceHeight;
  const displayAspect = displayWidth / displayHeight;

  let width, height;
  if (sourceAspect > displayAspect) {
    // Letterboxed (black bars top/bottom)
    width = displayWidth;
    height = displayWidth / sourceAspect;
  } else {
    // Pillarboxed (black bars left/right)
    width = displayHeight * sourceAspect;
    height = displayHeight;
  }

  return { width, height, sourceWidth, sourceHeight };
}

function resizeOverlayCanvas(metrics) {
  elements.overlayCanvas.width = metrics.width;
  elements.overlayCanvas.height = metrics.height;
}

// ==================== BOT STATE ====================

function connectBotStateWebSocket() {
  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${location.host}/ws/bot-state`;

  botStateWebSocket = new WebSocket(wsUrl);

  botStateWebSocket.onmessage = (event) => {
    try {
      const payload = JSON.parse(event.data);
      renderBotState(payload);
    } catch (e) {
      console.error('Failed to parse bot state', e);
    }
  };
}

function renderBotState(payload) {
  const container = elements.botStateContent;
  if (!container) return;

  const state = payload.botState || {};
  const decision = payload.decision || {};

  const items = [
    { label: 'State', value: state.label || state.state || 'Unknown' },
    { label: 'Script', value: payload.script || 'Idle' },
    { label: 'Tick', value: payload.tick ?? '--' },
    { label: 'Decision', value: decision.decisionType || 'None' },
    { label: 'Reason', value: decision.decisionReason || '--' },
    { label: 'Target', value: formatOptional(payload.targetEntity) }
  ];

  container.innerHTML = items.map(item => (
    `<div class="state-item">
      <div class="state-label">${item.label}</div>
      <div>${item.value}</div>
    </div>`
  )).join('');
}

function formatOptional(value) {
  if (!value) return '--';
  if (typeof value === 'string') return value;
  if (value.name) return value.name;
  return JSON.stringify(value);
}

// ==================== AI INSPECTOR ====================

async function fetchAiInspector() {
  try {
    const response = await fetch('/api/debug/ai-inspector/latest');
    if (!response.ok) return;
    const data = await response.json();
    renderAiInspector(data);
  } catch (error) {
    console.error('Failed to fetch AI inspector data', error);
  }
}

function renderAiInspector(data) {
  const container = elements.aiInspectorContent;
  if (!container) return;

  const evidence = (data.evidence || []).map(item => `<div>• ${item}</div>`).join('') || 'No evidence.';

  container.innerHTML = `
    <div class="ai-pill">${data.faultType || 'UNKNOWN'} · ${data.confidence || 'LOW'}</div>
    <div class="ai-evidence">${evidence}</div>
    <div><strong>Suggestion:</strong> ${data.suggestion || 'No action suggested.'}</div>
  `;
}

// ==================== SCRUBBER + REPLAY ====================

function initReplayModeListeners() {
  if (elements.refreshSessionsBtn) {
    elements.refreshSessionsBtn.addEventListener('click', loadRecordingSessions);
  }

  if (elements.sessionSelect) {
    elements.sessionSelect.addEventListener('change', (e) => {
      const recordingId = e.target.value;
      if (recordingId) {
        switchMode('replay');
        loadRecording(recordingId);
        const health = sessionHealthMap[recordingId];
        if (health) renderSessionHealthPreview(health);
        else {
          const preview = document.getElementById('sessionHealthPreview');
          if (preview) preview.style.display = 'none';
        }
        renderReplayMetadataPreview(recordingId);
        if (elements.editReplayMetadataBtn) elements.editReplayMetadataBtn.style.display = '';
      } else {
        // User selected blank option - clear everything
        currentRecording = null;
        lastHighlightedTick = null;
        replayLogHistory.length = 0;
        replayTickSnapshots.clear();
        replaySnapshotOrder.length = 0;
        replaySnapshotFetchInFlight.clear();
        currentReplayDebugSessionId = null;
        if (typeof perTickSectionToggles !== 'undefined') {
          perTickSectionToggles.clear();
        }
        if (currentMode === 'replay') {
          renderLogHistory('Select a recording to view logs');
        }
        const preview = document.getElementById('sessionHealthPreview');
        if (preview) preview.style.display = 'none';
        hideReplayMetadataEditor();
        if (elements.editReplayMetadataBtn) elements.editReplayMetadataBtn.style.display = 'none';
      }
    });
  }

  // Metadata editor listeners
  if (elements.editReplayMetadataBtn) {
    elements.editReplayMetadataBtn.addEventListener('click', showReplayMetadataEditor);
  }
  if (elements.saveMetadataBtn) {
    elements.saveMetadataBtn.addEventListener('click', saveReplayMetadata);
  }
  if (elements.cancelMetadataBtn) {
    elements.cancelMetadataBtn.addEventListener('click', hideReplayMetadataEditor);
  }
  if (elements.replayTagsInput) {
    elements.replayTagsInput.addEventListener('input', updateTagsPreview);
  }

  if (elements.replayVideo) {
    elements.replayVideo.addEventListener('timeupdate', () => {
      if (currentMode === 'replay' && currentRecording) {
        updateScrubberFromVideo();

        // Throttle sync requests (AbortController handles cancellation)
        const currentTimeMs = Math.floor(elements.replayVideo.currentTime * 1000);
        const timeSinceLastSync = currentTimeMs - lastSyncTimeMs;

        const syncInterval = elements.replayVideo.paused ? SYNC_INTERVAL_PAUSED_MS : SYNC_INTERVAL_PLAYING_MS;
        if (timeSinceLastSync >= syncInterval) {
          lastSyncTimeMs = currentTimeMs;
          syncLogsToVideo(currentTimeMs);
        }
      }
    });

    elements.replayVideo.addEventListener('loadedmetadata', () => {
      updateScrubberFromVideo();
    });
  }

  if (elements.scrubberRange) {
    elements.scrubberRange.addEventListener('input', () => {
      if (currentMode !== 'replay') return;
      elements.replayVideo.currentTime = Number(elements.scrubberRange.value);
      updateScrubberFromVideo();
      // Force immediate sync on manual scrub
      const currentTimeMs = Math.floor(elements.replayVideo.currentTime * 1000);
      lastSyncTimeMs = currentTimeMs;
      syncLogsToVideo(currentTimeMs);
    });
  }

  elements.scrubberPlayBtn.addEventListener('click', () => {
    if (currentMode !== 'replay') return;
    if (elements.replayVideo.paused) {
      elements.replayVideo.play();
      elements.scrubberPlayBtn.innerHTML = '<i class="bi bi-pause-fill"></i>';
    } else {
      elements.replayVideo.pause();
      elements.scrubberPlayBtn.innerHTML = '<i class="bi bi-play-fill"></i>';
    }
  });

  elements.scrubberPrevBtn.addEventListener('click', () => {
    if (currentMode !== 'replay') return;
    elements.replayVideo.currentTime = Math.max(0, elements.replayVideo.currentTime - 1);
  });

  elements.scrubberNextBtn.addEventListener('click', () => {
    if (currentMode !== 'replay') return;
    elements.replayVideo.currentTime = Math.min(
      elements.replayVideo.duration || 0,
      elements.replayVideo.currentTime + 1
    );
  });

  elements.scrubberSpeed.addEventListener('change', () => {
    if (elements.replayVideo) {
      elements.replayVideo.playbackRate = Number(elements.scrubberSpeed.value);
    }
  });
}

function updateScrubberFromVideo() {
  if (!elements.replayVideo || !elements.scrubberRange) return;
  const duration = elements.replayVideo.duration || 0;
  const current = elements.replayVideo.currentTime || 0;

  elements.scrubberRange.max = duration;
  elements.scrubberRange.step = 0.1; // 100ms granularity
  elements.scrubberRange.value = current;
  elements.scrubberTimecode.textContent = `${formatTime(current)} / ${formatTime(duration)}`;
}

function renderSessionHealthPreview(health) {
  const preview = document.getElementById('sessionHealthPreview');
  if (!preview) return;

  const eventsChip = document.getElementById('healthHasEvents');
  const eventCountChip = document.getElementById('healthEventCount');
  const failureChip = document.getElementById('healthFailureCount');
  const lifecycleChip = document.getElementById('healthLifecycleState');

  // Lifecycle state chip
  if (lifecycleChip && health.lifecycleState) {
    const state = health.lifecycleState;
    if (state === 'STARTING') {
      lifecycleChip.textContent = 'Starting';
      lifecycleChip.className = 'health-chip health-muted';
      lifecycleChip.title = 'Session is initializing';
    } else if (state === 'RECORDING') {
      lifecycleChip.textContent = 'Recording';
      lifecycleChip.className = 'health-chip health-active';
      lifecycleChip.title = 'Session is actively recording';
    } else if (state === 'FINALIZED') {
      lifecycleChip.textContent = 'Finalized';
      lifecycleChip.className = 'health-chip health-ok';
      lifecycleChip.title = 'Session completed successfully';
    } else if (state === 'FAILED') {
      lifecycleChip.textContent = 'Failed';
      lifecycleChip.className = 'health-chip health-error';
      lifecycleChip.title = health.failureReason || 'Session failed';
    }
    lifecycleChip.style.display = '';
  } else if (lifecycleChip) {
    lifecycleChip.style.display = 'none';
  }

  if (health.hasEvents) {
    eventsChip.textContent = 'Has Events';
    eventsChip.className = 'health-chip health-ok';
  } else {
    eventsChip.textContent = 'No Events';
    eventsChip.className = 'health-chip health-muted';
  }

  eventCountChip.textContent = `Events: ${health.eventCount ?? 0}`;
  eventCountChip.className = 'health-chip';

  const failures = health.failureMarkerCount ?? 0;
  failureChip.textContent = `Failures: ${failures}`;
  failureChip.className = failures > 0 ? 'health-chip health-error' : 'health-chip health-ok';

  preview.style.display = 'flex';
}

async function loadRecordingSessions() {
  if (!elements.sessionSelect) return;
  const preservedValue = elements.sessionSelect.value;

  try {
    const [recordingRes, debugRes] = await Promise.all([
      fetch('/api/recordings/sessions'),
      fetch('/api/debug/replay/recording-health')
    ]);

    if (!recordingRes.ok) {
      throw new Error('Failed to load sessions');
    }

    // Build health map keyed by recordingId
    sessionHealthMap = {};
    if (debugRes.ok) {
      const debugSessions = await debugRes.json();
      debugSessions.forEach(ds => {
        if (ds.recordingId) sessionHealthMap[ds.recordingId] = ds;
      });
    }

    const sessions = await recordingRes.json();
    elements.sessionSelect.innerHTML = '<option value="">-- Select a recording session --</option>';

    // Build metadata map keyed by recordingId
    sessionMetadataMap = {};

    sessions.forEach(session => {
      const option = document.createElement('option');
      option.value = session.recordingId;

      // Store metadata for editor
      sessionMetadataMap[session.recordingId] = {
        displayName: session.displayName,
        replayName: session.replayName,
        replayTags: session.replayTags || []
      };

      const h = sessionHealthMap[session.recordingId];
      const displayName = session.displayName || `${session.accountName || 'Unknown'}`;

      // Add lifecycle state badge
      let lifecycleBadge = '';
      if (h && h.lifecycleState) {
        const state = h.lifecycleState;
        if (state === 'STARTING') {
          lifecycleBadge = ' [Starting]';
        } else if (state === 'RECORDING') {
          lifecycleBadge = ' [Recording]';
        } else if (state === 'FINALIZED') {
          lifecycleBadge = ' [OK]';
        } else if (state === 'FAILED') {
          const reason = h.failureReason ? `: ${h.failureReason}` : '';
          lifecycleBadge = ` [FAILED${reason}]`;
        }
      }

      const eventPart = h ? ` · Events ${h.eventCount ?? 0}` : '';
      const failPart = h && (h.failureMarkerCount ?? 0) > 0 ? ` · Fail ${h.failureMarkerCount}` : '';
      option.textContent = `${displayName}${lifecycleBadge}${eventPart}${failPart}`;
      elements.sessionSelect.appendChild(option);
    });

    if (sessions.length === 0) {
      const option = document.createElement('option');
      option.textContent = 'No recording sessions found';
      option.disabled = true;
      elements.sessionSelect.appendChild(option);
    }

    if (preservedValue) {
      elements.sessionSelect.value = preservedValue;
      renderReplayMetadataPreview(preservedValue);
      if (elements.editReplayMetadataBtn && currentMode === 'replay') {
        elements.editReplayMetadataBtn.style.display = '';
      }
    }
  } catch (error) {
    console.error('Failed to load recording sessions:', error);
    elements.sessionSelect.innerHTML = '<option value="">Error loading sessions</option>';
  }
}

function renderReplayMetadataPreview(recordingId) {
  const meta = sessionMetadataMap[recordingId];
  if (!meta || !elements.replayMetadataPreview) return;

  elements.replayDisplayName.textContent = meta.displayName || '';
  elements.replayTagsDisplay.innerHTML = '';
  (meta.replayTags || []).forEach(tag => {
    const chip = document.createElement('span');
    chip.className = 'replay-tag-chip';
    chip.textContent = tag;
    elements.replayTagsDisplay.appendChild(chip);
  });

  const hasTags = (meta.replayTags || []).length > 0;
  const hasCustomName = meta.replayName;
  elements.replayMetadataPreview.style.display = (hasCustomName || hasTags) ? 'flex' : 'none';
}

function showReplayMetadataEditor() {
  const recordingId = elements.sessionSelect?.value;
  if (!recordingId) return;

  const meta = sessionMetadataMap[recordingId] || { replayName: '', replayTags: [] };

  if (elements.replayNameInput) {
    elements.replayNameInput.value = meta.replayName || '';
  }
  if (elements.replayTagsInput) {
    elements.replayTagsInput.value = (meta.replayTags || []).join(', ');
  }
  updateTagsPreview();

  if (elements.replayMetadataEditor) elements.replayMetadataEditor.style.display = 'flex';
  if (elements.replayMetadataPreview) elements.replayMetadataPreview.style.display = 'none';
}

function hideReplayMetadataEditor() {
  if (elements.replayMetadataEditor) elements.replayMetadataEditor.style.display = 'none';
  const recordingId = elements.sessionSelect?.value;
  if (recordingId) renderReplayMetadataPreview(recordingId);
}

function updateTagsPreview() {
  if (!elements.replayTagsInput || !elements.replayTagsPreview) return;
  const tags = elements.replayTagsInput.value
    .split(',')
    .map(t => t.trim())
    .filter(t => t.length > 0);
  elements.replayTagsPreview.innerHTML = '';
  tags.forEach(tag => {
    const chip = document.createElement('span');
    chip.className = 'replay-tag-chip';
    chip.textContent = tag;
    elements.replayTagsPreview.appendChild(chip);
  });
}

async function saveReplayMetadata() {
  const recordingId = elements.sessionSelect?.value;
  if (!recordingId) return;

  const body = {};
  if (elements.replayNameInput) {
    body.replayName = elements.replayNameInput.value.trim() || null;
  }
  if (elements.replayTagsInput) {
    body.replayTags = elements.replayTagsInput.value
      .split(',')
      .map(t => t.trim())
      .filter(t => t.length > 0);
  }

  try {
    const res = await fetch(`/api/recordings/sessions/${encodeURIComponent(recordingId)}/metadata`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({}));
      showNotification(err.error || `Failed to save metadata (${res.status})`, 'error');
      return;
    }

    // Update local cache
    const result = await res.json();
    sessionMetadataMap[recordingId] = {
      displayName: result.displayName,
      replayName: result.replayName,
      replayTags: result.replayTags || []
    };

    hideReplayMetadataEditor();

    // Refresh sessions list to update display names
    await loadRecordingSessions();
    if (elements.sessionSelect) elements.sessionSelect.value = recordingId;
    renderReplayMetadataPreview(recordingId);

    showNotification('Replay metadata saved', 'success');
  } catch (error) {
    console.error('Failed to save metadata:', error);
    showNotification('Failed to save metadata', 'error');
  }
}

async function loadRecording(recordingId) {
  // Clear ALL replay state immediately to prevent stale data
  currentRecording = null; // Clear first to stop ongoing syncs
  lastHighlightedTick = null;
  replayLogHistory.length = 0;
  replayTickSnapshots.clear(); // Clear tick snapshot map
  replaySnapshotOrder.length = 0;
  replaySnapshotFetchInFlight.clear();
  replaySnapshotTimeline.length = 0; // Clear snapshot timeline
  replaySessionStartTick = 0; // Reset session start tick
  currentReplayDebugSessionId = null;
  if (typeof perTickSectionToggles !== 'undefined') {
    perTickSectionToggles.clear(); // Clear tick UI state
  }

  // Reset replay state manager
  if (replayTickStateManager) {
    replayTickStateManager.reset();
  }
  lastSyncTimeMs = 0; // Reset throttle

  if (currentMode === 'replay') {
    renderLogHistory('Loading recording...');
  }

  try {
    const response = await fetch(`/api/recordings/${recordingId}`);

    // Handle HTTP errors
    if (!response.ok) {
      if (response.status === 404) {
        console.error('Recording not found:', recordingId);
        alert('Recording session not found. It may have been deleted.');
      } else {
        console.error('Failed to load recording, status:', response.status);
        alert('Failed to load recording. Please try again.');
      }
      // DON'T set currentRecording on failure
      return;
    }

    // Only set currentRecording after successful fetch
    currentRecording = recordingId;
    history.replaceState({}, '', `/debugger?recordingId=${recordingId}`);

    const recording = await response.json();
    currentRecordingSessionId = recording.id;
    currentRecordingFps = recording.fps || 30;

    console.log('Loaded recording metadata:', recording);

    // Check for video errors
    if (recording.videoError) {
      console.error('Video error for recording:', recording.videoError);
      alert(`Cannot load video: ${recording.videoError}\n\nPlease check the server logs for details.`);
      return;
    }

    // Validate videoUrl exists
    if (!recording.videoUrl) {
      console.error('No video URL in response:', recording);
      alert('Video is not available. The recording may not have completed properly.\n\nTry stopping and restarting the recording.');
      return;
    }

    // Load video
    if (elements.replayVideo) {
      console.log('Loading video from:', recording.videoUrl);
      elements.replayVideo.src = recording.videoUrl;
      elements.replayVideo.load();

      // Add error handler for video element
      elements.replayVideo.onerror = (e) => {
        console.error('Video element failed to load:', e);
        alert('Failed to load video file. The file may be corrupted or in an unsupported format.');
      };

      // Add success handler
      elements.replayVideo.onloadeddata = () => {
        console.log('Video loaded successfully, duration:', elements.replayVideo.duration);
      };
    }

    // Clear source/pattern state for new recording
    discoveredSources.clear();
    sourceLogTypes.clear();
    const sourceFilters = document.getElementById('sourceFilters');
    if (sourceFilters) {
      sourceFilters.innerHTML = '';
    }
    updateSourceFilterSummary();

    // Background: fetch debug session + tick cards without blocking video playback
    (async () => {
      try {
        const sessionResponse = await fetch(`/api/debug/replay/sessions/by-recording?recordingId=${recordingId}`);
        if (sessionResponse.ok) {
          const session = await sessionResponse.json();
          currentReplayDebugSessionId = session.sessionId;
          console.log('Loaded debug session for replay:', currentReplayDebugSessionId);

          // Fetch session info to get tick bounds
          let sessionInfo = null;
          try {
            const infoResponse = await fetch(`/api/debug/replay/session-info/${currentReplayDebugSessionId}`);
            if (infoResponse.ok) {
              sessionInfo = await infoResponse.json();
              replaySessionStartTick = sessionInfo.startTick || 0;
              console.log('Replay session info:', {
                startTick: sessionInfo.startTick,
                endTick: sessionInfo.endTick,
                minTick: sessionInfo.minTick,
                maxTick: sessionInfo.maxTick
              });
            }
          } catch (e) {
            console.warn('Failed to fetch session info:', e);
          }

          // Load full tick history upfront (not windowed)
          if (sessionInfo && sessionInfo.minTick != null && sessionInfo.maxTick != null) {
            const minTick = sessionInfo.minTick;
            const maxTick = sessionInfo.maxTick;

            console.log(`Loading full tick history: ticks ${minTick} to ${maxTick} (${maxTick - minTick + 1} ticks)`);

            try {
              const tickCardsResponse = await fetch(
                `/api/debug/replay/tick-cards/${recordingId}?start=${minTick}&end=${maxTick}`
              );

              if (tickCardsResponse.ok) {
                const tickCards = await tickCardsResponse.json();
                console.log(`Loaded ${tickCards.length} tick cards`);

                // Build full replay history from tick cards
                buildReplayHistoryFromTickCards(tickCards);

                // Render the full history
                if (currentMode === 'replay') {
                  renderLogHistory('Loading complete');
                  if (replayLogHistory.length === 0) {
                    diagnoseReplayEmptyLogs(recordingId);
                  }
                }
              } else {
                console.warn('Failed to load tick cards, status:', tickCardsResponse.status);
              }
            } catch (e) {
              console.error('Error loading tick cards:', e);
            }
          } else {
            console.warn('No tick range available for full history loading');
          }
        } else {
          console.warn('No debug session found for recording (old recording or session deleted)');
          currentReplayDebugSessionId = null;
        }
      } catch (e) {
        console.warn('Failed to fetch debug session:', e);
        currentReplayDebugSessionId = null;
      }
    })();

  } catch (error) {
    console.error('Failed to load recording:', error);
    alert('An error occurred while loading the recording:\n' + error.message);
  }
}

/**
 * Build full replay history from tick cards loaded upfront.
 * @param {Array} tickCards - Array of TickCardData from backend
 */
function buildReplayHistoryFromTickCards(tickCards) {
  replayLogHistory.length = 0;
  replayTickSnapshots.clear();
  replaySnapshotTimeline.length = 0;

  if (window.DEBUG_TICK) {
    console.log('[Replay] Building history from', tickCards.length, 'tick cards');
  }

  tickCards.forEach(card => {
    const tick = card.tick;

    // Store snapshot timeline (all ticks, even if no data, to maintain continuity)
    if (!replaySnapshotTimeline.includes(tick)) {
      replaySnapshotTimeline.push(tick);
    }

    // Normalize section data from TickCardData to snapshot shape
    // This maps backend TickCardData.sectionData to frontend snapshot format
    const snapshot = {
      playerState: card.sectionData?.player || null,
      inventory: card.sectionData?.inventory || null,
      equipment: null, // Not included in tick cards currently
      threats: card.sectionData?.npcs || null, // Use npcs as threats for compatibility
      projectiles: card.sectionData?.projectiles || null,
      cooldowns: null, // Not included in tick cards currently
      quest: card.sectionData?.quest || null,
      npcs: card.sectionData?.npcs || null,
      gameObjects: card.sectionData?.objects || null,
      groundObjects: card.sectionData?.groundObjects || null,
      groundItems: card.sectionData?.groundItems || null
    };

    // Store snapshot for all ticks (even empty ones) to show "No data" states
    replayTickSnapshots.set(tick, snapshot);

    if (window.DEBUG_TICK && tick % 10 === 0) {
      const hasData = Object.values(snapshot).some(v => v !== null);
      console.log(`[Replay] Tick ${tick}: ${hasData ? 'has data' : 'empty'}`);
    }

    // Extract logs from actions section (TickLog entities)
    const actionLogs = card.sectionData?.actions;
    if (Array.isArray(actionLogs)) {
      actionLogs.forEach(logEntry => {
        // Normalize TickLog format to frontend log format
        const normalizedLog = {
          kind: 'tick',
          tick: tick,
          level: logEntry.severity || logEntry.level || 'INFO',
          subsystem: logEntry.subsystem || 'System',
          source: logEntry.subsystem || 'System',
          message: logEntry.message || '',
          timestamp: logEntry.timestamp || null,
          logType: logEntry.eventType || null,
          data: logEntry.details || null
        };
        replayLogHistory.push(normalizedLog);
      });
    }

    // Extract dialogue logs (filtered from actions)
    const dialogueLogs = card.sectionData?.dialogue;
    if (Array.isArray(dialogueLogs)) {
      dialogueLogs.forEach(logEntry => {
        const normalizedLog = {
          kind: 'tick',
          tick: tick,
          level: logEntry.severity || 'INFO',
          subsystem: 'Dialogue',
          source: 'Dialogue',
          message: logEntry.message || '',
          timestamp: logEntry.timestamp || null,
          logType: 'DIALOGUE',
          data: logEntry.details || null
        };
        replayLogHistory.push(normalizedLog);
      });
    }

    // Extract varbit logs (filtered from actions)
    const varbitLogs = card.sectionData?.varbits;
    if (Array.isArray(varbitLogs)) {
      varbitLogs.forEach(logEntry => {
        const normalizedLog = {
          kind: 'tick',
          tick: tick,
          level: logEntry.severity || 'INFO',
          subsystem: 'Varbit',
          source: 'Varbit',
          message: logEntry.message || '',
          timestamp: logEntry.timestamp || null,
          logType: 'VARBIT_CHANGE',
          data: logEntry.details || null
        };
        replayLogHistory.push(normalizedLog);
      });
    }
  });

  // Sort snapshot timeline
  replaySnapshotTimeline.sort((a, b) => a - b);

  console.log(`Built replay history: ${replayLogHistory.length} log entries, ${replayTickSnapshots.size} snapshots across ${replaySnapshotTimeline.length} ticks (${replaySnapshotTimeline[0]}-${replaySnapshotTimeline[replaySnapshotTimeline.length - 1]})`);
}

async function diagnoseReplayEmptyLogs(recordingId) {
  try {
    const resp = await fetch(`/api/debug/replay/log-parity?recordingId=${encodeURIComponent(recordingId)}`);
    if (!resp.ok) return;
    const parity = await resp.json();
    if (parity && parity.tickLogCount === 0) {
      ensureLogPlaceholder('No replay tick logs were stored for this recording.');
    } else if (parity && parity.tickLogCount > 0) {
      ensureLogPlaceholder('Replay tick logs exist; scrubbing will fetch them per tick.');
    }
  } catch (e) {
    console.warn('Replay parity diagnosis failed:', e);
  }
}

async function fetchReplayTickLogsWindow(recordingId, tick, windowTicks = 4) {
  try {
    const resp = await fetch(`/api/debug/replay/tick-logs?recordingId=${encodeURIComponent(recordingId)}&tick=${tick}&windowTicks=${windowTicks}`);
    if (!resp.ok) return;
    const payload = await resp.json();
    if (!Array.isArray(payload.logs) || payload.logs.length === 0) return;
    renderReplayLogs(payload.logs, tick);
  } catch (e) {
    console.warn('Failed to fetch replay tick logs window:', e);
  }
}

/**
 * Calculate current tick from video time using snapshot timeline.
 * Uses snapshot timeline as authoritative source if available.
 * @param {number} videoMs - Current video time in milliseconds
 * @returns {number|null} - Calculated tick or null if unable to determine
 */
function calculateTickFromVideoTime(videoMs) {
  // Primary method: Use snapshot timeline if available
  if (replaySnapshotTimeline.length > 0) {
    // Calculate expected tick from video time (600ms per tick)
    const expectedTick = replaySessionStartTick + Math.floor(videoMs / 600);

    // Find closest snapshot tick
    let closestTick = replaySnapshotTimeline[0];
    let minDiff = Math.abs(expectedTick - closestTick);

    for (const snapshotTick of replaySnapshotTimeline) {
      const diff = Math.abs(expectedTick - snapshotTick);
      if (diff < minDiff) {
        minDiff = diff;
        closestTick = snapshotTick;
      }
      // Optimization: If we've passed the expected tick, stop searching
      if (snapshotTick > expectedTick) break;
    }

    // Use snapshot tick if within reasonable range (< 10 ticks drift)
    if (minDiff < 10) {
      return closestTick;
    }
  }

  // Fallback: Calculate from video time
  return replaySessionStartTick + Math.floor(videoMs / 600);
}

/**
 * Fetch and cache a replay snapshot for the given tick.
 * Uses LRU eviction to limit memory usage.
 */
async function maybeFetchReplaySnapshot(tick) {
  if (!currentReplayDebugSessionId || tick === null || tick === undefined) return;
  if (replayTickSnapshots.has(tick)) return; // Already cached
  if (replaySnapshotFetchInFlight.has(tick)) return; // Avoid overlapping requests

  replaySnapshotFetchInFlight.add(tick);
  try {
    const response = await fetch(`/api/debug/replay/tick/${currentReplayDebugSessionId}/${tick}`);
    if (response.ok) {
      const data = await response.json();
      if (data.snapshot) {
        const raw = data.snapshot;
        const normalized = {
          playerState: raw.playerState,
          inventory: raw.inventory ?? null,
          equipment: raw.equipment ?? null,
          threats: raw.threats ?? null,
          projectiles: raw.projectiles ?? null,
          cooldowns: raw.cooldowns ?? null,
          quest: raw.quest ?? null,
          npcs: raw.npcs ?? null,
          gameObjects: raw.gameObjects ?? null,
          groundObjects: raw.groundObjects ?? null,
          groundItems: raw.groundItems ?? null
        };
        replayTickSnapshots.set(tick, normalized);

        // Add to snapshot timeline if not already present
        if (!replaySnapshotTimeline.includes(tick)) {
          replaySnapshotTimeline.push(tick);
          replaySnapshotTimeline.sort((a, b) => a - b); // Keep sorted
        }

        // LRU eviction
        replaySnapshotOrder.push(tick);
        if (replaySnapshotOrder.length > MAX_REPLAY_SNAPSHOTS) {
          const evictTick = replaySnapshotOrder.shift();
          replayTickSnapshots.delete(evictTick);
          // Note: Don't remove from timeline - we still know this tick exists
        }

        // Update bot state + rerender tick group
        if (normalized.playerState) {
          renderTickBotState(normalized);
        }
        ensureTickGroupForSnapshot(tick);
        rerenderTickGroup(tick);
      }
    }
  } catch (error) {
    console.warn('Failed to fetch replay snapshot:', error);
  } finally {
    replaySnapshotFetchInFlight.delete(tick);
  }
}

async function syncLogsToVideo(videoMs) {
  if (!currentRecording) return;

  try {
    // Calculate current tick from video time (using snapshot timeline if available)
    let currentTick = calculateTickFromVideoTime(videoMs);

    // Fallback: fetch from unified endpoint for session-relative calculation
    if (currentTick === null && currentReplayDebugSessionId) {
      // Abort previous in-flight request (abort-and-replace pattern)
      if (currentSyncAbortController) {
        currentSyncAbortController.abort();
      }
      currentSyncAbortController = new AbortController();

      const response = await fetch(
        `/api/debug/replay/unified?recordingId=${currentRecording}&videoTimeMs=${Math.floor(videoMs)}&windowSeconds=1`,
        { signal: currentSyncAbortController.signal }
      );

      if (response.ok) {
        const result = await response.json();
        currentTick = result.currentTick;
      }
      currentSyncAbortController = null;
    }

    if (window.DEBUG_TICK) {
      console.log('[Replay] Sync tick:', currentTick, 'at video time:', videoMs);
    }

    if (currentTick != null) {
      updateTickDisplay(currentTick);
      highlightCurrentTickGroup(currentTick);
      maybeAutoFollowTick(currentTick);
      if (currentMode === 'replay' && currentRecording && replayLogHistory.length === 0) {
        fetchReplayTickLogsWindow(currentRecording, currentTick, 4);
      }
    }

  } catch (error) {
    if (error.name !== 'AbortError') {
      console.error('Failed to sync logs:', error);
    }
  }
}

function renderReplayLogs(logs, currentReplayTick = null) {
  replayLogHistory.length = 0;
  if (!logs || logs.length === 0) {
    // If we have a current tick, render just that tick group with snapshot
    if (currentReplayTick != null && currentMode === 'replay') {
      const outputContainer = elements.logsContainer;
      if (outputContainer) {
        outputContainer.innerHTML = '';
        // Create tick group for snapshot
        const tickGroup = buildTickGroup(currentReplayTick, []);
        outputContainer.appendChild(tickGroup);
        // Fetch snapshot data
        maybeFetchReplaySnapshot(currentReplayTick);
        highlightCurrentTickGroup(currentReplayTick);
      }
      return;
    }
    // No tick and no logs - show placeholder
    if (currentMode === 'replay') {
      renderLogHistory('No logs for this time period');
    }
    return;
  }

  // DEBUG: Log sample entries to understand payload shape
  if (window.DEBUG_TICK) {
    console.log('[Tick Debug] renderReplayLogs received', logs.length, 'entries');
    logs.slice(0, 5).forEach((log, i) => {
      console.log(`[Tick Debug] Entry ${i}:`, {
        origin: log.origin,
        source: log.source,
        tick: log.tick,
        logType: log.logType,
        structuredData: log.structuredData,
        message: log.message?.slice(0, 50)
      });
    });
  }

  logs.forEach(log => {
    const origin = log.origin || ''; // Backend-provided discriminator

    if (origin === 'tick') {
      // Tick log from tick_logs table - use normalizeTickLog
      // Preserve rich fields: logType, data
      const tickLogEntry = {
        kind: 'tick',
        tick: log.tick, // Must preserve even if 0
        level: log.level || 'INFO',
        subsystem: log.source, // Map backend 'source' to frontend 'subsystem'
        source: log.source,
        message: log.message,
        timestamp: log.timestamp,
        logType: log.logType || null,
        // Parse structuredData ONLY if it looks like JSON
        data: safeParseStructuredData(log.structuredData)
      };
      replayLogHistory.push(normalizeTickLog(tickLogEntry));
    } else if (log.logType || log.structuredData || (log.type && log.hasOwnProperty('tick') && log.data)) {
      // ActionLogger format logs (external/micro6 sources with structured data)
      let data = log.data;
      if (!data && log.structuredData) {
        data = safeParseStructuredData(log.structuredData);
      }
      const actionLog = {
        level: log.level || 'INFO',
        message: log.message || log.logType || log.type,
        source: log.source || 'external',
        timestamp: log.timestamp,
        logType: log.logType || log.type,
        tick: log.hasOwnProperty('tick') ? log.tick : null,
        data: data
      };
      replayLogHistory.push(normalizeActionLog(actionLog));
    } else {
      // Standard logs
      const stdLog = {
        ...log,
        tick: log.hasOwnProperty('tick') ? log.tick : null
      };
      replayLogHistory.push(normalizeStandardLog(stdLog));
    }
  });

  // Discover sources incrementally (don't clear existing discoveries to preserve filters)
  replayLogHistory.forEach(entry => {
    let actualSource = entry.source;
    const sourceIsSystem = isSystemSource(entry.source);
    if (!sourceIsSystem && entry.kind !== 'tick') {
      entry.originalSource = entry.source;
      actualSource = extractSourceFromMessage(entry.message, entry.source);
      entry.source = actualSource;
    }

    if (actualSource) {
      const kindSource = entry.originalSource || entry.source;
      const logKind = entry.kind === 'tick'
        ? 'tick'
        : (isSystemSource(kindSource) ? 'standard' : 'external');
      discoverSource(actualSource, logKind);
    }
    discoverPatterns(entry.message);
  });
  clampLogHistory(replayLogHistory);
  updateSourceFilterSummary();
  if (currentMode === 'replay') {
    const container = elements.logsContainer;
    const shouldAutoScroll = autoFollowReplay && !userScrolledRecently;
    const preserveScroll = container && !shouldAutoScroll;
    const previousScrollTop = preserveScroll ? container.scrollTop : 0;
    renderLogHistory();
    lastHighlightedTick = null; // DOM was rebuilt; force re-highlight on next sync
    if (preserveScroll) {
      container.scrollTop = previousScrollTop;
    }
    // Highlight current tick group after rendering
    highlightCurrentTickGroup(currentReplayTick);
    if (shouldAutoScroll && currentMode === 'replay') {
      lastProgrammaticScrollTime = Date.now(); // Mark as programmatic scroll
      container.scrollTop = container.scrollHeight;
    }
  }
}

/**
 * Highlight the current tick group in the log view.
 */
function highlightCurrentTickGroup(currentReplayTick) {
  if (currentReplayTick == null) return;
  if (currentReplayTick === lastHighlightedTick) {
    // Only skip if the group is actually highlighted in the DOM
    const alreadyHighlighted = document.querySelector(
      `.tick-group[data-tick="${currentReplayTick}"].current-tick-group`
    );
    if (alreadyHighlighted) return;
  }
  lastHighlightedTick = currentReplayTick;

  const groups = document.querySelectorAll('.tick-group');
  groups.forEach(g => g.classList.remove('current-tick-group'));

  groups.forEach(g => {
    if (g.dataset.tick == currentReplayTick) {
      g.classList.add('current-tick-group');
    }
  });
}

function scrollToCurrentTick(tick) {
  const container = elements.logsContainer;
  if (!container) return;

  // Find exact group or nearest by numeric distance
  let target = container.querySelector(`.tick-group[data-tick="${tick}"]`);
  if (!target) {
    let minDiff = Infinity;
    container.querySelectorAll('.tick-group[data-tick]').forEach(g => {
      const diff = Math.abs(parseInt(g.dataset.tick) - tick);
      if (diff < minDiff) { minDiff = diff; target = g; }
    });
  }
  if (!target) return;

  const isPlaying = elements.replayVideo && !elements.replayVideo.paused;
  const isLive = currentMode === 'live';
  const now = Date.now();
  lastProgrammaticScrollTime = now;
  programmaticScrollUntil = now + 1200;
  const behavior = (isLive || isPlaying) ? 'instant' : 'smooth';
  if (window.DEBUG_TICK) {
    console.log('[AutoFollow] scrollToCurrentTick tick:', tick, 'behavior:', behavior, 'isLive:', isLive);
  }
  target.scrollIntoView({ block: 'nearest', behavior });
}

/**
 * Auto-follow the given tick in the logs panel if the toggle is enabled and the user
 * has not manually scrolled recently. Works in both live and replay modes.
 */
function maybeAutoFollowTick(tick) {
  if (window.DEBUG_TICK) {
    if (!autoFollowReplay) console.log('[AutoFollow] skip: autoFollowReplay=false');
    else if (tick == null) console.log('[AutoFollow] skip: tick=null');
    else if (userScrolledRecently) console.log('[AutoFollow] skip: userScrolledRecently=true');
  }
  if (!autoFollowReplay || tick == null || userScrolledRecently) return;
  if (!elements.logsContainer) return;
  if (window.DEBUG_TICK) {
    console.log('[AutoFollow] maybeAutoFollowTick tick:', tick, 'mode:', currentMode);
  }
  highlightCurrentTickGroup(tick);
  scrollToCurrentTick(tick);
}

function formatTime(seconds) {
  if (isNaN(seconds)) return '00:00';

  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
}

// ==================== TICK CONTROLS ====================

function startTickPolling() {
  if (tickPoller) clearInterval(tickPoller);
  tickPoller = setInterval(async () => {
    try {
      const response = await fetch('/api/debug/tick/current');
      if (!response.ok) return;
      const data = await response.json();
      elements.tickDisplay.textContent = `Tick ${data.tick ?? '--'}`;
    } catch (error) {
      console.error('Failed to fetch tick info', error);
    }
  }, 1000);
}

function initTickControls() {
  if (!elements.tickJumpBtn) return;
  elements.tickJumpBtn.addEventListener('click', async () => {
    const value = Number(elements.tickJumpInput.value);
    if (Number.isNaN(value)) return;

    try {
      await fetch('/api/debug/tick/jump', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tick: value })
      });
    } catch (error) {
      console.error('Failed to jump tick', error);
    }
  });

  const scrubberJumpBtn = document.getElementById('scrubberTickJumpBtn');
  const scrubberJumpInput = document.getElementById('scrubberTickJumpInput');
  if (scrubberJumpBtn && scrubberJumpInput) {
    scrubberJumpBtn.addEventListener('click', async () => {
      const value = Number(scrubberJumpInput.value);
      if (Number.isNaN(value)) return;

      try {
        await fetch('/api/debug/tick/jump', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ tick: value })
        });
      } catch (error) {
        console.error('Failed to jump tick', error);
      }
    });
  }
}

// ==================== LOG FILTER ====================

function initLogFilters() {
  const levelButtons = document.querySelectorAll('[data-level]');
  const logSearchBtn = document.getElementById('logSearchBtn');
  const clearLogsBtn = document.getElementById('clearLogsBtn');

  if (logSearchBtn) {
    logSearchBtn.addEventListener('click', filterLogs);
  }

  if (clearLogsBtn) {
    clearLogsBtn.addEventListener('click', clearLiveLogHistory);
  }

  // Reset filters button
  if (elements.resetFiltersBtn) {
    elements.resetFiltersBtn.addEventListener('click', resetAllFilters);
  }

  document.getElementById('logSearchInput')?.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
      filterLogs();
    }
  });

  levelButtons.forEach(btn => {
    btn.addEventListener('click', (e) => {
      levelButtons.forEach(b => b.classList.remove('active'));
      e.target.classList.add('active');
      currentLogLevel = e.target.dataset.level;
      filterLogs();
    });
  });
}

function filterLogs() {
  renderLogHistory();
}

// ==================== WINDOW SELECTION ====================

let currentWindowId = '';

async function fetchAndRenderWindows() {
  try {
    const response = await fetch('/api/windows');
    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    const windows = await response.json();
    renderWindowList(windows);
    await loadCurrentWindowSelection();
  } catch (err) {
    console.error('Error fetching windows:', err);
  }
}

function renderWindowList(windows) {
  const windowList = document.getElementById('windowList');
  if (!windowList) return;

  const items = windowList.querySelectorAll('li');
  for (let i = items.length - 1; i >= 2; i--) {
    items[i].remove();
  }

  windows.forEach(window => {
    const listItem = document.createElement('li');
    const anchor = document.createElement('a');
    anchor.className = 'dropdown-item';
    anchor.href = '#';
    anchor.setAttribute('data-window-id', window.id);

    const displayName = window.accountName || window.title;
    anchor.innerHTML = `<i class="bi bi-window-stack me-2"></i>${displayName}`;

    anchor.addEventListener('click', (e) => {
      e.preventDefault();
      selectWindowInternal(window.id, displayName);
    });

    listItem.appendChild(anchor);
    windowList.appendChild(listItem);
  });

  if (windows.length === 0) {
    const listItem = document.createElement('li');
    listItem.innerHTML = '<span class="dropdown-item text-muted">No windows found</span>';
    windowList.appendChild(listItem);
  }
}

async function loadCurrentWindowSelection() {
  try {
    const response = await fetch('/api/windows/current');
    if (response.status === 204 || response.status === 404) {
      updateSelectedWindowLabel('', 'Auto-detect');
      return;
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    const window = await response.json();
    const displayName = window.accountName || window.title;
    updateSelectedWindowLabel(window.id, displayName);
    currentWindowId = window.id;
  } catch (err) {
    console.error('Error loading current window selection:', err);
  }
}

function updateSelectedWindowLabel(id, displayName) {
  const label = document.getElementById('selectedWindowLabel');
  if (label) {
    label.textContent = id ? displayName : 'Auto-detect';
  }
  currentWindowId = id;
}

async function selectWindowInternal(windowId, displayName) {
  try {
    const response = await fetch('/api/windows/select', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: windowId })
    });

    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    updateSelectedWindowLabel(windowId, displayName || 'Auto-detect');
  } catch (err) {
    console.error('Error selecting window:', err);
    alert('Failed to select window. Please try again.');
  }
}

window.selectWindow = function(windowId) {
  selectWindowInternal(windowId, '');
};

// ==================== TICK-BASED REPLAY ====================

let currentDebugSession = null;
let currentTick = null;
let tickRange = { min: 0, max: 0 };
let tickStreamWebSocket = null;
let isPlaying = false;
let playbackSpeed = 1;
let playbackInterval = null;

/**
 * Connect to tick stream WebSocket for live tick updates.
 */
function connectTickStreamWebSocket() {
  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${location.host}/ws/debugger/tick-stream`;

  if (tickStreamWebSocket) {
    tickStreamWebSocket.close();
  }

  tickStreamWebSocket = new WebSocket(wsUrl);

  tickStreamWebSocket.onopen = () => {
    console.log('Connected to tick stream WebSocket');
  };

  tickStreamWebSocket.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);

      // Handle SESSION_CREATED broadcast from Micro6
      if (data.type === 'SESSION_CREATED') {
        // Always keep the canonical debug session pointer in sync with Micro6,
        // independent of feed visibility state.
        currentDebugSessionId = data.sessionId;
        if (!isRecording || !currentSessionId) {
          currentSessionId = data.sessionId;
        }
        if (debugFeedEnabled) {
          isDebugSessionActive = true;
          if (elements.debugSessionStatus) {
            elements.debugSessionStatus.classList.remove('d-none');
          }
        }
        console.log('✅ Micro6 session detected:', data.sessionId);
        return;
      }

      // Ignore tick data when not in live mode
      if (currentMode !== 'live') {
        if (window.DEBUG_TICK) console.log('[Tick Debug] Ignoring tick message - not in live mode');
        return;
      }

      if (!debugFeedEnabled) {
        if (window.DEBUG_TICK) console.log('[Tick Debug] Ignoring tick data - debug feed off');
        return;
      }
      if (window.DEBUG_TICK) console.log('[Tick Debug] Received tick data:', data);
      updatePanelsForTick(data);
    } catch (e) {
      console.error('Failed to parse tick stream message', e);
    }
  };

  tickStreamWebSocket.onerror = (error) => {
    console.error('Tick stream WebSocket error:', error);
  };

  tickStreamWebSocket.onclose = () => {
    tickStreamWebSocket = null;
    // Reconnect after 3 seconds
    setTimeout(connectTickStreamWebSocket, 3000);
  };
}

/**
 * Format tick event for display in Actions section.
 */
function formatTickEvent(event) {
  if (!event || !event.type) return JSON.stringify(event);

  switch (event.type) {
    case 'DAMAGE_TAKEN':
      return `Took ${event.amount} damage from ${event.source || 'unknown'} (${event.hitType || 'unknown'})`;

    case 'DAMAGE_DEALT':
      return `Dealt ${event.amount} damage to ${event.target || 'unknown'} (${event.hitType || 'unknown'})`;

    case 'ITEM_DROPPED':
      return `Dropped ${event.quantity || 1}x ${event.itemName || `Item ${event.itemId}`}`;

    case 'ITEM_PICKED_UP':
      return `Picked up ${event.quantity || 1}x ${event.itemName || `Item ${event.itemId}`}`;

    case 'SPELL_CAST':
      return `Cast ${event.spellName || 'spell'} on ${event.target || 'target'}`;

    case 'PRAYER_ACTIVATED':
      return `Activated prayer: ${event.prayerName || event.prayerId}`;

    case 'PRAYER_DEACTIVATED':
      return `Deactivated prayer: ${event.prayerName || event.prayerId}`;

    case 'DIALOGUE':
      const options = event.options ? `\nOptions: ${event.options.join(', ')}` : '';
      return `${event.npc || 'NPC'}: "${event.text}"${options}`;

    case 'DIALOGUE_OPTION_SELECTED':
      return `Selected dialogue option: "${event.option}"`;

    case 'ITEM_EQUIPPED':
      return `Equipped ${event.itemName || `Item ${event.itemId}`}`;

    case 'ITEM_UNEQUIPPED':
      return `Unequipped ${event.itemName || `Item ${event.itemId}`}`;

    case 'ANIMATION_STARTED':
      return `Started animation ${event.animationId}`;

    case 'PROJECTILE_SPAWNED':
      return `Projectile spawned: ${event.projectileId} (${event.remainingCycles || 0} cycles)`;

    case 'NPC_SPAWNED':
      return `NPC spawned: ${event.npcName || `NPC ${event.npcId}`} at (${event.position?.x}, ${event.position?.y})`;

    case 'NPC_DESPAWNED':
      return `NPC despawned: ${event.npcName || `NPC ${event.npcId}`}`;

    case 'QUEST_STEP_CHANGED':
      return `Quest "${event.questName}" step changed: ${event.oldStep} → ${event.newStep}`;

    case 'VARBIT_CHANGED':
      return `Varbit ${event.varbitId} changed: ${event.oldValue} → ${event.newValue}`;

    case 'PLAYER_MOVED':
      return `Moved to (${event.position?.x}, ${event.position?.y}, plane ${event.position?.plane || 0})`;

    default:
      return `${event.type}: ${JSON.stringify(event)}`;
  }
}

/**
 * Update all panels for a specific tick.
 * Now supports both legacy format and new TICK_SNAPSHOT/TICK_DIFF messages.
 */
function updatePanelsForTick(data) {
  // Get state manager for current mode
  const stateManager = getTickStateManager();

  // Check if this is a new-format message (TICK_SNAPSHOT or TICK_DIFF)
  const isNewFormat = data.type === 'TICK_SNAPSHOT' || data.type === 'TICK_DIFF';

  let tick, state, events;

  if (isNewFormat) {
    // Process new format message (TICK_SNAPSHOT or TICK_DIFF)
    const result = stateManager.processMessage(data);
    if (!result) {
      console.error('Failed to process tick message:', data);
      return;
    }

    tick = result.tick;
    state = result.state;
    events = result.events || [];

    console.log(`[Tick ${tick}] Processed ${data.type}, events: ${events.length}`);

    // Store snapshot in appropriate map
    const snapshot = {
      playerState: state.player || state.playerState,
      inventory: state.inventory,
      equipment: state.equipment,
      threats: state.threats,
      projectiles: state.projectiles,
      cooldowns: state.cooldowns,
      quest: state.quest || state.quests,
      npcs: state.npcs,
      gameObjects: state.objects || state.gameObjects,
      groundObjects: state.groundObjects,
      groundItems: state.groundItems,
      varbits: state.varbits
    };

    if (currentMode === 'live') {
      liveTickSnapshots.set(tick, snapshot);
      // Prune old snapshots
      if (liveTickSnapshots.size > MAX_LOG_ENTRIES) {
        const keysToDelete = [...liveTickSnapshots.keys()].slice(0, liveTickSnapshots.size - MAX_LOG_ENTRIES);
        keysToDelete.forEach(k => liveTickSnapshots.delete(k));
      }
      ensureTickGroupForSnapshot(tick);
    } else {
      replayTickSnapshots.set(tick, snapshot);
      ensureTickGroupForSnapshot(tick);
    }

    // Add events to log history
    if (events.length > 0) {
      events.forEach(event => {
        const syntheticLog = {
          tick: tick,
          subsystem: event.type || 'Event',
          level: event.level || 'INFO',
          eventType: event.type,   // maps to logType via normalizeTickLog
          details: event,          // maps to data via normalizeTickLog
          message: formatTickEvent(event),
          timestamp: new Date().toISOString(),
        };
        const logEntry = normalizeTickLog(syntheticLog);

        if (currentMode === 'live') {
          addLiveLogEntry(logEntry);
        } else {
          // For replay, add to replay log history
          replayLogHistory.push(logEntry);
        }
      });

      // Re-render logs to show new events
      if (currentMode === 'live') {
        renderLogHistory();
      }
    }

    // Render bot state from reconstructed state
    renderTickBotState(snapshot);

  } else {
    // Legacy format - treat as full snapshot
    tick = data.tick;
    const snapshot = {
      playerState: data.playerState || data.snapshot?.playerState,
      inventory: data.inventory || data.snapshot?.inventory,
      equipment: data.equipment || data.snapshot?.equipment,
      threats: data.threats || data.snapshot?.threats,
      projectiles: data.projectiles || data.snapshot?.projectiles,
      cooldowns: data.cooldowns || data.snapshot?.cooldowns,
      quest: data.quest || data.snapshot?.quest,
      npcs: data.npcs || data.snapshot?.npcs,
      gameObjects: data.gameObjects || data.snapshot?.gameObjects,
      groundObjects: data.groundObjects || data.snapshot?.groundObjects,
      groundItems: data.groundItems || data.snapshot?.groundItems
    };

    if (currentMode === 'live') {
      liveTickSnapshots.set(tick, snapshot);
      // Prune old snapshots
      if (liveTickSnapshots.size > MAX_LOG_ENTRIES) {
        const keysToDelete = [...liveTickSnapshots.keys()].slice(0, liveTickSnapshots.size - MAX_LOG_ENTRIES);
        keysToDelete.forEach(k => liveTickSnapshots.delete(k));
      }
      ensureTickGroupForSnapshot(tick);
    } else {
      replayTickSnapshots.set(tick, snapshot);
      ensureTickGroupForSnapshot(tick);
    }

    // Render legacy format
    if (data.snapshot) {
      renderTickBotState(data.snapshot);
    } else if (data.playerState) {
      renderTickBotState({ playerState: data.playerState, threats: data.threats, projectiles: data.projectiles, cooldowns: data.cooldowns });
    }

    // Legacy logs
    if (data.logs && data.logs.length > 0) {
      renderTickLogs(data.logs);
    }
  }

  // Update tick display
  if (tick !== undefined && tick !== null) {
    currentTick = tick;
    updateTickDisplay(tick);
    if (currentMode === 'live') {
      maybeAutoFollowTick(currentTick);
    }
  }

  // Overlays (both formats)
  if (data.overlays) {
    renderTickOverlays(data.overlays);
  }

  // Update AI inspector with current tick
  if (currentDebugSession && currentTick) {
    fetchTickInspector(currentDebugSession, currentTick);
  }
}

/**
 * Update tick display elements.
 */
function updateTickDisplay(tick) {
  const currentTickEl = document.getElementById('currentTick');
  if (currentTickEl) {
    currentTickEl.textContent = tick != null ? tick : '--';
  }

  // Calculate time from tick (600ms per tick)
  const currentTimeEl = document.getElementById('currentTime');
  if (currentTimeEl) {
    if (tick != null) {
      const timeSeconds = (tick * 0.6).toFixed(1);
      currentTimeEl.textContent = formatTickTime(timeSeconds);
    } else {
      currentTimeEl.textContent = '--:--.-';
    }
  }

  // Calculate frame - use recording FPS if available, else default 30
  const currentFrameEl = document.getElementById('currentFrame');
  if (currentFrameEl) {
    if (tick != null) {
      const fps = currentRecordingFps || 30;
      const frame = Math.floor(tick * 0.6 * fps);
      currentFrameEl.textContent = frame;
    } else {
      currentFrameEl.textContent = '--';
    }
  }
}

/**
 * Format time from tick seconds.
 */
function formatTickTime(seconds) {
  const mins = Math.floor(seconds / 60);
  const secs = (seconds % 60).toFixed(1);
  return `${mins.toString().padStart(2, '0')}:${secs.padStart(4, '0')}`;
}

/**
 * Render bot state from tick snapshot.
 */
function renderTickBotState(snapshot) {
  const container = elements.botStateContent;
  if (!container || !snapshot.playerState) return;

  const player = snapshot.playerState;
  const position = player.position || player.worldPosition || player.worldPoint
    || (player.tile ? player.tile : null) || {};
  const hp = player.hp || {};
  const prayer = player.prayer || {};
  const prayerCurrent = prayer.current ?? prayer.points;
  const prayerMax     = prayer.max    ?? prayer.maxPoints;

  const items = [
    { label: 'Position', value: position.x !== undefined && position.y !== undefined
        ? `(${position.x}, ${position.y}, ${position.plane ?? 0})` : '--' },
    { label: 'HP', value: hp.current !== undefined ? `${hp.current} / ${hp.max}` : '--' },
    { label: 'Prayer', value: prayerCurrent !== undefined ? `${prayerCurrent} / ${prayerMax}` : '--' },
    { label: 'Animation', value: player.animation || '--' },
    { label: 'Energy', value: player.energy !== undefined ? `${player.energy}%` : '--' },
    { label: 'Threats', value: snapshot.threats ? snapshot.threats.length : 0 },
    { label: 'Projectiles', value: snapshot.projectiles ? snapshot.projectiles.length : 0 }
  ];

  // Add cooldowns if available
  if (snapshot.cooldowns) {
    Object.entries(snapshot.cooldowns).forEach(([key, value]) => {
      items.push({ label: key, value: value > 0 ? `${value}t` : 'Ready' });
    });
  }

  container.innerHTML = items.map(item => (
    `<div class="state-item">
      <div class="state-label">${item.label}</div>
      <div>${item.value}</div>
    </div>`
  )).join('');
}

/**
 * Render logs from tick batch.
 */
function renderTickLogs(logs) {
  if (currentMode === 'replay') {
    replayLogHistory.length = 0;
    discoveredSources.clear();
    sourceLogTypes.clear();
    const sourceFiltersEl = document.getElementById('sourceFilters');
    if (sourceFiltersEl) sourceFiltersEl.innerHTML = '';

    logs.forEach(log => {
      const entry = normalizeTickLog(log);
      replayLogHistory.push(entry);
      if (entry.source) {
        discoverSource(entry.source, 'tick');
      }
      discoverPatterns(entry.message);
    });

    clampLogHistory(replayLogHistory);
    updateSourceFilterSummary();
    const container = elements.logsContainer;
    const shouldAutoScroll = autoFollowReplay && !userScrolledRecently;
    const preserveScroll = container && !shouldAutoScroll;
    const previousScrollTop = preserveScroll ? container.scrollTop : 0;
    renderLogHistory();
    if (preserveScroll) {
      container.scrollTop = previousScrollTop;
    } else if (shouldAutoScroll) {
      lastProgrammaticScrollTime = Date.now(); // Mark as programmatic scroll
      container.scrollTop = container.scrollHeight;
    }
    highlightCurrentTickGroup(currentTick);
    return;
  }

  logs.forEach(log => {
    addLiveLogEntry(normalizeTickLog(log));
  });
}

/**
 * Render overlays from tick batch.
 */
function renderTickOverlays(overlays) {
  if (!elements.overlayCanvas) return;
  const ctx = elements.overlayCanvas.getContext('2d');
  const metrics = getViewportMetrics();
  if (!metrics) return;

  resizeOverlayCanvas(metrics);
  ctx.clearRect(0, 0, elements.overlayCanvas.width, elements.overlayCanvas.height);

  const scaleX = metrics.width / metrics.sourceWidth;
  const scaleY = metrics.height / metrics.sourceHeight;

  overlays.forEach(overlay => {
    switch (overlay.type) {
      case 'HITBOX':
        renderHitbox(ctx, overlay.data, scaleX, scaleY);
        break;
      case 'PROJECTILE':
        renderProjectile(ctx, overlay.data, scaleX, scaleY);
        break;
      case 'THREAT_ZONE':
        renderThreatZone(ctx, overlay.data, scaleX, scaleY);
        break;
      case 'PLAYER_TILE':
        renderPlayerTile(ctx, overlay.data, scaleX, scaleY);
        break;
      default:
        renderDefaultOverlay(ctx, overlay.data, scaleX, scaleY);
    }
  });
}

/**
 * Render hitbox overlay.
 */
function renderHitbox(ctx, data, scaleX, scaleY) {
  ctx.strokeStyle = 'rgba(255, 77, 77, 0.8)';
  ctx.lineWidth = 2;
  ctx.strokeRect(
    (data.x || 0) * scaleX,
    (data.y || 0) * scaleY,
    (data.width || 0) * scaleX,
    (data.height || 0) * scaleY
  );
}

/**
 * Render projectile overlay.
 */
function renderProjectile(ctx, data, scaleX, scaleY) {
  ctx.strokeStyle = 'rgba(0, 255, 136, 0.8)';
  ctx.lineWidth = 2;

  const x = (data.x || 0) * scaleX;
  const y = (data.y || 0) * scaleY;

  ctx.beginPath();
  ctx.arc(x, y, 5, 0, Math.PI * 2);
  ctx.stroke();

  // Draw ETA text if available
  if (data.eta !== undefined) {
    ctx.fillStyle = 'rgba(0, 255, 136, 0.9)';
    ctx.font = '10px monospace';
    ctx.fillText(`ETA:${data.eta}`, x + 8, y);
  }
}

/**
 * Render threat zone overlay.
 */
function renderThreatZone(ctx, data, scaleX, scaleY) {
  ctx.fillStyle = 'rgba(255, 230, 109, 0.2)';
  ctx.fillRect(
    (data.x || 0) * scaleX,
    (data.y || 0) * scaleY,
    (data.width || 0) * scaleX,
    (data.height || 0) * scaleY
  );
}

/**
 * Render player tile overlay.
 */
function renderPlayerTile(ctx, data, scaleX, scaleY) {
  ctx.strokeStyle = 'rgba(0, 245, 255, 0.8)';
  ctx.lineWidth = 2;
  ctx.strokeRect(
    (data.x || 0) * scaleX,
    (data.y || 0) * scaleY,
    (data.width || 32) * scaleX,
    (data.height || 32) * scaleY
  );
}

/**
 * Render default overlay.
 */
function renderDefaultOverlay(ctx, data, scaleX, scaleY) {
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.5)';
  ctx.lineWidth = 1;
  ctx.strokeRect(
    (data.x || 0) * scaleX,
    (data.y || 0) * scaleY,
    (data.width || 0) * scaleX,
    (data.height || 0) * scaleY
  );
}

/**
 * Highlight overlays for a specific tick.
 */
function highlightTickOverlay(tick) {
  // Future implementation: highlight overlays associated with this tick
}

/**
 * Clear overlay highlight.
 */
function clearHighlightOverlay() {
  // Future implementation: clear highlight
}

/**
 * Fetch tick inspector analysis.
 */
async function fetchTickInspector(sessionId, tick) {
  try {
    const response = await fetch(`/api/debug/ai-inspector/tick/${sessionId}/${tick}?contextWindow=50`);
    if (!response.ok) return;
    const data = await response.json();
    renderTickInspector(data);
  } catch (error) {
    console.error('Failed to fetch tick inspector', error);
  }
}

/**
 * Render tick inspector results.
 */
function renderTickInspector(data) {
  const container = elements.aiInspectorContent;
  if (!container) return;

  const evidence = (data.evidence || []).map(item => `<div>• ${item}</div>`).join('') || 'No evidence.';

  container.innerHTML = `
    <div class="ai-card">
      <div class="ai-pill">${data.faultLabel || 'OK'} · ${data.confidence || 'LOW'}</div>
      <div class="ai-section">
        <strong>Current Tick:</strong> ${data.tick || '--'}
      </div>
      <div class="ai-section">
        <strong>Root Cause:</strong>
        <div>${data.rootCause || 'No issues detected.'}</div>
      </div>
      <div class="ai-section">
        <strong>Evidence:</strong>
        <div class="ai-evidence">${evidence}</div>
      </div>
      <div class="ai-section">
        <strong>Surgical Instruction:</strong>
        <div>${data.surgicalInstruction || 'Continue monitoring.'}</div>
      </div>
    </div>
  `;
}

/**
 * Initialize tick-based replay controls.
 */
function initTickReplayControls() {
  // Start button (jump to start)
  const scrubberStartBtn = document.getElementById('scrubberStartBtn');
  if (scrubberStartBtn) {
    scrubberStartBtn.addEventListener('click', () => jumpToTick(tickRange.min));
  }

  // End button (jump to end)
  const scrubberEndBtn = document.getElementById('scrubberEndBtn');
  if (scrubberEndBtn) {
    scrubberEndBtn.addEventListener('click', () => jumpToTick(tickRange.max));
  }

  // Keyboard shortcuts
  document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT') return; // Ignore when typing

    if (e.key === 'ArrowLeft') {
      jumpToTick((currentTick || 0) - 1);
    } else if (e.key === 'ArrowRight') {
      jumpToTick((currentTick || 0) + 1);
    } else if (e.key === ' ') {
      e.preventDefault();
      toggleTickPlayback();
    }
  });
}

/**
 * Jump to a specific tick.
 */
async function jumpToTick(tick) {
  if (!currentDebugSession || tick < tickRange.min || tick > tickRange.max) return;

  try {
    const response = await fetch(`/api/debug/replay/tick/${currentDebugSession}/${tick}`);
    if (!response.ok) return;

    const data = await response.json();
    updatePanelsForTick(data);

    // Update scrubber
    if (elements.scrubberRange) {
      const scrubberValue = ((tick - tickRange.min) / (tickRange.max - tickRange.min)) * 100;
      elements.scrubberRange.value = scrubberValue;
    }

    currentTick = tick;
  } catch (error) {
    console.error('Failed to jump to tick', error);
  }
}

/**
 * Toggle tick playback.
 */
function toggleTickPlayback() {
  if (isPlaying) {
    stopTickPlayback();
  } else {
    startTickPlayback();
  }
}

/**
 * Start tick playback.
 */
function startTickPlayback() {
  if (!currentDebugSession) return;

  isPlaying = true;
  const scrubberPlayBtn = document.getElementById('scrubberPlayBtn');
  if (scrubberPlayBtn) {
    scrubberPlayBtn.innerHTML = '<i class="bi bi-pause-fill"></i>';
  }

  playbackInterval = setInterval(() => {
    if (currentTick < tickRange.max) {
      jumpToTick(currentTick + 1);
    } else {
      stopTickPlayback();
    }
  }, 600 / playbackSpeed);
}

/**
 * Stop tick playback.
 */
function stopTickPlayback() {
  isPlaying = false;
  clearInterval(playbackInterval);
  const scrubberPlayBtn = document.getElementById('scrubberPlayBtn');
  if (scrubberPlayBtn) {
    scrubberPlayBtn.innerHTML = '<i class="bi bi-play-fill"></i>';
  }
}

/**
 * Load debug session for replay.
 */
async function loadDebugSession(sessionId) {
  try {
    // Clear previous session state
    replayTickSnapshots.clear();
    perTickSectionToggles.clear();
    replayLogHistory.length = 0;

    // Get session info
    const sessionResponse = await fetch(`/api/debug/replay/session-info/${sessionId}`);
    if (!sessionResponse.ok) return;

    const sessionInfo = await sessionResponse.json();
    currentDebugSession = sessionId;
    tickRange.min = sessionInfo.minTick || sessionInfo.startTick || 0;
    tickRange.max = sessionInfo.maxTick || sessionInfo.endTick || tickRange.min;

    // Update scrubber range
    if (elements.scrubberRange) {
      elements.scrubberRange.min = 0;
      elements.scrubberRange.max = 100;
      elements.scrubberRange.value = 0;
    }

    // Update total time and frame
    const totalSeconds = ((tickRange.max - tickRange.min) * 0.6).toFixed(1);
    document.getElementById('totalTime').textContent = formatTickTime(totalSeconds);

    const totalFrame = Math.round((tickRange.max - tickRange.min) * 0.6 * 30);
    document.getElementById('totalFrame').textContent = totalFrame;

    // Load timeline markers
    loadTimelineMarkers(sessionId);

    // Jump to start tick
    jumpToTick(tickRange.min);

  } catch (error) {
    console.error('Failed to load debug session', error);
  }
}

/**
 * Load timeline markers for a session.
 */
async function loadTimelineMarkers(sessionId) {
  try {
    const response = await fetch(`/api/debug/replay/timeline-markers/${sessionId}`);
    if (!response.ok) return;

    const markers = await response.json();
    renderTimelineMarkers(markers);
  } catch (error) {
    console.error('Failed to load timeline markers', error);
  }
}

/**
 * Render timeline markers.
 */
function renderTimelineMarkers(markers) {
  const container = document.getElementById('timelineMarkers');
  if (!container) return;

  container.innerHTML = '';

  markers.forEach(marker => {
    const tickPercent = ((marker.tick - tickRange.min) / (tickRange.max - tickRange.min)) * 100;

    const markerEl = document.createElement('div');
    markerEl.className = `timeline-marker marker-${marker.markerType.toLowerCase()}`;
    markerEl.style.left = `${tickPercent}%`;
    markerEl.title = `${marker.label}: ${marker.description || ''}`;
    markerEl.dataset.tick = marker.tick;

    markerEl.addEventListener('click', () => {
      jumpToTick(marker.tick);
    });

    container.appendChild(markerEl);
  });
}

// ==================== RECORDING CONTROLS ====================

/**
 * Initialize recording controls.
 */
function initRecordingControls() {
  if (!elements.recordBtn) return;

  // Handle record button clicks
  elements.recordBtn.addEventListener('click', async () => {
    if (isRecording) {
      await stopRecording();
    } else {
      await startRecording();
    }
  });

  // Check if recording is already active on page load
  checkRecordingStatus();

  // Periodic state sync check (every 30 seconds)
  // This helps recover from state mismatches
  setInterval(() => {
    checkRecordingStatus();
  }, 30000);
}

/**
 * Start a new recording session.
 */
async function startRecording() {
  try {
    const response = await fetch('/api/debug/replay/start-recording', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accountName: currentWindowId || 'Unknown',
        scriptName: 'Debug Session',
        recordingEnabled: true,
        sessionId: currentDebugSessionId || currentSessionId || null
      })
    });

    if (!response.ok) {
      // Parse error response to get specific error message
      let errorMessage = 'Failed to start recording';
      let errorType = 'UNKNOWN';

      try {
        const errorData = await response.json();
        if (errorData.message) {
          errorMessage = errorData.message;
        }
        if (errorData.errorType) {
          errorType = errorData.errorType;
        }

        // Special handling for already-in-progress error
        if (errorType === 'ALREADY_IN_PROGRESS') {
          console.warn('Recording already in progress:', errorData);

          // Ask user if they want to force reset
          const shouldReset = confirm(
            errorMessage + '\n\nWould you like to force reset the recording state? ' +
            '(Warning: This may leave the previous recording incomplete)'
          );

          if (shouldReset) {
            await forceResetRecording();
            // Retry starting the recording
            await startRecording();
            return;
          }
        }

      } catch (parseError) {
        console.error('Failed to parse error response:', parseError);
      }

      throw new Error(errorMessage);
    }

    const data = await response.json();
    if (data.sessionId && currentSessionId && data.sessionId !== currentSessionId) {
      console.warn(`Recording session rebound from ${currentSessionId} to ${data.sessionId}`);
      showNotification('Recording bound to active tick session', 'info');
    }
    currentSessionId = data.sessionId;
    currentDebugSessionId = data.sessionId;

    // Validate recording actually started before updating UI
    if (data.recordingId) {
      // Recording started successfully
      currentRecordingId = data.recordingId;
      isRecording = true;
      recordingStartTime = Date.now();

      // Update button state
      elements.recordBtn.classList.remove('btn-danger');
      elements.recordBtn.classList.add('btn-success');
      elements.recordBtnText.textContent = 'Stop Recording';

      // Show recording indicator
      if (elements.recordingStatus) {
        elements.recordingStatus.classList.remove('d-none');
      }

      // Start duration timer
      startRecordingDurationTimer();

      console.log('✅ Recording started:', currentRecordingId);
      showNotification('Recording started', 'success');
    } else {
      // Session started but recording failed
      isRecording = false;
      currentRecordingId = null;

      console.warn('⚠️ Session started without recording:', data);
      showNotification('Session started but recording failed - check backend logs', 'warning');
    }

  } catch (error) {
    console.error('❌ Failed to start recording:', error);
    showNotification(error.message, 'error');
    alert('Failed to start recording: ' + error.message);

    // Ensure state is reset on error
    isRecording = false;
    currentRecordingId = null;
    currentSessionId = null;
    currentDebugSessionId = null;
  }
}

/**
 * Stop the active recording session.
 *
 * <p>Routes to the appropriate endpoint based on whether a debug session exists:
 * - If debug session exists: uses /api/debug/replay/stop-recording with sessionId
 * - If no debug session (auto-recording): uses /api/recordings/stop
 */
async function stopRecording() {
  try {
    let endpoint, body, headers = {};

    const stopSessionId = currentDebugSessionId || currentSessionId;

    // If we have a debug session, use the debug stop endpoint
    if (stopSessionId) {
      endpoint = '/api/debug/replay/stop-recording';
      body = JSON.stringify({
        sessionId: stopSessionId,
        endTick: null
      });
      headers['Content-Type'] = 'application/json';
    } else {
      // Auto-recording scenario: use the recording-only stop endpoint
      endpoint = '/api/recordings/stop';
      body = undefined; // No body needed for recording-only stop
    }

    const response = await fetch(endpoint, {
      method: 'POST',
      headers: headers,
      body: body
    });

    // Handle "no recording in progress" gracefully
    if (response.status === 400) {
      const errorData = await response.json().catch(() => ({}));
      if (errorData.message?.includes('No recording in progress') ||
          errorData.error?.includes('No recording in progress')) {
        console.warn('Recording already stopped, resetting UI state');
        isRecording = false;
        currentRecordingId = null;
        currentSessionId = null;
        currentDebugSessionId = null;
        recordingStartTime = null;

        // Reset UI
        elements.recordBtn.classList.remove('btn-success');
        elements.recordBtn.classList.add('btn-danger');
        elements.recordBtnText.textContent = 'Start Recording';

        if (elements.recordingStatus) {
          elements.recordingStatus.classList.add('d-none');
        }

        if (recordingDurationInterval) {
          clearInterval(recordingDurationInterval);
          recordingDurationInterval = null;
        }

        showNotification('Recording was already stopped', 'warning');
        return;
      }
    }

    if (!response.ok) {
      throw new Error('Failed to stop recording');
    }

    const data = await response.json();
    isRecording = false;

    // Update button state
    elements.recordBtn.classList.remove('btn-success');
    elements.recordBtn.classList.add('btn-danger');
    elements.recordBtnText.textContent = 'Start Recording';

    // Hide recording indicator
    if (elements.recordingStatus) {
      elements.recordingStatus.classList.add('d-none');
    }

    // Stop duration timer
    stopRecordingDurationTimer();

    const savedRecordingId = currentRecordingId;
    currentRecordingId = null;
    currentSessionId = null;
    currentDebugSessionId = null;
    recordingStartTime = null;

    console.log('Recording stopped');
    showNotification('Recording stopped', 'success');

    // Refresh sessions list and auto-open the just-stopped recording
    await loadRecordingSessions();
    if (savedRecordingId && elements.sessionSelect) {
      elements.sessionSelect.value = savedRecordingId;
      switchMode('replay');
      loadRecording(savedRecordingId);
    }

  } catch (error) {
    // Separate backend connectivity errors from logic errors
    if (error.message.includes('Failed to fetch') || error.name === 'TypeError') {
      console.error('❌ Backend unreachable:', error);
      showNotification('Backend unreachable - check if ChromaScape is running', 'error');
    } else {
      console.error('❌ Failed to stop recording:', error);
      showNotification('Failed to stop recording: ' + error.message, 'error');
    }
  }
}

/**
 * Force reset the recording state.
 * This is a recovery function for stuck recording states.
 */
async function forceResetRecording() {
  try {
    console.warn('🔄 Force resetting recording state...');

    const response = await fetch('/api/recordings/reset', {
      method: 'POST'
    });

    if (!response.ok) {
      throw new Error('Failed to reset recording state');
    }

    const data = await response.json();

    // Reset frontend state
    isRecording = false;
    currentRecordingId = null;
    currentSessionId = null;
    currentDebugSessionId = null;
    recordingStartTime = null;

    // Update UI
    elements.recordBtn.classList.remove('btn-success');
    elements.recordBtn.classList.add('btn-danger');
    elements.recordBtnText.textContent = 'Start Recording';

    if (elements.recordingStatus) {
      elements.recordingStatus.classList.add('d-none');
    }

    if (recordingDurationInterval) {
      clearInterval(recordingDurationInterval);
      recordingDurationInterval = null;
    }

    console.log('Recording state reset:', data);
    showNotification(data.message, 'warning');

    return data.reset;

  } catch (error) {
    console.error('❌ Failed to reset recording state:', error);
    showNotification('Failed to reset recording state: ' + error.message, 'error');
    throw error;
  }
}

/**
 * Check current recording status and synchronize frontend state with backend.
 */
async function checkRecordingStatus() {
  try {
    const response = await fetch('/api/recordings/status');
    if (!response.ok) return;

    const data = await response.json();
    const backendRecording = data.recording && data.recordingId;

    if (backendRecording && !isRecording) {
      // Backend says recording, but frontend doesn't know about it
      const sourceLabel = data.triggerSource || 'UNKNOWN';
      console.log(`📹 Synchronizing: Backend has active recording [source: ${sourceLabel}], updating frontend state`);

      isRecording = true;
      currentRecordingId = data.recordingId;
      recordingStartTime = data.startTime ? new Date(data.startTime).getTime() : Date.now();

      // Try to find associated debug session
      if (!currentSessionId) {
        try {
          const sessionResponse = await fetch(`/api/debug/replay/sessions/active?recordingId=${currentRecordingId}`);
          if (sessionResponse.ok) {
            const sessionData = await sessionResponse.json();
            currentSessionId = sessionData.sessionId;
            console.log('📹 Found associated debug session:', currentSessionId);
          }
        } catch (e) {
          console.log('📹 No active debug session found for this recording (auto-recording)');
        }
      }

      // Update UI to recording state
      elements.recordBtn.classList.remove('btn-danger');
      elements.recordBtn.classList.add('btn-success');
      elements.recordBtnText.textContent = 'Stop Recording';

      if (elements.recordingStatus) {
        elements.recordingStatus.classList.remove('d-none');
      }

      startRecordingDurationTimer();

      const srcNote = data.triggerSource ? ` [${data.triggerSource}]` : '';
      console.log(`📹 Recording already in progress: ${currentRecordingId}${srcNote}`);
      if (data.triggerSource && data.triggerSource !== 'MANUAL') {
        showNotification(`Recording active (${data.triggerSource.replace('_', ' ')})`, 'info');
      }

    } else if (!backendRecording && isRecording) {
      // Frontend thinks we're recording, but backend says no
      console.warn('State mismatch: Frontend thinks recording, but backend says no. Resetting frontend state.');

      isRecording = false;
      currentRecordingId = null;
      currentSessionId = null;
      currentDebugSessionId = null;
      recordingStartTime = null;

      // Reset UI
      elements.recordBtn.classList.remove('btn-success');
      elements.recordBtn.classList.add('btn-danger');
      elements.recordBtnText.textContent = 'Start Recording';

      if (elements.recordingStatus) {
        elements.recordingStatus.classList.add('d-none');
      }

      if (recordingDurationInterval) {
        clearInterval(recordingDurationInterval);
        recordingDurationInterval = null;
      }
    }
  } catch (error) {
    console.error('Failed to check recording status:', error);
  }
}

/**
 * Start timer to show recording duration.
 */
function startRecordingDurationTimer() {
  if (recordingDurationInterval) {
    clearInterval(recordingDurationInterval);
  }

  recordingDurationInterval = setInterval(() => {
    if (!recordingStartTime || !elements.recordingDuration) return;

    const elapsed = Date.now() - recordingStartTime;
    const minutes = Math.floor(elapsed / 60000);
    const seconds = Math.floor((elapsed % 60000) / 1000);

    elements.recordingDuration.textContent =
      `${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}`;
  }, 1000);
}

/**
 * Stop recording duration timer.
 */
function stopRecordingDurationTimer() {
  if (recordingDurationInterval) {
    clearInterval(recordingDurationInterval);
    recordingDurationInterval = null;
  }
}

/**
 * Show notification toast.
 */
function showNotification(message, type = 'info') {
  console.log(`[${type.toUpperCase()}] ${message}`);
}

if (typeof window !== 'undefined') {
  window.showNotification = showNotification;
}

// ==================== DEBUG SESSION CONTROLS ====================

/**
 * Initialize debug session button (live mode only).
 */
function initDebugSessionControls() {
  if (!elements.startDebugSessionBtn) return;

  elements.startDebugSessionBtn.addEventListener('click', async () => {
    await startDebugSession();
  });
}

/**
 * Initialize debug feed toggle (pause/resume Micro6 tick feed).
 */
function initDebugFeedControls() {
  if (!elements.debugFeedToggleBtn) return;

  const saved = localStorage.getItem(DEBUG_FEED_KEY);
  debugFeedEnabled = saved === 'true';
  setDebugFeedEnabled(debugFeedEnabled, true);

  elements.debugFeedToggleBtn.addEventListener('click', async () => {
    const next = !debugFeedEnabled;
    setDebugFeedEnabled(next, true);
  });
}

function updateDebugFeedToggleUi() {
  if (!elements.debugFeedToggleBtn) return;
  if (debugFeedEnabled) {
    elements.debugFeedToggleBtn.classList.remove('btn-outline-secondary');
    elements.debugFeedToggleBtn.classList.add('btn-outline-success');
    elements.debugFeedToggleBtn.querySelector('span').textContent = 'Debug Feed: On';
  } else {
    elements.debugFeedToggleBtn.classList.remove('btn-outline-success');
    elements.debugFeedToggleBtn.classList.add('btn-outline-secondary');
    elements.debugFeedToggleBtn.querySelector('span').textContent = 'Debug Feed: Off';
  }
}

async function setDebugFeedEnabled(enabled, sendSignal) {
  debugFeedEnabled = enabled;
  localStorage.setItem(DEBUG_FEED_KEY, String(enabled));
  updateDebugFeedToggleUi();

  if (!enabled) {
    // Hide debug session indicator when feed is off (do not stop recording)
    if (elements.debugSessionStatus) {
      elements.debugSessionStatus.classList.add('d-none');
    }
    isDebugSessionActive = false;
  }

  if (sendSignal) {
    try {
      const response = await fetch('/api/debug/feed-control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled })
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
    } catch (error) {
      console.error('Failed to control debug feed:', error);
      showNotification('Failed to control debug feed: ' + error.message, 'error');
    }
  }
}

/**
 * Start a debug session without recording.
 */
async function startDebugSession() {
  try {
    const response = await fetch('/api/debug/replay/start-session', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accountName: currentWindowId || 'Unknown',
        scriptName: 'Live Debug'
      })
    });

    if (!response.ok) {
      throw new Error('Failed to start debug session');
    }

    const data = await response.json();
    currentDebugSessionId = data.sessionId;
    isDebugSessionActive = true;

    // Ensure debug feed is enabled when starting a session
    if (!debugFeedEnabled) {
      setDebugFeedEnabled(true, true);
    }

    // Show debug session status badge
    if (elements.debugSessionStatus) {
      elements.debugSessionStatus.classList.remove('d-none');
    }

    // Disable the button to prevent duplicate sessions
    if (elements.startDebugSessionBtn) {
      elements.startDebugSessionBtn.disabled = true;
      elements.startDebugSessionBtn.classList.add('btn-outline-secondary');
      elements.startDebugSessionBtn.classList.remove('btn-outline-success');
    }

    console.log('✅ Debug session started:', currentDebugSessionId);
    showNotification('Debug session started - tick data now visible', 'success');

  } catch (error) {
    console.error('❌ Failed to start debug session:', error);
    showNotification('Failed to start debug session: ' + error.message, 'error');
  }
}

// ==================== TASK RECORDING CONTROLS ====================

/**
 * Initialize task recording toggles.
 */
function initTaskRecordingControls() {
  const taskTogglesContainer = document.getElementById('taskToggles');
  if (!taskTogglesContainer) return;

  // Fetch current task states and render toggles
  fetch('/api/recordings/tasks')
    .then(r => r.json())
    .then(taskStates => {
      renderTaskToggles(taskTogglesContainer, taskStates);
    })
    .catch(err => console.error('Failed to load task states:', err));

  // Reset button handler
  const resetBtn = document.getElementById('resetTasksBtn');
  if (resetBtn) {
    resetBtn.addEventListener('click', async () => {
      const response = await fetch('/api/recordings/tasks/reset', { method: 'POST' });
      const taskStates = await response.json();
      renderTaskToggles(taskTogglesContainer, taskStates);
    });
  }
}

/**
 * Render task toggle checkboxes.
 */
function renderTaskToggles(container, taskStates) {
  container.innerHTML = '';

  Object.keys(taskStates).forEach(taskKey => {
    const enabled = taskStates[taskKey];
    const displayName = taskKey.charAt(0).toUpperCase() + taskKey.slice(1);

    const item = document.createElement('div');
    item.className = 'task-toggle-item' + (enabled ? ' active' : '');
    item.innerHTML = `
      <input type="checkbox" id="task-${taskKey}" ${enabled ? 'checked' : ''}>
      <label for="task-${taskKey}">${displayName}</label>
    `;

    // Add click handler to toggle
    item.addEventListener('click', async (e) => {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'LABEL') {
        const checkbox = item.querySelector('input');
        const newState = !checkbox.checked;

        // Update server
        await fetch(`/api/recordings/tasks/${taskKey}?enabled=${newState}`, {
          method: 'POST'
        });

        // Update UI
        checkbox.checked = newState;
        item.classList.toggle('active', newState);
      }
    });

    container.appendChild(item);
  });
}

// ==================== VIEWPORT PAUSE/RESUME ====================

async function togglePauseCapture() {
  const pauseIcon = document.getElementById("pauseIcon");
  const isCurrentlyPaused = pauseIcon.classList.contains("bi-play-fill");

  try {
    const endpoint = isCurrentlyPaused ? "/api/screencapture/resume" : "/api/screencapture/pause";
    const response = await fetch(endpoint, { method: "POST" });

    if (response.ok) {
      pauseIcon.classList.toggle("bi-play-fill", !isCurrentlyPaused);
      pauseIcon.classList.toggle("bi-pause-fill", isCurrentlyPaused);
      console.log(`Screen capture ${isCurrentlyPaused ? 'resumed' : 'paused'}`);
    }
  } catch (error) {
    console.error("Failed to toggle pause state:", error);
  }
}

async function updatePauseButtonState() {
  try {
    const response = await fetch("/api/screencapture/status");
    if (response.ok) {
      const data = await response.json();
      const pauseIcon = document.getElementById("pauseIcon");
      if (pauseIcon) {
        pauseIcon.classList.toggle("bi-play-fill", data.paused);
        pauseIcon.classList.toggle("bi-pause-fill", !data.paused);
      }
    }
  } catch (error) {
    console.error("Failed to fetch pause state:", error);
  }
}

// ==================== PATCH LOOP CONTROLS ====================

const PATCH_LOOP_CATALOG_POLL_INTERVAL_MS = 30000;
let patchLoopCatalogPollTimer = null;
let patchLoopCatalogEntries = [];

function setPatchScriptCatalogStatus(message, tone = 'muted') {
  const status = document.getElementById('patchScriptCatalogStatus');
  if (!status) return;

  const toneClass = {
    muted: 'text-muted',
    success: 'text-success',
    warning: 'text-warning',
    error: 'text-danger'
  }[tone] || 'text-muted';

  status.className = `form-text mt-1 ${toneClass}`;
  status.textContent = message;
}

function buildPatchLoopSearchIndex(task) {
  const aliasText = Array.isArray(task?.search_aliases) ? task.search_aliases.join(' ') : '';
  const tokens = [
    task?.panel_display_name,
    task?.display_name,
    task?.task_key,
    task?.category,
    Array.isArray(task?.tags) ? task.tags.join(' ') : '',
    aliasText
  ].filter(Boolean);
  return tokens.join(' ').toLowerCase();
}

function createPatchLoopOption(select, value, label, disabled = false) {
  const option = document.createElement('option');
  option.value = value;
  option.textContent = label;
  option.disabled = disabled;
  select.appendChild(option);
}

function renderPatchLoopScriptOptions(selectedValue = '') {
  const select = document.getElementById('patchScriptSelect');
  const searchInput = document.getElementById('patchScriptSearchInput');
  if (!select) return;

  const query = (searchInput?.value || '').trim().toLowerCase();
  select.innerHTML = '<option value="">Select script...</option>';

  const filtered = query
    ? patchLoopCatalogEntries.filter(entry => entry.searchText.includes(query))
    : [...patchLoopCatalogEntries];

  const grouped = new Map();
  filtered.forEach(entry => {
    if (!grouped.has(entry.group)) {
      grouped.set(entry.group, []);
    }
    grouped.get(entry.group).push(entry);
  });

  for (const [groupLabel, entries] of grouped.entries()) {
    const group = document.createElement('optgroup');
    group.label = groupLabel;
    entries.forEach(entry => {
      createPatchLoopOption(group, entry.value, entry.label, entry.disabled);
    });
    select.appendChild(group);
  }

  if (filtered.length === 0) {
    const emptyLabel = query ? 'No scripts match your search' : 'No scripts/tasks available';
    createPatchLoopOption(select, '', emptyLabel, true);
  }

  if (selectedValue) {
    const matching = Array.from(select.options).find(
      option => option.value === selectedValue && option.disabled !== true
    );
    if (matching) {
      select.value = selectedValue;
    }
  }
}

function toBase64UrlUtf8(input) {
  try {
    const utf8 = new TextEncoder().encode(String(input || ''));
    let binary = '';
    for (let i = 0; i < utf8.length; i++) {
      binary += String.fromCharCode(utf8[i]);
    }
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  } catch (e) {
    console.warn('Failed to encode display task key:', e);
    return '';
  }
}

function buildMicro6ExecutionValue(task) {
  const rawTaskKey = (task?.task_key || '').toString();
  if (rawTaskKey.startsWith('DISPLAY::')) {
    return `micro6:${rawTaskKey}`;
  }
  const panelDisplayName = (task?.panel_display_name || task?.display_name || '').toString().trim();
  if (panelDisplayName.length > 0) {
    const encoded = toBase64UrlUtf8(panelDisplayName);
    if (encoded.length > 0) {
      return `micro6:DISPLAY::${encoded}`;
    }
  }
  return `micro6:${rawTaskKey}`;
}

/**
 * Loads available scripts/tasks for patch loop via backend catalog proxy.
 *
 * @param {boolean} forceRefresh when true, asks backend to refresh upstream catalog
 */
async function loadPatchLoopScripts(forceRefresh = false) {
  try {
    const select = document.getElementById('patchScriptSelect');
    if (!select) return;

    const selectedValue = select.value;
    patchLoopCatalogEntries = [];
    select.innerHTML = '<option value="">Select script...</option>';
    setPatchScriptCatalogStatus('Loading script catalog...');

    const catalogUrl = `/api/debug/patch-loop/catalog${forceRefresh ? '?refresh=true' : ''}`;

    let catalogPayload = null;
    try {
      const resp = await fetch(catalogUrl);
      if (!resp.ok) {
        throw new Error(`HTTP ${resp.status}`);
      }
      catalogPayload = await resp.json();
    } catch (e) {
      console.warn('Failed to fetch patch-loop catalog:', e);
    }

    let queueCount = 0;
    let patchLoopCount = 0;
    let micro6QueueCount = 0;
    let otherQueueCount = 0;

    const tasks = Array.isArray(catalogPayload?.tasks) ? catalogPayload.tasks : [];
    const sourceMap = catalogPayload?.sources && typeof catalogPayload.sources === 'object'
      ? catalogPayload.sources
      : {};
    const payloadErrors = Array.isArray(catalogPayload?.errors) ? catalogPayload.errors : [];

    if (tasks.length > 0) {
      const queueTasks = tasks.filter(
        t => t && (t.queueable === true || t.execution_mode === 'queue')
      );
      const patchLoopTasks = tasks.filter(
        t => t && t.execution_mode === 'patch_loop' && t.queueable !== true
      );
      const micro6QueueTasks = queueTasks
        .filter(t => (t?.client_type || '').toLowerCase() === 'micro6')
        .sort((a, b) => {
          const catA = (a?.category || '').toString().toLowerCase();
          const catB = (b?.category || '').toString().toLowerCase();
          if (catA !== catB) return catA.localeCompare(catB);
          const nameA = (a?.display_name || a?.task_key || '').toString().toLowerCase();
          const nameB = (b?.display_name || b?.task_key || '').toString().toLowerCase();
          return nameA.localeCompare(nameB);
        });
      const otherQueueTasks = queueTasks
        .filter(t => (t?.client_type || '').toLowerCase() !== 'micro6')
        .sort((a, b) =>
          (a?.display_name || a?.task_key || '')
            .toString()
            .localeCompare((b?.display_name || b?.task_key || '').toString())
        );

      const seenMicro6Labels = new Set();
      micro6QueueTasks.forEach(task => {
        const taskKey = task?.task_key;
        if (!taskKey) return;
        const displayName = task?.panel_display_name || task?.display_name || taskKey;
        const dedupeKey = displayName.toString().trim().toLowerCase();
        if (dedupeKey && seenMicro6Labels.has(dedupeKey)) {
          return;
        }
        if (dedupeKey) {
          seenMicro6Labels.add(dedupeKey);
        }
        const category = task?.category ? ` [${task.category}]` : '';
        const isRunnable = task?.runnable !== false;
        const disabledReason = task?.disabled_reason ? ` [not runnable: ${task.disabled_reason}]` : '';
        patchLoopCatalogEntries.push({
          group: 'Micro6 AccountBuilder',
          value: buildMicro6ExecutionValue(task),
          label: `${displayName} (${taskKey})${category}${isRunnable ? '' : disabledReason}`,
          disabled: !isRunnable,
          searchText: buildPatchLoopSearchIndex(task)
        });
        queueCount++;
        micro6QueueCount++;
      });

      otherQueueTasks.forEach(task => {
        const taskKey = task?.task_key;
        if (!taskKey) return;
        const clientType = task?.client_type || 'unknown';
        const displayName = task?.display_name || taskKey;
        patchLoopCatalogEntries.push({
          group: 'Other Queue Tasks',
          value: `${clientType}:${taskKey}`,
          label: `${displayName} (${taskKey}) [${clientType}] [visible only - not executable in patch loop]`,
          disabled: true,
          searchText: buildPatchLoopSearchIndex(task)
        });
        queueCount++;
        otherQueueCount++;
      });

      if (patchLoopTasks.length > 0) {
        patchLoopTasks.forEach(task => {
          const taskKey = task?.task_key;
          if (!taskKey) return;
          const displayName = task?.display_name || taskKey;
          patchLoopCatalogEntries.push({
            group: 'Patch-Loop Scripts (local)',
            value: `local:${displayName}`,
            label: displayName,
            disabled: false,
            searchText: buildPatchLoopSearchIndex(task)
          });
          patchLoopCount++;
        });
      }
    }

    renderPatchLoopScriptOptions(selectedValue);

    const sourceFailures = Object.entries(sourceMap)
      .filter(([, meta]) => meta && meta.ok === false)
      .map(([name, meta]) => `${name}: ${meta.error || 'unavailable'}`);
    const failureMessages = Array.from(new Set([...sourceFailures, ...payloadErrors]));

    if (failureMessages.length > 0) {
      const summary = failureMessages.slice(0, 2).join(' | ');
      setPatchScriptCatalogStatus(`Catalog degraded: ${summary}`, 'warning');
    } else if (queueCount > 0 || patchLoopCount > 0) {
      setPatchScriptCatalogStatus(
        `Loaded ${queueCount + patchLoopCount} tasks (${micro6QueueCount} micro6, ${otherQueueCount} other queue, ${patchLoopCount} patch-loop).`,
        'success'
      );
    } else {
      setPatchScriptCatalogStatus('No scripts/tasks available.', 'muted');
    }

    console.log(`Loaded patch loop options: queue=${queueCount}, patch_loop=${patchLoopCount}`);
  } catch (error) {
    console.error('Error loading patch loop scripts:', error);
    setPatchScriptCatalogStatus(`Failed to load catalog: ${error.message}`, 'error');
  }
}

/**
 * Polls provider status and updates UI badge.
 */
async function updateProviderStatus() {
  try {
    const response = await fetch('/api/debug/patch-loop/provider/status');
    if (!response.ok) return;

    const status = await response.json();
    const badge = document.getElementById('providerStatusBadge');
    const nameText = document.getElementById('providerNameText');

    if (!badge || !nameText) return;

    if (status.available) {
      badge.className = 'badge bg-success';
      badge.innerHTML = '<i class="bi bi-check-circle me-1"></i>Available';
    } else {
      badge.className = 'badge bg-danger';
      badge.innerHTML = '<i class="bi bi-x-circle me-1"></i>Unavailable';
    }

    nameText.textContent = buildProviderDisplayText(status);

    // Update provider detail row
    const detailRow = document.getElementById('providerDetailRow');
    if (detailRow) {
      const runtimeProvider = document.getElementById('providerRuntimeProvider');
      const runtimeModel = document.getElementById('providerRuntimeModel');
      const bridgeMode = document.getElementById('providerBridgeMode');
      if (runtimeProvider) runtimeProvider.textContent = status.runtimeProvider || status.frameworkProvider || 'unknown';
      if (runtimeModel) runtimeModel.textContent = status.runtimeModel || 'unknown';
      if (bridgeMode) bridgeMode.textContent = 'mode: ' + (status.bridgeMode || 'unknown');
      detailRow.style.display = 'block';
    }
  } catch (error) {
    console.error('Error fetching provider status:', error);
  }
}

function buildProviderDisplayText(status) {
  const frameworkProvider = status.frameworkProvider || status.providerName || 'unknown';
  const runtimeProvider = status.runtimeProvider;
  const runtimeModel = status.runtimeModel;
  const hasRuntimeWrapper =
    runtimeProvider && runtimeProvider !== 'unknown' && runtimeProvider !== frameworkProvider;

  if (hasRuntimeWrapper) {
    if (runtimeModel) {
      return `Provider: ${frameworkProvider} (wrapper: ${runtimeProvider}, model: ${runtimeModel})`;
    }
    return `Provider: ${frameworkProvider} (wrapper: ${runtimeProvider})`;
  }

  if (runtimeModel) {
    return `Provider: ${frameworkProvider} (model: ${runtimeModel})`;
  }

  return `Provider: ${frameworkProvider}`;
}

function buildProviderUnavailableMessage(status) {
  const frameworkProvider = status.frameworkProvider || status.providerName || 'unknown';
  const runtimeProvider = status.runtimeProvider;
  const runtimeModel = status.runtimeModel;
  const availabilityReason = status.availabilityReason;
  const hasRuntimeWrapper =
    runtimeProvider && runtimeProvider !== 'unknown' && runtimeProvider !== frameworkProvider;
  const reasonSuffix = availabilityReason && availabilityReason !== 'unknown'
    ? ` (${String(availabilityReason).replace(/_/g, ' ')})`
    : '';

  if (hasRuntimeWrapper) {
    if (runtimeModel) {
      return `Provider (${frameworkProvider} / ${runtimeProvider}, model: ${runtimeModel}) is unavailable${reasonSuffix}`;
    }
    return `Provider (${frameworkProvider} / ${runtimeProvider}) is unavailable${reasonSuffix}`;
  }

  if (runtimeModel) {
    return `Provider (${frameworkProvider}, model: ${runtimeModel}) is unavailable${reasonSuffix}`;
  }

  return `Provider (${frameworkProvider}) is unavailable${reasonSuffix}`;
}

/**
 * Derives execution requirements from a compiled graph's node types.
 *
 * TRADEOFF: The permissive fallback (no provider/prompt required when response shape is
 * unexpected) prioritizes not blocking run-only workflows over catching contract drift early.
 * If the compile response shape changes (e.g., nodes array removed or renamed), execution
 * will proceed to the server and fail there with a real error message rather than being
 * silently blocked client-side with a misleading "provider unavailable" message.
 * See PatchLoopGraphCompilerTest for contract-locked tests that guard against this drift.
 *
 * @param {object} compiledGraph - The compiledGraph from the compile response
 * @returns {{ requiresProvider: boolean, requiresGoalPrompt: boolean }}
 */
function deriveGraphRequirements(compiledGraph) {
  if (!compiledGraph || !compiledGraph.nodes) {
    // Permissive fallback: don't block run-only flows on response-contract drift.
    // If the response shape is unexpected, let execution attempt and fail server-side.
    return { requiresProvider: false, requiresGoalPrompt: false };
  }
  // Only ANALYZE invokes the analysis provider; downstream AI nodes operate on its output.
  const hasAnalysis = compiledGraph.nodes.some(n => n.type === 'ANALYZE');
  return { requiresProvider: hasAnalysis, requiresGoalPrompt: hasAnalysis };
}

/**
 * Validates preflight conditions before executing patch loop.
 * @param {{ requiresProvider: boolean, requiresGoalPrompt: boolean }} requirements
 * @returns { valid: boolean, message: string }
 */
async function validatePatchLoopPreflight(requirements) {
  const script = document.getElementById('patchScriptSelect')?.value;
  const prompt = document.getElementById('goalPromptInput')?.value;

  if (!script) {
    return { valid: false, message: 'Please select a script' };
  }

  if (requirements.requiresGoalPrompt && (!prompt || prompt.trim().length === 0)) {
    return { valid: false, message: 'Please enter a goal prompt (required for analysis graphs)' };
  }

  if (requirements.requiresProvider) {
    try {
      const response = await fetch('/api/debug/patch-loop/provider/status');
      if (response.ok) {
        const status = await response.json();
        if (!status.available) {
          return { valid: false, message: buildProviderUnavailableMessage(status) };
        }
      }
    } catch (error) {
      console.warn('Could not verify provider status:', error);
    }
  }

  return { valid: true };
}

/**
 * Initialize Patch Loop UI controls and event listeners.
 */
function initPatchLoopControls() {
  // Load scripts and provider status on initialization
  loadPatchLoopScripts(true);
  updateProviderStatus();

  // Poll provider status every 15 seconds
  setInterval(updateProviderStatus, 15000);

  // Poll script catalog periodically and allow manual refresh.
  if (patchLoopCatalogPollTimer) {
    clearInterval(patchLoopCatalogPollTimer);
  }
  patchLoopCatalogPollTimer = setInterval(() => {
    loadPatchLoopScripts(true);
  }, PATCH_LOOP_CATALOG_POLL_INTERVAL_MS);

  const refreshCatalogBtn = document.getElementById('refreshPatchScriptCatalogBtn');
  if (refreshCatalogBtn) {
    refreshCatalogBtn.addEventListener('click', async () => {
      refreshCatalogBtn.disabled = true;
      try {
        await loadPatchLoopScripts(true);
      } finally {
        refreshCatalogBtn.disabled = false;
      }
    });
  }

  const patchScriptSearchInput = document.getElementById('patchScriptSearchInput');
  if (patchScriptSearchInput) {
    patchScriptSearchInput.addEventListener('input', () => {
      const selectedValue = document.getElementById('patchScriptSelect')?.value || '';
      renderPatchLoopScriptOptions(selectedValue);
    });
  }

  // Toggle patch loop panel collapse/expand
  const togglePatchLoopBtn = document.getElementById('togglePatchLoopBtn');
  if (togglePatchLoopBtn) {
    togglePatchLoopBtn.addEventListener('click', function() {
      const panel = document.getElementById('patchLoopPanel');
      const content = document.getElementById('patchPanelContent');
      const icon = togglePatchLoopBtn.querySelector('i');
      if (content.style.display === 'none') {
        content.style.display = 'block';
        icon.classList.remove('bi-chevron-down');
        icon.classList.add('bi-chevron-up');
      } else {
        content.style.display = 'none';
        icon.classList.remove('bi-chevron-up');
        icon.classList.add('bi-chevron-down');
      }
    });
  }

  // Load template button
  const loadTemplateBtn = document.getElementById('loadTemplateBtn');
  if (loadTemplateBtn) {
    loadTemplateBtn.addEventListener('click', function() {
      const template = `graph TD;
A[start] --> B[run_script]
B --> C[collect_evidence]
C --> D[analyze]
D --> E[generate_patch]
E --> F[apply_patch]
F --> G[validate]
G --> H[retest]
H --> I[evaluate_success]
I -.success.-> J[end]
I -.retry.-> D
I -.failure.-> J`;
      const mermaidInput = document.getElementById('mermaidInput');
      if (mermaidInput) {
        mermaidInput.value = template;
      }
    });
  }

  // Retry from node select
  const retryFromNodeSelect = document.getElementById('retryFromNodeSelect');
  if (retryFromNodeSelect) {
    retryFromNodeSelect.addEventListener('change', async function() {
      const nodeId = this.value;
      if (nodeId && window.patchLoopManager && window.patchLoopManager.iterationId) {
        try {
          await window.patchLoopManager.controlExecution(
            window.patchLoopManager.iterationId,
            'RETRY_FROM_NODE',
            nodeId
          );
          console.log('Retrying from node:', nodeId);
        } catch (error) {
          console.error('Failed to retry from node:', error);
        }
      }
    });
  }

  // Compile graph button
  const compileGraphBtn = document.getElementById('compileGraphBtn');
  if (compileGraphBtn) {
    compileGraphBtn.addEventListener('click', async () => {
      const mermaidInput = document.getElementById('mermaidInput');
      if (!mermaidInput) return;

      const mermaid = mermaidInput.value;
      if (!mermaid.trim()) {
        console.warn('No Mermaid graph provided');
        return;
      }

      try {
        // Disable button during compilation
        compileGraphBtn.disabled = true;
        compileGraphBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Compiling...';

        // Call compile endpoint (or validate)
        const response = await fetch('/api/debug/patch-loop/compile', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ mermaid })
        });

        if (response.ok) {
          const result = await response.json();
          console.log('Graph compiled successfully:', result);

          // Store compiled graph for preflight validation
          window.patchLoopCompiledGraph = true;

          // Enable execute button
          const executeLoopBtn = document.getElementById('executeLoopBtn');
          if (executeLoopBtn) {
            executeLoopBtn.disabled = false;
          }

          compileGraphBtn.innerHTML = '<i class="bi bi-check-circle me-2"></i>Compiled';
          setTimeout(() => {
            compileGraphBtn.innerHTML = '<i class="bi bi-code-slash me-2"></i>Compile Graph';
            compileGraphBtn.disabled = false;
          }, 2000);
        } else {
          let errorPayload;
          try {
            const contentType = response.headers.get('content-type') || '';
            if (contentType.includes('application/json')) {
              const asJson = await response.json();
              errorPayload = typeof asJson === 'string' ? asJson : JSON.stringify(asJson);
            } else {
              errorPayload = await response.text();
            }
          } catch (parseError) {
            errorPayload = `Graph compilation failed (HTTP ${response.status})`;
          }
          console.error('Compilation failed:', errorPayload);
          showNotification(
            typeof errorPayload === 'string' && errorPayload
              ? errorPayload
              : `Graph compilation failed (HTTP ${response.status})`,
            'error'
          );
          compileGraphBtn.innerHTML = '<i class="bi bi-exclamation-triangle me-2"></i>Failed';
          setTimeout(() => {
            compileGraphBtn.innerHTML = '<i class="bi bi-code-slash me-2"></i>Compile Graph';
            compileGraphBtn.disabled = false;
          }, 2000);
        }
      } catch (error) {
        console.error('Failed to compile graph:', error);
        compileGraphBtn.innerHTML = '<i class="bi bi-exclamation-triangle me-2"></i>Error';
        setTimeout(() => {
          compileGraphBtn.innerHTML = '<i class="bi bi-code-slash me-2"></i>Compile Graph';
          compileGraphBtn.disabled = false;
        }, 2000);
      }
    });
  }

  // Execute button
  const executeLoopBtn = document.getElementById('executeLoopBtn');
  if (executeLoopBtn) {
    executeLoopBtn.addEventListener('click', async () => {
      const mermaidInput = document.getElementById('mermaidInput');
      const scriptSelect = document.getElementById('patchScriptSelect');
      const goalPromptInput = document.getElementById('goalPromptInput');
      const executionModeSelect = document.getElementById('executionModeSelect');

      if (!mermaidInput) return;

      const mermaid = mermaidInput.value;
      const scriptName = scriptSelect ? scriptSelect.value : null;
      const goalPrompt = goalPromptInput ? goalPromptInput.value : '';
      const executionMode = executionModeSelect ? executionModeSelect.value : 'analysis_only';

      if (!mermaid.trim()) {
        console.warn('No Mermaid graph provided');
        return;
      }

      try {
        // Disable button during execution
        executeLoopBtn.disabled = true;
        executeLoopBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Compiling...';

        // Step 1: Compile Mermaid to inspect node types
        const compileResponse = await fetch('/api/debug/patch-loop/compile', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ mermaid })
        });
        if (!compileResponse.ok) {
          let compileError;
          try {
            const contentType = compileResponse.headers.get('content-type') || '';
            if (contentType.includes('application/json')) {
              const asJson = await compileResponse.json();
              compileError = typeof asJson === 'string' ? asJson : JSON.stringify(asJson);
            } else {
              compileError = await compileResponse.text();
            }
          } catch (e) {
            compileError = `Graph compilation failed (HTTP ${compileResponse.status})`;
          }
          alert(compileError || `Graph compilation failed (HTTP ${compileResponse.status})`);
          executeLoopBtn.innerHTML = '<i class="bi bi-play-fill me-2"></i>Execute';
          executeLoopBtn.disabled = false;
          return;
        }
        window.patchLoopCompiledGraph = true;
        const compileResult = await compileResponse.json();

        // Step 2: Derive requirements from compiled graph node types
        const requirements = deriveGraphRequirements(compileResult);

        // Step 3: Conditional preflight validation
        const preflight = await validatePatchLoopPreflight(requirements);
        if (!preflight.valid) {
          alert(preflight.message);
          executeLoopBtn.innerHTML = '<i class="bi bi-play-fill me-2"></i>Execute';
          executeLoopBtn.disabled = false;
          return;
        }

        // Step 4: Refresh provider status and execute
        executeLoopBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2"></span>Executing...';
        await updateProviderStatus();

        // Execute the exact Mermaid graph currently in the editor.
        const result = await window.patchLoopManager.executeGraph(
          mermaid,
          scriptName,
          goalPrompt,
          { executionMode }
        );

        console.log('Graph execution started:', result);

        // Enable control buttons
        const pauseLoopBtn = document.getElementById('pauseLoopBtn');
        const cancelLoopBtn = document.getElementById('cancelLoopBtn');
        if (pauseLoopBtn) pauseLoopBtn.disabled = false;
        if (cancelLoopBtn) cancelLoopBtn.disabled = false;

        // Reconnect WebSocket with executionId
        if (window.patchLoopManager && result.executionId) {
          window.patchLoopManager.disconnect();
          window.patchLoopManager.connect(result.executionId);
        }

        executeLoopBtn.innerHTML = '<i class="bi bi-play-fill me-2"></i>Execute';
      } catch (error) {
        console.error('Failed to execute graph:', error);
        executeLoopBtn.innerHTML = '<i class="bi bi-exclamation-triangle me-2"></i>Failed';
        setTimeout(() => {
          executeLoopBtn.innerHTML = '<i class="bi bi-play-fill me-2"></i>Execute';
          executeLoopBtn.disabled = false;
        }, 2000);
      }
    });
  }

  // Pause button
  const pauseLoopBtn = document.getElementById('pauseLoopBtn');
  if (pauseLoopBtn) {
    pauseLoopBtn.addEventListener('click', async () => {
      if (!window.patchLoopManager || !window.patchLoopManager.iterationId) {
        console.warn('No active execution to pause');
        return;
      }

      try {
        await window.patchLoopManager.controlExecution(window.patchLoopManager.iterationId, 'PAUSE');

        // Update button states
        pauseLoopBtn.disabled = true;
        const resumeLoopBtn = document.getElementById('resumeLoopBtn');
        if (resumeLoopBtn) resumeLoopBtn.disabled = false;
      } catch (error) {
        console.error('Failed to pause execution:', error);
      }
    });
  }

  // Resume button
  const resumeLoopBtn = document.getElementById('resumeLoopBtn');
  if (resumeLoopBtn) {
    resumeLoopBtn.addEventListener('click', async () => {
      if (!window.patchLoopManager || !window.patchLoopManager.iterationId) {
        console.warn('No active execution to resume');
        return;
      }

      try {
        await window.patchLoopManager.controlExecution(window.patchLoopManager.iterationId, 'RESUME');

        // Update button states
        resumeLoopBtn.disabled = true;
        const pauseLoopBtn = document.getElementById('pauseLoopBtn');
        if (pauseLoopBtn) pauseLoopBtn.disabled = false;
      } catch (error) {
        console.error('Failed to resume execution:', error);
      }
    });
  }

  // Cancel button
  const cancelLoopBtn = document.getElementById('cancelLoopBtn');
  if (cancelLoopBtn) {
    cancelLoopBtn.addEventListener('click', async () => {
      if (!window.patchLoopManager || !window.patchLoopManager.iterationId) {
        console.warn('No active execution to cancel');
        return;
      }

      if (!confirm('Are you sure you want to cancel the patch loop execution?')) {
        return;
      }

      try {
        await window.patchLoopManager.controlExecution(window.patchLoopManager.iterationId, 'CANCEL', 'User cancelled');

        // Disable all control buttons
        const pauseLoopBtn = document.getElementById('pauseLoopBtn');
        const resumeLoopBtn = document.getElementById('resumeLoopBtn');
        if (pauseLoopBtn) pauseLoopBtn.disabled = true;
        if (resumeLoopBtn) resumeLoopBtn.disabled = true;
        cancelLoopBtn.disabled = true;

        // Enable execute button
        const executeLoopBtn = document.getElementById('executeLoopBtn');
        if (executeLoopBtn) executeLoopBtn.disabled = false;
      } catch (error) {
        console.error('Failed to cancel execution:', error);
      }
    });
  }
}

// ==================== INIT ====================

document.addEventListener('DOMContentLoaded', () => {
  cacheElements();
  switchMode('live'); // Initialize mode BEFORE WebSocket connections

  elements.liveBtn.addEventListener('click', () => switchMode('live'));
  elements.replayBtn.addEventListener('click', () => switchMode('replay'));

  connectLogWebSocket();
  connectViewportWebSocket();
  connectOverlayWebSocket();
  connectBotStateWebSocket();
  connectTickStreamWebSocket(); // New tick stream WebSocket

  // Initialize patch loop manager
  if (typeof PatchLoopManager !== 'undefined') {
    window.patchLoopManager = new PatchLoopManager();
    window.patchLoopManager.connect();

    // Initialize Patch Loop UI controls
    initPatchLoopControls();
  }

  loadCurrentFps(); // Load current FPS setting

  initReplayModeListeners();
  initTickControls();
  initLogFilters();
  initLogTypeToggles(); // Log type toggles (tick, external, verbose) - MUST be first
  initAutoFollowToggle();
  initSourceFilters(); // Source filter chips
  initPatternFilterDropdown(); // Pattern filter chips
  initSectionToggles(); // Section toggles for tick cards
  initTickReplayControls(); // New tick-based replay controls
  initRecordingControls(); // Recording controls
  initDebugSessionControls(); // Debug session controls (live mode only)
  initDebugFeedControls(); // Debug feed toggle (pause/resume micro6)
  initTaskRecordingControls(); // Task recording controls

  // Viewport pause/resume button
  document.getElementById("pauseCaptureBtn")?.addEventListener("click", togglePauseCapture);
  updatePauseButtonState();

  startTickPolling();

  fetchAndRenderWindows();
  setInterval(fetchAndRenderWindows, 5000);

  if (elements.replayVideo) {
    elements.replayVideo.addEventListener('loadedmetadata', () => resizeOverlayCanvas(getViewportMetrics() || { width: 0, height: 0 }));
  }

  window.addEventListener('resize', () => {
    const metrics = getViewportMetrics();
    if (metrics) resizeOverlayCanvas(metrics);
  });

  aiInspectorPoller = setInterval(fetchAiInspector, 5000);
  fetchAiInspector();

  // Start recording diagnostics polling
  setInterval(() => {
    if (currentMode === 'live') {
      updateRecordingDiagnostics();
    }
  }, 2000);
  updateRecordingDiagnostics();

  // Start feed status polling
  setInterval(updateFeedStatus, 5000);
  updateFeedStatus();

  // Deep-link: auto-open a recording via ?recordingId=, ?recording= (legacy), ?sessionId=, or ?mode=replay
  (async () => {
    const params = new URLSearchParams(window.location.search);
    const modeParam = params.get('mode');
    const recordingIdParam = params.get('recordingId') || params.get('recording'); // legacy fallback
    const sessionIdParam = params.get('sessionId');

    if (!recordingIdParam && !sessionIdParam && modeParam !== 'replay') return;

    switchMode('replay');
    await loadRecordingSessions();

    let targetRecordingId = recordingIdParam;

    // Resolve sessionId → recordingId if needed
    if (!targetRecordingId && sessionIdParam) {
      try {
        const res = await fetch(`/api/debug/replay/recording-by-session?sessionId=${encodeURIComponent(sessionIdParam)}`);
        if (res.ok) {
          const data = await res.json();
          targetRecordingId = data.recordingId;
        } else {
          console.warn('[deep-link] Could not resolve sessionId to recordingId');
        }
      } catch (e) {
        console.warn('[deep-link] recording-by-session request failed:', e);
      }
    }

    if (targetRecordingId) {
      if (elements.sessionSelect) elements.sessionSelect.value = targetRecordingId;
      const health = sessionHealthMap[targetRecordingId];
      if (health) renderSessionHealthPreview(health);
      renderReplayMetadataPreview(targetRecordingId);
      if (elements.editReplayMetadataBtn) elements.editReplayMetadataBtn.style.display = '';
      await loadRecording(targetRecordingId);
    } else if (modeParam === 'replay') {
      // mode=replay only: stay in replay mode with selector usable, no auto-load
      console.info('[deep-link] mode=replay, no recording resolved; leaving selector open');
    }
  })();
});

/**
 * Poll recording metrics and update diagnostics strip
 */
async function updateRecordingDiagnostics() {
  const PINNED_KEY = 'debuggerDiagnosticsPinnedFull';

  // Initialise expand/pin state once on first call
  if (!updateRecordingDiagnostics._initialised) {
    updateRecordingDiagnostics._initialised = true;
    const pinToggle = document.getElementById('diagnosticsPinToggle');
    const pinned = localStorage.getItem(PINNED_KEY) === 'true';
    if (pinToggle) pinToggle.checked = pinned;
    if (pinned) _rdsSetExpanded(true);

    // Expand button handler
    document.getElementById('diagnosticsExpandBtn')?.addEventListener('click', () => {
      const expanded = document.getElementById('diagnosticsFullDetails').style.display !== 'none';
      _rdsSetExpanded(!expanded);
    });

    // Pin toggle handler
    pinToggle?.addEventListener('change', () => {
      localStorage.setItem(PINNED_KEY, pinToggle.checked);
      if (pinToggle.checked) _rdsSetExpanded(true);
    });
  }

  try {
    const response = await fetch('/api/recordings/metrics');
    const metrics = await response.json();

    if (metrics.isRecording) {
      document.getElementById('bufferDepth').textContent = metrics.frameBufferDepth;
      document.getElementById('bufferCapacity').textContent = metrics.frameBufferSize;
      document.getElementById('droppedFrames').textContent = metrics.droppedFrames;

      const durationSec = Math.floor(metrics.recordingDurationMs / 1000);
      const mm = Math.floor(durationSec / 60);
      const ss = durationSec % 60;
      document.getElementById('diagnosticsRecordingDuration').textContent =
        `${String(mm).padStart(2, '0')}:${String(ss).padStart(2, '0')}`;

      const fillPercent = Math.round(
        (metrics.frameBufferDepth / metrics.frameBufferSize) * 100
      );
      const progressBar = document.getElementById('bufferProgressBar');
      progressBar.style.width = fillPercent + '%';
      progressBar.textContent = fillPercent + '%';
      progressBar.className =
        fillPercent < 50 ? 'progress-bar bg-success' :
        fillPercent < 80 ? 'progress-bar bg-warning' :
                           'progress-bar bg-danger';
    } else {
      // Idle placeholders
      document.getElementById('bufferDepth').textContent = '0';
      document.getElementById('bufferCapacity').textContent = '-';
      document.getElementById('droppedFrames').textContent = '0';
      document.getElementById('diagnosticsRecordingDuration').textContent = '00:00';
      const progressBar = document.getElementById('bufferProgressBar');
      progressBar.style.width = '0%';
      progressBar.textContent = '0%';
      progressBar.className = 'progress-bar bg-success';
    }
  } catch (error) {
    console.error('Error fetching recording metrics:', error);
  }
}

function _rdsSetExpanded(expand) {
  const details = document.getElementById('diagnosticsFullDetails');
  const chevron = document.getElementById('diagnosticsChevron');
  if (!details) return;
  details.style.display = expand ? 'block' : 'none';
  chevron?.classList.toggle('bi-chevron-down', !expand);
  chevron?.classList.toggle('bi-chevron-up', expand);
}

/**
 * Update feed status indicator
 */
async function updateFeedStatus() {
  try {
    const response = await fetch('/api/debug/feed-status');
    const status = await response.json();

    const indicator = document.getElementById('feedStatusIndicator');
    if (!indicator) return;

    if (status.connected && status.lastTickReceived) {
      indicator.className = 'badge bg-success me-2';
      indicator.innerHTML = `<i class="bi bi-circle-fill"></i> Feed: Active (Tick ${status.lastTickReceived})`;
    } else {
      indicator.className = 'badge bg-warning me-2';
      indicator.innerHTML = `<i class="bi bi-circle-fill"></i> Feed: Waiting`;
    }
  } catch (error) {
    const indicator = document.getElementById('feedStatusIndicator');
    if (indicator) {
      indicator.className = 'badge bg-danger me-2';
      indicator.innerHTML = `<i class="bi bi-circle-fill"></i> Feed: Error`;
    }
  }
}

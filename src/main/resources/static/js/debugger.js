/**
 * ChromaScape Debugger Page
 * Unified layout with live viewport, logs, scrubber, bot state, and AI inspector.
 */

const replayRecoveryCore = globalThis.ChromaScapeReplayCore;

let currentMode = 'live';
let currentRecording = null;
let directSessionJumpGeneration = 0;
let sessionHealthMap = {};
let sessionMetadataMap = {};
let sessionPlayabilityMap = {};
let currentRecordingSessionId = null;
let currentRecordingFps = 30; // Default FPS, updated when loading recording
let currentLogLevel = 'all';
let logWebSocket = null;
let overlayWebSocket = null;
let botStateWebSocket = null;
let viewportWebSocket = null;
let tickPoller = null;
let aiInspectorPoller = null;
let recordingStatusPoller = null;
let providerStatusPoller = null;
let windowSelectionPoller = null;
let recordingDiagnosticsPoller = null;
let feedStatusPoller = null;
let debuggerPageClosing = false;
let logWebSocketReconnectTimer = null;

// Viewport frame synchronization state
let lastDrawnSeq = 0;
let latestSeq = 0;
let pendingBitmap = null;
let pendingSeq = 0;
let pendingSourceWidth = 0;
let pendingSourceHeight = 0;
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

// Session/recording synchronization guards. These prevent duplicate SESSION_CREATED broadcasts
// and overlapping status generations from resetting the live panel during transport recovery.
let lastProcessedSessionCreatedKey = null;
let ignoredSessionCreatedCount = 0;
let lastIgnoredSessionSummaryAt = 0;
let recordingStatusRequest = null;
let recordingStatusGeneration = 0;
let recordingStatusAbortController = null;
let recordingNotificationId = null;

const elements = {};
const MAX_LOG_ENTRIES = 3000;
const liveLogHistory = [];
const replayLogHistory = [];

const DEBUGGER_WORKSPACES = Object.freeze({
  runs: {
    eyebrow: 'Operator workspace',
    title: 'Runs',
    description: 'Launch a Micro6 task, follow its progress, and open evidence or recovery from one place.'
  },
  live: {
    eyebrow: 'Observe',
    title: 'Live session',
    description: 'Watch the selected client, current tick, structured state, and incoming logs.'
  },
  replays: {
    eyebrow: 'Investigate',
    title: 'Replay evidence',
    description: 'Select a recording and inspect synchronized video, ticks, state, overlays, and logs.'
  },
  recovery: {
    eyebrow: 'Diagnose and repair',
    title: 'Recovery',
    description: 'Annotate run evidence, generate a patch plan, and approve a controlled relaunch.'
  },
  'ai-training': {
    eyebrow: 'Evidence and operations',
    title: 'AI Training',
    description: 'Trace recordings through datasets, models, held-out replay, and live shadow without copying identifiers.'
  },
  developer: {
    eyebrow: 'Advanced',
    title: 'Developer Tools',
    description: 'Experimental replay kernels, patch-loop internals, recording labs, and raw AI tooling.'
  }
});

let debuggerWorkspaceInitialized = false;
let debuggerWorkspaceModeSync = false;
let currentDebuggerWorkspace = 'runs';

function activateDebuggerWorkspace(workspace, options = {}) {
  const definition = DEBUGGER_WORKSPACES[workspace];
  if (!definition) return;

  // Runs is a canonical, replay-free location. Replacing (rather than pushing) removes a prior
  // ?recordingId deep link from the current history entry, so refreshing cannot reopen it.
  if (workspace === 'runs' && options.syncUrl !== false) {
    const runsPath = '/debugger/runs';
    if (window.location.pathname !== runsPath || window.location.search || window.location.hash) {
      history.replaceState({ workspace: 'runs' }, '', runsPath);
    }
  }

  currentDebuggerWorkspace = workspace;
  document.body.dataset.workspace = workspace;

  document.querySelectorAll('[data-workspace]').forEach(button => {
    if (!button.classList.contains('workspace-tab')) return;
    const active = button.dataset.workspace === workspace;
    button.classList.toggle('active', active);
    button.setAttribute('aria-selected', String(active));
    button.tabIndex = active ? 0 : -1;
  });

  const eyebrow = document.getElementById('workspaceEyebrow');
  const title = document.getElementById('workspaceTitle');
  const description = document.getElementById('workspaceDescription');
  if (eyebrow) eyebrow.textContent = definition.eyebrow;
  if (title) title.textContent = definition.title;
  if (description) description.textContent = definition.description;

  const recoveryEmpty = document.getElementById('recoveryEmptyState');
  if (recoveryEmpty) {
    recoveryEmpty.hidden = workspace !== 'recovery'
      || document.body.classList.contains('recovery-mode-active');
  }

  if (options.syncMode !== false && (workspace === 'live' || workspace === 'replays')) {
    debuggerWorkspaceModeSync = true;
    try {
      switchMode(workspace === 'replays' ? 'replay' : 'live');
    } finally {
      debuggerWorkspaceModeSync = false;
    }
  }

  document.dispatchEvent(new CustomEvent('debugger:workspace-changed', {
    detail: { workspace }
  }));
}

function syncWorkspaceConnectionStatus() {
  const source = document.getElementById('feedStatusIndicator');
  const dot = document.getElementById('workspaceConnectionDot');
  const text = document.getElementById('workspaceConnectionText');
  if (!source || !dot || !text) return;

  const connected = /connected/i.test(source.textContent || '')
    && !/disconnected/i.test(source.textContent || '');
  dot.classList.toggle('connected', connected);
  dot.classList.toggle('warning', !connected && /connecting/i.test(source.textContent || ''));
  text.textContent = connected ? 'Telemetry connected' : 'Telemetry disconnected';
}

function initializeDebuggerWorkspaces() {
  document.querySelectorAll('.workspace-tab').forEach(button => {
    button.addEventListener('click', () => activateDebuggerWorkspace(button.dataset.workspace));
    button.addEventListener('keydown', event => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      const tabs = Array.from(document.querySelectorAll('.workspace-tab'));
      const currentIndex = tabs.indexOf(button);
      const direction = event.key === 'ArrowRight' ? 1 : -1;
      const next = tabs[(currentIndex + direction + tabs.length) % tabs.length];
      next.focus();
      activateDebuggerWorkspace(next.dataset.workspace);
    });
  });
  document.querySelectorAll('[data-workspace-link]').forEach(button => {
    button.addEventListener('click', () => activateDebuggerWorkspace(button.dataset.workspaceLink));
  });

  const feedStatus = document.getElementById('feedStatusIndicator');
  if (feedStatus) {
    new MutationObserver(syncWorkspaceConnectionStatus)
      .observe(feedStatus, { childList: true, subtree: true, characterData: true, attributes: true });
  }
  syncWorkspaceConnectionStatus();
  debuggerWorkspaceInitialized = true;
  // Do not canonicalize during bootstrap: a recording deep link still needs its query parameters
  // later in initialization. URL ownership begins when the operator selects a workspace.
  activateDebuggerWorkspace('runs', { syncMode: false, syncUrl: false });
}

window.DebuggerWorkspace = {
  activate: activateDebuggerWorkspace,
  current: () => currentDebuggerWorkspace
};

// Replay recording-load lifecycle: generation guards against stale metadata/session
// responses repopulating state after the user switches recordings or leaves Replay mode.
let replayLoadGeneration = 0;
let replayLoadAbortController = null;
let replayTickWindowRequestSequence = 0;

// Quest Helper filter state (persisted across tick card rerenders)
const questHelperFilterState = {
  search: '',
  status: 'all',
  requirement: 'all',
  missingOnly: false,
  raw: false
};

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

    // Apply Quest Helper structured payload changes (replace wholesale)
    if (changes.questHelper) {
      state.questHelper = changes.questHelper;
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

/**
 * Whether a recording-load response (captured generation + recordingId) is still current.
 * Before a recording is committed currentRecording is intentionally null, so callers
 * validate the generation directly in that window.
 */
function isCurrentReplayLoad(generation, recordingId) {
  return replayRecoveryCore.acceptsReplayResponse(
    currentMode,
    generation,
    replayLoadGeneration,
    currentRecording,
    recordingId
  );
}

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
const replaySnapshotFetchInFlight = new Map();

function abortReplaySnapshotRequests() {
  replaySnapshotFetchInFlight.forEach(request => request.controller.abort());
  replaySnapshotFetchInFlight.clear();
}
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
let replayTimelineScale = 1; // Wall-clock recording duration / encoded media duration

// Windowed tick-card loading state
const replayTickCardCache = new Map(); // tick -> tick card data
const replayLoadedRanges = []; // Array of {start, end} windows already fetched
const replayTickWindowInFlight = []; // Array of {start, end} windows currently being fetched
let replayTickCardsUnavailable = false; // Stop repeated 404s for a video-only/orphaned replay

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
  packets: true,
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
  elements.replayVideoWarning = document.getElementById('replayVideoWarning');
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
  elements.fishingShadowContent = document.getElementById('fishingShadowContent');
  elements.fishingRecordingLab = document.getElementById('fishingRecordingLab');
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
  // Recovery workspace containers
  elements.recoveryRawLogsContainer = document.getElementById('recoveryRawLogsContainer');
  elements.recoveryRawLogsMeta = document.getElementById('recoveryRawLogsMeta');
}

function switchMode(mode) {
  if (debuggerWorkspaceInitialized
      && !debuggerWorkspaceModeSync
      && !document.body.classList.contains('recovery-mode-active')) {
    activateDebuggerWorkspace(mode === 'replay' ? 'replays' : 'live', { syncMode: false });
  }
  if (currentMode === mode) return; // Don't clear logs if already in this mode

  // Invalidate any in-flight recording load / timeline sync before switching.
  replayLoadGeneration++;
  if (replayLoadAbortController) {
    replayLoadAbortController.abort();
    replayLoadAbortController = null;
  }
  if (currentSyncAbortController) {
    currentSyncAbortController.abort();
    currentSyncAbortController = null;
  }

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
    abortReplaySnapshotRequests();
    // Clear replay snapshots when entering replay mode
    replayTickSnapshots.clear();
    replaySnapshotTimeline.length = 0;
    replaySessionStartTick = 0;
    currentRecording = null;
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
    setReplayVideoWarning(null);
    currentRecordingFps = 30; // Reset to default when leaving replay
    currentReplayDebugSessionId = null;
    replaySnapshotOrder.length = 0;
    abortReplaySnapshotRequests();
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

function captureLogScrollAnchor(container) {
  if (!container || currentMode !== 'live') return null;
  const groups = Array.from(container.querySelectorAll('.tick-group'));
  if (groups.length === 0) return null;

  const containerRect = container.getBoundingClientRect();
  const anchor = groups.find(group => group.getBoundingClientRect().bottom > containerRect.top)
    || groups[0];
  const anchorRect = anchor.getBoundingClientRect();

  return {
    element: anchor,
    offsetTop: anchorRect.top - containerRect.top,
    scrollTop: container.scrollTop
  };
}

function restoreLogScrollAnchor(container, anchor) {
  if (!container || !anchor || currentMode !== 'live') return;

  const now = Date.now();
  lastProgrammaticScrollTime = now;
  programmaticScrollUntil = now + 300;

  if (anchor.element && anchor.element.isConnected) {
    const containerRect = container.getBoundingClientRect();
    const anchorRect = anchor.element.getBoundingClientRect();
    container.scrollTop += anchorRect.top - containerRect.top - anchor.offsetTop;
    return;
  }

  container.scrollTop = anchor.scrollTop;
}

function withLiveLogScrollAnchor(callback) {
  const container = elements.logsContainer;
  const anchor = captureLogScrollAnchor(container);
  const result = callback();
  restoreLogScrollAnchor(container, anchor);
  return result;
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
      QUEST_HELPER_STEP_CHANGED: 'quest',
      QUEST_HELPER_BLOCKED: 'quest',
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
    const tickLabel = tick === null ? 'Unticked logs' : `Tick ${tick}`;
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

  const tickLabel = tick === null ? 'Unticked logs' : `Tick ${tick}`;

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
    const card = replayTickCardCache.get(Number(tick));
    const timelineTimeMs = card && Number.isFinite(Number(card.videoTimeMs))
      ? Number(card.videoTimeMs)
      : Math.max(0, (Number(tick) - replaySessionStartTick) * 600);
    const videoTime = timelineTimeMs / Math.max(0.0001, replayTimelineScale) / 1000;
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
    'groundItems',
    'packets'
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
      groundItems: 'GI',
      packets: 'Pk'
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
  header.insertBefore(buildPacketCountBadge(snapshot), headerChips);

  // Build structured sections
  if (isSectionVisible(tick, 'actions') && entries.length > 0) {
    content.appendChild(buildActionsSection(tick, entries));
  }

  if (isSectionVisible(tick, 'quest') && (snapshot?.quest || snapshot?.questHelper)) {
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

  if (isSectionVisible(tick, 'packets') && snapshot) {
    content.appendChild(buildPacketsSection(tick, snapshot));
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

  const tickLabel = tick === null ? 'Unticked logs' : `Tick ${tick}`;

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
    'groundItems',
    'packets'
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
      groundItems: 'GI',
      packets: 'Pk'
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

  if (isSectionVisible(tick, 'quest') && (snapshot?.quest || snapshot?.questHelper)) {
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

  if (isSectionVisible(tick, 'packets') && snapshot) {
    content.appendChild(buildPacketsSection(tick, snapshot));
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
 * Re-renders only the packet list rows for a tick, preserving the filter inputs
 * (and keyboard focus) while typing. Falls back to a full tick re-render when
 * the section shape cannot be found.
 */
function rerenderPacketListOnly(tick, focusedInput, caretPos) {
  const outputContainer = typeof elements !== 'undefined' ? elements.logsContainer : null;
  if (!outputContainer) {
    rerenderTickGroup(tick);
    return;
  }
  const tickSelector = tick === null ? '[data-tick="null"]' : `[data-tick="${tick}"]`;
  const existingGroup = outputContainer.querySelector(`.tick-group${tickSelector}`);
  const packetSection = existingGroup ? existingGroup.querySelector('.section-packets') : null;
  const list = packetSection ? packetSection.querySelector('.packet-list') : null;
  if (!packetSection || !list) {
    rerenderTickGroup(tick);
    return;
  }
  const store = currentMode === 'live' ? liveTickSnapshots : replayTickSnapshots;
  const snapshot = store.get(tick) || {};
  const packets = Array.isArray(snapshot.packets) ? snapshot.packets : [];
  const seededPacketCount = Number(snapshot.packetCount ?? 0) || 0;
  const packetTotal = Math.max(packets.length, seededPacketCount);
  const visible = packets.filter(packetMatchesFilters);
  const shown = visible.slice(0, PACKET_ROW_CAP);
  list.replaceChildren();
  if (packets.length === 0) {
    const empty = document.createElement('span');
    empty.className = 'section-empty';
    const sourceStatus = snapshot.transport?.packetSourceStatus;
    empty.textContent = sourceStatus === 'UNAVAILABLE'
      ? 'Packet source unavailable (client not launched through RSProx)'
      : (seededPacketCount > 0
        ? `Loading ${seededPacketCount} packet${seededPacketCount === 1 ? '' : 's'}…`
        : 'No packets for this tick');
    list.appendChild(empty);
  } else {
    shown.forEach(packet => list.appendChild(buildPacketRow(packet)));
    if (visible.length > shown.length) {
      const more = document.createElement('div');
      more.className = 'section-empty';
      more.textContent = `Showing ${shown.length} of ${visible.length} matching packets (${packetTotal} total)`;
      list.appendChild(more);
    } else if (visible.length < packets.length) {
      const note = document.createElement('div');
      note.className = 'section-empty';
      note.textContent = `Showing ${visible.length} of ${packetTotal} packets (filtered)`;
      list.appendChild(note);
    } else if (packetTotal > packets.length) {
      const pending = document.createElement('div');
      pending.className = 'section-empty';
      pending.textContent = `Showing ${packets.length} of ${packetTotal} packets (loading…)`;
      list.appendChild(pending);
    }
  }
  if (focusedInput && focusedInput.isConnected) {
    focusedInput.focus({ preventScroll: true });
    try {
      // Restore the caret where the user was editing instead of forcing it to
      // the end: typing in the middle of the filter must not jump the cursor.
      const pos = Number.isInteger(caretPos)
        ? Math.max(0, Math.min(caretPos, focusedInput.value.length))
        : focusedInput.value.length;
      focusedInput.setSelectionRange(pos, pos);
    } catch (e) {
      // Non-text inputs: focus alone is sufficient.
    }
  }
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
  const questHelper = snapshot?.questHelper || snapshot?.quest_helper;
  if (questHelper && typeof questHelper === 'object' && !Array.isArray(questHelper)) {
    return buildQuestHelperSection(tick, snapshot, questHelper);
  }
  return buildSimpleQuestSection(tick, snapshot);
}

function buildSimpleQuestSection(tick, snapshot) {
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
 * Build the rich Quest Helper section from the full questHelper payload.
 * Renders current step, missing requirements/blockers, dialogue, filterable step list, and raw JSON.
 */
function buildQuestHelperSection(tick, snapshot, questHelper) {
  const section = document.createElement('div');
  section.className = 'tick-section section-quest quest-helper-card';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const header = document.createElement('div');
  header.className = 'tick-section-header';
  header.innerHTML = '<i class="bi bi-book"></i> Quest ';
  header.appendChild(document.createTextNode(`(${tickLabel})`));

  const content = document.createElement('div');
  content.className = 'tick-section-content';

  const activeQuest = questHelper.activeQuest || {};
  const currentStep = questHelper.currentStep || null;
  const steps = Array.isArray(questHelper.steps) ? questHelper.steps : [];
  const requirements = questHelper.requirements || null;
  const dialogue = questHelper.dialogue || null;
  const debug = questHelper.debug || {};
  const collectionMode = questHelper.collectionMode || debug.collectionMode || null;

  const nameRow = document.createElement('div');
  nameRow.className = 'quest-helper-name-row';
  const nameSpan = document.createElement('strong');
  nameSpan.textContent = activeQuest.questName || snapshot?.quest?.name || 'Unknown Quest';
  nameRow.appendChild(nameSpan);

  const questState = activeQuest.questState || snapshot?.quest?.questHelperState || null;
  if (questState) {
    const badge = document.createElement('span');
    badge.className = 'quest-state-badge ' + getQuestStateBadgeClass(questState);
    badge.textContent = questState.replace(/_/g, ' ');
    nameRow.appendChild(badge);
  }
  if (collectionMode) {
    const modeBadge = document.createElement('span');
    modeBadge.className = 'quest-helper-source-badge quest-helper-source-' + collectionMode;
    modeBadge.textContent = 'Source: ' + collectionMode.replace(/_/g, ' ');
    nameRow.appendChild(modeBadge);
  }
  content.appendChild(nameRow);

  renderQuestHelperCurrentStep(content, currentStep);
  renderQuestHelperRequirements(content, requirements);
  if (dialogue && (dialogue.active || dialogue.text)) {
    renderQuestHelperDialogue(content, dialogue);
  }

  if (steps.length > 0) {
    const stepsContainer = document.createElement('div');
    stepsContainer.className = 'quest-helper-steps-container';

    const listHost = document.createElement('div');
    listHost.className = 'quest-helper-step-list';
    const rawHost = document.createElement('div');
    rawHost.className = 'quest-helper-raw-json';

    function rerender() {
      listHost.innerHTML = '';
      rawHost.innerHTML = '';
      rawHost.style.display = questHelperFilterState.raw ? 'block' : 'none';
      if (questHelperFilterState.raw) {
        const pre = document.createElement('pre');
        pre.textContent = JSON.stringify(questHelper, null, 2);
        rawHost.appendChild(pre);
      }
      const filtered = filterQuestHelperSteps(steps, questHelperFilterState, questHelper);
      if (filtered.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'section-empty';
        empty.textContent = 'No steps match filters';
        listHost.appendChild(empty);
      } else {
        filtered.forEach(step => listHost.appendChild(renderQuestHelperStepRow(step)));
      }
    }

    stepsContainer.appendChild(buildQuestHelperFilters(questHelperFilterState, rerender));
    stepsContainer.appendChild(listHost);
    stepsContainer.appendChild(rawHost);
    rerender();
    content.appendChild(stepsContainer);
  }

  // Render debug error if present
  if (collectionMode === 'error' || questHelper.debug?.lastCollectionError) {
    const errLine = document.createElement('div');
    errLine.className = 'quest-helper-debug-error';
    errLine.textContent = 'Collection error: ' + (questHelper.debug?.lastCollectionError || collectionMode);
    content.appendChild(errLine);
  }

  // Render top-level targets if present
  const topTargets = Array.isArray(questHelper.targets) ? questHelper.targets : [];
  if (topTargets.length > 0) {
    renderQuestHelperTargets(content, topTargets);
  }

  section.appendChild(header);
  section.appendChild(content);
  return section;
}

function renderQuestHelperCurrentStep(content, currentStep) {
  const wrap = document.createElement('div');
  wrap.className = 'quest-helper-current-step';
  if (!currentStep || typeof currentStep !== 'object') {
    wrap.innerHTML = '<span class="section-empty">No active step</span>';
    content.appendChild(wrap);
    return;
  }
  const label = document.createElement('div');
  label.className = 'quest-helper-step-label';
  const status = currentStep.status || 'ACTIVE';
  const statusBadge = document.createElement('span');
  statusBadge.className = 'quest-helper-status-chip quest-helper-status-' + status.toLowerCase();
  statusBadge.textContent = status;
  label.appendChild(statusBadge);
  if (currentStep.index != null) {
    const idx = document.createElement('span');
    idx.className = 'quest-helper-step-index';
    idx.textContent = `Step ${currentStep.index}`;
    label.appendChild(idx);
  }
  if (currentStep.changedThisTick === true) {
    const changed = document.createElement('span');
    changed.className = 'quest-helper-changed-flag';
    changed.textContent = '(changed this tick)';
    label.appendChild(changed);
  }
  wrap.appendChild(label);

  const text = document.createElement('div');
  text.className = 'quest-helper-step-text';
  text.textContent = currentStep.text || '(no step text)';
  wrap.appendChild(text);
  content.appendChild(wrap);
}

function renderQuestHelperRequirements(content, requirements) {
  if (!requirements || typeof requirements !== 'object') return;
  const missing = Array.isArray(requirements.missing) ? requirements.missing : [];
  const met = Array.isArray(requirements.met) ? requirements.met : [];
  const blockedBy = Array.isArray(requirements.blockedBy) ? requirements.blockedBy : [];
  if (missing.length === 0 && met.length === 0 && blockedBy.length === 0) return;

  const wrap = document.createElement('div');
  wrap.className = 'quest-helper-requirements';
  const title = document.createElement('div');
  title.className = 'quest-helper-subheader';
  title.textContent = missing.length > 0 ? 'Blockers' : 'Requirements';
  wrap.appendChild(title);

  if (missing.length > 0) {
    const row = document.createElement('div');
    row.className = 'quest-helper-chip-row';
    missing.forEach(req => row.appendChild(buildQuestHelperRequirementChip(req, true)));
    wrap.appendChild(row);
  }
	blockedBy.forEach(text => {
		const div = document.createElement('div');
		div.className = 'quest-helper-blocker-line';
		div.textContent = '! ' + formatQuestHelperBlocker(text);
		wrap.appendChild(div);
	});
  if (met.length > 0) {
    const metTitle = document.createElement('div');
    metTitle.className = 'quest-helper-subheader quest-helper-subheader-minor';
    metTitle.textContent = `Met (${met.length})`;
    wrap.appendChild(metTitle);
    const row = document.createElement('div');
    row.className = 'quest-helper-chip-row';
    met.forEach(req => row.appendChild(buildQuestHelperRequirementChip(req, false)));
    wrap.appendChild(row);
  }
  content.appendChild(wrap);
}

function formatQuestHelperBlocker(blocker) {
	if (typeof blocker === 'string') return blocker;
	if (!blocker || typeof blocker !== 'object') return String(blocker ?? '');
	return blocker.text || blocker.name || JSON.stringify(blocker);
}

function buildQuestHelperRequirementChip(req, missing) {
	const chip = document.createElement('span');
	chip.className = 'quest-helper-requirement-chip ' + (missing ? 'missing' : 'met');
	const type = req && req.type ? req.type : 'UNKNOWN';
	const name = req && req.name ? req.name : 'requirement';
	let text = name;
	if (missing) {
		const required = req && req.quantityRequired != null ? req.quantityRequired : (req && req.quantity != null ? req.quantity : null);
		const owned = req && req.quantityOwned != null ? req.quantityOwned : null;
		if (owned != null && required != null) {
			text = `${name} ×${owned}/${required}`;
		} else if (required != null) {
			text = `${name} ×${required} required`;
		}
	}
	chip.textContent = `${type}: ${text}`;
	chip.title = req ? JSON.stringify(req) : '';
	return chip;
}

function renderQuestHelperDialogue(content, dialogue) {
  const wrap = document.createElement('div');
  wrap.className = 'quest-helper-dialogue';
  const title = document.createElement('div');
  title.className = 'quest-helper-subheader';
  title.textContent = 'Dialogue';
  wrap.appendChild(title);
  if (dialogue.npcName) {
    const npc = document.createElement('div');
    npc.className = 'quest-helper-dialogue-npc';
    npc.textContent = dialogue.npcName;
    wrap.appendChild(npc);
  }
  if (dialogue.text) {
    const t = document.createElement('div');
    t.className = 'quest-helper-dialogue-text';
    t.textContent = '"' + dialogue.text + '"';
    wrap.appendChild(t);
  }
  const opts = Array.isArray(dialogue.options) ? dialogue.options : [];
  if (opts.length > 0) {
    const list = document.createElement('div');
    list.className = 'quest-helper-dialogue-options';
    opts.forEach(opt => {
      const o = document.createElement('div');
      o.className = 'quest-helper-dialogue-option';
      if (dialogue.recommendedOption && opt === dialogue.recommendedOption) {
        o.classList.add('recommended');
        o.textContent = '★ ' + opt;
      } else {
        o.textContent = opt;
      }
      list.appendChild(o);
    });
    wrap.appendChild(list);
  }
  content.appendChild(wrap);
}

function buildQuestHelperFilters(filterState, onChange) {
  const bar = document.createElement('div');
  bar.className = 'quest-helper-filter-row';

  const search = document.createElement('input');
  search.type = 'text';
  search.placeholder = 'Search steps...';
  search.className = 'quest-helper-search-input';
  search.addEventListener('input', () => {
    filterState.search = search.value.toLowerCase();
    onChange();
  });
  bar.appendChild(search);

  const status = document.createElement('select');
  status.className = 'quest-helper-status-filter';
  ['all', 'active', 'available', 'done', 'locked', 'blocked', 'unknown'].forEach(s => {
    const o = document.createElement('option');
    o.value = s;
    o.textContent = s.charAt(0).toUpperCase() + s.slice(1);
    status.appendChild(o);
  });
  status.addEventListener('change', () => {
    filterState.status = status.value;
    onChange();
  });
  bar.appendChild(status);

  const req = document.createElement('select');
  req.className = 'quest-helper-requirement-filter';
  ['all', 'missing', 'met', 'item', 'skill', 'quest', 'varbit'].forEach(s => {
    const o = document.createElement('option');
    o.value = s;
    o.textContent = s.charAt(0).toUpperCase() + s.slice(1);
    req.appendChild(o);
  });
  req.addEventListener('change', () => {
    filterState.requirement = req.value;
    onChange();
  });
  bar.appendChild(req);

  const missingLabel = document.createElement('label');
  missingLabel.className = 'quest-helper-missing-only';
  const missingCb = document.createElement('input');
  missingCb.type = 'checkbox';
  missingCb.addEventListener('change', () => {
    filterState.missingOnly = missingCb.checked;
    onChange();
  });
  missingLabel.appendChild(missingCb);
  missingLabel.appendChild(document.createTextNode(' Missing only'));
  bar.appendChild(missingLabel);

  const rawLabel = document.createElement('label');
  rawLabel.className = 'quest-helper-raw-toggle';
  const rawCb = document.createElement('input');
  rawCb.type = 'checkbox';
  rawCb.addEventListener('change', () => {
    filterState.raw = rawCb.checked;
    onChange();
  });
  rawLabel.appendChild(rawCb);
  rawLabel.appendChild(document.createTextNode(' Raw'));
  bar.appendChild(rawLabel);

  return bar;
}

function isQuestHelperRequirementMissing(req) {
	if (!req) return false;
	if (req.met === false) return true;
	if (req.quantityOwned != null && req.quantityRequired != null) {
		return req.quantityOwned < req.quantityRequired;
	}
	if (req.quantityOwned != null && req.quantity != null) {
		return req.quantityOwned < req.quantity;
	}
	return false;
}

function filterQuestHelperSteps(steps, filterState, questHelper) {
	const topMissing = Array.isArray(questHelper?.requirements?.missing) ? questHelper.requirements.missing : [];
	const topBlocked = Array.isArray(questHelper?.requirements?.blockedBy) ? questHelper.requirements.blockedBy : [];
	return steps.filter(step => {
		if (!step) return false;
		if (filterState.status !== 'all') {
			const s = (step.status || 'UNKNOWN').toLowerCase();
			if (s !== filterState.status) return false;
		}
		const reqs = Array.isArray(step.requirements) ? step.requirements : [];
		if (filterState.missingOnly) {
			const hasStepMissing = reqs.some(r => isQuestHelperRequirementMissing(r));
			const currentIsActive = (step.status || '').toUpperCase() === 'ACTIVE';
			const topHasMissing = topMissing.length > 0 || topBlocked.length > 0;
			if (!hasStepMissing && !(currentIsActive && topHasMissing)) return false;
		}
		if (filterState.requirement !== 'all') {
			const allReqs = [...reqs, ...topMissing.map(r => ({ ...r, _source: 'top' }))];
			const match = allReqs.some(r => {
				if (!r) return false;
				if (filterState.requirement === 'missing') {
					return isQuestHelperRequirementMissing(r);
				}
				if (filterState.requirement === 'met') {
					if (r.quantityOwned != null && r.quantityRequired != null) return r.quantityOwned >= r.quantityRequired;
					return r.met === true;
				}
				return (r.type || '').toLowerCase() === filterState.requirement;
			});
			if (!match) return false;
		}
		if (filterState.search) {
      const targets = Array.isArray(step.targets) ? step.targets.map(t => t && (t.name || t.text)) : [];
      const topTargets = Array.isArray(questHelper?.targets)
        ? questHelper.targets.map(t => t && (t.name || t.text))
        : [];
      const haystack = [
        step.text, step.stepId,
        ...reqs.map(r => r && (r.name || r.text)),
        ...targets,
        step.dialogue && step.dialogue.text,
        questHelper?.activeQuest?.questName,
        questHelper?.dialogue?.text,
        questHelper?.dialogue?.npcName,
        ...topMissing.map(r => r && (r.name || r.text)),
        ...topTargets
      ].filter(Boolean).join(' ').toLowerCase();
      if (!haystack.includes(filterState.search)) return false;
    }
    return true;
  });
}

function renderQuestHelperStepRow(step) {
  const row = document.createElement('div');
  const status = (step.status || 'UNKNOWN').toUpperCase();
  row.className = 'quest-helper-step-row quest-helper-status-' + status.toLowerCase();
  const statusSpan = document.createElement('span');
  statusSpan.className = 'quest-helper-status-chip quest-helper-status-' + status.toLowerCase();
  statusSpan.textContent = status;
  row.appendChild(statusSpan);
  if (step.index != null) {
    const idx = document.createElement('span');
    idx.className = 'quest-helper-step-index';
    idx.textContent = String(step.index);
    row.appendChild(idx);
  }
  const text = document.createElement('span');
  text.className = 'quest-helper-step-text';
  text.textContent = step.text || step.stepId || '(no step text)';
  row.appendChild(text);

  // Per-step requirements
  const reqs = Array.isArray(step.requirements) ? step.requirements : [];
  if (reqs.length > 0) {
    const reqWrap = document.createElement('div');
    reqWrap.className = 'quest-helper-step-requirements';
    reqs.forEach(r => {
      if (!r) return;
      const chip = buildQuestHelperRequirementChip(
        r,
        (r.met === false || (r.quantityOwned != null && r.quantityRequired != null && r.quantityOwned < r.quantityRequired))
      );
      reqWrap.appendChild(chip);
    });
    row.appendChild(reqWrap);
  }

  // Per-step targets
  const targets = Array.isArray(step.targets) ? step.targets : [];
  if (targets.length > 0) {
    const targetWrap = document.createElement('div');
    targetWrap.className = 'quest-helper-target-list';
    targets.forEach(t => {
      const el = document.createElement('span');
      el.className = 'quest-helper-target';
      el.textContent = (t.name || t.text || t.type || '') + (t.location ? ' @ ' + t.location : '');
      targetWrap.appendChild(el);
    });
    row.appendChild(targetWrap);
  }

  // Per-step raw JSON toggle
  if (step.rawClassName || step.source) {
    const details = document.createElement('details');
    details.className = 'quest-helper-step-raw';
    const summary = document.createElement('summary');
    summary.textContent = 'Raw step data';
    details.appendChild(summary);
    const pre = document.createElement('pre');
    try { pre.textContent = JSON.stringify(step, null, 2); } catch (e) { pre.textContent = String(step); }
    details.appendChild(pre);
    row.appendChild(details);
  }

  return row;
}

/**
 * Render top-level targets for the Quest Helper section.
 */
function renderQuestHelperTargets(content, targets) {
	if (!Array.isArray(targets) || targets.length === 0) return;
	const wrap = document.createElement('div');
	wrap.className = 'quest-helper-target-list';
	const title = document.createElement('div');
	title.className = 'quest-helper-subheader';
	title.textContent = 'Targets';
	wrap.appendChild(title);
	targets.forEach(t => {
		if (!t) return;
		const el = document.createElement('span');
		el.className = 'quest-helper-target';
		let textParts = [];
		if (t.name) textParts.push(t.name);
		if (t.text && t.text !== t.name) textParts.push(t.text);
		if (t.type) textParts.push('[' + t.type + ']');
		if (t.action) textParts.push(t.action);
		if (t.worldPoint) {
			const wp = t.worldPoint;
			textParts.push('(' + (wp.x ?? '?') + ',' + (wp.y ?? '?') + ',' + (wp.plane ?? 0) + ')');
		}
		if (t.distance != null) textParts.push(t.distance + 't');
		if (t.reachable === false) textParts.push('(unreachable)');
		el.textContent = textParts.length > 0 ? textParts.join(' ') : (t.stepId || 'Target');
		wrap.appendChild(el);
	});
	content.appendChild(wrap);
}

/**
 * Format a Quest Helper structured event for the action log.
 */
function formatQuestHelperEvent(event) {
  const q = event.questName ?? event.questKey ?? '?';
  const oldStep = event.oldStepId ?? event.oldStepText ?? null;
  const newStep = event.newStepId ?? event.newStepText ?? null;
  if (event.type === 'QUEST_HELPER_STEP_CHANGED' && oldStep != null && newStep != null) {
    return `Quest Helper step changed: ${q} ${oldStep} → ${newStep}`;
  }
  if (event.type === 'QUEST_HELPER_BLOCKED') {
    return `Quest Helper blocked: ${q} — ${event.reason ?? event.detail ?? ''}`.trim();
  }
  return `Quest Helper ${event.type.replace(/^QUEST_HELPER_/, '').replace(/_/g, ' ')}: ${q}`;
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

/* ---------------------------------------------------------------------------
 * RSProx packets section (SC-1618).
 *
 * Packets arrive inside TICK_BATCH (live WebSocket) or the paginated replay
 * packet API. A batch may carry observations for several assigned ticks, so
 * updatePanelsForTick distributes rows by packet.tick via distributePacketsToTicks.
 * All packet-derived text is rendered with textContent/escapeHtml and decoded
 * bodies expand lazily; rows are capped so bursts cannot freeze the browser.
 * ------------------------------------------------------------------------- */
const MAX_PACKETS_PER_TICK_SNAPSHOT = 500;
const PACKET_ROW_CAP = 200;
const PACKET_DECODED_PREVIEW_CHARS = 2000;

const packetFilters = {
  direction: '',
  status: '',
  text: ''
};

function packetIdentityKey(packet) {
  return replayRecoveryCore.packetIdentityKey(packet);
}

function cappedPackets(packets) {
  if (!Array.isArray(packets) || packets.length === 0) return [];
  return packets.slice(-MAX_PACKETS_PER_TICK_SNAPSHOT);
}

/**
 * Merges packet rows into per-tick snapshots by packet.tick (not the enclosing
 * batch tick) and ensures a tick group exists for every affected tick.
 * Ownership follows packet.tick: every packet lands only under its assigned tick,
 * and the primary snapshot keeps only its own packets, so batches spanning
 * several ticks never duplicate rows under the enclosing batch tick.
 */
function distributePacketsToTicks(packets, primaryTick) {
  if (!Array.isArray(packets) || packets.length === 0) return;
  const store = currentMode === 'live' ? liveTickSnapshots : replayTickSnapshots;
  const byTick = new Map();
  packets.forEach(p => {
    const t = p && Number.isFinite(Number(p.tick)) ? Number(p.tick) : primaryTick;
    if (!byTick.has(t)) byTick.set(t, []);
    byTick.get(t).push(p);
  });
  byTick.forEach((list, t) => {
    if (t === primaryTick) return;
    let snap = store.get(t);
    if (!snap) {
      snap = {};
      store.set(t, snap);
    }
    const existing = Array.isArray(snap.packets) ? snap.packets : [];
    const seen = new Set(existing.map(packetIdentityKey));
    const fresh = list.filter(
      e => !seen.has(packetIdentityKey(e)));
    const merged = existing.concat(fresh);
    snap.packetTruncated = merged.length > MAX_PACKETS_PER_TICK_SNAPSHOT || !!snap.packetTruncated;
    snap.packets = merged.slice(-MAX_PACKETS_PER_TICK_SNAPSHOT);
    try {
      ensureTickGroupForSnapshot(t);
    } catch (e) {
      console.warn('packet tick-group ensure failed', e);
    }
  });
  const primaryList = byTick.get(primaryTick);
  if (primaryList && primaryList.length !== packets.length) {
    const primary = store.get(primaryTick);
    if (primary && Array.isArray(primary.packets)) {
      primary.packets = primary.packets.filter(
        p => (p && Number.isFinite(Number(p.tick)) ? Number(p.tick) : primaryTick) === primaryTick);
    }
  }
}

function packetMatchesFilters(packet) {
  if (packetFilters.direction && packet.direction !== packetFilters.direction) return false;
  if (packetFilters.status) {
    if (packetFilters.status === 'ERRORS') {
      if (packet.decodeStatus !== 'DECODE_ERROR' && packet.decodeStatus !== 'UNKNOWN_PROTOCOL') return false;
    } else if (packetFilters.status === 'GAPS') {
      if (packet.decodeStatus !== 'GAP') return false;
    } else if (packetFilters.status === 'DECODED_ONLY') {
      if (packet.decodeStatus !== 'DECODED') return false;
    } else if (packet.decodeStatus !== packetFilters.status) {
      return false;
    }
  }
  if (packetFilters.text) {
    const hay = `${packet.protocolName || ''} ${packet.opcode ?? ''} ${packet.decodedType || ''} ${packet.decodeError || ''} ${packet.sequence ?? ''} ${packet.packetSequence ?? ''}`.toLowerCase();
    if (!hay.includes(packetFilters.text.toLowerCase())) return false;
  }
  return true;
}

function formatPacketTime(packet) {
  if (packet.capturedAt) {
    const d = new Date(packet.capturedAt);
    if (!isNaN(d.getTime())) {
      return d.toISOString().slice(11, 23);
    }
  }
  return `#${packet.sequence ?? '?'}`;
}

function buildPacketCountBadge(snapshot) {
  const rows = Array.isArray(snapshot?.packets) ? snapshot.packets : [];
  const seeded = Number(snapshot?.packetCount ?? 0) || 0;
  const count = Math.max(rows.length, seeded);
  const badge = document.createElement('span');
  badge.className = 'tick-packet-count';
  badge.title = count === 1 ? '1 packet' : `${count} packets`;
  badge.textContent = `Pk ${count}`;
  if (count === 0) badge.classList.add('empty');
  const transport = snapshot?.transport;
  if (transport && (transport.droppedPacketCount > 0 || transport.omittedPacketCount > 0
      || transport.malformedPacketFrameCount > 0)) {
    badge.classList.add('has-gap');
    badge.title += ' (drops/omissions reported — see Packets section)';
  }
  return badge;
}

function buildPacketsSection(tick, snapshot) {
  const section = document.createElement('div');
  section.className = 'tick-section section-packets';

  const tickLabel = tick === null ? 'Unknown' : `Tick ${tick}`;
  const packets = Array.isArray(snapshot?.packets) ? snapshot.packets : [];
  const seededPacketCount = Number(snapshot?.packetCount ?? 0) || 0;
  const packetTotal = Math.max(packets.length, seededPacketCount);
  const transport = snapshot?.transport || {};

  const header = document.createElement('div');
  header.className = 'tick-section-header';
  const title = document.createElement('span');
  title.innerHTML = '<i class="bi bi-diagram-3"></i> ';
  title.appendChild(document.createTextNode(`Packets (${tickLabel}) · ${packetTotal}`));
  header.appendChild(title);

  const badges = document.createElement('span');
  badges.className = 'packet-badges';
  const sourceStatus = transport.packetSourceStatus;
  if (sourceStatus && sourceStatus !== 'CONNECTED') {
    const source = document.createElement('span');
    source.className = 'packet-badge packet-badge-source';
    source.textContent = sourceStatus === 'UNAVAILABLE' ? 'source unavailable' : `source ${sourceStatus.toLowerCase()}`;
    badges.appendChild(source);
  }
  const dropped = Number(transport.droppedPacketCount || 0);
  const omitted = Number(transport.omittedPacketCount || 0);
  const malformed = Number(transport.malformedPacketFrameCount || 0);
  if (dropped > 0 || omitted > 0 || malformed > 0 || packets.some(p => p.decodeStatus === 'GAP')) {
    const gap = document.createElement('span');
    gap.className = 'packet-badge packet-badge-gap';
    const range = transport.firstDroppedPacketSequence != null
      ? ` seq ${transport.firstDroppedPacketSequence}–${transport.lastDroppedPacketSequence}`
      : '';
    const malformedSuffix = malformed > 0 ? `, malformed ${malformed}` : '';
    gap.textContent = `gap: dropped ${dropped}, omitted ${omitted}${malformedSuffix}${range}`;
    badges.appendChild(gap);
  }
  if (snapshot?.packetTruncated) {
    const trunc = document.createElement('span');
    trunc.className = 'packet-badge packet-badge-truncated';
    trunc.textContent = 'truncated';
    badges.appendChild(trunc);
  }
  if (snapshot?.replayPacketsTruncated) {
    const trunc = document.createElement('span');
    trunc.className = 'packet-badge packet-badge-truncated';
    trunc.textContent = 'replay window truncated at 10,000 packets';
    trunc.title = 'Narrow the tick range to load the remaining replay packets';
    trunc.style.cursor = 'pointer';
    trunc.addEventListener('click', () => {
      const truncatedWindow = replayPacketTruncatedWindows.find(w => w.start <= tick && tick <= w.end);
      const continuation = truncatedWindow
        ? replayPacketContinuationCursors.get(
          replayPacketWindowKey(currentRecording, truncatedWindow.start, truncatedWindow.end))
        : null;
      if (continuation && truncatedWindow && currentRecording
          && typeof ensureReplayPacketWindow === 'function') {
        ensureReplayPacketWindow(currentRecording, truncatedWindow.start, truncatedWindow.end,
          { forceReload: true, continueFromCursor: true });
        return;
      }
      const range = currentReplayVisibleRange(tick);
      if (range && typeof loadReplayTickWindow === 'function' && currentRecording) {
        const span = range.end - range.start;
        // Halve the visible window around its center and force a reload: the 10,000-packet
        // cap binds per window, so a narrower range resolves into rows and clears the flag.
        const clickedTick = Number(tick);
        const center = Number.isFinite(clickedTick)
          ? Math.max(range.start, Math.min(range.end, clickedTick))
          : Math.floor((range.start + range.end) / 2);
        const half = Math.max(1, Math.floor(span / 4));
        loadReplayTickWindow(currentRecording, Math.max(0, center - half), center + half,
          { forceReload: true });
      }
    });
    badges.appendChild(trunc);
  }
  const packetError = replayPacketErrorForTick(tick);
  if (packetError) {
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'packet-badge packet-badge-error packet-retry-btn';
    retry.textContent = 'packet load failed — retry';
    retry.title = packetError.message;
    retry.addEventListener('click', event => {
      event.stopPropagation();
      clearReplayPacketLoadError(packetError.recordingId, packetError.start, packetError.end);
      ensureReplayPacketWindow(packetError.recordingId, packetError.start, packetError.end,
        { forceReload: true });
    });
    badges.appendChild(retry);
  }
  header.appendChild(badges);
  section.appendChild(header);

  // Filters: direction, status, text search.
  const filters = document.createElement('div');
  filters.className = 'packet-filters';
  const dirSelect = document.createElement('select');
  dirSelect.className = 'packet-filter-select';
  dirSelect.title = 'Filter by direction';
  [['', 'Both directions'], ['CLIENT_TO_SERVER', 'Client →'], ['SERVER_TO_CLIENT', '← Server']].forEach(([value, label]) => {
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    if (packetFilters.direction === value) opt.selected = true;
    dirSelect.appendChild(opt);
  });
  dirSelect.addEventListener('change', () => {
    packetFilters.direction = dirSelect.value;
    rerenderTickGroup(tick);
  });
  const statusSelect = document.createElement('select');
  statusSelect.className = 'packet-filter-select';
  statusSelect.title = 'Filter by decode status';
  [['', 'All statuses'], ['DECODED_ONLY', 'Decoded'], ['ERRORS', 'Errors'], ['GAPS', 'Gaps'], ['DECODED', 'DECODED'], ['UNKNOWN_PROTOCOL', 'UNKNOWN_PROTOCOL'], ['DECODE_ERROR', 'DECODE_ERROR'], ['GAP', 'GAP']].forEach(([value, label]) => {
    const opt = document.createElement('option');
    opt.value = value;
    opt.textContent = label;
    if (packetFilters.status === value) opt.selected = true;
    statusSelect.appendChild(opt);
  });
  statusSelect.addEventListener('change', () => {
    packetFilters.status = statusSelect.value;
    rerenderTickGroup(tick);
  });
  const textInput = document.createElement('input');
  textInput.type = 'text';
  textInput.className = 'packet-filter-text';
  textInput.placeholder = 'Filter protocol/opcode/type…';
  textInput.value = packetFilters.text || '';
  textInput.addEventListener('input', () => {
    packetFilters.text = textInput.value;
    const caret = textInput.selectionStart;
    rerenderPacketListOnly(tick, textInput, caret);
  });
  // Avoid collapsing the tick group while interacting with filters.
  filters.addEventListener('click', e => e.stopPropagation());
  filters.appendChild(dirSelect);
  filters.appendChild(statusSelect);
  filters.appendChild(textInput);
  section.appendChild(filters);

  const content = document.createElement('div');
  content.className = 'tick-section-content packet-list';

  if (snapshot?.packetLoadError) {
    const retry = document.createElement('button');
    retry.type = 'button';
    retry.className = 'packet-badge packet-badge-error packet-retry-btn';
    retry.textContent = 'packet load failed — retry';
    retry.title = snapshot.packetLoadError;
    retry.addEventListener('click', event => {
      event.stopPropagation();
      jumpToTick(tick);
    });
    content.appendChild(retry);
    section.appendChild(content);
    return section;
  }

  if (packets.length === 0) {
    const empty = document.createElement('span');
    empty.className = 'section-empty';
    empty.textContent = sourceStatus === 'UNAVAILABLE'
      ? 'Packet source unavailable (client not launched through RSProx)'
      : (seededPacketCount > 0
        ? `Loading ${seededPacketCount} packet${seededPacketCount === 1 ? '' : 's'}…`
        : 'No packets for this tick');
    content.appendChild(empty);
    section.appendChild(content);
    return section;
  }

  const visible = packets.filter(packetMatchesFilters);
  const shown = visible.slice(0, PACKET_ROW_CAP);
  shown.forEach(packet => content.appendChild(buildPacketRow(packet)));
  if (visible.length > shown.length) {
    const more = document.createElement('div');
    more.className = 'section-empty';
    more.textContent = `Showing ${shown.length} of ${visible.length} matching packets (${packetTotal} total)`;
    content.appendChild(more);
  } else if (visible.length < packets.length) {
    const note = document.createElement('div');
    note.className = 'section-empty';
    note.textContent = `Showing ${visible.length} of ${packetTotal} packets (filtered)`;
    content.appendChild(note);
  } else if (packetTotal > packets.length) {
    const pending = document.createElement('div');
    pending.className = 'section-empty';
    pending.textContent = `Showing ${packets.length} of ${packetTotal} packets (loading…)`;
    content.appendChild(pending);
  }
  section.appendChild(content);
  return section;
}

function buildPacketRow(packet) {
  const row = document.createElement('div');
  row.className = 'packet-row';
  const identity = replayRecoveryCore.packetDisplayIdentity(packet);

  const arrow = document.createElement('span');
  arrow.className = 'packet-direction ' + (packet.direction === 'CLIENT_TO_SERVER' ? 'packet-out' : 'packet-in');
  arrow.textContent = packet.direction === 'CLIENT_TO_SERVER' ? '→' : '←';
  arrow.title = packet.direction || '';

  const time = document.createElement('span');
  time.className = 'packet-time';
  time.textContent = formatPacketTime(packet);

  const seq = document.createElement('span');
  seq.className = 'packet-seq';
  seq.textContent = `obs #${identity.observationSequence ?? '?'}`;

  const captureSeq = document.createElement('span');
  captureSeq.className = 'packet-capture-seq';
  captureSeq.textContent = identity.captureSequence != null
    ? `capture #${identity.captureSequence}`
    : (packet.decodeStatus === 'GAP' ? 'capture GAP range' : 'capture —');

  const opcode = document.createElement('span');
  opcode.className = 'packet-opcode';
  opcode.textContent = `op ${packet.opcode ?? '?'}`;

  const name = document.createElement('span');
  name.className = 'packet-name';
  name.textContent = packet.protocolName || 'UNKNOWN';

  const size = document.createElement('span');
  size.className = 'packet-size';
  size.textContent = packet.encodedSize != null ? `${packet.encodedSize}B` : '';

  const status = document.createElement('span');
  status.className = 'packet-badge packet-status-' + String(packet.decodeStatus || 'UNKNOWN').toLowerCase().replace(/[^a-z]/g, '');
  status.textContent = packet.decodeStatus || 'UNKNOWN';
  if (packet.synthetic) {
    status.textContent += ' · synthetic';
  }

  const toggle = document.createElement('button');
  toggle.type = 'button';
  toggle.className = 'packet-expand-btn';
  toggle.textContent = '+';
  toggle.title = 'Expand decoded detail';

  const summary = document.createElement('div');
  summary.className = 'packet-summary';
  summary.append(arrow, time, seq, captureSeq, opcode, name, size, status, toggle);

  const detail = document.createElement('div');
  detail.className = 'packet-detail';
  detail.hidden = true;

  toggle.addEventListener('click', e => {
    e.stopPropagation();
    detail.hidden = !detail.hidden;
    toggle.textContent = detail.hidden ? '+' : '−';
    if (!detail.hidden && !detail.dataset.rendered) {
      renderPacketDetail(detail, packet);
      detail.dataset.rendered = '1';
    }
  });

  // Keyboard accessible expansion.
  summary.tabIndex = 0;
  summary.addEventListener('keydown', e => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      toggle.click();
    }
  });

  row.append(summary, detail);
  return row;
}

function renderPacketDetail(container, packet) {
  if (packet.decodedType) {
    const typeRow = document.createElement('div');
    typeRow.className = 'packet-detail-row';
    const label = document.createElement('span');
    label.className = 'packet-detail-label';
    label.textContent = 'Type: ';
    const value = document.createElement('span');
    value.textContent = packet.decodedType;
    typeRow.append(label, value);
    container.appendChild(typeRow);
  }
  if (packet.decodeError) {
    const errRow = document.createElement('div');
    errRow.className = 'packet-detail-row packet-detail-error';
    errRow.textContent = `Error: ${packet.decodeError}`;
    container.appendChild(errRow);
  }
  if (packet.decodeStatus === 'GAP' && packet.decoded && Array.isArray(packet.decoded.droppedPacketRanges)) {
    const ranges = document.createElement('div');
    ranges.className = 'packet-detail-row packet-detail-gap-ranges';
    const text = packet.decoded.droppedPacketRanges.map(range =>
      `${range.first ?? '?'}–${range.last ?? '?'} (${range.count ?? '?'})`).join(', ');
    ranges.textContent = `Lost capture ranges: ${text || 'unknown'}`;
    container.appendChild(ranges);
  }
  if (packet.decoded !== undefined && packet.decoded !== null) {
    const pre = document.createElement('pre');
    pre.className = 'packet-decoded';
    let text;
    try {
      text = JSON.stringify(packet.decoded, null, 2);
    } catch (e) {
      text = String(packet.decoded);
    }
    pre.textContent = text.length > PACKET_DECODED_PREVIEW_CHARS
      ? text.slice(0, PACKET_DECODED_PREVIEW_CHARS) + `… (${text.length - PACKET_DECODED_PREVIEW_CHARS} more chars)`: text;
    container.appendChild(pre);
  } else {
    const meta = document.createElement('div');
    meta.className = 'packet-detail-row section-empty';
    meta.textContent = 'Metadata only (decoded body omitted)';
    container.appendChild(meta);
  }
  if (packet.sanitizedPayloadBase64) {
    const raw = document.createElement('div');
    raw.className = 'packet-detail-row section-empty';
    raw.textContent = `Sanitized payload: ${packet.sanitizedPayloadBase64.slice(0, 128)}${packet.sanitizedPayloadBase64.length > 128 ? '…' : ''}`;
    container.appendChild(raw);
  }
}

/* Replay packet loading: visible-window fetch merged by packet.tick. */
const replayPacketSessionCache = new Map();
/* Windows that hit the 10,000-packet cap: narrowing the tick range retries them. */
const replayPacketTruncatedWindows = [];
/* A continuation cursor is retained when a window reaches the browser page cap. */
const replayPacketContinuationCursors = new Map();
/* One request per recording/window; overlapping UI rerenders share the same promise. */
const replayPacketInFlight = replayRecoveryCore.createInFlightRegistry();
/* Failed packet loads stay visible until an explicit retry succeeds. */
const replayPacketLoadErrors = new Map();

function replayPacketWindowKey(recordingId, startTick, endTick) {
  return `${recordingId}:${startTick}:${endTick}`;
}

function replayPacketErrorForTick(tick) {
  if (!currentRecording) return null;
  for (const error of replayPacketLoadErrors.values()) {
    if (error.recordingId === currentRecording && error.start <= tick && tick <= error.end) {
      return error;
    }
  }
  return null;
}

function setReplayPacketLoadError(recordingId, startTick, endTick, message) {
  const key = replayPacketWindowKey(recordingId, startTick, endTick);
  replayPacketLoadErrors.set(key, {
    recordingId,
    start: startTick,
    end: endTick,
    message: message || 'Packet rows could not be loaded.'
  });
  if (currentMode === 'replay' && currentRecording === recordingId
      && typeof replayTickSnapshots !== 'undefined') {
    replayTickSnapshots.forEach((_snapshot, tick) => {
      if (tick >= startTick && tick <= endTick && typeof rerenderTickGroup === 'function') {
        try { rerenderTickGroup(tick); } catch (e) { console.warn('packet error rerender failed', e); }
      }
    });
  }
}

function clearReplayPacketLoadError(recordingId, startTick, endTick) {
  replayPacketLoadErrors.delete(replayPacketWindowKey(recordingId, startTick, endTick));
}

/** True when the requested range overlaps a truncated window and is narrower than it. */
function replayPacketWindowNeedsRecovery(startTick, endTick) {
  if (!Array.isArray(replayPacketTruncatedWindows) || replayPacketTruncatedWindows.length === 0) return false;
  const span = endTick - startTick;
  return replayPacketTruncatedWindows.some(w =>
    w.start <= endTick && startTick <= w.end && span < (w.end - w.start));
}

/**
 * Visible replay tick range for truncation recovery. Prefer the truncated window containing
 * the clicked tick so recovery stays local to the warning the operator selected. If several
 * windows overlap that tick, use the narrowest one; falling back to the widest window keeps
 * keyboard/programmatic callers useful when no focused tick is available.
 */
function currentReplayVisibleRange(focusTick = null) {
  if (Array.isArray(replayPacketTruncatedWindows) && replayPacketTruncatedWindows.length > 0) {
    const selected = replayRecoveryCore.chooseRecoveryWindow(
      replayPacketTruncatedWindows,
      focusTick
    );
    if (selected) {
      return selected;
    }
  }
  if (typeof replayLoadedRanges !== 'undefined' && Array.isArray(replayLoadedRanges)
      && replayLoadedRanges.length > 0) {
    let start = replayLoadedRanges[0].start;
    let end = replayLoadedRanges[0].end;
    for (const r of replayLoadedRanges) {
      start = Math.min(start, r.start);
      end = Math.max(end, r.end);
    }
    return { start, end };
  }
  return null;
}

async function loadReplayPacketWindow(recordingId, startTick, endTick, options = {}) {
  if (!recordingId || startTick == null || endTick == null) return;
  if (currentMode !== 'replay' || currentRecording !== recordingId) return;
  const generation = typeof replayLoadGeneration !== 'undefined' ? replayLoadGeneration : 0;
  const signal = (typeof replayLoadAbortController !== 'undefined' && replayLoadAbortController)
    ? replayLoadAbortController.signal
    : null;
  // Recovery reloads bypass any cached completion for this exact window: the 10,000-packet
  // cap otherwise makes a narrower retry a no-op because the range claims to be loaded.
  const forceReload = !!(options && options.forceReload);
  const loadedKey = `${recordingId}:${startTick}:${endTick}`;
  if (!forceReload) {
    ensureReplayPacketWindow.loadedWindows = ensureReplayPacketWindow.loadedWindows || new Set();
    if (ensureReplayPacketWindow.loadedWindows.has(loadedKey)) return;
  }
  let sessionId = replayPacketSessionCache.get(recordingId);
  if (sessionId === undefined) {
    try {
      const lookup = await fetch(
        `/api/debug/replay/sessions/by-recording?recordingId=${encodeURIComponent(recordingId)}`,
        signal ? { signal } : {});
      if (typeof isCurrentReplayLoad === 'function' && !isCurrentReplayLoad(generation, recordingId)) return;
      if (lookup.ok) {
        const summary = await lookup.json();
        sessionId = summary.sessionId || null;
      } else {
        sessionId = null;
      }
    } catch (e) {
      if (e && e.name === 'AbortError') return;
      setReplayPacketLoadError(recordingId, startTick, endTick,
        e && e.message ? e.message : 'Replay session lookup failed.');
      console.warn('Replay packet session lookup failed:', e);
      return;
    }
    replayPacketSessionCache.set(recordingId, sessionId);
  }
  if (!sessionId) {
    setReplayPacketLoadError(recordingId, startTick, endTick,
      'No debug session is attached to this recording.');
    return;
  }
  try {
    const windowKey = replayPacketWindowKey(recordingId, startTick, endTick);
    let cursor = options && options.continueFromCursor
      ? (replayPacketContinuationCursors.get(windowKey) || '')
      : '';
    if (options && options.continueFromCursor && !cursor) return;
    let pages = 0;
    for (;;) {
      const url = `/api/debug/replay/packets/${encodeURIComponent(sessionId)}?startTick=${startTick}&endTick=${endTick}&limit=500`
        + (cursor ? `&cursor=${encodeURIComponent(cursor)}` : '');
      const response = await fetch(url, signal ? { signal } : {});
      if (typeof isCurrentReplayLoad === 'function' && !isCurrentReplayLoad(generation, recordingId)) return;
      if (!response.ok) {
        setReplayPacketLoadError(recordingId, startTick, endTick,
          `Packet request failed (${response.status}).`);
        return;
      }
      const page = await response.json();
      if (typeof isCurrentReplayLoad === 'function' && !isCurrentReplayLoad(generation, recordingId)) return;
      mergeReplayPackets(page.packets || []);
      const pageDecision = replayRecoveryCore.nextPacketPage(page, pages);
      if (pageDecision.done) {
        if (!pageDecision.truncated) {
          // Fully loaded: this narrower window recovers any truncated window it covers,
          // clearing the flag so the 10,000-packet warning disappears once rows arrive.
          clearReplayPacketsTruncatedForRange(recordingId, startTick, endTick);
          replayPacketContinuationCursors.delete(windowKey);
        } else {
          cursor = pageDecision.cursor;
          replayPacketContinuationCursors.set(windowKey, cursor);
          markReplayPacketsTruncated(startTick, endTick, cursor);
        }
        clearReplayPacketLoadError(recordingId, startTick, endTick);
        ensureReplayPacketWindow.loadedWindows = ensureReplayPacketWindow.loadedWindows || new Set();
        ensureReplayPacketWindow.loadedWindows.add(`${recordingId}:${startTick}:${endTick}`);
        return;
      }
      cursor = pageDecision.cursor;
      pages++;
    }
  } catch (e) {
    if (!e || e.name !== 'AbortError') {
      setReplayPacketLoadError(recordingId, startTick, endTick,
        e && e.message ? e.message : 'Packet request failed.');
      console.warn('Replay packet fetch failed:', e);
    }
  }
}

/** Coalesces overlapping packet loads for the same recording/window. */
async function ensureReplayPacketWindow(recordingId, startTick, endTick, options = {}) {
  const key = replayPacketWindowKey(recordingId, startTick, endTick);
  return replayPacketInFlight.ensure(
    key,
    () => loadReplayPacketWindow(recordingId, startTick, endTick, options)
  );
}

function markReplayPacketsTruncated(startTick, endTick, continuationCursor = null) {
  if (!Array.isArray(replayPacketTruncatedWindows)) return;
  const existing = replayPacketTruncatedWindows.find(w => w.start === startTick && w.end === endTick);
  if (existing) {
    if (continuationCursor) existing.cursor = continuationCursor;
  } else {
    replayPacketTruncatedWindows.push({ start: startTick, end: endTick, cursor: continuationCursor });
  }
  if (continuationCursor) {
    replayPacketContinuationCursors.set(
      replayPacketWindowKey(currentRecording, startTick, endTick), continuationCursor);
  }
  if (typeof replayTickSnapshots !== 'undefined') replayTickSnapshots.forEach((snapshot, tick) => {
    if (tick < startTick || tick > endTick) return;
    if (snapshot) snapshot.replayPacketsTruncated = true;
  });
  if (currentMode === 'replay') {
    replayTickSnapshots.forEach((_snapshot, tick) => {
      if (tick < startTick || tick > endTick) return;
      try {
        if (typeof rerenderTickGroup === 'function') rerenderTickGroup(tick);
      } catch (e) {
        console.warn('packet truncation rerender failed', e);
      }
    });
  }
}

/**
 * Clears the truncation flag for ticks fully covered by a successfully loaded range, so
 * narrowing the tick range after a 10,000-packet truncation recovers visibly. Ranges
 * outside the loaded window keep their flag until they are loaded in full.
 */
function clearReplayPacketsTruncatedForRange(recordingId, startTick, endTick) {
  if (typeof startTick !== 'number' || typeof endTick !== 'number') {
    endTick = startTick;
    startTick = recordingId;
    recordingId = null;
  }
  const overlappingWindows = replayPacketTruncatedWindows.filter(w =>
    w.end >= startTick && w.start <= endTick);
  const clearedAny = overlappingWindows.length > 0;
  const remainingWindows = replayRecoveryCore.subtractTruncatedWindows(
    replayPacketTruncatedWindows,
    startTick,
    endTick
  );
  const cursorRecording = recordingId == null ? currentRecording : recordingId;
  for (const w of overlappingWindows) {
    replayPacketContinuationCursors.delete(replayPacketWindowKey(cursorRecording, w.start, w.end));
  }
  replayPacketTruncatedWindows.splice(0, replayPacketTruncatedWindows.length, ...remainingWindows);
  // The narrower retry must not inherit the wide window's "already loaded" marker:
  // drop every cached packet window inside the recovered range so later pans refetch.
  if (ensureReplayPacketWindow.loadedWindows) {
    for (const key of Array.from(ensureReplayPacketWindow.loadedWindows)) {
      const parts = String(key).split(':');
      const s = Number(parts[parts.length - 2]);
      const e = Number(parts[parts.length - 1]);
      const keyRecording = parts.slice(0, -2).join(':');
      if (Number.isFinite(s) && Number.isFinite(e) && s >= startTick && e <= endTick
          && (recordingId == null || keyRecording === String(recordingId))) {
        ensureReplayPacketWindow.loadedWindows.delete(key);
      }
    }
    if (recordingId != null) {
      ensureReplayPacketWindow.loadedWindows.add(`${recordingId}:${startTick}:${endTick}`);
    }
  }
  if (typeof replayTickSnapshots !== 'undefined') replayTickSnapshots.forEach((snapshot, tick) => {
    if (tick < startTick || tick > endTick) return;
    if (snapshot && snapshot.replayPacketsTruncated
        && !replayPacketTruncatedWindows.some(w => tick >= w.start && tick <= w.end)) {
      snapshot.replayPacketsTruncated = false;
      try {
        if (currentMode === 'replay' && typeof rerenderTickGroup === 'function') rerenderTickGroup(tick);
      } catch (e) {
        console.warn('packet truncation recovery rerender failed', e);
      }
    }
  });
  return clearedAny;
}

function mergeReplayPackets(rows) {
  if (!Array.isArray(rows) || rows.length === 0) return;
  const affected = new Set();
  rows.forEach(packet => {
    const tick = Number(packet.tick);
    if (!Number.isFinite(tick)) return;
    let snapshot = replayTickSnapshots.get(tick);
    if (!snapshot) {
      snapshot = {};
      replayTickSnapshots.set(tick, snapshot);
    }
    const existing = Array.isArray(snapshot.packets) ? snapshot.packets : [];
    const duplicate = existing.some(e => packetIdentityKey(e) === packetIdentityKey(packet));
    if (!duplicate) {
      existing.push(packet);
      if (existing.length > MAX_PACKETS_PER_TICK_SNAPSHOT) {
        snapshot.packetTruncated = true;
        snapshot.packets = existing.slice(-MAX_PACKETS_PER_TICK_SNAPSHOT);
      } else {
        snapshot.packets = existing;
      }
      affected.add(tick);
    }
  });
  if (currentMode === 'replay') {
    affected.forEach(tick => {
      try {
        rerenderTickGroup(tick);
      } catch (e) {
        console.warn('packet tick rerender failed', e);
      }
    });
  }
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
 * Player state display helpers - resolve from nested canonical or flat legacy fields.
 */

function normalizeCoordinateValue(value) {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.trunc(value);
  }
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!/^-?\d+$/.test(trimmed)) return null;
    return Number.parseInt(trimmed, 10);
  }
  return null;
}

function normalizePositionObject(pos) {
  if (!pos || typeof pos !== 'object') {
    return {};
  }

  const x = normalizeCoordinateValue(pos.x);
  const y = normalizeCoordinateValue(pos.y ?? pos.z);
  if (x === null || y === null) {
    return {};
  }

  const z = normalizeCoordinateValue(pos.z ?? y) ?? y;
  const plane = normalizeCoordinateValue(pos.plane) ?? 0;
  return { x, y, z, plane };
}

function getPlayerPosition(player) {
  if (!player || typeof player !== 'object') {
    return {};
  }

  // Nested canonical and legacy position objects.
  for (const key of ['position', 'worldPosition', 'worldPoint', 'tile']) {
    if (player[key] && typeof player[key] === 'object' && player[key].x !== undefined) {
      return normalizePositionObject(player[key]);
    }
  }

  // Flat fields.
  if (player.x !== undefined) {
    const x = normalizeCoordinateValue(player.x);
    const y = normalizeCoordinateValue(player.y ?? player.z ?? player.worldY);
    if (x === null || y === null) return {};
    const z = normalizeCoordinateValue(player.z ?? y) ?? y;
    const plane = normalizeCoordinateValue(player.plane) ?? 0;
    return { x, y, z, plane };
  }
  if (player.worldX !== undefined) {
    const x = normalizeCoordinateValue(player.worldX);
    const y = normalizeCoordinateValue(player.worldY ?? player.y ?? player.z);
    if (x === null || y === null) return {};
    const z = normalizeCoordinateValue(player.z ?? y) ?? y;
    const plane = normalizeCoordinateValue(player.plane) ?? 0;
    return { x, y, z, plane };
  }
  return {};
}

function getPlayerHp(player) {
  if (player.hp && typeof player.hp === 'object' && player.hp.current !== undefined) {
    return player.hp.current + '/' + (player.hp.max || '?');
  }
  if (player.currentHp !== undefined) {
    return player.currentHp + '/' + (player.maxHp || '?');
  }
  return null;
}

function getPlayerPrayer(player) {
  if (player.prayer && typeof player.prayer === 'object' && player.prayer.current !== undefined) {
    var pc = player.prayer.current;
    var pm = player.prayer.max || player.prayer.maxPoints || '?';
    return pc + '/' + pm;
  }
  if (player.currentPrayer !== undefined) {
    return player.currentPrayer + '/' + (player.maxPrayer || '?');
  }
  return null;
}

function getPlayerEnergy(player) {
  if (player.energy !== undefined) return player.energy + '%';
  if (player.runEnergy !== undefined) return player.runEnergy + '%';
  return null;
}

function getPlayerAnimation(player) {
  if (player.animation !== undefined) return String(player.animation);
  if (player.animationId !== undefined) return String(player.animationId);
  return null;
}

function buildCollapsibleJsonTree(value, options) {
  options = options || {};
  var maxDepth = options.maxDepth || 10;
  var indent = options.indent || 2;
  var depth = options.depth || 0;
  var container = document.createElement('div');
  container.className = 'player-json-tree';

  if (depth >= maxDepth) {
    var truncSpan = document.createElement('span');
    truncSpan.className = 'player-json-value';
    truncSpan.textContent = '...';
    container.appendChild(truncSpan);
    return container;
  }

  if (value === null || value === undefined) {
    var nullSpan = document.createElement('span');
    nullSpan.className = 'player-json-value player-json-null';
    nullSpan.textContent = 'null';
    container.appendChild(nullSpan);
  } else if (typeof value === 'boolean' || typeof value === 'number') {
    var valSpan = document.createElement('span');
    valSpan.className = 'player-json-value player-json-primitive';
    valSpan.textContent = String(value);
    container.appendChild(valSpan);
  } else if (typeof value === 'string') {
    var strSpan = document.createElement('span');
    strSpan.className = 'player-json-value player-json-string';
    strSpan.textContent = '"' + escapeHtml(value) + '"';
    container.appendChild(strSpan);
  } else if (Array.isArray(value)) {
    var openSpan = document.createElement('span');
    openSpan.className = 'player-json-bracket';
    openSpan.textContent = '[';
    container.appendChild(openSpan);
    if (value.length === 0) {
      var closeSpan = document.createElement('span');
      closeSpan.className = 'player-json-bracket';
      closeSpan.textContent = ']';
      container.appendChild(closeSpan);
    } else {
      var listDiv = document.createElement('div');
      listDiv.className = 'player-json-children';
      for (var i = 0; i < value.length; i++) {
        var itemDiv = document.createElement('div');
        itemDiv.className = 'player-json-entry';
        var childContainer = buildCollapsibleJsonTree(value[i],
          { maxDepth: maxDepth, indent: indent, depth: depth + 1 });
        itemDiv.appendChild(childContainer);
        if (i < value.length - 1) {
          var comma = document.createElement('span');
          comma.textContent = ',';
          childContainer.appendChild(comma);
        }
        listDiv.appendChild(itemDiv);
      }
      container.appendChild(listDiv);
      var closeDiv = document.createElement('div');
      closeDiv.textContent = ']';
      container.appendChild(closeDiv);
    }
  } else if (typeof value === 'object') {
    var keys = Object.keys(value);
    var openBrace = document.createElement('span');
    openBrace.className = 'player-json-bracket';
    openBrace.textContent = '{';
    container.appendChild(openBrace);
    if (keys.length === 0) {
      var closeBrace = document.createElement('span');
      closeBrace.className = 'player-json-bracket';
      closeBrace.textContent = '}';
      container.appendChild(closeBrace);
    } else {
      var objListDiv = document.createElement('div');
      objListDiv.className = 'player-json-children';
      for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        var entryDiv = document.createElement('div');
        entryDiv.className = 'player-json-entry';

        var keySpan = document.createElement('span');
        keySpan.className = 'player-json-key';
        keySpan.textContent = '"' + escapeHtml(key) + '": ';

        entryDiv.appendChild(keySpan);

        var valContainer = buildCollapsibleJsonTree(value[key],
          { maxDepth: maxDepth, indent: indent, depth: depth + 1 });
        entryDiv.appendChild(valContainer);

        if (i < keys.length - 1) {
          var trailingComma = document.createElement('span');
          trailingComma.textContent = ',';
          entryDiv.appendChild(trailingComma);
        }

        objListDiv.appendChild(entryDiv);
      }
      container.appendChild(objListDiv);
      var closingBrace = document.createElement('div');
      closingBrace.textContent = '}';
      container.appendChild(closingBrace);
    }
  }

  return container;
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

    const position = getPlayerPosition(player);
    const hpDisplay = getPlayerHp(player);
    const prayerDisplay = getPlayerPrayer(player);
    const energyDisplay = getPlayerEnergy(player);
    const animDisplay = getPlayerAnimation(player);

    const stats = [
      { label: 'HP', value: hpDisplay || '--' },
      { label: 'Prayer', value: prayerDisplay || '--' },
      { label: 'Energy', value: energyDisplay || '--' },
      { label: 'Animation', value: animDisplay || '--' },
      { label: 'Position', value: position.x !== undefined ? `(${position.x}, ${position.y}, ${position.plane ?? 0})` : '--' },
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

    // Collapsible full player object
    const toggleBtn = document.createElement('button');
    toggleBtn.className = 'player-json-toggle';
    toggleBtn.innerHTML = '<i class="bi bi-code-slash"></i> Full Player Object';
    toggleBtn.setAttribute('aria-expanded', 'false');

    const jsonPanel = document.createElement('div');
    jsonPanel.className = 'player-json-panel';
    jsonPanel.style.display = 'none';

    const jsonTree = buildCollapsibleJsonTree(snapshot.playerState, { maxDepth: 5, indent: 2 });
    jsonPanel.appendChild(jsonTree);

    toggleBtn.addEventListener('click', () => {
      const expanded = toggleBtn.getAttribute('aria-expanded') === 'true';
      if (expanded) {
        jsonPanel.style.display = 'none';
        toggleBtn.setAttribute('aria-expanded', 'false');
        toggleBtn.querySelector('.bi').className = 'bi bi-code-slash';
      } else {
        jsonPanel.style.display = 'block';
        toggleBtn.setAttribute('aria-expanded', 'true');
        toggleBtn.querySelector('.bi').className = 'bi bi-code-slash';
      }
    });

    content.appendChild(toggleBtn);
    content.appendChild(jsonPanel);
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

      // Immediately jump to the best locally known tick, then request the authoritative server
      // window for the current video time. The async response will render first and follow the
      // exact returned tick.
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
      if (currentMode === 'replay' && elements.replayVideo && currentRecording) {
        const currentTimeMs = Math.floor(elements.replayVideo.currentTime * 1000);
        lastSyncTimeMs = currentTimeMs;
        syncLogsToVideo(currentTimeMs);
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

function renderLogHistory(emptyMessage, options = {}) {
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  const history = getActiveLogHistory();
  const searchText = getLogSearchText();

  // Handle completely empty logs
  if (history.length === 0) {
    // If the container already has rendered content (e.g., a quiet-window
    // tick group from renderReplayLogs), don't wipe it. A tick-card
    // response should update snapshot content but not replace the
    // authoritative quiet-window UI.
    const existingGroup = outputContainer.querySelector('.tick-group');
    if (existingGroup && !options.forceEmpty) {
      return;
    }
    const defaultMsg = currentMode === 'replay'
      ? (currentRecording
          ? 'No logs for this time period'
          : 'Select a recording to view logs')
      : 'Waiting for logs...';
    ensureLogPlaceholder(emptyMessage || defaultMsg);
    return;
  }

  outputContainer.innerHTML = '';

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

  // The transport source is authoritative. Many Micro6 messages begin with bracketed state or
  // component labels; treating each bracket as a new source produced dozens of meaningless
  // one-off filters.
  const actualSource = entry.source || 'unknown';

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
  withLiveLogScrollAnchor(() => {
    batch.forEach(entry => addLiveLogEntryToTickGroup(entry));
    pruneTickGroups();
  });
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

function normalizeStandardLog(log, options = {}) {
  const rawTick = log.tick;
  const numericTick = rawTick !== null && rawTick !== undefined ? Number(rawTick) : NaN;
  return {
    kind: 'standard',
    level: String(log.level || 'INFO').toUpperCase(),
    message: log.message ? String(log.message) : '',
    source: log.source ? String(log.source) : 'unknown',
    timestamp: log.timestamp || null,
    // Ordinary process logs are not game-tick events. Preserve only a positive explicit tick;
    // never collapse unticked startup/client logs into the initial Tick 0 group.
    tick: Number.isFinite(numericTick) && numericTick > 0 ? numericTick : null
  };
}

function normalizeTickLog(log, options = {}) {
  const allowTickFallback = options.allowTickFallback !== false;
  // Preserve null ticks for unified replay data; live callers may fall back to currentTick.
  const tickValue = (log.hasOwnProperty('tick') && log.tick !== null && log.tick !== undefined)
    ? log.tick
    : (allowTickFallback ? (currentTick ?? null) : null);
  // Normalize '--' or non-numeric to null for proper sorting
  const normalizedTick = (tickValue === null || tickValue === '--' || isNaN(parseInt(tickValue))) ? null : tickValue;

  // Support both logType/data (batch) and eventType/details (live DTO) field names
  const resolvedLogType = log.logType || log.eventType || null;
  const resolvedData    = log.data    || log.details    || null;

  let resolvedMessage = log.message ? String(log.message) : '';
  if (resolvedData && isClickEventType(resolvedLogType) && hasClickProvenance(resolvedData)) {
    resolvedMessage = formatEventFromTypeAndData(resolvedLogType, resolvedData);
  }
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
    case 'QUEST_HELPER_STEP_CHANGED':
    case 'QUEST_HELPER_BLOCKED':
    case 'QUEST_HELPER_TARGET_CHANGED':
    case 'QUEST_HELPER_REQUIREMENT_CHANGED':
      return formatQuestHelperEvent({ ...d, type });
    case 'DIALOGUE':
      return `Dialogue: ${d.speaker ?? d.npcName ?? d.npc ?? ''}: "${d.text ?? ''}"`;
    case 'DIALOGUE_OPTION_SELECTED':
      return `Selected dialogue option: "${d.option ?? d.text ?? ''}"`;
    case 'CLICK': {
      const x = d.x ?? d.point?.x ?? null;
      const y = d.y ?? d.point?.y ?? null;
      const pos = x != null && y != null ? ` at ${x},${y}` : '';
      const isUserClick = isManualClickData(d);
      const isScriptClick = isScriptClickData(d);
      if (isUserClick) return `Manual click${pos}`;
      if (isScriptClick) return `Script click${pos}`;
      return `Click${pos}`;
    }
    case 'WALKHERE': {
      const wx = d.x ?? d.point?.x ?? null;
      const wy = d.y ?? d.point?.y ?? null;
      const wpos = wx != null && wy != null ? ` at ${wx},${wy}` : '';
      const isUserWalk = isManualClickData(d);
      const isScriptWalk = isScriptClickData(d);
      if (isUserWalk) return `Manual walk${wpos}`;
      if (isScriptWalk) return `Script walk${wpos}`;
      return `Walk${wpos}`;
    }
    default:
      if (Object.keys(d).length > 0) return `${type}: ${JSON.stringify(d)}`;
      return type;
  }
}

function isClickEventType(type) {
  return type === 'CLICK' || type === 'WALKHERE';
}

function isManualClickData(data) {
  if (!data) return false;
  return data.user_clicked === true ||
    data.click_source === 'user' ||
    data.initiated_by === 'user' ||
    data.input_origin === 'manual';
}

function isScriptClickData(data) {
  if (!data) return false;
  return data.script_clicked === true ||
    data.click_source === 'script' ||
    data.initiated_by === 'script' ||
    data.input_origin === 'script' ||
    data.input_origin === 'automation';
}

function hasClickProvenance(data) {
  return isManualClickData(data) || isScriptClickData(data);
}

/**
 * Normalize ActionLogger log format.
 * ActionLogger format: {tick, ts, type, data}
 * @param {Object} log - Raw action log
 * @returns {Object} Normalized log entry
 */
function normalizeActionLog(log, options = {}) {
  const data = log.data || null;
  const logType = log.logType || null;
  const provenanceMessage =
    data && isClickEventType(logType) && hasClickProvenance(data)
      ? formatEventFromTypeAndData(logType, data)
      : null;
  return {
    kind: 'action',
    level: String(log.level || 'INFO').toUpperCase(),
    message: provenanceMessage || (log.message ? String(log.message) : ''),
    source: log.source ? String(log.source) : 'external',
    timestamp: log.timestamp || null,
    logType, // DIALOGUE, INVENTORY, VARBIT, ANIMATION
    // Action rows without an explicit positive game tick remain unticked. Borrowing currentTick
    // makes unrelated asynchronous logs appear to have happened in the same tick.
    tick: Number.isFinite(Number(log.tick)) && Number(log.tick) > 0 ? Number(log.tick) : null,
    data, // Structured data
    clickSource: data?.click_source || data?.input_origin || null,
    userClicked: isManualClickData(data),
    scriptClicked: isScriptClickData(data)
  };
}

function connectLogWebSocket() {
  if (debuggerPageClosing) return;
  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${location.host}/ws/logs`;

  function connect() {
    if (debuggerPageClosing) return;
    logWebSocket = new WebSocket(wsUrl);

    logWebSocket.onopen = () => {
      console.log('[Debugger] Log WebSocket connected to', wsUrl);
      if (logWebSocketReconnectTimer) {
        clearTimeout(logWebSocketReconnectTimer);
        logWebSocketReconnectTimer = null;
      }
    };

    logWebSocket.onmessage = (event) => {
      if (currentMode !== 'live') return;
      handleLiveLogPayload(event.data);
    };

    logWebSocket.onclose = () => {
      logWebSocket = null;
      if (!debuggerPageClosing && !logWebSocketReconnectTimer) {
        logWebSocketReconnectTimer = setTimeout(() => {
          logWebSocketReconnectTimer = null;
          connect();
        }, 3000);
      }
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
  if (debuggerPageClosing) return;
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
  viewportWebSocket.binaryType = 'arraybuffer';

  viewportWebSocket.onopen = () => {
    console.log('Connected to viewport WebSocket');
  };

  viewportWebSocket.onmessage = async (event) => {
    if (currentMode !== 'live') return;
    elements.viewportLoader.style.display = 'none';
    elements.viewportCanvas.style.display = 'block';

    try {
      const frame = await parseViewportFramePayload(event.data);

      // Track highest seq seen
      if (frame.seq > latestSeq) latestSeq = frame.seq;

      // Drop immediately if already drawn
      if (frame.seq <= lastDrawnSeq) {
        if (window.DEBUG_FRAME_SEQ) {
          console.log(`[FRAME] Dropped frame ${frame.seq} - already drawn ${lastDrawnSeq}`);
        }
        return;
      }

      // Buffer the frame
      bufferFrame(frame);

      // Trigger drain (idempotent, safe to call always)
      drainFrameBuffer();
    } catch (error) {
      console.warn('Failed to parse viewport frame', error);
    }
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

async function parseViewportFramePayload(rawData) {
  if (rawData instanceof Blob) {
    return parseViewportFramePayload(await rawData.arrayBuffer());
  }

  if (rawData instanceof ArrayBuffer) {
    if (rawData.byteLength < 16) {
      throw new Error(`Viewport frame too small: ${rawData.byteLength}`);
    }

    const view = new DataView(rawData);
    const seq = Number(view.getBigUint64(0, false));
    const sourceWidth = view.getInt32(8, false);
    const sourceHeight = view.getInt32(12, false);
    const jpegBytes = rawData.slice(16);

    return {
      seq,
      blob: new Blob([jpegBytes], { type: 'image/jpeg' }),
      sourceWidth,
      sourceHeight
    };
  }

  const payload = JSON.parse(rawData);
  return {
    seq: payload.seq ?? ++latestSeq,
    dataUrl: payload.data,
    sourceWidth: payload.sourceWidth ?? 0,
    sourceHeight: payload.sourceHeight ?? 0
  };
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
async function decodeFrame(frame) {
  if (frame.blob) {
    if (window.createImageBitmap) {
      return await createImageBitmap(frame.blob);
    }
    return new Promise((resolve, reject) => {
      const img = new Image();
      const objectUrl = URL.createObjectURL(frame.blob);
      img.onload = () => {
        URL.revokeObjectURL(objectUrl);
        resolve(img);
      };
      img.onerror = (error) => {
        URL.revokeObjectURL(objectUrl);
        reject(error);
      };
      img.src = objectUrl;
    });
  }

  const dataUrl = frame.dataUrl;
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
  const sourceWidth = pendingSourceWidth;
  const sourceHeight = pendingSourceHeight;
  pendingBitmap = null;
  pendingSeq = 0;
  pendingSourceWidth = 0;
  pendingSourceHeight = 0;

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
  if (
    elements.viewportCanvas.width !== bitmap.width ||
    elements.viewportCanvas.height !== bitmap.height
  ) {
    elements.viewportCanvas.width = bitmap.width;
    elements.viewportCanvas.height = bitmap.height;
  }
  elements.viewportCanvas.dataset.sourceWidth = String(sourceWidth || bitmap.width);
  elements.viewportCanvas.dataset.sourceHeight = String(sourceHeight || bitmap.height);

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
function bufferFrame(frame) {
  const { seq } = frame;
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
  frameBuffer.push(frame);

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
  pendingSourceWidth = 0;
  pendingSourceHeight = 0;
  if (elements.viewportCanvas?.dataset) {
    delete elements.viewportCanvas.dataset.sourceWidth;
    delete elements.viewportCanvas.dataset.sourceHeight;
  }

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

    const bitmap = await decodeFrame(frame);

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
      pendingSourceWidth = frame.sourceWidth || bitmap.width;
      pendingSourceHeight = frame.sourceHeight || bitmap.height;

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
  if (debuggerPageClosing) return;
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
  const sourceWidth =
    Number(activeElement.dataset?.sourceWidth || 0) ||
    activeElement.width ||
    activeElement.videoWidth ||
    displayWidth;
  const sourceHeight =
    Number(activeElement.dataset?.sourceHeight || 0) ||
    activeElement.height ||
    activeElement.videoHeight ||
    displayHeight;

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
  if (debuggerPageClosing) return;
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

/** Detaches and closes the non-tick page sockets before unload. */
function closeDebuggerWebSockets() {
  if (logWebSocketReconnectTimer) {
    clearTimeout(logWebSocketReconnectTimer);
    logWebSocketReconnectTimer = null;
  }
  const sockets = [logWebSocket, viewportWebSocket, overlayWebSocket, botStateWebSocket];
  logWebSocket = null;
  viewportWebSocket = null;
  overlayWebSocket = null;
  botStateWebSocket = null;
  sockets.forEach((socket) => {
    if (!socket) return;
    socket.onopen = null;
    socket.onmessage = null;
    socket.onerror = null;
    socket.onclose = null;
    try {
      socket.close(1000, 'page unload');
    } catch (error) {
      // best-effort cleanup
    }
  });
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
        replayLoadGeneration++;
        if (replayLoadAbortController) {
          replayLoadAbortController.abort();
          replayLoadAbortController = null;
        }
        if (currentSyncAbortController) {
          currentSyncAbortController.abort();
          currentSyncAbortController = null;
        }
        currentRecording = null;
        renderFishingRecordingLabSelection();
        lastHighlightedTick = null;
        replayLogHistory.length = 0;
        replayTickSnapshots.clear();
        replaySnapshotOrder.length = 0;
        abortReplaySnapshotRequests();
        currentReplayDebugSessionId = null;
        if (typeof perTickSectionToggles !== 'undefined') {
          perTickSectionToggles.clear();
        }
        if (currentMode === 'replay') {
          renderLogHistory('Select a recording to view logs', { forceEmpty: true });
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

        // Throttle sync requests (AbortController handles cancellation).
        // A backward seek makes currentTimeMs - lastSyncTimeMs negative, which must
        // bypass normal forward-time throttling and sync immediately.
        const currentTimeMs = Math.floor(elements.replayVideo.currentTime * 1000);
        const timeSinceLastSync = currentTimeMs - lastSyncTimeMs;

        const syncInterval = elements.replayVideo.paused ? SYNC_INTERVAL_PAUSED_MS : SYNC_INTERVAL_PLAYING_MS;
        if (timeSinceLastSync < 0 || timeSinceLastSync >= syncInterval) {
          lastSyncTimeMs = currentTimeMs;
          syncLogsToVideo(currentTimeMs);
        }
      }
    });

    elements.replayVideo.addEventListener('seeked', () => {
      if (currentMode === 'replay' && currentRecording) {
        const currentTimeMs = Math.floor(elements.replayVideo.currentTime * 1000);
        lastSyncTimeMs = currentTimeMs;
        syncLogsToVideo(currentTimeMs);
      }
    });

    // The global handler below only updates scrubber dimensions; the per-recording
    // one-shot handler in loadRecording() performs the initial time-zero log sync.
    elements.replayVideo.addEventListener('loadedmetadata', () => {
      updateScrubberFromVideo();
    });
  }

  if (elements.scrubberRange) {
    // Track whether the browser emitted seeked for the last scrubber assignment.
    let scrubberSeekHandled = true;

    elements.scrubberRange.addEventListener('input', () => {
      if (currentMode !== 'replay') return;
      scrubberSeekHandled = false;
      elements.replayVideo.currentTime = Number(elements.scrubberRange.value);
      updateScrubberFromVideo();

      // When the browser does not emit seeked for this assignment (e.g. the value did
      // not change), fall back to a direct sync. The fallback is cancelled by seeked.
      window.setTimeout(() => {
        if (!scrubberSeekHandled && currentMode === 'replay' && currentRecording) {
          scrubberSeekHandled = true;
          const currentTimeMs = Math.floor(elements.replayVideo.currentTime * 1000);
          lastSyncTimeMs = currentTimeMs;
          syncLogsToVideo(currentTimeMs);
        }
      }, 80);
    });

    elements.replayVideo.addEventListener('seeked', () => {
      scrubberSeekHandled = true;
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
    // Load sessions first; health is fetched afterwards so it never blocks the dropdown.
    const recordingRes = await fetch(
      '/api/recordings/sessions?includeArchived=' + (fishingRecordingLab.showArchived ? 'true' : 'false')
    );
    if (!recordingRes.ok) {
      throw new Error('Failed to load sessions');
    }
    const sessions = await recordingRes.json();

    elements.sessionSelect.innerHTML = '<option value="">-- Select a recording session --</option>';

    // Build metadata map keyed by recordingId
    sessionMetadataMap = {};
    sessionPlayabilityMap = {};
    fishingRecordingLab.archivedRecordings.clear();

    sessions.forEach(session => {
      const option = document.createElement('option');
      option.value = session.recordingId;
      option.dataset.recordingId = session.recordingId;
      if (session.archivedAt) fishingRecordingLab.archivedRecordings.add(session.recordingId);

      // Store metadata for editor
      sessionMetadataMap[session.recordingId] = {
        displayName: session.displayName,
        replayName: session.replayName,
        replayTags: session.replayTags || []
      };

      // Track playability for loadRecording checks
      sessionPlayabilityMap[session.recordingId] = {
        playable: session.playable,
        status: session.status,
        videoError: session.videoError
      };

      const displayName = session.displayName || `${session.accountName || 'Unknown'}`;

      // Mark and disable unfinished recordings
      const playable = session.playable === true;
      const statusBadge = session.status && session.status !== 'FINALIZED'
        ? ` [${session.status === 'RECORDING' ? 'Recording' : 'Not finalized'}]`
        : ' [OK]';

      option.textContent = `${displayName}${statusBadge}${session.archivedAt ? ' · Archived' : ''}`;

      if (!playable) {
        option.disabled = true;
        option.title = session.videoError || 'Recording is not finalized or video is unavailable';
      }

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

    // Fetch health asynchronously without blocking the selector
    loadRecordingSessionHealth(sessions);
  } catch (error) {
    console.error('Failed to load recording sessions:', error);
    elements.sessionSelect.innerHTML = '<option value="">Error loading sessions</option>';
  }
}

/** Fetch recording health and apply badges to existing dropdown options. */
async function loadRecordingSessionHealth(sessions) {
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 3000);

    const debugRes = await fetch('/api/debug/replay/recording-health?limit=500', {
      signal: controller.signal
    });
    clearTimeout(timeoutId);

    if (!debugRes.ok) return;

    const debugSessions = await debugRes.json();
    sessionHealthMap = {};
    debugSessions.forEach(ds => {
      if (ds.recordingId) sessionHealthMap[ds.recordingId] = ds;
    });

    // Update option labels with health badges
    Array.from(elements.sessionSelect.options).forEach(option => {
      const recordingId = option.dataset.recordingId;
      if (!recordingId) return;
      const h = sessionHealthMap[recordingId];
      if (!h) return;

      const currentText = option.textContent;
      const baseText = currentText.split(' · ')[0];

      const eventPart = ` · Tick Events ${h.eventCount ?? 0}`;
      const failPart = (h.failureMarkerCount ?? 0) > 0 ? ` · Fail ${h.failureMarkerCount}` : '';
      option.textContent = `${baseText}${eventPart}${failPart}`;
    });
  } catch (error) {
    if (error.name !== 'AbortError') {
      console.warn('Failed to load recording health (non-blocking):', error);
    }
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

/**
 * Shows or clears the degraded-recording warning above the replay viewport.
 * @param {string|null} message warning text, or null to hide
 */
function setReplayVideoWarning(message) {
  const banner = elements.replayVideoWarning;
  if (!banner) return;
  if (message) {
    banner.textContent = message;
    banner.hidden = false;
  } else {
    banner.textContent = '';
    banner.hidden = true;
  }
}

async function loadRecording(recordingId) {
  // Start a new recording-load generation: any in-flight metadata/session response from an
  // earlier load must not repopulate replay state.
  const loadGeneration = ++replayLoadGeneration;
  if (replayLoadAbortController) {
    replayLoadAbortController.abort();
  }
  if (currentSyncAbortController) {
    currentSyncAbortController.abort();
    currentSyncAbortController = null;
  }
  const loadController = new AbortController();
  replayLoadAbortController = loadController;

  setReplayVideoWarning(null);

  // Clear ALL replay state immediately to prevent stale data
  currentRecording = null; // Clear first to stop ongoing syncs
  lastHighlightedTick = null;
  replayLogHistory.length = 0;
  replayTickSnapshots.clear(); // Clear tick snapshot map
  replaySnapshotOrder.length = 0;
  abortReplaySnapshotRequests();
  replaySnapshotTimeline.length = 0; // Clear snapshot timeline
  replaySessionStartTick = 0; // Reset session start tick
  replayTimelineScale = 1;
  currentReplayDebugSessionId = null;
  replayLoadedRanges.length = 0;
  replayTickWindowInFlight.length = 0;
  replayTickCardsUnavailable = false;
  replayTickCardCache.clear();
  if (typeof replayPacketTruncatedWindows !== 'undefined') {
    replayPacketTruncatedWindows.length = 0;
  }
  if (typeof replayPacketContinuationCursors !== 'undefined') {
    replayPacketContinuationCursors.clear();
  }
  if (typeof replayPacketLoadErrors !== 'undefined') {
    replayPacketLoadErrors.clear();
  }
  if (typeof replayPacketInFlight !== 'undefined') {
    replayPacketInFlight.clear();
  }
  if (typeof replayPacketSessionCache !== 'undefined') {
    replayPacketSessionCache.clear();
  }
  if (typeof ensureReplayPacketWindow !== 'undefined' && ensureReplayPacketWindow.loadedWindows) {
    ensureReplayPacketWindow.loadedWindows.clear();
  }
  if (typeof perTickSectionToggles !== 'undefined') {
    perTickSectionToggles.clear(); // Clear tick UI state
  }

  // Reset replay state manager
  if (replayTickStateManager) {
    replayTickStateManager.reset();
  }
  lastSyncTimeMs = 0; // Reset throttle

  if (currentMode === 'replay') {
    renderLogHistory('Loading recording logs…', { forceEmpty: true });
  }

  // After every await, a stale load must return without mutating state.
  const stale = () =>
    loadController.signal.aborted || loadGeneration !== replayLoadGeneration;

  let response;
  try {
    response = await fetch(`/api/recordings/${encodeURIComponent(recordingId)}`, {
      signal: loadController.signal
    });
  } catch (error) {
    if (error.name === 'AbortError') return false;
    console.error('Failed to load recording:', error);
    if (!stale()) {
      renderLogHistory('An error occurred while loading the recording: ' + error.message, { forceEmpty: true });
    }
    return false;
  }
  if (stale()) return false;

  // Handle HTTP errors
  if (!response.ok) {
    if (response.status === 404) {
      console.error('Recording not found:', recordingId);
      if (!stale()) {
        alert('Recording session not found. It may have been deleted.');
        renderLogHistory('Recording session not found. It may have been deleted.', { forceEmpty: true });
      }
    } else {
      console.error('Failed to load recording, status:', response.status);
      if (!stale()) {
        alert('Failed to load recording. Please try again.');
        renderLogHistory(`Failed to load recording (${response.status}).`, { forceEmpty: true });
      }
    }
    return false;
  }

  let recording;
  try {
    recording = await response.json();
  } catch (e) {
    console.error('Failed to parse recording metadata:', e);
    if (!stale()) {
      renderLogHistory('Could not read recording metadata.', { forceEmpty: true });
    }
    return false;
  }
  if (stale()) return false;

  currentRecordingSessionId = recording.id;
  currentRecordingFps = recording.fps || 30;

  console.log('Loaded recording metadata:', recording);

  // Refuse to load unplayable recordings
  if (recording.playable === false) {
    console.error('Recording is not playable:', recording.videoError);
    if (!stale()) {
      renderLogHistory(`Cannot load recording: ${recording.videoError || 'Recording not finalized'}`, { forceEmpty: true });
    }
    return false;
  }

  // Check for video errors (non-blocking)
  if (recording.videoError) {
    console.error('Video error for recording:', recording.videoError);
    if (!stale()) renderLogHistory(`Cannot load video: ${recording.videoError}`, { forceEmpty: true });
    return false;
  }

  // Validate videoUrl exists before committing currentRecording
  if (!recording.videoUrl) {
    console.error('No video URL in response:', recording);
    if (!stale()) {
      renderLogHistory('Video is not available. The recording may not have completed properly.', { forceEmpty: true });
    }
    return false;
  }

  // Commit currentRecording only after metadata fetch succeeded, the recording is playable,
  // the video URL exists, and this load is still the current generation.
  if (stale()) return false;
  currentRecording = recordingId;
  history.replaceState({}, '', `/debugger?recordingId=${encodeURIComponent(recordingId)}`);
  renderFishingRecordingLabSelection();

  if (recording.degraded) {
    setReplayVideoWarning(recording.videoWarning
      || 'This recording contains very few captured frames and may show a static image.');
  } else {
    setReplayVideoWarning(null);
  }

  // Load video
  if (elements.replayVideo) {
    console.log('Loading video from:', recording.videoUrl);

    // Attach a one-shot metadata handler before load() so the initial time-zero
    // log sync fires as soon as video metadata is available. The global
    // loadedmetadata handler still updates scrubber dimensions only.
    elements.replayVideo.addEventListener('loadedmetadata', () => {
      const recordingStartMs = Date.parse(recording.startTime);
      const recordingEndMs = Date.parse(recording.endTime);
      const mediaDurationMs = elements.replayVideo.duration * 1000;
      if (Number.isFinite(recordingStartMs)
        && Number.isFinite(recordingEndMs)
        && recordingEndMs > recordingStartMs
        && Number.isFinite(mediaDurationMs)
        && mediaDurationMs > 0) {
        replayTimelineScale = (recordingEndMs - recordingStartMs) / mediaDurationMs;
      }
      updateScrubberFromVideo();
      if (isCurrentReplayLoad(loadGeneration, recordingId)) {
        const currentTimeMs = Math.floor(elements.replayVideo.currentTime * 1000);
        lastSyncTimeMs = currentTimeMs;
        syncLogsToVideo(currentTimeMs);
      }
    }, { once: true });

    elements.replayVideo.src = recording.videoUrl;
    elements.replayVideo.load();

    // Add error handler for video element
    elements.replayVideo.onerror = (e) => {
      console.error('Video element failed to load:', e);
      if (isCurrentReplayLoad(loadGeneration, recordingId)) {
        renderLogHistory('Failed to load video file. The file may be corrupted or in an unsupported format.', { forceEmpty: true });
      }
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

  // Background: fetch debug session + initial tick window without blocking video playback
  (async () => {
    try {
      const sessionResponse = await fetch(
        `/api/debug/replay/sessions/by-recording?recordingId=${encodeURIComponent(recordingId)}`,
        { signal: loadController.signal }
      );
      if (stale()) return;
      if (sessionResponse.ok) {
        const session = await sessionResponse.json();
        if (!isCurrentReplayLoad(loadGeneration, recordingId)) return;
        currentReplayDebugSessionId = session.sessionId;
        console.log('Loaded debug session for replay:', currentReplayDebugSessionId);

        // Fetch session info to get tick bounds
        let sessionInfo = null;
        try {
          const infoResponse = await fetch(
            `/api/debug/replay/session-info/${encodeURIComponent(currentReplayDebugSessionId)}`,
            { signal: loadController.signal }
          );
          if (stale() || !isCurrentReplayLoad(loadGeneration, recordingId)) return;
          if (infoResponse.ok) {
            sessionInfo = await infoResponse.json();
            if (!isCurrentReplayLoad(loadGeneration, recordingId)) return;
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

        // Load a small initial window around the start tick
        if (sessionInfo && sessionInfo.minTick != null && sessionInfo.maxTick != null) {
          const minTick = sessionInfo.minTick;
          const maxTick = sessionInfo.maxTick;
          const initialEnd = Math.min(minTick + 150, maxTick);
          console.log(`Loading initial tick window: ticks ${minTick} to ${initialEnd}`);
          await loadReplayTickWindow(recordingId, minTick, initialEnd);
        } else {
          console.warn('No tick range available for windowed loading');
        }
      } else {
        console.warn('No debug session found for recording (old recording or session deleted)');
        if (isCurrentReplayLoad(loadGeneration, recordingId)) {
          currentReplayDebugSessionId = null;
        }
      }
    } catch (e) {
      if (e.name === 'AbortError') return;
      console.warn('Failed to fetch debug session:', e);
      if (isCurrentReplayLoad(loadGeneration, recordingId)) {
        currentReplayDebugSessionId = null;
      }
    }
  })();

  return true;
}

/**
 * Build replay history from tick cards loaded for a window.
 * This function is additive; it adds new tick cards to the cache without clearing prior data.
 * @param {Array} tickCards - Array of TickCardData from backend
 */
function buildReplayHistoryFromTickCards(tickCards) {
  if (window.DEBUG_TICK) {
    console.log('[Replay] Adding history from', tickCards.length, 'tick cards');
  }

  tickCards.forEach(card => {
    const tick = card.tick;

    // Skip already cached ticks to avoid duplicates on overlapping windows
    if (replayTickCardCache.has(tick)) return;
    replayTickCardCache.set(tick, card);

    // Store snapshot timeline (all ticks, even if no data, to maintain continuity)
    if (!replaySnapshotTimeline.includes(tick)) {
      replaySnapshotTimeline.push(tick);
    }

    // Normalize section data from TickCardData to snapshot shape
    const snapshot = {
      playerState: card.sectionData?.player || null,
      inventory: card.sectionData?.inventory || null,
      equipment: null,
      threats: card.sectionData?.npcs || null,
      projectiles: card.sectionData?.projectiles || null,
      cooldowns: null,
      quest: card.sectionData?.quest || null,
      questHelper: card.sectionData?.questHelper || null,
      npcs: card.sectionData?.npcs || null,
      gameObjects: card.sectionData?.objects || null,
      groundObjects: card.sectionData?.groundObjects || null,
      groundItems: card.sectionData?.groundItems || null,
      // Tick cards carry packet counts only; rows arrive through the paginated replay
      // packet API. Seed the count badge from the annotated range metadata until rows land.
      packets: [],
      packetCount: Number(card.sectionCounts?.packets ?? 0) || 0
    };

    replayTickSnapshots.set(tick, snapshot);

    if (window.DEBUG_TICK && tick % 10 === 0) {
      const hasData = Object.values(snapshot).some(v => v !== null);
      console.log(`[Replay] Tick ${tick}: ${hasData ? 'has data' : 'empty'}`);
    }
    // Note: tick-card rows intentionally do NOT enter replayLogHistory. Unified replay
    // responses are the only owner of replay log history; tick cards contribute
    // snapshots and section data only.
  });

  // Sort snapshot timeline
  replaySnapshotTimeline.sort((a, b) => a - b);

  const minTick = replaySnapshotTimeline.length > 0 ? replaySnapshotTimeline[0] : null;
  const maxTick = replaySnapshotTimeline.length > 0 ? replaySnapshotTimeline[replaySnapshotTimeline.length - 1] : null;
  console.log(`Built replay history: ${replayTickSnapshots.size} snapshots across ${replaySnapshotTimeline.length} ticks${minTick != null ? ` (${minTick}-${maxTick})` : ''}`);
}

/** Load a window of tick cards for the current replay and add them to the cache. */
async function loadReplayTickWindow(recordingId, startTick, endTick, options = {}) {
  if (!recordingId || startTick == null || endTick == null) return;
  if (replayTickCardsUnavailable) return;
  if (startTick > endTick) return;
  const forceReload = !!(options && options.forceReload);
  // A truncated packet window must reload on a narrower range even when tick cards are
  // cached: tick-card coverage otherwise suppresses the packet retry forever.
  const needsPacketRecovery = typeof replayPacketWindowNeedsRecovery === 'function'
    && replayPacketWindowNeedsRecovery(startTick, endTick);
  if (!forceReload && !needsPacketRecovery
      && (isReplayRangeLoaded(startTick, endTick) || isReplayRangeInFlight(startTick, endTick))) return;

  // Capture generation and abort signal to guard against stale responses
  // polluting a newly selected recording.
  const generation = replayLoadGeneration;
  const signal = replayLoadAbortController ? replayLoadAbortController.signal : null;

  console.log(`Loading tick window for ${recordingId}: ticks ${startTick}-${endTick}`);
  const requestToken = registerReplayInFlightRange(
    recordingId,
    startTick,
    endTick,
    generation
  );
  try {
    const fetchOptions = {};
    if (signal) fetchOptions.signal = signal;
    const response = await fetch(
      `/api/debug/replay/tick-cards/${recordingId}?start=${startTick}&end=${endTick}`,
      fetchOptions
    );

    // Validate generation and recording before mutating any state.
    if (!isCurrentReplayLoad(generation, recordingId)) {
      console.log(`Discarding stale tick-card response for recording ${recordingId} (gen ${generation})`);
      return;
    }

    if (!response.ok) {
      if (response.status === 404) {
        replayTickCardsUnavailable = true;
      }
      console.warn('Failed to load tick cards, status:', response.status);
      return;
    }
    const tickCards = await response.json();

    // Re-validate before mutating state (fetch could have taken time).
    if (!isCurrentReplayLoad(generation, recordingId)) return;

    buildReplayHistoryFromTickCards(tickCards);
    registerReplayLoadedRange(startTick, endTick);
    if (currentMode === 'replay' && currentRecording === recordingId) {
      tickCards.forEach(card => rerenderTickGroup(card.tick));
    }
    // Packet rows are not embedded in tick cards. Fetch the visible window so the
    // seeded packet counts resolve into rows; stale responses cancel via generation.
    // Recovery-driven reloads force the packet fetch even when tick cards were already
    // loaded: tick-card coverage otherwise suppresses the narrower retry.
    if (typeof ensureReplayPacketWindow === 'function') {
      ensureReplayPacketWindow(recordingId, startTick, endTick,
        needsPacketRecovery ? { forceReload: true } : undefined);
    }
    console.log(`Loaded ${tickCards.length} tick cards for ticks ${startTick}-${endTick}`);
  } catch (e) {
    if (e.name === 'AbortError') {
      console.log(`Tick-card request for ${recordingId} was aborted`);
    } else {
      console.error('Error loading tick cards:', e);
    }
  } finally {
    unregisterReplayInFlightRange(requestToken);
  }
}

/** Ensure the ticks around the current video position are loaded. */
async function ensureReplayTickWindow(recordingId, currentTick) {
  if (!recordingId || currentTick == null || isNaN(currentTick)) return;
  if (replayTickCardsUnavailable) return;
  const start = Math.max(0, currentTick - 50);
  const end = currentTick + 150;
  if (isReplayRangeLoaded(start, end)) return;
  await loadReplayTickWindow(recordingId, start, end);
}

/** Check if a range is fully covered by already-loaded ranges. */
function isReplayRangeLoaded(start, end) {
  return replayLoadedRanges.some(r => r.start <= start && r.end >= end);
}

/** Check if a range overlaps an in-flight request. */
function isReplayRangeInFlight(start, end) {
  return replayTickWindowInFlight.some(r => r.start <= end && r.end >= start);
}

function registerReplayInFlightRange(recordingId, start, end, generation) {
  const requestToken = {
    id: ++replayTickWindowRequestSequence,
    recordingId,
    generation,
    start,
    end
  };
  replayTickWindowInFlight.push(requestToken);
  return requestToken;
}

function unregisterReplayInFlightRange(requestToken) {
  const index = replayTickWindowInFlight.indexOf(requestToken);
  if (index >= 0) {
    replayTickWindowInFlight.splice(index, 1);
  }
}

/** Register a fetched range and merge with existing loaded ranges. */
function registerReplayLoadedRange(start, end) {
  replayLoadedRanges.push({ start, end });
  replayLoadedRanges.sort((a, b) => a.start - b.start);
  const merged = [];
  for (const range of replayLoadedRanges) {
    if (merged.length === 0 || range.start > merged[merged.length - 1].end + 1) {
      merged.push(range);
    } else {
      merged[merged.length - 1].end = Math.max(merged[merged.length - 1].end, range.end);
    }
  }
  replayLoadedRanges.length = 0;
  replayLoadedRanges.push(...merged);
}

/**
 * Calculate current tick from video time using snapshot timeline.
 * Uses snapshot timeline as authoritative source if available.
 * @param {number} videoMs - Current video time in milliseconds
 * @returns {number|null} - Calculated tick or null if unable to determine
 */
function calculateTickFromVideoTime(videoMs) {
  videoMs *= replayTimelineScale;
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
  const recordingId = currentRecording;
  const generation = replayLoadGeneration;
  const sessionId = currentReplayDebugSessionId;
  if (!recordingId || !sessionId || tick === null || tick === undefined) return;
  if (replayTickSnapshots.has(tick)) return; // Already cached
  if (replaySnapshotFetchInFlight.has(tick)) return; // Avoid overlapping requests

  const requestToken = {
    recordingId,
    generation,
    sessionId,
    tick,
    controller: new AbortController()
  };
  replaySnapshotFetchInFlight.set(tick, requestToken);
  try {
    const response = await fetch(`/api/debug/replay/tick/${sessionId}/${tick}`, {
      signal: requestToken.controller.signal
    });
    if (response.ok) {
      const data = await response.json();
      if (replaySnapshotFetchInFlight.get(tick) === requestToken
        && isCurrentReplayLoad(generation, recordingId)
        && currentReplayDebugSessionId === sessionId
        && data.snapshot) {
        const raw = data.snapshot;
        const normalized = {
          playerState: raw.playerState,
          inventory: raw.inventory ?? null,
          equipment: raw.equipment ?? null,
          threats: raw.threats ?? null,
          projectiles: raw.projectiles ?? null,
          cooldowns: raw.cooldowns ?? null,
          quest: raw.quest ?? null,
          questHelper: raw.questHelper ?? null,
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
    if (error.name === 'AbortError') return;
    console.warn('Failed to fetch replay snapshot:', error);
  } finally {
    if (replaySnapshotFetchInFlight.get(tick) === requestToken) {
      replaySnapshotFetchInFlight.delete(tick);
    }
  }
}

async function syncLogsToVideo(videoMs) {
  if (!currentRecording) return;
  if (currentMode !== 'replay') return;

  const requestedRecordingId = currentRecording;
  const requestedGeneration = replayLoadGeneration;
  const requestedVideoMs = Math.floor(videoMs * replayTimelineScale);

  // Abort the preceding replay-log request before starting a new one
  if (currentSyncAbortController) {
    currentSyncAbortController.abort();
  }
  const abortController = new AbortController();
  const requestToken = {
    controller: abortController,
    generation: requestedGeneration,
    recordingId: requestedRecordingId,
    videoMs: requestedVideoMs
  };
  currentSyncAbortController = abortController;

  try {
    if (window.DEBUG_TICK) {
      console.log(`[Replay] Unified sync for ${requestedRecordingId} at ${requestedVideoMs}ms`);
    }

    const response = await fetch(
      `/api/debug/replay/unified?recordingId=${encodeURIComponent(requestedRecordingId)}` +
      `&videoTimeMs=${requestedVideoMs}&windowSeconds=3`,
      { signal: abortController.signal }
    );

    // Keep ownership through response parsing and rendering. A newer seek can arrive after
    // headers but before JSON parsing, so releasing the slot here would allow stale results.
    if (!isCurrentReplaySync(requestToken)) return;

    if (!response.ok) {
      // Non-destructive error state; video playback stays usable
      console.error('Failed to fetch unified replay logs, status:', response.status);
      renderReplayLogError();
      return;
    }

    const result = await response.json();

    // Stale-response protection: mode, generation, recording, and response ownership must
    // all still match the request that was made.
    if (!isCurrentReplaySync(requestToken)
      || result.recordingId !== requestedRecordingId) {
      return;
    }

    if (window.DEBUG_TICK) {
      console.log(`[Replay] Unified response for ${requestedRecordingId}: ` +
        `currentTick=${result.currentTick}, logs=${result.logs ? result.logs.length : 0}`);
    }

    const currentTick = result.currentTick != null ? result.currentTick : null;

    // Render first: rebuilding the log DOM invalidates the groups that highlighting and
    // auto-follow need to target. Following before this call silently scrolled stale elements.
    renderReplayLogs(result.logs || [], currentTick, result.hasAnyLogs !== false);

    if (currentTick != null) {
      updateTickDisplay(currentTick);
      highlightCurrentTickGroup(currentTick);
      maybeAutoFollowTick(currentTick);
    }

    // Load nearby tick snapshots for the authoritative server tick
    if (currentTick != null) {
      ensureReplayTickWindow(requestedRecordingId, currentTick);
    }

  } catch (error) {
    if (error.name === 'AbortError') {
      return; // Expected control flow: superseded by a newer request
    }
    if (!isCurrentReplaySync(requestToken)) return;
    console.error('Failed to sync replay logs:', error);
    renderReplayLogError();
  } finally {
    if (currentSyncAbortController === abortController) {
      currentSyncAbortController = null;
    }
  }
}

function renderReplayLogError() {
  if (currentMode !== 'replay' || !currentRecording) return;
  // Retain the last successfully rendered window when it exists; otherwise show a placeholder.
  if (replayLogHistory.length === 0) {
    ensureLogPlaceholder('Could not load recording logs');
  } else {
    showNotification('Could not load recording logs', 'error');
  }
}

function getReplayLogIdentity(entry) {
  if (entry.id != null && entry.id !== undefined && entry.id !== '') {
    return `${entry.origin || 'log'}:${entry.id}`;
  }
  return [
    entry.origin || 'log',
    entry.timestamp || '',
    entry.tick != null ? entry.tick : '',
    entry.source || entry.subsystem || '',
    entry.logType || entry.eventType || '',
    entry.message || ''
  ].join('|');
}

function isCurrentReplaySync(requestToken) {
  return currentMode === 'replay'
    && currentSyncAbortController === requestToken.controller
    && replayLoadGeneration === requestToken.generation
    && currentRecording === requestToken.recordingId;
}

function normalizeReplayUnifiedLog(log) {
  const origin = log.origin || 'standard';
  const base = {
    origin: origin,
    id: log.id != null ? log.id : null,
    tick: log.tick != null ? log.tick : null,
    level: log.level || 'INFO',
    source: log.source ? String(log.source) : 'unknown',
    timestamp: log.timestamp || null,
    logType: log.logType || null
  };

  if (origin === 'tick') {
    return {
      ...base,
      kind: 'tick',
      subsystem: log.source || 'tick',
      message: log.message ? String(log.message) : '',
      data: safeParseStructuredData(log.structuredData)
    };
  }

  if (origin === 'external' || log.structuredData || log.logType) {
    return {
      ...base,
      kind: 'action',
      message: log.message || log.logType || log.type || '',
      data: safeParseStructuredData(log.structuredData)
    };
  }

  return {
    ...base,
    kind: 'standard',
    message: log.message ? String(log.message) : '',
    data: safeParseStructuredData(log.structuredData)
  };
}

function renderReplayLogs(logs, currentReplayTick = null, hasAnyLogs = true) {
  if (currentMode !== 'replay') return;

  replayLogHistory.length = 0;
  const seen = new Set();
  const uniqueLogs = [];

  if (Array.isArray(logs)) {
    logs.forEach(log => {
      const key = getReplayLogIdentity(log);
      if (seen.has(key)) return;
      seen.add(key);
      uniqueLogs.push(log);
    });
  }

  if (uniqueLogs.length === 0) {
    // Quiet timeline window: still render the current tick group with its snapshot,
    // then append the explicit quiet-window message.
    if (currentReplayTick != null) {
      const outputContainer = elements.logsContainer;
      if (outputContainer) {
        outputContainer.innerHTML = '';
        const tickGroup = buildTickGroup(currentReplayTick, []);
        outputContainer.appendChild(tickGroup);
        maybeFetchReplaySnapshot(currentReplayTick);
        highlightCurrentTickGroup(currentReplayTick);
        const quiet = document.createElement('div');
        quiet.className = 'text-muted fst-italic p-3';
        quiet.textContent = hasAnyLogs === false
          ? 'No logs were captured for this recording'
          : 'No logs for this time period';
        outputContainer.appendChild(quiet);
      }
      return;
    }
    // Selected recording, successful response, no logs and no tick near current time
    renderLogHistory(
      hasAnyLogs === false
        ? 'No logs were captured for this recording'
        : 'No logs for this time period',
      { forceEmpty: true }
    );
    return;
  }

  uniqueLogs.forEach(log => {
    const origin = log.origin || 'standard';
    const entry = normalizeReplayUnifiedLog(log);
    let normalized;
    if (entry.kind === 'tick') {
      // Unified replay data carries the persisted tick; never fabricate one.
      normalized = normalizeTickLog(entry, { allowTickFallback: false });
    } else if (entry.kind === 'action') {
      normalized = normalizeActionLog(entry, { allowTickFallback: false });
    } else {
      normalized = normalizeStandardLog(entry, { allowTickFallback: false });
    }
    normalized.origin = origin;
    normalized.id = entry.id;
    replayLogHistory.push(normalized);
  });

  // Rebuild source discovery from the current replay window. Counts are recomputed per
  // response (windowed history), so overlapping requests never accumulate. Stale chips for
  // sources no longer in the window are removed by the rebuild below.
  discoveredSources.clear();
  sourceLogTypes.clear();
  discoveredPatterns.clear();
  replayLogHistory.forEach(entry => {
    // Keep replay source handling identical to live handling. The persisted transport source is
    // authoritative; bracketed message prefixes are component/state labels, not new sources.
    const actualSource = entry.source || 'unknown';
    entry.source = actualSource;

    if (actualSource) {
      const logKind = entry.kind === 'tick'
        ? 'tick'
        : (isSystemSource(entry.source) ? 'standard' : 'external');
      discoverSource(actualSource, logKind);
    }
    discoverPatterns(entry.message);
  });
  // Rebuild the chip DOM without touching hidden-source preferences.
  const sourceFiltersEl = document.getElementById('sourceFilters');
  if (sourceFiltersEl) {
    sourceFiltersEl.innerHTML = '';
    discoveredSources.forEach((count, source) => {
      if (!sourceLogTypes.has(source)) return;
      updateSourceChip(source);
    });
  }
  updateSourceFilterSummary();

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

  renderRecoveryWorkspacePanes();
}

function renderRecoveryWorkspacePanes() {
  if (!document.body.classList.contains('recovery-mode-active')) {
    return;
  }
  renderRecoveryRawLogs();
}

/**
 * Render raw plain-text log lines to the recovery raw-logs container.
 */
function renderRecoveryRawLogs() {
  const container = elements.recoveryRawLogsContainer;
  if (!container) return;

  const lines = [];
  replayLogHistory.forEach(entry => {
    const tick = entry.tick != null ? String(entry.tick).padStart(6, ' ') : '      ';
    const source = (entry.source || '').padEnd(18).slice(0, 18);
    const level = (entry.level || 'INFO').padEnd(5);
    const message = entry.message || '';
    const levelClass = `raw-level-${(entry.level || 'INFO').toUpperCase()}`;
    lines.push(
      `<span class="recovery-raw-log-line">` +
      `<span class="raw-tick">[${tick}]</span> ` +
      `<span class="raw-source">[${escapeHtml(source)}]</span> ` +
      `<span class="${levelClass}">[${escapeHtml(level)}]</span> ` +
      `${escapeHtml(message)}` +
      `</span>`
    );
  });

  container.innerHTML = lines.join('\n') || '<span class="text-muted fst-italic">No raw log entries</span>';

  if (elements.recoveryRawLogsMeta) {
    elements.recoveryRawLogsMeta.textContent = `${lines.length} raw lines`;
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

  // Find exact group or nearest by numeric distance.
  let target = container.querySelector(`.tick-group[data-tick="${tick}"]`);
  if (!target) {
    let minDiff = Infinity;
    const allGroups = container.querySelectorAll('.tick-group[data-tick]');
    allGroups.forEach(g => {
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
  const behavior = (isLive || isPlaying) ? 'auto' : 'smooth';
  if (window.DEBUG_TICK) {
    console.log('[AutoFollow] scrollToCurrentTick tick:', tick, 'behavior:', behavior, 'isLive:', isLive);
  }

  // scrollIntoView() also scrolls ancestor containers, including the document. During live tick
  // updates that made the page scrollbar jump up and down even though only the log pane should
  // follow the current tick. Compute the smallest pane-local adjustment instead.
  const containerRect = container.getBoundingClientRect();
  const targetRect = target.getBoundingClientRect();
  let nextScrollTop = container.scrollTop;
  if (targetRect.top < containerRect.top) {
    nextScrollTop += targetRect.top - containerRect.top;
  } else if (targetRect.bottom > containerRect.bottom) {
    nextScrollTop += targetRect.bottom - containerRect.bottom;
  } else {
    return;
  }
  container.scrollTo({ top: Math.max(0, nextScrollTop), behavior });
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
  renderRecoveryWorkspacePanes();
}

// ==================== WINDOW SELECTION ====================

let currentWindowId = '';
let currentWindowAccountName = '';
let pendingWindowSessionRebind = false;

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
      updateSelectedWindowSelection('', 'Auto-detect', '', false);
      return;
    }
    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    const window = await response.json();
    const displayName = window.accountName || window.title;
    updateSelectedWindowSelection(window.id, displayName, window.accountName || '', false);
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

function normalizeWindowAccountName(value) {
  return value ? String(value).trim().toLowerCase() : '';
}

function resetLiveDebugSessionForWindowChange(displayName) {
  lastProcessedSessionCreatedKey = null;
  ignoredSessionCreatedCount = 0;
  currentDebugSessionId = null;
  currentDebugSession = null;
  isDebugSessionActive = false;
  pendingWindowSessionRebind = true;

  if (liveTickStateManager) {
    liveTickStateManager.reset();
  }
  currentTick = null;
  updateTickDisplay(null);
  clearLiveLogHistory();
  clearOverlayCanvas();

  // Account/window session rebind: clear the shadow panel until a new session is detected.
  fishingShadowSwitchSession(null);

  if (elements.debugSessionStatus) {
    elements.debugSessionStatus.classList.add('d-none');
  }
  if (elements.startDebugSessionBtn) {
    elements.startDebugSessionBtn.disabled = false;
    elements.startDebugSessionBtn.classList.remove('btn-outline-secondary');
    elements.startDebugSessionBtn.classList.add('btn-outline-success');
  }

  console.log('[Debugger] Window changed; waiting for tick session from', displayName || 'Auto-detect');
}

function updateSelectedWindowSelection(id, displayName, accountName, resetDebugSession) {
  const previousWindowId = currentWindowId || '';
  const previousAccountName = currentWindowAccountName || '';

  updateSelectedWindowLabel(id, displayName);
  currentWindowAccountName = accountName || (id ? displayName : '');

  const changed =
    previousWindowId !== (id || '') ||
    normalizeWindowAccountName(previousAccountName) !== normalizeWindowAccountName(currentWindowAccountName);

  if (resetDebugSession && changed && currentMode === 'live') {
    resetLiveDebugSessionForWindowChange(displayName);
  }
}

function sessionCreatedMatchesSelectedWindow(data) {
  if (!currentWindowId || !currentWindowAccountName) {
    return true;
  }

  const selectedAccount = normalizeWindowAccountName(currentWindowAccountName);
  const sessionAccount = normalizeWindowAccountName(data.accountName);
  return !sessionAccount || selectedAccount === sessionAccount;
}

function buildSessionCreatedKey(data) {
  const core = typeof DebuggerSessionSyncCore !== 'undefined' ? DebuggerSessionSyncCore : null;
  if (core) {
    return core.sessionCreatedKey(
      currentWindowId || '',
      data && data.accountName ? data.accountName : currentWindowAccountName,
      data && data.sessionId,
      data && data.shadowRunId
    );
  }
  return [
    currentWindowId || '',
    normalizeWindowAccountName(data && data.accountName ? data.accountName : currentWindowAccountName),
    data && data.sessionId ? data.sessionId : '',
    data && data.shadowRunId ? data.shadowRunId : ''
  ].join('|');
}

function noteIgnoredSessionCreated(data) {
  ignoredSessionCreatedCount++;
  const now = Date.now();
  if (now - lastIgnoredSessionSummaryAt >= 60000) {
    lastIgnoredSessionSummaryAt = now;
    console.info(
      '[Debugger] Ignored SESSION_CREATED events while debug feed is disabled:',
      ignoredSessionCreatedCount,
      'latest session:',
      data && data.sessionId ? data.sessionId : '(unknown)'
    );
    ignoredSessionCreatedCount = 0;
  }
}

function shouldRenderLiveTickBatch(data) {
  if (!data || !data.sessionId) {
    return !pendingWindowSessionRebind;
  }
  if (pendingWindowSessionRebind) {
    return false;
  }
  return !currentDebugSessionId || data.sessionId === currentDebugSessionId;
}

async function selectWindowInternal(windowId, displayName) {
  try {
    const response = await fetch('/api/windows/select', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: windowId })
    });

    if (!response.ok) throw new Error(`HTTP ${response.status}`);

    updateSelectedWindowSelection(windowId, displayName || 'Auto-detect', displayName || '', true);
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
let tickStreamReconnectTimer = null;
let tickStreamConnectionGeneration = 0;
let tickStreamPageClosing = false;
let tickStreamLastErrorAt = 0;
let tickStreamLastParseErrorAt = 0;
let isPlaying = false;
let playbackSpeed = 1;
let playbackInterval = null;

/**
 * Connect to tick stream WebSocket for live tick updates.
 */
function connectTickStreamWebSocket() {
  if (tickStreamPageClosing) return;

  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${location.host}/ws/debugger/tick-stream`;
  const generation = ++tickStreamConnectionGeneration;

  if (tickStreamReconnectTimer) {
    clearTimeout(tickStreamReconnectTimer);
    tickStreamReconnectTimer = null;
  }

  const previousSocket = tickStreamWebSocket;
  tickStreamWebSocket = null;
  if (previousSocket) {
    // Detach lifecycle callbacks before closing so an intentional replacement cannot schedule a
    // second reconnect when the old socket reports onclose asynchronously.
    previousSocket.onopen = null;
    previousSocket.onmessage = null;
    previousSocket.onerror = null;
    previousSocket.onclose = null;
    try {
      previousSocket.close(1000, 'replaced');
    } catch (error) {
      // A socket that never completed its handshake may reject close(); it is already detached.
    }
  }

  const socket = new WebSocket(wsUrl);
  tickStreamWebSocket = socket;

  socket.onopen = () => {
    if (generation !== tickStreamConnectionGeneration || socket !== tickStreamWebSocket) return;
    console.log('Connected to tick stream WebSocket');
    // A disabled feed must not restart shadow requests merely because the browser transport
    // recovered. The next accepted SESSION_CREATED event will bind the panel when re-enabled.
    if (debugFeedEnabled) {
      fishingShadowLoadStatus();
      fishingShadowLoadHistory();
    }
  };

  socket.onmessage = (event) => {
    if (generation !== tickStreamConnectionGeneration || socket !== tickStreamWebSocket) return;
    try {
      const data = JSON.parse(event.data);

      // Handle AI_PLAYER_SHADOW_DECISION broadcasts from the fishing shadow agent.
      if (data.type === 'AI_PLAYER_SHADOW_DECISION') {
        if (!debugFeedEnabled) return;
        handleShadowDecision(data);
        return;
      }

      // Handle SESSION_CREATED broadcast from Micro6
      if (data.type === 'SESSION_CREATED') {
        if (!sessionCreatedMatchesSelectedWindow(data)) {
          // Session broadcasts are fan-out notifications. A busy Micro6 account that is not the
          // selected debugger window must not turn into one console entry per reconnect.
          return;
        }

        const sessionCreatedKey = buildSessionCreatedKey(data);
        const syncCore = typeof DebuggerSessionSyncCore !== 'undefined'
          ? DebuggerSessionSyncCore
          : null;
        if (syncCore
          ? syncCore.isDuplicateSessionCreated(lastProcessedSessionCreatedKey, sessionCreatedKey)
          : lastProcessedSessionCreatedKey === sessionCreatedKey) {
          // Duplicate broadcasts are expected while multiple debugger consumers are connected;
          // they must not reset state or restart any request.
          return;
        }

        if (!debugFeedEnabled) {
          // Treat an ignored broadcast as seen for deduplication purposes. Reconnects may repeat
          // the same SESSION_CREATED tuple while the feed is disabled; only the first contributes
          // to the throttled diagnostic summary. Re-enabling the feed clears this key above.
          lastProcessedSessionCreatedKey = sessionCreatedKey;
          noteIgnoredSessionCreated(data);
          return;
        }
        lastProcessedSessionCreatedKey = sessionCreatedKey;

        // Update UI to use Micro6's sessionId
        if (data.sessionId) {
          currentDebugSessionId = data.sessionId;
          currentDebugSession = data.sessionId;
          isDebugSessionActive = true;
          pendingWindowSessionRebind = false;
          if (elements.debugSessionStatus) {
            elements.debugSessionStatus.classList.remove('d-none');
          }
          console.log('✅ Micro6 session detected:', data.sessionId, 'run:', data.shadowRunId || 'unknown');
          // A new session/run must never inherit the previous session's shadow tick baseline.
          fishingShadowSwitchSession(data.sessionId, data.shadowRunId || null);
        }
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
      if (!shouldRenderLiveTickBatch(data)) {
        if (window.DEBUG_TICK) {
          console.log(
            '[Tick Debug] Ignoring tick data for inactive session:',
            data.sessionId,
            'active:',
            currentDebugSessionId,
            'pendingRebind:',
            pendingWindowSessionRebind
          );
        }
        return;
      }
      if (window.DEBUG_TICK) console.log('[Tick Debug] Received tick data:', data);
      withLiveLogScrollAnchor(() => updatePanelsForTick(data));
    } catch (e) {
      const now = Date.now();
      if (now - tickStreamLastParseErrorAt >= 30000) {
        tickStreamLastParseErrorAt = now;
        console.error('Failed to parse tick stream message');
      }
    }
  };

  socket.onerror = () => {
    if (generation !== tickStreamConnectionGeneration || socket !== tickStreamWebSocket) return;
    const now = Date.now();
    if (now - tickStreamLastErrorAt >= 30000) {
      tickStreamLastErrorAt = now;
      console.error('Tick stream WebSocket transport error');
    }
  };

  socket.onclose = () => {
    if (generation !== tickStreamConnectionGeneration || socket !== tickStreamWebSocket) return;
    tickStreamWebSocket = null;
    if (tickStreamPageClosing || tickStreamReconnectTimer) return;
    // Reconnect after 3 seconds. Keep one timer per socket generation so repeated close/error
    // callbacks cannot create an unbounded browser-side connection storm.
    tickStreamReconnectTimer = setTimeout(() => {
      tickStreamReconnectTimer = null;
      if (!tickStreamPageClosing && generation === tickStreamConnectionGeneration && !tickStreamWebSocket) {
        connectTickStreamWebSocket();
      }
    }, 3000);
  };
}

function closeTickStreamWebSocket() {
  tickStreamPageClosing = true;
  tickStreamConnectionGeneration++;
  fishingShadowAbortPendingRequests();
  if (tickStreamReconnectTimer) {
    clearTimeout(tickStreamReconnectTimer);
    tickStreamReconnectTimer = null;
  }
  const socket = tickStreamWebSocket;
  tickStreamWebSocket = null;
  if (socket) {
    socket.onopen = null;
    socket.onmessage = null;
    socket.onerror = null;
    socket.onclose = null;
    try {
      socket.close(1000, 'page unload');
    } catch (error) {
      // best-effort cleanup
    }
  }
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

    case 'QUEST_HELPER_STEP_CHANGED':
    case 'QUEST_HELPER_BLOCKED':
    case 'QUEST_HELPER_TARGET_CHANGED':
    case 'QUEST_HELPER_REQUIREMENT_CHANGED':
      return formatQuestHelperEvent(event);

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
      questHelper: state.questHelper || state.quest_helper,
      npcs: state.npcs,
      gameObjects: state.objects || state.gameObjects,
      groundObjects: state.groundObjects,
      groundItems: state.groundItems,
      varbits: state.varbits,
      packets: Array.isArray(data.packets) ? data.packets.slice(-MAX_PACKETS_PER_TICK_SNAPSHOT) : [],
      transport: data.transport || null
    };
    // Partition by packet.tick so multi-tick batches never duplicate rows under
    // the enclosing batch tick; the primary snapshot keeps only its own packets.
    // Pre-register first: distributePacketsToTicks partitions against the store.
    (currentMode === 'live' ? liveTickSnapshots : replayTickSnapshots).set(tick, snapshot);
    distributePacketsToTicks(snapshot.packets, tick);
    snapshot.packets = snapshot.packets.filter(
      p => (p && Number.isFinite(Number(p.tick)) ? Number(p.tick) : tick) === tick);

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
        }
        // Replay mode: tick-stream rows belong to the live tick feed, not the unified
        // replay window; they are intentionally not appended to replayLogHistory.
      });

      // Re-render logs to show new events
      if (currentMode === 'live') {
        renderLogHistory();
        renderRecoveryWorkspacePanes();
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
      questHelper: data.questHelper || data.snapshot?.questHelper,
      npcs: data.npcs || data.snapshot?.npcs,
      gameObjects: data.gameObjects || data.snapshot?.gameObjects,
      groundObjects: data.groundObjects || data.snapshot?.groundObjects,
      groundItems: data.groundItems || data.snapshot?.groundItems,
      packets: cappedPackets(data.packets || data.snapshot?.packets),
      packetTruncated: !!data.packetTruncated,
      packetLoadError: data.packetLoadError || null,
      transport: data.transport || data.snapshot?.transport || null
    };
    // Same partition as the new format: primary keeps only its own packets.
    (currentMode === 'live' ? liveTickSnapshots : replayTickSnapshots).set(tick, snapshot);
    distributePacketsToTicks(snapshot.packets, tick);
    snapshot.packets = snapshot.packets.filter(
      p => (p && Number.isFinite(Number(p.tick)) ? Number(p.tick) : tick) === tick);

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
  let timeStr = '--:--.-';
  if (currentTimeEl) {
    if (tick != null) {
      const timeSeconds = (tick * 0.6).toFixed(1);
      timeStr = formatTickTime(timeSeconds);
      currentTimeEl.textContent = timeStr;
    } else {
      currentTimeEl.textContent = '--:--.-';
    }
  }

  // Calculate frame - use recording FPS if available, else default 30
  const currentFrameEl = document.getElementById('currentFrame');
  let frameStr = '--';
  if (currentFrameEl) {
    if (tick != null) {
      const fps = currentRecordingFps || 30;
      const frame = Math.floor(tick * 0.6 * fps);
      frameStr = String(frame);
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
  const position = getPlayerPosition(player);
  const hpDisplay = getPlayerHp(player);
  const prayerDisplay = getPlayerPrayer(player);
  const energyDisplay = getPlayerEnergy(player);
  const animDisplay = getPlayerAnimation(player);

  const items = [
    { label: 'Position', value: position.x !== undefined && position.y !== undefined
        ? `(${position.x}, ${position.y}, ${position.plane ?? 0})` : '--' },
    { label: 'HP', value: hpDisplay || '--' },
    { label: 'Prayer', value: prayerDisplay || '--' },
    { label: 'Animation', value: animDisplay || '--' },
    { label: 'Energy', value: energyDisplay || '--' },
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
    // The tick stream is a live feed; unified replay responses are the only owner of
    // replayLogHistory, so replay-mode tick-stream rows are not rendered here.
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

  const sessionId = currentDebugSession;
  const directSession = currentMode === 'replay' && !currentRecording;
  const generation = ++directSessionJumpGeneration;
  try {
    const response = await fetch(`/api/debug/replay/tick/${encodeURIComponent(sessionId)}/${tick}`);
    if (!response.ok) return;

    const data = await response.json();
    if (directSession) {
      try {
        const pageResult = await replayRecoveryCore.collectPacketPages(async cursor => {
          const params = new URLSearchParams({
            startTick: String(tick), endTick: String(tick), limit: '500'
          });
          if (cursor) params.set('cursor', cursor);
          const packetResponse = await fetch(
            `/api/debug/replay/packets/${encodeURIComponent(sessionId)}?${params}`);
          if (!packetResponse.ok) throw new Error(`Packet request failed (${packetResponse.status})`);
          return packetResponse.json();
        });
        data.packets = pageResult.packets;
        data.packetTruncated = pageResult.truncated
          || pageResult.packets.length > MAX_PACKETS_PER_TICK_SNAPSHOT;
      } catch (error) {
        data.packets = [];
        data.packetLoadError = error?.message || 'Packet request failed';
      }
      if (generation !== directSessionJumpGeneration || currentDebugSession !== sessionId
          || currentMode !== 'replay' || currentRecording) return;
    }
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
    if (currentMode !== 'replay') switchMode('replay');
    directSessionJumpGeneration++;
    const loadGeneration = ++replayLoadGeneration;
    stopTickPlayback();
    if (replayLoadAbortController) {
      replayLoadAbortController.abort();
      replayLoadAbortController = null;
    }
    currentDebugSession = null;
    currentRecording = null;
    if (elements.sessionSelect) elements.sessionSelect.value = '';
    if (elements.replayVideo) {
      elements.replayVideo.pause();
      elements.replayVideo.removeAttribute('src');
      elements.replayVideo.load();
    }
    setReplayVideoWarning('This debug session has no video recording; showing tick and packet replay.');
    // Clear previous session state
    replayTickSnapshots.clear();
    perTickSectionToggles.clear();
    replayLogHistory.length = 0;
    clearLogs();

    // Get session info
    const sessionResponse = await fetch(
      `/api/debug/replay/session-info/${encodeURIComponent(sessionId)}`);
    if (loadGeneration !== replayLoadGeneration) return false;
    if (!sessionResponse.ok) {
      setReplayVideoWarning('Debug session could not be loaded.');
      return false;
    }

    const sessionInfo = await sessionResponse.json();
    if (loadGeneration !== replayLoadGeneration) return false;
    currentDebugSession = sessionId;
    currentReplayDebugSessionId = sessionId;
    history.replaceState({}, '', `/debugger?sessionId=${encodeURIComponent(sessionId)}&mode=replay`);
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
    await jumpToTick(tickRange.min);
    return true;

  } catch (error) {
    console.error('Failed to load debug session', error);
    return false;
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
  if (recordingStatusPoller) clearInterval(recordingStatusPoller);
  recordingStatusPoller = setInterval(() => {
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
        recordingEnabled: true
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
    currentSessionId = data.sessionId;
    currentDebugSessionId = data.sessionId;
    currentDebugSession = data.sessionId;
    pendingWindowSessionRebind = false;
    // Explicit session selection: reset the shadow panel and resume history for the new session.
    fishingShadowSwitchSession(data.sessionId);

    // Validate recording actually started before updating UI
    if (data.recordingId) {
      // Recording started successfully
      currentRecordingId = data.recordingId;
      recordingNotificationId = currentRecordingId;
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
      showNotification(data.recordingFailureReason ||
        'Session started but video recording could not be enabled', 'warning');
    }

  } catch (error) {
    console.error('❌ Failed to start recording:', error);
    showNotification(error.message, 'error');
    alert('Failed to start recording: ' + error.message);

    // Ensure state is reset on error
    isRecording = false;
    currentRecordingId = null;
    currentSessionId = null;
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

    // If we have a debug session, use the debug stop endpoint
    if (currentSessionId) {
      endpoint = '/api/debug/replay/stop-recording';
      body = JSON.stringify({
        sessionId: currentSessionId,
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
        recordingNotificationId = null;
        currentSessionId = null;
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
    recordingNotificationId = null;
    currentSessionId = null;
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
    recordingNotificationId = null;
    currentSessionId = null;
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
  if (recordingStatusRequest) return recordingStatusRequest;

  const generation = ++recordingStatusGeneration;
  const controller = new AbortController();
  recordingStatusAbortController = controller;
  const request = (async () => {
    try {
      const response = await fetch('/api/recordings/status', { signal: controller.signal });
      if (!response.ok || controller.signal.aborted || generation !== recordingStatusGeneration) return;

      const data = await response.json();
      if (controller.signal.aborted || generation !== recordingStatusGeneration) return;

      const backendRecording = Boolean(data && data.recording && data.recordingId);
      const backendRecordingId = backendRecording ? String(data.recordingId) : null;
      const previousRecordingId = currentRecordingId;
      const previousRecordingActive = isRecording;
      const core = typeof DebuggerSessionSyncCore !== 'undefined' ? DebuggerSessionSyncCore : null;
      const transition = core
        ? core.recordingTransition(
          previousRecordingId,
          previousRecordingActive,
          backendRecordingId,
          backendRecording
        )
        : {
          changed: previousRecordingId !== backendRecordingId || previousRecordingActive !== backendRecording,
          recordingChanged: previousRecordingId !== backendRecordingId,
          becameInactive: previousRecordingActive && !backendRecording
        };

      if (backendRecording) {
        // A new recording generation invalidates the previous association. Keep the recording
        // active while the debugger waits for SESSION_CREATED or the association endpoint.
        if (transition.recordingChanged && previousRecordingId && previousRecordingId !== backendRecordingId) {
          currentSessionId = null;
          currentDebugSessionId = null;
          currentDebugSession = null;
          isDebugSessionActive = false;
        }

        isRecording = true;
        currentRecordingId = backendRecordingId;
        recordingStartTime = data.startTime
          ? new Date(data.startTime).getTime()
          : (recordingStartTime || Date.now());

        if (transition.changed) {
          elements.recordBtn.classList.remove('btn-danger');
          elements.recordBtn.classList.add('btn-success');
          elements.recordBtnText.textContent = 'Stop Recording';
          if (elements.recordingStatus) elements.recordingStatus.classList.remove('d-none');
          startRecordingDurationTimer();
        }

        if (core
          ? core.shouldNotifyRecording(recordingNotificationId, backendRecordingId)
          : recordingNotificationId !== backendRecordingId) {
          recordingNotificationId = backendRecordingId;
          const sourceLabel = data.triggerSource
            ? ` (${String(data.triggerSource).replace(/_/g, ' ')})`
            : '';
          showNotification(`Recording active${sourceLabel}`, 'info');
        }

        // Association is best-effort. A 404 is a normal transition while the backend finalizes or
        // binds a session; it must not clear the active recording or reset the panel repeatedly.
        // While the debug feed is disabled, do not start live-session discovery or mutate the
        // fishing panel in response to recording polls. The next explicit enable/broadcast will
        // bind the live session once.
        if (debugFeedEnabled && !currentSessionId) {
          try {
            const sessionResponse = await fetch(
              `/api/debug/replay/sessions/active?recordingId=${encodeURIComponent(backendRecordingId)}`,
              { signal: controller.signal }
            );
            if (
              sessionResponse.ok
              && !controller.signal.aborted
              && generation === recordingStatusGeneration
              && currentRecordingId === backendRecordingId
              && !currentSessionId
            ) {
              const sessionData = await sessionResponse.json();
              if (sessionData && sessionData.sessionId) {
                currentSessionId = sessionData.sessionId;
                currentDebugSessionId = sessionData.sessionId;
                currentDebugSession = sessionData.sessionId;
                pendingWindowSessionRebind = false;
                fishingShadowSwitchSession(sessionData.sessionId);
              }
            }
          } catch (associationError) {
            if (!associationError || associationError.name !== 'AbortError') {
              // Missing association is intentionally quiet; the next poll or SESSION_CREATED event
              // will retry it without producing another panel reset or user notification.
            }
          }
        }
        return;
      }

      if (previousRecordingActive || previousRecordingId) {
        // Clear the recording and its session association as one transition, then invalidate the
        // three shadow requests once. This prevents alternating status generations from thrashing
        // the fishing panel.
        isRecording = false;
        currentRecordingId = null;
        currentSessionId = null;
        currentDebugSessionId = null;
        currentDebugSession = null;
        isDebugSessionActive = false;
        recordingStartTime = null;
        recordingNotificationId = null;
        elements.recordBtn.classList.remove('btn-success');
        elements.recordBtn.classList.add('btn-danger');
        elements.recordBtnText.textContent = 'Start Recording';
        if (elements.recordingStatus) elements.recordingStatus.classList.add('d-none');
        stopRecordingDurationTimer();
        // Keep the live fishing panel untouched while the debug feed is disabled. The panel is
        // rebound from the next accepted session event when the feed is explicitly enabled.
        if (debugFeedEnabled) {
          fishingShadowSwitchSession(null);
        }
      }
    } catch (error) {
      if (!error || error.name !== 'AbortError') {
        console.error('Failed to check recording status:', error);
      }
    }
  })();

  let settledRequest;
  settledRequest = request.finally(() => {
    if (recordingStatusRequest === settledRequest) {
      recordingStatusRequest = null;
    }
    if (recordingStatusAbortController === controller) {
      recordingStatusAbortController = null;
    }
  });
  recordingStatusRequest = settledRequest;
  return recordingStatusRequest;
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
    // A disabled feed must stop in-flight shadow/session discovery work immediately. The
    // recording itself remains untouched, but its status/association and shadow requests must
    // not continue applying live-session state while transport events are being ignored.
    fishingShadowAbortPendingRequests();
    // Hide debug session indicator when feed is off (do not stop recording)
    if (elements.debugSessionStatus) {
      elements.debugSessionStatus.classList.add('d-none');
    }
    isDebugSessionActive = false;
  } else {
    // Allow the next accepted broadcast after re-enabling the feed to bind the current window,
    // even if the same session was observed while the feed was disabled.
    lastProcessedSessionCreatedKey = null;
    if (fishingShadowAgentInitialized) {
      // Re-enable is an explicit lifecycle trigger. Rebind the currently selected session once so
      // a broadcast that arrived while disabled does not leave the panel permanently stale.
      fishingShadowSwitchSession(null);
      if (currentDebugSessionId) {
        fishingShadowSwitchSession(currentDebugSessionId, null);
      } else {
        fishingShadowLoadModels();
        fishingShadowLoadStatus();
      }
    }
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
    currentDebugSession = data.sessionId;
    pendingWindowSessionRebind = false;
    isDebugSessionActive = true;
    // Explicit session selection: reset the shadow panel and resume history for the new session.
    fishingShadowSwitchSession(data.sessionId);

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
const DEFAULT_PATCH_LOOP_TEMPLATE = `graph TD;
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
let activePatchLoopSeed = null;

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

function renderPatchLoopSeedStatus(message, tone = 'muted') {
  const status = document.getElementById('patchLoopSeedStatus');
  if (!status) return;

  const toneClass = {
    muted: 'text-muted',
    success: 'text-success',
    warning: 'text-warning',
    error: 'text-danger',
    info: 'text-info'
  }[tone] || 'text-muted';

  status.className = `form-text mt-1 ${toneClass}`;
  status.textContent = message;
}

function clonePatchLoopSeed(seed) {
  if (!seed || typeof seed !== 'object') {
    return null;
  }
  return JSON.parse(JSON.stringify(seed));
}

function findPatchLoopCatalogValueForTaskKey(taskKey) {
  const normalizedTaskKey = String(taskKey || '').trim();
  if (!normalizedTaskKey) {
    return '';
  }
  const entry = patchLoopCatalogEntries.find(candidate => {
    if (!candidate || candidate.disabled) {
      return false;
    }
    return candidate.value === normalizedTaskKey
      || candidate.value === `micro6:${normalizedTaskKey}`
      || candidate.value.endsWith(`:${normalizedTaskKey}`);
  });
  return entry?.value || '';
}

function applyPatchLoopSeedToInputs(seed) {
  if (!seed) {
    return;
  }
  const mermaidInput = document.getElementById('mermaidInput');
  const scriptSelect = document.getElementById('patchScriptSelect');
  const goalPromptInput = document.getElementById('goalPromptInput');
  const executionModeSelect = document.getElementById('executionModeSelect');
  const patchPanel = document.getElementById('patchLoopPanel');
  const patchPanelContent = document.getElementById('patchPanelContent');
  const togglePatchLoopBtn = document.getElementById('togglePatchLoopBtn');

  if (patchPanel && patchPanel.style.display === 'none') {
    patchPanel.style.display = 'block';
  }
  if (patchPanelContent && patchPanelContent.style.display === 'none') {
    patchPanelContent.style.display = 'block';
    const icon = togglePatchLoopBtn?.querySelector('i');
    if (icon) {
      icon.classList.remove('bi-chevron-down');
      icon.classList.add('bi-chevron-up');
    }
  }

  if (goalPromptInput) {
    goalPromptInput.value = seed.goalPrompt || '';
  }
  if (executionModeSelect) {
    executionModeSelect.value = seed.executionMode || 'analysis_only';
  }
  if (mermaidInput && !mermaidInput.value.trim()) {
    mermaidInput.value = seed.mermaidTemplate || DEFAULT_PATCH_LOOP_TEMPLATE;
  }

  const resolvedScriptValue =
    findPatchLoopCatalogValueForTaskKey(seed.taskKey || seed.scriptName || '');
  if (scriptSelect && resolvedScriptValue) {
    scriptSelect.value = resolvedScriptValue;
    activePatchLoopSeed.scriptSelectValue = resolvedScriptValue;
  }

  const summary = seed.summary || seed.displayName || seed.taskKey || 'recovery workspace';
  renderPatchLoopSeedStatus(`Seeded from recovery workspace: ${summary}`, 'info');
}

function seedPatchLoopFromRecoveryWorkspace(seed) {
  activePatchLoopSeed = clonePatchLoopSeed(seed);
  if (!activePatchLoopSeed) {
    renderPatchLoopSeedStatus(
      'Recovery workspace opened, but patch-loop seed data was unavailable.',
      'warning'
    );
    return false;
  }
  applyPatchLoopSeedToInputs(activePatchLoopSeed);
  return true;
}

function clearPatchLoopSeed() {
  activePatchLoopSeed = null;
  renderPatchLoopSeedStatus(
    'Patch loop is waiting for a manual selection or recovery workspace seed.',
    'muted'
  );
}

function scriptMatchesActivePatchLoopSeed(scriptName) {
  if (!activePatchLoopSeed || !scriptName) {
    return false;
  }
  const candidates = new Set([
    activePatchLoopSeed.scriptSelectValue,
    activePatchLoopSeed.scriptName,
    activePatchLoopSeed.taskKey,
    activePatchLoopSeed.taskKey ? `micro6:${activePatchLoopSeed.taskKey}` : null
  ].filter(Boolean));
  return candidates.has(scriptName);
}

function getPatchLoopExecutionSeed(scriptName) {
  if (!scriptMatchesActivePatchLoopSeed(scriptName)) {
    return null;
  }
  return clonePatchLoopSeed(activePatchLoopSeed);
}

function buildPatchLoopSearchIndex(task) {
  const tokens = [
    task?.display_name,
    task?.task_key,
    task?.category,
    Array.isArray(task?.tags) ? task.tags.join(' ') : ''
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

  const desiredValue =
    selectedValue
    || activePatchLoopSeed?.scriptSelectValue
    || findPatchLoopCatalogValueForTaskKey(activePatchLoopSeed?.taskKey || activePatchLoopSeed?.scriptName || '');
  if (desiredValue) {
    const matching = Array.from(select.options).find(
      option => option.value === desiredValue && option.disabled !== true
    );
    if (matching) {
      select.value = desiredValue;
      if (activePatchLoopSeed) {
        activePatchLoopSeed.scriptSelectValue = desiredValue;
      }
    }
  }
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

      micro6QueueTasks.forEach(task => {
        const taskKey = task?.task_key;
        if (!taskKey) return;
        const displayName = task?.display_name || taskKey;
        const category = task?.category ? ` [${task.category}]` : '';
        patchLoopCatalogEntries.push({
          group: 'Micro6 AccountBuilder',
          value: `micro6:${taskKey}`,
          label: `${displayName} (${taskKey})${category}`,
          disabled: false,
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

    // Update headline badge
    const headlineBadge = document.getElementById('codexMcpHeadlineBadge');
    const headlineText = document.getElementById('codexMcpHeadlineText');
    if (headlineBadge && headlineText) {
      const stateClasses = ['badge-verified', 'badge-unverified', 'badge-wrong-provider',
        'badge-down', 'badge-unknown', 'badge-failed'];
      headlineBadge.classList.remove(...stateClasses);
      headlineText.textContent = status.statusHeadline || 'Checking Codex MCP status...';
      const state = status.connectionState || '';
      if (state === 'CODEX_CONNECTED_VERIFIED') headlineBadge.classList.add('badge-verified');
      else if (state === 'CODEX_CONNECTED_UNVERIFIED') headlineBadge.classList.add('badge-unverified');
      else if (state === 'MCP_UP_WRAPPER_NOT_CODEX') headlineBadge.classList.add('badge-wrong-provider');
      else if (state === 'MCP_DOWN') headlineBadge.classList.add('badge-down');
      else if (state === 'VERIFICATION_FAILED') headlineBadge.classList.add('badge-failed');
      else headlineBadge.classList.add('badge-unknown');
    }

    // Update one-line detail
    const detailText = document.getElementById('codexMcpDetailText');
    if (detailText) {
      detailText.textContent = status.statusDetail || '';
    }

    if (!badge || !nameText) return;

    const connectionState = status.connectionState || '';
    if (connectionState === 'CODEX_CONNECTED_VERIFIED') {
      badge.className = 'badge bg-success';
      badge.innerHTML = '<i class="bi bi-check-circle me-1"></i>Codex Ready';
    } else if (connectionState === 'CODEX_CONNECTED_UNVERIFIED') {
      badge.className = 'badge bg-warning text-dark';
      badge.innerHTML = '<i class="bi bi-exclamation-circle me-1"></i>Unverified';
    } else if (connectionState === 'MCP_UP_WRAPPER_NOT_CODEX') {
      badge.className = 'badge bg-warning text-dark';
      badge.innerHTML = '<i class="bi bi-arrow-left-right me-1"></i>Wrong Wrapper';
    } else if (connectionState === 'VERIFICATION_FAILED') {
      badge.className = 'badge bg-warning text-dark';
      badge.innerHTML = '<i class="bi bi-exclamation-triangle me-1"></i>Verify Failed';
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

    // Update verification row with secondary metadata
    const verificationRow = document.getElementById('providerVerificationRow');
    if (verificationRow) {
      const verificationText = document.getElementById('providerVerificationText');
      if (verificationText) {
        const parts = [];
        parts.push('Framework: ' + (status.frameworkProvider || 'unknown'));
        parts.push('Wrapper: ' + (status.runtimeProvider || 'unknown'));
        if (status.runtimeModel) parts.push('Model: ' + status.runtimeModel);
        if (status.bridgeMode) parts.push('Bridge: ' + status.bridgeMode);
        if (status.lastVerifiedAt) {
          const d = new Date(status.lastVerifiedAt);
          parts.push('Verified: ' + d.toLocaleTimeString());
        }
        if (status.lastVerificationError) {
          parts.push('Last error: ' + status.lastVerificationError);
        }
        verificationText.textContent = parts.join(' | ');
      }
      verificationRow.style.display = 'block';
    }

    // Show/hide verify button based on backend config
    const verifyBtn = document.getElementById('verifyCodexMcpBtn');
    if (verifyBtn) {
      verifyBtn.style.display = status.verificationAvailable ? 'inline-block' : 'none';
    }
  } catch (error) {
    console.error('Error fetching provider status:', error);
  }
}

/**
 * Trigger manual Codex MCP verification and refresh status.
 */
async function triggerCodexMcpVerification() {
  const verifyBtn = document.getElementById('verifyCodexMcpBtn');
  const recoveryVerifyBtn = document.getElementById('recoveryVerifyCodexMcpBtn');
  if (verifyBtn) {
    verifyBtn.disabled = true;
    verifyBtn.innerHTML = '<i class="bi bi-hourglass-split me-1"></i>Verifying...';
  }
  if (recoveryVerifyBtn) {
    recoveryVerifyBtn.disabled = true;
    recoveryVerifyBtn.innerHTML = '<i class="bi bi-hourglass-split"></i>';
  }
  try {
    const response = await fetch('/api/debug/patch-loop/provider/verify', { method: 'POST' });
    if (!response.ok) {
      const text = await response.text();
      throw new Error(text || 'HTTP ' + response.status);
    }
    const status = await response.json();
    if (typeof renderRecoveryCodexMcpStatus === 'function') {
      renderRecoveryCodexMcpStatus(status);
    }
    const verificationSucceeded =
      status.connectionState === 'CODEX_CONNECTED_VERIFIED' && status.endToEndVerified === true;
    await updateProviderStatus();
    if (verificationSucceeded) {
      showNotification('Codex MCP verification complete', 'success');
    } else {
      const detail = status.lastVerificationError || status.statusHeadline || 'Verification did not confirm Codex MCP.';
      showNotification('Verification failed: ' + detail, 'error');
    }
  } catch (error) {
    console.error('Codex MCP verification failed:', error);
    await updateProviderStatus();
    showNotification('Verification failed: ' + error.message, 'error');
  } finally {
    if (verifyBtn) {
      verifyBtn.disabled = false;
      verifyBtn.innerHTML = '<i class="bi bi-shield-check me-1"></i>Verify Codex MCP';
    }
    if (recoveryVerifyBtn) {
      recoveryVerifyBtn.disabled = false;
      recoveryVerifyBtn.innerHTML = '<i class="bi bi-shield-check"></i>';
    }
  }
}

/**
 * Render Codex MCP status for the recovery mode toolbar.
 */
function renderRecoveryCodexMcpStatus(status) {
  const badge = document.getElementById('recoveryCodexMcpBadge');
  const text = document.getElementById('recoveryCodexMcpText');
  const btn = document.getElementById('recoveryVerifyCodexMcpBtn');

  if (!badge || !text) return;

  text.textContent = status.statusHeadline || 'Codex MCP status unknown';

  const stateClasses = ['badge-verified', 'badge-unverified', 'badge-wrong-provider',
    'badge-down', 'badge-unknown', 'badge-failed'];
  badge.classList.remove(...stateClasses);
  const state = status.connectionState || '';
  if (state === 'CODEX_CONNECTED_VERIFIED') badge.classList.add('badge-verified');
  else if (state === 'CODEX_CONNECTED_UNVERIFIED') badge.classList.add('badge-unverified');
  else if (state === 'MCP_UP_WRAPPER_NOT_CODEX') badge.classList.add('badge-wrong-provider');
  else if (state === 'MCP_DOWN') badge.classList.add('badge-down');
  else if (state === 'VERIFICATION_FAILED') badge.classList.add('badge-failed');
  else badge.classList.add('badge-unknown');

  if (btn) {
    btn.style.display = status.verificationAvailable ? 'inline-block' : 'none';
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
  if (providerStatusPoller) clearInterval(providerStatusPoller);
  providerStatusPoller = setInterval(updateProviderStatus, 15000);

  // Verify Codex MCP button
  const verifyBtn = document.getElementById('verifyCodexMcpBtn');
  if (verifyBtn) {
    verifyBtn.addEventListener('click', triggerCodexMcpVerification);
  }

  // Recovery mode Codex MCP verify button
  const recoveryVerifyBtn = document.getElementById('recoveryVerifyCodexMcpBtn');
  if (recoveryVerifyBtn) {
    recoveryVerifyBtn.addEventListener('click', triggerCodexMcpVerification);
  }

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
      const mermaidInput = document.getElementById('mermaidInput');
      if (mermaidInput) {
        mermaidInput.value = DEFAULT_PATCH_LOOP_TEMPLATE;
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
      const seededExecution = getPatchLoopExecutionSeed(scriptName);

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
          {
            executionMode,
            sessionContext: seededExecution?.sessionContext || null,
            taskMetadata: seededExecution?.taskMetadata || null
          }
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

// ==================== FISHING RECORDING LAB ====================

const fishingRecordingLab = {
  modelId: null,
  ranges: new Map(),
  archivedRecordings: new Set(),
  showArchived: false,
  busy: false
};

function initFishingRecordingLab() {
  const lab = elements.fishingRecordingLab;
  if (!lab) return;
  lab.addEventListener('click', async (event) => {
    const button = event.target.closest('button');
    if (!button || fishingRecordingLab.busy) return;
    try {
      if (button.id === 'fishingLabMarkStart') setFishingLabRangeEdge('start');
      else if (button.id === 'fishingLabMarkEnd') setFishingLabRangeEdge('end');
      else if (button.id === 'fishingLabClearRange') clearFishingLabRange();
      else if (button.id === 'fishingLabUseTraining') useCurrentFishingRecording('training');
      else if (button.id === 'fishingLabUseTest') useCurrentFishingRecording('test');
      else if (button.id === 'fishingLabPreflight') await runFishingLabPreflight();
      else if (button.id === 'fishingLabTrain') await runFishingLabTraining();
      else if (button.id === 'fishingLabReplay') await runFishingLabReplay();
      else if (button.id === 'fishingLabArchive') await archiveFishingLabRecording();
    } catch (error) {
      renderFishingLabResult({ status: 'FAILED', message: error.message || String(error) });
    }
  });
  lab.addEventListener('change', (event) => {
    if (event.target.id === 'fishingLabShowArchived') {
      fishingRecordingLab.showArchived = event.target.checked;
      loadRecordingSessions();
      return;
    }
    if (event.target.id === 'fishingLabStartTick' || event.target.id === 'fishingLabEndTick') {
      rememberFishingLabRange();
    }
  });
  renderFishingRecordingLabSelection();
}

function renderFishingRecordingLabSelection() {
  const label = document.getElementById('fishingLabCurrentRecording');
  if (label) label.textContent = currentRecording || 'select a replay';
  const range = currentRecording ? fishingRecordingLab.ranges.get(currentRecording) : null;
  const start = document.getElementById('fishingLabStartTick');
  const end = document.getElementById('fishingLabEndTick');
  if (start) start.value = range && range.startTick != null ? range.startTick : '';
  if (end) end.value = range && range.endTick != null ? range.endTick : '';
  const model = document.getElementById('fishingLabModelId');
  if (model) model.textContent = fishingRecordingLab.modelId || '--';
  const archive = document.getElementById('fishingLabArchive');
  if (archive) {
    const archived = currentRecording && fishingRecordingLab.archivedRecordings.has(currentRecording);
    archive.textContent = archived ? 'Restore current' : 'Archive current';
  }
}

function setFishingLabRangeEdge(edge) {
  if (!currentRecording || currentMode !== 'replay') throw new Error('Select and open a replay before marking a range');
  if (!Number.isFinite(Number(currentTick))) throw new Error('The replay has no current tick yet');
  const input = document.getElementById(edge === 'start' ? 'fishingLabStartTick' : 'fishingLabEndTick');
  if (input) input.value = String(Number(currentTick));
  rememberFishingLabRange();
}

function rememberFishingLabRange(strict = false) {
  if (!currentRecording) return;
  const startValue = document.getElementById('fishingLabStartTick')?.value.trim() || '';
  const endValue = document.getElementById('fishingLabEndTick')?.value.trim() || '';
  if (!strict && Boolean(startValue) !== Boolean(endValue)) return;
  const range = readFishingLabRangeInputs();
  if (range) fishingRecordingLab.ranges.set(currentRecording, range);
  else fishingRecordingLab.ranges.delete(currentRecording);
}

function clearFishingLabRange() {
  if (currentRecording) fishingRecordingLab.ranges.delete(currentRecording);
  const start = document.getElementById('fishingLabStartTick');
  const end = document.getElementById('fishingLabEndTick');
  if (start) start.value = '';
  if (end) end.value = '';
}

function readFishingLabRangeInputs() {
  const startValue = document.getElementById('fishingLabStartTick')?.value.trim() || '';
  const endValue = document.getElementById('fishingLabEndTick')?.value.trim() || '';
  if (!startValue && !endValue) return null;
  if (!startValue || !endValue) throw new Error('Mark both range edges, or use the full recording');
  const startTick = Number(startValue);
  const endTick = Number(endValue);
  if (!Number.isInteger(startTick) || !Number.isInteger(endTick) || startTick < 0 || endTick < startTick) {
    throw new Error('Range ticks must be non-negative integers with start at or before end');
  }
  return { startTick, endTick };
}

function rangeForFishingRecording(recordingId) {
  if (recordingId === currentRecording) rememberFishingLabRange(true);
  return fishingRecordingLab.ranges.get(recordingId) || null;
}

function useCurrentFishingRecording(kind) {
  if (!currentRecording) throw new Error('Select a replay first');
  rememberFishingLabRange(true);
  const input = document.getElementById(kind === 'training' ? 'fishingLabTrainingId' : 'fishingLabTestId');
  if (input) input.value = currentRecording;
}

async function fishingLabRequest(path, options) {
  fishingRecordingLab.busy = true;
  try {
    const response = await fetch(path, options);
    const text = await response.text();
    let data;
    try { data = text ? JSON.parse(text) : {}; } catch (_) { data = { message: text }; }
    if (!response.ok) throw new Error(data.message || data.error || data.code || ('HTTP ' + response.status));
    return data;
  } finally {
    fishingRecordingLab.busy = false;
  }
}

function fishingLabPayload(recordingId, extra = {}) {
  return { recordingId, ...(rangeForFishingRecording(recordingId) || {}), ...extra };
}

async function runFishingLabPreflight() {
  if (!currentRecording) throw new Error('Select a finalized replay first');
  const payload = fishingLabPayload(currentRecording);
  const params = new URLSearchParams({ recordingId: payload.recordingId });
  if (payload.startTick != null) {
    params.set('startTick', payload.startTick);
    params.set('endTick', payload.endTick);
  }
  renderFishingLabResult({ status: 'RUNNING', message: 'Running preflight...' });
  renderFishingLabResult(await fishingLabRequest('/api/ai-player/prototype/fishing/preflight?' + params));
}

async function runFishingLabTraining() {
  const recordingId = document.getElementById('fishingLabTrainingId')?.value.trim();
  if (!recordingId) throw new Error('Choose a training recording');
  renderFishingLabResult({ status: 'RUNNING', message: 'Training demonstration model...' });
  const data = await fishingLabRequest('/api/ai-player/prototype/fishing/train', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(fishingLabPayload(recordingId))
  });
  fishingRecordingLab.modelId = data.modelId || null;
  renderFishingRecordingLabSelection();
  renderFishingLabResult(data);
  await fishingShadowLoadModels();
}

async function runFishingLabReplay() {
  const recordingId = document.getElementById('fishingLabTestId')?.value.trim();
  const modelId = fishingRecordingLab.modelId || fishingShadow.selectedModelId || fishingShadow.activeModelId;
  if (!recordingId) throw new Error('Choose a held-out replay recording');
  if (!modelId) throw new Error('Train or select a model first');
  renderFishingLabResult({ status: 'RUNNING', message: 'Running held-out replay...' });
  const data = await fishingLabRequest('/api/ai-player/prototype/fishing/run', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(fishingLabPayload(recordingId, { modelId }))
  });
  renderFishingLabResult(data);
  await fishingShadowLoadModels();
}

async function archiveFishingLabRecording() {
  if (!currentRecording) throw new Error('Select a finalized replay first');
  const archivedId = currentRecording;
  const restore = fishingRecordingLab.archivedRecordings.has(archivedId);
  const action = restore ? 'restore' : 'archive';
  const data = await fishingLabRequest('/api/recordings/sessions/' + encodeURIComponent(archivedId) + '/' + action, { method: 'POST' });
  if (!restore) fishingRecordingLab.ranges.delete(archivedId);
  if (!restore && !fishingRecordingLab.showArchived) currentRecording = null;
  await loadRecordingSessions();
  renderFishingRecordingLabSelection();
  renderFishingLabResult(data);
}

function renderFishingLabResult(data) {
  const node = document.getElementById('fishingLabResult');
  if (!node) return;
  const status = String((data && data.status) || '').toUpperCase();
  node.className = 'recording-lab-result';
  if (status === 'READY') node.classList.add('ready');
  else if (status === 'PASSED' || status === 'TRAINED') node.classList.add('passed');
  else if (status === 'NOT_READY') node.classList.add('not-ready');
  else if (status === 'FAILED' || status === 'REJECTED') node.classList.add('failed');
  node.textContent = JSON.stringify(data, null, 2);
}

// ==================== FISHING SHADOW AGENT ====================

let fishingShadow = {
  models: [],
  activeModelId: null,
  selectedModelId: null,
  status: null,
  latestDecision: null,
  latestTick: -1,
  decisions: [],
  decisionMap: new Map(), // key -> decision (dedupe across REST history + live WebSocket)
  sessionId: null,
  shadowRunId: null,
  runs: [],
  selectedRunId: null,
  showingLegacy: false,
  processed: 0,
  predicted: 0,
  abstained: 0,
  persistenceError: null
};
let fishingShadowFeedbackTimer = null;
// Monotonic generation for the tracked shadow session/run. Status/history responses captured for an
// older generation are discarded so a stale response can never pollute a newer session's state.
let fishingShadowSessionGeneration = 0;
// Monotonic revision of the live WebSocket decision stream. A status response captured before the
// latest accepted live decision must not roll session counters backward, so its session counters
// are skipped when this revision changed while the request was in flight.
let fishingShadowLiveRevision = 0;
// Abort controllers for the in-flight status/history requests, cancelled on session switch.
let fishingShadowStatusAbortController = null;
let fishingShadowHistoryAbortController = null;
let fishingShadowRunsAbortController = null;
let fishingShadowAgentInitialized = false;

function fishingShadowAbortPendingRequests() {
  if (recordingStatusAbortController) {
    recordingStatusGeneration++;
    recordingStatusAbortController.abort();
    recordingStatusAbortController = null;
    // The old promise will settle asynchronously. Clear the single-flight slot now so a session
    // switch can start a fresh status poll instead of receiving the already-aborted promise.
    recordingStatusRequest = null;
  }
  if (fishingShadowStatusAbortController) {
    fishingShadowStatusAbortController.abort();
    fishingShadowStatusAbortController = null;
  }
  if (fishingShadowHistoryAbortController) {
    fishingShadowHistoryAbortController.abort();
    fishingShadowHistoryAbortController = null;
  }
  if (fishingShadowRunsAbortController) {
    fishingShadowRunsAbortController.abort();
    fishingShadowRunsAbortController = null;
  }
}

function initFishingShadowAgent() {
  const container = elements.fishingShadowContent;
  if (!container) return;
  fishingShadowAgentInitialized = true;

  // Buttons are wired through event delegation inside the rendered panel.
  container.addEventListener('click', (event) => {
    const refreshBtn = event.target.closest('#shadowRefreshModelsBtn');
    if (refreshBtn) {
      fishingShadowLoadModels();
      return;
    }
    const activateBtn = event.target.closest('#shadowActivateBtn');
    if (activateBtn) {
      fishingShadowActivate();
      return;
    }
    const deactivateBtn = event.target.closest('#shadowDeactivateBtn');
    if (deactivateBtn) {
      fishingShadowDeactivate();
      return;
    }
    const legacyBtn = event.target.closest('#shadowLoadLegacyBtn');
    if (legacyBtn) {
      const session = fishingShadow.sessionId || currentDebugSessionId;
      if (session) fishingShadowLoadLegacy(session);
      return;
    }
  });

  container.addEventListener('change', (event) => {
    if (event.target.id === 'shadowModelSelect') {
      fishingShadow.selectedModelId = event.target.value || null;
      return;
    }
    if (event.target.id === 'shadowRunSelect') {
      const runId = event.target.value || null;
      const session = fishingShadow.sessionId || currentDebugSessionId;
      if (runId && session) {
        fishingShadowSwitchSession(session, runId);
      }
    }
  });

  // Loading the panel must not create shadow/status traffic while the live feed is disabled. The
  // feed toggle is initialized before this function during page startup; explicit user actions
  // on the panel remain allowed and are handled by the delegated listeners above.
  if (!debugFeedEnabled) {
    renderFishingShadow();
    return;
  }

  fishingShadowLoadModels();
  // On reload, restore the live session/run when known; otherwise leave the panel unbound until a
  // SESSION_CREATED broadcast or explicit selection arrives.
  if (typeof currentDebugSessionId !== 'undefined' && currentDebugSessionId) {
    fishingShadowSwitchSession(currentDebugSessionId, null);
  } else {
    fishingShadowLoadStatus();
  }
}

function escapeHtml(value) {
  if (value === null || value === undefined) return '';
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

async function fishingShadowLoadModels() {
  const container = elements.fishingShadowContent;
  if (!container) return;
  try {
    const response = await fetch('/api/ai-player/prototype/fishing/models');
    if (!response.ok) {
      renderFishingShadowFeedback('Models load failed: ' + response.status, 'error');
      return;
    }
    const data = await response.json();
    fishingShadow.models = Array.isArray(data.models) ? data.models : [];
    fishingShadow.activeModelId = data.activeModelId || null;
    renderFishingShadow();
  } catch (error) {
    console.error('Failed to load fishing models', error);
    renderFishingShadowFeedback('Models load failed', 'error');
  }
}

async function fishingShadowLoadStatus(targetSessionId, targetShadowRunId) {
  const container = elements.fishingShadowContent;
  if (!container) return;
  const generation = fishingShadowSessionGeneration;
  const liveRevision = fishingShadowLiveRevision;
  const session = targetSessionId || fishingShadow.sessionId;
  const run = targetShadowRunId || fishingShadow.shadowRunId;
  if (fishingShadowStatusAbortController) fishingShadowStatusAbortController.abort();
  const controller = new AbortController();
  fishingShadowStatusAbortController = controller;
  try {
    const params = new URLSearchParams();
    if (session) params.set('sessionId', session);
    if (run) params.set('shadowRunId', run);
    const qs = params.toString() ? '?' + params.toString() : '';
    const response = await fetch('/api/ai-player/prototype/fishing/shadow/status' + qs, {
      signal: controller.signal
    });
    if (!response.ok) return;
    const status = await response.json();
    // Discard responses captured for a previous session/run generation.
    const syncCore = typeof DebuggerSessionSyncCore !== 'undefined' ? DebuggerSessionSyncCore : null;
    if (syncCore
      ? !syncCore.acceptsGeneration(
        generation,
        fishingShadowSessionGeneration,
        controller.signal,
        session,
        fishingShadow.sessionId,
        run,
        fishingShadow.shadowRunId
      )
      : generation !== fishingShadowSessionGeneration
        || controller.signal.aborted
        || (session && session !== fishingShadow.sessionId)
        || (run && run !== fishingShadow.shadowRunId)) return;

    // Model readiness and persistence fields are global/current status and always apply. Session
    // counters and the latest decision apply only when no accepted live decision landed while the
    // request was in flight (the live revision matched), so a delayed same-run response can never
    // roll live counters backward. If the revision changed, retain local counters and live
    // decisions.
    const applySessionCounters = liveRevision === fishingShadowLiveRevision;
    const shadowCore = typeof FishingShadowCore !== 'undefined' ? FishingShadowCore : null;
    if (shadowCore) {
      Object.assign(
        fishingShadow,
        shadowCore.mergeStatusIntoState(fishingShadow, status, session, run, applySessionCounters)
      );
    } else {
      fishingShadow.status = status;
      // Global/current status fields always apply: active model, readiness, activation, and the
      // writer-wide persistence metrics.
      fishingShadow.activeModelId = (status && status.activeModelId) || null;
      if (status && typeof status.readyForInference !== 'undefined') fishingShadow.readyForInference = status.readyForInference;
      if (status && typeof status.activationState !== 'undefined') fishingShadow.activationState = status.activationState;
      if (status && typeof status.activationLoadError !== 'undefined') fishingShadow.activationLoadError = status.activationLoadError;
      if (status && typeof status.inactiveReason !== 'undefined') fishingShadow.inactiveReason = status.inactiveReason;
      if (status && typeof status.enabled !== 'undefined') fishingShadow.enabled = status.enabled;
      if (status && typeof status.shadowMode !== 'undefined') fishingShadow.shadowMode = status.shadowMode;
      if (status && typeof status.controlEnabled !== 'undefined') fishingShadow.controlEnabled = status.controlEnabled;
      if (status && typeof status.operational !== 'undefined') fishingShadow.operational = status.operational;
      // Clear a previously displayed persistence error once the backend reports recovery.
      fishingShadow.persistenceError = (status && status.persistenceError) || null;
      ['persistenceLifecycle', 'persistenceQueueSize', 'persistenceEnqueued', 'persistenceQueued',
        'persistenceInFlight', 'persistenceCompleted', 'persistenceWritten', 'persistenceFailed',
        'persistenceAbandoned', 'persistenceDropped'].forEach((key) => {
        if (status && typeof status[key] !== 'undefined') fishingShadow[key] = status[key];
      });
      // Session counters and the latest decision apply only when the caller opted in, a target
      // session and run were named, and the backend and the tracked state both name that exact
      // target session and run.
      const sessionScoped = applySessionCounters
        && session
        && run
        && status && status.sessionId === session
        && status.shadowRunId === run
        && fishingShadow.sessionId === session
        && fishingShadow.shadowRunId === run;
      if (sessionScoped) {
        if (Number.isFinite(Number(status.processed))) fishingShadow.processed = Number(status.processed);
        if (Number.isFinite(Number(status.predicted))) fishingShadow.predicted = Number(status.predicted);
        if (Number.isFinite(Number(status.abstained))) fishingShadow.abstained = Number(status.abstained);
        if (status.latestDecision && status.latestDecision.tick != null) {
          const tick = Number(status.latestDecision.tick);
          if (Number.isFinite(tick) && tick > fishingShadow.latestTick) {
            fishingShadow.latestTick = tick;
            fishingShadow.latestDecision = status.latestDecision;
            const key = run + ':' + tick;
            if (!fishingShadow.decisionMap.has(key)) {
              fishingShadow.decisionMap.set(key, status.latestDecision);
              rebuildFishingShadowRolling();
            }
          }
        }
      }
    }
    renderFishingShadow();
  } catch (error) {
    if (error && error.name === 'AbortError') return;
    console.error('Failed to load fishing shadow status', error);
  } finally {
    if (fishingShadowStatusAbortController === controller) {
      fishingShadowStatusAbortController = null;
    }
  }
}

async function fishingShadowActivate() {
  const modelId = fishingShadow.selectedModelId || fishingShadow.activeModelId;
  if (!modelId) {
    renderFishingShadowFeedback('Select a validated model first', 'error');
    return;
  }
  try {
    const response = await fetch('/api/ai-player/prototype/fishing/models/activate', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ modelId })
    });
    const data = await response.json();
    if (!response.ok) {
      const code = data && data.code ? data.code : ('HTTP ' + response.status);
      renderFishingShadowFeedback('Activation failed: ' + escapeHtml(code), 'error');
      return;
    }
    fishingShadow.activeModelId = data.modelId || modelId;
    renderFishingShadowFeedback('Model activated: ' + escapeHtml(fishingShadow.activeModelId), 'success');
    // Refresh both the model list and status so the readiness badge reflects the new activation.
    await fishingShadowLoadModels();
    await fishingShadowLoadStatus();
  } catch (error) {
    console.error('Failed to activate model', error);
    renderFishingShadowFeedback('Activation failed', 'error');
  }
}

async function fishingShadowDeactivate() {
  try {
    const response = await fetch('/api/ai-player/prototype/fishing/models/deactivate', {
      method: 'POST'
    });
    const data = await response.json();
    if (!response.ok) {
      renderFishingShadowFeedback('Deactivation failed: HTTP ' + response.status, 'error');
      return;
    }
    fishingShadow.activeModelId = null;
    fishingShadow.selectedModelId = null;
    renderFishingShadowFeedback('Model deactivated', 'success');
    // Refresh both the model list and status so the readiness badge reflects the deactivation.
    await fishingShadowLoadModels();
    await fishingShadowLoadStatus();
  } catch (error) {
    console.error('Failed to deactivate model', error);
    renderFishingShadowFeedback('Deactivation failed', 'error');
  }
}

function handleShadowDecision(data) {
  if (!data || data.type !== 'AI_PLAYER_SHADOW_DECISION') return;

  const core = typeof FishingShadowCore !== 'undefined' ? FishingShadowCore : null;
  if (core) {
    const result = core.mergeLiveDecisionIntoState(fishingShadow, data, currentDebugSessionId || null);
    if (!result.accepted) return;
    Object.assign(fishingShadow, result.state);
    // Advance the live revision only for accepted live decisions so a delayed status response
    // captured before this decision can never roll the session counters backward.
    fishingShadowLiveRevision++;
    renderFishingShadow();
    return;
  }

  // Fallback merge (kept for resilience if fishing-shadow-core.js is missing).
  const sessionId = data.sessionId;
  const runId = data.shadowRunId;
  if (sessionId == null || sessionId === '') return;
  if (runId == null || runId === '') return;
  // Require the correct session: if the UI is bound to a live session, the decision must match it.
  if (currentDebugSessionId && sessionId && sessionId !== currentDebugSessionId) return;
  if (fishingShadow.sessionId && sessionId && sessionId !== fishingShadow.sessionId) return;
  if (fishingShadow.shadowRunId && runId && runId !== fishingShadow.shadowRunId) return;

  // tick 0 is a valid tick; only invalid or non-newer ticks are rejected.
  const tick = Number(data.tick);
  if (!Number.isFinite(tick) || tick < 0) return;
  if (fishingShadow.latestTick >= 0 && tick <= fishingShadow.latestTick) return;

  // Deduplicate by (shadowRunId, tick) so REST history and live messages never double-count.
  const key = runId + ':' + tick;
  if (fishingShadow.decisionMap.has(key)) return;

  const status = data.status;
  if (status !== 'PREDICTED' && status !== 'ABSTAINED') return;

  if (sessionId) fishingShadow.sessionId = sessionId;
  fishingShadow.shadowRunId = runId;
  fishingShadow.decisionMap.set(key, data);
  fishingShadow.latestTick = tick;
  fishingShadow.latestDecision = data;
  updateFishingShadowCounters(data);
  rebuildFishingShadowRolling();
  fishingShadowLiveRevision++;
  renderFishingShadow();
}

function updateFishingShadowCounters(data) {
  fishingShadow.processed++;
  if (data.status === 'PREDICTED') {
    fishingShadow.predicted++;
  } else if (data.status === 'ABSTAINED') {
    fishingShadow.abstained++;
  }
}

function rebuildFishingShadowRolling() {
  fishingShadow.decisions = Array.from(fishingShadow.decisionMap.values())
    .sort((a, b) => Number(b.tick) - Number(a.tick))
    .slice(0, 20);
}

/**
 * Load persisted shadow history for one run (or legacy history) and merge it (newest-first from
 * the server, normalized oldest-to-newest locally, deduplicated by shadowRunId+tick) with live
 * decisions.
 *
 * A response captured before the tracked session/run changed is discarded, and every history
 * decision whose sessionId or shadowRunId differs from the requested session/run is rejected, so an
 * old run's high tick can never suppress the new run's live decisions. Legacy history (no run id)
 * is loaded only by explicit user selection and is never merged into a run-keyed state.
 */
async function fishingShadowLoadHistory(sessionId, shadowRunId, legacy) {
  const target = sessionId || fishingShadow.sessionId || currentDebugSessionId;
  if (!target) return;
  const run = shadowRunId !== undefined ? shadowRunId : fishingShadow.shadowRunId;
  const generation = fishingShadowSessionGeneration;
  if (fishingShadowHistoryAbortController) fishingShadowHistoryAbortController.abort();
  const controller = new AbortController();
  fishingShadowHistoryAbortController = controller;
  try {
    const params = new URLSearchParams({ sessionId: target, limit: '200' });
    if (legacy) {
      params.set('legacy', 'true');
    } else if (run) {
      params.set('shadowRunId', run);
    } else {
      return;
    }
    const response = await fetch(
      '/api/ai-player/prototype/fishing/shadow/history?' + params.toString(),
      { signal: controller.signal }
    );
    if (!response.ok) return;
    const history = await response.json();
    if (!Array.isArray(history)) return;
    // Discard responses captured for a previous session generation.
    const core = typeof DebuggerSessionSyncCore !== 'undefined' ? DebuggerSessionSyncCore : null;
    if (core
      ? !core.acceptsGeneration(
        generation,
        fishingShadowSessionGeneration,
        controller.signal,
        target,
        fishingShadow.sessionId,
        legacy ? null : run,
        legacy ? null : fishingShadow.shadowRunId
      )
      : generation !== fishingShadowSessionGeneration
        || controller.signal.aborted
        || target !== fishingShadow.sessionId
        || (!legacy && run !== fishingShadow.shadowRunId)) return;

    if (typeof FishingShadowCore !== 'undefined') {
      Object.assign(fishingShadow, FishingShadowCore.mergeHistoryIntoState(fishingShadow, history, target, legacy ? null : run));
    } else {
      let newestTick = fishingShadow.latestTick;
      for (const decision of history) {
        if (!decision || decision.tick == null) continue;
        const tick = Number(decision.tick);
        if (!Number.isFinite(tick)) continue;
        if (decision.sessionId !== target) continue;
        if (!legacy && decision.shadowRunId !== run) continue;
        const key = (legacy ? '' : run) + ':' + tick;
        if (!fishingShadow.decisionMap.has(key)) {
          fishingShadow.decisionMap.set(key, decision);
        }
        if (tick > newestTick) newestTick = tick;
      }
      fishingShadow.sessionId = fishingShadow.sessionId || target;
      if (!legacy) fishingShadow.shadowRunId = fishingShadow.shadowRunId || run || null;
      if (newestTick > fishingShadow.latestTick) {
        fishingShadow.latestTick = newestTick;
        const newest = Array.from(fishingShadow.decisionMap.values())
          .sort((a, b) => Number(b.tick) - Number(a.tick))[0];
        fishingShadow.latestDecision = newest || null;
      }
      rebuildFishingShadowRolling();
    }
    renderFishingShadow();
  } catch (error) {
    if (error && error.name === 'AbortError') return;
    console.error('Failed to load fishing shadow history', error);
  } finally {
    if (fishingShadowHistoryAbortController === controller) {
      fishingShadowHistoryAbortController = null;
    }
  }
}

/**
 * Switch the shadow panel to a new tracked session and shadow run. This is the single reset path
 * used by every session/run-change event (WebSocket SESSION_CREATED, manual recording start, manual
 * debug-session start, explicit live-session selection, and account/window rebind) so a previous
 * run's state can never bleed into the new one.
 *
 * Resets ticks, decisions, map, counters, errors, and latest decision; increments the session
 * generation; renders immediately; and loads status and history for the exact session/run. When
 * only a session is known, run discovery selects the active run or leaves the run selector open
 * (legacy history is never chosen silently).
 */
function fishingShadowSwitchSession(sessionId, shadowRunId) {
  const nextSessionId = sessionId || null;
  const nextShadowRunId = shadowRunId || null;
  if (
    fishingShadow.sessionId === nextSessionId
    && fishingShadow.shadowRunId === nextShadowRunId
    && !fishingShadow.showingLegacy
  ) {
    return;
  }
  fishingShadowSessionGeneration++;
  fishingShadowLiveRevision = 0;
  fishingShadowAbortPendingRequests();
  fishingShadow.selectedRunId = null;
  fishingShadow.runs = [];
  fishingShadow.showingLegacy = false;
  const fresh = (typeof FishingShadowCore !== 'undefined')
    ? FishingShadowCore.switchShadowSessionState(sessionId, shadowRunId)
    : {
        latestTick: -1,
        latestDecision: null,
        decisions: [],
        decisionMap: new Map(),
        sessionId: sessionId || null,
        shadowRunId: shadowRunId || null,
        processed: 0,
        predicted: 0,
        abstained: 0,
        persistenceError: null
      };
  fishingShadow.sessionId = nextSessionId;
  fishingShadow.shadowRunId = nextShadowRunId;
  fishingShadow.latestTick = fresh.latestTick;
  fishingShadow.latestDecision = fresh.latestDecision;
  fishingShadow.decisions = fresh.decisions;
  fishingShadow.decisionMap = fresh.decisionMap;
  fishingShadow.processed = fresh.processed;
  fishingShadow.predicted = fresh.predicted;
  fishingShadow.abstained = fresh.abstained;
  fishingShadow.persistenceError = fresh.persistenceError;
  fishingShadow.status = null;
  renderFishingShadow();
  if (sessionId && shadowRunId) {
    fishingShadowLoadStatus(sessionId, shadowRunId);
    fishingShadowLoadHistory(sessionId, shadowRunId, false);
  } else if (sessionId) {
    fishingShadowLoadRuns(sessionId);
  }
}

/**
 * Discovers the shadow runs for a session and selects the active run when present. When no active
 * run exists, the run selector is rendered so the user can pick a run or explicitly load legacy
 * history — legacy history is never chosen silently.
 */
async function fishingShadowLoadRuns(sessionId) {
  const container = elements.fishingShadowContent;
  if (!container) return;
  const generation = fishingShadowSessionGeneration;
  if (!sessionId) return;
  if (fishingShadowRunsAbortController) {
    fishingShadowRunsAbortController.abort();
  }
  const controller = new AbortController();
  fishingShadowRunsAbortController = controller;
  try {
    const params = new URLSearchParams({ sessionId });
    const response = await fetch(
      '/api/ai-player/prototype/fishing/shadow/runs?' + params.toString(),
      { signal: controller.signal }
    );
    if (!response.ok) return;
    const data = await response.json();
    const core = typeof DebuggerSessionSyncCore !== 'undefined' ? DebuggerSessionSyncCore : null;
    if (core
      ? !core.acceptsGeneration(
        generation,
        fishingShadowSessionGeneration,
        controller.signal,
        sessionId,
        fishingShadow.sessionId,
        null,
        null
      )
      : generation !== fishingShadowSessionGeneration
        || controller.signal.aborted
        || sessionId !== fishingShadow.sessionId) return;
    if (data && data.sessionId && data.sessionId !== sessionId) return;
    if (!data || !Array.isArray(data.runs)) return;
    fishingShadow.runs = data.runs;
    const active = data.runs.find(r => r.active && !r.legacy && r.shadowRunId);
    if (active) {
      fishingShadowSwitchSession(sessionId, active.shadowRunId);
      // Switching to the active run resets the panel, including its run list. Restore the
      // response that was just validated so the selector remains useful without another request.
      fishingShadow.runs = data.runs;
      renderFishingShadow();
      return;
    }
    renderFishingShadow();
  } catch (error) {
    if (error && error.name === 'AbortError') return;
    console.error('Failed to load fishing shadow runs', error);
  } finally {
    if (fishingShadowRunsAbortController === controller) {
      fishingShadowRunsAbortController = null;
    }
  }
}

/** Loads legacy (schema-v1) history for a session by explicit user selection. */
function fishingShadowLoadLegacy(sessionId) {
  fishingShadowSessionGeneration++;
  fishingShadowLiveRevision = 0;
  fishingShadowAbortPendingRequests();
  const fresh = (typeof FishingShadowCore !== 'undefined')
    ? FishingShadowCore.switchShadowSessionState(sessionId, null)
    : {
        latestTick: -1,
        latestDecision: null,
        decisions: [],
        decisionMap: new Map(),
        sessionId: sessionId || null,
        shadowRunId: null,
        processed: 0,
        predicted: 0,
        abstained: 0,
        persistenceError: null
      };
  fishingShadow.sessionId = sessionId || null;
  fishingShadow.shadowRunId = null;
  fishingShadow.selectedRunId = null;
  fishingShadow.showingLegacy = true;
  fishingShadow.latestTick = fresh.latestTick;
  fishingShadow.latestDecision = fresh.latestDecision;
  fishingShadow.decisions = fresh.decisions;
  fishingShadow.decisionMap = fresh.decisionMap;
  fishingShadow.processed = fresh.processed;
  fishingShadow.predicted = fresh.predicted;
  fishingShadow.abstained = fresh.abstained;
  fishingShadow.persistenceError = fresh.persistenceError;
  fishingShadow.status = null;
  renderFishingShadow();
  fishingShadowLoadStatus(sessionId, null);
  fishingShadowLoadHistory(sessionId, null, true);
}

function renderFishingShadowFeedback(message, type) {
  const container = elements.fishingShadowContent;
  if (!container) return;
  const existing = container.querySelector('.shadow-feedback');
  if (existing) existing.remove();
  const div = document.createElement('div');
  div.className = 'shadow-feedback ' + (type === 'success' ? 'success' : 'error');
  div.textContent = message;
  container.prepend(div);
  clearTimeout(fishingShadowFeedbackTimer);
  fishingShadowFeedbackTimer = setTimeout(() => div.remove(), 6000);
}

function renderFishingShadow() {
  const container = elements.fishingShadowContent;
  if (!container) return;

  const status = fishingShadow.status || {};
  const enabled = !!status.enabled;
  const shadowMode = !!status.shadowMode;
  const controlEnabled = !!status.controlEnabled;
  const operational = !!status.operational;
  const readyForInference = !!status.readyForInference;
  const inactiveReason = status.inactiveReason || '';
  const activationLoadError = status.activationLoadError || null;

  // The dropdown selects the user's pending selection first, falling back to the active model.
  // A live rerender must not discard a pending selection.
  const dropdownModelId = fishingShadow.selectedModelId || fishingShadow.activeModelId;
  const activeModel =
    (fishingShadow.models || []).find(m => m.modelId === fishingShadow.activeModelId) || null;
  const validated = !!activeModel && !!activeModel.replayValidated;
  const selectedModel =
    (fishingShadow.models || []).find(m => m.modelId === dropdownModelId) || null;
  const canActivate = !!selectedModel && !!selectedModel.replayValidated;

  const decision = fishingShadow.latestDecision;
  const statusBadgeClass = decision
    ? (decision.status === 'PREDICTED' ? 'predicted' : (decision.status === 'ABSTAINED' ? 'abstained' : 'error'))
    : 'inactive';
  const statusLabel = decision ? decision.status : (operational ? 'INACTIVE' : 'INACTIVE');

  const action = decision ? decision.action : null;
  const targetNpc = decision ? decision.targetNpc : null;

  const featureBadge = (on, text) =>
    `<span class="shadow-feature-badge ${on ? 'on' : 'off'}">${escapeHtml(text)}</span>`;

  const optionsHtml = (fishingShadow.models || []).map(model => {
    const selected = model.modelId === dropdownModelId ? ' selected' : '';
    const disabled = model.replayValidated ? '' : ' disabled';
    return `<option value="${escapeHtml(model.modelId)}"${selected}${disabled}>${escapeHtml(model.modelId)}${model.replayValidated ? ' (validated)' : ' (not validated)'}</option>`;
  }).join('');

  // Run selector: lets the user switch between discovered runs of the current session and load
  // legacy (schema-v1) history only by explicit selection.
  const runs = fishingShadow.runs || [];
  const nonLegacyRuns = runs.filter(r => !r.legacy && r.shadowRunId);
  const hasLegacy = runs.some(r => r.legacy);
  const runSelectorHtml = (nonLegacyRuns.length > 0 || hasLegacy) ? `
    <div class="shadow-model-controls">
      ${nonLegacyRuns.length > 0 ? `
      <select id="shadowRunSelect" title="Shadow run">
        <option value="">Select a run&hellip;</option>
        ${nonLegacyRuns.map(r => {
          const label = `${r.shadowRunId} · ticks ${r.firstTick ?? '--'}-${r.lastTick ?? '--'}${r.active ? ' (active)' : ''} · ${r.decisionCount} decisions`;
          const sel = fishingShadow.shadowRunId === r.shadowRunId ? ' selected' : '';
          return `<option value="${escapeHtml(r.shadowRunId)}"${sel}>${escapeHtml(label)}</option>`;
        }).join('')}
      </select>` : ''}
      ${hasLegacy && !fishingShadow.showingLegacy ? `<button type="button" class="btn btn-sm btn-outline-secondary" id="shadowLoadLegacyBtn">Load legacy history</button>` : ''}
    </div>` : '';

  const counters = `
    <span class="shadow-counter">Processed: ${escapeHtml(fishingShadow.processed ?? 0)}</span>
    <span class="shadow-counter">Predicted: ${escapeHtml(fishingShadow.predicted ?? 0)}</span>
    <span class="shadow-counter">Abstained: ${escapeHtml(fishingShadow.abstained ?? 0)}</span>`;

  const rows = fishingShadow.decisions.map(d => {
    const dAction = d.action;
    const dTarget = d.targetNpc;
    return `<tr>
      <td>${escapeHtml(d.tick ?? '--')}</td>
      <td><span class="shadow-badge ${d.status === 'PREDICTED' ? 'predicted' : 'abstained'}">${escapeHtml(d.status)}</span></td>
      <td>${dAction ? escapeHtml(dAction.type) : '--'}</td>
      <td>${dAction ? escapeHtml(dAction.option || '') : '--'}</td>
      <td>${dTarget ? escapeHtml(dTarget.name) : '--'}</td>
      <td>${dTarget ? escapeHtml(dTarget.distance) : '--'}</td>
      <td>${d.abstentionReason ? escapeHtml(d.abstentionReason) : '--'}</td>
      <td>${d.policyDistance != null ? escapeHtml(d.policyDistance) : '--'}</td>
      <td>${d.matchedExampleTick != null ? escapeHtml(d.matchedExampleTick) : '--'}</td>
      <td>${d.latencyMicros != null ? escapeHtml(d.latencyMicros) + ' &micro;s' : '--'}</td>
    </tr>`;
  }).join('');

  const actionLine = action
    ? `${escapeHtml(action.type)}${action.option ? ' &middot; ' + escapeHtml(action.option) : ''}${action.target ? ' &rarr; ' + escapeHtml(action.target) : ''}`
    : '--';
  const npcLine = targetNpc
    ? `${escapeHtml(targetNpc.name)}${targetNpc.distance != null ? ', ' + escapeHtml(targetNpc.distance) + ' tiles' : ''}`
    : '--';

  container.innerHTML = `
    <div class="shadow-feature-row">
      ${featureBadge(enabled, 'AI enabled')}
      ${featureBadge(shadowMode, 'Shadow mode')}
      ${featureBadge(!controlEnabled, 'Control disabled')}
      ${featureBadge(readyForInference, 'Ready for inference')}
    </div>
    ${inactiveReason ? `<div class="shadow-feedback error">${escapeHtml(inactiveReason)}</div>` : ''}
    ${activationLoadError ? `<div class="shadow-feedback error">Activation: ${escapeHtml(activationLoadError)}</div>` : ''}
    ${fishingShadow.persistenceError ? `<div class="shadow-feedback error">Persistence: ${escapeHtml(fishingShadow.persistenceError)}</div>` : ''}
    ${runSelectorHtml}
    <div class="shadow-model-controls">
      <select id="shadowModelSelect" title="Active fishing model">
        <option value="">No model selected</option>
        ${optionsHtml}
      </select>
      <button type="button" class="btn btn-sm btn-outline-secondary" id="shadowRefreshModelsBtn">Refresh</button>
      <button type="button" class="btn btn-sm btn-outline-success" id="shadowActivateBtn" ${canActivate ? '' : 'disabled'}>Activate</button>
      <button type="button" class="btn btn-sm btn-outline-danger" id="shadowDeactivateBtn">Deactivate</button>
      <span class="shadow-validation-badge ${validated ? '' : 'not-validated'}">
        ${validated ? 'Replay validated' : 'Not replay-validated'}
      </span>
    </div>
    <div class="shadow-detail-row">
      <span class="shadow-badge ${statusBadgeClass}">${escapeHtml(statusLabel)}</span>
      <span class="shadow-counter">Session: ${escapeHtml(fishingShadow.sessionId || status.sessionId || currentDebugSessionId || '--')}</span>
      <span class="shadow-counter">Run: ${escapeHtml(fishingShadow.shadowRunId || status.shadowRunId || (fishingShadow.showingLegacy ? 'legacy' : '--'))}</span>
      <span class="shadow-counter">Tick: ${escapeHtml(decision ? decision.tick : (status.latestTick ?? '--'))}</span>
    </div>
    <div class="shadow-detail-row">
      <span class="shadow-detail-label">Action</span>
      <span class="shadow-detail-value">${actionLine}</span>
    </div>
    <div class="shadow-detail-row">
      <span class="shadow-detail-label">NPC</span>
      <span class="shadow-detail-value">${npcLine}</span>
    </div>
    <div class="shadow-detail-row">
      <span class="shadow-detail-label">Policy distance</span>
      <span class="shadow-detail-value">${decision && decision.policyDistance != null ? escapeHtml(decision.policyDistance) : '--'}</span>
    </div>
    <div class="shadow-detail-row">
      <span class="shadow-detail-label">Matched demo tick</span>
      <span class="shadow-detail-value">${decision && decision.matchedExampleTick != null ? escapeHtml(decision.matchedExampleTick) : '--'}</span>
    </div>
    <div class="shadow-detail-row">
      <span class="shadow-detail-label">Abstention</span>
      <span class="shadow-detail-value">${decision && decision.abstentionReason ? escapeHtml(decision.abstentionReason) : '--'}</span>
    </div>
    <div class="shadow-detail-row">
      <span class="shadow-detail-label">Inference latency</span>
      <span class="shadow-detail-value">${decision && decision.latencyMicros != null ? escapeHtml(decision.latencyMicros) + ' &micro;s' : '--'}</span>
    </div>
    <div class="shadow-counters">${counters}</div>
    ${fishingShadow.persistenceLifecycle ? `
    <div class="shadow-detail-row">
      <span class="shadow-detail-label">Persistence</span>
      <span class="shadow-detail-value">${escapeHtml(fishingShadow.persistenceLifecycle)}
        &middot; queued ${escapeHtml(fishingShadow.persistenceQueued ?? 0)}
        &middot; in-flight ${escapeHtml(fishingShadow.persistenceInFlight ?? 0)}
        &middot; completed ${escapeHtml(fishingShadow.persistenceCompleted ?? 0)}
        &middot; written ${escapeHtml(fishingShadow.persistenceWritten ?? 0)}
        &middot; failed ${escapeHtml(fishingShadow.persistenceFailed ?? 0)}
        &middot; abandoned ${escapeHtml(fishingShadow.persistenceAbandoned ?? 0)}
        &middot; dropped ${escapeHtml(fishingShadow.persistenceDropped ?? 0)}</span>
    </div>` : ''}
    <div>
      <div class="shadow-detail-label">Latest 20 decisions</div>
      <table class="shadow-actions-table">
        <thead><tr>
          <th>Tick</th><th>Status</th><th>Action</th><th>Option</th><th>Target</th><th>Dist</th><th>Abstention</th><th>Policy dist</th><th>Demo tick</th><th>Latency</th>
        </tr></thead>
        <tbody>${rows || '<tr><td colspan="10" class="text-muted">No decisions yet.</td></tr>'}</tbody>
      </table>
    </div>`;
}

// ==================== INIT ====================

document.addEventListener('DOMContentLoaded', () => {
  cacheElements();
  switchMode('live'); // Initialize mode BEFORE WebSocket connections
  initializeDebuggerWorkspaces();

  elements.liveBtn.addEventListener('click', () => switchMode('live'));
  elements.replayBtn.addEventListener('click', () => switchMode('replay'));

  connectLogWebSocket();
  connectViewportWebSocket();
  connectOverlayWebSocket();
  connectBotStateWebSocket();
  connectTickStreamWebSocket(); // New tick stream WebSocket

  const closeDebuggerPage = () => {
    if (debuggerPageClosing) return;
    debuggerPageClosing = true;
    closeDebuggerWebSockets();
    closeTickStreamWebSocket();
    fishingShadowAbortPendingRequests();
    if (tickPoller) {
      clearInterval(tickPoller);
      tickPoller = null;
    }
    if (aiInspectorPoller) {
      clearInterval(aiInspectorPoller);
      aiInspectorPoller = null;
    }
    if (recordingStatusPoller) {
      clearInterval(recordingStatusPoller);
      recordingStatusPoller = null;
    }
    if (providerStatusPoller) {
      clearInterval(providerStatusPoller);
      providerStatusPoller = null;
    }
    if (windowSelectionPoller) {
      clearInterval(windowSelectionPoller);
      windowSelectionPoller = null;
    }
    if (recordingDiagnosticsPoller) {
      clearInterval(recordingDiagnosticsPoller);
      recordingDiagnosticsPoller = null;
    }
    if (feedStatusPoller) {
      clearInterval(feedStatusPoller);
      feedStatusPoller = null;
    }
    if (recordingDurationInterval) {
      clearInterval(recordingDurationInterval);
      recordingDurationInterval = null;
    }
    if (playbackInterval) {
      clearInterval(playbackInterval);
      playbackInterval = null;
    }
    if (patchLoopCatalogPollTimer) {
      clearInterval(patchLoopCatalogPollTimer);
      patchLoopCatalogPollTimer = null;
    }
    if (window.patchLoopManager && typeof window.patchLoopManager.disconnect === 'function') {
      window.patchLoopManager.disconnect();
    }
  };
  window.addEventListener('pagehide', closeDebuggerPage, { once: true });
  window.addEventListener('beforeunload', closeDebuggerPage, { once: true });

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
  initFishingRecordingLab(); // Recording quality, range, training, and replay workflow
  initFishingShadowAgent(); // Fishing Shadow Agent panel
  initTaskRecordingControls(); // Task recording controls

  // Viewport pause/resume button
  document.getElementById("pauseCaptureBtn")?.addEventListener("click", togglePauseCapture);
  updatePauseButtonState();

  startTickPolling();

  fetchAndRenderWindows();
  if (windowSelectionPoller) clearInterval(windowSelectionPoller);
  windowSelectionPoller = setInterval(fetchAndRenderWindows, 5000);

  if (elements.replayVideo) {
    elements.replayVideo.addEventListener('loadedmetadata', () => resizeOverlayCanvas(getViewportMetrics() || { width: 0, height: 0 }));
  }

  window.addEventListener('resize', () => {
    const metrics = getViewportMetrics();
    if (metrics) resizeOverlayCanvas(metrics);
  });

  if (aiInspectorPoller) clearInterval(aiInspectorPoller);
  aiInspectorPoller = setInterval(fetchAiInspector, 5000);
  fetchAiInspector();

  // Start recording diagnostics polling
  if (recordingDiagnosticsPoller) clearInterval(recordingDiagnosticsPoller);
  recordingDiagnosticsPoller = setInterval(() => {
    if (currentMode === 'live') {
      updateRecordingDiagnostics();
    }
  }, 2000);
  updateRecordingDiagnostics();

  // Start feed status polling
  if (feedStatusPoller) clearInterval(feedStatusPoller);
  feedStatusPoller = setInterval(updateFeedStatus, 5000);
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
    } else if (sessionIdParam) {
      await loadDebugSession(sessionIdParam);
    } else if (modeParam === 'replay') {
      // mode=replay only: stay in replay mode with selector usable, no auto-load
      console.info('[deep-link] mode=replay, no recording resolved; leaving selector open');
    }
  })();
});

window.ChromaScapePatchLoop = window.ChromaScapePatchLoop || {
  getDefaultTemplate() {
    return DEFAULT_PATCH_LOOP_TEMPLATE;
  },
  seedRecoveryWorkspace(seed) {
    return seedPatchLoopFromRecoveryWorkspace(seed);
  },
  getActiveSeed() {
    return clonePatchLoopSeed(activePatchLoopSeed);
  },
  clearSeed() {
    clearPatchLoopSeed();
  }
};

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

/* =========================================================================
 * Recovery Mode replay accessor (added for same-screen recovery editing).
 * Exposes the current replay position / session so account-task-planner.js can
 * drive inline recovery range editing without duplicating the replay surface.
 * ========================================================================= */
window.ChromaScapeReplay = window.ChromaScapeReplay || {
  getReplayVideo() {
    return document.getElementById('replayVideo');
  },
  getCurrentTimeSeconds() {
    const el = document.getElementById('currentTime');
    if (!el || !el.textContent || el.textContent.indexOf('--') === 0) {
      const video = this.getReplayVideo();
      return video && Number.isFinite(video.currentTime) ? video.currentTime : null;
    }
    const parts = el.textContent.trim().split(':');
    if (parts.length < 2) {
      return null;
    }
    const minutes = Number(parts[0]);
    const seconds = Number(parts[1]);
    if (!Number.isFinite(minutes) || !Number.isFinite(seconds)) {
      return null;
    }
    return (minutes * 60) + seconds;
  },
  getCurrentTick() {
    const el = document.getElementById('currentTick');
    if (!el || !el.textContent || el.textContent === '--') {
      return typeof currentTick !== 'undefined' ? currentTick : null;
    }
    const tick = Number(el.textContent);
    return Number.isFinite(tick) ? tick : (typeof currentTick !== 'undefined' ? currentTick : null);
  },
  getCurrentFrame() {
    const el = document.getElementById('currentFrame');
    if (!el || !el.textContent || el.textContent === '--') {
      return null;
    }
    const frame = Number(el.textContent);
    return Number.isFinite(frame) ? frame : null;
  },
  getReplayDebugSessionId() {
    if (typeof currentReplayDebugSessionId !== 'undefined' && currentReplayDebugSessionId) {
      return currentReplayDebugSessionId;
    }
    if (typeof currentDebugSessionId !== 'undefined' && currentDebugSessionId) {
      return currentDebugSessionId;
    }
    return null;
  },
  getReplayState() {
    return {
      mode: typeof currentMode !== 'undefined' ? currentMode : null,
      currentTimeSeconds: this.getCurrentTimeSeconds(),
      currentTick: this.getCurrentTick(),
      currentFrame: this.getCurrentFrame(),
      replayDebugSessionId: this.getReplayDebugSessionId()
    };
  },
  switchMode(mode) {
    if (typeof switchMode === 'function') {
      switchMode(mode);
    }
  },
  /**
   * Repopulates the shared recording picker so operators can load any replay (for example in the
   * recovery workspace, where the opened run may have no replay of its own).
   */
  refreshRecordings() {
    if (typeof loadRecordingSessions === 'function') {
      return loadRecordingSessions();
    }
    return Promise.resolve();
  },
  loadRecording(recordingId) {
    if (typeof loadRecording === 'function') {
      return loadRecording(recordingId);
    }
  },
  loadDebugSession(sessionId) {
    if (typeof loadDebugSession === 'function') {
      return loadDebugSession(sessionId);
    }
  },
  // Poll-safe variant: probes the recording endpoint first and only loads on success, returning
  // false (without alert spam) when the recording is unavailable. Used during recovery bind polls.
  async loadRecordingQuietly(recordingId) {
    if (!recordingId || typeof loadRecording !== 'function') {
      return false;
    }
    try {
      const probe = await fetch(`/api/recordings/${recordingId}`);
      if (!probe.ok) {
        console.debug('[Recovery] Recording not yet available:', recordingId, probe.status);
        return false;
      }
      loadRecording(recordingId);
      return true;
    } catch (error) {
      console.debug('[Recovery] Recording probe failed:', error);
      return false;
    }
  }
};

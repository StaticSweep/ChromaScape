/**
 * ChromaScape Debugger Page
 * Unified layout with live viewport, logs, scrubber, bot state, and AI inspector.
 */

let currentMode = 'live';
let currentRecording = null;
let currentRecordingSessionId = null;
let currentLogLevel = 'all';
let logWebSocket = null;
let overlayWebSocket = null;
let botStateWebSocket = null;
let viewportWebSocket = null;
let tickPoller = null;
let aiInspectorPoller = null;

// Recording state variables
let isRecording = false;
let currentRecordingId = null;
let recordingStartTime = null;
let recordingDurationInterval = null;

const elements = {};

function cacheElements() {
  elements.liveBtn = document.getElementById('liveModeBtn');
  elements.replayBtn = document.getElementById('replayModeBtn');
  elements.logsContainer = document.getElementById('logsContainer');
  elements.viewportImage = document.getElementById('viewport-image');
  elements.replayVideo = document.getElementById('replayVideo');
  elements.viewportLoader = document.getElementById('viewport-loader');
  elements.overlayCanvas = document.getElementById('overlay-canvas');
  elements.sessionSelect = document.getElementById('sessionSelect');
  elements.refreshSessionsBtn = document.getElementById('refreshSessionsBtn');
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
  elements.recordingDuration = document.getElementById('recordingDuration');
  // Task recording elements
  elements.taskRecordingPanel = document.getElementById('taskRecordingPanel');
  elements.taskToggles = document.getElementById('taskToggles');
  elements.resetTasksBtn = document.getElementById('resetTasksBtn');
}

function switchMode(mode) {
  currentMode = mode;
  elements.liveBtn.classList.toggle('active', mode === 'live');
  elements.replayBtn.classList.toggle('active', mode === 'replay');

  const isReplay = mode === 'replay';
  elements.scrubberMode.textContent = isReplay ? 'Replay' : 'Live';

  if (isReplay) {
    elements.viewportImage.style.display = 'none';
    elements.replayVideo.style.display = 'block';
    elements.viewportLoader.style.display = 'none';
    clearOverlayCanvas();
    loadRecordingSessions();
    setScrubberEnabled(true);
  } else {
    elements.replayVideo.style.display = 'none';
    elements.viewportImage.style.display = 'block';
    elements.viewportLoader.style.display = 'flex';
    clearOverlayCanvas();
    setScrubberEnabled(false);
  }

  const sessionControls = document.querySelector('.session-controls');
  if (sessionControls) {
    sessionControls.style.opacity = isReplay ? '1' : '0.5';
    sessionControls.querySelectorAll('select, button').forEach(el => {
      el.disabled = !isReplay;
    });
  }

  clearLogs();
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
  elements.logsContainer.innerHTML = '';
  elements.logsContainer.innerHTML = '<div class="text-muted fst-italic">Waiting for logs...</div>';
}

// ==================== LOGGER ====================

function connectLogWebSocket() {
  const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
  const wsUrl = `${wsProtocol}://${location.host}/ws/logs`;

  let reconnectTimeout;

  function connect() {
    logWebSocket = new WebSocket(wsUrl);

    logWebSocket.onopen = () => {
      clearTimeout(reconnectTimeout);
    };

    logWebSocket.onmessage = (event) => {
      if (currentMode !== 'live') return;
      appendLogLine(event.data, elements.logsContainer);
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

function appendLogLine(data, outputContainer) {
  try {
    const log = JSON.parse(data);
    const level = log.level || 'INFO';
    const message = log.message || data;

    const line = document.createElement('div');
    line.className = `log-line log-${level.toLowerCase()}`;

    const timestamp = log.timestamp
      ? new Date(log.timestamp).toLocaleTimeString()
      : new Date().toLocaleTimeString();
    line.innerHTML = `<span class="log-timestamp">${timestamp}</span> <span class="log-level">[${level}]</span> ${message}`;

    outputContainer.appendChild(line);
    outputContainer.scrollTop = outputContainer.scrollHeight;

    while (outputContainer.children.length > 1000) {
      outputContainer.removeChild(outputContainer.firstChild);
    }
  } catch (e) {
    const line = document.createElement('div');
    line.className = 'log-line';
    line.textContent = data;
    outputContainer.appendChild(line);
  }
}

// ==================== VIEWPORT ====================

function connectViewportWebSocket() {
  if (viewportWebSocket && viewportWebSocket.readyState === WebSocket.OPEN) {
    return;
  }

  viewportWebSocket = new WebSocket(`ws://${window.location.host}/ws/viewport`);

  viewportWebSocket.onopen = () => {
    console.log('Connected to viewport WebSocket');
  };

  viewportWebSocket.onmessage = (event) => {
    if (currentMode !== 'live') return;
    elements.viewportLoader.style.display = 'none';
    elements.viewportImage.style.display = 'block';
    elements.viewportImage.src = event.data;
  };

  viewportWebSocket.onerror = (error) => {
    console.error('Viewport WebSocket error:', error);
  };

  viewportWebSocket.onclose = () => {
    viewportWebSocket = null;
  };
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
  const activeElement = currentMode === 'replay' ? elements.replayVideo : elements.viewportImage;
  if (!activeElement) return null;

  const width = activeElement.clientWidth;
  const height = activeElement.clientHeight;
  if (!width || !height) return null;

  const sourceWidth = activeElement.videoWidth || activeElement.naturalWidth || width;
  const sourceHeight = activeElement.videoHeight || activeElement.naturalHeight || height;

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
        loadRecording(recordingId);
      }
    });
  }

  if (elements.replayVideo) {
    elements.replayVideo.addEventListener('timeupdate', () => {
      if (currentMode === 'replay' && currentRecording) {
        updateScrubberFromVideo();
        if (Math.floor(elements.replayVideo.currentTime) % 2 === 0) {
          syncLogsToVideo(elements.replayVideo.currentTime * 1000);
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
  const duration = Math.floor(elements.replayVideo.duration || 0);
  const current = Math.floor(elements.replayVideo.currentTime || 0);

  elements.scrubberRange.max = duration;
  elements.scrubberRange.value = current;
  elements.scrubberTimecode.textContent = `${formatTime(current)} / ${formatTime(duration)}`;
}

async function loadRecordingSessions() {
  if (!elements.sessionSelect) return;

  try {
    const response = await fetch('/api/recordings/sessions');
    if (!response.ok) {
      throw new Error('Failed to load sessions');
    }

    const sessions = await response.json();
    elements.sessionSelect.innerHTML = '<option value="">-- Select a recording session --</option>';

    sessions.forEach(session => {
      const option = document.createElement('option');
      option.value = session.recordingId;
      const startTime = new Date(session.startTime).toLocaleString();
      const duration = session.endTime
        ? Math.round((new Date(session.endTime) - new Date(session.startTime)) / 1000 / 60) + ' min'
        : 'In progress';
      option.textContent = `${session.accountName || 'Unknown'} - ${startTime} (${duration})`;
      elements.sessionSelect.appendChild(option);
    });

    if (sessions.length === 0) {
      const option = document.createElement('option');
      option.textContent = 'No recording sessions found';
      option.disabled = true;
      elements.sessionSelect.appendChild(option);
    }
  } catch (error) {
    console.error('Failed to load recording sessions:', error);
    elements.sessionSelect.innerHTML = '<option value="">Error loading sessions</option>';
  }
}

async function loadRecording(recordingId) {
  currentRecording = recordingId;

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
      return;
    }

    const recording = await response.json();
    currentRecordingSessionId = recording.id;

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

    // Load logs for this recording
    const logResponse = await fetch(`/api/logs/timeline?recordingId=${recordingId}&videoTimeMs=0`);
    if (logResponse.ok) {
      const result = await logResponse.json();
      renderReplayLogs(result.logs);
    } else {
      console.warn('Failed to load logs for recording, status:', logResponse.status);
      // Don't block video playback for log loading failure
    }

  } catch (error) {
    console.error('Failed to load recording:', error);
    alert('An error occurred while loading the recording:\n' + error.message);
  }
}

async function syncLogsToVideo(videoMs) {
  if (!currentRecording) return;

  try {
    const response = await fetch(`/api/logs/timeline?recordingId=${currentRecording}&videoTimeMs=${Math.floor(videoMs)}`);
    if (!response.ok) {
      return;
    }

    const result = await response.json();
    renderReplayLogs(result.logs);
  } catch (error) {
    console.error('Failed to sync logs:', error);
  }
}

function renderReplayLogs(logs) {
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  outputContainer.innerHTML = '';

  if (!logs || logs.length === 0) {
    outputContainer.innerHTML = '<div class="text-muted fst-italic">No logs for this time period</div>';
    return;
  }

  logs.forEach(log => {
    appendLogLine(JSON.stringify(log), outputContainer);
  });
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
}

// ==================== LOG FILTER ====================

function initLogFilters() {
  const levelButtons = document.querySelectorAll('[data-level]');
  const logSearchBtn = document.getElementById('logSearchBtn');

  if (logSearchBtn) {
    logSearchBtn.addEventListener('click', filterLogs);
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
  const outputContainer = elements.logsContainer;
  if (!outputContainer) return;

  const searchText = document.getElementById('logSearchInput')?.value.toLowerCase() || '';
  const allLogLines = outputContainer.querySelectorAll('.log-line');

  allLogLines.forEach(line => {
    const text = line.textContent.toLowerCase();
    const matchesSearch = text.includes(searchText);
    let matchesLevel = true;

    if (currentLogLevel !== 'all') {
      matchesLevel = line.classList.contains(`log-${currentLogLevel.toLowerCase()}`);
    }

    line.style.display = (matchesSearch && matchesLevel) ? 'block' : 'none';
  });
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
    if (response.status === 404) {
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
    if (currentMode !== 'live') return;
    try {
      const data = JSON.parse(event.data);
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
 * Update all panels for a specific tick.
 */
function updatePanelsForTick(data) {
  if (data.tick) {
    currentTick = data.tick;
    updateTickDisplay(data.tick);
  }

  if (data.snapshot) {
    renderTickBotState(data.snapshot);
  }

  if (data.logs && data.logs.length > 0) {
    renderTickLogs(data.logs);
  }

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
    currentTickEl.textContent = tick;
  }

  // Calculate time from tick (600ms per tick)
  const timeSeconds = (tick * 0.6).toFixed(1);
  const currentTimeEl = document.getElementById('currentTime');
  if (currentTimeEl) {
    currentTimeEl.textContent = formatTickTime(timeSeconds);
  }

  // Calculate frame from tick (assuming 30 FPS)
  const frame = Math.round(tick * 0.6 * 30);
  const currentFrameEl = document.getElementById('currentFrame');
  if (currentFrameEl) {
    currentFrameEl.textContent = frame;
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
  const position = player.position || {};
  const hp = player.hp || {};
  const prayer = player.prayer || {};

  const items = [
    { label: 'Position', value: position.x && position.y ? `(${position.x}, ${position.y}, ${position.plane || 0})` : '--' },
    { label: 'HP', value: hp.current !== undefined ? `${hp.current} / ${hp.max}` : '--' },
    { label: 'Prayer', value: prayer.current !== undefined ? `${prayer.current} / ${prayer.max}` : '--' },
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
  const container = elements.logsContainer;
  if (!container) return;

  logs.forEach(log => {
    const tick = log.tick || currentTick || '--';
    const level = log.severity || log.level || 'INFO';
    const subsystem = log.subsystem || '';
    const message = log.message || '';

    const line = document.createElement('div');
    line.className = `log-line log-${level.toLowerCase()}`;
    line.dataset.tick = tick;

    const timestamp = log.timestamp
      ? new Date(log.timestamp).toLocaleTimeString()
      : new Date().toLocaleTimeString();

    line.innerHTML = `<span class="log-tick">T${tick}</span> <span class="log-subsystem">${subsystem}</span> <span class="log-level">[${level}]</span> ${message}`;
    line.title = message; // Tooltip for full message

    // Add hover effect to highlight overlay
    line.addEventListener('mouseenter', () => highlightTickOverlay(tick));
    line.addEventListener('mouseleave', () => clearHighlightOverlay());

    container.appendChild(line);
    container.scrollTop = container.scrollHeight;

    // Limit log entries
    while (container.children.length > 1000) {
      container.removeChild(container.firstChild);
    }
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

    const data = await response.json;
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
    const response = await fetch('/api/recordings/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        accountName: currentWindowId || 'Unknown',
        scriptName: 'Debug Session',
        windowTitle: document.getElementById('selectedWindowLabel')?.textContent || ''
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

  } catch (error) {
    console.error('❌ Failed to start recording:', error);
    showNotification(error.message, 'error');
    alert('Failed to start recording: ' + error.message);
  }
}

/**
 * Stop the active recording session.
 */
async function stopRecording() {
  try {
    const response = await fetch('/api/recordings/stop', {
      method: 'POST'
    });

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

    console.log('✅ Recording stopped:', currentRecordingId);
    showNotification('Recording saved', 'success');

    // Refresh sessions list if in replay mode
    if (currentMode === 'replay') {
      loadRecordingSessions();
    }

    currentRecordingId = null;
    recordingStartTime = null;

  } catch (error) {
    console.error('❌ Failed to stop recording:', error);
    alert('Failed to stop recording: ' + error.message);
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

    console.log('✅ Recording state reset:', data);
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
      console.log('📹 Synchronizing: Backend has active recording, updating frontend state');

      isRecording = true;
      currentRecordingId = data.recordingId;
      recordingStartTime = data.startTime ? new Date(data.startTime).getTime() : Date.now();

      // Update UI to recording state
      elements.recordBtn.classList.remove('btn-danger');
      elements.recordBtn.classList.add('btn-success');
      elements.recordBtnText.textContent = 'Stop Recording';

      if (elements.recordingStatus) {
        elements.recordingStatus.classList.remove('d-none');
      }

      startRecordingDurationTimer();

      console.log('📹 Recording already in progress:', currentRecordingId);

    } else if (!backendRecording && isRecording) {
      // Frontend thinks we're recording, but backend says no
      console.warn('⚠️ State mismatch: Frontend thinks recording, but backend says no. Resetting frontend state.');

      isRecording = false;
      currentRecordingId = null;
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

// ==================== INIT ====================

document.addEventListener('DOMContentLoaded', () => {
  cacheElements();

  elements.liveBtn.addEventListener('click', () => switchMode('live'));
  elements.replayBtn.addEventListener('click', () => switchMode('replay'));

  connectLogWebSocket();
  connectViewportWebSocket();
  connectOverlayWebSocket();
  connectBotStateWebSocket();
  connectTickStreamWebSocket(); // New tick stream WebSocket

  initReplayModeListeners();
  initTickControls();
  initLogFilters();
  initTickReplayControls(); // New tick-based replay controls
  initRecordingControls(); // Recording controls
  initTaskRecordingControls(); // Task recording controls

  startTickPolling();

  switchMode('live');

  fetchAndRenderWindows();
  setInterval(fetchAndRenderWindows, 5000);

  if (elements.viewportImage) {
    elements.viewportImage.addEventListener('load', () => renderOverlays({ clicks: [], entities: [], mousePosition: null }));
  }

  if (elements.replayVideo) {
    elements.replayVideo.addEventListener('loadedmetadata', () => resizeOverlayCanvas(getViewportMetrics() || { width: 0, height: 0 }));
  }

  window.addEventListener('resize', () => {
    const metrics = getViewportMetrics();
    if (metrics) resizeOverlayCanvas(metrics);
  });

  aiInspectorPoller = setInterval(fetchAiInspector, 5000);
  fetchAiInspector();
});

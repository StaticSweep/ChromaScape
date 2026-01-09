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
    if (!response.ok) {
      throw new Error('Failed to load recording');
    }

    const recording = await response.json();
    currentRecordingSessionId = recording.id;

    if (elements.replayVideo && recording.videoPath) {
      elements.replayVideo.src = recording.videoPath;
      elements.replayVideo.load();
    }

    const logResponse = await fetch(`/api/logs/timeline?recordingId=${recordingId}&videoTimeMs=0`);
    if (logResponse.ok) {
      const result = await logResponse.json();
      renderReplayLogs(result.logs);
    }
  } catch (error) {
    console.error('Failed to load recording:', error);
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

// ==================== INIT ====================

document.addEventListener('DOMContentLoaded', () => {
  cacheElements();

  elements.liveBtn.addEventListener('click', () => switchMode('live'));
  elements.replayBtn.addEventListener('click', () => switchMode('replay'));

  connectLogWebSocket();
  connectViewportWebSocket();
  connectOverlayWebSocket();
  connectBotStateWebSocket();
  initReplayModeListeners();
  initTickControls();
  initLogFilters();
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

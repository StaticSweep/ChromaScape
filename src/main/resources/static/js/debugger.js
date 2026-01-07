/**
 * ChromaScape Debugger Page
 * Provides Live Mode (real-time logs + viewport) and Replay Mode (video + synchronized logs)
 */

// ==================== MODE SWITCHING ====================

let currentMode = 'live'; // 'live' or 'replay'
let currentRecording = null;
let currentLogLevel = 'all';
let logViewportWebSocket = null;
let logReplayWebSocket = null;

/**
 * Switches between Live and Replay modes.
 */
function switchMode(mode) {
  currentMode = mode;
  const liveMode = document.getElementById('liveMode');
  const replayMode = document.getElementById('replayMode');
  const liveBtn = document.getElementById('liveModeBtn');
  const replayBtn = document.getElementById('replayModeBtn');

  if (mode === 'live') {
    liveMode.style.display = 'block';
    replayMode.style.display = 'none';
    liveBtn.classList.add('active');
    replayBtn.classList.remove('active');

    // Reconnect live WebSockets if needed
    if (!logViewportWebSocket) {
      connectLogWebSocket();
      connectViewportWebSocket();
    }
  } else {
    liveMode.style.display = 'none';
    replayMode.style.display = 'block';
    liveBtn.classList.remove('active');
    replayBtn.classList.add('active');

    // Load recording sessions
    loadRecordingSessions();
  }
}

// Initialize mode switcher buttons
document.addEventListener('DOMContentLoaded', () => {
  const liveBtn = document.getElementById('liveModeBtn');
  const replayBtn = document.getElementById('replayModeBtn');

  if (liveBtn) {
    liveBtn.addEventListener('click', () => switchMode('live'));
  }
  if (replayBtn) {
    replayBtn.addEventListener('click', () => switchMode('replay'));
  }

  // Initialize live mode connections
  connectLogWebSocket();
  connectViewportWebSocket();

  // Initialize replay mode event listeners
  initReplayModeListeners();
});

// ==================== LOGGER ====================

/**
 * Establishes WebSocket connection for real-time log streaming.
 */
function connectLogWebSocket() {
  const wsProtocol = location.protocol === "https:" ? "wss" : "ws";
  const wsUrl = `${wsProtocol}://${location.host}/ws/logs`;
  const consoleOutput = document.getElementById("consoleOutput");

  let reconnectTimeout;

  function connect() {
    logViewportWebSocket = new WebSocket(wsUrl);

    logViewportWebSocket.onopen = () => {
      console.log("Connected to log WebSocket:", wsUrl);
      clearTimeout(reconnectTimeout);
    };

    logViewportWebSocket.onmessage = (event) => {
      appendLogLine(event.data, consoleOutput);
    };

    logViewportWebSocket.onclose = () => {
      console.log("Log WebSocket disconnected, reconnecting in 3s...");
      logViewportWebSocket = null;
      reconnectTimeout = setTimeout(connect, 3000);
    };

    logViewportWebSocket.onerror = (error) => {
      console.error("Log WebSocket error:", error);
    };
  }

  connect();
}

/**
 * Helper function to parse and display log lines in a given output container.
 */
function appendLogLine(data, outputContainer) {
  try {
    const log = JSON.parse(data);
    const level = log.level || "INFO";
    const message = log.message || data;

    const line = document.createElement("div");
    line.className = `log-line log-${level.toLowerCase()}`;

    // Format timestamp if available
    const timestamp = log.timestamp ? new Date(log.timestamp).toLocaleTimeString() : new Date().toLocaleTimeString();
    line.innerHTML = `<span class="log-timestamp">${timestamp}</span> <span class="log-level">[${level}]</span> ${message}`;

    outputContainer.appendChild(line);

    // Auto-scroll to bottom
    outputContainer.scrollTop = outputContainer.scrollHeight;

    // Limit log lines to prevent memory issues
    while (outputContainer.children.length > 1000) {
      outputContainer.removeChild(outputContainer.firstChild);
    }
  } catch (e) {
    // Fallback for non-JSON log lines
    const line = document.createElement("div");
    line.className = "log-line";
    line.textContent = data;
    outputContainer.appendChild(line);
  }
}

// ==================== VIEWPORT ====================

/**
 * Initializes the viewport WebSocket connection for screen capture.
 */
function connectViewportWebSocket() {
  const viewportContainer = document.getElementById("viewport-container");
  if (!viewportContainer) {
    console.error("Viewport container not found");
    return;
  }

  // Clear initial content
  viewportContainer.innerHTML = "";

  // Create the wrapper and image elements
  const wrapper = document.createElement("div");
  wrapper.className = "text-center p-2";

  // Loading indicator
  const loaderContainer = document.createElement("div");
  loaderContainer.id = "viewport-loader";
  loaderContainer.className = "loader-container";

  const loader = document.createElement("div");
  loader.className = "glass-loader";

  const loaderText = document.createElement("div");
  loaderText.className = "loader-text";
  loaderText.innerText = "Waiting for Visual Input...";

  loaderContainer.appendChild(loader);
  loaderContainer.appendChild(loaderText);

  // Main image element (hidden initially)
  const img = document.createElement("img");
  img.id = "viewport-image";
  img.className = "img-fluid border border-secondary rounded shadow-sm";
  img.style.maxHeight = "100%";
  img.style.display = "none";

  wrapper.appendChild(loaderContainer);
  wrapper.appendChild(img);
  viewportContainer.appendChild(wrapper);

  // WebSocket connection
  const socket = new WebSocket("ws://" + window.location.host + "/ws/viewport");

  socket.onopen = () => {
    console.log("Connected to viewport WebSocket");
  };

  socket.onmessage = (event) => {
    try {
      // Hide loader and show image
      if (loaderContainer.style.display !== "none") {
        loaderContainer.style.display = "none";
        img.style.display = "inline-block";
      }

      // Update image source with new frame
      img.src = event.data;
    } catch (e) {
      console.error("Failed to process viewport message", e);
    }
  };

  socket.onerror = (error) => {
    console.error("Viewport WebSocket error:", error);
  };

  socket.onclose = () => {
    console.log("Viewport WebSocket disconnected");
  };
}

// ==================== REPLAY MODE ====================

/**
 * Initializes event listeners for replay mode functionality.
 */
function initReplayModeListeners() {
  const refreshBtn = document.getElementById('refreshSessionsBtn');
  const sessionSelect = document.getElementById('sessionSelect');
  const syncLogsBtn = document.getElementById('syncLogsBtn');
  const videoPlayer = document.getElementById('replayVideo');
  const logSearchBtn = document.getElementById('logSearchBtn');
  const levelButtons = document.querySelectorAll('[data-level]');

  if (refreshBtn) {
    refreshBtn.addEventListener('click', loadRecordingSessions);
  }

  if (sessionSelect) {
    sessionSelect.addEventListener('change', (e) => {
      const recordingId = e.target.value;
      if (recordingId) {
        loadRecording(recordingId);
      }
    });
  }

  if (syncLogsBtn) {
    syncLogsBtn.addEventListener('click', () => {
      if (currentRecording && videoPlayer) {
        const videoMs = videoPlayer.currentTime * 1000;
        syncLogsToVideo(videoMs);
      }
    });
  }

  if (videoPlayer) {
    videoPlayer.addEventListener('timeupdate', () => {
      if (currentMode === 'replay' && currentRecording) {
        updateVideoTimestamp();
        // Auto-sync logs every 2 seconds of video playback
        if (Math.floor(videoPlayer.currentTime) % 2 === 0) {
          const videoMs = videoPlayer.currentTime * 1000;
          syncLogsToVideo(videoMs);
        }
      }
    });

    videoPlayer.addEventListener('loadedmetadata', () => {
      updateVideoTimestamp();
    });
  }

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

/**
 * Loads available recording sessions from the server.
 */
async function loadRecordingSessions() {
  const sessionSelect = document.getElementById('sessionSelect');
  if (!sessionSelect) return;

  try {
    const response = await fetch('/api/recordings/sessions');
    if (!response.ok) {
      throw new Error('Failed to load sessions');
    }

    const sessions = await response.json();

    sessionSelect.innerHTML = '<option value="">-- Select a recording session --</option>';

    sessions.forEach(session => {
      const option = document.createElement('option');
      option.value = session.recordingId;
      const startTime = new Date(session.startTime).toLocaleString();
      const duration = session.endTime ?
        Math.round((new Date(session.endTime) - new Date(session.startTime)) / 1000 / 60) + ' min' :
        'In progress';
      option.textContent = `${session.accountName || 'Unknown'} - ${startTime} (${duration})`;
      sessionSelect.appendChild(option);
    });

    if (sessions.length === 0) {
      const option = document.createElement('option');
      option.textContent = 'No recording sessions found';
      option.disabled = true;
      sessionSelect.appendChild(option);
    }
  } catch (error) {
    console.error('Failed to load recording sessions:', error);
    sessionSelect.innerHTML = '<option value="">Error loading sessions</option>';
  }
}

/**
 * Loads a specific recording for playback.
 */
async function loadRecording(recordingId) {
  currentRecording = recordingId;
  const videoPlayer = document.getElementById('replayVideo');

  try {
    const response = await fetch(`/api/recordings/${recordingId}`);
    if (!response.ok) {
      throw new Error('Failed to load recording');
    }

    const recording = await response.json();

    if (videoPlayer && recording.videoPath) {
      videoPlayer.src = recording.videoPath;
      videoPlayer.load();
    }

    // Load initial logs for the recording
    const logResponse = await fetch(`/api/logs/timeline?recordingId=${recordingId}&videoTimeMs=0`);
    if (logResponse.ok) {
      const result = await logResponse.json();
      renderReplayLogs(result.logs);
    }
  } catch (error) {
    console.error('Failed to load recording:', error);
  }
}

/**
 * Synchronizes logs to the current video position.
 */
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

/**
 * Renders replay logs in the log output container.
 */
function renderReplayLogs(logs) {
  const outputContainer = document.getElementById('replayLogOutput');
  if (!outputContainer) return;

  // Clear existing logs
  outputContainer.innerHTML = '';

  if (!logs || logs.length === 0) {
    outputContainer.innerHTML = '<div class="text-muted fst-italic">No logs for this time period</div>';
    return;
  }

  logs.forEach(log => {
    appendLogLine(JSON.stringify(log), outputContainer);
  });
}

/**
 * Updates the video timestamp display.
 */
function updateVideoTimestamp() {
  const videoPlayer = document.getElementById('replayVideo');
  const timestampDisplay = document.getElementById('videoTimestamp');

  if (!videoPlayer || !timestampDisplay) return;

  const current = formatTime(videoPlayer.currentTime);
  const duration = formatTime(videoPlayer.duration || 0);
  timestampDisplay.textContent = `${current} / ${duration}`;
}

/**
 * Formats time in seconds to MM:SS format.
 */
function formatTime(seconds) {
  if (isNaN(seconds)) return '00:00';

  const mins = Math.floor(seconds / 60);
  const secs = Math.floor(seconds % 60);
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
}

/**
 * Filters logs based on search text and log level.
 */
function filterLogs() {
  const outputContainer = document.getElementById('replayLogOutput');
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

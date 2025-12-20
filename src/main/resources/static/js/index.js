/**
 * Selected window mode for script execution ("Fixed" or "Resizable").
 * @type {string|null}
 */
let selectedWindowMode = null;

/**
 * Name of the script currently selected in the UI.
 * @type {string|null}
 */
let selectedScriptName = null;

/**
 * Reference to the previously selected list element to manage highlighting.
 * @type {HTMLElement|null}
 */
let previouslySelectedElement = null;

/**
 * Indicates whether the script is currently started.
 * @type {boolean}
 */
let isStarted = false;

/**
 * Initializes the UI after the DOM content is fully loaded.
 * Connects WebSockets, fetches scripts, and sets up UI elements.
 */
document.addEventListener("DOMContentLoaded", () => {
    (async () => {
        try {
            await initializeUI();
        } catch (err) {
            console.error("UI initialization failed:", err);
        }
    })();
});

/**
 * Initializes the UI by setting up WebSocket connections and UI elements.
 */
async function initializeUI() {
    connectLogWebSocket();
    connectStateWebSocket();

    try {
        await fetchAndRenderScripts();
    } catch (error) {
        console.error("Failed to initialize scripts:", error);
    }

    setupWindowModeDropdown();
    setupStartStopButton();
}

// ----------------- CONTROL STATE MANAGEMENT -----------------

/**
 * Updates the enabled/disabled state of the UI controls based on whether a script is running.
 * @param {boolean} running - Whether a script is currently running
 */
function updateControlsState(running) {
    const screenshotterBtn = document.getElementById("screenshotterButton");
    const scriptList = document.getElementById("script-list");
    const startBtn = document.getElementById("startButton");

    // Disable/Enable Screenshotter Button
    if (screenshotterBtn) {
        screenshotterBtn.disabled = running;
    }

    // Disable/Enable Script List
    if (scriptList) {
        if (running) {
            scriptList.classList.add("disabled-script-list");
        } else {
            scriptList.classList.remove("disabled-script-list");
        }
    }
}

// ----------------- SCRIPT FETCH + UI -----------------

/**
 * Fetches the list of available scripts from the backend and renders them in the UI.
 * Connects to the /api/scripts endpoint.
 */
async function fetchAndRenderScripts() {
    try {
        const response = await fetch("/api/scripts");
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const scripts = await response.json();
        renderScriptList(scripts);
    } catch (err) {
        console.error("Error fetching scripts:", err);
    }
}

/**
 * Renders the script list in the sidebar and sets up selection highlighting.
 * Populates the 'script-list' unordered list element.
 * 
 * @param {string[]} scripts - List of script names to display.
 */
function renderScriptList(scripts) {
    const listGroup = document.getElementById("script-list");
    if (!listGroup) return;

    listGroup.innerHTML = ""; // Clear existing list

    scripts.forEach(script => {
        if (script === "package-info.java") return;
        if (script === "Screenshotter.java") return; // Exclude Screenshotter

        const listItem = document.createElement("li");
        listItem.className = "list-group-item d-flex justify-content-between align-items-start list-group-item-action bg-med";
        listItem.style.cursor = "pointer";

        const title = document.createElement("div");
        title.className = "fw-bold p-2"; // Removed text-white to allow CSS to control color
        title.textContent = script;

        listItem.appendChild(title);
        listGroup.appendChild(listItem);

        listItem.addEventListener("click", () => {
            if (isStarted) return; // double check logic if pointer-events fails or via keyboard

            if (previouslySelectedElement) {
                previouslySelectedElement.classList.remove("bg-light");
                previouslySelectedElement.classList.add("bg-med");
            }

            listItem.classList.remove("bg-med");
            listItem.classList.add("bg-light");

            selectedScriptName = script;
            previouslySelectedElement = listItem;
        });
    });
}

// ----------------- WINDOW MODE -----------------

/**
 * Sets up the dropdown for selecting window mode.
 * Updates the UI text and stores the selected value.
 * Listeners are attached to all elements with class 'dropdown-item'.
 */
function setupWindowModeDropdown() {
    document.querySelectorAll(".dropdown-item").forEach(item => {
        item.addEventListener("click", e => {
            e.preventDefault();
            const dropdown = document.getElementById("windowModeDropdown");
            dropdown.textContent = item.textContent;
            selectedWindowMode = item.getAttribute("data-value");
        });
    });
}

// ----------------- LOGS -----------------

/**
 * Establishes a WebSocket connection to the backend log stream.
 * Incoming logs are appended to the console output terminal in real time.
 * Handles automatic reconnection on closure.
 */
function connectLogWebSocket() {
    const wsProtocol = location.protocol === "https:" ? "wss" : "ws";
    const wsUrl = `${wsProtocol}://${location.host}/ws/logs`;
    let ws;

    function initialize() {
        ws = new WebSocket(wsUrl);

        ws.onopen = () => console.log("Connected to log WebSocket:", wsUrl);

        ws.onmessage = (event) => appendLogLine(event.data);

        ws.onclose = (event) => {
            console.warn("Log WebSocket closed:", event.reason);
            setTimeout(initialize, 2000); // reconnect
        };

        ws.onerror = (error) => {
            console.error("WebSocket error:", error);
            ws.close();
        };
    }

    initialize();
}

/**
 * Appends a single log line to the terminal output.
 * Parses the log data as JSON if possible to style by log level.
 * Auto-scrolls to the bottom if the user is near the end of the log.
 * 
 * @param {string} data - Raw log data string (JSON or plain text)
 */
function appendLogLine(data) {
    const terminal = document.getElementById("consoleOutput");
    if (!terminal) return;

    const nearBottom = (terminal.scrollHeight - terminal.clientHeight - terminal.scrollTop) <= 20;

    const logEl = document.createElement("div");
    logEl.className = "log-entry";

    try {
        const logObj = JSON.parse(data);
        const level = logObj.level || "INFO";
        const msg = logObj.message || data;

        logEl.classList.add(`log-${level}`);
        logEl.textContent = `[${level}] ${msg}`;
    } catch (e) {
        // Fallback for non-JSON messages
        logEl.classList.add("log-INFO");
        logEl.textContent = data;
    }

    terminal.appendChild(logEl);

    if (nearBottom) {
        terminal.scrollTop = terminal.scrollHeight;
    }
}

/**
 * Updates the progress bar UI element.
 * Sets the text content and width of the progress bar.
 * 
 * @param {number|string} progress - Progress percentage (0-100)
 */
function updateProgressBar(progress) {
    const bar = document.getElementById("progressBar");
    if (bar) {
        bar.textContent = `${progress}%`;
        bar.style.width = `${progress}%`;
    }
}

// ----------------- START/STOP & SCREENSHOTTER -----------------

/**
 * Sets up the Start/Stop button and the Screenshotter button.
 * - Start/Stop: Toggles script execution via /api/runConfig or /api/stop.
 * - Screenshotter: Runs the specific "Screenshotter.java" script.
 */
function setupStartStopButton() {
    const startBtn = document.getElementById("startButton");
    const screenshotterBtn = document.getElementById("screenshotterButton");

    if (startBtn) {
        startBtn.addEventListener("click", async () => {
            try {
                if (!isStarted) {
                    const runConfig = getRunConfig();
                    if (!runConfig) return;

                    await startScript(runConfig);
                } else {
                    await stopScript();
                }
                // UI update to be handled by WebSocket state logic mostly
                updateStartButtonUI();
            } catch (err) {
                console.error("Error toggling script:", err);
            }
        });
    }

    if (screenshotterBtn) {
        screenshotterBtn.addEventListener("click", async () => {
            if (isStarted) return; // Should be disabled, but safety check

            // Start Screenshotter
            try {
                await startScript({ script: "Screenshotter.java" });
            } catch (err) {
                console.error("Error starting Screenshotter:", err);
            }
        });
    }
}

/**
 * Retrieves the configuration for running the selected script.
 * Validates that a script is selected.
 * 
 * @returns {{script: string}|null} Run configuration object with script name, or null if invalid.
 */
function getRunConfig() {
    const mode = document.getElementById("windowModeDropdown")?.textContent;

    if (!selectedScriptName) {
        alert("Please select a script.");
        return null;
    }

    return { script: selectedScriptName };
}

/**
 * Sends a POST request to start the selected script on the backend.
 * Triggers a page reload upon success to refresh state.
 * 
 * @param {object} config - Run configuration object containing script name.
 */
async function startScript(config) {
    const res = await fetch("/api/runConfig", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(config)
    });
    if (!res.ok) throw new Error("Failed to start script");
    // Trigger a full page reload to ensure fresh state (clear logs, reset UI)
    window.location.reload();
}

/**
 * Sends a POST request to stop the currently running script on the backend.
 * Updates local state to stopped.
 */
async function stopScript() {
    const res = await fetch("/api/stop", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}"
    });
    if (!res.ok) throw new Error("Failed to stop script");
    isStarted = false;
}

/**
 * Updates the Start/Stop button UI to reflect the current script state.
 * Toggles class names and text content between "Start" and "Stop".
 * Preserves layout classes (btn-lg, w-100, py-3, mb-2).
 */
function updateStartButtonUI() {
    const btn = document.getElementById("startButton");
    if (!btn) return;

    if (isStarted) {
        // Switch to Stop state
        btn.classList.remove("btn-success");
        btn.classList.add("btn-danger");
        btn.innerHTML = '<i class="bi bi-stop-circle-fill me-2"></i>STOP';
    } else {
        // Switch to Start state
        btn.classList.remove("btn-danger");
        btn.classList.add("btn-success");
        btn.innerHTML = '<i class="bi bi-play-circle-fill me-2"></i>START';
    }
}

// ----------------- BACKEND STATE SYNC -----------------

/**
 * Establishes a WebSocket connection to track backend script running state.
 * Updates the Start/Stop button and Control Panel state if the backend state changes.
 * Handles automatic reconnection.
 */
function connectStateWebSocket() {
    const wsProtocol = location.protocol === "https:" ? "wss" : "ws";
    const wsUrl = `${wsProtocol}://${location.host}/ws/state`;
    let ws;

    function initialize() {
        ws = new WebSocket(wsUrl);

        ws.onopen = () => console.log("Connected to state WebSocket:", wsUrl);

        ws.onmessage = (event) => {
            const running = event.data === "true";
            const wasStarted = isStarted;
            isStarted = running;

            if (isStarted !== wasStarted) {
                updateStartButtonUI();
            }
            // Always ensure controls match state
            updateControlsState(isStarted);
        };

        ws.onclose = (event) => {
            console.warn("State WebSocket closed:", event.reason);
            setTimeout(initialize, 2000);
        };

        ws.onerror = (error) => {
            console.error("State WebSocket error:", error);
            ws.close();
        };
    }

    initialize();
}


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

// ----------------- SCRIPT FETCH + UI -----------------

/**
 * Fetches the list of available scripts from the backend and renders them in the UI.
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
 * @param {string[]} scripts - List of script names to display.
 */
function renderScriptList(scripts) {
    const listGroup = document.getElementById("script-list");
    if (!listGroup) return;

    scripts.forEach(script => {
        if (script === "package-info.java") return;

        const listItem = document.createElement("li");
        listItem.className = "list-group-item d-flex justify-content-between align-items-start list-group-item-action bg-med";
        listItem.style.cursor = "pointer";

        const title = document.createElement("div");
        title.className = "fw-bold p-2 text-white";
        title.textContent = script;

        listItem.appendChild(title);
        listGroup.appendChild(listItem);

        listItem.addEventListener("click", () => {
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
 * @param {string} line - Log line to append
 */
function appendLogLine(line) {
    const terminal = document.getElementById("consoleOutput");
    if (!terminal) return;

    const nearBottom = (terminal.scrollHeight - terminal.clientHeight - terminal.scrollTop) <= 5;

    const logEl = document.createElement("a");
    logEl.textContent = line;
    terminal.appendChild(logEl);
    terminal.appendChild(document.createElement("br"));

    if (nearBottom) {
        terminal.scrollTop = terminal.scrollHeight - terminal.clientHeight;
    }
}

/**
 * Updates the progress bar UI element.
 * @param {number|string} progress - Progress percentage (0-100)
 */
function updateProgressBar(progress) {
    const bar = document.getElementById("progressBar");
    if (bar) {
        bar.textContent = `${progress}%`;
        bar.style.width = `${progress}%`;
    }
}

// ----------------- START/STOP -----------------

/**
 * Sets up the Start/Stop button and its click handler.
 * Toggles script execution and updates the button UI.
 */
function setupStartStopButton() {
    const startBtn = document.getElementById("startButton");
    if (!startBtn) return;

    startBtn.addEventListener("click", async () => {
        const runConfig = getRunConfig();
        if (!runConfig) return;

        try {
            if (!isStarted) {
                await startScript(runConfig);
            } else {
                await stopScript();
            }
            updateStartButtonUI();
        } catch (err) {
            console.error("Error toggling script:", err);
        }
    });
}

/**
 * Retrieves the configuration for running the selected script.
 * @returns {{script: string}|null} Run configuration or null if invalid
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
 * Sends a request to start the selected script on the backend.
 * @param {object} config - Run configuration
 */
async function startScript(config) {
    const res = await fetch("/api/runConfig", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(config)
    });
    if (!res.ok) throw new Error("Failed to start script");
    isStarted = true;
}

/**
 * Sends a request to stop the running script on the backend.
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
 */
function updateStartButtonUI() {
    const btn = document.getElementById("startButton");
    if (!btn) return;

    if (isStarted) {
        btn.className = "btn btn-danger m-2";
        btn.textContent = "Stop";
    } else {
        btn.className = "btn btn-success m-2";
        btn.textContent = "Start";
    }
}

// ----------------- BACKEND STATE SYNC -----------------

/**
 * Establishes a WebSocket connection to track backend script running state.
 * Updates the Start/Stop button if the state changes.
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

            if (isStarted !== wasStarted) updateStartButtonUI();
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

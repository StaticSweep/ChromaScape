/**
 * Configuration for slider pairs used to control HSV (Hue, Saturation, Value) ranges.
 * Each object defines the IDs for the minimum and maximum sliders and their upper limit.
 *
 * @constant
 * @type {Array<{min: string, max: string, limit: number}>}
 */
const pairs = [
    { min: "hueMin", max: "hueMax", limit: 179 },
    { min: "satMin", max: "satMax", limit: 255 },
    { min: "valMin", max: "valMax", limit: 255 }
];

/**
 * Initializes the application once the DOM is fully loaded.
 * Sets up slider event listeners, the submit button, and fetches the initial images.
 */
document.addEventListener("DOMContentLoaded", () => {
    initSliders();
    initSubmitButton();
    fetchSliderState(); // Restore state from server
    initNameInput();
    updateImages().catch(console.error);
});

/**
 * Fetches the current slider state from the server and updates the UI.
 *
 * <p>Retrieves the HSV values from `/api/slider` and updates the corresponding input elements
 * and display text. This ensures the frontend matches the backend state on page load.
 *
 * @async
 * @returns {Promise<void>}
 */
async function fetchSliderState() {
    try {
        const response = await fetch(`/api/slider?t=${Date.now()}`);
        if (!response.ok) throw new Error(`Failed to fetch slider state: ${response.status}`);

        const state = await response.json();

        // Iterate over returned state and update UI
        for (const [id, value] of Object.entries(state)) {
            const el = document.getElementById(id);
            if (el) {
                el.value = value;
                updateDisplay(id, value);
            }
        }

        updateCodeSnippet(); // Refresh code snippet with new values
    } catch (err) {
        console.error("Error loading slider state:", err);
    }
}

/**
 * Initializes all slider inputs defined in the {@link pairs} array.
 *
 * <p>This function attaches event listeners that delegate to the {@link sendSliderVal} concurrency
 * loop, attaches immediate input listeners to enforce UI constraints (min < max), and initializes
 * the numerical display values next to the sliders.
 */
function initSliders() {
    pairs.forEach(pair => {
        const minEl = document.getElementById(pair.min);
        const maxEl = document.getElementById(pair.max);

        if (minEl && maxEl) {
            const sendMin = sendSliderVal;
            const sendMax = sendSliderVal;

            // Attach input listeners for live constraint and display updates
            minEl.addEventListener("input", () => {
                handleInput(pair, "min");
                sendMin(pair.min, minEl.value);
            });
            maxEl.addEventListener("input", () => {
                handleInput(pair, "max");
                sendMax(pair.max, maxEl.value);
            });

            // Initialize display values
            updateDisplay(pair.min, minEl.value);
            updateDisplay(pair.max, maxEl.value);
        }
    });
}

/**
 * Handles input events for a slider pair to enforce logical constraints.
 *
 * <p>Ensures that the minimum slider value never exceeds the maximum slider value (and vice versa)
 * by adjusting the conflicting slider to satisfy `min < max`.
 *
 * @param {Object} pair - The configuration object for the slider pair (from {@link pairs}).
 * @param {string} type - The type of slider being adjusted ("min" or "max").
 */
function handleInput(pair, type) {
    const minEl = document.getElementById(pair.min);
    const maxEl = document.getElementById(pair.max);

    let minVal = parseInt(minEl.value);
    let maxVal = parseInt(maxEl.value);

    // Constraint Logic: Ensure Min < Max
    if (minVal >= maxVal) {
        if (type === "min") {
            minEl.value = maxVal - 1;
            minVal = maxVal - 1;
        } else {
            maxEl.value = minVal + 1;
            maxVal = minVal + 1;
        }
    }

    updateDisplay(pair.min, minEl.value);
    updateDisplay(pair.max, maxEl.value);
    updateCodeSnippet();
}

/**
 * Updates the text display element associated with a specific slider.
 * Expects a DOM element with the ID `val-{id}` to exist.
 *
 * @param {string} id - The ID of the slider (e.g., "hueMin").
 * @param {string|number} value - The current value to display.
 */
function updateDisplay(id, value) {
    const display = document.getElementById(`val-${id}`);
    if (display) {
        display.innerText = value;
    }
}

/**
 * Initializes the submit button listener.
 *
 * <p>Validates the color name input (non-empty, no spaces) before sending a POST request
 * to `/api/submitColour` to save the configuration.
 */
function initSubmitButton() {
    const button = document.getElementById("submitButton");
    if (!button) return;

    button.addEventListener("click", async () => {
        const input = document.getElementById("colourNameInput");
        const colorName = input?.value.trim();

        if (!colorName) {
            alert("Please enter a name.");
            return;
        }

        if (/\s/.test(colorName)) {
            alert("Name cannot contain spaces.");
            return;
        }

        try {
            const res = await fetch("/api/submitColour", {
                method: "POST",
                headers: { "Content-type": "text/plain" },
                body: colorName
            });

            if (!res.ok) throw new Error(`Server error: ${res.status}`);
            alert("Configuration saved successfully.");
        } catch (err) {
            console.error("Submit error:", err);
            alert("Failed to save configuration.");
        }
    });
}

/**
 * A registry of active transmission states for each slider.
 *
 * <p>Each entry stores the most recent value from the input event (pending transmission)
 * and a boolean flag indicating if the specific slider loop is currently active.
 *
 * @type {Map<string, {latestValue: (string|number|null), isSending: boolean}>}
 */
const pendingUpdates = new Map();

/**
 * Sends a slider value update to the server using a conflating queue loop.
 * To ensure that the slider state doesn't overload the backend.
 *
 * @param {string} sliderName - The ID/name of the slider being updated.
 * @param {string|number} value - The new value of the slider.
 * @returns {Promise<void>}
 */
async function sendSliderVal(sliderName, value) {
    // Update the "latest pending value" for this slider
    if (!pendingUpdates.has(sliderName)) {
        pendingUpdates.set(sliderName, {
            latestValue: value,
            isSending: false
        });
    } else {
        pendingUpdates.get(sliderName).latestValue = value;
    }

    const state = pendingUpdates.get(sliderName);

    // If already sending, do nothing; the loop will pick up the new value automatically
    if (state.isSending) return;

    // Start the sending loop
    state.isSending = true;

    try {
        while (state.latestValue !== null) {
            // Capture the value we are about to send and clear the "next" slot
            const valueToSend = state.latestValue;
            state.latestValue = null; // Mark as "nothing more to send" for now

            try {
                const response = await fetch("/api/slider", {
                    method: "POST",
                    headers: { "Content-type": "application/json" },
                    body: JSON.stringify({ sliderName, sliderValue: valueToSend })
                });

                if (!response.ok) throw new Error(`Slider update failed: ${response.status}`);
                await updateImages();
            } catch (err) {
                console.error("Error updating slider/image:", err);
            }
        }
    } finally {
        // Loop finished (nextValue was null), so we are free again
        state.isSending = false;
    }
}

/**
 * Refreshes the original and modified images by appending a timestamp query parameter.
 * This bypasses browser caching to show the latest image processing results.
 *
 * @async
 * @returns {Promise<void>}
 */
async function updateImages() {
    const timestamp = Date.now();
    const original = document.getElementById("originalImage");
    const modified = document.getElementById("modifiedImage");

    if (original) original.src = `/api/originalImage?t=${timestamp}`;
    if (modified) modified.src = `/api/modifiedImage?t=${timestamp}`;
}

/**
 * Updates the code snippet display with the current slider values and colour name.
 */
function updateCodeSnippet() {
    const name = document.getElementById("colourNameInput").value.trim() || "MyColour";

    // Get values
    const hMin = document.getElementById("hueMin").value;
    const hMax = document.getElementById("hueMax").value;
    const sMin = document.getElementById("satMin").value;
    const sMax = document.getElementById("satMax").value;
    const vMin = document.getElementById("valMin").value;
    const vMax = document.getElementById("valMax").value;

    const snippet = `ColourObj ${toCamelCase(name)} = new ColourObj("${name}", new Scalar(${hMin}, ${sMin}, ${vMin}, 0), new Scalar(${hMax}, ${sMax}, ${vMax}, 0));`;

    const codeEl = document.getElementById("codeSnippet");
    if (codeEl) codeEl.innerText = snippet;
}

/**
 * Helper to convert valid variable names
 */
function toCamelCase(str) {
    return str.replace(/(?:^\w|[A-Z]|\b\w)/g, (word, index) => {
        return index === 0 ? word.toLowerCase() : word.toUpperCase();
    }).replace(/\s+/g, '');
}
/**
 * Initializes the colour name input listener to update the code snippet in real-time.
 */
function initNameInput() {
    const nameInput = document.getElementById("colourNameInput");
    if (nameInput) {
        // 'input' fires on every keystroke, 'change' only when focus is lost
        nameInput.addEventListener("input", () => {
            updateCodeSnippet();
        });
    }
}

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
    updateImages().catch(console.error);
});

/**
 * Initializes all slider inputs defined in the {@link pairs} array.
 *
 * <p>This function:
 * <ul>
 * <li>Attaches debounced event listeners to send updates to the server.</li>
 * <li>Attaches immediate input listeners to enforce UI constraints (min < max).</li>
 * <li>Initializes the numerical display values next to the sliders.</li>
 * </ul>
 */
function initSliders() {
    pairs.forEach(pair => {
        const minEl = document.getElementById(pair.min);
        const maxEl = document.getElementById(pair.max);

        if (minEl && maxEl) {
            const sendMin = throttle(sendSliderVal, 150); // Updates ~6 times a second while dragging
            const sendMax = throttle(sendSliderVal, 150);

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
 * Creates a throttled version of a function that ensures it is called at most once
 * within the specified time limit.
 *
 * <p>Unlike debounce, which waits for a pause in execution, throttle guarantees
 * that the function fires regularly (every `limit` ms) during continuous events
 * like scrolling or slider dragging.
 *
 * @param {Function} func - The function to throttle.
 * @param {number} limit - The time interval in milliseconds (e.g., 100ms).
 * @returns {Function} A new throttled function that accepts the same arguments.
 */
function throttle(func, limit) {
    let inThrottle;
    return function (...args) {
        if (!inThrottle) {
            func.apply(this, args);
            inThrottle = true;
            setTimeout(() => inThrottle = false, limit);
        }
    }
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
 * Sends a slider value update to the server.
 *
 * <p>After successfully updating the backend via `/api/slider`, triggers an image refresh.
 *
 * @async
 * @param {string} sliderName - The ID/name of the slider being updated.
 * @param {string|number} value - The new value of the slider.
 * @returns {Promise<void>}
 */
async function sendSliderVal(sliderName, value) {
    try {
        const response = await fetch("/api/slider", {
            method: "POST",
            headers: { "Content-type": "application/json" },
            body: JSON.stringify({ sliderName, sliderValue: value })
        });

        if (!response.ok) throw new Error(`Slider update failed: ${response.status}`);
        await updateImages();
    } catch (err) {
        console.error("Error updating slider/image:", err);
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
/**
 * Statistics WebSocket Client
 * Connects to /ws/stats and updates the dashboard metrics.
 */
document.addEventListener('DOMContentLoaded', () => {
    // Elements
    const timeEl = document.getElementById('stat-time');
    const cyclesEl = document.getElementById('stat-cycles');
    const inputsEl = document.getElementById('stat-inputs');
    const objectsEl = document.getElementById('stat-objects');

    if (!timeEl || !cyclesEl || !inputsEl || !objectsEl) return;

    const socket = new WebSocket('ws://' + window.location.host + '/ws/stats');

    socket.onopen = () => {
        console.log('Connected to Statistics WebSocket');
    };

    socket.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            timeEl.innerText = data.time;
            cyclesEl.innerText = data.cycles;
            inputsEl.innerText = data.inputs;
            objectsEl.innerText = data.objects;
        } catch (e) {
            console.error('Failed to parse stats', e);
        }
    };
});

/**
 * Semantic State WebSocket Client
 * Connects to the semantic state websocket endpoint and updates the UI indicator.
 */
document.addEventListener('DOMContentLoaded', () => {
    const statusPill = document.getElementById('bot-status-pill');
    if (!statusPill) return;

    const socket = new WebSocket('ws://' + window.location.host + '/ws/semantic-state');

    socket.onopen = () => {
        console.log('Connected to Semantic State WebSocket');
    };

    socket.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            updateStatus(data.label, data.css);
        } catch (e) {
            console.error('Failed to process state message', e);
        }
    };

    function updateStatus(label, cssClass) {
        statusPill.innerText = label;
        statusPill.className = `badge rounded-pill bg-${cssClass} ms-2`;
        statusPill.style.color = 'white'; // Force white text
        statusPill.style.textShadow = '0 1px 2px rgba(0,0,0,0.3)'; // Add shadow for legibility
    }
});

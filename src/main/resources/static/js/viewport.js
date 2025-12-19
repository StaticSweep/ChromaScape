/**
 * Viewport WebSocket Client
 * Connects to the viewport websocket endpoint and renders received images.
 */
document.addEventListener('DOMContentLoaded', () => {
    const viewportContainer = document.getElementById('viewport-container');
    if (!viewportContainer) {
        console.error('Viewport container not found');
        return;
    }

    // Clear initial content
    viewportContainer.innerHTML = '';

    // Create the single main image element
    const wrapper = document.createElement('div');
    wrapper.className = 'text-center p-2';

    const img = document.createElement('img');
    img.id = 'viewport-image';
    img.className = 'img-fluid border border-secondary rounded shadow-sm';
    img.style.maxHeight = '400px';
    img.style.display = 'none'; // Hide until we have data

    wrapper.appendChild(img);
    viewportContainer.appendChild(wrapper);

    const socket = new WebSocket('ws://' + window.location.host + '/ws/viewport');

    socket.onmessage = (event) => {
        try {
            // Update the single image view
            img.src = event.data;
            img.style.display = 'inline-block';
        } catch (e) {
            console.error('Failed to process viewport message', e);
        }
    };

});

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

    // Create the loading container
    const loaderContainer = document.createElement('div');
    loaderContainer.id = 'viewport-loader';
    loaderContainer.className = 'loader-container';

    const loader = document.createElement('div');
    loader.className = 'glass-loader';

    const loaderText = document.createElement('div');
    loaderText.className = 'loader-text';
    loaderText.innerText = 'Waiting for Visual Input...';

    loaderContainer.appendChild(loader);
    loaderContainer.appendChild(loaderText);

    // Create the main image element (hidden initially)
    const img = document.createElement('img');
    img.id = 'viewport-image';
    img.className = 'img-fluid border border-secondary rounded shadow-sm';
    img.style.maxHeight = '400px';
    img.style.display = 'none';

    wrapper.appendChild(loaderContainer);
    wrapper.appendChild(img);
    viewportContainer.appendChild(wrapper);

    const socket = new WebSocket('ws://' + window.location.host + '/ws/viewport');

    socket.onmessage = (event) => {
        try {
            // Hide loader if visible
            if (loaderContainer.style.display !== 'none') {
                loaderContainer.style.display = 'none';
                img.style.display = 'inline-block';
            }

            // Update the single image view
            img.src = event.data;
        } catch (e) {
            console.error('Failed to process viewport message', e);
        }
    };

});

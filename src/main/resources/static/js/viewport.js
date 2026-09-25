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

    // Create the single main canvas element
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

    // Create the main canvas element (hidden initially)
    const canvas = document.createElement('canvas');
    canvas.id = 'viewport-canvas';
    canvas.className = 'img-fluid border border-secondary rounded shadow-sm';
    canvas.style.maxHeight = '400px';
    canvas.style.display = 'none';
    const ctx = canvas.getContext('2d');

    wrapper.appendChild(loaderContainer);
    wrapper.appendChild(canvas);
    viewportContainer.appendChild(wrapper);

    const wsProtocol = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${wsProtocol}://${window.location.host}/ws/viewport`);
    socket.binaryType = 'arraybuffer';
    let latestSeq = 0;
    let lastRenderedSeq = 0;
    let pendingFrame = null;
    let decodeInFlight = false;

    socket.onopen = () => {
        console.log('Viewport WebSocket connected');
    };

    socket.onerror = (error) => {
        console.error('Viewport WebSocket error:', error);
    };

    socket.onclose = (event) => {
        console.log('Viewport WebSocket closed:', event.code, event.reason);
    };

    socket.onmessage = async (event) => {
        try {
            // Hide loader if visible
            if (loaderContainer.style.display !== 'none') {
                loaderContainer.style.display = 'none';
                canvas.style.display = 'inline-block';
            }

            const frame = await parseViewportFrame(event.data);
            if (frame.seq <= latestSeq) {
                return;
            }
            latestSeq = frame.seq;
            pendingFrame = frame;

            if (!decodeInFlight) {
                processLatestFrame();
            }
        } catch (e) {
            console.error('Failed to process viewport message', e);
        }
    };

    async function processLatestFrame() {
        if (!pendingFrame || !ctx) {
            return;
        }

        const frame = pendingFrame;
        pendingFrame = null;
        decodeInFlight = true;

        try {
            const bitmap = await decodeViewportFrame(frame);
            if (frame.seq < latestSeq || frame.seq <= lastRenderedSeq) {
                if (bitmap?.close) bitmap.close();
                return;
            }

            if (canvas.width !== bitmap.width || canvas.height !== bitmap.height) {
                canvas.width = bitmap.width;
                canvas.height = bitmap.height;
            }

            ctx.clearRect(0, 0, canvas.width, canvas.height);
            ctx.drawImage(bitmap, 0, 0);
            lastRenderedSeq = frame.seq;

            if (bitmap?.close) bitmap.close();
        } catch (error) {
            console.error('Viewport frame decode failed', error);
        } finally {
            decodeInFlight = false;
            if (pendingFrame) {
                setTimeout(processLatestFrame, 0);
            }
        }
    }

    async function parseViewportFrame(rawData) {
        if (rawData instanceof Blob) {
            return parseViewportFrame(await rawData.arrayBuffer());
        }

        if (rawData instanceof ArrayBuffer) {
            if (rawData.byteLength < 16) {
                throw new Error(`Viewport frame too small: ${rawData.byteLength}`);
            }
            const view = new DataView(rawData);
            const seq = Number(view.getBigUint64(0, false));
            const jpegBytes = rawData.slice(16);
            return {
                seq,
                blob: new Blob([jpegBytes], { type: 'image/jpeg' })
            };
        }

        const payload = JSON.parse(rawData);
        return {
            seq: payload.seq ?? Date.now(),
            dataUrl: payload.data
        };
    }

    async function decodeViewportFrame(frame) {
        if (frame.blob) {
            if (window.createImageBitmap) {
                return createImageBitmap(frame.blob);
            }

            return new Promise((resolve, reject) => {
                const img = new Image();
                const objectUrl = URL.createObjectURL(frame.blob);
                img.onload = () => {
                    URL.revokeObjectURL(objectUrl);
                    resolve(img);
                };
                img.onerror = (error) => {
                    URL.revokeObjectURL(objectUrl);
                    reject(error);
                };
                img.src = objectUrl;
            });
        }

        return new Promise((resolve, reject) => {
            const img = new Image();
            img.onload = () => resolve(img);
            img.onerror = reject;
            img.src = frame.dataUrl;
        });
    }

});

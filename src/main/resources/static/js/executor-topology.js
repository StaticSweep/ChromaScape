'use strict';
(() => {
    const status = document.getElementById('topology-status');
    const rows = document.getElementById('topology-clients');
    const button = document.getElementById('refresh-topology');
    let request = null;
    async function refresh() {
        if (request) request.abort();
        const controller = new AbortController();
        request = controller;
        const deadline = setTimeout(() => controller.abort(), 5000);
        button.disabled = true;
        status.textContent = 'Loading executor observations…';
        try {
            const response = await fetch('/api/debug/builder-goals/clients', {signal: controller.signal, credentials: 'same-origin'});
            if (!response.ok) throw new Error('Executor observations are unavailable');
            const body = await response.json();
            const clients = Array.isArray(body) ? body : body.clients;
            if (!Array.isArray(clients)) throw new Error('Executor observations have an unexpected shape');
            const fragment = document.createDocumentFragment();
            for (const client of clients) {
                const row = document.createElement('tr');
                const trust = client.certificateExpiryClass === 'EXPIRED' ? 'TLS EXPIRED' : client.certificateExpiryClass === 'NOT_YET_VALID' ? 'TLS NOT YET VALID' : client.online === false ? 'OFFLINE' : client.stale ? 'STALE' : client.endpointTrusted ? 'TRUSTED' : 'BLOCKED';
                const values = [client.clientId, client.environment, client.endpointRef, client.fencingGeneration, trust, client.machineUuid, client.launcherSessionId, client.freshness, client.certificateExpiryClass, client.lastValidationAt, client.remediation || client.endpointTrustReason || 'No remediation reported'];
                for (const [index, value] of values.entries()) {
                    const cell = document.createElement('td');
                    if (index === 8 && client.certificateObservedAt) {
                        cell.title = `Verified TLS observation: ${client.certificateObservedAt}; valid from ${client.certificateNotBefore}; expires ${client.certificateNotAfter}`;
                    }
                    cell.textContent = value == null ? 'Not available' : String(value);
                    row.appendChild(cell);
                }
                fragment.appendChild(row);
            }
            rows.replaceChildren(fragment);
            status.textContent = clients.length ? `${clients.length} executor observations. Refresh to revalidate expired or changed identities.` : 'No executor observations. Check the authenticated heartbeat and allocation issuer.';
        } catch (error) {
            rows.replaceChildren();
            status.textContent = 'Executor observations are unavailable. Check registry connectivity and retry.';
        } finally {
            clearTimeout(deadline);
            if (request === controller) { request = null; button.disabled = false; }
        }
    }
    button.addEventListener('click', refresh);
    refresh();
})();

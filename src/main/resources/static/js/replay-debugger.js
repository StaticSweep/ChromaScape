/**
 * xRSPS Replay Debugger - Frontend module for causal simulation mode.
 *
 * Security contract: every dynamic value from the API is inserted via
 * textContent / DOM APIs only. innerHTML is never fed API data.
 */

(() => {
    'use strict';

    const MODES = {
        OBSERVED_PLAYBACK: 'OBSERVED_PLAYBACK',
        POLICY_REPLAY: 'POLICY_REPLAY',
        CAUSAL_SIMULATION: 'CAUSAL_SIMULATION',
    };
    const TERMINAL_STATES = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'];
    const MAX_STORED_EVENTS = 500;
    const POLL_BASE_MS = 1000;
    const POLL_MAX_MS = 15000;

    const state = {
        mode: MODES.CAUSAL_SIMULATION,
        selectedRecordingId: null,
        selectedCurationId: null,
        selectedManifestId: null,
        selectedRunId: null,
        capabilities: null,
        preflight: null,
        run: null,
        events: [],
        lastEventOrdinal: 0,
        pollHandle: null,
        pollBackoffMs: POLL_BASE_MS,
    };

    // ── API helpers ──

    async function apiGet(path) {
        const resp = await fetch('/api/replay' + path);
        if (!resp.ok) {
            const body = await resp.json().catch(() => ({}));
            throw new Error(body.message || `API ${resp.status}`);
        }
        return resp.json();
    }

    async function apiPost(path, body) {
        const resp = await fetch('/api/replay' + path, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.message || `API ${resp.status}`);
        }
        return resp.json();
    }

    /** Durable per-session idempotency keys survive retries of this call site. */
    function idempotencyKey(scope) {
        const storageKey = 'replay.idem.' + scope;
        let key = null;
        try { key = sessionStorage.getItem(storageKey); } catch (_) { /* storage unavailable */ }
        if (!key) {
            key = scope + '-' +
                (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`.replace(/[^a-z0-9-]/gi, '0'));
            try { sessionStorage.setItem(storageKey, key); } catch (_) { /* ignore */ }
        }
        return key;
    }

    function el(tag, className, textContentValue) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (textContentValue !== undefined && textContentValue !== null) {
            node.textContent = String(textContentValue);
        }
        return node;
    }

    // ── Capability ──

    async function loadCapabilities() {
        state.capabilities = await apiGet('/capabilities');
        renderCapabilities();
    }

    function renderCapabilities() {
        const container = document.getElementById('replay-capability-badge');
        if (!container) return;
        container.replaceChildren();
        const c = state.capabilities;
        let cls = 'badge bg-danger';
        let label = 'Unavailable';
        if (!c) {
            cls = 'badge bg-secondary';
            label = 'Unknown';
        } else if (c.openRuneAvailable) {
            cls = 'badge bg-warning text-dark';
            label = 'OpenRune Detected';
        } else if (c.modes && c.modes.causalSimulation) {
            cls = 'badge bg-info text-dark';
            label = 'xRSPS Ready';
        }
        container.appendChild(el('span', cls, label));
    }

    // ── Catalog ──

    async function loadCatalog() {
        const catalog = await apiGet('/recordings');
        renderCatalog(catalog);
    }

    function renderCatalog(catalog) {
        const tbody = document.getElementById('replay-recording-list');
        if (!tbody || !catalog || !catalog.entries) return;
        tbody.replaceChildren();

        if (!catalog.entries.length) {
            const row = el('tr');
            const cell = el('td', 'text-center', 'No recordings found');
            cell.colSpan = 5;
            row.appendChild(cell);
            tbody.appendChild(row);
            return;
        }

        for (const entry of catalog.entries) {
            const e = entry.eligibility;
            let badgeCls = 'badge bg-secondary';
            let badgeLabel = 'N/A';
            if (e) {
                if (e.simulationEligible) { badgeCls = 'badge bg-success'; badgeLabel = 'Sim'; }
                else if (e.policyReplayEligible) { badgeCls = 'badge bg-info text-dark'; badgeLabel = 'Policy'; }
                else { badgeCls = 'badge bg-secondary'; badgeLabel = 'Playback'; }
            } else {
                badgeCls = 'badge bg-danger';
            }

            const row = el('tr', 'recording-row');
            row.dataset.id = entry.recordingId;

            const badgeCell = el('td');
            badgeCell.appendChild(el('span', badgeCls, badgeLabel));

            const actionCell = el('td');
            const selectBtn = el('button', 'btn btn-outline-primary btn-sm', 'Select');
            selectBtn.type = 'button';
            selectBtn.addEventListener('click', () => selectRecording(entry.recordingId));
            actionCell.appendChild(selectBtn);

            row.appendChild(actionCell);
            row.appendChild(el('td', '', entry.recordingId));
            row.appendChild(el('td', '', e ? entry.accountName : entry.accountName || ''));
            row.appendChild(el('td', '', entry.scriptName || ''));
            row.appendChild(el('td', '', fmtTime(entry.startTime)));
            tbody.appendChild(row);
        }
    }

    // ── Preflight ──

    async function selectRecording(recordingId) {
        state.selectedRecordingId = recordingId;
        state.preflight = await apiGet(`/recordings/${encodeURIComponent(recordingId)}/preflight`);
        renderPreflight();
    }

    function renderPreflight() {
        const container = document.getElementById('replay-preflight-panel');
        if (!container || !state.preflight) return;
        container.replaceChildren();
        const pf = state.preflight;
        const e = pf.eligibility;

        const panel = el('div', 'glass-panel');
        panel.appendChild(el('h5', '', 'Preflight'));
        panel.appendChild(el('div', 'small mb-2', pf.recordingId));

        const badges = el('div', 'mb-2');
        badges.appendChild(el('span', e.playbackEligible ? 'badge bg-success me-1' : 'badge bg-secondary me-1', 'Playback'));
        badges.appendChild(el('span', e.policyReplayEligible ? 'badge bg-success me-1' : 'badge bg-secondary me-1', 'Policy Replay'));
        badges.appendChild(el('span', e.simulationEligible ? 'badge bg-success' : 'badge bg-danger', 'Simulation'));
        panel.appendChild(badges);

        for (const reason of e.blockingReasons || []) {
            const alertEl = el('div', 'alert alert-warning small mt-1');
            const strong = el('strong', '', reason.code);
            alertEl.appendChild(strong);
            alertEl.appendChild(document.createTextNode(': ' + reason.message));
            if (reason.remediation) {
                const remediation = el('div', 'text-muted', '→ ' + reason.remediation);
                alertEl.appendChild(remediation);
            }
            panel.appendChild(alertEl);
        }

        if (pf.curations && pf.curations.length) {
            const published = pf.curations.filter(c => c.state === 'PUBLISHED');
            if (published.length) {
                const label = el('label', 'form-label mt-2', 'Published Curation');
                label.htmlFor = 'curation-select';
                const select = el('select', 'form-select form-select-sm');
                select.id = 'curation-select';
                const placeholder = el('option', '', '-- Select --');
                placeholder.value = '';
                select.appendChild(placeholder);
                for (const c of published) {
                    const opt = el('option', '', `v${c.version} — ${c.role} (${c.state})`);
                    opt.value = c.id;
                    select.appendChild(opt);
                }
                select.addEventListener('change', () => selectCuration(select.value));
                panel.appendChild(label);
                panel.appendChild(select);
            } else {
                panel.appendChild(el('p', 'text-muted small mt-2', 'No published curations available'));
            }
        }
        container.appendChild(panel);
    }

    // ── Compile & Run ──

    async function selectCuration(curationId) {
        state.selectedCurationId = curationId;
        if (!curationId) return;
        const manifest = await apiPost('/scenarios', {
            recordingId: state.selectedRecordingId,
            curationId: curationId,
            seed: '',
        });
        state.selectedManifestId = manifest.manifestId;
        renderCompileResult(manifest);
    }

    function renderCompileResult(manifest) {
        const container = document.getElementById('replay-compile-panel');
        if (!container) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel');

        panel.appendChild(el('h5', '', 'Manifest Compiled'));

        const idRow = el('div');
        idRow.appendChild(el('strong', '', 'ID: '));
        idRow.appendChild(document.createTextNode(manifest.manifestId));
        panel.appendChild(idRow);

        const checksumRow = el('div');
        checksumRow.appendChild(el('strong', '', 'Checksum: '));
        checksumRow.appendChild(el('code', '', manifest.manifestChecksum));
        panel.appendChild(checksumRow);

        panel.appendChild(el('div', '', `Completeness: ${manifest.completeness}`));

        const startBtn = el('button', 'btn btn-primary btn-sm mt-2', 'Start Causal Simulation');
        startBtn.type = 'button';
        startBtn.addEventListener('click', () => startRun());
        panel.appendChild(startBtn);

        container.appendChild(panel);
    }

    async function startRun() {
        if (!state.selectedManifestId) return;
        // Fresh key per logical run; reused on retry of this exact request.
        const key = 'ui-run-' +
            (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString(36));
        const runResponse = await apiPost('/runs', {
            mode: MODES.CAUSAL_SIMULATION,
            manifestId: state.selectedManifestId,
            idempotencyKey: key,
            stopPolicy: 'STOP_ON_FIRST_HARD',
        });
        state.selectedRunId = runResponse.runId;
        state.run = runResponse;
        state.events = [];
        state.lastEventOrdinal = 0;
        state.pollBackoffMs = POLL_BASE_MS;
        renderRunStatus();
        startPolling();
    }

    function renderRunStatus() {
        const container = document.getElementById('replay-run-panel');
        if (!container || !state.run) return;
        container.replaceChildren();
        const r = state.run;
        const panel = el('div', 'glass-panel');

        panel.appendChild(el('h5', '', 'Run'));
        panel.appendChild(el('div', 'small', r.runId));

        const badgeRow = el('div', 'mt-1');
        badgeRow.appendChild(el('span', `badge bg-${runStateColor(r.state)}`, r.state));
        badgeRow.appendChild(document.createTextNode(' '));
        badgeRow.appendChild(el('span', 'badge bg-secondary', r.phase));
        panel.appendChild(badgeRow);

        const progress = el('div', 'progress mt-2');
        progress.style.height = '5px';
        const bar = el('div', r.state === 'SUCCEEDED'
            ? 'progress-bar bg-success'
            : r.state === 'FAILED' || r.state === 'TIMED_OUT'
                ? 'progress-bar bg-danger'
                : 'progress-bar progress-bar-striped progress-bar-animated');
        bar.style.width = TERMINAL_STATES.includes(r.state) ? '100%' : '50%';
        progress.appendChild(bar);
        panel.appendChild(progress);

        const buttons = el('div', 'mt-2');
        const cancelBtn = el('button', 'btn btn-danger btn-sm', 'Cancel');
        cancelBtn.type = 'button';
        cancelBtn.addEventListener('click', () => cancelRun());
        buttons.appendChild(cancelBtn);
        if (r.state === 'SUCCEEDED') {
            const reportBtn = el('button', 'btn btn-info btn-sm ms-1', 'View Report');
            reportBtn.type = 'button';
            reportBtn.addEventListener('click', () => viewReport());
            buttons.appendChild(reportBtn);
        }
        panel.appendChild(buttons);

        container.appendChild(panel);
    }

    async function cancelRun() {
        if (!state.selectedRunId) return;
        await apiPost(`/runs/${encodeURIComponent(state.selectedRunId)}/cancel`, {});
        stopPolling();
        state.run = await apiGet(`/runs/${encodeURIComponent(state.selectedRunId)}`);
        renderRunStatus();
    }

    async function viewReport() {
        if (!state.selectedRunId) return;
        const report = await apiGet(`/runs/${encodeURIComponent(state.selectedRunId)}/report`);
        const container = document.getElementById('replay-report-panel');
        if (!container) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel');

        panel.appendChild(el('h5', '', 'Report'));

        const outcomeRow = el('div');
        outcomeRow.appendChild(el('strong', '', 'Outcome: '));
        outcomeRow.appendChild(document.createTextNode(report.outcome));
        panel.appendChild(outcomeRow);

        const countsRow = el('div', 'small');
        countsRow.textContent =
            `Actions: ${report.executedActionCount} · Checkpoints: ${report.checkpointCount} · ` +
            `Hard: ${report.mismatchHardCount} · Soft: ${report.mismatchSoftCount}`;
        panel.appendChild(countsRow);

        if (report.finalChainHash) {
            const hashRow = el('div', 'small mt-1');
            hashRow.appendChild(el('strong', '', 'Final chain hash: '));
            hashRow.appendChild(el('code', '', report.finalChainHash));
            panel.appendChild(hashRow);
        }

        if (report.reportJson) {
            const pre = el('pre', 'mt-2 small');
            pre.style.maxHeight = '240px';
            pre.style.overflow = 'auto';
            try {
                pre.textContent = JSON.stringify(JSON.parse(report.reportJson), null, 2);
            } catch (_) {
                pre.textContent = report.reportJson;
            }
            panel.appendChild(pre);
        }
        container.appendChild(panel);
    }

    // ── Event Polling (bounded buffer + reconnect backoff) ──

    function startPolling() {
        stopPolling();
        scheduleNextPoll(POLL_BASE_MS);
        pollEvents();
    }

    function scheduleNextPoll(delayMs) {
        state.pollHandle = setTimeout(pollEvents, delayMs);
    }

    function stopPolling() {
        if (state.pollHandle) {
            clearTimeout(state.pollHandle);
            state.pollHandle = null;
        }
    }

    async function pollEvents() {
        if (!state.selectedRunId) return;
        try {
            const page = await apiGet(
                `/runs/${encodeURIComponent(state.selectedRunId)}/events?after=${state.lastEventOrdinal}&limit=100`);
            if (page.events && page.events.length > 0) {
                for (const ev of page.events) {
                    state.events.push(ev);
                }
                if (state.events.length > MAX_STORED_EVENTS) {
                    state.events = state.events.slice(-MAX_STORED_EVENTS);
                }
                state.lastEventOrdinal = page.nextAfter;
                renderEvents();
            }
            state.run = await apiGet(`/runs/${encodeURIComponent(state.selectedRunId)}`);
            renderRunStatus();
            state.pollBackoffMs = POLL_BASE_MS;
            if (TERMINAL_STATES.includes(state.run.state)) {
                stopPolling();
                if (state.run.state === 'SUCCEEDED') {
                    viewReport();
                }
            } else {
                scheduleNextPoll(POLL_BASE_MS);
            }
        } catch (e) {
            console.warn('Poll error:', e);
            state.pollBackoffMs = Math.min(state.pollBackoffMs * 2, POLL_MAX_MS);
            scheduleNextPoll(state.pollBackoffMs);
        }
    }

    function renderEvents() {
        const container = document.getElementById('replay-events-panel');
        if (!container) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel');

        panel.appendChild(el('h5', '', `Events (${state.events.length})`));

        const scroller = el('div', 'tick-cards-panel');
        scroller.style.maxHeight = '300px';

        for (const ev of state.events.slice(-50)) {
            const card = el('div', 'tick-card');
            const header = el('div', 'tick-header');
            header.appendChild(el('span', 'tick-number', '#' + ev.ordinal));
            header.appendChild(el('span', 'badge bg-secondary', 'Tick ' + ev.tick));
            header.appendChild(el('span', '', ev.eventType));
            card.appendChild(header);
            scroller.appendChild(card);
        }
        panel.appendChild(scroller);
        container.appendChild(panel);
    }

    // ── Helpers ──

    function runStateColor(s) {
        const map = {
            QUEUED: 'secondary', CLAIMED: 'info', STARTING: 'info', RUNNING: 'primary',
            CANCELLING: 'warning', SUCCEEDED: 'success', FAILED: 'danger',
            CANCELLED: 'dark', TIMED_OUT: 'warning',
        };
        return map[s] || 'secondary';
    }

    function fmtTime(t) {
        if (!t) return '';
        return new Date(t).toLocaleString();
    }

    // ── Public API ──

    window.ReplayDebugger = {
        loadCapabilities,
        loadCatalog,
        selectRecording,
        selectCuration,
        startRun,
        cancelRun,
        viewReport,
    };

    document.addEventListener('DOMContentLoaded', () => {
        ReplayDebugger.loadCapabilities().catch(console.warn);
        ReplayDebugger.loadCatalog().catch(console.warn);
    });
})();

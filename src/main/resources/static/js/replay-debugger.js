/**
 * ChromaScape xRSPS replay debugger.
 *
 * The v2 API owns conversion, manifest compilation, run state, and trace verification. The
 * browser is only a projection of that durable state; it never creates a simulator or controls
 * a game client. API values are inserted through textContent/DOM APIs, never innerHTML.
 */
(() => {
    'use strict';

    const API_ROOT = '/api/replay';
    const V2_ROOT = `${API_ROOT}/v2`;
    const TERMINAL_STATES = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'];
    const MAX_STORED_EVENTS = 500;
    const POLL_BASE_MS = 1000;
    const POLL_MAX_MS = 15000;

    const state = {
        selectedRecordingId: null,
        preflight: null,
        run: null,
        report: null,
        mismatches: [],
        events: [],
        lastEventOrdinal: -1,
        pollHandle: null,
        pollBackoffMs: POLL_BASE_MS,
        idempotencyKeys: new Map(),
    };

    class ReplayApiError extends Error {
        constructor(message, code, remediation) {
            super(message);
            this.name = 'ReplayApiError';
            this.code = code;
            this.remediation = remediation;
        }
    }

    async function apiRequest(url, options = {}) {
        const headers = { Accept: 'application/json', ...(options.headers || {}) };
        if (options.body !== undefined) headers['Content-Type'] = 'application/json';
        const response = await fetch(url, {
            credentials: 'same-origin',
            ...options,
            headers,
        });
        const body = await response.json().catch(() => null);
        if (!response.ok) {
            const error = isRecord(body) ? body : {};
            throw new ReplayApiError(
                typeof error.message === 'string' ? error.message : `Replay API request failed (${response.status})`,
                typeof error.code === 'string' ? error.code : `HTTP_${response.status}`,
                typeof error.remediation === 'string' ? error.remediation : null);
        }
        return body;
    }

    function apiGet(path) {
        return apiRequest(`${API_ROOT}${path}`);
    }

    function v2Get(path) {
        return apiRequest(`${V2_ROOT}${path}`);
    }

    function v2Post(path, body) {
        return apiRequest(`${V2_ROOT}${path}`, {
            method: 'POST',
            ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        });
    }

    function el(tag, className, value) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (value !== undefined && value !== null) node.textContent = String(value);
        return node;
    }

    function isRecord(value) {
        return typeof value === 'object' && value !== null && !Array.isArray(value);
    }

    function valueOrDash(value) {
        return value === undefined || value === null || value === '' ? '—' : value;
    }

    function fmtTime(value) {
        if (!value) return '—';
        const date = new Date(value);
        return Number.isNaN(date.valueOf()) ? String(value) : date.toLocaleString();
    }

    function fmtPercent(value) {
        return typeof value === 'number' && Number.isFinite(value) ? `${value.toFixed(1)}%` : '—';
    }

    function appendField(parent, label, value, code = false) {
        const row = el('div', 'small mb-1');
        row.appendChild(el('strong', '', `${label}: `));
        row.appendChild(el(code ? 'code' : 'span', '', valueOrDash(value)));
        parent.appendChild(row);
    }

    function appendBadge(parent, enabled, label, positive = 'success') {
        parent.appendChild(el('span', `badge bg-${enabled ? positive : 'secondary'} me-1`, label));
    }

    function renderError(container, error) {
        if (!container) return;
        container.replaceChildren();
        const panel = el('div', 'alert alert-danger small');
        if (error && error.code) panel.appendChild(el('strong', '', `${error.code}: `));
        panel.appendChild(document.createTextNode(error?.message || 'Replay request failed'));
        if (error?.remediation) panel.appendChild(el('div', 'mt-1', `Remediation: ${error.remediation}`));
        container.appendChild(panel);
    }

    // ── Capability and catalog ──

    async function loadCapabilities() {
        const capabilities = await apiGet('/capabilities');
        renderCapabilities(capabilities);
        return capabilities;
    }

    function renderCapabilities(capabilities) {
        const container = document.getElementById('replay-capability-badge');
        if (!container) return;
        container.replaceChildren();
        const ready = capabilities?.modes?.causalSimulation === true &&
            capabilities?.controlAllowed === false && capabilities?.openRuneAvailable === false;
        const label = ready ? 'xRSPS Ready' : 'xRSPS Unavailable';
        container.appendChild(el('span', `badge bg-${ready ? 'info text-dark' : 'danger'} me-2`, label));
        if (capabilities) {
            container.appendChild(el('span', 'badge bg-secondary me-1', capabilities.fidelity || 'unknown'));
            container.appendChild(el('span', `badge bg-${capabilities.certifiable === true ? 'success' : 'warning text-dark'}`,
                capabilities.certifiable === true ? 'certifiable' : 'development / non-certifiable'));
            const details = el('div', 'small text-muted mt-1');
            appendField(details, 'Scenario versions', (capabilities.scenarioSchemaVersions || []).join(', '));
            appendField(details, 'Trace schema', capabilities.viewerTraceSchema);
            appendField(details, 'Actions', (capabilities.supportedActions || []).join(', '));
            const identity = capabilities.xrsps || {};
            appendField(details, 'Adapter', identity.adapterVersion, true);
            appendField(details, 'Build', identity.buildCommit, true);
            appendField(details, 'Cache', identity.cacheRevision, true);
            appendField(details, 'Content', identity.contentRevision, true);
            container.appendChild(details);
        }
    }

    async function loadCatalog() {
        const catalog = await apiGet('/recordings');
        renderCatalog(catalog);
        return catalog;
    }

    function renderCatalog(catalog) {
        const tbody = document.getElementById('replay-recording-list');
        if (!tbody || !catalog || !Array.isArray(catalog.entries)) return;
        tbody.replaceChildren();
        if (catalog.entries.length === 0) {
            const row = el('tr');
            const cell = el('td', 'text-center', 'No recordings found');
            cell.colSpan = 5;
            row.appendChild(cell);
            tbody.appendChild(row);
            return;
        }
        for (const entry of catalog.entries) {
            const row = el('tr', 'recording-row');
            const eligibility = entry.eligibility || {};
            const status = eligibility.simulationEligible ? 'Simulation ready' :
                eligibility.policyReplayEligible ? 'Policy only' :
                    eligibility.playbackEligible ? 'Playback only' : 'Unavailable';
            const selectCell = el('td');
            const button = el('button', 'btn btn-outline-primary btn-sm', 'Inspect');
            button.type = 'button';
            button.addEventListener('click', () => selectRecording(entry.recordingId));
            selectCell.appendChild(button);
            row.appendChild(selectCell);
            row.appendChild(el('td', '', entry.recordingId));
            row.appendChild(el('td', '', entry.accountName));
            row.appendChild(el('td', '', entry.scriptName));
            row.appendChild(el('td', '', `${status} · ${fmtTime(entry.startTime)}`));
            tbody.appendChild(row);
        }
    }

    // ── v2 preflight ──

    async function selectRecording(recordingId) {
        stopPolling();
        state.selectedRecordingId = recordingId;
        state.preflight = null;
        state.run = null;
        state.report = null;
        state.mismatches = [];
        state.events = [];
        state.lastEventOrdinal = -1;
        clearPanel('replay-compile-panel');
        clearPanel('replay-run-panel');
        clearPanel('replay-events-panel');
        clearPanel('replay-report-panel');
        clearPanel('replay-mismatch-panel');
        try {
            state.preflight = await v2Get(`/recordings/${encodeURIComponent(recordingId)}/preflight?conversionMode=AUTO`);
            renderPreflight();
            renderCompilePanel();
        } catch (error) {
            renderError(document.getElementById('replay-preflight-panel'), error);
        }
    }

    function renderPreflight() {
        const container = document.getElementById('replay-preflight-panel');
        const pf = state.preflight;
        if (!container || !pf) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel');
        panel.appendChild(el('h5', '', 'xRSPS Preflight'));
        appendField(panel, 'Recording', pf.recordingId, true);
        appendField(panel, 'Debug session', pf.debugSessionId, true);
        appendField(panel, 'State', pf.preflightState);

        const badges = el('div', 'mb-2');
        appendBadge(badges, pf.playbackEligible, 'Playback');
        appendBadge(badges, pf.policyReplayEligible, 'Policy Replay');
        appendBadge(badges, pf.simulationEligible, 'xRSPS Simulation');
        appendBadge(badges, pf.certificationEligible, 'Certification', 'success');
        panel.appendChild(badges);

        const conversion = el('div', 'small border-top pt-2 mt-2');
        appendField(conversion, 'Conversion quality', pf.conversionQuality);
        appendField(conversion, 'Action coverage', fmtPercent(pf.actionCoveragePct));
        appendField(conversion, 'Actions', `${valueOrDash(pf.actionCount)} (${valueOrDash(pf.typedActionCount)} typed, ${valueOrDash(pf.legacyLogCount)} legacy)`);
        appendField(conversion, 'Gaps / approximations', `${valueOrDash(pf.gapCount)} / ${valueOrDash(pf.approximationCount)}`);
        panel.appendChild(conversion);

        const identity = pf.capability?.xrsps || {};
        const runtime = el('div', 'small border-top pt-2 mt-2');
        runtime.appendChild(el('strong', '', 'Pinned runtime identity'));
        appendField(runtime, 'Adapter', identity.adapterVersion, true);
        appendField(runtime, 'Build', identity.buildCommit, true);
        appendField(runtime, 'Cache', identity.cacheRevision, true);
        appendField(runtime, 'Content', identity.contentRevision, true);
        panel.appendChild(runtime);

        const blockers = Array.isArray(pf.blockingReasons) ? pf.blockingReasons : [];
        for (const reason of blockers) {
            const alert = el('div', 'alert alert-warning small mt-1');
            alert.appendChild(el('strong', '', `${reason.code}: `));
            alert.appendChild(document.createTextNode(valueOrDash(reason.message)));
            if (reason.remediation) alert.appendChild(el('div', 'text-muted', `Remediation: ${reason.remediation}`));
            panel.appendChild(alert);
        }
        container.appendChild(panel);
    }

    function renderCompilePanel() {
        const container = document.getElementById('replay-compile-panel');
        if (!container || !state.preflight) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel mt-2');
        panel.appendChild(el('h5', '', 'Immutable conversion'));
        panel.appendChild(el('p', 'small text-muted',
            'The v2 service compiles the finalized recording into a deterministic ScenarioManifest and queues one durable xRSPS run.'));
        const button = el('button', 'btn btn-primary btn-sm', 'Start xRSPS Run');
        button.type = 'button';
        button.disabled = state.preflight.simulationEligible !== true;
        button.addEventListener('click', () => createRun());
        panel.appendChild(button);
        if (!state.preflight.simulationEligible) {
            panel.appendChild(el('div', 'small text-muted mt-2', 'Simulation is blocked by preflight; playback eligibility is independent.'));
        }
        container.appendChild(panel);
    }

    // ── Durable v2 run ──

    function idempotencyKey(recordingId) {
        let key = state.idempotencyKeys.get(recordingId);
        if (!key) {
            const random = typeof crypto !== 'undefined' && crypto.randomUUID
                ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;
            key = `debugger-${recordingId}-${random}`.replace(/[^A-Za-z0-9._:-]/g, '-');
            state.idempotencyKeys.set(recordingId, key);
        }
        return key;
    }

    async function createRun() {
        if (!state.selectedRecordingId || !state.preflight?.simulationEligible) return;
        try {
            const response = await v2Post(
                `/recordings/${encodeURIComponent(state.selectedRecordingId)}/xrsps-runs`,
                {
                    conversionMode: 'AUTO',
                    openViewer: true,
                    stopPolicy: 'STOP_ON_FIRST_HARD',
                    idempotencyKey: idempotencyKey(state.selectedRecordingId),
                });
            state.run = response;
            state.report = null;
            state.events = [];
            state.lastEventOrdinal = -1;
            state.pollBackoffMs = POLL_BASE_MS;
            renderRunStatus();
            renderEvents();
            startPolling();
        } catch (error) {
            renderError(document.getElementById('replay-run-panel'), error);
        }
    }

    function renderRunStatus() {
        const container = document.getElementById('replay-run-panel');
        const run = state.run;
        if (!container || !run) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel mt-2');
        panel.appendChild(el('h5', '', 'Durable xRSPS run'));
        appendField(panel, 'Run', run.runId, true);
        appendField(panel, 'Phase', run.phase);
        const status = el('div', 'mt-1 mb-2');
        status.appendChild(el('span', `badge bg-${runStateColor(run.state)} me-1`, valueOrDash(run.state)));
        status.appendChild(el('span', 'badge bg-secondary', valueOrDash(run.outcome || 'active')));
        panel.appendChild(status);

        const metrics = el('div', 'small');
        appendField(metrics, 'Fidelity', run.fidelity);
        appendField(metrics, 'Conversion quality', run.conversionQuality);
        appendField(metrics, 'Coverage', fmtPercent(run.actionCoveragePct));
        appendField(metrics, 'Gaps / approximations', `${valueOrDash(run.gapCount)} / ${valueOrDash(run.approximationCount)}`);
        appendField(metrics, 'Trace', run.viewerAvailable ? `${valueOrDash(run.viewerTraceFrameCount)} verified frames` : 'not verified');
        appendField(metrics, 'Created / started', `${fmtTime(run.createdAt)} / ${fmtTime(run.startedAt)}`);
        panel.appendChild(metrics);

        const identity = el('div', 'small border-top pt-2 mt-2');
        identity.appendChild(el('strong', '', 'Runtime identity'));
        appendField(identity, 'Adapter', run.xrspsAdapterVersion, true);
        appendField(identity, 'Build', run.xrspsBuildCommit, true);
        appendField(identity, 'Cache', run.cacheRevision, true);
        appendField(identity, 'Content', run.contentRevision, true);
        panel.appendChild(identity);

        const buttons = el('div', 'mt-2');
        if (!TERMINAL_STATES.includes(run.state)) {
            const cancel = el('button', 'btn btn-outline-danger btn-sm', 'Cancel');
            cancel.type = 'button';
            cancel.addEventListener('click', () => cancelRun());
            buttons.appendChild(cancel);
        }
        if (run.viewerAvailable) {
            const viewer = el('button', 'btn btn-info btn-sm ms-1', 'Open Viewer');
            viewer.type = 'button';
            viewer.addEventListener('click', () => openViewer());
            buttons.appendChild(viewer);
        }
        if (run.terminalAt) {
            const report = el('button', 'btn btn-secondary btn-sm ms-1', 'Load Report');
            report.type = 'button';
            report.addEventListener('click', () => loadReport());
            buttons.appendChild(report);
        }
        if (buttons.childNodes.length) panel.appendChild(buttons);
        container.appendChild(panel);
    }

    async function cancelRun() {
        if (!state.run?.runId) return;
        try {
            await v2Post(`/runs/${encodeURIComponent(state.run.runId)}/cancel`);
            state.run = await v2Get(`/runs/${encodeURIComponent(state.run.runId)}`);
            renderRunStatus();
            if (TERMINAL_STATES.includes(state.run.state)) stopPolling();
        } catch (error) {
            renderError(document.getElementById('replay-run-panel'), error);
        }
    }

    async function openViewer() {
        if (!state.run?.runId || !state.run.viewerAvailable) return;
        try {
            const session = await v2Post(`/runs/${encodeURIComponent(state.run.runId)}/viewer-session`);
            if (!session?.viewerUrl) throw new Error('Viewer session did not return a launch URL');
            const opened = window.open(session.viewerUrl, '_blank', 'noopener,noreferrer');
            if (!opened) renderError(document.getElementById('replay-run-panel'),
                new Error('The browser blocked the observer window. Allow pop-ups and try again.'));
        } catch (error) {
            renderError(document.getElementById('replay-run-panel'), error);
        }
    }

    async function loadReport() {
        if (!state.run?.runId) return;
        try {
            state.report = await v2Get(`/runs/${encodeURIComponent(state.run.runId)}/report`);
            renderReport();
            await loadMismatches();
        } catch (error) {
            renderError(document.getElementById('replay-report-panel'), error);
        }
    }

    async function loadMismatches() {
        if (!state.run?.runId) return;
        try {
            const response = await v2Get(`/runs/${encodeURIComponent(state.run.runId)}/mismatches?limit=200`);
            state.mismatches = Array.isArray(response?.mismatches) ? response.mismatches : [];
            renderMismatches();
        } catch (error) {
            renderError(document.getElementById('replay-mismatch-panel'), error);
        }
    }

    function renderReport() {
        const container = document.getElementById('replay-report-panel');
        const report = state.report;
        if (!container || !report) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel mt-2');
        panel.appendChild(el('h5', '', 'Terminal report'));
        appendField(panel, 'Outcome', report.outcome);
        appendField(panel, 'Terminal reason', report.terminalReason);
        appendField(panel, 'Actions / checkpoints', `${valueOrDash(report.executedActionCount)} / ${valueOrDash(report.checkpointCount)}`);
        appendField(panel, 'Hard / soft / info mismatches', `${valueOrDash(report.mismatchHardCount)} / ${valueOrDash(report.mismatchSoftCount)} / ${valueOrDash(report.mismatchInfoCount)}`);
        appendField(panel, 'Report checksum', report.reportChecksum, true);
        appendField(panel, 'Trace checksum', report.viewerTraceChecksum, true);
        appendArtifactLinks(panel);
        if (report.reportJson) {
            const pre = el('pre', 'small mt-2');
            pre.style.maxHeight = '260px';
            pre.style.overflow = 'auto';
            try { pre.textContent = JSON.stringify(JSON.parse(report.reportJson), null, 2); }
            catch (_) { pre.textContent = String(report.reportJson); }
            panel.appendChild(pre);
        }
        container.appendChild(panel);
    }

    function renderMismatches() {
        const container = document.getElementById('replay-mismatch-panel');
        if (!container || !state.run) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel mt-2');
        panel.appendChild(el('h5', '', `Mismatch evidence (${state.mismatches.length})`));
        if (state.mismatches.length === 0) {
            panel.appendChild(el('div', 'small text-success', 'No recorded expectation differs from the simulated projection.'));
            container.appendChild(panel);
            return;
        }

        const first = state.mismatches[0];
        const jump = el('button', 'btn btn-outline-warning btn-sm mb-2', 'Open first mismatch in viewer');
        jump.type = 'button';
        jump.addEventListener('click', () => openMismatchViewer(first));
        panel.appendChild(jump);

        for (const mismatch of state.mismatches) {
            const card = el('div', 'border rounded p-2 mb-2');
            const heading = el('div', 'd-flex justify-content-between align-items-start gap-2');
            const title = el('div', 'small');
            title.appendChild(el('span', `badge bg-${mismatchLevelColor(mismatch.level)} me-1`, valueOrDash(mismatch.level)));
            title.appendChild(el('strong', '', valueOrDash(mismatch.ruleId)));
            title.appendChild(el('span', 'text-muted ms-2', `tick ${valueOrDash(mismatch.firstTick)} · ${valueOrDash(mismatch.domain)}`));
            heading.appendChild(title);
            const open = el('button', 'btn btn-outline-info btn-sm', 'Open');
            open.type = 'button';
            open.addEventListener('click', () => openMismatchViewer(mismatch));
            heading.appendChild(open);
            card.appendChild(heading);
            appendField(card, 'Pointer', mismatch.jsonPointer, true);
            appendJsonField(card, 'Recorded expected', mismatch.expected);
            appendJsonField(card, 'Simulated actual', mismatch.actual);
            if (mismatch.sourceEvidence) appendJsonField(card, 'Source evidence', mismatch.sourceEvidence);
            panel.appendChild(card);
        }
        container.appendChild(panel);
    }

    function appendJsonField(parent, label, value) {
        const wrapper = el('div', 'small mt-1');
        wrapper.appendChild(el('strong', '', `${label}:`));
        const pre = el('pre', 'small bg-dark rounded p-1 mt-1 mb-0');
        pre.style.maxHeight = '160px';
        pre.style.overflow = 'auto';
        try {
            pre.textContent = JSON.stringify(value === undefined ? null : value, null, 2);
        } catch (_) {
            pre.textContent = String(value);
        }
        wrapper.appendChild(pre);
        parent.appendChild(wrapper);
    }

    function appendArtifactLinks(parent) {
        if (!state.run?.runId) return;
        const group = el('div', 'mt-2');
        group.appendChild(el('strong', 'small d-block mb-1', 'Immutable artifacts'));
        for (const name of ['manifest', 'trace', 'hashes', 'report']) {
            const link = el('a', 'btn btn-outline-secondary btn-sm me-1 mb-1', name);
            link.href = `${V2_ROOT}/runs/${encodeURIComponent(state.run.runId)}/artifacts/${name}`;
            link.download = `replay-${state.run.runId}-${name}.json`;
            link.textContent = `Download ${name}`;
            group.appendChild(link);
        }
        parent.appendChild(group);
    }

    async function openMismatchViewer(mismatch) {
        if (!state.run?.runId) return;
        try {
            const session = await v2Post(`/runs/${encodeURIComponent(state.run.runId)}/viewer-session`);
            if (!session?.viewerUrl) throw new Error('Viewer session did not return a launch URL');
            const url = new URL(session.viewerUrl, window.location.origin);
            if (mismatch?.firstTick !== undefined && mismatch?.firstTick !== null) {
                url.searchParams.set('tick', String(mismatch.firstTick));
            }
            const opened = window.open(url.toString(), '_blank', 'noopener,noreferrer');
            if (!opened) renderError(document.getElementById('replay-mismatch-panel'),
                new Error('The browser blocked the observer window. Allow pop-ups and try again.'));
        } catch (error) {
            renderError(document.getElementById('replay-mismatch-panel'), error);
        }
    }

    // ── Bounded event polling ──

    function startPolling() {
        stopPolling();
        void pollRun();
    }

    function stopPolling() {
        if (state.pollHandle !== null) {
            clearTimeout(state.pollHandle);
            state.pollHandle = null;
        }
    }

    function schedulePoll(delay) {
        stopPolling();
        state.pollHandle = setTimeout(() => { void pollRun(); }, delay);
    }

    async function pollRun() {
        if (!state.run?.runId) return;
        const runId = encodeURIComponent(state.run.runId);
        try {
            const page = await v2Get(`/runs/${runId}/events?after=${state.lastEventOrdinal}&limit=100`);
            if (Array.isArray(page?.events)) {
                state.events.push(...page.events);
                if (state.events.length > MAX_STORED_EVENTS) state.events = state.events.slice(-MAX_STORED_EVENTS);
                if (typeof page.nextAfter === 'number') state.lastEventOrdinal = page.nextAfter;
                renderEvents();
            }
            state.run = await v2Get(`/runs/${runId}`);
            renderRunStatus();
            state.pollBackoffMs = POLL_BASE_MS;
            if (TERMINAL_STATES.includes(state.run.state)) {
                stopPolling();
                await loadReport();
            } else {
                schedulePoll(POLL_BASE_MS);
            }
        } catch (error) {
            console.warn('Replay polling failed', error);
            state.pollBackoffMs = Math.min(state.pollBackoffMs * 2, POLL_MAX_MS);
            schedulePoll(state.pollBackoffMs);
        }
    }

    function renderEvents() {
        const container = document.getElementById('replay-events-panel');
        if (!container || !state.run) return;
        container.replaceChildren();
        const panel = el('div', 'glass-panel mt-2');
        panel.appendChild(el('h5', '', `Events (${state.events.length})`));
        const list = el('div', 'tick-cards-panel');
        list.style.maxHeight = '320px';
        for (const event of state.events.slice(-50)) {
            const card = el('div', 'tick-card');
            const header = el('div', 'tick-header');
            header.appendChild(el('span', 'tick-number', `#${valueOrDash(event.ordinal)}`));
            header.appendChild(el('span', 'badge bg-secondary', `Tick ${valueOrDash(event.tick)}`));
            header.appendChild(el('span', '', valueOrDash(event.eventType)));
            card.appendChild(header);
            if (event.category) card.appendChild(el('div', 'small text-muted', event.category));
            if (event.payload) card.appendChild(el('code', 'small text-muted', JSON.stringify(event.payload)));
            list.appendChild(card);
        }
        panel.appendChild(list);
        container.appendChild(panel);
    }

    function clearPanel(id) {
        const node = document.getElementById(id);
        if (node) node.replaceChildren();
    }

    function runStateColor(stateValue) {
        return {
            QUEUED: 'secondary', CLAIMED: 'info', STARTING: 'info', RUNNING: 'primary',
            CANCELLING: 'warning', SUCCEEDED: 'success', FAILED: 'danger',
            CANCELLED: 'dark', TIMED_OUT: 'warning',
        }[stateValue] || 'secondary';
    }

    function mismatchLevelColor(level) {
        return { HARD: 'danger', SOFT: 'warning text-dark', INFO: 'info text-dark' }[level] || 'secondary';
    }

    window.ReplayDebugger = {
        loadCapabilities,
        loadCatalog,
        selectRecording,
        createRun,
        cancelRun,
        openViewer,
        loadReport,
        loadMismatches,
        openMismatchViewer,
    };

    document.addEventListener('DOMContentLoaded', () => {
        loadCapabilities().catch((error) => renderError(document.getElementById('replay-capability-badge'), error));
        loadCatalog().catch((error) => renderError(document.getElementById('replay-recording-list'), error));
        const mode = document.getElementById('replayModeSelect');
        if (mode) {
            mode.value = 'CAUSAL_SIMULATION';
            mode.addEventListener('change', () => {
                if (mode.value !== 'CAUSAL_SIMULATION') {
                    mode.value = 'CAUSAL_SIMULATION';
                    const panel = document.getElementById('replay-preflight-panel');
                    if (panel) renderError(panel, new ReplayApiError(
                        'Only causal xRSPS simulation is available in the v2 flow.',
                        'MODE_UNAVAILABLE', 'Observed video/log playback remains available in its existing player.'));
                }
            });
        }
    });
})();

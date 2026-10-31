(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else {
    root.MouseVideoImport = api;
    document.addEventListener('DOMContentLoaded', () => {
      const panel = document.querySelector('[data-mouse-video]');
      if (panel) api.mount(panel);
    });
  }
})(typeof window === 'undefined' ? globalThis : window, function () {
  'use strict';

  const base = '/api/debug/mouse-videos';
  const storageKey = 'mouse-video:state';
  const CORRECTION_KINDS = [
    'CURSOR_VISIBILITY',
    'PAUSE',
    'SCENE_CUT',
    'ACTION_INDICATOR',
    'TARGET_REGION',
    'CAMERA_ACTIVITY',
  ];
  const MAX_CORRECTIONS = 2000;
  let nonce = null;
  let nonceExpiry = 0;

  function newRequestId() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    return '00000000-0000-4000-8000-' + Date.now().toString(16).padStart(12, '0').slice(-12);
  }

  /**
   * Terminal exact-preview outcome, or null while the server is still working. CANCELLED and
   * FAILED stop polling; the caller must use a new request identity to try again.
   */
  function previewTerminalError(preview) {
    const state = preview && preview.state;
    if (state === 'CANCELLED') return 'Exact preview was cancelled';
    if (state === 'RECONCILIATION_REQUIRED') {
      return 'Exact preview needs operator reconciliation';
    }
    if (state === 'FAILED') {
      return (preview && preview.failureCode) || 'The server could not produce this exact frame';
    }
    return null;
  }

  function formatBytes(value) {
    if (typeof value !== 'number' || !isFinite(value) || value < 0) return '—';
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let size = value;
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit += 1;
    }
    return (unit === 0 ? size : size.toFixed(1)) + ' ' + units[unit];
  }

  function formatLimits(limits) {
    if (!limits) return '';
    return (
      'Limits: upload ≤ ' +
      formatBytes(limits.maxUploadBytes) +
      ', duration ≤ ' +
      Math.round((limits.maxDurationSeconds || 0) / 60) +
      ' min, resolution ≤ ' +
      (limits.maxWidth || '?') +
      '×' +
      (limits.maxHeight || '?') +
      ', ≤ ' +
      (limits.maxDecodeFrames || '?') +
      ' frames.'
    );
  }

  function selectedProxyArtifact(source) {
    if (!source || !source.selectedAnalysisId) return null;
    return (
      (source.artifacts || []).find(
        (artifact) =>
          artifact.role === 'PROXY' &&
          artifact.state === 'FINALIZED' &&
          artifact.analysisId === source.selectedAnalysisId
      ) || null
    );
  }

  function describeSource(source) {
    if (!source) return 'Unknown source';
    return (
      (source.kind || 'SOURCE') +
      ' · ' +
      (source.originReview || 'UNREVIEWED') +
      ' · ' +
      (source.status || 'NO_JOB')
    );
  }

  /**
   * Source id carried by a `?source=<uuid>` deep link, or null when the link is absent or malformed.
   * Only a whole UUID is accepted so a stray query value can never be used as a request path segment.
   */
  function linkedSourceId(search) {
    const raw = new URLSearchParams(search || '').get('source');
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(raw || '')
      ? raw
      : null;
  }

  /** Describes a frozen-index frame without rounding its integer PTS or timebase. */
  function describeFrame(frame) {
    if (!frame) return 'No frame selected';
    const parts = ['frame ' + frame.ordinal];
    parts.push('pts ' + (frame.pts === null || frame.pts === undefined ? 'unknown' : frame.pts));
    if (frame.ptsTime !== null && frame.ptsTime !== undefined) parts.push('t ' + frame.ptsTime);
    parts.push(frame.key ? 'keyframe' : 'predicted');
    return parts.join(' · ');
  }

  /**
   * Builds one correction entry from form values. Kinds that need exact source-frame pixels are not
   * offered here: without an exact-frame preview the browser cannot claim source coordinates, so a
   * visible-point correction is refused instead of being guessed.
   */
  function buildCorrection(kind, fields, ordinal) {
    const values = fields || {};
    const entry = { kind: kind };
    const number = (name) => {
      const value = Number(values[name]);
      if (!Number.isFinite(value)) throw new Error('A finite ' + name + ' is required');
      return value;
    };
    if (kind === 'CURSOR_VISIBILITY') {
      entry.frameOrdinal = Number.isInteger(values.frameOrdinal) ? values.frameOrdinal : ordinal;
      entry.visibility = values.visibility || 'HIDDEN';
      if (entry.visibility === 'VISIBLE') {
        if (!Number.isFinite(Number(values.x)) || !Number.isFinite(Number(values.y))) {
          throw new Error(
            'A visible cursor point requires an exact-frame preview and finite source coordinates'
          );
        }
        entry.x = number('x');
        entry.y = number('y');
      } else {
        entry.unknownReason = String(values.unknownReason || '').trim() || 'unspecified';
      }
    } else if (kind === 'TARGET_REGION') {
      entry.frameOrdinal = Number.isInteger(values.frameOrdinal) ? values.frameOrdinal : ordinal;
      entry.x = number('x');
      entry.y = number('y');
      entry.width = number('width');
      entry.height = number('height');
      const label = String(values.label || '').trim();
      if (label) entry.label = label.slice(0, 80);
    } else if (kind === 'CAMERA_ACTIVITY') {
      entry.frameOrdinal = Number.isInteger(values.frameOrdinal) ? values.frameOrdinal : ordinal;
      entry.activity = values.activity || 'REGION_CHANGE';
      if (entry.activity !== 'NONE') {
        entry.x = number('x');
        entry.y = number('y');
        entry.width = number('width');
        entry.height = number('height');
      }
    } else if (kind === 'PAUSE') {
      entry.startFrameOrdinal = Number.isInteger(values.startFrameOrdinal)
        ? values.startFrameOrdinal
        : ordinal;
      entry.endFrameOrdinal = Number.isInteger(values.endFrameOrdinal)
        ? values.endFrameOrdinal
        : ordinal;
    } else if (kind === 'SCENE_CUT') {
      entry.beforeFrameOrdinal = Number.isInteger(values.beforeFrameOrdinal)
        ? values.beforeFrameOrdinal
        : ordinal;
      entry.afterFrameOrdinal = Number.isInteger(values.afterFrameOrdinal)
        ? values.afterFrameOrdinal
        : ordinal + 1;
    } else if (kind === 'ACTION_INDICATOR') {
      entry.frameOrdinal = Number.isInteger(values.frameOrdinal) ? values.frameOrdinal : ordinal;
      entry.indicator = values.indicator || 'MENU_OPEN';
    } else {
      throw new Error('Unsupported correction kind: ' + kind);
    }
    const confidence = Number(values.confidence);
    if (Number.isFinite(confidence) && confidence >= 0 && confidence <= 1) {
      entry.confidence = confidence;
    }
    const note = String(values.note || '').trim();
    if (note) entry.note = note.slice(0, 280);
    const provenance = String(values.provenance || '').trim().toUpperCase();
    if (['BLIND_UNVERIFIED', 'DETECTOR_ASSISTED', 'UNKNOWN'].includes(provenance)) {
      entry.provenance = provenance;
    }
    return entry;
  }

  /** Append-only local draft with the same duplicate/count rules the server enforces. */
  function addCorrection(draft, entry) {
    const items = Array.isArray(draft) ? draft.slice() : [];
    if (items.length >= MAX_CORRECTIONS) {
      throw new Error('The draft already holds the maximum ' + MAX_CORRECTIONS + ' corrections');
    }
    const encoded = JSON.stringify(entry);
    if (items.some((item) => JSON.stringify(item) === encoded)) {
      throw new Error('That correction is already in the draft');
    }
    items.push(entry);
    return items;
  }

  function loadState() {
    try {
      return JSON.parse(globalThis.localStorage.getItem(storageKey)) || {};
    } catch (ignored) {
      return {};
    }
  }

  function saveState(patch) {
    const next = Object.assign(loadState(), patch);
    try {
      globalThis.localStorage.setItem(storageKey, JSON.stringify(next));
    } catch (ignored) {
      // storage unavailable; identity is best-effort only
    }
    return next;
  }

  async function request(url, options) {
    const response = await fetch(url, options);
    const text = await response.text();
    let payload = null;
    if (text) {
      try {
        payload = JSON.parse(text);
      } catch (ignored) {
        payload = null;
      }
    }
    if (!response.ok) {
      const error = new Error(
        (payload && (payload.message || payload.error)) || response.statusText || 'Request failed'
      );
      error.code = payload && payload.code;
      error.status = response.status;
      throw error;
    }
    return payload;
  }

  async function writeNonce() {
    const now = Date.now();
    if (nonce && now < nonceExpiry) return nonce;
    const issued = await request(base + '/write-nonce');
    nonce = issued.nonce;
    nonceExpiry = Date.parse(issued.expiresAt) - 5000;
    return nonce;
  }

  async function writeHeaders(extra) {
    const headers = Object.assign({}, extra);
    headers['X-Mouse-Video-Nonce'] = await writeNonce();
    return headers;
  }

  function mount(panel) {
    if (!panel || panel.dataset.mounted) return;
    panel.dataset.mounted = 'true';
    const reviewCore =
      (typeof window !== 'undefined' && window.MouseVideoReviewCore) ||
      (typeof globalThis !== 'undefined' && globalThis.MouseVideoReviewCore) ||
      null;

    const status = panel.querySelector('[data-video-status]');
    const mode = panel.querySelector('[data-video-mode]');
    const uploadField = panel.querySelector('[data-video-upload-field]');
    const youtubeField = panel.querySelector('[data-video-youtube-field]');
    const form = panel.querySelector('[data-video-import]');
    const rows = panel.querySelector('[data-video-rows]');
    const inspector = panel.querySelector('[data-video-inspector]');
    let capabilities = null;
    let pollTimer = null;
    let inspectorToken = 0;
    let inspectedSourceId = null;

    const review = {
      sourceId: null,
      source: null,
      analysisId: null,
      frameCount: 0,
      frames: [],
      byOrdinal: new Map(),
      nextCursor: null,
      artifactChecksum: null,
      selectedOrdinal: null,
      draft: [],
      draftVersion: 0,
      draftContext: null,
      history: [],
      baseRevision: 0,
      conflict: false,
      dirty: false,
      observations: [],
      pendingSave: null,
      session: null,
      policy: null,
      lastAcknowledgement: null,
      historyCursor: null,
      generation: 0,
      pendingSession: null,
      autosaveTimer: null,
      reviewMode: {
        active: false,
        blind: false,
        forcedBlind: false,
        queue: [],
        index: 0,
        seed: 0,
        labelsSinceSave: 0,
        undo: [],
        saving: false,
        saveQueue: null,
        predictionsWasChecked: false,
      },
      preview: {
        token: 0,
        ordinal: null,
        metadata: null,
        imageOrdinal: null,
        loading: false,
        error: '',
        point: null,
        region: null,
      },
    };

    const setStatus = (message) => {
      if (status) status.textContent = message;
    };

    if (reviewCore) {
      review.reviewMode.saveQueue = reviewCore.createSaveQueue(sendReviewSnapshot);
    }

    const setReviewStatus = (message) => {
      const target = panel.querySelector('[data-review-status]');
      if (target) target.textContent = message;
    };

    const toggleMode = () => {
      const youtube = mode && mode.value === 'youtube';
      if (uploadField) uploadField.hidden = youtube;
      if (youtubeField) youtubeField.hidden = !youtube;
    };
    if (mode) mode.addEventListener('change', toggleMode);

    async function loadCapabilities() {
      capabilities = await request(base + '/capabilities');
      const limits = panel.querySelector('[data-video-limits]');
      if (limits) limits.textContent = formatLimits(capabilities.limits);
      if (!capabilities.enabled) {
        setStatus('External video import is disabled on this server.');
        if (form) form.querySelector('[data-video-submit]').disabled = true;
      } else if (!capabilities.tools.ffprobeAvailable) {
        setStatus('ffprobe is unavailable; imports cannot be analysed yet.');
      } else {
        setStatus('Ready.');
      }
    }

    async function refreshCatalog() {
      const kind = panel.querySelector('[data-video-filter-kind]');
      const query = kind && kind.value ? '&kind=' + encodeURIComponent(kind.value) : '';
      const page = await request(base + '/sources?limit=25' + query);
      if (!rows) return;
      rows.innerHTML = '';
      for (const source of page.items) {
        const tr = document.createElement('tr');
        const idCell = document.createElement('td');
        idCell.textContent = source.id.slice(0, 8);
        const originCell = document.createElement('td');
        originCell.textContent = source.declaredOrigin;
        const statusCell = document.createElement('td');
        statusCell.textContent = source.status;
        const coverageCell = document.createElement('td');
        coverageCell.textContent = source.coverage || '—';
        const actionCell = document.createElement('td');
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = 'Inspect';
        button.setAttribute('aria-label', 'Inspect source ' + source.id);
        button.addEventListener('click', () => inspect(source.id));
        actionCell.appendChild(button);
        tr.append(idCell, originCell, statusCell, coverageCell, actionCell);
        rows.appendChild(tr);
      }
      if (!page.items.length) {
        const tr = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = 5;
        cell.textContent = 'No imported sources yet.';
        tr.appendChild(cell);
        rows.appendChild(tr);
      }
    }

    async function inspect(sourceId) {
      const token = ++inspectorToken;
      // Finish and persist the current review before its source identity can change.
      if (review.sourceId && review.sourceId !== sourceId &&
          (review.reviewMode.active || review.pendingSave || review.dirty)) {
        const closed = review.reviewMode.active ? await exitReviewMode() : await flushReviewSaves();
        if (!closed) {
          setStatus('Unsaved review labels could not be saved; the current source stays loaded.');
          return;
        }
      }
      inspectedSourceId = sourceId;
      if (pollTimer) clearTimeout(pollTimer);
      pollTimer = null;
      saveState({ lastSourceId: sourceId });
      const source = await request(base + '/sources/' + sourceId);
      if (token !== inspectorToken) return;
      if (!inspector) return;
      inspector.hidden = false;
      panel.querySelector('[data-video-inspector-title]').textContent =
        describeSource(source) + ' · ' + source.id;
      const player = panel.querySelector('[data-video-player]');
      if (player) {
        const proxy = selectedProxyArtifact(source);
        if (proxy) {
          player.src =
            base +
            '/sources/' +
            sourceId +
            '/media/PROXY?analysisId=' +
            encodeURIComponent(source.selectedAnalysisId);
          player.hidden = false;
        } else if (source.originalQualified) {
          player.src = base + '/sources/' + sourceId + '/media/ORIGINAL';
          player.hidden = false;
        } else {
          player.removeAttribute('src');
          player.hidden = true;
          setStatus('Preview unavailable: the original is quarantined until it is verified.');
        }
      }
      renderMetadata(source);
      renderJobs(source);
      renderArtifacts(source);
      if (token !== inspectorToken) return;
      const unknowns = panel.querySelector('[data-video-unknowns]');
      if (unknowns) {
        unknowns.textContent =
          'Native ticks: unavailable · Physical clicks: unavailable · Training eligibility: false';
      }
      await openReview(source);
      if (review.policy?.predictionAccess && !review.reviewMode.blind) await loadExports(sourceId);
    }

    function renderMetadata(source) {
      const metadata = panel.querySelector('[data-video-metadata]');
      if (!metadata) return;
      metadata.innerHTML = '';
      const entries = {
        'Original hash': source.originalChecksum || '—',
        'Original size': formatBytes(source.originalSizeBytes),
        'Verified type': source.verifiedMediaType || 'unqualified',
        'Review revision': String(source.reviewRevision),
      };
      for (const [key, value] of Object.entries(entries)) {
        const dt = document.createElement('dt');
        dt.textContent = key;
        const dd = document.createElement('dd');
        dd.textContent = value;
        metadata.append(dt, dd);
      }
    }

    function renderJobs(source) {
      const jobs = panel.querySelector('[data-video-jobs]');
      if (!jobs) return;
      jobs.innerHTML = '';
      for (const job of source.jobs || []) {
        const li = document.createElement('li');
        li.textContent = job.kind + ' · ' + job.status + ' · ' + job.stage;
        if (!['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'NEEDS_RECONCILIATION'].includes(job.status)) {
          const cancel = document.createElement('button');
          cancel.type = 'button';
          cancel.textContent = 'Cancel';
          cancel.addEventListener('click', () => cancelJob(source.id, job.id));
          li.appendChild(cancel);
          pollJob(source.id, job.id);
        }
        jobs.appendChild(li);
      }
    }

    function renderArtifacts(source) {
      const artifacts = panel.querySelector('[data-video-artifacts]');
      if (!artifacts) return;
      artifacts.innerHTML = '';
      for (const artifact of source.artifacts || []) {
        const li = document.createElement('li');
        li.textContent =
          artifact.role +
          ' · ' +
          artifact.state +
          ' · ' +
          formatBytes(artifact.sizeBytes) +
          (artifact.analysisId ? ' · analysis ' + artifact.analysisId.slice(0, 8) : '');
        artifacts.appendChild(li);
      }
    }

    function pollJob(sourceId, jobId) {
      if (pollTimer || inspectedSourceId !== sourceId) return;
      const token = inspectorToken;
      const tick = async () => {
        pollTimer = null;
        if (token !== inspectorToken) return;
        try {
          const job = await request(base + '/jobs/' + jobId);
          if (token !== inspectorToken) return;
          if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'NEEDS_RECONCILIATION'].includes(job.status)) {
            setStatus(job.status === 'NEEDS_RECONCILIATION'
              ? 'Worker cleanup requires operator reconciliation.' : 'Job ' + job.status + '.');
            await inspect(sourceId);
            return;
          }
        } catch (ignored) {
          // transient; retry
        }
        if (token === inspectorToken) pollTimer = setTimeout(tick, 2000);
      };
      pollTimer = setTimeout(tick, 2000);
    }

    async function cancelJob(sourceId, jobId) {
      try {
        const job = await request(base + '/jobs/' + jobId);
        const result = await request(base + '/jobs/' + jobId + '/cancel', {
          method: 'POST',
          headers: await writeHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({ expectedRevision: job.cancellationRevision || 0 }),
        });
        setStatus(
          result.status === 'CANCELLED'
            ? 'Cancelled.'
            : result.status === 'NEEDS_RECONCILIATION'
              ? 'Stopping worker… awaiting reconciliation.'
              : 'Stopping worker…'
        );
        await inspect(sourceId);
      } catch (error) {
        setStatus('Cancel failed: ' + error.message);
      }
    }

    // ------------------------------------------------------------------ review

    async function openReview(source) {
      const section = panel.querySelector('[data-video-review]');
      if (!section) return;
      // A job finishing on this source re-inspects it. That refresh must not reset the review in
      // progress: right after an acknowledged save the draft is clean but review mode, the open
      // session and its seeded labels are still live, and a reset would drop the next label.
      if (
        reviewCore
          ? reviewCore.preservesReviewOnRefresh(review, source.id)
          : review.sourceId === source.id && review.dirty
      ) {
        review.source = source;
        await loadHistory();
        setReviewStatus(
          review.dirty
            ? 'Source refreshed. Unsaved review and its base revision were preserved.'
            : 'Source refreshed. The open review was preserved.'
        );
        return;
      }
      review.sourceId = source.id;
      review.source = source;
      review.baseRevision = source.reviewRevision || 0;
      review.pendingSave = null;
      review.session = null;
      review.policy = null;
      review.lastAcknowledgement = null;
      review.generation += 1;
      review.draft = [];
      review.draftVersion += 1;
      review.draftContext = null;
      review.dirty = false;
      review.frames = [];
      review.byOrdinal = new Map();
      review.nextCursor = null;
      review.artifactChecksum = null;
      review.selectedOrdinal = null;
      review.conflict = false;
      review.observations = [];
      resetPreview();
      resetReviewModeUi();
      section.hidden = false;
      const analysisSelect = panel.querySelector('[data-review-analysis]');
      if (analysisSelect) {
        analysisSelect.innerHTML = '';
        for (const analysis of source.analyses || []) {
          const option = document.createElement('option');
          option.value = analysis.id;
          option.textContent =
            (analysis.algorithmId || 'analysis') +
            ' · ' +
            (analysis.status || 'UNKNOWN') +
            (analysis.selected ? ' · selected' : '');
          option.disabled = analysis.status !== 'COMPLETE';
          analysisSelect.appendChild(option);
        }
        analysisSelect.value = source.selectedAnalysisId || '';
      }
      review.analysisId = source.selectedAnalysisId || null;
      // Policy is resolved before any prediction/history/export read or overlay rendering.
      review.reviewMode.blind = true;
      syncBlindUi();
      if (review.analysisId) {
        await loadReviewPolicy();
        if (review.policy.forcedBlind) {
          const baseline = (source.analyses || []).find(item => item.algorithmId === 'probe/v1' && item.status === 'COMPLETE');
          if (baseline && review.analysisId !== baseline.id) {
            review.analysisId = baseline.id;
            if (analysisSelect) analysisSelect.value = baseline.id;
            await loadReviewPolicy();
          }
        }
      }
      // A held-out source is blind from the first render, before any observation is displayed.
      const mode = review.reviewMode;
      mode.forcedBlind = reviewCore
        ? Boolean(review.policy?.forcedBlind || reviewCore.isHeldoutContext(source.context))
        : mode.forcedBlind;
      mode.blind = mode.forcedBlind || Boolean(review.policy?.eligibleModes?.includes('WITHOUT_SUGGESTIONS'));
      const heldoutBlind = panel.querySelector('[data-review-blind-toggle]');
      if (heldoutBlind) {
        heldoutBlind.checked = mode.blind;
        heldoutBlind.disabled = mode.forcedBlind;
      }
      syncBlindUi();
      renderDraft();
      renderConflict();
      renderOverlay();
      await loadHistory();
      if (inspectedSourceId !== source.id) return;
      if (review.analysisId) {
        await loadFrames(null);
        await loadObservations();
        setReviewStatus('Pinned to analysis ' + review.analysisId.slice(0, 8) + '.');
      } else {
        renderFrames();
        setReviewStatus(
          'No completed analysis yet: frame review and corrections need a frozen frame index.'
        );
      }
    }

    async function loadFrames(cursor, options) {
      if (!review.analysisId) return;
      const sourceId = review.sourceId;
      const analysisId = review.analysisId;
      const suffix = cursor ? '&cursor=' + encodeURIComponent(cursor) : '';
      const page = await request(
        base +
          '/sources/' +
          review.sourceId +
          '/analyses/' +
          review.analysisId +
          '/frame-index?limit=200' +
          suffix
      );
      if (review.sourceId !== sourceId || review.analysisId !== analysisId) return;
      if (review.artifactChecksum && page.artifactChecksum !== review.artifactChecksum) {
        setReviewStatus(
          'The frozen index changed between pages; reload the analysis before reviewing.'
        );
        return;
      }
      review.artifactChecksum = page.artifactChecksum;
      review.frameCount = page.frameCount;
      review.nextCursor = page.nextCursor || null;
      for (const frame of page.items || []) {
        if (!review.byOrdinal.has(frame.ordinal)) {
          review.byOrdinal.set(frame.ordinal, frame);
          review.frames.push(frame);
        }
      }
      if (review.selectedOrdinal === null && review.frames.length) {
        review.selectedOrdinal = review.frames[0].ordinal;
      }
      if (!(options && options.silent)) {
        renderFrames();
        renderFrameDetail();
      }
      const more = panel.querySelector('[data-review-load-more]');
      if (more) more.disabled = !review.nextCursor;
    }

    function renderFrames() {
      const body = panel.querySelector('[data-frame-rows]');
      if (!body) return;
      body.innerHTML = '';
      if (!review.byOrdinal.size) {
        const tr = document.createElement('tr');
        const cell = document.createElement('td');
        cell.colSpan = 5;
        cell.textContent = review.analysisId
          ? 'No frozen frames are published for this analysis.'
          : 'Select a completed analysis to load its frozen frames.';
        tr.appendChild(cell);
        body.appendChild(tr);
        return;
      }
      const visibleStart = Math.max(0, (review.selectedOrdinal || 0) - 100);
      for (const frame of review.frames.slice(visibleStart, visibleStart + 200)) {
        const tr = document.createElement('tr');
        if (frame.ordinal === review.selectedOrdinal) tr.setAttribute('aria-current', 'true');
        const ordinal = document.createElement('td');
        ordinal.textContent = String(frame.ordinal);
        const pts = document.createElement('td');
        pts.textContent = frame.pts === null || frame.pts === undefined ? 'unknown' : frame.pts;
        const time = document.createElement('td');
        time.textContent = frame.ptsTime === null || frame.ptsTime === undefined ? '—' : frame.ptsTime;
        const key = document.createElement('td');
        key.textContent = frame.key ? 'key' : '';
        const action = document.createElement('td');
        const select = document.createElement('button');
        select.type = 'button';
        select.textContent = 'Select';
        select.setAttribute('aria-label', 'Select ' + describeFrame(frame));
        select.addEventListener('click', () => selectFrame(frame.ordinal));
        action.appendChild(select);
        tr.append(ordinal, pts, time, key, action);
        body.appendChild(tr);
      }
    }

    function selectFrame(ordinal, seek) {
      const frame = review.byOrdinal.get(ordinal);
      if (!frame) {
        setReviewStatus('Frame ' + ordinal + ' is not in the loaded page; load more frames first.');
        return;
      }
      review.selectedOrdinal = ordinal;
      review.preview.point = null;
      review.preview.region = null;
      renderFrames();
      renderFrameDetail();
      if (seek !== false) seekProxy(frame);
      loadPreview(frame);
    }

    function renderFrameDetail() {
      const detail = panel.querySelector('[data-frame-detail]');
      if (!detail) return;
      const frame = review.byOrdinal.get(review.selectedOrdinal);
      const ordinalInput = panel.querySelector('[data-frame-ordinal]');
      if (ordinalInput) ordinalInput.value = frame ? String(frame.ordinal) : '';
      detail.textContent = frame
        ? describeFrame(frame) + ' · of ' + review.frameCount + ' indexed frames'
        : 'No frame selected';
      const note = panel.querySelector('[data-frame-note]');
      if (note) {
        note.textContent =
          'The player seek below uses the browser video element and is approximate; only the ' +
          'frozen frame ordinal, PTS and timebase identify the source frame.';
      }
      syncCorrectionDefaults();
    }

    function seekProxy(frame) {
      const player = panel.querySelector('[data-video-player]');
      if (!player || !player.src) {
        setReviewStatus('No playable proxy is available; the frame ordinal is still exact.');
        return;
      }
      const seconds = Number(frame.ptsTime);
      if (!Number.isFinite(seconds)) {
        setReviewStatus(
          'This frame has no decimal time; use its exact PTS (' + frame.pts + ') with a decoder.'
        );
        return;
      }
      try {
        player.currentTime = Math.max(0, seconds);
        setReviewStatus('Seeked the proxy to approximately t=' + seconds + 's.');
      } catch (ignored) {
        setReviewStatus('The browser could not seek this proxy.');
      }
    }

    function stepFrame(delta) {
      if (review.reviewMode.active) return;
      if (!review.frames.length) return;
      const index = review.frames.findIndex((frame) => frame.ordinal === review.selectedOrdinal);
      const next = Math.min(Math.max(0, (index < 0 ? 0 : index) + delta), review.frames.length - 1);
      selectFrame(review.frames[next].ordinal);
    }

    function resetPreview() {
      const token = (review.preview.token || 0) + 1;
      review.preview = {
        token,
        ordinal: null,
        metadata: null,
        imageOrdinal: null,
        loading: false,
        error: '',
        point: null,
        region: null,
      };
      const image = panel.querySelector('[data-preview-image]');
      if (image) {
        image.hidden = true;
        image.removeAttribute('src');
      }
      const stage = panel.querySelector('[data-preview-stage]');
      if (stage) stage.dataset.ready = 'false';
      const empty = panel.querySelector('[data-preview-empty]');
      if (empty) {
        empty.hidden = false;
        empty.textContent = 'Select a frame to request an exact source preview.';
      }
      const status = panel.querySelector('[data-preview-status]');
      if (status) status.textContent = '';
      const identity = panel.querySelector('[data-preview-identity]');
      if (identity) identity.textContent = '';
      const selection = panel.querySelector('[data-preview-selection]');
      if (selection) selection.textContent = '';
      renderOverlay();
    }

    function setPreviewStatus(message) {
      const status = panel.querySelector('[data-preview-status]');
      if (status) status.textContent = message;
    }

    function previewIdentity(metadata) {
      if (!metadata) return '';
      return (
        'Exact frame ' +
        metadata.frameOrdinal +
        ' · pts ' +
        (metadata.pts == null ? 'unknown' : metadata.pts) +
        ' · timebase ' +
        ((metadata.timeBase && metadata.timeBase.raw) || 'unknown') +
        ' · ' +
        (metadata.outputWidth || '?') +
        '×' +
        (metadata.outputHeight || '?') +
        ' from ' +
        (metadata.sourceWidth || '?') +
        '×' +
        (metadata.sourceHeight || '?') +
        ' · ' +
        (metadata.method || 'frame-png/v1')
      );
    }

    async function loadPreview(frame, force) {
      if (!review.analysisId || !frame) return;
      const token = ++review.preview.token;
      review.preview = {
        token,
        ordinal: frame.ordinal,
        metadata: null,
        imageOrdinal: null,
        loading: true,
        error: '',
        point: null,
        region: null,
      };
      renderOverlay();
      const image = panel.querySelector('[data-preview-image]');
      if (image) {
        image.hidden = true;
        image.removeAttribute('src');
      }
      const stage = panel.querySelector('[data-preview-stage]');
      if (stage) stage.dataset.ready = 'false';
      const empty = panel.querySelector('[data-preview-empty]');
      if (empty) {
        empty.hidden = false;
        empty.textContent = force ? 'Requesting a fresh exact preview…' : 'Loading the exact source frame…';
      }
      setPreviewStatus('Exact preview pending for frame ' + frame.ordinal + '.');
      try {
        if (!force) {
          const page = await request(
            base +
              '/sources/' +
              review.sourceId +
              '/analyses/' +
              review.analysisId +
              '/frame-previews'
          );
          if (token !== review.preview.token) return;
          const ready = (page.items || [])
            .filter((item) => item.state === 'READY' && item.frameOrdinal === frame.ordinal)
            .sort((left, right) => String(left.readyAt).localeCompare(String(right.readyAt)))
            .pop();
          if (ready) return applyPreview(ready, token);
        }
        const key = 'previewRequest' + frame.ordinal;
        const requestId = panel.dataset[key] || (panel.dataset[key] = newRequestId());
        const receipt = await request(
          base +
            '/sources/' +
            review.sourceId +
            '/analyses/' +
            review.analysisId +
            '/frame-previews',
          {
            method: 'POST',
            headers: await writeHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify({
              requestId: requestId,
              frameOrdinal: frame.ordinal,
              maxWidth: 1280,
              maxHeight: 720,
            }),
          }
        );
        if (token !== review.preview.token) return;
        if (receipt.state === 'READY') {
          const metadata = await request(receipt.metadataUrl);
          if (token === review.preview.token) applyPreview(metadata, token);
          return;
        }
        const receiptError = previewTerminalError(receipt);
        if (receiptError) {
          // Terminal: a retry must use a new request identity, not replay this receipt.
          delete panel.dataset[key];
          throw new Error(receiptError);
        }
        for (let attempt = 0; attempt < 80 && token === review.preview.token; attempt++) {
          await new Promise((resolve) => setTimeout(resolve, 750));
          if (token !== review.preview.token) return;
          const metadata = await request(receipt.metadataUrl);
          if (metadata.state === 'READY') {
            applyPreview(metadata, token);
            return;
          }
          const metadataError = previewTerminalError(metadata);
          if (metadataError) {
            delete panel.dataset[key];
            throw new Error(metadataError);
          }
        }
        if (token === review.preview.token) {
          setPreviewStatus('Exact preview is still pending; it continues on the server.');
        }
      } catch (error) {
        if (token !== review.preview.token) return;
        review.preview.loading = false;
        review.preview.error = error.message;
        setPreviewStatus('Exact preview unavailable: ' + error.message);
        if (empty) {
          empty.hidden = false;
          empty.textContent = 'The exact source frame is unavailable. No overlay is shown.';
        }
        renderOverlay();
      }
    }

    function applyPreview(metadata, token) {
      if (!metadata || token !== review.preview.token) return;
      if (metadata.frameOrdinal !== review.selectedOrdinal) return;
      review.preview.metadata = metadata;
      review.preview.loading = false;
      review.preview.error = '';
      const image = panel.querySelector('[data-preview-image]');
      const stage = panel.querySelector('[data-preview-stage]');
      const empty = panel.querySelector('[data-preview-empty]');
      if (!image) return;
      image.onload = () => {
        if (token !== review.preview.token || metadata.frameOrdinal !== review.selectedOrdinal) return;
        review.preview.imageOrdinal = metadata.frameOrdinal;
        image.hidden = false;
        if (empty) empty.hidden = true;
        if (stage) stage.dataset.ready = 'true';
        renderOverlay();
      };
      image.onerror = () => {
        if (token !== review.preview.token) return;
        image.hidden = true;
        if (stage) stage.dataset.ready = 'false';
        setPreviewStatus('Exact preview bytes could not be displayed.');
      };
      image.src = metadata.mediaUrl;
      const identity = panel.querySelector('[data-preview-identity]');
      if (identity) identity.textContent = previewIdentity(metadata);
      setPreviewStatus(
        metadata.checksum
          ? 'Exact preview ready · ' + String(metadata.checksum).slice(0, 18) + '…'
          : 'Exact preview ready.'
      );
      syncCorrectionDefaults();
      if (review.preview.point && panel.querySelector('[data-correction-kind]')?.value === 'CURSOR_VISIBILITY') {
        renderCorrectionFields();
      }
    }

    async function loadObservations() {
      if (!review.analysisId || review.reviewMode.blind || !review.policy?.predictionAccess) return;
      const sourceId = review.sourceId;
      const analysisId = review.analysisId;
      review.observations = [];
      try {
        let cursor = null;
        for (let page = 0; page < 12; page++) {
          const suffix = cursor ? '&cursor=' + encodeURIComponent(cursor) : '';
          const result = await request(
            base +
              '/sources/' +
              review.sourceId +
              '/analyses/' +
              review.analysisId +
              '/observations?limit=500' +
              suffix
          );
          if (review.sourceId !== sourceId || review.analysisId !== analysisId) return;
          if (!result.available) {
            review.observations = [];
            renderOverlay();
            return;
          }
          review.observations = review.observations.concat(result.items || []);
          cursor = result.nextCursor;
          if (!cursor || review.observations.length >= 4000) break;
        }
      } catch (ignored) {
        review.observations = [];
      }
      renderOverlay();
    }

    function observationRegion(observation) {
      const x = Number(observation.x);
      const y = Number(observation.y);
      const width = Number(observation.width);
      const height = Number(observation.height);
      return [x, y, width, height].every((value) => Number.isFinite(value))
        ? { x, y, width, height }
        : null;
    }

    function observationContainsFrame(observation, ordinal) {
      if (!observation) return false;
      if (Number(observation.frameOrdinal) === ordinal) return true;
      const evidence = observation.evidence || {};
      const start = Number(evidence.startFrameOrdinal);
      const end = Number(evidence.endFrameOrdinal);
      return Number.isFinite(start) && Number.isFinite(end) && ordinal >= start && ordinal <= end;
    }

    function svgNode(name, attributes) {
      const node = document.createElementNS('http://www.w3.org/2000/svg', name);
      for (const [key, value] of Object.entries(attributes || {})) {
        node.setAttribute(key, String(value));
      }
      return node;
    }

    function addPoint(svg, x, y, className) {
      svg.appendChild(svgNode('circle', { cx: x, cy: y, r: 7, class: className + '-point' }));
      svg.appendChild(
        svgNode('circle', { cx: x, cy: y, r: 15, class: className, 'fill-opacity': 0.18 })
      );
    }

    function addRegion(svg, region, className, label) {
      svg.appendChild(
        svgNode('rect', {
          x: region.x,
          y: region.y,
          width: Math.max(1, region.width),
          height: Math.max(1, region.height),
          class: className,
        })
      );
      if (label) {
        const text = svgNode('text', { x: region.x + 4, y: Math.max(14, region.y + 16) });
        text.textContent = label;
        svg.appendChild(text);
      }
    }

    // Browser exposure is conservative and monotonic, but cannot certify independent review.
    // Persist source-wide so changing analysis or reopening cannot erase prior viewing.
    const exposedSources = new Set();
    function suggestionExposure(mark) {
      if (!review.sourceId) return true;
      const key = 'mouse-video-exposed:' + review.sourceId;
      try {
        if (mark) {
          exposedSources.add(key);
          globalThis.localStorage.setItem(key, 'true');
        }
        return exposedSources.has(key) || globalThis.localStorage.getItem(key) !== null;
      } catch (ignored) {
        return true; // unavailable storage cannot establish an unexposed editor
      }
    }

    function renderOverlay() {
      const svg = panel.querySelector('[data-preview-overlay]');
      if (!svg) return;
      svg.replaceChildren();
      const metadata = review.preview.metadata;
      if (!metadata || review.preview.imageOrdinal !== review.selectedOrdinal) return;
      const width = Number(metadata.sourceWidth) || Number(metadata.outputWidth);
      const height = Number(metadata.sourceHeight) || Number(metadata.outputHeight);
      if (!(width > 0) || !(height > 0)) return;
      svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
      svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
      const mode = review.reviewMode;
      const showPredictions =
        panel.querySelector('[data-preview-predictions]')?.checked ?? true;
      // Blind review hides every suggestion layer, including the original prediction overlay.
      const suggestionsVisible = reviewCore
        ? reviewCore.showSuggestionLayer({
            reviewActive: mode.active,
            blind: mode.blind,
            predictions: showPredictions,
          })
        : showPredictions;
      if (suggestionsVisible || (mode.active && !mode.blind)) suggestionExposure(true);
      if (suggestionsVisible) {
        for (const observation of review.observations) {
          if (!observationContainsFrame(observation, review.selectedOrdinal)) continue;
          if (observation.kind === 'CURSOR_VISIBILITY' && observation.visibility === 'VISIBLE') {
            const x = Number(observation.x);
            const y = Number(observation.y);
            if (Number.isFinite(x) && Number.isFinite(y)) addPoint(svg, x, y, 'prediction');
          } else if (observation.kind === 'TARGET_REGION' || observation.kind === 'CAMERA_ACTIVITY') {
            const region = observationRegion(observation);
            if (region) addRegion(svg, region, 'prediction', observation.kind);
          }
        }
      }
      if (mode.active && !mode.blind && reviewCore) {
        const guess = reviewCore.guessForOrdinal(review.observations, review.selectedOrdinal);
        if (guess.visibility === 'VISIBLE') {
          svg.appendChild(
            svgNode('rect', {
              x: guess.x - guess.width / 2,
              y: guess.y - guess.height / 2,
              width: guess.width,
              height: guess.height,
              class: 'guess',
            })
          );
          const label = svgNode('text', {
            x: guess.x + guess.width / 2 + 6,
            y: Math.max(16, guess.y - guess.height / 2),
            class: 'guess-label',
          });
          label.textContent =
            'VISIBLE ' + (guess.confidence === null ? '?' : guess.confidence.toFixed(2));
          svg.appendChild(label);
        } else {
          const unsure = svgNode('text', { x: 8, y: 24, class: 'guess-label' });
          unsure.textContent = 'UNSURE';
          svg.appendChild(unsure);
        }
        const segments = reviewCore.buildTrail(
          review.observations,
          review.selectedOrdinal,
          review.frames
        );
        segments.forEach((segment, segmentIndex) => {
          if (segment.length > 1) {
            svg.appendChild(
              svgNode('polyline', {
                points: segment.map((point) => point.x + ',' + point.y).join(' '),
                class: 'trail',
                'stroke-opacity': (
                  0.3 +
                  0.6 * ((segmentIndex + 1) / segments.length)
                ).toFixed(2),
              })
            );
          }
          segment.forEach((point, pointIndex) => {
            svg.appendChild(
              svgNode('circle', {
                cx: point.x,
                cy: point.y,
                r: 3,
                class: 'trail-point',
                'fill-opacity': (0.3 + 0.7 * ((pointIndex + 1) / segment.length)).toFixed(2),
              })
            );
          });
        });
        const next = reviewCore.expectedNextPoint(segments, {
          targetOrdinal: review.selectedOrdinal,
          nextTime: Number(review.byOrdinal.get(Number(review.selectedOrdinal) + 1)?.ptsTime),
          width, height,
        });
        if (next) {
          svg.appendChild(
            svgNode('circle', { cx: next.x, cy: next.y, r: 5, class: 'expected-next' })
          );
        }
      }
      for (const entry of review.draft) {
        if (!observationContainsFrame(entry, review.selectedOrdinal)) continue;
        if (entry.kind === 'CURSOR_VISIBILITY' && entry.visibility === 'VISIBLE') {
          addPoint(svg, Number(entry.x), Number(entry.y), 'reviewed');
        } else if (entry.kind === 'TARGET_REGION' || entry.kind === 'CAMERA_ACTIVITY') {
          const region = observationRegion(entry);
          if (region) addRegion(svg, region, 'reviewed', entry.kind);
        }
      }
      if (review.preview.point) {
        addPoint(svg, review.preview.point.x, review.preview.point.y, 'pending');
      }
      if (review.preview.region) {
        addRegion(svg, review.preview.region, 'pending', 'selection');
      }
    }

    function imageCoordinates(event) {
      const image = panel.querySelector('[data-preview-image]');
      const metadata = review.preview.metadata;
      if (!image || !metadata) return null;
      const width = Number(metadata.sourceWidth);
      const height = Number(metadata.sourceHeight);
      if (!(width > 0) || !(height > 0)) return null;
      const rect = image.getBoundingClientRect();
      const scale = Math.min(rect.width / width, rect.height / height);
      const left = rect.left + (rect.width - width * scale) / 2;
      const top = rect.top + (rect.height - height * scale) / 2;
      const x = (event.clientX - left) / scale;
      const y = (event.clientY - top) / scale;
      if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x >= width || y >= height) {
        return null;
      }
      return { x: Math.round(x), y: Math.round(y) };
    }

    function setPreviewSelection() {
      const selection = panel.querySelector('[data-preview-selection]');
      if (!selection) return;
      if (review.preview.point) {
        selection.textContent = 'Selected source point: (' + review.preview.point.x + ', ' + review.preview.point.y + ').';
      } else if (review.preview.region) {
        const region = review.preview.region;
        selection.textContent =
          'Selected source region: (' +
          region.x +
          ', ' +
          region.y +
          ') ' +
          region.width +
          '×' +
          region.height +
          '.';
      } else {
        selection.textContent = 'Click the exact frame to select a source point, or drag for a region.';
      }
      syncCorrectionDefaults();
      renderOverlay();
    }

    // ------------------------------------------------------------ review mode

    function markReviewDraftChanged() {
      if (review.session && review.session.state !== 'OPEN') return;
      review.draftVersion += 1;
      review.dirty = true;
      if (review.reviewMode.active) {
        clearTimeout(review.autosaveTimer);
        const generation = review.generation;
        review.autosaveTimer = setTimeout(() => {
          if (generation === review.generation && review.reviewMode.active && (review.dirty || review.pendingSave)) saveReviewCumulative();
        }, 1500);
      }
    }

    function setDraftContext() {
      review.draftContext =
        review.sourceId && review.analysisId
          ? { sourceId: review.sourceId, analysisId: review.analysisId, sessionId: review.session?.id || null, frameIndexChecksum: review.artifactChecksum }
          : null;
    }

    function draftMatchesContext() {
      const context = review.draftContext;
      return Boolean(
        context &&
          context.sourceId === review.sourceId &&
          context.analysisId === review.analysisId &&
          context.sessionId === (review.session?.id || null) &&
          context.frameIndexChecksum === review.artifactChecksum
      );
    }

    /** Blind review disables the suggestion toggle and suppresses every prediction layer. */
    function syncBlindUi() {
      const mode = review.reviewMode;
      const blindActive = mode.blind;
      const predictions = panel.querySelector('[data-preview-predictions]');
      if (!predictions) return;
      if (blindActive && !predictions.disabled) {
        mode.predictionsWasChecked = predictions.checked;
        predictions.checked = false;
        predictions.disabled = true;
      } else if (!blindActive && predictions.disabled) {
        predictions.disabled = false;
        if (mode.predictionsWasChecked) predictions.checked = true;
        mode.predictionsWasChecked = false;
      }
    }

    function resetReviewModeUi() {
      const mode = review.reviewMode;
      mode.active = false;
      mode.blind = false;
      mode.forcedBlind = false;
      mode.queue = [];
      mode.index = 0;
      mode.seed = 0;
      mode.labelsSinceSave = 0;
      mode.undo = [];
      mode.saving = false;
      const section = panel.querySelector('[data-video-review]');
      if (section) section.dataset.reviewMode = 'false';
      const toggle = panel.querySelector('[data-review-mode-toggle]');
      if (toggle) toggle.checked = false;
      const blind = panel.querySelector('[data-review-blind-toggle]');
      if (blind) {
        blind.checked = false;
        blind.disabled = false;
      }
      syncBlindUi();
      setReviewModeStatus('');
      setReviewModeCounts();
      updateQueueControls();
    }

    function updateQueueControls() {
      const mode = review.reviewMode;
      const enabled = mode.active && mode.queue.length > 0 && (!review.session || review.session.state === 'OPEN');
      for (const selector of ['[data-review-range-start]', '[data-review-range-end]']) { const field = panel.querySelector(selector); if (field) field.disabled = mode.active; }
      const prev = panel.querySelector('[data-review-prev]');
      const next = panel.querySelector('[data-review-next]');
      const undo = panel.querySelector('[data-review-undo]');
      const help = panel.querySelector('[data-review-mode-help]');
      if (prev) prev.disabled = !enabled || mode.index <= 0;
      if (next) next.disabled = !enabled || mode.index >= mode.queue.length - 1;
      if (undo) undo.disabled = !mode.active || !mode.undo.length;
      for (const selector of ['[data-review-hidden]', '[data-review-ambiguous]', '[data-review-skip]']) {
        const button = panel.querySelector(selector);
        if (button) button.disabled = !enabled;
      }
      const accept = panel.querySelector('[data-review-accept]');
      if (accept) {
        const item = enabled ? mode.queue[mode.index] : null;
        const guess =
          item && reviewCore ? reviewCore.guessForOrdinal(review.observations, item.ordinal) : null;
        accept.disabled = !enabled || mode.blind || !guess || guess.visibility !== 'VISIBLE';
      }
      if (help) help.hidden = !mode.active;
    }

    function setReviewModeStatus(message) {
      const status = panel.querySelector('[data-review-mode-status]');
      if (!status) return;
      const text = reviewCore ? reviewCore.reviewStatusText(message, review.session?.state) : message || '';
      status.hidden = !text;
      status.textContent = text;
    }

    function setReviewModeCounts() {
      const counts = panel.querySelector('[data-review-mode-counts]');
      if (!counts) return;
      const mode = review.reviewMode;
      if (!mode.active || !reviewCore) {
        counts.hidden = true;
        return;
      }
      const summary = reviewCore.countLabels(review.draft);
      counts.hidden = false;
      counts.textContent =
        'labelled ' + summary.labelled + ' · accepted ' + summary.accepted +
        ' · corrected ' + summary.corrected + ' · no cursor ' + summary.noCursor;
    }

    function renderReviewModeStatus() {
      setReviewModeCounts();
      updateQueueControls();
      const mode = review.reviewMode;
      if (!mode.active || !mode.queue.length) return;
      const item = mode.queue[mode.index];
      const frame = review.byOrdinal.get(item.ordinal);
      const time =
        frame && frame.ptsTime !== null && frame.ptsTime !== undefined
          ? Number(frame.ptsTime).toFixed(2)
          : '?';
      setReviewModeStatus(
        'frame ' + item.ordinal + ' · t=' + time + ' s · item ' + (mode.index + 1) +
          '/' + mode.queue.length + ' · reason: ' + item.reason +
          (mode.blind ? ' · blind' : '')
      );
    }

    /** Pages the frozen index through the last observed sample before building the queue. */
    async function loadReviewPolicy() {
      const sourceId = review.sourceId, analysisId = review.analysisId;
      const policy = await request(base + '/sources/' + sourceId + '/review-policy?analysisId=' + encodeURIComponent(analysisId));
      if (review.sourceId !== sourceId || review.analysisId !== analysisId) throw new Error('Review policy context changed');
      if (policy.contract !== 'review-policy/v1') throw new Error('Review policy could not be verified');
      review.policy = policy;
      const start = panel.querySelector('[data-review-range-start]');
      const end = panel.querySelector('[data-review-range-end]');
      if (start) start.max = Math.max(0, policy.frameCount - 1);
      if (end) { end.max = Math.max(0, policy.frameCount - 1); end.value = Math.min(Number(start?.value) + 1999, policy.frameCount - 1); }
    }

    async function ensureReviewFramesLoaded() {
      const generation = review.generation, sourceId = review.sourceId, analysisId = review.analysisId;
      const complete = await reviewCore.completeIndex(cursor => request(base + '/sources/' + sourceId + '/analyses/' + analysisId + '/frame-index?limit=500' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '')), {
        cancelled: () => generation !== review.generation || sourceId !== review.sourceId || analysisId !== review.analysisId,
      });
      if (review.policy && review.policy.frameIndexChecksum !== complete.checksum) throw new Error('Policy and frame index do not match');
      review.frames = complete.frames;
      review.byOrdinal = new Map(complete.frames.map(frame => [frame.ordinal, frame]));
      review.artifactChecksum = complete.checksum;
      review.frameCount = complete.frameCount;
      review.nextCursor = null;
      renderFrames();
      renderFrameDetail();
    }

    async function openServerSession() {
      if (!review.policy?.enrolled) return;
      if (!panel.querySelector('[data-review-attestation]')?.checked) throw new Error('Confirm the annotation protocol before opening a server review session.');
      const mode = review.reviewMode.blind ? 'WITHOUT_SUGGESTIONS' : 'DETECTOR_ASSISTED';
      if (mode === 'WITHOUT_SUGGESTIONS' && suggestionExposure(false)) throw new Error('This browser records prior suggestion exposure or unavailable storage; independent review is unavailable.');
      const start = Number(panel.querySelector('[data-review-range-start]')?.value || 0);
      const end = Number(panel.querySelector('[data-review-range-end]')?.value);
      const existing = (review.policy.sessions || []).find(item => item.mode === mode && item.startOrdinal === start && item.endOrdinal === end && item.state !== 'ABANDONED');
      const previousSessionId = review.session?.id;
      if (existing) review.session = await request(base + '/review-sessions/' + existing.id);
      else {
        if (!review.pendingSession) review.pendingSession = JSON.stringify({ requestId: newRequestId(), analysisId: review.analysisId, frameIndexChecksum: review.artifactChecksum, mode, policyDigest: review.policy.policyDigest, startOrdinal: start, endOrdinal: end, annotationAttestation: true });
        const pending = JSON.parse(review.pendingSession);
        if (pending.analysisId !== review.analysisId || pending.mode !== mode || pending.startOrdinal !== start || pending.endOrdinal !== end) throw new Error('Resolve the previous pending session creation before changing its context.');
        review.session = await request(base + '/sources/' + review.sourceId + '/review-sessions', { method: 'POST', headers: await writeHeaders({ 'Content-Type': 'application/json' }), body: review.pendingSession });
        review.pendingSession = null;
      }
      if (review.session.id !== previousSessionId && !review.dirty) { review.draft = []; review.reviewMode.undo = []; }
      review.generation += 1;
      setDraftContext();
      const seal = panel.querySelector('[data-review-seal]');
      if (seal) seal.hidden = review.session.state !== 'OPEN';
      const abandon = panel.querySelector('[data-review-abandon]'); if (abandon) abandon.hidden = review.session.state !== 'OPEN';
      setReviewStatus('Session ' + review.session.mode + ' · ' + review.session.state + ' · frames ' + start + '–' + end);
    }

    function reviewFrameWidth() {
      const metadata = review.preview.metadata;
      const width = metadata ? Number(metadata.sourceWidth) : NaN;
      return Number.isFinite(width) && width > 0 ? width : 1920;
    }

    function rebuildReviewQueue() {
      const mode = review.reviewMode;
      if (!reviewCore) return;
      if (review.session && mode.blind) {
        mode.queue = reviewCore.denseQueue(review.frames, review.session.startOrdinal, review.session.endOrdinal);
        return;
      }
      mode.queue = reviewCore.buildReviewQueue({
        frames: review.frames,
        observations: review.observations,
        frameWidth: reviewFrameWidth(),
        seed: mode.seed,
        blind: mode.blind,
      });
      if (review.session) mode.queue = mode.queue.filter(item => item.ordinal >= review.session.startOrdinal && item.ordinal <= review.session.endOrdinal);
      mode.index = Math.min(mode.index, Math.max(0, mode.queue.length - 1));
    }

    function selectQueueItem(index) {
      const mode = review.reviewMode;
      if (!mode.active || !mode.queue.length) return;
      mode.index = Math.max(0, Math.min(index, mode.queue.length - 1));
      selectFrame(mode.queue[mode.index].ordinal);
      renderReviewModeStatus();
    }

    function applyReviewLabel(action, point) {
      const mode = review.reviewMode;
      if (!mode.active || !reviewCore || (review.session && review.session.state !== 'OPEN')) return false;
      const item = mode.queue[mode.index];
      if (!item) return false;
      if (!draftMatchesContext()) {
        setReviewModeStatus(
          'This draft belongs to another source or analysis; reselect the analysis before labelling.'
        );
        return false;
      }
      const guess = reviewCore.guessForOrdinal(review.observations, item.ordinal);
      let values;
      try {
        values = reviewCore.labelValues(action, {
          guess: guess,
          point: point,
          queueReason: item.reason,
          blind: mode.blind,
          exposed: suggestionExposure(false),
        });
      } catch (error) {
        setReviewModeStatus(error.message);
        return false;
      }
      const existing = review.draft.some(
        (entry) =>
          entry &&
          entry.kind === 'CURSOR_VISIBILITY' &&
          Number(entry.frameOrdinal) === Number(item.ordinal)
      );
      if (review.draft.length >= MAX_CORRECTIONS && !existing) {
        setReviewModeStatus(
          'This review holds the maximum ' + MAX_CORRECTIONS + ' corrections.'
        );
        return false;
      }
      let entry;
      try {
        entry = buildCorrection(
          'CURSOR_VISIBILITY',
          {
            frameOrdinal: item.ordinal,
            visibility: values.visibility,
            x: values.x,
            y: values.y,
            unknownReason: values.unknownReason,
            confidence: values.confidence,
            note: values.note,
            provenance: values.provenance,
          },
          item.ordinal
        );
      } catch (error) {
        setReviewModeStatus(error.message);
        return false;
      }
      entry.labelOrigin = ({ accept: 'ACCEPTED_PREDICTION', click: 'MANUAL_VISIBLE', hidden: 'MANUAL_HIDDEN', ambiguous: 'MANUAL_AMBIGUOUS' })[action];
      mode.undo = reviewCore.rememberDraft(mode.undo, review.draft);
      review.draft = reviewCore.replaceCursorLabel(review.draft, entry);
      markReviewDraftChanged();
      mode.labelsSinceSave += 1;
      renderDraft();
      updateQueueControls();
      setReviewModeCounts();
      if (reviewCore.autosaveDue(mode.labelsSinceSave)) void saveReviewCumulative();
      selectQueueItem(mode.index + 1);
      return true;
    }

    function skipQueueItem() {
      const mode = review.reviewMode;
      if (!mode.active) return;
      // The status strip keeps describing the newly selected item; skipping changes nothing else.
      selectQueueItem(mode.index + 1);
    }

    function undoReviewLabel() {
      if (review.session && review.session.state !== 'OPEN') return;
      const mode = review.reviewMode;
      if (!reviewCore) return;
      const undone = reviewCore.undoDraft(mode.undo);
      if (!undone) {
        setReviewModeStatus('Nothing to undo in this review session.');
        return;
      }
      mode.undo = undone.history;
      review.draft = undone.draft;
      markReviewDraftChanged();
      mode.labelsSinceSave = Math.max(0, mode.labelsSinceSave - 1);
      renderDraft();
      setReviewModeCounts();
      updateQueueControls();
      setReviewModeStatus('Undid the last label.');
    }

    async function enterReviewMode() {
      const toggle = panel.querySelector('[data-review-mode-toggle]');
      if (!reviewCore) {
        setReviewStatus('Review mode is unavailable: its script did not load.');
        if (toggle) toggle.checked = false;
        return;
      }
      if (!review.analysisId) {
        setReviewStatus('Review mode needs a completed analysis.');
        if (toggle) toggle.checked = false;
        return;
      }
      const mode = review.reviewMode;
      // Blind is decided before any observation loads or any overlay renders, so the first
      // held-out frame cannot expose a detector prediction.
      mode.forcedBlind = Boolean(review.policy?.forcedBlind || reviewCore.isHeldoutContext(review.source && review.source.context));
      const blind = panel.querySelector('[data-review-blind-toggle]');
      if (blind) {
        blind.disabled = mode.forcedBlind;
        if (mode.forcedBlind) blind.checked = true;
        mode.blind = mode.forcedBlind ? true : Boolean(blind.checked);
      } else {
        mode.blind = mode.forcedBlind;
      }
      mode.active = true;
      syncBlindUi();
      mode.seed = reviewCore.seedFromString(review.sourceId + ':' + review.analysisId);
      if (review.draft.length && !review.draftContext) setDraftContext();
      if (review.draftContext && !draftMatchesContext()) {
        mode.active = false;
        if (toggle) toggle.checked = false;
        syncBlindUi();
        setReviewStatus(
          'This draft belongs to a different analysis; reselect that analysis before reviewing.'
        );
        return;
      }
      await ensureReviewFramesLoaded();
      await openServerSession();
      await loadHistory();
      if (!review.observations.length) await loadObservations();
      if (!review.dirty && !mode.undo.length) {
        const sessionHistory = review.session ? review.history.map(item => ({ ...item, corrections: (item.corrections || []).map(entry => ({ ...entry, provenance: mode.blind ? 'BLIND_UNVERIFIED' : 'DETECTOR_ASSISTED', labelOrigin: item.provenance?.origins?.[String(entry.frameOrdinal)] })) })) : review.history;
        review.draft = reviewCore.seedDraft(sessionHistory, {
          analysisId: review.analysisId,
          blindOnly: mode.blind,
        });
        review.draftVersion = Math.max(review.draftVersion, review.history.at(-1)?.acknowledgedClientEditVersion || 0) + 1;
        setDraftContext();
        renderDraft();
      }
      const reason = panel.querySelector('[data-review-reason]');
      if (reason) reason.value = reviewCore.reviewReason({ blind: mode.blind, seed: mode.seed, exposed: suggestionExposure(false) });
      const use = panel.querySelector('[data-source-use]');
      if (use) use.value = 'INSPECTION_ONLY';
      const section = panel.querySelector('[data-video-review]');
      if (section) section.dataset.reviewMode = 'true';
      setReviewModeStatus('Loading the frozen frame index for review…');
      rebuildReviewQueue();
      mode.index = 0;
      mode.labelsSinceSave = 0;
      mode.undo = [];
      if (!mode.queue.length) {
        setReviewModeStatus('No reviewable frames: this analysis has no cursor observations.');
        updateQueueControls();
        return;
      }
      selectQueueItem(0);
    }

    async function exitReviewMode() {
      const mode = review.reviewMode;
      if (!mode.active) return true;
      const flushed = await flushReviewSaves();
      if (!flushed) {
        setReviewModeStatus(
          'Unsaved labels could not be persisted; review mode stays open so nothing is lost.'
        );
        return false;
      }
      mode.active = false;
      mode.blind = mode.forcedBlind;
      const section = panel.querySelector('[data-video-review]');
      if (section) section.dataset.reviewMode = 'false';
      const toggle = panel.querySelector('[data-review-mode-toggle]');
      if (toggle) toggle.checked = false;
      const blind = panel.querySelector('[data-review-blind-toggle]');
      if (blind) blind.disabled = false;
      syncBlindUi();
      setReviewModeStatus('');
      setReviewModeCounts();
      updateQueueControls();
      renderOverlay();
      return true;
    }

    function reviewSaveReason() {
      const mode = review.reviewMode;
      return reviewCore
        ? reviewCore.reviewReason({ blind: mode.blind, seed: mode.seed, exposed: suggestionExposure(false) })
        : 'FA12 dev review';
    }

    /**
     * Sends one cumulative snapshot. Only the exact snapshot is acknowledged: a label added while
     * the request is in flight keeps the draft dirty and asks the queue to send again.
     */
    async function sendReviewSnapshot() {
      const mode = review.reviewMode;
      if (!review.sourceId) return true;
      if (review.session?.state === 'SEALED') { setReviewModeStatus('This session is sealed and read-only.'); return !review.dirty; }
      if (review.reviewMode.active && !draftMatchesContext()) {
        setReviewModeStatus(
          'The draft does not belong to the selected analysis; reload it before saving.'
        );
        return false;
      }
      if (review.draft.length > MAX_CORRECTIONS) {
        setReviewModeStatus(
          'This review exceeds ' + MAX_CORRECTIONS + ' corrections and cannot be saved.'
        );
        return false;
      }
      const snapshotVersion = review.pendingSave ? review.pendingSave.version : review.draftVersion;
      const snapshotDraft = review.pendingSave ? review.pendingSave.draft : review.draft.map(entry => { const copy = { ...entry }; if (!review.session) delete copy.labelOrigin; return copy; });
      const reason = review.pendingSave ? review.pendingSave.reason : mode.active
        ? reviewSaveReason() : panel.querySelector('[data-review-reason]')?.value.trim();
      if (!reason) {
        setReviewStatus('A review reason is required before saving.');
        return false;
      }
      if (reviewCore && /blind-unverified/i.test(reason)) {
        const provenance = reviewCore.draftProvenance(snapshotDraft);
        if (provenance !== 'BLIND_UNVERIFIED' && provenance !== 'EMPTY') {
          setReviewModeStatus(
            'Unverified blind saves must hold blind labels only; this draft is ' +
              provenance.toLowerCase() +
              '. Undo detector-assisted labels or switch Blind off.'
          );
          return false;
        }
      }
      mode.saving = true;
      // Capture the wire bytes and context before nonce acquisition or any other await.
      // An uncertain outcome MUST retry these bytes before later edits get another UUID.
      if (!review.pendingSave) {
        const payload = {
          requestId: newRequestId(),
          analysisId: review.analysisId,
          expectedBaseRevision: review.baseRevision,
          reason: reason,
          sourceUse: mode.active ? 'INSPECTION_ONLY' :
            panel.querySelector('[data-source-use]')?.value || 'INSPECTION_ONLY',
          corrections: snapshotDraft,
        };
        let snapshotJson = null;
        if (review.session) {
          payload.clientEditVersion = snapshotVersion;
          payload.origins = Object.fromEntries(snapshotDraft.map(entry => [String(entry.frameOrdinal), entry.labelOrigin]));
          payload.corrections = snapshotDraft.map(entry => { const copy = { ...entry }; delete copy.provenance; delete copy.labelOrigin; return copy; });
          snapshotJson = JSON.stringify(payload);
        }
        const body = JSON.stringify(snapshotJson === null ? payload : { snapshotJson });
        review.pendingSave = Object.freeze({
          url: review.session ? base + '/review-sessions/' + review.session.id + '/reviews' : base + '/sources/' + review.sourceId + '/reviews',
          sessionId: review.session?.id || null,
          generation: review.generation,
          snapshotJson,
          sourceId: review.sourceId,
          analysisId: review.analysisId,
          baseRevision: review.baseRevision,
          version: snapshotVersion,
          draft: JSON.parse(JSON.stringify(snapshotDraft)),
          reason: reason,
          body: body,
        });
      }
      const pending = review.pendingSave;
      const baseRevision = pending.baseRevision;
      if (pending.sourceId !== review.sourceId || pending.analysisId !== review.analysisId) {
        mode.saving = false;
        setReviewModeStatus('A pending save belongs to another analysis; restore it before saving.');
        return false;
      }
      try {
        const created = await request(pending.url, {
          method: 'POST',
          headers: await writeHeaders({ 'Content-Type': 'application/json' }),
          body: pending.body,
        });
        if (review.session) {
          const hash = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(pending.snapshotJson));
          const expectedDigest = 'sha256:' + Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('');
          if (created.sessionId !== pending.sessionId || created.sourceId !== pending.sourceId || created.analysisId !== pending.analysisId || created.acknowledgedClientEditVersion !== pending.version || created.snapshotDigest !== expectedDigest || pending.generation !== review.generation) throw new Error('Review acknowledgement does not match the submitted context and content');
          review.lastAcknowledgement = created;
        }
        review.pendingSave = null;
        review.conflict = false;
        // One created revision advances the source by exactly one. Do not adopt a newer server
        // revision here: that would hide a concurrent writer instead of reporting the 409.
        review.baseRevision = review.session ? created.appliedSourceRevision : baseRevision + 1;
        const acknowledgement = reviewCore
          ? reviewCore.acknowledgeSave({
              submittedVersion: snapshotVersion,
              currentVersion: review.draftVersion,
            })
          : { dirty: snapshotVersion !== review.draftVersion, resave: snapshotVersion !== review.draftVersion };
        review.dirty = acknowledgement.dirty;
        if (!acknowledgement.dirty) mode.labelsSinceSave = 0;
        renderConflict();
        await loadHistory();
        setReviewModeStatus(
          'Saved revision ' + created.id.slice(0, 8) + ' against base v' +
            created.expectedBaseRevision + ' · ' + snapshotDraft.length + ' labels' +
            (acknowledgement.resave ? ' · newer labels queued' : '')
        );
        return acknowledgement.resave ? 'again' : true;
      } catch (error) {
        // Definitive client rejection did not commit; allow correction of the payload.
        // Transport failures, timeouts and server errors retain the immutable pending write.
        if (error.status >= 400 && error.status < 500 &&
            ![408, 409, 429].includes(error.status)) review.pendingSave = null;
        if (error.status === 409) {
          review.conflict = true;
          renderConflict();
          setReviewModeStatus(
            'Conflict: ' + error.message + ' Labels are kept; reload the base revision.'
          );
        } else {
          setReviewModeStatus('Autosave failed: ' + error.message);
        }
        return false;
      } finally {
        mode.saving = false;
      }
    }

    /** Serializes cumulative saves so a slow response can never clear a newer draft. */
    function saveReviewCumulative() {
      const mode = review.reviewMode;
      if (!review.sourceId) return Promise.resolve(false);
      if (!mode.saveQueue && reviewCore) {
        mode.saveQueue = reviewCore.createSaveQueue(sendReviewSnapshot);
      }
      return mode.saveQueue ? mode.saveQueue.kick() : Promise.resolve(false);
    }

    /** Waits until the latest draft is acknowledged; a failed save leaves review mode open. */
    async function flushReviewSaves() {
      const mode = review.reviewMode;
      const queue = mode.saveQueue;
      if (!queue) return true;
      if (!review.dirty && !review.pendingSave && mode.labelsSinceSave <= 0 && !queue.isBusy()) return true;
      let saved = await queue.kick();
      let attempts = 0;
      while (saved && (review.dirty || mode.labelsSinceSave > 0) && attempts < 10) {
        attempts += 1;
        saved = await queue.kick();
      }
      return saved && !review.dirty && mode.labelsSinceSave <= 0;
    }

    function renderCorrectionFields() {
      const container = panel.querySelector('[data-correction-fields]');
      const kindSelect = panel.querySelector('[data-correction-kind]');
      if (!container || !kindSelect) return;
      container.replaceChildren();
      const kind = kindSelect.value;
      const ordinal = review.selectedOrdinal === null ? 0 : review.selectedOrdinal;
      const previewReady =
        review.preview.metadata &&
        review.preview.imageOrdinal === review.selectedOrdinal &&
        !review.preview.loading;
      const add = (labelText, node) => {
        const label = document.createElement('label');
        label.textContent = labelText;
        label.appendChild(node);
        container.appendChild(label);
        return node;
      };
      const select = (values) => {
        const node = document.createElement('select');
        for (const value of values) {
          const option = document.createElement('option');
          option.value = value;
          option.textContent = value;
          node.appendChild(option);
        }
        return node;
      };
      const number = (value, name, min, max) => {
        const node = document.createElement('input');
        node.type = 'number';
        node.min = String(min == null ? 0 : min);
        if (max != null) node.max = String(max);
        node.step = '1';
        node.value = String(Number.isFinite(Number(value)) ? Number(value) : 0);
        if (name) node.dataset.field = name;
        return node;
      };
      const regionFields = () => {
        const region = review.preview.region || { x: 0, y: 0, width: 1, height: 1 };
        add('X', number(region.x, 'x')).setAttribute('aria-label', 'Region X');
        add('Y', number(region.y, 'y')).setAttribute('aria-label', 'Region Y');
        add('Width', number(region.width, 'width', 1)).setAttribute('aria-label', 'Region width');
        add('Height', number(region.height, 'height', 1)).setAttribute('aria-label', 'Region height');
      };
      if (kind === 'CURSOR_VISIBILITY') {
        const options = previewReady
          ? ['VISIBLE', 'HIDDEN', 'AMBIGUOUS', 'UNSUPPORTED']
          : ['HIDDEN', 'AMBIGUOUS', 'UNSUPPORTED'];
        const visibility = select(options);
        visibility.dataset.field = 'visibility';
        visibility.value = review.preview.point && previewReady ? 'VISIBLE' : 'HIDDEN';
        add('Visibility', visibility);
        const pointX = number(review.preview.point ? review.preview.point.x : 0, 'x');
        const pointY = number(review.preview.point ? review.preview.point.y : 0, 'y');
        const xLabel = add('Cursor X (source px)', pointX);
        const yLabel = add('Cursor Y (source px)', pointY);
        xLabel.dataset.cursorOnly = 'true';
        yLabel.dataset.cursorOnly = 'true';
        const reason = document.createElement('input');
        reason.type = 'text';
        reason.maxLength = 280;
        reason.dataset.field = 'unknownReason';
        reason.placeholder = 'Why the cursor cannot be recorded';
        add('Reason for non-visible state', reason);
        xLabel.closest('label').hidden = yLabel.closest('label').hidden = !previewReady;
        visibility.addEventListener('change', () => {
          const hidden = visibility.value !== 'VISIBLE';
          xLabel.closest('label').hidden = yLabel.closest('label').hidden = hidden;
        });
      } else if (kind === 'TARGET_REGION') {
        regionFields();
        const label = document.createElement('input');
        label.type = 'text';
        label.maxLength = 80;
        label.dataset.field = 'label';
        label.placeholder = 'Visible region label';
        add('Label', label);
      } else if (kind === 'CAMERA_ACTIVITY') {
        const activity = select(['VIEWPORT_MOTION', 'REGION_CHANGE', 'NONE']);
        activity.dataset.field = 'activity';
        add('Activity', activity);
        regionFields();
      } else if (kind === 'PAUSE') {
        add('Start frame', number(ordinal, 'startFrameOrdinal')).setAttribute('aria-label', 'Pause start frame');
        add('End frame', number(ordinal, 'endFrameOrdinal')).setAttribute('aria-label', 'Pause end frame');
      } else if (kind === 'SCENE_CUT') {
        add('Frame before cut', number(ordinal, 'beforeFrameOrdinal')).setAttribute(
          'aria-label',
          'Frame before cut'
        );
        add('Frame after cut', number(ordinal + 1, 'afterFrameOrdinal')).setAttribute(
          'aria-label',
          'Frame after cut'
        );
      } else if (kind === 'ACTION_INDICATOR') {
        const indicator = select([
          'MENU_OPEN',
          'MENU_CLOSE',
          'HIGHLIGHT_ON',
          'HIGHLIGHT_OFF',
          'CURSOR_STATE_CHANGE',
        ]);
        indicator.dataset.field = 'indicator';
        add('Indicator', indicator);
      }
      const confidence = document.createElement('input');
      confidence.type = 'number';
      confidence.min = '0';
      confidence.max = '1';
      confidence.step = '0.01';
      confidence.value = '1';
      confidence.dataset.field = 'confidence';
      add('Confidence (0–1)', confidence);
      const note = document.createElement('input');
      note.type = 'text';
      note.maxLength = 280;
      note.dataset.field = 'note';
      add('Note (optional)', note);
    }

    function syncCorrectionDefaults() {
      const container = panel.querySelector('[data-correction-fields]');
      if (!container) return;
      const ordinal = review.selectedOrdinal === null ? 0 : review.selectedOrdinal;
      for (const field of container.querySelectorAll('[data-field="startFrameOrdinal"], [data-field="endFrameOrdinal"]')) {
        field.value = String(ordinal);
      }
      for (const field of container.querySelectorAll('[data-field="beforeFrameOrdinal"]')) {
        field.value = String(ordinal);
      }
      for (const field of container.querySelectorAll('[data-field="afterFrameOrdinal"]')) {
        field.value = String(Math.min(ordinal + 1, Math.max(0, review.frameCount - 1)));
      }
      if (review.preview.point) {
        for (const name of ['x', 'y']) {
          const field = container.querySelector('[data-field="' + name + '"]');
          if (field && !field.closest('label').hidden) field.value = String(review.preview.point[name]);
        }
      }
      if (review.preview.region) {
        for (const name of ['x', 'y', 'width', 'height']) {
          const field = container.querySelector('[data-field="' + name + '"]');
          if (field) field.value = String(review.preview.region[name]);
        }
      }
    }

    function correctionValues() {
      const container = panel.querySelector('[data-correction-fields]');
      const values = {};
      if (!container) return values;
      for (const field of container.querySelectorAll('[data-field]')) {
        const name = field.dataset.field;
        if (field.type === 'number') {
          const parsed = Number(field.value);
          values[name] = Number.isFinite(parsed) ? parsed : undefined;
        } else {
          values[name] = field.value;
        }
      }
      return values;
    }

    function renderDraft() {
      const list = panel.querySelector('[data-review-draft]');
      const count = panel.querySelector('[data-review-draft-count]');
      if (count) count.textContent = String(review.draft.length);
      if (!list) return;
      list.innerHTML = '';
      review.draft.forEach((entry, index) => {
        const li = document.createElement('li');
        const text = document.createElement('span');
        text.textContent = JSON.stringify(entry);
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.textContent = 'Remove';
        remove.setAttribute('aria-label', 'Remove draft correction ' + (index + 1));
        remove.disabled = Boolean(review.session && review.session.state !== 'OPEN');
        remove.addEventListener('click', () => {
          if (review.session && review.session.state !== 'OPEN') return;
          review.draft = review.draft.filter((_, position) => position !== index);
          markReviewDraftChanged();
          renderDraft();
        });
        li.append(text, remove);
        list.appendChild(li);
      });
    }

    async function loadHistory(before) {
      if (!review.sourceId) return;
      const sourceId = review.sourceId, analysisId = review.analysisId, generation = review.generation, sessionId = review.session?.id;
      if (review.reviewMode.blind && !review.session) { review.history = []; renderHistory(); return; }
      const page = await request(review.session ? base + '/review-sessions/' + review.session.id + '/reviews' + (before ? '?before=' + encodeURIComponent(before) : '') : base + '/sources/' + review.sourceId + '/reviews');
      if (review.sourceId !== sourceId || review.analysisId !== analysisId || generation !== review.generation || sessionId !== review.session?.id) return;
      review.historyCursor = page.nextCursor || null;
      const older = panel.querySelector('[data-review-history-older]'); if (older) older.hidden = !review.historyCursor;
      review.history = page.items || [];
      if (review.session && !before) review.lastAcknowledgement = review.history.at(-1) || null;
      renderHistory();
    }

    function renderHistory() {
      const list = panel.querySelector('[data-review-history]');
      if (!list) return;
      list.innerHTML = '';
      if (!review.history.length) {
        const li = document.createElement('li');
        li.textContent = 'No review revisions yet.';
        list.appendChild(li);
        return;
      }
      for (const item of review.history.slice().reverse()) {
        const li = document.createElement('li');
        const label = document.createElement('span');
        const corrections = Array.isArray(item.corrections) ? item.corrections.length : 0;
        label.textContent =
          (item.contract || 'corrections/v0') +
          ' · ' +
          item.state +
          ' · ' +
          item.sourceUse +
          ' · base v' +
          item.expectedBaseRevision +
          ' · ' +
          corrections +
          ' correction(s)' +
          (item.supersededBy ? ' · superseded by ' + item.supersededBy.slice(0, 8) : '');
        const view = document.createElement('button');
        view.type = 'button';
        view.textContent = 'Details';
        view.setAttribute('aria-label', 'Show revision ' + item.id);
        view.addEventListener('click', () => {
          const detail = panel.querySelector('[data-review-history-detail]');
          if (detail) detail.textContent = JSON.stringify(item, null, 2);
        });
        li.append(label, view);
        list.appendChild(li);
      }
    }

    function renderConflict() {
      const banner = panel.querySelector('[data-review-conflict]');
      const reload = panel.querySelector('[data-review-reload]');
      if (reload) reload.hidden = !review.conflict;
      if (!banner) return;
      banner.hidden = !review.conflict;
      if (review.conflict) {
        banner.textContent =
          'The review advanced while this draft was open. Your ' +
          review.draft.length +
          ' unsaved correction(s) are still here. Reload the base revision, then save again with a ' +
          'new request identifier.';
      }
    }

    function clearConflict() {
      if (review.conflict) review.pendingSave = null;
      review.conflict = false;
      renderConflict();
      delete panel.dataset.pendingReviewRequestId;
    }

    async function saveReview() {
      const saved = await saveReviewCumulative();
      if (!review.reviewMode.active) {
        if (saved && !review.dirty) {
          review.draft = [];
          review.draftVersion += 1;
          renderDraft();
        }
        setReviewStatus(saved ? 'Saved review snapshot.' :
          'Review was not saved. Pending labels are retained; check the save status.');
      }
    }

    async function reloadBaseRevision() {
      try {
        const source = await request(base + '/sources/' + review.sourceId);
        review.baseRevision = source.reviewRevision || 0;
        review.source = source;
        clearConflict();
        await loadHistory();
        setReviewStatus(
          'Reloaded base revision v' +
            review.baseRevision +
            '. The draft was preserved; saving now uses a new request identifier.'
        );
      } catch (error) {
        setReviewStatus('Reload failed: ' + error.message);
      }
    }

    async function loadExports(sourceId) {
      const list = panel.querySelector('[data-video-exports]');
      if (!list) return;
      const page = await request(base + '/sources/' + sourceId + '/exports');
      if (inspectedSourceId !== sourceId) return;
      list.innerHTML = '';
      for (const item of page.items || []) {
        const li = document.createElement('li');
        li.textContent =
          'v' + item.reviewRevision + ' · ' + item.manifestChecksum.slice(0, 12) + '…';
        const link = document.createElement('a');
        link.href = base + '/exports/' + item.id;
        link.download = 'mouse-video-export-' + item.id.slice(0, 8) + '.json';
        link.textContent = 'Download';
        li.appendChild(link);
        list.appendChild(li);
      }
    }

    async function createExport(sourceId) {
      try {
        const requestId =
          (panel.dataset.pendingExportId) ||
          (panel.dataset.pendingExportId = newRequestId());
        const bundle = await request(base + '/sources/' + sourceId + '/exports', {
          method: 'POST',
          headers: await writeHeaders({ 'Content-Type': 'application/json' }),
          body: JSON.stringify({
            requestId: requestId,
            analysisId: review.analysisId,
            reviewRevision: review.baseRevision,
          }),
        });
        delete panel.dataset.pendingExportId;
        setStatus(bundle.created ? 'Export created.' : 'Existing export reused.');
        await loadExports(sourceId);
      } catch (error) {
        setStatus('Export failed: ' + error.message);
      }
    }

    if (form) {
      form.addEventListener('submit', async (event) => {
        event.preventDefault();
        const submit = form.querySelector('[data-video-submit]');
        if (submit.disabled) return;
        submit.disabled = true;
        const requestId =
          (panel.dataset.pendingRequestId) ||
          (panel.dataset.pendingRequestId = newRequestId());
        const meta = {
          declaredOrigin: panel.querySelector('[data-video-origin]').value,
          taskKey: panel.querySelector('[data-video-task]').value,
          skill: panel.querySelector('[data-video-skill]').value,
          context: panel.querySelector('[data-video-context]').value,
          place: panel.querySelector('[data-video-place]').value,
        };
        try {
          let receipt;
          if (mode && mode.value === 'youtube') {
            const url = panel.querySelector('[data-video-url]').value;
            receipt = await request(base + '/imports/youtube', {
              method: 'POST',
              headers: await writeHeaders({ 'Content-Type': 'application/json' }),
              body: JSON.stringify(
                Object.assign({ requestId: requestId, url: url }, meta)
              ),
            });
          } else {
            const fileInput = panel.querySelector('[data-video-file]');
            if (!fileInput.files.length) throw new Error('Choose a video file first.');
            const data = new FormData();
            data.append('file', fileInput.files[0]);
            data.append('metadata', new Blob([JSON.stringify(meta)], { type: 'application/json' }));
            receipt = await request(base + '/imports/upload', {
              method: 'POST',
              headers: await writeHeaders({ 'X-Mouse-Video-Request-Id': requestId }),
              body: data,
            });
          }
          delete panel.dataset.pendingRequestId;
          saveState({ lastSourceId: receipt.sourceId, lastJobId: receipt.jobId });
          setStatus('Import accepted. Refreshing…');
          await refreshCatalog();
        } catch (error) {
          setStatus('Import failed: ' + error.message);
        } finally {
          submit.disabled = false;
        }
      });
    }

    const refreshButton = panel.querySelector('[data-video-refresh]');
    if (refreshButton) refreshButton.addEventListener('click', refreshCatalog);

    const exportButton = panel.querySelector('[data-video-export]');
    if (exportButton)
      exportButton.addEventListener('click', () => {
        const state = loadState();
        if (state.lastSourceId) createExport(state.lastSourceId);
      });

    const kindSelect = panel.querySelector('[data-correction-kind]');
    if (kindSelect) {
      for (const kind of CORRECTION_KINDS) {
        const option = document.createElement('option');
        option.value = kind;
        option.textContent = kind.replace(/_/g, ' ').toLowerCase();
        kindSelect.appendChild(option);
      }
      kindSelect.addEventListener('change', renderCorrectionFields);
      renderCorrectionFields();
    }

    const addButton = panel.querySelector('[data-correction-add]');
    if (addButton)
      addButton.addEventListener('click', () => {
        if (review.session && review.session.state !== 'OPEN') return;
        if (review.selectedOrdinal === null) {
          setReviewStatus('Select a frame before adding a correction.');
          return;
        }
        try {
          const entry = buildCorrection(
            kindSelect.value,
            correctionValues(),
            review.selectedOrdinal
          );
          review.draft = addCorrection(review.draft, entry);
          markReviewDraftChanged();
          renderDraft();
          setReviewStatus('Added to draft: ' + entry.kind + '. Nothing is saved until you save.');
        } catch (error) {
          setReviewStatus(error.message);
        }
      });

    globalThis.addEventListener('beforeunload', event => {
      if (review.dirty || review.pendingSave || review.reviewMode.saving) { event.preventDefault(); event.returnValue = ''; }
    });
    panel.querySelector('[data-review-abandon]')?.addEventListener('click', async () => {
      try {
        if (!review.session || !(await flushReviewSaves())) throw new Error('Save the latest labels before abandoning qualification.');
        const acknowledgement = review.lastAcknowledgement || review.history.at(-1);
        const payload = JSON.stringify({ requestId: newRequestId(), latestRevisionId: acknowledgement?.id || null });
        await request(base + '/review-sessions/' + review.session.id + '/abandon', { method: 'POST', headers: await writeHeaders({ 'Content-Type': 'application/json' }), body: payload });
        review.session = null; review.lastAcknowledgement = null; review.pendingSession = null;
        review.draft = []; review.draftVersion += 1; review.dirty = false; review.generation += 1;
        review.reviewMode.undo = []; review.reviewMode.labelsSinceSave = 0;
        review.reviewMode.active = false;
        const toggle = panel.querySelector('[data-review-mode-toggle]'); if (toggle) toggle.checked = false;
        await loadReviewPolicy(); setDraftContext(); renderDraft(); updateQueueControls();
        setReviewModeStatus('Qualification session abandoned. Saved history remains available; its labels cannot be sealed.');
      } catch (error) { setReviewModeStatus('Abandon refused: ' + error.message); }
    });
    panel.querySelector('[data-review-seal]')?.addEventListener('click', async () => {
      try {
        if (!review.session || !(await flushReviewSaves())) throw new Error('Save the latest draft before sealing.');
        const acknowledgement = review.lastAcknowledgement || review.history.at(-1);
        if (!acknowledgement) throw new Error('No acknowledged review revision to seal.');
        const payload = JSON.stringify({ requestId: newRequestId(), revisionId: acknowledgement.id, snapshotDigest: acknowledgement.snapshotDigest });
        review.session = await request(base + '/review-sessions/' + review.session.id + '/seal', { method: 'POST', headers: await writeHeaders({ 'Content-Type': 'application/json' }), body: payload });
        const link = panel.querySelector('[data-review-session-export]');
        if (link) { link.hidden = false; link.href = base + '/review-sessions/' + review.session.id + '/export'; }
        setReviewModeStatus('Review session sealed. Labels are immutable and ready to export.');
        renderDraft(); updateQueueControls();
      } catch (error) { setReviewModeStatus('Seal refused: ' + error.message); }
    });
    const saveButton = panel.querySelector('[data-review-save]');
    for (const selector of ['[data-review-reason]', '[data-source-use]', '[data-correction-fields]']) {
      const field = panel.querySelector(selector);
      field?.addEventListener('input', () => { markReviewDraftChanged(); });
      field?.addEventListener('change', () => { markReviewDraftChanged(); });
    }
    if (saveButton) saveButton.addEventListener('click', saveReview);

    panel.querySelector('[data-review-history-older]')?.addEventListener('click', () => {
      if (review.historyCursor) loadHistory(review.historyCursor).catch(error => setReviewStatus('History could not be loaded: ' + error.message));
    });
    const reloadButton = panel.querySelector('[data-review-reload]');
    if (reloadButton) reloadButton.addEventListener('click', reloadBaseRevision);

    const reviewModeToggle = panel.querySelector('[data-review-mode-toggle]');
    if (reviewModeToggle)
      reviewModeToggle.addEventListener('change', async () => {
        try {
          if (reviewModeToggle.checked) await enterReviewMode();
          else {
            const closed = await exitReviewMode();
            if (!closed) reviewModeToggle.checked = true;
          }
        } catch (error) {
          review.reviewMode.active = false;
          reviewModeToggle.checked = false;
          const section = panel.querySelector('[data-video-review]');
          if (section) section.dataset.reviewMode = 'false';
          setReviewStatus('Review mode failed: ' + error.message);
          updateQueueControls();
        }
        reviewModeToggle.blur();
      });

    const reviewBlindToggle = panel.querySelector('[data-review-blind-toggle]');
    if (reviewBlindToggle)
      reviewBlindToggle.addEventListener('change', () => {
        const mode = review.reviewMode;
        if (review.session) {
          reviewBlindToggle.checked = review.session.mode === 'WITHOUT_SUGGESTIONS';
          setReviewModeStatus('Session mode is immutable. Finish this session before opening another mode.');
          return;
        }
        if (mode.forcedBlind) {
          reviewBlindToggle.checked = true;
          return;
        }
        mode.blind = reviewBlindToggle.checked;
        reviewBlindToggle.blur();
        syncBlindUi();
        if (!mode.active) return;
        const reason = panel.querySelector('[data-review-reason]');
        if (reason && reviewCore) {
          reason.value = reviewCore.reviewReason({ blind: mode.blind, seed: mode.seed, exposed: suggestionExposure(false) });
        }
        rebuildReviewQueue();
        mode.index = 0;
        renderOverlay();
        if (mode.queue.length) selectQueueItem(0);
        else renderReviewModeStatus();
      });

    for (const [selector, delta] of [
      ['[data-review-prev]', -1],
      ['[data-review-next]', 1],
    ]) {
      const button = panel.querySelector(selector);
      if (button)
        button.addEventListener('click', () => {
          selectQueueItem(review.reviewMode.index + delta);
          button.blur();
        });
    }

    const reviewUndo = panel.querySelector('[data-review-undo]');
    if (reviewUndo)
      reviewUndo.addEventListener('click', () => {
        undoReviewLabel();
        reviewUndo.blur();
      });

    for (const [selector, action] of [
      ['[data-review-accept]', 'accept'],
      ['[data-review-hidden]', 'hidden'],
      ['[data-review-ambiguous]', 'ambiguous'],
    ]) {
      const button = panel.querySelector(selector);
      if (button)
        button.addEventListener('click', () => {
          applyReviewLabel(action, null);
          button.blur();
        });
    }
    const reviewSkip = panel.querySelector('[data-review-skip]');
    if (reviewSkip)
      reviewSkip.addEventListener('click', () => {
        skipQueueItem();
        reviewSkip.blur();
      });

    // Keyboard-first review: keys act while review mode is on, except inside form fields.
    document.addEventListener('keydown', (event) => {
      const mode = review.reviewMode;
      if (!mode.active || event.defaultPrevented) return;
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      const target = event.target;
      if (target && /^(INPUT|SELECT|TEXTAREA)$/.test(target.tagName)) return;
      if (target && target.tagName === 'BUTTON' && (event.key === 'Enter' || event.key === ' ')) {
        return;
      }
      if (event.key === 'Enter') {
        event.preventDefault();
        if (mode.blind) {
          setReviewModeStatus('Blind mode has no detector guess; click the cursor or press H.');
          return;
        }
        const item = mode.queue[mode.index];
        const guess =
          item && reviewCore
            ? reviewCore.guessForOrdinal(review.observations, item.ordinal)
            : null;
        if (!guess || guess.visibility !== 'VISIBLE') {
          setReviewModeStatus('The detector guess is UNSURE; click the cursor or press H.');
          return;
        }
        applyReviewLabel('accept', null);
      } else if (event.key === 'h' || event.key === 'H') {
        event.preventDefault();
        applyReviewLabel('hidden', null);
      } else if (event.key === 'a' || event.key === 'A') {
        event.preventDefault();
        applyReviewLabel('ambiguous', null);
      } else if (event.key === 's' || event.key === 'S') {
        event.preventDefault();
        skipQueueItem();
      } else if (event.key === 'z' || event.key === 'Z') {
        event.preventDefault();
        undoReviewLabel();
      } else if (event.key === 'ArrowRight') {
        event.preventDefault();
        selectQueueItem(mode.index + 1);
      } else if (event.key === 'ArrowLeft') {
        event.preventDefault();
        selectQueueItem(mode.index - 1);
      }
    });

    const analysisSelect = panel.querySelector('[data-review-analysis]');
    if (analysisSelect)
      analysisSelect.addEventListener('change', async () => {
        // A save belongs to the analysis it was reviewed against; finish it before switching.
        if (review.reviewMode.active || review.pendingSave || review.dirty) {
          const closed = review.reviewMode.active ? await exitReviewMode() : await flushReviewSaves();
          if (!closed) {
            analysisSelect.value = review.analysisId || '';
            return;
          }
        }
        review.analysisId = analysisSelect.value || null;
        review.session = null;
        review.lastAcknowledgement = null;
        review.generation += 1;
        review.reviewMode.blind = true;
        syncBlindUi();
        await loadReviewPolicy();
        review.reviewMode.forcedBlind = review.policy.forcedBlind;
        review.reviewMode.blind = review.policy.forcedBlind || review.policy.eligibleModes.includes('WITHOUT_SUGGESTIONS');
        syncBlindUi();
        // Cumulative history is per analysis: never carry a draft across the switch.
        review.draft = [];
        review.draftVersion += 1;
        review.dirty = false;
        review.draftContext = null;
        review.reviewMode.undo = [];
        review.reviewMode.labelsSinceSave = 0;
        review.frames = [];
        review.byOrdinal = new Map();
        review.selectedOrdinal = null;
        review.nextCursor = null;
        review.artifactChecksum = null;
        review.observations = [];
        resetPreview();
        renderDraft();
        renderFrames();
        renderFrameDetail();
        if (review.analysisId) {
          await loadFrames(null);
          await loadObservations();
          await loadHistory();
          setDraftContext();
          review.draft = reviewCore
            ? reviewCore.seedDraft(review.history, { analysisId: review.analysisId })
            : [];
          review.draftVersion += 1;
          renderDraft();
        }
      });

    const frameKeys = panel.querySelector('[data-review-frames]');
    if (frameKeys)
      frameKeys.addEventListener('keydown', (event) => {
        if (review.reviewMode.active) return;
        if (event.target.closest?.('.mouse-video-table-scroll')) return;
        if (event.target !== frameKeys && /^(INPUT|SELECT|TEXTAREA)$/.test(event.target.tagName)) {
          return;
        }
        if (event.key === 'ArrowRight') {
          event.preventDefault();
          stepFrame(1);
        } else if (event.key === 'ArrowLeft') {
          event.preventDefault();
          stepFrame(-1);
        } else if (event.key === 'Home') {
          event.preventDefault();
          if (review.frames.length) selectFrame(review.frames[0].ordinal);
        } else if (event.key === 'End') {
          event.preventDefault();
          if (review.frames.length) selectFrame(review.frames[review.frames.length - 1].ordinal);
        }
      });

    const jump = panel.querySelector('[data-frame-ordinal]');
    if (jump)
      jump.addEventListener('change', () => {
        if (review.reviewMode.active) return;
        const ordinal = Number(jump.value);
        if (Number.isInteger(ordinal)) selectFrame(ordinal);
      });

    for (const [selector, delta] of [
      ['[data-frame-prev]', -1],
      ['[data-frame-next]', 1],
    ]) {
      const button = panel.querySelector(selector);
      if (button) button.addEventListener('click', () => stepFrame(delta));
    }
    const first = panel.querySelector('[data-frame-first]');
    if (first)
      first.addEventListener('click', () => {
        if (review.frames.length) selectFrame(review.frames[0].ordinal);
      });
    const last = panel.querySelector('[data-frame-last]');
    if (last)
      last.addEventListener('click', () => {
        if (review.frames.length) selectFrame(review.frames[review.frames.length - 1].ordinal);
      });
    const more = panel.querySelector('[data-review-load-more]');
    if (more)
      more.addEventListener('click', async () => {
        if (review.nextCursor) await loadFrames(review.nextCursor);
      });

    const previewRetry = panel.querySelector('[data-preview-retry]');
    if (previewRetry)
      previewRetry.addEventListener('click', () => {
        const frame = review.byOrdinal.get(review.selectedOrdinal);
        if (frame) loadPreview(frame, true);
      });
    const previewClear = panel.querySelector('[data-preview-clear]');
    if (previewClear)
      previewClear.addEventListener('click', () => {
        review.preview.point = null;
        review.preview.region = null;
        setPreviewSelection();
      });
    panel
      .querySelector('[data-preview-predictions]')
      ?.addEventListener('change', renderOverlay);
    panel.querySelector('[data-preview-mode]')?.addEventListener('change', () => {
      review.preview.point = null;
      review.preview.region = null;
      setPreviewSelection();
    });
    const previewImage = panel.querySelector('[data-preview-image]');
    let dragOrigin = null;
    previewImage?.addEventListener('pointerdown', (event) => {
      if (review.preview.imageOrdinal !== review.selectedOrdinal) return;
      dragOrigin = imageCoordinates(event);
      if (!dragOrigin) return;
      event.preventDefault();
      if (panel.querySelector('[data-preview-mode]')?.value === 'region') {
        review.preview.point = null;
        review.preview.region = { x: dragOrigin.x, y: dragOrigin.y, width: 1, height: 1 };
        renderOverlay();
      }
    });
    previewImage?.addEventListener('pointermove', (event) => {
      if (!dragOrigin) return;
      const current = imageCoordinates(event);
      if (!current) return;
      const x = Math.min(dragOrigin.x, current.x);
      const y = Math.min(dragOrigin.y, current.y);
      review.preview.region = {
        x,
        y,
        width: Math.max(1, Math.abs(current.x - dragOrigin.x)),
        height: Math.max(1, Math.abs(current.y - dragOrigin.y)),
      };
      renderOverlay();
    });
    previewImage?.addEventListener('pointerup', (event) => {
      if (!dragOrigin) return;
      const origin = dragOrigin;
      dragOrigin = null;
      const current = imageCoordinates(event);
      if (!current) return;
      const mode = panel.querySelector('[data-preview-mode]')?.value;
      if (mode === 'region') {
        review.preview.point = null;
        review.preview.region = {
          x: Math.min(origin.x, current.x),
          y: Math.min(origin.y, current.y),
          width: Math.max(1, Math.abs(current.x - origin.x)),
          height: Math.max(1, Math.abs(current.y - origin.y)),
        };
      } else {
        review.preview.point = current;
        review.preview.region = null;
      }
      setPreviewSelection();
      if (review.reviewMode.active && mode === 'point' && review.preview.point) {
        applyReviewLabel('click', review.preview.point);
      }
    });
    previewImage?.addEventListener('pointercancel', () => {
      dragOrigin = null;
    });

    const resume = loadState();
    // A `?source=<uuid>` link (from the replay catalog) takes priority over the resumed source.
    const linked = linkedSourceId(window.location.search);
    toggleMode();
    loadCapabilities()
      .then(async () => {
        await refreshCatalog();
        const sourceId = linked || resume.lastSourceId;
        if (sourceId) await inspect(sourceId);
      })
      .catch((error) => setStatus('Unable to load: ' + error.message));
  }

  return {
    mount,
    newRequestId,
    previewTerminalError,
    formatBytes,
    formatLimits,
    selectedProxyArtifact,
    describeSource,
    linkedSourceId,
    describeFrame,
    buildCorrection,
    addCorrection,
    loadState,
    saveState,
  };
});

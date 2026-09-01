(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.AiTrainingCatalog = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  const storageKey = 'chromascape.aiTraining.selectedLineage';
  let catalog = null;
  let selectedLineageId = null;
  let initialized = false;

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;').replaceAll("'", '&#39;');
  }

  function pretty(value) {
    return JSON.stringify(value == null ? {} : value, null, 2);
  }

  function rangeLabel(recording) {
    return recording.startTick == null ? 'Full recording' : `Ticks ${recording.startTick}–${recording.endTick}`;
  }

  function statusClass(status) {
    return String(status || 'unknown').toLowerCase().replaceAll('_', '-');
  }

  async function readJson(response) {
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body.message || body.code || `Request failed (${response.status})`);
    return body;
  }

  async function loadCatalog() {
    const usage = document.getElementById('aiTrainingUsageFilter').value;
    const task = document.getElementById('aiTrainingTaskFilter').value;
    const status = document.getElementById('aiTrainingStatusFilter').value;
    const query = new URLSearchParams({ usedInAi: String(usage !== 'all') });
    if (task !== 'all') query.set('task', task);
    if (status !== 'all') query.set('status', status);
    setAlert('Loading AI evidence…');
    try {
      catalog = await readJson(await fetch(`/api/ai-training/catalog?${query}`));
      populateTasks(catalog.tasks || [], task);
      renderCatalog(catalog);
      setAlert('');
      const available = (catalog.lineages || []).some(item => item.lineageId === selectedLineageId);
      const preferred = available ? selectedLineageId : restoreSelection(catalog.lineages || []);
      if (preferred) await selectLineage(preferred, false);
      else renderEmpty();
    } catch (error) {
      setAlert(error.message, true);
      renderEmpty('Catalog unavailable', 'The provider warning is isolated; refresh after the source is available.');
    }
  }

  function populateTasks(tasks, selected) {
    const select = document.getElementById('aiTrainingTaskFilter');
    const options = ['<option value="all">All tasks</option>']
      .concat(tasks.map(task => `<option value="${escapeHtml(task)}">${escapeHtml(task)}</option>`));
    select.innerHTML = options.join('');
    select.value = tasks.includes(selected) ? selected : 'all';
  }

  function renderCatalog(value) {
    const lineages = value.lineages || [];
    document.getElementById('aiTrainingCount').textContent = String(lineages.length);
    document.getElementById('aiTrainingLineageList').innerHTML = lineages.length
      ? lineages.map(item => `
        <button type="button" class="ai-training-lineage" role="option"
          aria-selected="${item.lineageId === selectedLineageId}" data-lineage-id="${escapeHtml(item.lineageId)}">
          <strong>${escapeHtml(item.displayName)}</strong>
          <small>${escapeHtml(item.provider)} · ${escapeHtml((item.recordingIds || []).length)} recording(s)</small>
          <span class="ai-training-badges">
            <span class="ai-training-badge ${statusClass(item.status)}">${escapeHtml(item.status)}</span>
            ${item.warningCount ? `<span class="ai-training-badge">${item.warningCount} warning(s)</span>` : ''}
          </span>
        </button>`).join('')
      : '<p class="text-muted small">No lineages match these filters.</p>';
    document.querySelectorAll('[data-lineage-id]').forEach(button => {
      button.addEventListener('click', () => selectLineage(button.dataset.lineageId));
      button.addEventListener('keydown', event => moveSelection(event, button));
    });

    const recordings = value.recordings || [];
    document.getElementById('aiTrainingRecordingCount').textContent = String(recordings.length);
    document.getElementById('aiTrainingRecordingList').innerHTML = recordings.length
      ? recordings.map(recording => `
        <div class="ai-training-recording">
          <strong>${escapeHtml(recording.displayName)}</strong>
          <div>${escapeHtml(recording.task || 'unknown')} · ${escapeHtml(recording.source || 'source unknown')}</div>
          <span class="ai-training-badges">${(recording.badges || []).map(badge => `<span class="ai-training-badge">${escapeHtml(badge)}</span>`).join('')}</span>
        </div>`).join('')
      : '<p class="text-muted small mt-2">No recordings match.</p>';
  }

  function moveSelection(event, button) {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const buttons = [...document.querySelectorAll('[data-lineage-id]')];
    const direction = event.key === 'ArrowDown' ? 1 : -1;
    const next = buttons[(buttons.indexOf(button) + direction + buttons.length) % buttons.length];
    next.focus();
    selectLineage(next.dataset.lineageId);
  }

  async function selectLineage(lineageId, focus = true) {
    selectedLineageId = lineageId;
    try { localStorage.setItem(storageKey, lineageId); } catch (_) { /* storage can be disabled */ }
    document.querySelectorAll('[data-lineage-id]').forEach(button =>
      button.setAttribute('aria-selected', String(button.dataset.lineageId === lineageId)));
    const detail = document.getElementById('aiTrainingDetail');
    detail.innerHTML = '<div class="ai-training-empty"><span class="spinner-border" aria-hidden="true"></span><p>Joining evidence…</p></div>';
    try {
      const lineage = await readJson(await fetch(`/api/ai-training/lineages/${encodeURIComponent(lineageId)}`));
      if (selectedLineageId !== lineageId) return;
      renderDetail(lineage);
      if (focus) detail.focus({ preventScroll: true });
    } catch (error) {
      renderEmpty('Lineage unavailable', error.message);
    }
  }

  function renderDetail(lineage) {
    const modelId = lineage.model && lineage.model.modelId;
    const graphId = lineage.model && lineage.model.graphId;
    const recordings = lineage.recordings || [];
    const training = recordings.find(value => value.role === 'TRAINING' || value.role === 'AI_ROUTINE_CAPTURE');
    const heldOut = recordings.find(value => value.role === 'HELD_OUT');
    const actions = lineage.actions || [];
    const warnings = lineage.warnings || [];
    document.getElementById('aiTrainingDetail').innerHTML = `
      <header class="ai-training-detail-header">
        <div>
          <span class="ai-training-badge">${escapeHtml(lineage.status)}</span>
          <h2>${escapeHtml(lineage.displayName)}</h2>
          <p>${escapeHtml(lineage.task)} · ${escapeHtml(lineage.source || 'source unknown')} · ${escapeHtml(lineage.provider)}</p>
        </div>
        <div class="ai-training-actions" aria-label="Lineage actions">
          ${actions.map(actionButton).join('')}
        </div>
      </header>
      ${actions.filter(action => !action.available && action.href).map(action => `
        <div class="ai-training-warning mt-3">${escapeHtml(action.disabledReason)}
          <button type="button" class="btn btn-sm btn-link" data-workspace-target="${escapeHtml(action.href.replace('#', ''))}">Open Runs</button>
        </div>`).join('')}
      ${warnings.map(warning => `<div class="ai-training-warning mt-3"><strong>${escapeHtml(warning.code)}</strong> — ${escapeHtml(warning.message)}</div>`).join('')}
      <section class="ai-training-section">
        <h3>Selected evidence</h3>
        <div class="ai-training-facts">
          ${fact('Training / capture', training ? training.recordingId : 'Not linked', training ? rangeLabel(training) : '')}
          ${fact('Dataset', lineage.dataset && lineage.dataset.datasetId || 'Not created', lineage.dataset && lineage.dataset.catchCount != null ? `${lineage.dataset.catchCount} catches` : '')}
          ${fact('Model', modelId || 'Not created', lineage.model && lineage.model.active ? 'Active' : '')}
          ${fact('Held-out', heldOut ? heldOut.recordingId : 'Not linked', heldOut ? rangeLabel(heldOut) : '')}
          ${fact('BehaviorGraph', graphId || behaviorGraphId(lineage) || 'Not created', lineage.behaviorGraph ? 'Persisted graph' : 'Prototype lineage only')}
          ${fact('Actions learned', actionVocabulary(lineage), '')}
          ${fact('Training outcome', trainingOutcome(lineage), xpOutcome(lineage))}
          ${fact('Shadow', lineage.shadow && lineage.shadow.active ? 'Active' : 'Inactive', `${lineage.shadow && lineage.shadow.runCount || 0} run(s) · ${lineage.shadow && lineage.shadow.decisionCount || 0} decision(s)`) }
        </div>
      </section>
      <section class="ai-training-section ai-training-diagrams">
        <div class="ai-training-diagram">
          <h3>Lineage graph</h3>
          <p class="small text-muted">Provenance from source recording through evaluation and shadow.</p>
          ${diagram(lineage.lineageGraph, 'No provenance nodes discovered.', 'lineage')}
        </div>
        <div class="ai-training-diagram">
          <h3>BehaviorGraph</h3>
          <p class="small text-muted">Only a genuine persisted <code>graph_…</code> definition appears here.</p>
          ${lineage.behaviorGraph ? diagram(lineage.behaviorGraph, 'The persisted graph has no nodes.', 'behavior') : '<div class="ai-training-not-created"><strong>Not created</strong><br><span>This model is not an AI Routine BehaviorGraph.</span></div>'}
        </div>
      </section>
      <section id="aiTrainingNodeDetail" class="ai-training-section ai-training-node-detail" hidden></section>
      <section class="ai-training-section">
        <h3>Recordings and ranges</h3>
        <div class="ai-training-facts">${recordings.map(recordingFact).join('') || '<p class="text-muted">No source recording reference.</p>'}</div>
      </section>
      <section class="ai-training-section">
        <h3>Held-out replay evidence</h3>
        ${(lineage.evaluations || []).map(evaluationRow).join('') || '<p class="text-muted">No replay result recorded.</p>'}
      </section>
      <details class="ai-training-section ai-training-raw">
        <summary>Raw evidence</summary>
        <pre>${escapeHtml(pretty(lineage.rawEvidence))}</pre>
      </details>`;

    document.querySelectorAll('[data-ai-training-action]').forEach(button =>
      button.addEventListener('click', () => executeAction(lineage, button.dataset.aiTrainingAction)));
    document.querySelectorAll('[data-workspace-target]').forEach(button =>
      button.addEventListener('click', () => window.DebuggerWorkspace && window.DebuggerWorkspace.activate(button.dataset.workspaceTarget)));
    document.querySelectorAll('[data-ai-node]').forEach(button => button.addEventListener('click', () => {
      const graphName = button.dataset.graphName;
      const graph = graphName === 'behavior' ? lineage.behaviorGraph : lineage.lineageGraph;
      const node = (graph.nodes || []).find(value => value.id === button.dataset.aiNode);
      showNode(node);
    }));
  }

  function actionButton(action) {
    const title = action.available ? '' : action.disabledReason || 'Prerequisite unavailable';
    const variant = action.action === 'ACTIVATE' ? 'btn-primary' : 'btn-outline-primary';
    return `<button type="button" class="btn btn-sm ${variant} ai-training-action"
      data-ai-training-action="${escapeHtml(action.action)}" ${action.available ? '' : 'disabled'}
      title="${escapeHtml(title)}">${escapeHtml(action.label)}</button>`;
  }

  function fact(label, value, note) {
    return `<div class="ai-training-fact"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong>${note ? `<small>${escapeHtml(note)}</small>` : ''}</div>`;
  }

  function recordingFact(recording) {
    const link = recording.videoUrl
      ? `<a class="btn btn-sm btn-outline-secondary mt-2" href="${escapeHtml(recording.videoUrl)}">Open replay/video</a>` : '';
    return `<div class="ai-training-fact"><span>${escapeHtml(recording.role)}</span><strong>${escapeHtml(recording.recordingId)}</strong><small>${escapeHtml(rangeLabel(recording))}</small>${link}</div>`;
  }

  function evaluationRow(value) {
    return `<div class="ai-training-evaluation">
      <strong>${escapeHtml(value.runId || value.traceId || 'Replay')}</strong>
      <span class="ai-training-badge">${escapeHtml(value.status || value.outcome || value.traceMode || 'RECORDED')}</span>
      <span>${escapeHtml(value.mismatchCount == null ? '—' : `${value.mismatchCount} mismatches`)}</span>
      <span>${escapeHtml(value.observedCatchDelta == null ? '' : `${value.observedCatchDelta} catches`)}</span>
    </div>`;
  }

  function diagram(value, emptyMessage, graphName) {
    const nodes = value && value.nodes || [];
    const edges = value && value.edges || [];
    if (!nodes.length) return `<div class="ai-training-not-created">${escapeHtml(emptyMessage)}</div>`;
    return `<div class="ai-training-flow">${nodes.map((node, index) => `
      ${index ? '<span class="ai-training-edge" aria-hidden="true">→</span>' : ''}
      <button type="button" class="ai-training-node" data-ai-node="${escapeHtml(node.id)}" data-graph-name="${escapeHtml(graphName)}">
        <span>${escapeHtml(node.type || 'NODE')}</span>${escapeHtml(node.label || node.id)}
      </button>`).join('')}</div>
      <div class="small text-muted mt-2">${edges.map(edge => `${escapeHtml(edge.from)} → ${escapeHtml(edge.to)}${edge.label ? ` (${escapeHtml(edge.label)})` : ''}`).join(' · ')}</div>`;
  }

  function behaviorGraphId(lineage) {
    const node = (lineage.lineageGraph && lineage.lineageGraph.nodes || []).find(value => value.type === 'BEHAVIOR_GRAPH');
    return node && node.id;
  }

  function showNode(node) {
    if (!node) return;
    const panel = document.getElementById('aiTrainingNodeDetail');
    panel.hidden = false;
    panel.innerHTML = `<h3>Graph node · ${escapeHtml(node.label || node.id)}</h3><pre>${escapeHtml(pretty(node.details))}</pre>`;
    panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  async function executeAction(lineage, action) {
    const definition = (lineage.actions || []).find(value => value.action === action);
    if (definition && definition.href && action === 'OPEN_ADVANCED') {
      window.DebuggerWorkspace && window.DebuggerWorkspace.activate(definition.href.replace('#', ''));
      return;
    }
    setAlert(`Running ${definition ? definition.label : action}…`);
    document.querySelectorAll('[data-ai-training-action]').forEach(button => { button.disabled = true; });
    try {
      const result = await readJson(await fetch('/api/ai-training/commands', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lineageId: lineage.lineageId, action })
      }));
      const message = result.message || 'Action completed';
      await loadCatalog();
      setAlert(message);
    } catch (error) {
      setAlert(error.message, true);
      await selectLineage(lineage.lineageId, false);
    }
  }

  function restoreSelection(lineages) {
    let stored = null;
    try { stored = localStorage.getItem(storageKey); } catch (_) { /* storage can be disabled */ }
    return lineages.some(item => item.lineageId === stored) ? stored : lineages[0] && lineages[0].lineageId;
  }

  function actionVocabulary(lineage) {
    const vocabulary = lineage.model && lineage.model.actionVocabulary
      || lineage.dataset && lineage.dataset.actionVocabulary || [];
    if (!vocabulary.length) return 'No actions recorded';
    return vocabulary.map(value => [value.type, value.option, value.target].filter(Boolean).join(' · ')).join(', ');
  }

  function trainingOutcome(lineage) {
    const catches = lineage.dataset && (lineage.dataset.catchCount ?? lineage.dataset.observedCatchDelta);
    return catches == null ? 'No outcome summary' : `${catches} catches`;
  }

  function xpOutcome(lineage) {
    const xp = lineage.dataset && lineage.dataset.fishingXpDelta;
    return xp == null ? '' : `${xp} Fishing XP`;
  }

  function setAlert(message, error = false) {
    const alert = document.getElementById('aiTrainingAlert');
    if (!alert) return;
    alert.hidden = !message;
    alert.textContent = message || '';
    alert.classList.toggle('error', error);
  }

  function renderEmpty(title = 'Select a lineage', message = 'Choose a recording-backed model or AI Routine to inspect its provenance and safe operations.') {
    document.getElementById('aiTrainingDetail').innerHTML = `<div class="ai-training-empty"><i class="bi bi-diagram-3"></i><h2>${escapeHtml(title)}</h2><p>${escapeHtml(message)}</p></div>`;
  }

  function initialize() {
    if (initialized || !document.getElementById('aiTrainingPanel')) return;
    initialized = true;
    ['aiTrainingUsageFilter', 'aiTrainingTaskFilter', 'aiTrainingStatusFilter'].forEach(id =>
      document.getElementById(id).addEventListener('change', loadCatalog));
    document.getElementById('aiTrainingRefresh').addEventListener('click', loadCatalog);
    document.addEventListener('debugger:workspace-changed', event => {
      if (event.detail && event.detail.workspace === 'ai-training' && !catalog) loadCatalog();
    });
    if (document.body.dataset.workspace === 'ai-training') loadCatalog();
  }

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
    else initialize();
  }

  return { escapeHtml, rangeLabel, statusClass, restoreSelection };
});

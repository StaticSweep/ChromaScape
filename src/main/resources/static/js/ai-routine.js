(() => {
  'use strict';
  const api = '/api/v1/ai-routine';
  const panel = document.getElementById('ai-routine-panel');
  if (!panel) return;
  // Pure state module: sequences advance only after an accepted response, pending payloads and
  // idempotency keys are persisted for durable retry, and tokens are never persisted.
  const state = AiRoutineState.load(localStorage.getItem(AiRoutineState.STORAGE_KEY) || '{}');
  state.connection = 'DISCONNECTED';
  let stream;
  let reconnectTimer;
  const output = panel.querySelector('[data-ai-routine-output]');
  const write = (value) => { if (output) output.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2); };
  // Never persist tokens: operator and capture tokens are read from inputs only.
  const persist = () => localStorage.setItem(AiRoutineState.STORAGE_KEY, AiRoutineState.persist({ ...state, connection: undefined }));
  const setConnection = (value) => { state.connection = value; const node = panel.querySelector('[data-ai-sse-state]'); if (node) node.textContent = value; };
  const request = async (path, options = {}) => {
    const response = await fetch(api + path, { headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }, ...options });
    const body = await response.text(); let value; try { value = body ? JSON.parse(body) : {}; } catch (_) { value = body; }
    if (!response.ok) throw new Error((value && (value.message || value.reason)) || `AI Routine request failed (${response.status})`);
    if (value && typeof value === 'object') Object.defineProperty(value, '__httpStatus', { value: response.status, enumerable: false });
    return value;
  };
  const render = () => panel.querySelectorAll('[data-ai-value]').forEach((node) => { node.textContent = state[node.dataset.aiValue] || '—'; });
  const identity = () => JSON.parse(panel.querySelector('[data-ai-identity]')?.value || '{}');
  // Capture must bind to the run identity returned by run creation, not a freshly parsed textarea.
  const captureIdentity = () => state.identity || identity();
  const bind = async () => {
    const value = identity();
    const mode = panel.querySelector('[data-ai-client-kind]')?.value || value.client?.client_kind;
    const run = mode === 'RUNELITE_CAPTURE'
      ? await operator('/runs/from-runelite-capture', { method: 'POST', body: JSON.stringify({ identity: value }) })
      : await operator(`/runs/from-stabilization/${encodeURIComponent(panel.querySelector('[data-ai-attempt]')?.value || '')}`, { method: 'POST', body: JSON.stringify({ identity: value }) });
    const conversation = await operator(`/runs/${encodeURIComponent(run.run_id || run.identity?.run?.run_id)}/assistant/conversations`, { method: 'POST' });
    // A new conversation resets the SSE cursor to zero via the pure state module.
    AiRoutineState.bindRun(state, run, conversation.conversation_id);
    persist(); render(); subscribe(); write(run);
  };
  const tokenHeader = () => panel.querySelector('[data-ai-token]')?.value || '';
  const operatorTokenHeader = () => panel.querySelector('[data-ai-operator-token]')?.value || '';
  const startCapture = async () => {
    // The pending request payload and idempotency key are persisted so an uncertain capture start
    // can be replayed exactly once with the same key.
    const pending = AiRoutineState.beginCapture(state, { schema: 'routine-capture-session/v1', identity: captureIdentity(), startedAt: new Date().toISOString(), startTick: 0, videoCapture: 'CHROMASCAPE_WINDOW' });
    persist();
    const response = await request('/capture/sessions', { method: 'POST', headers: { 'Authorization': `Bearer ${tokenHeader()}`, 'Idempotency-Key': pending.idempotencyKey }, body: JSON.stringify(pending.payload) });
    AiRoutineState.acceptCapture(state, 'start', response);
    persist(); render(); write(response);
    if (AiRoutineState.isPendingCaptureResponse(response, 'start')) await pollCaptureStatus('start');
  };
  const retryUncertain = async () => {
    const pending = state.completePending || state.capturePending;
    const pendingKind = state.completePending ? 'complete' : 'start';
    if (!pending) { write({ error: 'no uncertain capture request retained' }); return; }
    const response = await request(`/capture/sessions/${encodeURIComponent(state.sessionId)}/reconcile`, {
      method: 'POST', headers: { 'Authorization': `Bearer ${tokenHeader()}`, 'Idempotency-Key': pending.idempotencyKey }
    });
    AiRoutineState.acceptCapture(state, pendingKind, response);
    persist(); render(); write(response);
    if (AiRoutineState.isPendingCaptureResponse(response, pendingKind)) await pollCaptureStatus(pendingKind);
  };
  const pollCaptureStatus = async (kind) => {
    if (!state.sessionId) return null;
    let latest = null;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      latest = await request(`/capture/sessions/${encodeURIComponent(state.sessionId)}`, { headers: { 'Authorization': `Bearer ${tokenHeader()}` } });
      if (state.completePending) AiRoutineState.acceptCapture(state, 'complete', latest);
      if (state.capturePending) AiRoutineState.acceptCapture(state, 'start', latest);
      persist(); render(); write(latest);
      const pendingKind = kind || (state.completePending ? 'complete' : 'start');
      if (!AiRoutineState.isPendingCaptureResponse(latest, pendingKind)) return latest;
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    return latest;
  };
  const captureStatus = async () => { await pollCaptureStatus(); };
  const parseBatch = () => {
    const raw = panel.querySelector('[data-ai-batch]')?.value || '[]';
    try { return JSON.parse(raw); } catch (_) { throw new Error('batch JSON is invalid'); }
  };
  const uploadTicks = async () => {
    const values = parseBatch();
    // The pending payload (sequence + idempotency key) is persisted before the request so a lost
    // response can be retried exactly once; the sequence only advances after an accepted response.
    const pending = AiRoutineState.beginBatch(state, 'ticks', state.sessionId, state.tickSequence + 1, values);
    persist();
    const response = await request(`/capture/sessions/${encodeURIComponent(state.sessionId)}/ticks`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${tokenHeader()}`, 'Idempotency-Key': pending.idempotencyKey },
      body: JSON.stringify(pending.payload)
    });
    AiRoutineState.acceptBatch(state, 'ticks', response);
    persist(); render(); write(response);
  };
  const uploadActions = async () => {
    const values = parseBatch();
    const pending = AiRoutineState.beginBatch(state, 'actions', state.sessionId, state.actionSequence + 1, values);
    persist();
    const response = await request(`/capture/sessions/${encodeURIComponent(state.sessionId)}/actions`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${tokenHeader()}`, 'Idempotency-Key': pending.idempotencyKey },
      body: JSON.stringify(pending.payload)
    });
    AiRoutineState.acceptBatch(state, 'actions', response);
    persist(); render(); write(response);
  };
  const completeCapture = async () => {
    const finalTick = Number(panel.querySelector('[data-ai-final-tick]')?.value || 0);
    const videoSha = panel.querySelector('[data-ai-video-hash]')?.value || '';
    const pending = AiRoutineState.beginComplete(state, {
      finalTick: finalTick, tickSequence: state.tickSequence, actionSequence: state.actionSequence, videoSha256: videoSha
    });
    persist();
    const response = await request(`/capture/sessions/${encodeURIComponent(state.sessionId)}/complete`, {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${tokenHeader()}`, 'Idempotency-Key': pending.idempotencyKey },
      body: JSON.stringify(pending.payload)
    });
    AiRoutineState.acceptComplete(state, response);
    persist(); render(); write(response);
    if (AiRoutineState.isPendingCaptureResponse(response, 'complete')) await pollCaptureStatus('complete');
  };
  const curate = async () => {
    const include = panel.querySelector('[data-ai-curation-include]')?.value || '';
    const exclude = panel.querySelector('[data-ai-curation-exclude]')?.value || '';
    const value = await operator(`/recordings/${encodeURIComponent(state.recordingId)}/curation`, {
      method: 'PUT',
      body: JSON.stringify({ includeRanges: parseRanges(include), excludeRanges: parseRanges(exclude) })
    });
    write(value);
  };
  const parseRanges = (raw) => {
    const text = (raw || '').trim();
    if (!text) return null;
    try { return JSON.parse(text); } catch (_) { throw new Error('range JSON is invalid'); }
  };
  const generateGraph = async () => {
    if (!state.captured) { write({ error: 'graph generation requires a completed capture' }); return; }
    const value = await operator(`/recordings/${encodeURIComponent(state.recordingId)}/graphs`, { method: 'POST' });
    state.graphId = value.definition?.graph_id || value.graph_id || value.graph?.graph_id || state.graphId;
    persist(); render(); write(value);
  };
  const publishGraph = async () => {
    const graphId = panel.querySelector('[data-ai-graph-id]')?.value || state.graphId;
    const value = await operator(`/graphs/${encodeURIComponent(graphId)}/publish`, { method: 'POST' });
    write(value);
  };
  const createModel = async () => {
    const graphId = panel.querySelector('[data-ai-graph-id]')?.value || state.graphId;
    const value = await operator(`/graphs/${encodeURIComponent(graphId)}/models`, {
      method: 'POST',
      body: JSON.stringify({ identity: state.identity || identity() })
    });
    state.modelId = value.model_id || value.model?.model_id || state.modelId;
    persist(); render(); write(value);
  };
  const runReplay = async () => {
    const modelId = panel.querySelector('[data-ai-model-id]')?.value || state.modelId;
    const value = await operator(`/models/${encodeURIComponent(modelId)}/replays`, {
      method: 'POST',
      body: JSON.stringify({ recordingId: state.recordingId })
    });
    write(value);
  };
  const runShadow = async () => {
    const modelId = panel.querySelector('[data-ai-model-id]')?.value || state.modelId;
    const value = await operator(`/models/${encodeURIComponent(modelId)}/shadow`, {
      method: 'POST',
      body: JSON.stringify({ sessionId: state.sessionId })
    });
    write(value);
  };
  const subscribe = () => {
    if (!state.conversationId) return;
    if (stream) stream.close();
    setConnection('CONNECTING');
    // Resumable SSE: the persisted cursor is replayed on every new EventSource via the `after`
    // query parameter; the server never starts from zero on reconnect.
    const url = `${api}/assistant/conversations/${encodeURIComponent(state.conversationId)}/events?after=${encodeURIComponent(state.lastEventId)}`;
    stream = new EventSource(url, { withCredentials: true });
    const types = ['conversation.started', 'message.accepted', 'analysis.delta', 'proposal.created', 'proposal.accepted', 'proposal.rejected', 'generation.stopped', 'generation.failed', 'stream.complete'];
    types.forEach((type) => stream.addEventListener(type, (event) => { const id = Number(event.lastEventId || 0); if (id > state.lastEventId) { state.lastEventId = id; persist(); } setConnection(type === 'stream.complete' ? 'COMPLETE' : 'CONNECTED'); write(event.data); }));
    stream.onopen = () => setConnection('CONNECTED');
    stream.onerror = () => {
      setConnection('RECONNECTING');
      if (stream) stream.close();
      stream = null;
      clearTimeout(reconnectTimer);
      reconnectTimer = setTimeout(subscribe, 1000);
    };
  };
  const operator = async (path, options = {}) => {
    const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${operatorTokenHeader()}`, ...(options.headers || {}) };
    const response = await fetch(api + path, { headers, ...options });
    const body = await response.text(); let value; try { value = body ? JSON.parse(body) : {}; } catch (_) { value = body; }
    if (!response.ok) throw new Error((value && (value.message || value.reason)) || `AI Routine request failed (${response.status})`);
    return value;
  };
  const bindClick = (attr, handler) => panel.querySelector(`[${attr}]`)?.addEventListener('click', (event) => { event.preventDefault(); handler().catch((error) => write({ error: error.message })); });
  bindClick('data-ai-start', () => bind());
  bindClick('data-ai-capture-start', () => startCapture());
  bindClick('data-ai-capture-retry', () => retryUncertain());
  bindClick('data-ai-capture-status', () => captureStatus());
  bindClick('data-ai-ticks-upload', () => uploadTicks());
  bindClick('data-ai-actions-upload', () => uploadActions());
  bindClick('data-ai-capture-complete', () => completeCapture());
  bindClick('data-ai-curate', () => curate());
  bindClick('data-ai-graph-generate', () => generateGraph());
  bindClick('data-ai-graph-publish', () => publishGraph());
  bindClick('data-ai-model-create', () => createModel());
  bindClick('data-ai-replay-run', () => runReplay());
  bindClick('data-ai-shadow-run', () => runShadow());
  bindClick('data-ai-message', async () => { const value = await operator(`/assistant/conversations/${encodeURIComponent(state.conversationId)}/messages`, { method: 'POST', body: JSON.stringify({ message: panel.querySelector('[data-ai-text]')?.value || '' }) }); write(value); });
  bindClick('data-ai-stop', async () => { const value = await operator(`/assistant/conversations/${encodeURIComponent(state.conversationId)}/stop`, { method: 'POST' }); write(value); });
  bindClick('data-ai-reconcile', () => (state.capturePending || state.completePending) ? retryUncertain() : captureStatus());
  bindClick('data-ai-proposal-accept', async () => { const id = panel.querySelector('[data-ai-proposal-id]')?.value || ''; await operator(`/assistant/proposals/${encodeURIComponent(id)}/accept`, { method: 'POST' }); });
  bindClick('data-ai-proposal-reject', async () => { const id = panel.querySelector('[data-ai-proposal-id]')?.value || ''; await operator(`/assistant/proposals/${encodeURIComponent(id)}/reject`, { method: 'POST', body: JSON.stringify({ reason: 'operator rejected' }) }); });
  bindClick('data-ai-candidate-approve', async () => { const id = panel.querySelector('[data-ai-candidate-proposal]')?.value || ''; await operator(`/patches/${encodeURIComponent(id)}/approve`, { method: 'POST' }); });
  bindClick('data-ai-candidate-build', async () => { const id = panel.querySelector('[data-ai-candidate-proposal]')?.value || ''; await operator(`/patches/${encodeURIComponent(id)}/build`, { method: 'POST' }); });
  bindClick('data-ai-candidate-reconcile', async () => { const id = panel.querySelector('[data-ai-candidate-proposal]')?.value || ''; await operator(`/patches/${encodeURIComponent(id)}/reconcile`, { method: 'POST' }); });
  render();
  // Resume the SSE stream after a page reload when a conversation is already bound.
  if (state.conversationId) subscribe();
})();

(() => {
  'use strict';
  const api = '/api/v1/ai-routine';
  const panel = document.getElementById('ai-routine-panel');
  if (!panel) return;
  const state = JSON.parse(localStorage.getItem('sc967-ai-routine-state') || '{}');
  state.lastEventId = Number(state.lastEventId || 0);
  state.connection = 'DISCONNECTED';
  let stream;
  let reconnectTimer;
  const output = panel.querySelector('[data-ai-routine-output]');
  const write = (value) => { if (output) output.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2); };
  const persist = () => localStorage.setItem('sc967-ai-routine-state', JSON.stringify({ ...state, connection: undefined }));
  const setConnection = (value) => { state.connection = value; const node = panel.querySelector('[data-ai-sse-state]'); if (node) node.textContent = value; };
  const request = async (path, options = {}) => {
    const response = await fetch(api + path, { headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }, ...options });
    const body = await response.text(); let value; try { value = body ? JSON.parse(body) : {}; } catch (_) { value = body; }
    if (!response.ok) throw new Error((value && (value.message || value.reason)) || `AI Routine request failed (${response.status})`);
    return value;
  };
  const render = () => panel.querySelectorAll('[data-ai-value]').forEach((node) => { node.textContent = state[node.dataset.aiValue] || '—'; });
  const identity = () => JSON.parse(panel.querySelector('[data-ai-identity]')?.value || '{}');
  const bind = async () => {
    const value = identity();
    const mode = panel.querySelector('[data-ai-client-kind]')?.value || value.client?.client_kind;
    const run = mode === 'RUNELITE_CAPTURE'
      ? await request('/runs/from-runelite-capture', { method: 'POST', body: JSON.stringify({ identity: value }) })
      : await request(`/runs/from-stabilization/${encodeURIComponent(panel.querySelector('[data-ai-attempt]')?.value || '')}`, { method: 'POST', body: JSON.stringify({ identity: value }) });
    state.runId = run.run_id || run.identity?.run?.run_id;
    state.identity = run.identity;
    const conversation = await request(`/runs/${encodeURIComponent(state.runId)}/assistant/conversations`, { method: 'POST' });
    state.conversationId = conversation.conversation_id;
    persist(); render(); subscribe(); write(run);
  };
  const startCapture = async () => {
    const body = { schema: 'routine-capture-session/v1', identity: identity(), started_at: new Date().toISOString(), start_tick: 0, video_capture: 'CHROMASCAPE_WINDOW' };
    const response = await request('/capture/sessions', { method: 'POST', headers: { 'Authorization': `Bearer ${panel.querySelector('[data-ai-token]')?.value || ''}`, 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(body) });
    state.sessionId = response.session_id; state.recordingId = response.recording_id; persist(); render(); write(response);
  };
  const captureStatus = async () => { const value = await request(`/capture/sessions/${encodeURIComponent(state.sessionId)}`, { headers: { 'Authorization': `Bearer ${panel.querySelector('[data-ai-token]')?.value || ''}` } }); write(value); };
  const subscribe = () => {
    if (!state.conversationId) return;
    if (stream) stream.close();
    setConnection('CONNECTING');
    stream = new EventSource(`${api}/assistant/conversations/${encodeURIComponent(state.conversationId)}/events`, { withCredentials: true });
    const types = ['conversation.started', 'message.accepted', 'analysis.delta', 'proposal.created', 'proposal.accepted', 'proposal.rejected', 'generation.stopped', 'generation.failed', 'stream.complete'];
    types.forEach((type) => stream.addEventListener(type, (event) => { const id = Number(event.lastEventId || 0); if (id > state.lastEventId) { state.lastEventId = id; persist(); } setConnection(type === 'stream.complete' ? 'COMPLETE' : 'CONNECTED'); write(event.data); }));
    stream.onopen = () => setConnection('CONNECTED');
    stream.onerror = () => { setConnection('RECONNECTING'); stream.close(); clearTimeout(reconnectTimer); reconnectTimer = setTimeout(subscribe, 1000); };
  };
  panel.querySelector('[data-ai-start]')?.addEventListener('click', () => bind().catch((error) => write({ error: error.message })));
  panel.querySelector('[data-ai-capture-start]')?.addEventListener('click', () => startCapture().catch((error) => write({ error: error.message })));
  panel.querySelector('[data-ai-capture-status]')?.addEventListener('click', () => captureStatus().catch((error) => write({ error: error.message })));
  panel.querySelector('[data-ai-message]')?.addEventListener('click', async () => { try { write(await request(`/assistant/conversations/${encodeURIComponent(state.conversationId)}/messages`, { method: 'POST', body: JSON.stringify({ message: panel.querySelector('[data-ai-text]')?.value || '' }) })); } catch (error) { write({ error: error.message }); } });
  panel.querySelector('[data-ai-reconcile]')?.addEventListener('click', () => captureStatus().catch((error) => write({ error: error.message })));
  render();
})();

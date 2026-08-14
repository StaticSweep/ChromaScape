(() => {
  'use strict';
  const api = '/api/v1/ai-routine';
  const state = { runId: null, sessionId: null, recordingId: null, graphId: null, modelId: null, candidateId: null, lastEventId: 0 };
  const panel = document.getElementById('ai-routine-panel');
  if (!panel) return;
  const output = panel.querySelector('[data-ai-routine-output]');
  const write = (value) => { if (output) output.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2); };
  const request = async (path, options = {}) => {
    const response = await fetch(api + path, { headers: { 'Content-Type': 'application/json', ...(options.headers || {}) }, ...options });
    const body = await response.text();
    let value; try { value = body ? JSON.parse(body) : {}; } catch (_) { value = body; }
    if (!response.ok) throw new Error((value && value.message) || `AI Routine request failed (${response.status})`);
    return value;
  };
  const render = () => {
    panel.querySelectorAll('[data-ai-value]').forEach((node) => { const key = node.dataset.aiValue; node.textContent = state[key] || '—'; });
    const control = panel.querySelector('[data-ai-control]'); if (control) control.textContent = 'READ-ONLY (control_allowed=false)';
  };
  const start = async () => {
    const identity = panel.querySelector('[data-ai-identity]')?.value;
    const attemptId = panel.querySelector('[data-ai-attempt]')?.value || '';
    if (!identity) throw new Error('Current-slice identity JSON is required');
    const run = await request(`/runs/from-stabilization/${encodeURIComponent(attemptId)}`, { method: 'POST', body: JSON.stringify({ attemptId, identity: JSON.parse(identity) }) });
    state.runId = run.run_id || run.identity?.run?.run_id; render();
    const conversation = await request(`/runs/${encodeURIComponent(state.runId)}/assistant/conversations`, { method: 'POST' });
    state.conversationId = conversation.conversation_id; render();
    subscribe(); write(run);
  };
  const subscribe = () => {
    if (!state.conversationId) return;
    const stream = new EventSource(`${api}/assistant/conversations/${encodeURIComponent(state.conversationId)}/events`, { withCredentials: true });
    ['conversation.started', 'message.accepted', 'analysis.delta', 'proposal.created', 'generation.stopped', 'generation.failed', 'stream.complete'].forEach((type) => stream.addEventListener(type, (event) => { state.lastEventId = event.lastEventId || state.lastEventId; write(event.data); }));
    stream.onerror = () => stream.close();
  };
  panel.querySelector('[data-ai-start]')?.addEventListener('click', () => start().catch((error) => write({ error: error.message })));
  panel.querySelector('[data-ai-message]')?.addEventListener('click', async () => {
    try { const message = panel.querySelector('[data-ai-text]')?.value || ''; write(await request(`/assistant/conversations/${encodeURIComponent(state.conversationId)}/messages`, { method: 'POST', body: JSON.stringify({ message }) })); }
    catch (error) { write({ error: error.message }); }
  });
  render();
})();

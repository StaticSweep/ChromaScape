/**
 * Pure client-state module for the SC-967 AI Routine debugger UI.
 *
 * Responsibilities:
 * - persist complete pending tick/action/complete payloads together with their sequences and
 *   idempotency keys so an uncertain/lost response can be replayed exactly once;
 * - increment the tick/action sequence ONLY after an accepted response (never before a request);
 * - reconcile the authoritative capture status into local `captured`, session, recording, and
 *   next-sequence state;
 * - reset the SSE cursor to zero when a NEW conversation is created;
 * - never persist either the operator or the capture token.
 *
 * This module is intentionally side-effect free so it can be unit-tested in Node.
 */
((root) => {
  'use strict';

  const STORAGE_KEY = 'sc967-ai-routine-state';

  const defaults = () => ({
    lastEventId: 0,
    tickSequence: 0,
    actionSequence: 0,
    captured: false,
    sessionId: '',
    recordingId: '',
    runId: '',
    conversationId: '',
    capturePending: null,
    tickPending: null,
    actionPending: null,
    completePending: null
  });

  /** Loads persisted state; tokens are never persisted and therefore never loaded. */
  const load = (raw) => {
    const state = { ...defaults() };
    let parsed = {};
    if (raw) {
      try { parsed = JSON.parse(raw); } catch (_) { parsed = {}; }
    }
    for (const key of Object.keys(defaults())) {
      if (parsed[key] !== undefined) state[key] = parsed[key];
    }
    state.lastEventId = Number(state.lastEventId || 0);
    state.tickSequence = Number(state.tickSequence || 0);
    state.actionSequence = Number(state.actionSequence || 0);
    state.captured = Boolean(state.captured);
    return state;
  };

  /** Serializes state for persistence, dropping any token-like fields defensively. */
  const persist = (state) => {
    const { ...copy } = state;
    delete copy.operatorToken;
    delete copy.captureToken;
    return JSON.stringify(copy);
  };

  /** Creates (once) and returns the pending capture-start payload with its idempotency key. */
  const beginCapture = (state, payload) => {
    if (!state.capturePending) {
      state.capturePending = { payload: { ...payload }, idempotencyKey: newIdempotencyKey() };
    }
    return state.capturePending;
  };

  /** Creates (once) and returns the pending tick/action payload with its idempotency key. */
  const beginBatch = (state, kind, sessionId, sequence, values) => {
    const field = kind === 'ticks' ? 'tickPending' : 'actionPending';
    if (!state[field]) {
      state[field] = {
        idempotencyKey: newIdempotencyKey(),
        payload: {
          session_id: sessionId,
          sequence: sequence,
          ticks: kind === 'ticks' ? values : null,
          actions: kind === 'ticks' ? null : values
        }
      };
    }
    return state[field];
  };

  /** Creates (once) and returns the pending completion payload with its idempotency key. */
  const beginComplete = (state, payload) => {
    if (!state.completePending) {
      state.completePending = { idempotencyKey: newIdempotencyKey(), payload: { ...payload } };
    }
    return state.completePending;
  };

  /** Commits an accepted batch response: increments the sequence and clears the pending request. */
  const acceptBatch = (state, kind, response) => {
    const field = kind === 'ticks' ? 'tickPending' : 'actionPending';
    state[field] = null;
    const authoritative = Number(response && response.next_tick_sequence);
    if (kind === 'ticks') {
      state.tickSequence = Number.isFinite(authoritative) && authoritative > 0 ? authoritative - 1 : state.tickSequence + 1;
    } else {
      const nextAction = Number(response && response.next_action_sequence);
      state.actionSequence = Number.isFinite(nextAction) && nextAction > 0 ? nextAction - 1 : state.actionSequence + 1;
    }
    reconcileStatus(state, response);
    return state;
  };

  /** Reconciles the authoritative capture status into local captured/session/recording/sequences. */
  const reconcileStatus = (state, response) => {
    if (!response) return state;
    if (response.session_id) state.sessionId = response.session_id;
    if (response.recording_id) state.recordingId = response.recording_id;
    const status = response.completion_status || response.status;
    if (status === 'COMPLETED') state.captured = true;
    if (status === 'UNCERTAIN' || status === 'STARTING' || status === 'COMPLETING') state.captured = false;
    const nextTick = Number(response.next_tick_sequence);
    const nextAction = Number(response.next_action_sequence);
    if (Number.isFinite(nextTick) && nextTick > 0) state.tickSequence = nextTick - 1;
    if (Number.isFinite(nextAction) && nextAction > 0) state.actionSequence = nextAction - 1;
    return state;
  };

  /** Marks a completion as accepted, clearing the pending request. */
  const acceptComplete = (state, response) => {
    reconcileStatus(state, response);
    if (isTerminalCaptureResponse(response, 'complete')) state.completePending = null;
    return state;
  };

  /** A 202 or an operation lease/uncertain state is not an acknowledgement. */
  const isTerminalCaptureResponse = (response, kind) => {
    if (!response) return false;
    const expectedOperation = kind === 'start' ? 'START' : kind === 'complete' ? 'COMPLETE' : null;
    const operationState = String(response.operation?.state || '').toUpperCase();
    const operationKind = String(response.operation?.kind || '').toUpperCase();
    if (operationState && (!expectedOperation || !operationKind || operationKind === expectedOperation)) {
      return ['SUCCEEDED', 'FAILED'].includes(operationState);
    }
    const status = String(response.completion_status || response.status || '').toUpperCase();
    if (kind === 'start') return ['RECORDING', 'COMPLETED', 'FAILED'].includes(status);
    if (kind === 'complete') return ['COMPLETED', 'FAILED'].includes(status);
    return ['RECORDING', 'COMPLETED', 'FAILED'].includes(status);
  };

  const isPendingCaptureResponse = (response, kind) => !isTerminalCaptureResponse(response, kind);

  const acceptCapture = (state, kind, response) => {
    reconcileStatus(state, response);
    if (isTerminalCaptureResponse(response, kind)) {
      if (kind === 'start') state.capturePending = null;
      if (kind === 'complete') state.completePending = null;
    }
    return state;
  };

  /** Binds a run; a NEW conversation resets the SSE cursor to zero. */
  const bindRun = (state, run, conversationId) => {
    state.runId = (run && (run.run_id || (run.identity && run.identity.run && run.identity.run.run_id))) || state.runId;
    state.identity = (run && run.identity) || state.identity;
    if (conversationId && conversationId !== state.conversationId) {
      state.conversationId = conversationId;
      state.lastEventId = 0;
    } else if (conversationId) {
      state.conversationId = conversationId;
    }
    return state;
  };

  const randomKey = () => {
    if (typeof crypto !== 'undefined' && crypto.getRandomValues) {
      const bytes = new Uint8Array(16);
      crypto.getRandomValues(bytes);
      return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    }
    return 'key-' + Math.random().toString(36).slice(2);
  };

  const newIdempotencyKey = () => {
    const browserCrypto = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;
    return browserCrypto && typeof browserCrypto.randomUUID === 'function'
      ? browserCrypto.randomUUID()
      : randomKey();
  };

  const api = {
    STORAGE_KEY, defaults, load, persist, beginCapture, beginBatch, beginComplete,
    acceptBatch, acceptComplete, acceptCapture, isTerminalCaptureResponse,
    isPendingCaptureResponse, bindRun, reconcileStatus
  };
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  } else {
    root.AiRoutineState = api;
  }
})(typeof window !== 'undefined' ? window : globalThis);

const test = require('node:test');
const assert = require('node:assert');
const AiRoutineState = require('../../main/resources/static/js/ai-routine-state.js');

test('load never carries persisted tokens', () => {
  const raw = JSON.stringify({
    ...AiRoutineState.defaults(),
    operatorToken: 'op-secret',
    captureToken: 'cap-secret',
    tickSequence: 3,
    captured: true
  });
  const state = AiRoutineState.load(raw);
  assert.strictEqual(state.operatorToken, undefined);
  assert.strictEqual(state.captureToken, undefined);
  assert.strictEqual(state.tickSequence, 3);
  assert.strictEqual(state.captured, true);
});

test('persist drops token fields defensively', () => {
  const state = AiRoutineState.defaults();
  state.operatorToken = 'op-secret';
  state.captureToken = 'cap-secret';
  const serialized = AiRoutineState.persist(state);
  assert.ok(!serialized.includes('op-secret'));
  assert.ok(!serialized.includes('cap-secret'));
  const reparsed = JSON.parse(serialized);
  assert.strictEqual(reparsed.operatorToken, undefined);
  assert.strictEqual(reparsed.captureToken, undefined);
});

test('beginBatch persists complete payload and idempotency key, and is idempotent', () => {
  const state = AiRoutineState.defaults();
  const first = AiRoutineState.beginBatch(state, 'ticks', 'capture_x', 1, [{ tick: 1 }]);
  assert.ok(first.idempotencyKey);
  assert.deepStrictEqual(first.payload, {
    session_id: 'capture_x',
    sequence: 1,
    ticks: [{ tick: 1 }],
    actions: null
  });
  // A retry reuses the same pending request exactly (same key, same payload).
  const retry = AiRoutineState.beginBatch(state, 'ticks', 'capture_x', 1, [{ tick: 1 }]);
  assert.strictEqual(retry.idempotencyKey, first.idempotencyKey);
});

test('acceptBatch increments the sequence only after an accepted response', () => {
  const state = AiRoutineState.defaults();
  state.tickSequence = 0;
  AiRoutineState.beginBatch(state, 'ticks', 'capture_x', 1, [{ tick: 1 }]);
  AiRoutineState.acceptBatch(state, 'ticks', { next_tick_sequence: 2 });
  assert.strictEqual(state.tickSequence, 1);
  assert.strictEqual(state.tickPending, null);
});

test('acceptBatch reconciles authoritative next sequences from the server', () => {
  const state = AiRoutineState.defaults();
  AiRoutineState.beginBatch(state, 'actions', 'capture_x', 1, [{ tick: 1 }]);
  AiRoutineState.acceptBatch(state, 'actions', { next_action_sequence: 5 });
  assert.strictEqual(state.actionSequence, 4);
});

test('reconcileStatus reconciles captured state from completion_status', () => {
  const state = AiRoutineState.defaults();
  AiRoutineState.reconcileStatus(state, { completion_status: 'COMPLETED', session_id: 's', recording_id: 'r' });
  assert.strictEqual(state.captured, true);
  assert.strictEqual(state.sessionId, 's');
  assert.strictEqual(state.recordingId, 'r');

  AiRoutineState.reconcileStatus(state, { completion_status: 'UNCERTAIN' });
  assert.strictEqual(state.captured, false);
});

test('beginCapture persists the pending payload for uncertain retry', () => {
  const state = AiRoutineState.defaults();
  const pending = AiRoutineState.beginCapture(state, { identity: { run: { run_id: 'run_x' } }, videoCapture: 'CHROMASCAPE_WINDOW' });
  assert.ok(pending.idempotencyKey);
  assert.strictEqual(pending.payload.videoCapture, 'CHROMASCAPE_WINDOW');
  assert.strictEqual(state.capturePending.idempotencyKey, pending.idempotencyKey);
});

test('beginComplete persists the full completion payload and key', () => {
  const state = AiRoutineState.defaults();
  const pending = AiRoutineState.beginComplete(state, { finalTick: 42, tickSequence: 3, actionSequence: 2, videoSha256: 'sha256:abc' });
  assert.ok(pending.idempotencyKey);
  assert.strictEqual(pending.payload.tickSequence, 3);
  AiRoutineState.acceptComplete(state, { completion_status: 'COMPLETED' });
  assert.strictEqual(state.completePending, null);
  assert.strictEqual(state.captured, true);
});

test('a completed START operation does not acknowledge a pending completion', () => {
  const state = AiRoutineState.defaults();
  AiRoutineState.beginComplete(state, { finalTick: 42, tickSequence: 0, actionSequence: 0 });
  AiRoutineState.acceptComplete(state, {
    completion_status: 'RECORDING',
    operation: { kind: 'START', state: 'SUCCEEDED' }
  });
  assert.ok(state.completePending, 'the original completion payload must remain replayable');
});

test('bindRun resets the SSE cursor to zero when a new conversation is created', () => {
  const state = AiRoutineState.defaults();
  state.conversationId = 'conv_old';
  state.lastEventId = 37;
  AiRoutineState.bindRun(state, { run_id: 'run_x', identity: { run: { run_id: 'run_x' } } }, 'conv_new');
  assert.strictEqual(state.conversationId, 'conv_new');
  assert.strictEqual(state.lastEventId, 0, 'a new conversation must reset the SSE cursor');
});

test('bindRun keeps the SSE cursor when the same conversation is replayed', () => {
  const state = AiRoutineState.defaults();
  state.conversationId = 'conv_x';
  state.lastEventId = 37;
  AiRoutineState.bindRun(state, { run_id: 'run_x' }, 'conv_x');
  assert.strictEqual(state.lastEventId, 37, 'replaying the same conversation must keep the cursor');
});

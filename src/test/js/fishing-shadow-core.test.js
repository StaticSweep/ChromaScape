'use strict';

const { test } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

const core = require(
  path.resolve(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'fishing-shadow-core.js')
);

function decision(sessionId, tick, status = 'PREDICTED') {
  return { type: 'AI_PLAYER_SHADOW_DECISION', status, sessionId, tick };
}

function emptyState() {
  return core.createEmptyShadowState();
}

function acceptLive(state, data, filter) {
  return core.mergeLiveDecisionIntoState(state, data, filter);
}

test('createEmptyShadowState returns a fresh, disconnected state', () => {
  const a = core.createEmptyShadowState();
  const b = core.createEmptyShadowState();
  assert.strictEqual(a.latestTick, -1);
  assert.strictEqual(a.latestDecision, null);
  assert.strictEqual(a.processed, 0);
  assert.strictEqual(a.sessionId, null);
  assert.notStrictEqual(a.decisionMap, b.decisionMap);
});

test('mergeHistoryIntoState rejects decisions from a different session', () => {
  const state = emptyState();
  const next = core.mergeHistoryIntoState(
    state,
    [
      decision('session_old', 9000),
      decision('session_new', 1)
    ],
    'session_new'
  );
  // Only the matching-session decision is accepted; the old high tick is rejected.
  assert.strictEqual(next.decisionMap.size, 1);
  assert.strictEqual(next.latestTick, 1);
  assert.strictEqual(next.decisionMap.has('session_old:9000'), false);
  assert.strictEqual(next.decisionMap.has('session_new:1'), true);
});

test('a stale old-session history response can never suppress new live decisions', () => {
  // The UI switches to session_new and receives live tick 2.
  const state = emptyState();
  state.sessionId = 'session_new';
  const live = acceptLive(state, decision('session_new', 2), 'session_new');
  assert.strictEqual(live.accepted, true);
  assert.strictEqual(live.state.latestTick, 2);

  // A delayed history response for session_old (tick 9000) arrives afterwards.
  const merged = core.mergeHistoryIntoState(live.state, [decision('session_old', 9000)], 'session_old');
  // It must not be merged into the tracked session or raise the tick baseline.
  assert.strictEqual(merged.decisionMap.size, 1);
  assert.strictEqual(merged.latestTick, 2);
  assert.strictEqual(merged.latestDecision.tick, 2);
});

test('mergeHistoryIntoState deduplicates by (sessionId, tick)', () => {
  const state = emptyState();
  const next = core.mergeHistoryIntoState(
    state,
    [decision('s1', 1), decision('s1', 1), decision('s1', 2)],
    's1'
  );
  assert.strictEqual(next.decisionMap.size, 2);
  assert.strictEqual(next.processed, 0); // history never bumps counters
});

test('mergeHistoryIntoState keeps the newest decision as latest', () => {
  const state = emptyState();
  const next = core.mergeHistoryIntoState(state, [decision('s1', 5), decision('s1', 9)], 's1');
  assert.strictEqual(next.latestTick, 9);
  assert.strictEqual(next.latestDecision.tick, 9);
});

test('mergeLiveDecisionIntoState rejects a decision for a non-matching session filter', () => {
  const state = emptyState();
  const result = acceptLive(state, decision('other', 1), 'tracked');
  assert.strictEqual(result.accepted, false);
  assert.strictEqual(result.state, state);
  assert.strictEqual(state.decisionMap.size, 0);
});

test('mergeLiveDecisionIntoState rejects decisions not newer than the latest tick', () => {
  let state = emptyState();
  const first = acceptLive(state, decision('s1', 10), null);
  assert.strictEqual(first.accepted, true);
  assert.strictEqual(first.state.latestTick, 10);
  assert.strictEqual(first.state.processed, 1);
  state = first.state;

  const duplicate = acceptLive(state, decision('s1', 10), null);
  assert.strictEqual(duplicate.accepted, false);
  assert.strictEqual(duplicate.state, state);

  const older = acceptLive(state, decision('s1', 9), null);
  assert.strictEqual(older.accepted, false);
  assert.strictEqual(older.state, state);
  assert.strictEqual(state.processed, 1);
});

test('mergeLiveDecisionIntoState advances predicted and abstained counters', () => {
  let state = emptyState();
  const predicted = acceptLive(state, decision('s1', 1, 'PREDICTED'), null);
  assert.strictEqual(predicted.accepted, true);
  const abstained = acceptLive(predicted.state, decision('s1', 2, 'ABSTAINED'), null);
  assert.strictEqual(abstained.accepted, true);
  state = abstained.state;
  assert.strictEqual(state.processed, 2);
  assert.strictEqual(state.predicted, 1);
  assert.strictEqual(state.abstained, 1);
});

test('mergeLiveDecisionIntoState binds the sessionId on the first accepted decision', () => {
  let state = emptyState();
  const result = acceptLive(state, decision('s1', 1), null);
  assert.strictEqual(result.accepted, true);
  assert.strictEqual(result.state.sessionId, 's1');
});

test('mergeLiveDecisionIntoState rejects a message with a missing session ID', () => {
  const state = emptyState();
  const missing = acceptLive(state, { type: 'AI_PLAYER_SHADOW_DECISION', status: 'PREDICTED', tick: 1 }, null);
  assert.strictEqual(missing.accepted, false);
  assert.strictEqual(missing.state, state);

  const blank = acceptLive(state, { type: 'AI_PLAYER_SHADOW_DECISION', status: 'PREDICTED', sessionId: '   ', tick: 1 }, null);
  assert.strictEqual(blank.accepted, false);
  assert.strictEqual(blank.state, state);
  assert.strictEqual(state.processed, 0);
});

test('mergeLiveDecisionIntoState rejects a wrong message type', () => {
  const state = emptyState();
  const result = acceptLive(state, { type: 'SOMETHING_ELSE', sessionId: 's1', status: 'PREDICTED', tick: 1 }, null);
  assert.strictEqual(result.accepted, false);
  assert.strictEqual(result.state, state);
  assert.strictEqual(state.decisionMap.size, 0);
});

test('mergeLiveDecisionIntoState rejects an unknown status without bumping abstained', () => {
  const state = emptyState();
  const result = acceptLive(state, decision('s1', 1, 'SOMETHING_ELSE'), null);
  assert.strictEqual(result.accepted, false);
  assert.strictEqual(result.state, state);
  assert.strictEqual(state.processed, 0);
  assert.strictEqual(state.abstained, 0);
});

test('tick zero is accepted as a valid tick', () => {
  const state = emptyState();
  const result = acceptLive(state, decision('s1', 0), null);
  assert.strictEqual(result.accepted, true);
  assert.strictEqual(result.state.latestTick, 0);
  assert.strictEqual(result.state.processed, 1);
});

test('a negative or non-numeric tick is rejected', () => {
  const state = emptyState();
  const negative = acceptLive(state, decision('s1', -1), null);
  assert.strictEqual(negative.accepted, false);
  const nan = acceptLive(state, decision('s1', 'abc'), null);
  assert.strictEqual(nan.accepted, false);
});

test('duplicate live decisions do not advance counters (no revision for the caller)', () => {
  let state = emptyState();
  const first = acceptLive(state, decision('s1', 5), null);
  assert.strictEqual(first.accepted, true);
  state = first.state;

  const duplicate = acceptLive(state, decision('s1', 5), null);
  assert.strictEqual(duplicate.accepted, false);
  assert.strictEqual(duplicate.state, state);
  assert.strictEqual(state.processed, 1);
  assert.strictEqual(state.predicted, 1);
  assert.strictEqual(state.decisionMap.size, 1);
});

test('history never changes processed/predicted/abstained', () => {
  let state = emptyState();
  const live = acceptLive(state, decision('s1', 3, 'PREDICTED'), null);
  state = live.state;
  const merged = core.mergeHistoryIntoState(
    state,
    [decision('s1', 1), decision('s1', 2, 'ABSTAINED')],
    's1'
  );
  assert.strictEqual(merged.processed, 1);
  assert.strictEqual(merged.predicted, 1);
  assert.strictEqual(merged.abstained, 0);
  assert.strictEqual(merged.decisionMap.size, 3);
});

test('a delayed same-session status cannot lower live counters', () => {
  let state = emptyState();
  const live = acceptLive(state, decision('s1', 2, 'PREDICTED'), null);
  state = live.state;
  assert.strictEqual(state.processed, 1);

  // The status request was issued before the live decision arrived, so its live revision is stale:
  // session counters must not be applied (applySessionCounters=false).
  const staleStatus = {
    activeModelId: 'm1',
    persistenceError: null,
    sessionId: 's1',
    processed: 0,
    predicted: 0,
    abstained: 0,
    latestDecision: null
  };
  const merged = core.mergeStatusIntoState(state, staleStatus, 's1', false);
  assert.strictEqual(merged.processed, 1);
  assert.strictEqual(merged.predicted, 1);
  // Global/current fields still apply.
  assert.strictEqual(merged.activeModelId, 'm1');
});

test('status-before-live ordering keeps counters monotonic', () => {
  let state = emptyState();
  const status = {
    activeModelId: 'm1',
    persistenceError: null,
    sessionId: 's1',
    processed: 5,
    predicted: 3,
    abstained: 2,
    latestDecision: null
  };
  state = core.mergeStatusIntoState(state, status, 's1', true);
  assert.strictEqual(state.processed, 5);
  assert.strictEqual(state.predicted, 3);
  assert.strictEqual(state.abstained, 2);

  const live = acceptLive(state, decision('s1', 6, 'PREDICTED'), null);
  assert.strictEqual(live.accepted, true);
  assert.strictEqual(live.state.processed, 6);
  assert.strictEqual(live.state.predicted, 4);
});

test('live-before-status ordering (delayed status) does not roll back counters', () => {
  let state = emptyState();
  const live = acceptLive(state, decision('s1', 6, 'PREDICTED'), null);
  state = live.state;

  const status = {
    activeModelId: 'm1',
    persistenceError: null,
    sessionId: 's1',
    processed: 5,
    predicted: 3,
    abstained: 2,
    latestDecision: null
  };
  const merged = core.mergeStatusIntoState(state, status, 's1', false);
  assert.strictEqual(merged.processed, 1);
  assert.strictEqual(merged.predicted, 1);
  assert.strictEqual(merged.abstained, 0);
  assert.strictEqual(merged.latestTick, 6);
});

test('a stale old-session status cannot apply counters to the tracked session', () => {
  let state = emptyState();
  const live = acceptLive(state, decision('new', 2, 'PREDICTED'), null);
  state = live.state;

  const oldStatus = {
    activeModelId: null,
    persistenceError: null,
    sessionId: 'old',
    processed: 99,
    predicted: 99,
    abstained: 99,
    latestDecision: decision('old', 9000, 'PREDICTED')
  };
  const merged = core.mergeStatusIntoState(state, oldStatus, 'old', true);
  assert.strictEqual(merged.processed, 1);
  assert.strictEqual(merged.predicted, 1);
  assert.strictEqual(merged.latestTick, 2);
  assert.strictEqual(merged.decisionMap.size, 1);
});

test('status hydrates the latest decision into the tracked session when matching', () => {
  let state = emptyState();
  const status = {
    activeModelId: 'm1',
    persistenceError: null,
    sessionId: 's1',
    processed: 5,
    predicted: 3,
    abstained: 2,
    latestDecision: decision('s1', 42, 'ABSTAINED')
  };
  state = core.mergeStatusIntoState(state, status, 's1', true);
  assert.strictEqual(state.latestTick, 42);
  assert.strictEqual(state.latestDecision.tick, 42);
  assert.strictEqual(state.decisionMap.has('s1:42'), true);
});

test('switching sessions resets the shadow state (revision and counters)', () => {
  let state = emptyState();
  const live = acceptLive(state, decision('old', 9, 'PREDICTED'), null);
  state = live.state;
  assert.strictEqual(state.processed, 1);

  const fresh = core.switchShadowSessionState('new');
  assert.strictEqual(fresh.sessionId, 'new');
  assert.strictEqual(fresh.latestTick, -1);
  assert.strictEqual(fresh.latestDecision, null);
  assert.strictEqual(fresh.decisions.length, 0);
  assert.strictEqual(fresh.decisionMap.size, 0);
  assert.strictEqual(fresh.processed, 0);
  assert.strictEqual(fresh.predicted, 0);
  assert.strictEqual(fresh.abstained, 0);
  assert.strictEqual(fresh.persistenceError, null);

  const freshNull = core.switchShadowSessionState(null);
  assert.strictEqual(freshNull.sessionId, null);
});

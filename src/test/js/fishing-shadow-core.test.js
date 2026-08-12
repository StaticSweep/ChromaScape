'use strict';

const { test } = require('node:test');
const assert = require('node:assert');
const path = require('node:path');

const core = require(
  path.resolve(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'fishing-shadow-core.js')
);

function decision(sessionId, shadowRunId, tick, status = 'PREDICTED') {
  return { type: 'AI_PLAYER_SHADOW_DECISION', status, sessionId, shadowRunId, tick };
}

function emptyState() {
  return core.createEmptyShadowState();
}

function acceptLive(state, data, filter) {
  return core.mergeLiveDecisionIntoState(state, data, filter);
}

function persistenceStatus(overrides) {
  return Object.assign({
    activeModelId: 'm1',
    persistenceError: null,
    persistenceLifecycle: 'RUNNING',
    persistenceQueueSize: 0,
    persistenceEnqueued: 12,
    persistenceQueued: 0,
    persistenceInFlight: 0,
    persistenceCompleted: 12,
    persistenceWritten: 11,
    persistenceFailed: 1,
    persistenceAbandoned: 0,
    persistenceDropped: 0,
    sessionId: null,
    shadowRunId: null,
    processed: 0,
    predicted: 0,
    abstained: 0,
    latestDecision: null
  }, overrides || {});
}

test('createEmptyShadowState returns a fresh, disconnected state', () => {
  const a = core.createEmptyShadowState();
  const b = core.createEmptyShadowState();
  assert.strictEqual(a.latestTick, -1);
  assert.strictEqual(a.latestDecision, null);
  assert.strictEqual(a.processed, 0);
  assert.strictEqual(a.sessionId, null);
  assert.strictEqual(a.shadowRunId, null);
  assert.strictEqual(a.persistenceLifecycle, null);
  assert.strictEqual(a.persistenceWritten, 0);
  assert.notStrictEqual(a.decisionMap, b.decisionMap);
});

// ---- History merge ----

test('mergeHistoryIntoState rejects decisions from a different session', () => {
  const state = emptyState();
  const next = core.mergeHistoryIntoState(
    state,
    [
      decision('session_old', 'run_old', 9000),
      decision('session_new', 'run_new', 1)
    ],
    'session_new',
    'run_new'
  );
  // Only the matching-session decision is accepted; the old high tick is rejected.
  assert.strictEqual(next.decisionMap.size, 1);
  assert.strictEqual(next.latestTick, 1);
  assert.strictEqual(next.decisionMap.has('run_old:9000'), false);
  assert.strictEqual(next.decisionMap.has('run_new:1'), true);
});

test('same session with different runs do not deduplicate each other', () => {
  // run_a receives tick 5.
  let state = core.switchShadowSessionState('session_new', 'run_a');
  const live = acceptLive(state, decision('session_new', 'run_a', 5), 'session_new');
  assert.strictEqual(live.accepted, true);
  assert.strictEqual(live.state.decisionMap.size, 1);

  // A delayed history response for run_b (same session, tick 5) is rejected.
  const merged = core.mergeHistoryIntoState(
    live.state,
    [decision('session_new', 'run_b', 5)],
    'session_new',
    'run_b'
  );
  assert.strictEqual(merged.decisionMap.size, 1);
  assert.strictEqual(merged.latestTick, 5);
  assert.strictEqual(merged.decisionMap.has('run_a:5'), true);
  assert.strictEqual(merged.decisionMap.has('run_b:5'), false);
});

test('a stale old-session history response can never suppress new live decisions', () => {
  // The UI switches to session_new/run_new and receives live tick 2.
  const state = core.switchShadowSessionState('session_new', 'run_new');
  const live = acceptLive(state, decision('session_new', 'run_new', 2), 'session_new');
  assert.strictEqual(live.accepted, true);
  assert.strictEqual(live.state.latestTick, 2);

  // A delayed history response for session_old (tick 9000) arrives afterwards.
  const merged = core.mergeHistoryIntoState(
    live.state,
    [decision('session_old', 'run_old', 9000)],
    'session_old',
    'run_old'
  );
  // It must not be merged into the tracked session or raise the tick baseline.
  assert.strictEqual(merged.decisionMap.size, 1);
  assert.strictEqual(merged.latestTick, 2);
  assert.strictEqual(merged.latestDecision.tick, 2);
});

test('old-run high ticks cannot suppress new-run tick zero', () => {
  // An old run of the same session reached tick 9000.
  const state = core.switchShadowSessionState('s1', 'run_old');
  const old = acceptLive(state, decision('s1', 'run_old', 9000), null);
  assert.strictEqual(old.accepted, true);

  // A fresh state for a new run must accept tick zero.
  const fresh = core.switchShadowSessionState('s1', 'run_new');
  const zero = acceptLive(fresh, decision('s1', 'run_new', 0), null);
  assert.strictEqual(zero.accepted, true);
  assert.strictEqual(zero.state.latestTick, 0);
  assert.strictEqual(zero.state.latestDecision.tick, 0);
});

test('history rejects mismatched run ids', () => {
  const state = core.switchShadowSessionState('s1', 'run_a');
  // Requested run differs from the decision's run: rejected.
  const mismatched = core.mergeHistoryIntoState(
    state,
    [decision('s1', 'run_b', 1)],
    's1',
    'run_a'
  );
  assert.strictEqual(mismatched.decisionMap.size, 0);
  assert.strictEqual(mismatched.latestTick, -1);
});

test('legacy history never merges into a run-keyed state', () => {
  // A legacy decision (null run id) must never merge into a run-keyed state.
  const state = core.switchShadowSessionState('s1', 'run_a');
  const merged = core.mergeHistoryIntoState(
    state,
    [decision('s1', null, 9000)],
    's1',
    null
  );
  assert.strictEqual(merged.decisionMap.size, 0);
  assert.strictEqual(merged.latestTick, -1);
});

test('mergeHistoryIntoState deduplicates by (shadowRunId, tick)', () => {
  const state = emptyState();
  const next = core.mergeHistoryIntoState(
    state,
    [decision('s1', 'r1', 1), decision('s1', 'r1', 1), decision('s1', 'r1', 2)],
    's1',
    'r1'
  );
  assert.strictEqual(next.decisionMap.size, 2);
  assert.strictEqual(next.processed, 0); // history never bumps counters
});

test('mergeHistoryIntoState keeps the newest decision as latest', () => {
  const state = emptyState();
  const next = core.mergeHistoryIntoState(
    state,
    [decision('s1', 'r1', 5), decision('s1', 'r1', 9)],
    's1',
    'r1'
  );
  assert.strictEqual(next.latestTick, 9);
  assert.strictEqual(next.latestDecision.tick, 9);
});

// ---- Live decision merge ----

test('mergeLiveDecisionIntoState rejects a decision for a non-matching session filter', () => {
  const state = emptyState();
  const result = acceptLive(state, decision('other', 'r1', 1), 'tracked');
  assert.strictEqual(result.accepted, false);
  assert.strictEqual(result.state, state);
  assert.strictEqual(state.decisionMap.size, 0);
});

test('mergeLiveDecisionIntoState rejects a decision for a non-matching tracked run', () => {
  let state = core.switchShadowSessionState('s1', 'run_a');
  const first = acceptLive(state, decision('s1', 'run_a', 1), null);
  assert.strictEqual(first.accepted, true);
  state = first.state;

  // A live decision from run_b of the same session must not bind or pollute run_a.
  const wrongRun = acceptLive(state, decision('s1', 'run_b', 2), null);
  assert.strictEqual(wrongRun.accepted, false);
  assert.strictEqual(wrongRun.state, state);
  assert.strictEqual(state.shadowRunId, 'run_a');
});

test('mergeLiveDecisionIntoState rejects decisions not newer than the latest tick', () => {
  let state = emptyState();
  const first = acceptLive(state, decision('s1', 'r1', 10), null);
  assert.strictEqual(first.accepted, true);
  assert.strictEqual(first.state.latestTick, 10);
  assert.strictEqual(first.state.processed, 1);
  state = first.state;

  const duplicate = acceptLive(state, decision('s1', 'r1', 10), null);
  assert.strictEqual(duplicate.accepted, false);
  assert.strictEqual(duplicate.state, state);

  const older = acceptLive(state, decision('s1', 'r1', 9), null);
  assert.strictEqual(older.accepted, false);
  assert.strictEqual(older.state, state);
  assert.strictEqual(state.processed, 1);
});

test('mergeLiveDecisionIntoState advances predicted and abstained counters', () => {
  let state = emptyState();
  const predicted = acceptLive(state, decision('s1', 'r1', 1, 'PREDICTED'), null);
  assert.strictEqual(predicted.accepted, true);
  const abstained = acceptLive(predicted.state, decision('s1', 'r1', 2, 'ABSTAINED'), null);
  assert.strictEqual(abstained.accepted, true);
  state = abstained.state;
  assert.strictEqual(state.processed, 2);
  assert.strictEqual(state.predicted, 1);
  assert.strictEqual(state.abstained, 1);
});

test('mergeLiveDecisionIntoState binds the sessionId and shadowRunId on the first accepted decision', () => {
  let state = emptyState();
  const result = acceptLive(state, decision('s1', 'run_x', 1), null);
  assert.strictEqual(result.accepted, true);
  assert.strictEqual(result.state.sessionId, 's1');
  assert.strictEqual(result.state.shadowRunId, 'run_x');
});

test('mergeLiveDecisionIntoState rejects a message with a missing session or run ID', () => {
  const state = emptyState();
  const missingRun = acceptLive(state, { type: 'AI_PLAYER_SHADOW_DECISION', status: 'PREDICTED', sessionId: 's1', tick: 1 }, null);
  assert.strictEqual(missingRun.accepted, false);
  assert.strictEqual(missingRun.state, state);

  const missingSession = acceptLive(state, { type: 'AI_PLAYER_SHADOW_DECISION', status: 'PREDICTED', shadowRunId: 'r1', tick: 1 }, null);
  assert.strictEqual(missingSession.accepted, false);
  assert.strictEqual(missingSession.state, state);
  assert.strictEqual(state.processed, 0);
});

test('mergeLiveDecisionIntoState rejects a wrong message type', () => {
  const state = emptyState();
  const result = acceptLive(state, { type: 'SOMETHING_ELSE', sessionId: 's1', shadowRunId: 'r1', status: 'PREDICTED', tick: 1 }, null);
  assert.strictEqual(result.accepted, false);
  assert.strictEqual(result.state, state);
  assert.strictEqual(state.decisionMap.size, 0);
});

test('mergeLiveDecisionIntoState rejects an unknown status without bumping abstained', () => {
  const state = emptyState();
  const result = acceptLive(state, decision('s1', 'r1', 1, 'SOMETHING_ELSE'), null);
  assert.strictEqual(result.accepted, false);
  assert.strictEqual(result.state, state);
  assert.strictEqual(state.processed, 0);
  assert.strictEqual(state.abstained, 0);
});

test('tick zero is accepted as a valid tick', () => {
  const state = emptyState();
  const result = acceptLive(state, decision('s1', 'r1', 0), null);
  assert.strictEqual(result.accepted, true);
  assert.strictEqual(result.state.latestTick, 0);
  assert.strictEqual(result.state.processed, 1);
});

test('a negative or non-numeric tick is rejected', () => {
  const state = emptyState();
  const negative = acceptLive(state, decision('s1', 'r1', -1), null);
  assert.strictEqual(negative.accepted, false);
  const nan = acceptLive(state, decision('s1', 'r1', 'abc'), null);
  assert.strictEqual(nan.accepted, false);
});

test('duplicate live decisions do not advance counters (no revision for the caller)', () => {
  let state = emptyState();
  const first = acceptLive(state, decision('s1', 'r1', 5), null);
  assert.strictEqual(first.accepted, true);
  state = first.state;

  const duplicate = acceptLive(state, decision('s1', 'r1', 5), null);
  assert.strictEqual(duplicate.accepted, false);
  assert.strictEqual(duplicate.state, state);
  assert.strictEqual(state.processed, 1);
  assert.strictEqual(state.predicted, 1);
  assert.strictEqual(state.decisionMap.size, 1);
});

test('history never changes processed/predicted/abstained', () => {
  let state = emptyState();
  const live = acceptLive(state, decision('s1', 'r1', 3, 'PREDICTED'), null);
  state = live.state;
  const merged = core.mergeHistoryIntoState(
    state,
    [decision('s1', 'r1', 1), decision('s1', 'r1', 2, 'ABSTAINED')],
    's1',
    'r1'
  );
  assert.strictEqual(merged.processed, 1);
  assert.strictEqual(merged.predicted, 1);
  assert.strictEqual(merged.abstained, 0);
  assert.strictEqual(merged.decisionMap.size, 3);
});

// ---- Status merge (unbound/generic startup) ----

test('status without a target session or run does not hydrate counters', () => {
  const state = emptyState();
  const status = persistenceStatus({ processed: 5, predicted: 3, abstained: 2, sessionId: 's1', shadowRunId: 'r1' });
  const next = core.mergeStatusIntoState(state, status, null, null, true);
  assert.strictEqual(next.processed, 0);
  assert.strictEqual(next.predicted, 0);
  assert.strictEqual(next.abstained, 0);
  // The generic response never binds a session or run.
  assert.strictEqual(next.sessionId, null);
  assert.strictEqual(next.shadowRunId, null);
});

test('status without a target session or run does not raise latestTick', () => {
  const state = emptyState();
  const status = persistenceStatus({
    sessionId: 's1',
    shadowRunId: 'r1',
    latestDecision: decision('s1', 'r1', 9000, 'PREDICTED')
  });
  const next = core.mergeStatusIntoState(state, status, null, null, true);
  assert.strictEqual(next.latestTick, -1);
  assert.strictEqual(next.latestDecision, null);
  assert.strictEqual(next.decisionMap.size, 0);
});

test('exact status merge requires both identifiers', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({ processed: 5, sessionId: 's1', shadowRunId: 'r1' });
  // Missing run id on the request side: counters must not apply.
  const noRun = core.mergeStatusIntoState(state, status, 's1', null, true);
  assert.strictEqual(noRun.processed, 0);
  // Missing session id on the request side: counters must not apply.
  const noSession = core.mergeStatusIntoState(state, status, null, 'r1', true);
  assert.strictEqual(noSession.processed, 0);
});

test('a backend session differing from the requested target is rejected', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({ processed: 5, sessionId: 's2', shadowRunId: 'r1' });
  const next = core.mergeStatusIntoState(state, status, 's1', 'r1', true);
  assert.strictEqual(next.processed, 0);
  assert.strictEqual(next.latestTick, -1);
});

test('a backend run differing from the requested target is rejected', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({ processed: 5, sessionId: 's1', shadowRunId: 'r2' });
  const next = core.mergeStatusIntoState(state, status, 's1', 'r1', true);
  assert.strictEqual(next.processed, 0);
  assert.strictEqual(next.latestTick, -1);
});

test('a target differing from the currently tracked session or run is rejected', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({ processed: 5, sessionId: 's2', shadowRunId: 'r2' });
  const next = core.mergeStatusIntoState(state, status, 's2', 'r2', true);
  assert.strictEqual(next.processed, 0);
  assert.strictEqual(next.latestTick, -1);
});

test('matching target/status/state hydrates counters', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({
    sessionId: 's1',
    shadowRunId: 'r1',
    processed: 5,
    predicted: 3,
    abstained: 2,
    latestDecision: decision('s1', 'r1', 42, 'ABSTAINED')
  });
  const next = core.mergeStatusIntoState(state, status, 's1', 'r1', true);
  assert.strictEqual(next.processed, 5);
  assert.strictEqual(next.predicted, 3);
  assert.strictEqual(next.abstained, 2);
  assert.strictEqual(next.latestTick, 42);
  assert.strictEqual(next.latestDecision.tick, 42);
  assert.strictEqual(next.decisionMap.has('r1:42'), true);
});

test('stale live revision preserves local counters while global fields still apply', () => {
  let state = core.switchShadowSessionState('s1', 'r1');
  const live = acceptLive(state, decision('s1', 'r1', 2, 'PREDICTED'), null);
  state = live.state;
  assert.strictEqual(state.processed, 1);

  const staleStatus = persistenceStatus({
    sessionId: 's1',
    shadowRunId: 'r1',
    processed: 0,
    predicted: 0,
    abstained: 0,
    latestDecision: null
  });
  const merged = core.mergeStatusIntoState(state, staleStatus, 's1', 'r1', false);
  assert.strictEqual(merged.processed, 1);
  assert.strictEqual(merged.predicted, 1);
  // Global/current fields still apply.
  assert.strictEqual(merged.activeModelId, 'm1');
});

test('global model fields apply despite rejected session fields', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({
    activeModelId: 'm2',
    readyForInference: true,
    activationState: 'ACTIVE',
    sessionId: 'other',
    shadowRunId: 'r_other',
    processed: 99
  });
  const next = core.mergeStatusIntoState(state, status, 's1', 'r1', true);
  assert.strictEqual(next.activeModelId, 'm2');
  assert.strictEqual(next.readyForInference, true);
  assert.strictEqual(next.activationState, 'ACTIVE');
  // Session counters rejected.
  assert.strictEqual(next.processed, 0);
});

test('global persistence fields apply despite rejected session fields', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({
    sessionId: 'other',
    shadowRunId: 'r_other',
    processed: 99,
    persistenceLifecycle: 'RUNNING',
    persistenceWritten: 11,
    persistenceFailed: 1,
    persistenceDropped: 0
  });
  const next = core.mergeStatusIntoState(state, status, 's1', 'r1', true);
  assert.strictEqual(next.persistenceLifecycle, 'RUNNING');
  assert.strictEqual(next.persistenceWritten, 11);
  assert.strictEqual(next.persistenceFailed, 1);
  assert.strictEqual(next.persistenceDropped, 0);
  assert.strictEqual(next.processed, 0);
});

test('persistence abandoned metric renders safely', () => {
  const state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({
    sessionId: 's1',
    shadowRunId: 'r1',
    processed: 0,
    persistenceAbandoned: 3,
    persistenceError: 'SHADOW_PERSISTENCE_WRITER_FAILED'
  });
  const next = core.mergeStatusIntoState(state, status, 's1', 'r1', true);
  assert.strictEqual(next.persistenceAbandoned, 3);
  assert.strictEqual(next.persistenceError, 'SHADOW_PERSISTENCE_WRITER_FAILED');
});

test('switching sessions and runs resets all session-scoped data', () => {
  let state = core.switchShadowSessionState('old', 'run_old');
  const live = acceptLive(state, decision('old', 'run_old', 9, 'PREDICTED'), null);
  state = live.state;
  state = core.mergeStatusIntoState(state, persistenceStatus({ persistenceError: 'SHADOW_PERSISTENCE_WRITE_FAILED' }), null, null, false);
  assert.strictEqual(state.processed, 1);
  assert.strictEqual(state.persistenceError, 'SHADOW_PERSISTENCE_WRITE_FAILED');

  const fresh = core.switchShadowSessionState('new', 'run_new');
  assert.strictEqual(fresh.sessionId, 'new');
  assert.strictEqual(fresh.shadowRunId, 'run_new');
  assert.strictEqual(fresh.latestTick, -1);
  assert.strictEqual(fresh.latestDecision, null);
  assert.strictEqual(fresh.decisions.length, 0);
  assert.strictEqual(fresh.decisionMap.size, 0);
  assert.strictEqual(fresh.processed, 0);
  assert.strictEqual(fresh.predicted, 0);
  assert.strictEqual(fresh.abstained, 0);
  assert.strictEqual(fresh.persistenceError, null);
  assert.strictEqual(fresh.persistenceWritten, 0);
});

test('old run history/status responses cannot affect the new run', () => {
  let state = core.switchShadowSessionState('new', 'run_new');
  const live = acceptLive(state, decision('new', 'run_new', 2, 'PREDICTED'), null);
  state = live.state;

  // A delayed status response for the old run is rejected entirely.
  const oldStatus = persistenceStatus({
    sessionId: 'new',
    shadowRunId: 'run_old',
    processed: 99,
    latestDecision: decision('new', 'run_old', 9000, 'PREDICTED')
  });
  const withStatus = core.mergeStatusIntoState(state, oldStatus, 'new', 'run_old', true);
  assert.strictEqual(withStatus.processed, 1);
  assert.strictEqual(withStatus.latestTick, 2);
  assert.strictEqual(withStatus.decisionMap.size, 1);

  // A delayed history response for the old run is rejected entirely.
  const withHistory = core.mergeHistoryIntoState(state, [decision('new', 'run_old', 9000)], 'new', 'run_old');
  assert.strictEqual(withHistory.latestTick, 2);
  assert.strictEqual(withHistory.decisionMap.size, 1);
});

test('status-before-live ordering keeps counters monotonic', () => {
  let state = core.switchShadowSessionState('s1', 'r1');
  const status = persistenceStatus({
    sessionId: 's1',
    shadowRunId: 'r1',
    processed: 5,
    predicted: 3,
    abstained: 2,
    latestDecision: null
  });
  state = core.mergeStatusIntoState(state, status, 's1', 'r1', true);
  assert.strictEqual(state.processed, 5);
  assert.strictEqual(state.predicted, 3);
  assert.strictEqual(state.abstained, 2);

  const live = acceptLive(state, decision('s1', 'r1', 6, 'PREDICTED'), null);
  assert.strictEqual(live.accepted, true);
  assert.strictEqual(live.state.processed, 6);
  assert.strictEqual(live.state.predicted, 4);
});

test('live-before-status ordering (delayed status) does not roll back counters', () => {
  let state = core.switchShadowSessionState('s1', 'r1');
  const live = acceptLive(state, decision('s1', 'r1', 6, 'PREDICTED'), null);
  state = live.state;

  const status = persistenceStatus({
    sessionId: 's1',
    shadowRunId: 'r1',
    processed: 5,
    predicted: 3,
    abstained: 2,
    latestDecision: null
  });
  const merged = core.mergeStatusIntoState(state, status, 's1', 'r1', false);
  assert.strictEqual(merged.processed, 1);
  assert.strictEqual(merged.predicted, 1);
  assert.strictEqual(merged.abstained, 0);
  assert.strictEqual(merged.latestTick, 6);
});

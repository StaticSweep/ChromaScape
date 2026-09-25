const test = require('node:test');
const assert = require('node:assert/strict');

const core = require('../../main/resources/static/js/debugger-session-sync-core.js');

test('SESSION_CREATED identity includes window/account/session/run and deduplicates exact repeats', () => {
  const first = core.sessionCreatedKey('window-1', 'Account', 'debug_1', 'shadow_1');
  const same = core.sessionCreatedKey('window-1', ' account ', 'debug_1', 'shadow_1');
  const otherRun = core.sessionCreatedKey('window-1', 'account', 'debug_1', 'shadow_2');

  assert.equal(core.isDuplicateSessionCreated(first, same), true);
  assert.equal(core.isDuplicateSessionCreated(first, otherRun), false);
});

test('stale generations and aborted requests cannot apply', () => {
  const controller = new AbortController();
  assert.equal(core.acceptsGeneration(2, 2, controller.signal, 'debug_1', 'debug_1', 'shadow_1', 'shadow_1'), true);
  assert.equal(core.acceptsGeneration(1, 2, controller.signal, 'debug_1', 'debug_1', 'shadow_1', 'shadow_1'), false);
  controller.abort();
  assert.equal(core.acceptsGeneration(2, 2, controller.signal, 'debug_1', 'debug_1', 'shadow_1', 'shadow_1'), false);
});

test('recording transitions are generation-aware and notifications are once per id', () => {
  const active = core.recordingTransition(null, false, 'rec_1', true);
  assert.equal(active.changed, true);
  assert.equal(active.becameActive, true);
  assert.equal(active.recordingChanged, true);

  const unchanged = core.recordingTransition('rec_1', true, 'rec_1', true);
  assert.equal(unchanged.changed, false);
  assert.equal(core.shouldNotifyRecording('rec_1', 'rec_1'), false);
  assert.equal(core.shouldNotifyRecording(null, 'rec_1'), true);

  const ended = core.recordingTransition('rec_1', true, null, false);
  assert.equal(ended.becameInactive, true);
});

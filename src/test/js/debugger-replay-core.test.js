const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const core = require('../../main/resources/static/js/debugger-replay-core.js');

test('recovery subtracts only the recovered interval from a truncated window', () => {
  assert.deepEqual(
    core.subtractTruncatedWindows([{ start: 0, end: 100, cursor: 'wide' }], 0, 50),
    [{ start: 51, end: 100, cursor: null }]
  );
  assert.deepEqual(
    core.subtractTruncatedWindows([{ start: 0, end: 100, cursor: 'wide' }], 50, 70),
    [
      { start: 0, end: 49, cursor: null },
      { start: 71, end: 100, cursor: null }
    ]
  );
  assert.deepEqual(
    core.subtractTruncatedWindows([{ start: 0, end: 50 }], 0, 50),
    []
  );
});

test('single-tick packet recovery retains its continuation cursor at the page cap', () => {
  const page = { hasMore: true, cursor: 'tick=42|source-a|rsprox-packet/v2|19|88' };
  assert.deepEqual(core.nextPacketPage(page, 0), {
    done: false,
    truncated: false,
    cursor: page.cursor
  });
  assert.deepEqual(core.nextPacketPage(page, 19), {
    done: true,
    truncated: true,
    cursor: page.cursor
  });
  assert.deepEqual(core.nextPacketPage({ hasMore: false, cursor: '' }, 19), {
    done: true,
    truncated: false,
    cursor: null
  });
});

test('duplicate in-flight recovery requests share one promise and release after settlement', async () => {
  const registry = core.createInFlightRegistry();
  let calls = 0;
  let resolve;
  const pending = new Promise(done => { resolve = done; });
  const first = registry.ensure('recording-a:42:42', () => {
    calls++;
    return pending;
  });
  const duplicate = registry.ensure('recording-a:42:42', () => {
    calls++;
    return Promise.resolve('wrong duplicate');
  });

  assert.strictEqual(first, duplicate);
  assert.equal(registry.has('recording-a:42:42'), true);
  await Promise.resolve();
  assert.equal(calls, 1);
  resolve('loaded');
  assert.equal(await first, 'loaded');
  await Promise.resolve();
  assert.equal(registry.has('recording-a:42:42'), false);
});

test('failed recovery releases the window so the explicit retry can run again', async () => {
  const registry = core.createInFlightRegistry();
  let attempts = 0;
  await assert.rejects(
    registry.ensure('recording-a:10:10', () => {
      attempts++;
      return Promise.reject(new Error('temporary packet failure'));
    }),
    /temporary packet failure/
  );
  assert.equal(registry.has('recording-a:10:10'), false);

  await registry.ensure('recording-a:10:10', () => {
    attempts++;
    return Promise.resolve('retry succeeded');
  });
  assert.equal(attempts, 2);
});

test('stale recording responses are rejected by generation and recording identity', () => {
  assert.equal(core.acceptsReplayResponse('replay', 2, 2, 'recording-new', 'recording-new'), true);
  assert.equal(core.acceptsReplayResponse('replay', 1, 2, 'recording-new', 'recording-new'), false);
  assert.equal(core.acceptsReplayResponse('replay', 2, 2, 'recording-new', 'recording-old'), false);
  assert.equal(core.acceptsReplayResponse('live', 2, 2, 'recording-new', 'recording-new'), false);
  assert.equal(core.acceptsReplayResponse('replay', 2, 2, 'recording-new', 'recording-new', true), false);
});

test('observation and capture identities remain distinct in packet display', () => {
  assert.deepEqual(
    core.packetDisplayIdentity({ schema: 'rsprox-packet/v2', sequence: 77, packetSequence: 4812 }),
    { observationSequence: 77, captureSequence: 4812 }
  );
  assert.deepEqual(
    core.packetDisplayIdentity({ schema: 'rsprox-packet/v2', sequence: 78, decodeStatus: 'GAP' }),
    { observationSequence: 78, captureSequence: null }
  );
  assert.deepEqual(
    core.packetDisplayIdentity({ schema: 'rsprox-packet/v1', sequence: 19 }),
    { observationSequence: 19, captureSequence: 19 }
  );
});

test('debugger page wires the tested recovery guards and retry UI to production code', () => {
  const root = path.resolve(__dirname, '../../..');
  const script = fs.readFileSync(
    path.join(root, 'src/main/resources/static/js/debugger.js'), 'utf8');
  const template = fs.readFileSync(
    path.join(root, 'src/main/resources/templates/debugger.html'), 'utf8');
  assert.match(template, /debugger-replay-core\.js/);
  assert.match(script, /nextPacketPage/);
  assert.match(script, /createInFlightRegistry/);
  assert.match(script, /setReplayPacketLoadError/);
  assert.match(script, /packetDisplayIdentity/);
  assert.match(script, /forceReload: true/);
});

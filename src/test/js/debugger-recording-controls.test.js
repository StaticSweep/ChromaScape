const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/debugger.js'), 'utf8');
function section(from, to) {
  const start = script.indexOf(from);
  const end = script.indexOf(to, start);
  assert.ok(start >= 0 && end > start);
  return script.slice(start, end);
}
const controls = section('async function startRecording()', 'async function stopRecording()')
  + section('async function checkRecordingStatus()', 'function startRecordingDurationTimer()');
const watch = section('async function initDesktopWatchControls()', 'function updateDesktopWatchUi()');
const liveBinding = section('function bindRecorderSessionFromLiveTick(', 'async function selectWindowInternal(');

function element() {
  const classes = new Set();
  return {
    textContent: '', disabled: false, title: '',
    addEventListener() {},
    classList: { add(name) { classes.add(name); }, remove(name) { classes.delete(name); } },
    classes
  };
}

function recorder(fetch) {
  const alerts = [];
  const context = vm.createContext({
    fetch, AbortController, console: { log() {}, warn() {}, error() {} },
    elements: { recordBtn: element(), recordBtnText: element(), recordingStatus: element() },
    currentMode: 'live', currentDebugSessionId: 'selected-live-session',
    isDebugSessionActive: true, pendingWindowSessionRebind: false, currentWindowId: 'selected-window',
    recordingStartPending: false, recordingStatusGeneration: 0, recordingStatusAbortController: null,
    recordingStatusRequest: null, currentSessionId: null, currentDebugSession: null,
    isRecording: false, currentRecordingId: null, recordingNotificationId: null, recordingStartTime: null,
    debugFeedEnabled: true, alerts, stoppedTimers: 0,
    alert(message) { alerts.push(message); }, confirm() { return false; },
    showNotification() {}, fishingShadowSwitchSession() {}, startRecordingDurationTimer() {},
    stopRecordingDurationTimer() { context.stoppedTimers++; }
  });
  vm.runInContext(controls, context);
  return context;
}

test('Record targets the selected live recorder session instead of an idle browser socket', async () => {
  const calls = [];
  const context = recorder(async (url, options) => {
    calls.push({ url, body: JSON.parse(options.body) });
    return { ok: true, json: async () => ({ sessionId: 'selected-live-session', recordingId: 'recording-real' }) };
  });
  await context.startRecording();
  assert.equal(calls.length, 1);
  assert.equal(calls[0].body.sessionId, 'selected-live-session');
  assert.equal(context.currentSessionId, 'selected-live-session');
  assert.equal(context.currentRecordingId, 'recording-real');
  assert.equal(context.isRecording, true);
  assert.equal(context.elements.recordBtnText.textContent, 'Stop Recording');
  assert.equal(context.elements.recordBtn.disabled, false);
});

test('Record cannot target a replay, a missing producer or a previous window binding', async () => {
  for (const state of [
    { currentMode: 'replay' },
    { currentDebugSessionId: null },
    { isDebugSessionActive: false },
    { pendingWindowSessionRebind: true }
  ]) {
    let calls = 0;
    const context = recorder(async () => { calls++; throw Error('must not reach the server'); });
    Object.assign(context, state);
    await context.startRecording();
    assert.equal(calls, 0);
    assert.equal(context.isRecording, false);
    assert.equal(context.elements.recordBtnText.textContent, 'Start Recording');
    assert.equal(context.elements.recordBtn.disabled, false);
    assert.match(context.alerts[0], /live session/);
  }
});

test('a pending start suppresses duplicate clicks and provisional recording polls', async () => {
  let resolveStart;
  const calls = [];
  const context = recorder((url) => {
    calls.push(url);
    return new Promise(resolve => { resolveStart = resolve; });
  });
  const first = context.startRecording();
  await context.startRecording();
  await context.checkRecordingStatus();
  assert.equal(calls.length, 1);
  assert.equal(context.elements.recordBtn.disabled, true);
  resolveStart({ ok: false, status: 503, json: async () => ({ message: 'RECORDER_TICK_TIMEOUT' }) });
  await first;
  assert.equal(context.elements.recordBtnText.textContent, 'Start Recording');
  assert.equal(context.elements.recordBtn.disabled, false);
  assert.equal(context.recordingStartPending, false);
  assert.equal(context.currentRecordingId, null);
  assert.equal(context.stoppedTimers, 1);
});

test('a stale pre-start status response cannot restore Stop Recording after start rollback', async () => {
  let resolveStatus;
  const context = recorder((url) => url === '/api/recordings/status'
    ? new Promise(resolve => { resolveStatus = resolve; })
    : Promise.resolve({ ok: false, status: 503, json: async () => ({ message: 'RECORDER_TICK_TIMEOUT' }) }));
  context.elements.recordBtn.classList.add('btn-success');
  context.elements.recordBtnText.textContent = 'Stop Recording';
  const pending = context.checkRecordingStatus();
  await context.startRecording();
  resolveStatus({ ok: true, json: async () => ({ recording: true, recordingId: 'rolled-back-recording' }) });
  await pending;
  assert.equal(context.isRecording, false);
  assert.equal(context.currentRecordingId, null);
  assert.equal(context.elements.recordBtnText.textContent, 'Start Recording');
  assert.equal(context.elements.recordBtn.classes.has('btn-success'), false);
  assert.equal(context.elements.recordingStatus.classes.has('d-none'), true);
});

test('an unauthorized observer panel stops polling without changing recording controls', async () => {
  for (const status of [401, 403]) {
    let calls = 0;
    let intervals = 0;
    const label = element();
    const recordBtn = element();
    const context = vm.createContext({
      elements: { watchBtn: element(), recordBtn },
      document: { getElementById() { return label; } },
      fetch: async () => { calls++; return { ok: false, status }; },
      setInterval() { intervals++; }, clearInterval() {},
      console: { warn() {} }
    });
    vm.runInContext(watch, context);
    await context.initDesktopWatchControls();
    assert.equal(calls, 1);
    assert.equal(intervals, 0);
    assert.equal(context.elements.watchBtn.disabled, true);
    assert.equal(recordBtn.disabled, false);
    assert.match(label.textContent, /local access required/);
  }
});

test('observer polling also stops if access is lost after an initial successful response', async () => {
  let interval;
  const cleared = [];
  let status = 200;
  const context = vm.createContext({
    elements: { watchBtn: element() }, desktopWatching: false,
    document: { getElementById() { return element(); } }, updateDesktopWatchUi() {},
    fetch: async () => ({ ok: status === 200, status, json: async () => ({ state: 'IDLE' }) }),
    setInterval(callback) { interval = callback; return 17; },
    clearInterval(id) { cleared.push(id); }, console: { warn() {} }
  });
  vm.runInContext(watch, context);
  await context.initDesktopWatchControls();
  assert.equal(typeof interval, 'function');
  status = 403;
  await interval();
  assert.deepEqual(cleared, [17]);
  assert.equal(context.elements.watchBtn.disabled, true);
});

test('a refreshed page can bind the already-running recorder from its accepted live ticks', () => {
  const context = recorder(async () => { throw Error('No request expected'); });
  context.currentDebugSessionId = null;
  context.isDebugSessionActive = false;
  context.sessionCreatedMatchesSelectedWindow = () => true;
  context.shouldRenderLiveTickBatch = () => true;
  vm.runInContext(liveBinding, context);
  context.bindRecorderSessionFromLiveTick({ sessionId: 'actual-live-producer', tick: 14 });
  assert.equal(context.currentDebugSessionId, 'actual-live-producer');
  assert.equal(context.isDebugSessionActive, true);
  assert.equal(context.currentRecordingId, null);
});

test('live binding refuses replay, stale-window, foreign, malformed and already-bound input', () => {
  for (const scenario of [
    { state: { currentMode: 'replay' } },
    { state: { debugFeedEnabled: false } },
    { state: { pendingWindowSessionRebind: true } },
    { state: { currentDebugSessionId: 'other-producer' } },
    { state: { sessionCreatedMatchesSelectedWindow: () => false } },
    { data: { sessionId: 'actual-live-producer', tick: -1 } },
    { data: { sessionId: '', tick: 1 } }
  ]) {
    const context = recorder(async () => { throw Error('No request expected'); });
    context.currentDebugSessionId = null;
    context.isDebugSessionActive = false;
    context.sessionCreatedMatchesSelectedWindow = () => true;
    context.shouldRenderLiveTickBatch = () => true;
    Object.assign(context, scenario.state);
    const previous = context.currentDebugSessionId;
    vm.runInContext(liveBinding, context);
    context.bindRecorderSessionFromLiveTick(scenario.data || { sessionId: 'actual-live-producer', tick: 14 });
    assert.equal(context.currentDebugSessionId, previous);
    assert.equal(context.isDebugSessionActive, false);
  }
});

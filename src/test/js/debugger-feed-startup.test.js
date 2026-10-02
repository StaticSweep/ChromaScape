const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/debugger.js'), 'utf8');
const start = script.indexOf('function initDebugFeedControls()');
const end = script.indexOf('function updateDebugFeedToggleUi()', start);

test('opening the debugger observes ticks without changing the shared feed', () => {
  for (const saved of [null, 'false', 'true']) {
    const calls = [];
    let click;
    const context = vm.createContext({
      elements: { debugFeedToggleBtn: { addEventListener(event, callback) { click = callback; } } },
      localStorage: { getItem() { return saved; } },
      DEBUG_FEED_KEY: 'debugger_debug_feed_enabled',
      debugFeedEnabled: false,
      setDebugFeedEnabled(enabled, sendSignal) {
        context.debugFeedEnabled = enabled;
        calls.push({ enabled, sendSignal });
      }
    });
    vm.runInContext(script.slice(start, end) + '\ninitDebugFeedControls();', context);
    assert.deepEqual(calls, [{ enabled: true, sendSignal: false }]);
    click();
    assert.deepEqual(calls[1], { enabled: false, sendSignal: true });
    click();
    assert.deepEqual(calls[2], { enabled: true, sendSignal: true });
  }
});

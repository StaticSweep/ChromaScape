const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../..');
const template = fs.readFileSync(
  path.join(root, 'src/main/resources/templates/debugger.html'), 'utf8');
const script = fs.readFileSync(
  path.join(root, 'src/main/resources/static/js/debugger.js'), 'utf8');
const styles = fs.readFileSync(
  path.join(root, 'src/main/resources/static/css/debugger.css'), 'utf8');

test('debugger script parses before workspace handlers are registered', () => {
  const { Script } = require('node:vm');
  assert.doesNotThrow(() => new Script(script, { filename: 'debugger.js' }));
});

test('debugger exposes the operator workspaces in workflow order', () => {
  const names = [...template.matchAll(/class="workspace-tab[^\"]*"[^>]*data-workspace="([^"]+)"/g)]
    .map(match => match[1]);
  assert.deepEqual(names, ['runs', 'live', 'replays', 'recovery', 'ai-training', 'developer']);
});

test('debugger workspace template keeps element ids unique', () => {
  const ids = [...template.matchAll(/\sid="([^"]+)"/g)].map(match => match[1]);
  const duplicates = ids.filter((id, index) => ids.indexOf(id) !== index);
  assert.deepEqual([...new Set(duplicates)], []);
});

test('workspace navigation synchronizes live and replay modes', () => {
  assert.match(script, /workspace === 'replays' \? 'replay' : 'live'/);
  assert.match(script, /activateDebuggerWorkspace\(mode === 'replay' \? 'replays' : 'live'/);
  assert.match(script, /!document\.body\.classList\.contains\('recovery-mode-active'\)/);
});

test('Runs owns a canonical URL that removes replay deep-link state', () => {
  assert.match(script, /const runsPath = '\/debugger\/runs'/);
  assert.match(script, /history\.replaceState\(\{ workspace: 'runs' \}, '', runsPath\)/);
  assert.match(script, /activateDebuggerWorkspace\(replayDeepLink \? 'replays' : 'runs'/);
  assert.match(script, /params\.has\('sessionId'\) \|\| params\.get\('mode'\) === 'replay'/);
});

test('debugger loads the current cache-busted script bundle', () => {
  assert.match(template, /<script src="\/js\/debugger\.js\?v=27"><\/script>/);
  assert.match(template, /<script src="\/js\/debugger-replay-core\.js\?v=2"><\/script>/);
});

test('advanced systems are isolated in the developer workspace', () => {
  assert.match(styles, /body\[data-workspace="developer"\] #patchLoopPanel/);
  assert.match(styles, /body\[data-workspace="developer"\] #ai-routine-panel/);
  assert.match(styles, /body:not\(\[data-workspace="developer"\]\) #taskRecordingPanel/);
});

test('AI Training is isolated as a first-class workspace', () => {
  assert.match(script, /'ai-training': \{/);
  assert.match(styles, /body:not\(\[data-workspace="ai-training"\]\) #aiTrainingPanel/);
  assert.match(styles, /body\[data-workspace="ai-training"\] \.debugger-container/);
});

test('logger keeps unticked process logs out of Tick 0 and names their group clearly', () => {
  assert.match(
    script,
    /tick: Number\.isFinite\(numericTick\) && numericTick > 0 \? numericTick : null/
  );
  assert.match(script, /tick === null \? 'Unticked logs' : `Tick \$\{tick\}`/);
});

test('live and replay log sources trust transport metadata instead of bracket prefixes', () => {
  const authoritativeSourceAssignments =
    script.match(/const actualSource = entry\.source \|\| 'unknown';/g) || [];
  assert.equal(authoritativeSourceAssignments.length, 2);
  assert.doesNotMatch(
    script,
    /actualSource = extractSourceFromMessage\(entry\.message, entry\.source\)/
  );
});

test('clicking Live from Runs shows the live canvas without clearing session logs', () => {
  const vm = require('node:vm');
  function element(workspace) {
    const classes = new Set(workspace ? ['workspace-tab'] : []);
    return {
      dataset: workspace ? { workspace } : {},
      style: {},
      textContent: '',
      listeners: {},
      classList: {
        contains: name => classes.has(name),
        add: name => classes.add(name),
        remove: name => classes.delete(name),
        toggle(name, active) { active ? classes.add(name) : classes.delete(name); }
      },
      setAttribute(name, value) { this[name] = value; },
      addEventListener(name, handler) { this.listeners[name] = handler; },
      getContext: () => ({}),
      querySelector() { return this; }
    };
  }
  const tabs = ['runs', 'live', 'replays'].map(element);
  const nodes = new Map([...template.matchAll(/\sid="([^"]+)"/g)]
    .map(match => [match[1], element()]));
  nodes.get('viewport-canvas').style.display = 'none';
  nodes.get('replayVideo').style.display = 'block';
  const body = element();
  const signals = [];
  const preferences = new Map();
  const context = vm.createContext({
    console,
    URLSearchParams,
    location: { pathname: "/debugger", search: "", hash: "" },
    history: { replaceState() {} },
    localStorage: { getItem: key => preferences.get(key), setItem: (key, value) => preferences.set(key, value) },
    fetch: async (url, options) => { signals.push({ url, enabled: JSON.parse(options.body).enabled }); return { ok: true }; },
    document: {
      body,
      addEventListener() {},
      dispatchEvent() {},
      getElementById: id => nodes.get(id) || null,
      querySelectorAll: selector => selector === '[data-workspace-link]' ? [] : tabs
    },
    MutationObserver: class { observe() {} },
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } }
  });
  context.window = context;
  vm.runInContext(fs.readFileSync(path.join(root,
    'src/main/resources/static/js/debugger-replay-core.js'), 'utf8'), context);
  vm.runInContext(script, context);
  vm.runInContext('cacheElements(); initDebugFeedControls(); initializeDebuggerWorkspaces(); liveLogHistory.push({message: "keep"});', context);
  assert.equal(body.dataset.workspace, 'runs');
  assert.equal(signals.length, 0, 'loading Runs must not pause the producer');
  tabs[1].listeners.click();
  assert.equal(signals.length, 0, 'enabled feed needs no duplicate producer control');
  assert.equal(vm.runInContext('debugFeedEnabled', context), true);
  assert.equal(body.dataset.workspace, 'live');
  assert.equal(nodes.get('workspaceTitle').textContent, 'Live session');
  assert.equal(tabs[1]['aria-selected'], 'true');
  assert.equal(nodes.get('viewport-canvas').style.display, 'block');
  assert.equal(nodes.get('replayVideo').style.display, 'none');
  assert.equal(nodes.get('scrubberRange').disabled, true);
  tabs[0].listeners.click();
  tabs[1].listeners.click();
  assert.equal(nodes.get('viewport-canvas').style.display, 'block');
  assert.equal(vm.runInContext('liveLogHistory.length', context), 1);
  assert.equal(signals.length, 0, 'reopening an enabled feed must not send duplicate controls');
  nodes.get('feedStatusIndicator').textContent = 'Feed: Active (Tick 10)';
  vm.runInContext('syncWorkspaceConnectionStatus()', context);
  assert.equal(nodes.get('workspaceConnectionText').textContent, 'Telemetry connected');
});

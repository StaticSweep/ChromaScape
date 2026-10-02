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

test('debugger exposes the operator workspaces in workflow order', () => {
  const names = [...template.matchAll(/class="workspace-tab[^\"]*"[^>]*data-workspace="([^"]+)"/g)]
    .map(match => match[1]);
  assert.deepEqual(names, ['runs', 'live', 'replays', 'recovery', 'ai-player', 'ai-training', 'developer']);
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

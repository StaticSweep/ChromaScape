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
  assert.match(script, /activateDebuggerWorkspace\('runs', \{ syncMode: false, syncUrl: false \}\)/);
});

test('debugger loads the current cache-busted script bundle', () => {
  assert.match(template, /<script src="\/js\/debugger\.js\?v=24"><\/script>/);
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

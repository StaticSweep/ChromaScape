const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../..');
const api = require(path.join(root, 'src/main/resources/static/js/ai-training.js'));
const template = fs.readFileSync(path.join(root, 'src/main/resources/templates/debugger.html'), 'utf8');
const script = fs.readFileSync(path.join(root, 'src/main/resources/static/js/ai-training.js'), 'utf8');

test('AI Training tab exposes content-neutral catalog filters', () => {
  assert.match(template, /data-workspace="ai-training"/);
  assert.match(template, /id="aiTrainingUsageFilter"/);
  assert.match(template, /<option value="used" selected>Used in AI<\/option>/);
  assert.match(template, /<option value="all">All recordings<\/option>/);
});

test('prototype graph area truthfully renders Not created', () => {
  assert.match(script, /This model is not an AI Routine BehaviorGraph/);
  assert.match(script, /lineage\.behaviorGraph \? diagram/);
});

test('catalog operations are selection-based and preserve no-control flags server-side', () => {
  assert.match(script, /body: JSON\.stringify\(\{ lineageId: lineage\.lineageId, action \}\)/);
  assert.doesNotMatch(script, /prompt\(/);
  const panel = template.slice(template.indexOf('<section id="aiTrainingPanel"'), template.indexOf('<section id="ai-routine-panel"'));
  assert.doesNotMatch(panel, /Model ID/);
});

test('catalog utilities escape evidence and label inclusive ranges', () => {
  assert.equal(api.escapeHtml('<script>"x"</script>'), '&lt;script&gt;&quot;x&quot;&lt;/script&gt;');
  assert.equal(api.rangeLabel({ startTick: 67, endTick: 132 }), 'Ticks 67–132');
  assert.equal(api.rangeLabel({ startTick: null, endTick: null }), 'Full recording');
});

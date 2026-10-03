const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');

const templatePath = path.resolve(
  __dirname,
  '../../main/resources/templates/replay.html'
);
const template = fs.readFileSync(templatePath, 'utf8');

test('standalone replay page loads playable recording sessions, not only tick catalog entries', () => {
  assert.match(template, /const RECORDING_SESSIONS_API = '\/api\/recordings\/v1\/sessions';/);
  assert.match(template, /const REPLAY_TICK_CATALOG_API = '\/api\/replay\/playback\/v1\/recordings';/);
  assert.match(template, /const playable = session\.playable === true;/);
});

test('selecting a replay assigns the canonical video URL to the player', () => {
  assert.match(template, /await loadRecordingVideo\(recordingId\)/);
  assert.match(template, /videoPlayer\.src = recording\.videoUrl;/);
  assert.match(template, /fetch\(`\/api\/recordings\/\$\{encodeURIComponent\(recordingId\)\}`\)/);
  assert.doesNotMatch(template, /getElementById\('orRecordingId'\)/);
});

test('replay page has explicit readable control colors and visible playback status', () => {
  assert.match(template, /body \.form-control,/);
  assert.match(template, /color: #172033;/);
  assert.match(template, /id="replayLoadStatus"/);
  assert.match(template, /aria-live="polite"/);
});

test('standalone replay inline script parses', () => {
  const scripts = [...template.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/g)]
    .map(match => match[1])
    .filter(source => source.trim());
  assert.equal(scripts.length, 1);
  assert.doesNotThrow(() => new vm.Script(scripts[0]));
});

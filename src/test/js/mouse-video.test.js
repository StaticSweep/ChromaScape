'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const api = require('../../main/resources/static/js/mouse-video.js');

test('newRequestId returns a non-empty unique identifier', () => {
  const first = api.newRequestId();
  const second = api.newRequestId();
  assert.equal(typeof first, 'string');
  assert.ok(first.length > 0);
  assert.notEqual(first, second);
});

test('previewTerminalError stops polling on cancelled and failed previews', () => {
  assert.equal(api.previewTerminalError({ state: 'PENDING' }), null);
  assert.equal(api.previewTerminalError({ state: 'READY' }), null);
  assert.equal(api.previewTerminalError(undefined), null);
  assert.equal(api.previewTerminalError({ state: 'CANCELLED' }), 'Exact preview was cancelled');
  assert.match(api.previewTerminalError({ state: 'RECONCILIATION_REQUIRED' }), /reconciliation/);
  assert.equal(api.previewTerminalError({ state: 'FAILED', failureCode: 'DECODER_FAILED' }), 'DECODER_FAILED');
  assert.match(api.previewTerminalError({ state: 'FAILED' }), /could not produce/);
});

test('formatBytes scales units', () => {
  assert.equal(api.formatBytes(512), '512 B');
  assert.equal(api.formatBytes(2048), '2.0 KiB');
  assert.equal(api.formatBytes(1024 * 1024), '1.0 MiB');
  assert.equal(api.formatBytes(undefined), '—');
});

test('formatLimits renders bounded limits', () => {
  const text = api.formatLimits({
    maxUploadBytes: 512 * 1024 * 1024,
    maxDurationSeconds: 1200,
    maxWidth: 3840,
    maxHeight: 2160,
    maxDecodeFrames: 150000,
  });
  assert.match(text, /upload ≤ 512\.0 MiB/);
  assert.match(text, /duration ≤ 20 min/);
  assert.match(text, /3840×2160/);
  assert.match(text, /150000 frames/);
});

test('player proxy is pinned to the selected completed analysis', () => {
  const source = {
    selectedAnalysisId: 'analysis-b',
    artifacts: [
      { role: 'PROXY', state: 'FINALIZED', analysisId: 'analysis-a' },
      { role: 'PROXY', state: 'STAGING', analysisId: 'analysis-b' },
      { role: 'THUMBNAIL', state: 'FINALIZED', analysisId: 'analysis-b' },
      { role: 'PROXY', state: 'FINALIZED', analysisId: 'analysis-b' },
    ],
  };
  assert.equal(
    api.selectedProxyArtifact(source).analysisId,
    'analysis-b',
    'must not reuse another analysis or an unfinalized artifact'
  );
  assert.equal(api.selectedProxyArtifact({ artifacts: source.artifacts }), null);
  assert.equal(api.selectedProxyArtifact(null), null);
});

test('describeSource never claims native ticks', () => {
  const text = api.describeSource({ kind: 'UPLOAD', originReview: 'UNREVIEWED', status: 'SUCCEEDED' });
  assert.equal(text, 'UPLOAD · UNREVIEWED · SUCCEEDED');
  assert.equal(api.describeSource(null), 'Unknown source');
});

test('a ?source= deep link opens that exact source', () => {
  const id = '3f1c9a2e-5b7d-4c81-9e40-2ad6b8c1f0aa';
  assert.equal(api.linkedSourceId('?source=' + id), id);
  assert.equal(api.linkedSourceId('?source=' + id.toUpperCase() + '&mode=review'), id.toUpperCase());
  assert.equal(api.linkedSourceId('?mode=review'), null);
  assert.equal(api.linkedSourceId(''), null);
  assert.equal(api.linkedSourceId(undefined), null);
  assert.equal(api.linkedSourceId('?source='), null);
  assert.equal(api.linkedSourceId('?source=not-a-uuid'), null);
  assert.equal(api.linkedSourceId('?source=' + id + '/../jobs'), null, 'a path segment must not pass');
  assert.equal(api.linkedSourceId('?source=------------------------------------'), null);
});

test('state persistence survives a storage implementation', () => {
  const backing = new Map();
  globalThis.localStorage = {
    getItem: (key) => (backing.has(key) ? backing.get(key) : null),
    setItem: (key, value) => backing.set(key, value),
  };
  try {
    const saved = api.saveState({ lastSourceId: 'source-1' });
    assert.equal(saved.lastSourceId, 'source-1');
    assert.equal(api.loadState().lastSourceId, 'source-1');
  } finally {
    delete globalThis.localStorage;
  }
});

test('state persistence degrades safely when storage is unavailable', () => {
  delete globalThis.localStorage;
  assert.deepEqual(api.loadState(), {});
  assert.deepEqual(api.saveState({ lastJobId: 'job-1' }).lastJobId, 'job-1');
});

test('describeFrame preserves exact integer PTS text', () => {
  const text = api.describeFrame({
    ordinal: 12,
    pts: '9007199254740993',
    ptsTime: '9007199254740.993',
    key: true,
  });
  assert.match(text, /frame 12/);
  assert.ok(text.includes('9007199254740993'), 'PTS must not be rounded');
  assert.match(text, /keyframe/);
  assert.equal(api.describeFrame(null), 'No frame selected');
});

test('buildCorrection records an explicit unknown instead of guessing coordinates', () => {
  const entry = api.buildCorrection(
    'CURSOR_VISIBILITY',
    { visibility: 'HIDDEN', unknownReason: 'occluded by chatbox', confidence: 0.4 },
    7
  );
  assert.deepEqual(entry, {
    kind: 'CURSOR_VISIBILITY',
    frameOrdinal: 7,
    visibility: 'HIDDEN',
    unknownReason: 'occluded by chatbox',
    confidence: 0.4,
  });
  assert.throws(
    () => api.buildCorrection('CURSOR_VISIBILITY', { visibility: 'VISIBLE' }, 1),
    /exact-frame preview/
  );
  assert.deepEqual(
    api.buildCorrection('CURSOR_VISIBILITY', { visibility: 'VISIBLE', x: 12, y: 34 }, 2),
    {
      kind: 'CURSOR_VISIBILITY',
      frameOrdinal: 2,
      visibility: 'VISIBLE',
      x: 12,
      y: 34,
    }
  );
});

test('buildCorrection derives frame ranges from the selected frame', () => {
  assert.deepEqual(api.buildCorrection('PAUSE', {}, 4), {
    kind: 'PAUSE',
    startFrameOrdinal: 4,
    endFrameOrdinal: 4,
  });
  assert.deepEqual(api.buildCorrection('SCENE_CUT', {}, 4), {
    kind: 'SCENE_CUT',
    beforeFrameOrdinal: 4,
    afterFrameOrdinal: 5,
  });
  assert.deepEqual(api.buildCorrection('ACTION_INDICATOR', {}, 2), {
    kind: 'ACTION_INDICATOR',
    frameOrdinal: 2,
    indicator: 'MENU_OPEN',
  });
  assert.deepEqual(
    api.buildCorrection('TARGET_REGION', { x: 10, y: 20, width: 30, height: 40, label: 'bank' }, 1),
    {
      kind: 'TARGET_REGION',
      frameOrdinal: 1,
      x: 10,
      y: 20,
      width: 30,
      height: 40,
      label: 'bank',
    }
  );
  assert.deepEqual(
    api.buildCorrection(
      'CAMERA_ACTIVITY',
      { activity: 'REGION_CHANGE', x: 1, y: 2, width: 3, height: 4 },
      2
    ),
    {
      kind: 'CAMERA_ACTIVITY',
      frameOrdinal: 2,
      activity: 'REGION_CHANGE',
      x: 1,
      y: 2,
      width: 3,
      height: 4,
    }
  );
  assert.throws(() => api.buildCorrection('TARGET_REGION', {}, 1), /finite x is required/);
});

test('buildCorrection ignores out-of-range confidence and bounds the note', () => {
  const entry = api.buildCorrection(
    'PAUSE',
    { startFrameOrdinal: 1, endFrameOrdinal: 2, confidence: 4, note: 'x'.repeat(400) },
    1
  );
  assert.equal(entry.confidence, undefined);
  assert.equal(entry.note.length, 280);
});

test('addCorrection appends immutably and refuses duplicates', () => {
  const draft = [];
  const entry = api.buildCorrection('PAUSE', {}, 1);
  const next = api.addCorrection(draft, entry);
  assert.equal(draft.length, 0, 'the original draft must not be mutated');
  assert.equal(next.length, 1);
  assert.throws(() => api.addCorrection(next, entry), /already in the draft/);
});

test('addCorrection enforces the server count cap', () => {
  const draft = new Array(2000).fill(null).map((_, index) => ({ kind: 'PAUSE', index }));
  assert.throws(
    () => api.addCorrection(draft, { kind: 'ACTION_INDICATOR', frameOrdinal: 0 }),
    /maximum 2000 corrections/
  );
});

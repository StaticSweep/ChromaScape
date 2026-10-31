'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const api = require('../../main/resources/static/js/mouse-video.js');
const reviewCore = require('../../main/resources/static/js/mouse-video-review-core.js');

function framesAt(fps, count, from) {
  const start = from || 0;
  return Array.from({ length: count }, (_, index) => ({
    ordinal: start + index,
    ptsTime: (start + index) / fps,
    pts: String((start + index) * 1000),
    key: index % 30 === 0,
  }));
}

function visible(ordinal, x, y, confidence) {
  return {
    kind: 'CURSOR_VISIBILITY',
    frameOrdinal: ordinal,
    visibility: 'VISIBLE',
    x,
    y,
    confidence: confidence === undefined ? 0.9 : confidence,
  };
}

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

// ------------------------------------------------------------------ review mode

test('tick grid picks the nearest frame by PTS at 60, 30 and 24 fps', () => {
  for (const fps of [60, 30, 24]) {
    const frames = framesAt(fps, fps * 2, 0);
    const grid = reviewCore.buildTickGrid(frames);
    assert.equal(grid.length, 4, fps + ' fps has ticks at 0, 0.6, 1.2 and 1.8 s');
    for (const [index, ordinal] of grid.entries()) {
      const expected = reviewCore.buildTickGrid(frames)[index];
      assert.equal(ordinal, expected);
      const frame = frames[ordinal];
      assert.ok(Math.abs(frame.ptsTime - index * 0.6) <= 0.5 / fps + 1e-9);
    }
    assert.equal(new Set(grid).size, grid.length, 'no duplicate ordinals');
  }
  assert.equal(reviewCore.buildTickGrid([]).length, 0);
});

test('tick grid follows variable frame timestamps instead of a fixed frame count', () => {
  const frames = [
    { ordinal: 0, ptsTime: 0 },
    { ordinal: 1, ptsTime: 0.1 },
    { ordinal: 2, ptsTime: 0.55 },
    { ordinal: 3, ptsTime: 0.62 },
    { ordinal: 4, ptsTime: 1.3 },
    { ordinal: 5, ptsTime: 1.9 },
  ];
  const grid = reviewCore.buildTickGrid(frames);
  assert.deepEqual(grid, [0, 3, 4, 5], 'nearest frame to 0.6 s is 0.62 s, not 0.55 s');
});

test('the detector guess is a boxed VISIBLE hotspot or UNSURE, never a hidden point', () => {
  const observations = [
    visible(7, 100, 200, 0.86),
    { kind: 'CURSOR_VISIBILITY', frameOrdinal: 8, visibility: 'AMBIGUOUS', x: 1, y: 2 },
    { kind: 'CURSOR_VISIBILITY', frameOrdinal: 9, visibility: 'HIDDEN' },
  ];
  const guess = reviewCore.guessForOrdinal(observations, 7);
  assert.equal(guess.visibility, 'VISIBLE');
  assert.equal(guess.x, 100);
  assert.equal(guess.y, 200);
  assert.equal(guess.confidence, 0.86);
  assert.equal(guess.width, 24);
  assert.equal(guess.height, 32);
  assert.equal(reviewCore.guessForOrdinal(observations, 8).visibility, 'UNSURE');
  assert.equal(reviewCore.guessForOrdinal(observations, 9).visibility, 'UNSURE');
  assert.equal(reviewCore.guessForOrdinal(observations, 404).visibility, 'UNSURE');
});

test('review queue puts UNSURE, jumps, confident stills and between-tick checks before the grid', () => {
  const frames = framesAt(30, 180, 0);
  const observations = [
    { kind: 'CURSOR_VISIBILITY', frameOrdinal: 10, visibility: 'AMBIGUOUS' },
    visible(30, 100, 500),
    visible(31, 900, 500),
  ];
  for (let ordinal = 60; ordinal <= 160; ordinal += 3) {
    observations.push(visible(ordinal, 640, 480));
  }
  const queue = reviewCore.buildReviewQueue({
    frames,
    observations,
    frameWidth: 1920,
    seed: 7,
  });
  assert.equal(queue[0].reason, 'unsure', 'UNSURE items are reviewed first');
  assert.ok(queue.some((item) => item.reason === 'suspicious jump'));
  assert.ok(queue.some((item) => item.reason === 'confident still'));
  assert.ok(queue.some((item) => item.reason === 'between-tick check'));
  const priorityReasons = new Set([
    'unsure',
    'suspicious jump',
    'confident still',
    'between-tick check',
  ]);
  const firstGrid = queue.findIndex((item) => !priorityReasons.has(item.reason));
  assert.ok(
    queue.slice(0, firstGrid).every((item) => priorityReasons.has(item.reason)),
    'no grid item is scheduled before every priority item'
  );
  assert.ok(queue.slice(firstGrid).every((item) => !priorityReasons.has(item.reason)));
  assert.equal(new Set(queue.map((item) => item.ordinal)).size, queue.length);
});

test('spot checks are seeded, deterministic and never duplicate a queued ordinal', () => {
  const frames = framesAt(30, 180, 0);
  const observations = [];
  for (let ordinal = 0; ordinal < 180; ordinal += 3) {
    observations.push(visible(ordinal, 400 + (ordinal % 10), 400));
  }
  const first = reviewCore.buildReviewQueue({ frames, observations, seed: 42 });
  const again = reviewCore.buildReviewQueue({ frames, observations, seed: 42 });
  const other = reviewCore.buildReviewQueue({ frames, observations, seed: 43 });
  assert.deepEqual(first, again, 'the same seed reproduces the queue');
  assert.notDeepEqual(first, other, 'a different seed moves the spot checks');
  assert.ok(first.some((item) => item.reason === 'spot check'));
  assert.equal(new Set(first.map((item) => item.ordinal)).size, first.length);

  const blind = reviewCore.buildReviewQueue({
    frames,
    observations,
    seed: 42,
    blind: true,
  });
  assert.ok(blind.every((item) => item.reason === 'tick grid' || item.reason === 'spot check'));
  assert.ok(blind.some((item) => item.reason === 'spot check'));
  assert.deepEqual(
    blind.filter((item) => item.reason === 'tick grid').map((item) => item.ordinal),
    reviewCore.buildTickGrid(frames)
  );
});

test('trail segments break across gaps longer than 0.3 s', () => {
  const frames = framesAt(30, 60, 0);
  const observations = [
    visible(0, 100, 100),
    visible(3, 110, 100),
    visible(6, 120, 100),
    visible(30, 500, 500),
  ];
  const segments = reviewCore.buildTrail(observations, 30, frames);
  assert.equal(segments.length, 2);
  assert.equal(segments[0].length, 3);
  assert.equal(
    reviewCore.expectedNextPoint(segments),
    null,
    'a one-point segment cannot be extrapolated across the gap'
  );
  assert.deepEqual(
    reviewCore.expectedNextPoint([[{ x: 10, y: 10, time: 0 }, { x: 14, y: 12, time: 0.1 }]]),
    { x: 18, y: 14 }
  );
  assert.equal(reviewCore.expectedNextPoint([[{ x: 1, y: 1 }]]), null);
  assert.deepEqual(reviewCore.buildTrail(observations, 0, frames).length, 1);
});

test('label actions build valid corrections and replace rather than duplicate', () => {
  const guess = reviewCore.guessForOrdinal([visible(12, 200, 300, 0.91)], 12);
  const accepted = reviewCore.labelValues('accept', {
    guess,
    queueReason: 'suspicious jump',
  });
  const entry = api.buildCorrection('CURSOR_VISIBILITY', accepted, 12);
  assert.deepEqual(entry, {
    kind: 'CURSOR_VISIBILITY',
    frameOrdinal: 12,
    visibility: 'VISIBLE',
    x: 200,
    y: 300,
    confidence: 0.91,
    note: 'accepted · queue:suspicious jump',
    provenance: 'DETECTOR_ASSISTED',
  });

  const clicked = api.buildCorrection(
    'CURSOR_VISIBILITY',
    reviewCore.labelValues('click', { point: { x: 5, y: 6 }, queueReason: 'tick grid' }),
    12
  );
  assert.equal(clicked.x, 5);
  assert.equal(clicked.note, 'corrected · queue:tick grid');

  const hidden = api.buildCorrection(
    'CURSOR_VISIBILITY',
    reviewCore.labelValues('hidden', { queueReason: 'tick grid' }),
    12
  );
  assert.equal(hidden.visibility, 'HIDDEN');
  assert.equal(hidden.unknownReason, 'not visible');

  const ambiguous = api.buildCorrection(
    'CURSOR_VISIBILITY',
    reviewCore.labelValues('ambiguous', {}),
    13
  );
  assert.equal(ambiguous.visibility, 'AMBIGUOUS');
  assert.equal(ambiguous.unknownReason, 'unsure');

  assert.throws(
    () => reviewCore.labelValues('accept', { guess: { visibility: 'UNSURE' } }),
    /UNSURE/
  );
  assert.throws(() => reviewCore.labelValues('click', {}), /exact-frame preview/);

  let draft = reviewCore.replaceCursorLabel([], entry);
  draft = reviewCore.replaceCursorLabel(draft, clicked);
  assert.equal(draft.length, 1, 'one label per frame');
  assert.equal(draft[0].x, 5);
  const atCap = new Array(1999).fill({ kind: 'PAUSE' }).concat([entry]);
  assert.equal(
    reviewCore.replaceCursorLabel(atCap, clicked).length,
    2000,
    'replacing an existing frame never exceeds the cap'
  );
  assert.throws(
    () => reviewCore.replaceCursorLabel(new Array(2000).fill({ kind: 'PAUSE' }), entry),
    /maximum 2000 corrections/
  );
});

test('undo restores the exact previous draft', () => {
  const draft = [{ kind: 'CURSOR_VISIBILITY', frameOrdinal: 1, visibility: 'HIDDEN' }];
  const history = reviewCore.rememberDraft([], draft);
  const next = reviewCore.replaceCursorLabel(draft, {
    kind: 'CURSOR_VISIBILITY',
    frameOrdinal: 2,
    visibility: 'HIDDEN',
  });
  assert.equal(next.length, 2);
  const undone = reviewCore.undoDraft(history);
  assert.deepEqual(undone.draft, draft);
  assert.equal(undone.history.length, 0);
  assert.equal(reviewCore.undoDraft([]), null);
});

test('review mode seeds the cumulative draft from the latest revision and keeps earlier labels', () => {
  const history = [
    {
      id: 'rev-1',
      expectedBaseRevision: 0,
      corrections: [
        { kind: 'CURSOR_VISIBILITY', frameOrdinal: 1, visibility: 'VISIBLE', x: 10, y: 20 },
        { kind: 'PAUSE', startFrameOrdinal: 3, endFrameOrdinal: 3 },
      ],
    },
    {
      id: 'rev-2',
      expectedBaseRevision: 1,
      corrections: [
        { kind: 'CURSOR_VISIBILITY', frameOrdinal: 1, visibility: 'VISIBLE', x: 11, y: 21 },
        { kind: 'CURSOR_VISIBILITY', frameOrdinal: 2, visibility: 'HIDDEN', unknownReason: 'not visible' },
      ],
    },
  ];
  const seeded = reviewCore.seedDraft(history);
  assert.equal(seeded.length, 2, 'only the latest revision and only cursor labels are seeded');
  assert.equal(seeded.find((entry) => entry.frameOrdinal === 1).x, 11);
  const draft = reviewCore.replaceCursorLabel(
    seeded,
    api.buildCorrection(
      'CURSOR_VISIBILITY',
      reviewCore.labelValues('hidden', { queueReason: 'tick grid' }),
      5
    )
  );
  assert.deepEqual(
    draft.map((entry) => entry.frameOrdinal).sort((a, b) => a - b),
    [1, 2, 5],
    'the save body is cumulative'
  );
  const counts = reviewCore.countLabels(draft);
  assert.equal(counts.labelled, 3);
  assert.equal(counts.noCursor, 2);
  assert.equal(counts.accepted, 0);
  assert.equal(counts.corrected, 3);
});

test('blind mode uses no suggestions and is forced for held-out sources', () => {
  assert.equal(reviewCore.isHeldoutContext('YouTube · channel · clip · dev · full'), false);
  assert.equal(reviewCore.isHeldoutContext('YouTube · channel · clip · heldout · full'), true);
  assert.equal(reviewCore.isHeldoutContext(undefined), false);
  assert.match(
    reviewCore.reviewReason({ blind: false, seed: 12 }),
    /detector-assisted · seed=12/
  );
  assert.match(
    reviewCore.reviewReason({ blind: true, seed: 12 }),
    /blind-unverified · seed=12/
  );
  assert.equal(reviewCore.autosaveDue(24), false);
  assert.equal(reviewCore.autosaveDue(25), true);
});

test('cumulative seed is bound to the selected analysis', () => {
  const history = [
    {
      id: 'analysis-a-rev',
      analysisId: 'analysis-a',
      expectedBaseRevision: 0,
      corrections: [visible(1, 10, 20)],
    },
    {
      id: 'analysis-b-rev',
      analysisId: 'analysis-b',
      expectedBaseRevision: 1,
      corrections: [visible(1, 900, 800), visible(2, 910, 810)],
    },
  ];
  const forA = reviewCore.seedDraft(history, { analysisId: 'analysis-a' });
  assert.equal(forA.length, 1);
  assert.equal(forA[0].x, 10, 'analysis A keeps its own coordinates');
  assert.equal(forA[0].y, 20);
  const forB = reviewCore.seedDraft(history, { analysisId: 'analysis-b' });
  assert.equal(forB.length, 2);
  assert.equal(forB.find((entry) => entry.frameOrdinal === 1).x, 900);
  assert.deepEqual(
    reviewCore.seedDraft(history, { analysisId: 'analysis-c' }),
    [],
    'an unlabelled analysis seeds nothing'
  );
  assert.equal(
    reviewCore.seedDraft(history).length,
    2,
    'without an analysis filter the latest source-wide revision is still selected'
  );
});

test('trail terminates at cuts, hidden spans and frame-index holes', () => {
  const frames = framesAt(30, 30, 0);
  const cut = {
    kind: 'SCENE_CUT',
    beforeFrameOrdinal: 2,
    afterFrameOrdinal: 3,
  };
  const observations = [
    visible(0, 100, 100),
    visible(1, 104, 100),
    cut,
    visible(6, 120, 100),
    visible(9, 130, 100),
  ];
  const segments = reviewCore.buildTrail(observations, 9, frames);
  assert.deepEqual(
    segments.map((segment) => segment.map((point) => point.ordinal)),
    [[0, 1], [6, 9]],
    'the cut splits the trail even though the samples are close in time'
  );
  assert.deepEqual(reviewCore.expectedNextPoint(segments, { targetOrdinal: 9 }), {
    x: 140,
    y: 100,
  });
  assert.equal(
    reviewCore.expectedNextPoint(segments, { targetOrdinal: 6 }),
    null,
    'the dot is not extrapolated past the newest sample'
  );

  const ambiguous = [
    visible(0, 100, 100),
    { kind: 'CURSOR_VISIBILITY', frameOrdinal: 3, visibility: 'AMBIGUOUS' },
    visible(6, 120, 100),
  ];
  assert.equal(
    reviewCore.buildTrail(ambiguous, 6, frames).length,
    2,
    'an ambiguous sample ends the earlier segment'
  );

  const holedFrames = framesAt(30, 30, 0).filter((frame) => frame.ordinal < 3 || frame.ordinal > 18);
  const acrossHole = [visible(0, 100, 100), visible(25, 500, 500)];
  assert.equal(
    reviewCore.buildTrail(acrossHole, 25, holedFrames, { gapSeconds: 5 }).length,
    2,
    'a hole in the frozen frame index breaks continuity even without a time gap'
  );
});

test('blind review suppresses every suggestion layer', () => {
  assert.equal(
    reviewCore.showSuggestionLayer({ reviewActive: true, blind: true, predictions: true }),
    false,
    'the original prediction overlay is hidden before the first held-out frame'
  );
  assert.equal(
    reviewCore.showSuggestionLayer({ reviewActive: false, blind: true, predictions: true }),
    false,
    'a held-out source stays blind before review mode is entered'
  );
  assert.equal(
    reviewCore.showSuggestionLayer({ reviewActive: true, blind: false, predictions: true }),
    true
  );
  assert.equal(
    reviewCore.showSuggestionLayer({ reviewActive: false, blind: false, predictions: false }),
    false
  );
});

test('blind labels are unverified and assisted labels cannot become independent', () => {
  const guess = reviewCore.guessForOrdinal([visible(4, 50, 60, 0.95)], 4);
  const assistedEntry = api.buildCorrection(
    'CURSOR_VISIBILITY',
    reviewCore.labelValues('accept', { guess, queueReason: 'tick grid' }),
    4
  );
  assert.equal(assistedEntry.provenance, 'DETECTOR_ASSISTED');
  assert.equal(reviewCore.draftProvenance([assistedEntry]), 'DETECTOR_ASSISTED');
  assert.equal(reviewCore.isUnassistedDraft([assistedEntry]), false);

  const blindEntry = api.buildCorrection(
    'CURSOR_VISIBILITY',
    reviewCore.labelValues('hidden', { blind: true, queueReason: 'tick grid' }),
    4
  );
  assert.equal(blindEntry.provenance, 'BLIND_UNVERIFIED');
  assert.equal(reviewCore.draftProvenance([blindEntry]), 'BLIND_UNVERIFIED');
  assert.equal(reviewCore.isUnassistedDraft([blindEntry]), false);
  assert.equal(reviewCore.draftProvenance([assistedEntry, blindEntry]), 'MIXED');
  assert.equal(reviewCore.draftProvenance([]), 'EMPTY');
  assert.equal(reviewCore.draftProvenance([{ kind: 'PAUSE' }]), 'EMPTY');
  assert.equal(
    reviewCore.draftProvenance([{ kind: 'CURSOR_VISIBILITY', frameOrdinal: 1, visibility: 'HIDDEN' }]),
    'UNKNOWN',
    'labels without structured provenance stay unknown'
  );
  assert.throws(
    () => reviewCore.labelValues('accept', { blind: true, guess }),
    /Blind mode has no detector guess/
  );

  const history = [
    {
      id: 'rev-blind',
      analysisId: 'analysis-a',
      expectedBaseRevision: 0,
      corrections: [assistedEntry, blindEntry],
    },
  ];
  const blindOnly = reviewCore.seedDraft(history, {
    analysisId: 'analysis-a',
    blindOnly: true,
  });
  assert.deepEqual(
    blindOnly.map((entry) => entry.provenance),
    ['BLIND_UNVERIFIED'],
    'held-out seeding never revives detector-assisted history'
  );
});

test('an in-flight save never acknowledges a newer draft', async () => {
  assert.deepEqual(
    reviewCore.acknowledgeSave({ submittedVersion: 2, currentVersion: 2 }),
    { dirty: false, resave: false }
  );
  assert.deepEqual(
    reviewCore.acknowledgeSave({ submittedVersion: 2, currentVersion: 3 }),
    { dirty: true, resave: true },
    'a label added during the request stays dirty'
  );
  assert.deepEqual(reviewCore.acknowledgeSave({ submittedVersion: 2 }), {
    dirty: true,
    resave: true,
  });

  const sent = [];
  let version = 0;
  const queue = reviewCore.createSaveQueue(async () => {
    sent.push({ version });
    if (sent.length === 1) {
      version += 1;
      return 'again';
    }
    return true;
  });
  assert.equal(await queue.kick(), true);
  assert.deepEqual(
    sent.map((item) => item.version),
    [0, 1],
    'the queue resends the newer snapshot before it reports success'
  );

  let failures = 0;
  const failing = reviewCore.createSaveQueue(async () => {
    failures += 1;
    return false;
  });
  assert.equal(await failing.kick(), false);
  assert.equal(failures, 1, 'a failed save stops the queue instead of dropping the draft');

  let calls = 0;
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const joined = reviewCore.createSaveQueue(async () => {
    calls += 1;
    await gate;
    return true;
  });
  const first = joined.kick();
  const second = joined.kick();
  release();
  assert.equal(await first, true);
  assert.equal(await second, true);
  assert.equal(calls, 1, 'a concurrent kick joins the running save');
});

test('trail follows PTS order across variable frame timing', () => {
  const frames = [
    { ordinal: 0, ptsTime: 0, pts: '0', key: true },
    { ordinal: 1, ptsTime: 0.02, pts: '1', key: false },
    { ordinal: 2, ptsTime: 0.05, pts: '2', key: false },
    { ordinal: 3, ptsTime: 0.09, pts: '3', key: false },
    { ordinal: 4, ptsTime: 0.15, pts: '4', key: false },
    { ordinal: 5, ptsTime: 0.22, pts: '5', key: false },
  ];
  const observations = [
    visible(0, 10, 10),
    visible(1, 12, 10),
    visible(2, 15, 11),
    visible(5, 30, 20),
  ];
  const segments = reviewCore.buildTrail(observations, 5, frames);
  assert.equal(segments.length, 1, 'a sub-0.3 s VFR span stays connected');
  assert.deepEqual(
    segments[0].map((point) => point.ordinal),
    [0, 1, 2, 5]
  );
  assert.deepEqual(reviewCore.expectedNextPoint(segments, { targetOrdinal: 5 }), {
    x: 45,
    y: 29,
  });
});


test('prior suggestion exposure survives enabling blind labels', () => {
  for (const action of ['hidden', 'ambiguous', 'click']) {
    const values = reviewCore.labelValues(action, {
      blind: true, exposed: true, point: { x: 10, y: 20 },
    });
    assert.equal(values.provenance, 'DETECTOR_ASSISTED');
  }
  assert.match(reviewCore.reviewReason({ blind: true, exposed: true }), /detector-assisted/);
  assert.match(reviewCore.reviewReason({ blind: true, exposed: false }), /blind-unverified/);
  assert.equal(reviewCore.normalizeProvenance('INDEPENDENT'), 'UNKNOWN');
});

test('complete bounded index loading reaches beyond forty pages and refuses gaps or cursor loops', async () => {
  const frames = Array.from({ length: 8401 }, (_, ordinal) => ({ ordinal, ptsTime: String(ordinal / 30) }));
  const load = async cursor => { const offset = Number(cursor || 0); return { artifactChecksum: 'frozen', frameCount: frames.length, items: frames.slice(offset, offset + 200), nextCursor: offset + 200 < frames.length ? String(offset + 200) : null }; };
  const complete = await reviewCore.completeIndex(load);
  assert.equal(complete.frames.length, 8401);
  assert.equal(complete.frames.at(-1).ordinal, 8400);
  await assert.rejects(reviewCore.completeIndex(async () => ({ artifactChecksum: 'a', frameCount: 2, items: [{ ordinal: 1 }], nextCursor: null })), /ordinal/);
  await assert.rejects(reviewCore.completeIndex(async cursor => ({ artifactChecksum: 'a', frameCount: 3, items: [{ ordinal: cursor ? 1 : 0 }], nextCursor: 'repeat' })), /Repeated/);
  await assert.rejects(reviewCore.completeIndex(load, { cancelled: () => true }), /cancelled/);
  await assert.rejects(reviewCore.completeIndex(async () => ({ artifactChecksum: 'a', frameCount: 2, items: [{ ordinal: 0 }], nextCursor: null })), /coverage/);
});

test('dense truth queues use source PTS, include range ends and remain bounded', () => {
  const frames = Array.from({ length: 30 }, (_, ordinal) => ({ ordinal, ptsTime: String(ordinal / 30) }));
  assert.deepEqual(reviewCore.denseQueue(frames, 0, 29).map(item => item.ordinal), [0,3,6,9,12,15,18,21,24,27,29]);
  assert.deepEqual(reviewCore.denseQueue(frames, 15, 20).map(item => item.ordinal), [15,18,20]);
});


test('next-position estimates use actual PTS deltas and source bounds', () => {
  const segments = [[{ x: 10, y: 10, time: 0 }, { x: 14, y: 12, time: 0.2 }]];
  assert.deepEqual(reviewCore.expectedNextPoint(segments, { nextTime: 0.25 }), { x: 15, y: 12.5 });
  assert.deepEqual(reviewCore.expectedNextPoint(segments, { nextTime: 0.4, width: 16, height: 13 }), { x: 15, y: 12 });
  assert.equal(reviewCore.expectedNextPoint(segments, { nextTime: NaN }), null);
  assert.equal(reviewCore.expectedNextPoint([[{ x: 10, y: 10 }, { x: 14, y: 12 }]]), null);
});

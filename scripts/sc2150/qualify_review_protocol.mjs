// SC-2150 review protocol regressions. Run from the repository root with node.
// Uses the real page scripts with controlled API faults; backend verification is separate.
import http from 'node:http';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from '@playwright/test';

const root = process.cwd();
const sourceId = '3f1c9a2e-5b7d-4c81-9e40-2ad6b8c1f0aa';
const analysisA = '9a7b6c5d-4e3f-4a2b-8c1d-0e9f8a7b6c5d';
const analysisB = '1b2c3d4e-5f60-4718-9a2b-3c4d5e6f7081';
let heldout = false;

const frames = Array.from({ length: 360 }, (_, index) => ({
  ordinal: index,
  pts: String(index * 1000),
  ptsTime: (index / 60).toFixed(6),
  duration: null,
  key: index % 60 === 0,
}));

function observationsFor(analysisId) {
  const base = analysisId === analysisA ? 300 : 1200;
  const observations = [];
  for (let ordinal = 0; ordinal < 360; ordinal += 6) {
    observations.push({
      kind: 'CURSOR_VISIBILITY',
      frameOrdinal: ordinal,
      visibility: 'VISIBLE',
      x: base + (ordinal % 12),
      y: 300,
      confidence: 0.9,
    });
  }
  observations.push({
    kind: 'CURSOR_VISIBILITY',
    frameOrdinal: 123,
    visibility: 'AMBIGUOUS',
    confidence: 0.4,
  });
  if (analysisId === analysisB) {
    observations.push({ kind: 'SCENE_CUT', beforeFrameOrdinal: 180, afterFrameOrdinal: 181 });
  }
  return observations;
}

const history = [
  {
    id: 'rev-a',
    analysisId: analysisA,
    expectedBaseRevision: 0,
    createdAt: '2026-10-25T00:00:00Z',
    contract: 'corrections/v2',
    state: 'SUPERSEDED',
    sourceUse: 'INSPECTION_ONLY',
    reason: 'FA12 dev review · detector-assisted · seed=1',
    corrections: [
      { kind: 'CURSOR_VISIBILITY', frameOrdinal: 6, visibility: 'VISIBLE', x: 111, y: 222,
        provenance: 'DETECTOR_ASSISTED' },
    ],
  },
  {
    id: 'rev-b',
    analysisId: analysisB,
    expectedBaseRevision: 1,
    createdAt: '2026-10-25T01:00:00Z',
    contract: 'corrections/v2',
    state: 'CURRENT',
    sourceUse: 'INSPECTION_ONLY',
    reason: 'FA12 dev review · detector-assisted · seed=2',
    corrections: [
      { kind: 'CURSOR_VISIBILITY', frameOrdinal: 6, visibility: 'VISIBLE', x: 999, y: 888,
        provenance: 'DETECTOR_ASSISTED' },
    ],
  },
];

function source() {
  return {
    id: sourceId,
    kind: 'UPLOAD',
    declaredOrigin: 'GENERATED',
    originReview: 'UNREVIEWED',
    lifecycle: 'OPEN',
    context: heldout
      ? 'YouTube · channel · clip · heldout · full'
      : 'YouTube · channel · clip · dev · full',
    status: 'SUCCEEDED',
    coverage: 'video · analysis',
    reviewRevision: 2,
    selectedAnalysisId: analysisA,
    originalQualified: false,
    originalChecksum: 'f'.repeat(64),
    originalSizeBytes: 1024,
    verifiedMediaType: 'video/mp4',
    jobs: [],
    artifacts: [],
    analyses: [
      { id: analysisA, algorithmId: 'cursor-template/v7', status: 'COMPLETE', selected: true },
      { id: analysisB, algorithmId: 'cursor-template/v7', status: 'COMPLETE', selected: false },
    ],
  };
}

const previewMeta = {
  state: 'READY',
  mediaUrl: '/frame.svg',
  sourceWidth: 1920,
  sourceHeight: 1080,
  outputWidth: 320,
  outputHeight: 180,
  checksum: 'abc123abc123',
  method: 'frame-png/v1',
  timeBase: { raw: '1/60' },
  pts: '0',
};

let previewOrdinal = 0;
let baseRevision = 2;
const saves = [];
const pngSvg =
  '<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180">' +
  '<rect width="320" height="180" fill="#2b3a55"/><circle cx="160" cy="90" r="8" fill="#e2e8f0"/>' +
  '</svg>';

const server = http.createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  const send = (type, body) => {
    response.writeHead(200, { 'Content-Type': type });
    response.end(body);
  };
  if (url.pathname === '/mouse/video') {
    return send('text/html', fs.readFileSync(path.join(root, 'src/main/resources/templates/mouse-video.html')));
  }
  if (url.pathname.startsWith('/js/')) {
    return send('text/javascript', fs.readFileSync(path.join(root, 'src/main/resources/static', url.pathname)));
  }
  if (url.pathname.startsWith('/css/')) {
    return send('text/css', fs.readFileSync(path.join(root, 'src/main/resources/static', url.pathname)));
  }
  if (url.pathname === '/frame.svg') return send('image/svg+xml', pngSvg);
  response.writeHead(404);
  response.end();
});

await new Promise((resolve) => server.listen(18291, '127.0.0.1', resolve));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
page.setDefaultTimeout(20000);
const pageErrors = [];
page.on('pageerror', (error) => pageErrors.push(String(error)));
page.on('console', (message) => {
  if (message.type() === 'error') pageErrors.push('console: ' + message.text());
});

await page.route('**/api/debug/mouse-videos/**', async (route) => {
  const request = route.request();
  const url = new URL(request.url());
  const api = url.pathname;
  const method = request.method();
  const json = (body, status) =>
    route.fulfill({ status: status || 200, contentType: 'application/json', body: JSON.stringify(body) });

  if (api.endsWith('/review-policy')) return json({ contract: 'review-policy/v1', enrolled: false, forcedBlind: heldout, split: heldout ? 'heldout' : 'dev', frameCount: frames.length, frameIndexChecksum: 'checksum-1', eligibleModes: ['DETECTOR_ASSISTED'], predictionAccess: true, sessions: [] });
  if (api.endsWith('/capabilities')) {
    return json({ enabled: true, tools: { ffprobeAvailable: true, ffmpegAvailable: true }, limits: {} });
  }
  if (api.endsWith('/write-nonce')) {
    return json({ nonce: 'nonce', expiresAt: new Date(Date.now() + 600000).toISOString() });
  }
  if (new RegExp('/sources/' + sourceId + '$').test(api)) return json(source());
  if (api.endsWith('/sources')) return json({ items: [source()], total: 1, nextCursor: null });
  if (api.endsWith('/reviews') && method === 'GET') return json({ items: history, nextCursor: null });
  if (api.endsWith('/reviews') && method === 'POST') {
    const body = JSON.parse(request.postData() || '{}');
    saves.push({
      at: Date.now(),
      requestId: body.requestId,
      wireBody: request.postData(),
      analysisId: body.analysisId,
      expectedBaseRevision: body.expectedBaseRevision,
      reason: body.reason,
      labels: body.corrections.length,
      provenance: body.corrections.map((entry) => entry.provenance || 'UNKNOWN'),
      ordinals: body.corrections.map((entry) => entry.frameOrdinal),
      hidden: body.corrections.filter((entry) => entry.visibility === 'HIDDEN').length,
      corrections: body.corrections,
    });
    await new Promise((resolve) => setTimeout(resolve, 700));
    if (saves.length === 1) {
      baseRevision = Number(body.expectedBaseRevision) + 1;
      return route.abort('failed'); // simulate committed write with lost HTTP response
    }
    if (saves[0].requestId === body.requestId && saves[0].wireBody !== request.postData()) {
      return json({code:'CONFLICT',message:'Request identifier reused with changed body'},409);
    }
    baseRevision = Number(body.expectedBaseRevision) + 1;
    return json({
      id: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeee' + saves.length,
      sourceId,
      analysisId: body.analysisId,
      expectedBaseRevision: body.expectedBaseRevision,
      contract: 'corrections/v2',
      corrections: body.corrections,
      reason: body.reason,
      sourceUse: body.sourceUse,
      state: 'CURRENT',
    });
  }
  if (api.endsWith('/exports')) return json({ items: [] });
  if (api.includes('/observations')) {
    const match = api.match(/analyses\/([^/]+)\/observations/);
    const analysisId = match ? match[1] : analysisA;
    const items = observationsFor(analysisId);
    return json({ available: true, items, nextCursor: null, total: items.length });
  }
  if (api.includes('/frame-index')) {
    return json({ artifactChecksum: 'checksum-1', frameCount: frames.length, items: frames, nextCursor: null });
  }
  if (api.endsWith('/frame-previews') && method === 'GET') return json({ items: [] });
  if (api.endsWith('/frame-previews') && method === 'POST') {
    const body = JSON.parse(request.postData() || '{}');
    previewOrdinal = body.frameOrdinal;
    return json({ state: 'READY', metadataUrl: '/api/debug/mouse-videos/preview-meta' });
  }
  if (api.endsWith('/preview-meta')) {
    return json(Object.assign({}, previewMeta, { frameOrdinal: previewOrdinal }));
  }
  if (api.includes('/media/')) return route.fulfill({ status: 404, body: '' });
  return json({});
});

async function openReviewPage() {
  await page.goto('http://127.0.0.1:18291/mouse/video?source=' + sourceId);
  await page.locator('[data-video-review]').waitFor({ state: 'visible' });
  await page.waitForFunction(() => {
    const select = document.querySelector('[data-review-analysis]');
    return select && select.options.length > 0 && select.value;
  });
}


try {
  console.log("phase: initial review");
  await openReviewPage();
  await page.locator('[data-review-mode-toggle]').check();
  await page.waitForFunction(() => /reason:/.test(document.querySelector('[data-review-mode-status]').textContent));
  await page.locator('[data-review-hidden]').click();
  await page.locator('[data-review-save]').click();
  await page.waitForFunction(() => /Autosave failed/.test(document.querySelector('[data-review-mode-status]').textContent));
  await page.locator('[data-review-hidden]').click();
  await page.locator('[data-review-save]').click();
  await page.waitForFunction(() => /Saved revision/.test(document.querySelector('[data-review-mode-status]').textContent) &&
    !/newer labels queued/.test(document.querySelector('[data-review-mode-status]').textContent));
  assert.equal(saves.length, 3);
  assert.equal(saves[0].wireBody, saves[1].wireBody, 'uncertain retry preserves exact bytes');
  assert.equal(saves[0].requestId, saves[1].requestId);
  assert.notEqual(saves[1].requestId, saves[2].requestId, 'later edits get a new identity');
  assert.equal(saves[2].expectedBaseRevision, saves[1].expectedBaseRevision + 1);
  assert.deepEqual(saves.map((save) => save.labels), [2, 2, 3]);
  const retry = { posts: saves.length, identicalRetry: true, labelCounts: saves.map((save) => save.labels) };

  console.log("phase: exact retry passed");
  history.length = 0;
  await page.reload();
  await page.locator('[data-review-mode-toggle]').check();
  await page.waitForFunction(() => /reason:/.test(document.querySelector('[data-review-mode-status]').textContent));
  await page.locator('[data-review-skip]').click();
  await page.waitForFunction(() => document.querySelector('[data-frame-ordinal]').value === '0' &&
    !document.querySelector('[data-preview-image]').hidden);
  assert.ok(await page.locator('[data-preview-overlay] .prediction-point').count());
  await page.locator('[data-review-blind-toggle]').check();
  await page.waitForFunction(() => !document.querySelector('[data-preview-image]').hidden);
  assert.equal(await page.locator('[data-preview-overlay] .prediction-point').count(), 0);
  await page.locator('[data-review-hidden]').click();
  await page.locator('[data-review-save]').click();
  await page.waitForFunction(() => /Saved revision/.test(document.querySelector('[data-review-mode-status]').textContent));
  assert.deepEqual(saves.at(-1).provenance, ['DETECTOR_ASSISTED']);
  assert.match(saves.at(-1).reason, /detector-assisted/);

  // A fresh forced-blind open still remembers prior exposure to this source.
  console.log("phase: exposed blind passed");
  heldout = true;
  await page.reload();
  await page.locator('[data-review-mode-toggle]').check();
  await page.waitForFunction(() => /reason:/.test(document.querySelector('[data-review-mode-status]').textContent));
  await page.locator('[data-review-hidden]').click();
  await page.locator('[data-review-save]').click();
  await page.waitForFunction(() => /Saved revision/.test(document.querySelector('[data-review-mode-status]').textContent));
  assert.deepEqual(saves.at(-1).provenance, ['DETECTOR_ASSISTED']);
  assert.match(saves.at(-1).reason, /detector-assisted/);

  // A new local exposure record can only produce an unverified blind assertion.
  console.log("phase: reopen exposure passed");
  await page.evaluate(() => localStorage.clear());
  await page.reload();
  await page.locator('[data-review-mode-toggle]').check();
  await page.waitForFunction(() => /reason:/.test(document.querySelector('[data-review-mode-status]').textContent));
  await page.locator('[data-review-hidden]').click();
  await page.locator('[data-review-save]').click();
  await page.waitForFunction(() => /Saved revision/.test(document.querySelector('[data-review-mode-status]').textContent));
  assert.deepEqual(saves.at(-1).provenance, ['BLIND_UNVERIFIED']);
  assert.match(saves.at(-1).reason, /blind-unverified/);
  const freshBlind = saves.at(-1).provenance;
  await page.addInitScript(() => Object.defineProperty(window, 'localStorage', {
    get() { throw new Error('storage unavailable'); },
  }));
  await page.reload();
  await page.locator('[data-review-mode-toggle]').check();
  await page.waitForFunction(() => /reason:/.test(document.querySelector('[data-review-mode-status]').textContent));
  await page.locator('[data-review-hidden]').click();
  await page.locator('[data-review-save]').click();
  await page.waitForFunction(() => /Saved revision/.test(document.querySelector('[data-review-mode-status]').textContent));
  assert.deepEqual(saves.at(-1).provenance, ['DETECTOR_ASSISTED'], 'storage failure is conservative');
  assert.deepEqual(pageErrors.filter((message) => !message.startsWith('console:')), []);
  console.log(JSON.stringify({ retry, exposurePersistsOnReopen: true,
    blindClaim: freshBlind, storageFailureAssisted: true, pageErrors: pageErrors.filter((message) => !message.startsWith('console:')) }, null, 2));
} finally {
  await browser.close();
  await new Promise((resolve) => server.close(resolve));
}

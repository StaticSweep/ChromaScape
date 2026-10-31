// Actual Spring/PostgreSQL review qualification on a disposable loopback server.
// Run: SC2150_BASE_URL=http://127.0.0.1:18190 SC2150_EVIDENCE=build/... node scripts/sc2150/qualify_review_backend.mjs
import { chromium, request as playwrightRequest, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const origin = process.env.SC2150_BASE_URL;
const target = new URL(origin);
assert.equal(target.hostname, '127.0.0.1');
assert.ok(Number(target.port) >= 18190 && Number(target.port) <= 18199);
const evidence = process.env.SC2150_EVIDENCE;
assert.ok(evidence);
fs.mkdirSync(evidence, { recursive: true });
const fixture = path.join(evidence, 'review-fixture.mp4');
const encoders = execFileSync('ffmpeg', ['-hide_banner', '-encoders'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
const encoder = encoders.includes('libx264') ? 'libx264' : 'libopenh264';
execFileSync('ffmpeg', ['-v', 'error', '-nostdin', '-y', '-f', 'lavfi', '-i',
  'testsrc2=size=160x96:rate=30:duration=1', '-threads', '2', '-c:v', encoder, fixture]);
const api = origin + '/api/debug/mouse-videos';
const http = await playwrightRequest.newContext({ extraHTTPHeaders: { Origin: origin } });
async function nonce() { return (await (await http.get(api + '/write-nonce')).json()).nonce; }
async function post(route, data) {
  return http.post(api + route, { data, headers: { 'X-Mouse-Video-Nonce': await nonce() } });
}
const browser = await chromium.launch();
try {
  const upload = await http.post(api + '/imports/upload', {
    headers: { 'X-Mouse-Video-Nonce': await nonce(), 'X-Mouse-Video-Request-Id': randomUUID() },
    multipart: {
      metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({
        declaredOrigin: 'GENERATED', taskKey: 'synthetic-review-f26', context: 'Synthetic · heldout · protocol test',
      })) },
      file: { name: 'review.mp4', mimeType: 'video/mp4', buffer: fs.readFileSync(fixture) },
    },
  });
  assert.equal(upload.status(), 202, await upload.text());
  const receipt = await upload.json();
  await expect.poll(async () => (await (await http.get(api + '/jobs/' + receipt.jobId)).json()).status,
    { timeout: 150000 }).toBe('SUCCEEDED');
  const source = await (await http.get(api + '/sources/' + receipt.sourceId)).json();
  const legacyResponse = await post('/sources/' + source.id + '/exports', {
    requestId: randomUUID(), analysisId: source.selectedAnalysisId, reviewRevision: 0,
  });
  assert.ok([200, 201].includes(legacyResponse.status()), await legacyResponse.text());
  const legacyExport = await legacyResponse.json();
  const legacyBytes = await (await http.get(api + '/exports/' + legacyExport.exportId)).body();
  const page = await browser.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(String(error)));
  await page.goto(origin + '/mouse/video?source=' + source.id);
  await expect(page.locator('[data-review-blind-toggle]')).toBeChecked();
  await page.locator('[data-review-mode-toggle]').check();
  await expect(page.locator('[data-review-hidden]')).toBeEnabled({ timeout: 60000 });
  await expect(page.locator('[data-preview-image]')).toBeVisible({ timeout: 60000 });
  await page.locator('[data-review-hidden]').click();
  const savedResponse = page.waitForResponse(r => r.request().method() === 'POST' && r.url().endsWith('/reviews'));
  await page.locator('[data-review-save]').click();
  const saved = await savedResponse;
  assert.equal(saved.status(), 201, await saved.text());
  const revision = await saved.json();
  const submittedBody = saved.request().postData();
  assert.equal(revision.contract, 'corrections/v2');
  assert.equal(revision.corrections.length, 1);
  assert.equal(revision.corrections[0].provenance, 'BLIND_UNVERIFIED');
  const retry = await post('/sources/' + source.id + '/reviews', JSON.parse(submittedBody));
  assert.equal(retry.status(), 201, await retry.text());
  assert.equal((await retry.json()).id, revision.id);
  const changed = JSON.parse(submittedBody);
  changed.corrections[0].provenance = 'DETECTOR_ASSISTED';
  assert.equal((await post('/sources/' + source.id + '/reviews', changed)).status(), 409);
  changed.requestId = randomUUID();
  changed.expectedBaseRevision = 1;
  changed.corrections[0].provenance = 'INDEPENDENT';
  assert.equal((await post('/sources/' + source.id + '/reviews', changed)).status(), 400);
  const history = await (await http.get(api + '/sources/' + source.id + '/reviews')).json();
  assert.equal(history.items.length, 1);
  assert.equal(history.items[0].corrections[0].provenance, 'BLIND_UNVERIFIED');
  const bundleResponse = await post('/sources/' + source.id + '/exports', {
    requestId: randomUUID(), analysisId: source.selectedAnalysisId, reviewRevision: 1,
  });
  assert.ok([200, 201].includes(bundleResponse.status()), await bundleResponse.text());
  const bundle = await bundleResponse.json();
  const downloaded = await (await http.get(api + '/exports/' + bundle.exportId)).body();
  assert.ok(downloaded.equals(await (await http.get(api + '/exports/' + bundle.exportId)).body()));
  const manifest = (await (await http.get(api + '/exports/' + bundle.exportId + '/manifest')).json()).manifest;
  assert.equal(manifest.review.latestId, revision.id);
  assert.equal(manifest.review.contract, 'corrections/v2');
  assert.equal(manifest.review.independenceVerified, false);
  assert.deepEqual(manifest.review.provenance, ['BLIND_UNVERIFIED']);
  assert.ok(legacyBytes.equals(await (await http.get(api + '/exports/' + legacyExport.exportId)).body()));
  assert.deepEqual(pageErrors, []);
  const result = { sourceId: source.id, analysisId: source.selectedAnalysisId, revisionId: revision.id,
    exportId: bundle.exportId, browserPost: saved.status(), retrySameRevision: true,
    changedRetry: 409, independentClaim: 400, contract: revision.contract, provenance: revision.corrections[0].provenance,
    exportPinnedAndRepeatable: true, legacyExportUnchanged: true, pageErrors };
  for (const [name, body] of Object.entries({ result, revision, manifest, history })) {
    fs.writeFileSync(path.join(evidence, name + '.json'), JSON.stringify(body, null, 2));
  }
  console.log(JSON.stringify(result, null, 2));
} finally {
  await browser.close();
  await http.dispose();
}

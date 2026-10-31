// SC-2150 Step 4 browser matrix against a real Spring/PostgreSQL verification server.
// Synthetic mechanics only: generated fixtures, generated labels; not human or held-out evidence.
//
// Env: SC2150_BASE_URL (loopback 18190-18199), SC2150_EVIDENCE (output dir), SC2150_POLICY_FILE
// (the corpus manifest the server was started with). Cases:
//   A delayed nonce / snapshot POST / history while labelling, then close + reload
//   B a committed snapshot whose response is lost, followed by later edits
//   C two sources reviewed concurrently in two tabs with interleaved delayed saves
//   D sealed session: every key, button and save path is refused; direct API write is 409
//   E no prediction-bearing 200 responses before seal
//   F long source (> 41 index pages) and review toggled off/on while the index is loading
import { chromium, request as playwrightRequest, expect } from '@playwright/test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { randomUUID, createHash } from 'node:crypto';

const origin = process.env.SC2150_BASE_URL;
const evidence = process.env.SC2150_EVIDENCE;
const policyPath = process.env.SC2150_POLICY_FILE;
const url = new URL(origin);
assert.equal(url.hostname, '127.0.0.1');
assert.ok(Number(url.port) >= 18190 && Number(url.port) <= 18199);
assert.ok(evidence && policyPath);
fs.mkdirSync(evidence, { recursive: true });
const PREVIEW_TIMEOUT_MS = Number(process.env.SC2150_PREVIEW_TIMEOUT_MS || 60000);
const api = origin + '/api/debug/mouse-videos';
const encoder = execFileSync('ffmpeg', ['-hide_banner', '-encoders'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
  .includes('libx264') ? 'libx264' : 'libopenh264';
const results = {};

function fixture(name, seconds, size, pattern) {
  const file = path.join(evidence, name);
  execFileSync('ffmpeg', ['-v', 'error', '-nostdin', '-y', '-f', 'lavfi', '-i',
    `${pattern}=size=${size}:rate=30:duration=${seconds}`, '-threads', '2', '-c:v', encoder, file]);
  return { file, digest: 'sha256:' + createHash('sha256').update(fs.readFileSync(file)).digest('hex') };
}

// A delayed route handler can outlive its route (unroute or context close mid-delay).
async function settle(action) {
  try { return await action(); } catch (error) { if (!/already handled|Target .*closed|has been closed/i.test(String(error))) throw error; }
}
const http = await playwrightRequest.newContext({ extraHTTPHeaders: { Origin: origin } });
async function get(route) { const r = await http.get(api + route); assert.equal(r.status(), 200, route + ' ' + await r.text()); return r.json(); }
async function post(route, data) { const nonce = (await get('/write-nonce')).nonce; return http.post(api + route, { data, headers: { 'X-Mouse-Video-Nonce': nonce } }); }
async function upload(fx, task) {
  const nonce = (await get('/write-nonce')).nonce;
  const r = await http.post(api + '/imports/upload', { headers: { 'X-Mouse-Video-Nonce': nonce, 'X-Mouse-Video-Request-Id': randomUUID() },
    multipart: { metadata: { name: 'meta.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ declaredOrigin: 'GENERATED', taskKey: task, context: 'Synthetic protocol mechanics' })) },
      file: { name: 'synthetic.mp4', mimeType: 'video/mp4', buffer: fs.readFileSync(fx.file) } } });
  assert.equal(r.status(), 202, await r.text());
  const receipt = await r.json();
  await expect.poll(async () => (await get('/jobs/' + receipt.jobId)).status, { timeout: 300000 }).toBe('SUCCEEDED');
  const source = await get('/sources/' + receipt.sourceId);
  return { id: source.id, analysisId: source.selectedAnalysisId };
}
async function history(sessionId) {
  const items = [];
  let cursor = null;
  do {
    const page = await get('/review-sessions/' + sessionId + '/reviews' + (cursor ? '?before=' + encodeURIComponent(cursor) : ''));
    items.push(...page.items);
    cursor = page.nextCursor;
  } while (cursor);
  return items.sort((a, b) => a.acknowledgedClientEditVersion - b.acknowledgedClientEditVersion);
}
function latest(items) { return items[items.length - 1]; }
function correctionKeys(item) { return item ? item.corrections.map(c => c.frameOrdinal + ':' + c.visibility).sort() : []; }

async function waitForPreview(page) {
  const deadline = Date.now() + PREVIEW_TIMEOUT_MS;
  while (Date.now() < deadline) {
    if (await page.locator('[data-preview-image]').isVisible() && await page.locator('[data-review-hidden]').isEnabled()) return;
    await page.waitForTimeout(150);
  }
  throw new Error('preview did not become ready within ' + PREVIEW_TIMEOUT_MS + 'ms');
}
async function openReview(page, sourceId) {
  await page.goto(origin + '/mouse/video?source=' + sourceId);
  await expect(page.locator('[data-review-blind-toggle]')).toBeChecked();
  await page.locator('[data-review-attestation]').check();
  await page.locator('[data-review-mode-toggle]').check();
}
// The open session is read from the server's review policy; the export link exists only once sealed.
async function openSessionId(source) {
  const policy = await get('/sources/' + source.id + '/review-policy?analysisId=' + source.analysisId);
  const open = (policy.sessions || []).filter(s => s.state === 'OPEN');
  assert.equal(open.length, 1, 'exactly one open session for ' + source.id);
  return open[0].id;
}
// Label n frames as hidden, alternating the button and the H key.
async function currentOrdinal(page) {
  return page.locator('[data-frame-ordinal]').inputValue().catch(() => null);
}
const actionLog = [];
async function draftCount(page) { return page.locator('[data-review-draft-count]').textContent().catch(() => null); }
async function label(page, n) {
  for (let i = 0; i < n; i++) {
    await waitForPreview(page);
    const before = await currentOrdinal(page);
    const countBefore = await draftCount(page);
    const via = i % 2 === 0 ? 'button' : 'key';
    if (via === 'button') await page.locator('[data-review-hidden]').click();
    else await page.keyboard.press('h');
    // A label advances the queue (or, on the last item, only adds the label); wait for either so
    // the next label cannot hit the same frame.
    await expect.poll(async () => (await currentOrdinal(page)) !== before || (await draftCount(page)) !== countBefore,
      { timeout: 30000 }).toBe(true);
    actionLog.push({ at: Date.now(), via, before, after: await currentOrdinal(page), countBefore, countAfter: await draftCount(page),
      focused: await page.evaluate(() => document.activeElement && (document.activeElement.tagName + ' ' + [...document.activeElement.attributes].map(a => a.name).filter(n => n.startsWith('data-')).join(','))) });
  }
}
async function closeAndReopen(page) {
  await page.locator('[data-review-mode-toggle]').uncheck();
  // The checkbox flips at once, but review mode closes only after its final flush is
  // acknowledged; reloading earlier would abandon an in-flight save (the page's beforeunload
  // guard protects a user, but a scripted reload does not stop for it).
  await expect(page.locator('[data-video-review]')).toHaveAttribute('data-review-mode', 'false', { timeout: 120000 });
  await page.reload();
  await page.locator('[data-review-attestation]').check();
  await page.locator('[data-review-mode-toggle]').check();
}
const postLog = [];
function snapshotFacts(req) {
  try { const s = JSON.parse(req.postDataJSON().snapshotJson); return { requestId: s.requestId, version: s.clientEditVersion, base: s.expectedBaseRevision, corrections: s.corrections.length }; } catch { return {}; }
}
function watch(page) {
  const errors = [], predictions = [], posts = [];
  page.on('requestfinished', async r => {
    if (r.method() !== 'POST' || !r.url().endsWith('/reviews')) return;
    const res = await r.response();
    postLog.push({ at: Date.now(), ...snapshotFacts(r), status: res ? res.status() : null, body: res ? (await res.text().catch(() => '')).slice(0, 300) : null });
  });
  page.on('requestfailed', r => {
    if (r.method() === 'POST' && r.url().endsWith('/reviews')) postLog.push({ at: Date.now(), ...snapshotFacts(r), failed: r.failure()?.errorText });
  });
  page.on('pageerror', e => errors.push(String(e)));
  page.on('response', r => {
    const u = r.url();
    if (r.status() === 200 && (u.includes('/observations') || u.includes('/exports'))) predictions.push(u);
  });
  page.on('request', r => { if (r.method() === 'POST' && r.url().endsWith('/reviews')) posts.push(r.postDataJSON()); });
  return { errors, predictions, posts };
}

const fxA = fixture('matrix-a.mp4', 5.9, '160x96', 'testsrc2');
const fxB = fixture('matrix-b.mp4', 5.9, '160x96', 'smptebars');
const fxLong = fixture('matrix-long.mp4', 300, '64x36', 'testsrc2');
fs.writeFileSync(policyPath, JSON.stringify({ schema: 'review-corpus/v1', sources: [
  { originalDigest: fxA.digest, originGroupId: 'synthetic-matrix-a', split: 'heldout' },
  { originalDigest: fxB.digest, originGroupId: 'synthetic-matrix-b', split: 'heldout' },
  { originalDigest: fxLong.digest, originGroupId: 'synthetic-matrix-long', split: 'heldout' }] }));
const sourceA = await upload(fxA, 'synthetic-matrix-a');
const sourceB = await upload(fxB, 'synthetic-matrix-b');
const sourceLong = await upload(fxLong, 'synthetic-matrix-long');

const browser = await chromium.launch();
try {
  // A + B: delays and a lost committed response in one session.
  {
    const page = await browser.newPage();
    page.setDefaultTimeout(120000);
    const seen = watch(page);
    let delayed = 0, lostRevision = null, lostRequestId = null;
    await page.route('**/api/debug/mouse-videos/**', async route => {
      const req = route.request();
      const u = req.url();
      if (u.endsWith('/write-nonce') || (req.method() === 'GET' && u.includes('/reviews'))) {
        delayed++; await new Promise(r => setTimeout(r, 1500)); return settle(() => route.continue());
      }
      if (req.method() === 'POST' && u.endsWith('/reviews')) {
        delayed++; await new Promise(r => setTimeout(r, 1500));
        if (!lostRevision) {
          const response = await settle(() => route.fetch());
          if (!response) return;
          const body = await response.json();
          postLog.push({ at: Date.now(), ...snapshotFacts(req), replayedByRoute: true, status: response.status(), body: JSON.stringify(body).slice(0, 300) });
          if (response.status() === 201 || response.status() === 200) {
            lostRevision = body.revisionId;
            lostRequestId = JSON.parse(req.postDataJSON().snapshotJson).requestId;
            return settle(() => route.abort('failed'));
          }
          return settle(() => route.fulfill({ response }));
        }
      }
      return settle(() => route.continue());
    });
    await openReview(page, sourceA.id);
    await label(page, 30); // crosses the 25-mutation autosave while POSTs are delayed and one is lost
    await label(page, 8);  // edits after the lost response
    await closeAndReopen(page);
    await expect(page.locator('[data-review-draft-count]')).toHaveText('38');
    const sessionId = await openSessionId(sourceA);
    const items = await history(sessionId);
    const versions = items.map(i => i.acknowledgedClientEditVersion);
    assert.deepEqual(versions, [...versions].sort((a, b) => a - b));
    assert.equal(new Set(versions).size, versions.length, 'each edit version acknowledged once');
    assert.ok(lostRevision, 'a committed response was lost');
    assert.equal(items.filter(i => i.revisionId === lostRevision).length, 1, 'lost revision recorded exactly once');
    const retries = seen.posts.filter(p => JSON.parse(p.snapshotJson).requestId === lostRequestId).length;
    assert.ok(retries >= 2, 'the uncertain snapshot was retried with its exact request id');
    assert.equal(latest(items).corrections.length, 38);
    assert.deepEqual(seen.predictions, []);
    assert.deepEqual(seen.errors, []);
    results.delaysAndLostResponse = { sessionId, labels: 38, revisions: items.length, delayedRequests: delayed, lostRevision, exactRetries: retries };

    // D: seal, then every mutation path must be refused without a false saved notice.
    await page.unrouteAll({ behavior: 'wait' });
    // Sealing requires complete dense coverage: an incomplete truth set is refused by design.
    const queueSize = Number((await page.locator('[data-review-mode-status]').textContent()).match(/item \d+\/(\d+)/)?.[1] || 60);
    // After reopening, the queue starts at item 1 again; step past the 38 labelled items first.
    for (let i = 0; i < 38; i++) {
      const before = await currentOrdinal(page);
      await page.keyboard.press('ArrowRight');
      await expect.poll(() => currentOrdinal(page), { timeout: 30000 }).not.toBe(before);
    }
    await label(page, queueSize - 38);
    await expect(page.locator('[data-review-draft-count]')).toHaveText(String(queueSize));
    await page.locator('[data-review-mode-toggle]').uncheck();
    await expect(page.locator('[data-video-review]')).toHaveAttribute('data-review-mode', 'false', { timeout: 120000 });
    await page.locator('[data-review-mode-toggle]').check();
    results.delaysAndLostResponse.completedForSeal = queueSize;
    await page.locator('[data-review-seal]').click();
    await expect(page.locator('[data-review-mode-status]')).toContainText('sealed');
    const sealedCount = (await history(sessionId)).length;
    for (const key of ['h', 'a', 's', 'z', 'Enter']) await page.keyboard.press(key);
    const refused = {};
    for (const control of ['hidden', 'accept', 'ambiguous', 'undo', 'save', 'skip']) {
      const button = page.locator('[data-review-' + control + ']');
      refused[control] = (await button.count()) === 0 || (await button.isDisabled()) || !(await button.isVisible());
      if (!refused[control]) await button.click();
    }
    await page.waitForTimeout(1500);
    assert.equal((await history(sessionId)).length, sealedCount, 'no revision after seal');
    const status = await page.locator('[data-review-mode-status]').textContent();
    assert.match(status, /sealed/i);
    assert.doesNotMatch(status, /\bsaved\b/i);
    const direct = await post('/review-sessions/' + sessionId + '/reviews', { snapshotJson: JSON.stringify({ requestId: randomUUID() }) });
    assert.equal(direct.status(), 409, 'direct write to a sealed session');
    await page.reload();
    await page.locator('[data-review-attestation]').check();
    await page.locator('[data-review-mode-toggle]').check();
    await expect(page.locator('[data-review-mode-status]')).toContainText('sealed and read-only');
    results.sealedReadOnly = { sessionId, keys: ['h', 'a', 's', 'z', 'Enter'], controlsDisabledOrHidden: refused, directWrite: 409, statusAfterReload: 'sealed and read-only' };
    await page.close();
  }

  // C: two sources concurrently, interleaved delayed saves; no cross-context mutation.
  {
    const ctx = await browser.newContext();
    const pageA = await ctx.newPage(), pageB = await ctx.newPage();
    for (const p of [pageA, pageB]) {
      p.setDefaultTimeout(120000);
      await p.route('**/review-sessions/*/reviews', async route => {
        if (route.request().method() === 'POST') await new Promise(r => setTimeout(r, 1200));
        return settle(() => route.continue());
      });
    }
    const seenA = watch(pageA), seenB = watch(pageB);
    await openReview(pageB, sourceB.id);
    // Source A's range is sealed by case D, so the concurrent pair is source B and the long source.
    await openReview(pageA, sourceLong.id);
    for (let round = 0; round < 3; round++) { await label(pageA, 5); await label(pageB, 7); }
    await closeAndReopen(pageA); await closeAndReopen(pageB);
    const idA = await openSessionId(sourceLong), idB = await openSessionId(sourceB);
    assert.ok(idA && idB && idA !== idB);
    const histA = await history(idA), histB = await history(idB);
    assert.equal(latest(histA).corrections.length, 15);
    assert.equal(latest(histB).corrections.length, 21);
    assert.ok(histA.every(i => i.sessionId === idA) && histB.every(i => i.sessionId === idB), 'revisions stay in their own session');
    assert.deepEqual([...seenA.errors, ...seenB.errors], []);
    assert.deepEqual([...seenA.predictions, ...seenB.predictions], []);
    results.twoSources = { sessionLong: idA, labelsLong: 15, sessionB: idB, labelsB: 21 };
    await ctx.close();
  }

  // F: long source; > 41 index pages; toggle off and on while the index is loading.
  {
    const page = await browser.newPage();
    page.setDefaultTimeout(180000);
    const seen = watch(page);
    let indexPages = 0, pageLimit = null;
    page.on('request', r => {
      if (!r.url().includes('/frame-index')) return;
      indexPages++;
      pageLimit = Number(new URL(r.url()).searchParams.get('limit')) || pageLimit;
    });
    await page.goto(origin + '/mouse/video?source=' + sourceLong.id);
    await page.locator('[data-review-attestation]').check();
    await page.locator('[data-review-mode-toggle]').check();
    await page.waitForTimeout(300);
    await page.locator('[data-review-mode-toggle]').uncheck();
    await page.locator('[data-review-mode-toggle]').check();
    await waitForPreview(page);
    const index = await get('/sources/' + sourceLong.id + '/analyses/' + sourceLong.analysisId + '/frame-index?limit=1');
    const frameCount = index.frameCount ?? index.totalFrames ?? null;
    // The old client stopped after 41 pages of 200 (8,200 frames). This source has more frames,
    // and the client must request every page of its complete index.
    assert.ok(frameCount > 8200, 'long fixture exceeds the old truncation point: ' + frameCount);
    assert.ok(pageLimit > 0, 'client paged the frame index');
    assert.ok(indexPages >= Math.ceil(frameCount / pageLimit),
      `client requested ${indexPages} pages of ${pageLimit} for ${frameCount} frames`);
    assert.deepEqual(seen.errors, []);
    assert.deepEqual(seen.predictions, []);
    results.longSource = { sourceId: sourceLong.id, frameCount, indexRequests: indexPages, pageLimit, toggledDuringLoad: true };
    await page.close();
  }

  results.fixtureOrigin = 'GENERATED; synthetic protocol mechanics only, not held-out accuracy or human qualification';
  fs.writeFileSync(path.join(evidence, 'matrix-result.json'), JSON.stringify(results, null, 2));
  console.log(JSON.stringify(results, null, 2));
} finally {
  fs.writeFileSync(path.join(evidence, 'post-log.json'), JSON.stringify(postLog, null, 1));
  fs.writeFileSync(path.join(evidence, 'action-log.json'), JSON.stringify(actionLog, null, 1));
  await browser.close();
  await http.dispose();
}

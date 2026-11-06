// Real-backend qualification. Run qualify_http.py first against the same disposable server.
const { chromium, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

(async () => {
  const base = process.env.SC2150_BASE_URL;
  const evidence = process.env.SC2150_EVIDENCE;
  const url = new URL(base);
  assert.equal(url.hostname, '127.0.0.1');
  assert(url.port && url.port !== '8090');
  assert(evidence);
  const fixture = JSON.parse(fs.readFileSync(path.join(evidence, 'fixture-results.json')))[0];
  const browser = await chromium.launch({ headless: true, executablePath: process.env.SC2150_CHROMIUM || undefined });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  const errors = [];
  const checks = [];
  const apiBase = base + '/api/debug/mouse-videos';
  const initialHistory = await (await context.request.get(apiBase + '/sources/' + fixture.sourceId + '/reviews')).json();
  const historyCount = initialHistory.items.length;
  async function open() {
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/mouse/video');
    await page.getByRole('button', { name: 'Inspect source ' + fixture.sourceId, exact: true }).click();
    await page.locator('[data-review-analysis]').selectOption(fixture.analysisId);
    await page.locator('[data-frame-rows] button').first().click();
    await expect(page.locator('[data-preview-image]')).toBeVisible({ timeout: 60000 });
    return page;
  }
  async function save(page, status = 201) {
    const response = page.waitForResponse(r => r.url().endsWith('/reviews') && r.request().method() === 'POST');
    await page.locator('[data-review-save]').click();
    assert.equal((await response).status(), status);
  }
  // The analysis already carries a saved revision from qualify_http.py, so the editor resumes
  // with a seeded draft; every count assertion is relative to that baseline.
  const draftCount = async page => Number(await page.locator('[data-review-draft-count]').innerText());
  try {
    const first = await open();
    const second = await open();
    // The draft may resume saved cursor labels from an earlier revision; every later count is
    // relative to whatever baseline this source currently has.
    const seeded = await draftCount(first);
    const jobReads = {};
    first.on('request', request => {
      const match = request.url().match(/\/jobs\/([a-f0-9-]+)$/);
      if (match && request.method() === 'GET') jobReads[match[1]] = (jobReads[match[1]] || 0) + 1;
    });
    const overlay = first.locator('[data-preview-image]');
    const box = await overlay.boundingBox();
    assert(box);
    await overlay.click({ position: { x: box.width * .25, y: box.height * .5 } });
    await first.locator('[data-correction-kind]').selectOption('CURSOR_VISIBILITY');
    await expect(first.locator('[data-field="visibility"]')).toHaveValue('VISIBLE');
    await first.locator('[data-correction-add]').click();
    await expect(first.locator('[data-review-draft-count]')).toHaveText(String(seeded + 1));
    await first.locator('[data-review-reason]').fill('Synthetic browser visible point with conflict recovery');
    await first.locator('[data-source-use]').selectOption('TRAINING_REQUESTED');
    await second.locator('[data-review-reason]').fill('Concurrent source-use review');
    await second.locator('[data-source-use]').selectOption('TRAINING_DENIED');
    await save(second);
    await first.getByRole('button', { name: 'Inspect source ' + fixture.sourceId, exact: true }).click();
    await expect(first.locator('[data-review-draft-count]')).toHaveText(String(seeded + 1));
    await save(first, 409);
    await expect(first.locator('[data-review-conflict]')).toBeVisible();
    await expect(first.locator('[data-review-draft-count]')).toHaveText(String(seeded + 1));
    await first.locator('[data-review-reload]').click();
    await expect(first.locator('[data-review-conflict]')).toBeHidden();
    await expect(first.locator('[data-review-draft-count]')).toHaveText(String(seeded + 1));
    await save(first);
    await expect(first.locator('[data-review-history] li')).toHaveCount(historyCount + 2);
    await first.locator('[data-review-history] button').first().click();
    await expect(first.locator('[data-review-history-detail]')).toContainText('corrections/v1');
    checks.push('visible point, independent-page conflict, draft retention, reload/save, source-use, history');

    await first.locator('[data-frame-rows] button').first().click();
    await expect(first.locator('[data-preview-image]')).toBeVisible({ timeout: 60000 });
    await first.locator('[data-preview-mode]').selectOption('region');
    const region = await overlay.boundingBox();
    await first.mouse.move(region.x + region.width * .1, region.y + region.height * .2);
    await first.mouse.down();
    await first.mouse.move(region.x + region.width * .7, region.y + region.height * .8, { steps: 5 });
    await first.mouse.up();
    await first.locator('[data-correction-kind]').selectOption('TARGET_REGION');
    const beforeRegion = await draftCount(first);
    await first.locator('[data-correction-add]').click();
    await expect(first.locator('[data-review-draft-count]')).toHaveText(String(beforeRegion + 1));
    await first.locator('[data-review-reason]').fill('Synthetic browser region');
    await save(first);
    await expect(first.locator('[data-review-history] li')).toHaveCount(historyCount + 3);
    checks.push('source-pixel region selection and persisted review');

    await first.locator('[data-frame-rows] button').first().click();
    const frame = first.locator('[data-frame-ordinal]');
    // With automatic suggestions shown, arrows follow visible suggestions. Frame-exact stepping is
    // the contract once suggestions are hidden; the ordinal input itself never moves frames.
    const predictions = first.locator('[data-preview-predictions]');
    const suggestedBefore = Number(await frame.inputValue());
    await predictions.check();
    await first.locator('[data-review-frames]').focus();
    await first.keyboard.press('ArrowRight');
    await expect.poll(async () => Number(await frame.inputValue())).toBeGreaterThan(suggestedBefore);
    const suggestionOrdinal = Number(await frame.inputValue());
    await expect(first.locator('[data-preview-image]')).toBeVisible({ timeout: 60000 });
    await expect(first.locator('[data-preview-suggestion-status]')).toContainText('Suggested cursor on frame ' + suggestionOrdinal);
    await predictions.uncheck();
    await expect(first.locator('[data-preview-suggestion-status]')).toHaveText('Automatic suggestions hidden.');
    await first.locator('[data-review-frames]').focus();
    const before = Number(await frame.inputValue());
    await first.keyboard.press('ArrowRight');
    await expect(frame).toHaveValue(String(before + 1));
    await frame.focus();
    await first.keyboard.press('ArrowRight');
    await expect(frame).toHaveValue(String(before + 1));
    await expect(first.locator('[data-preview-image]')).toBeVisible({ timeout: 60000 });
    await first.setViewportSize({ width: 390, height: 844 });
    assert(await first.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1));
    await first.screenshot({ path: path.join(evidence, 'browser-narrow.png'), fullPage: true });
    const narrowPreview = await overlay.boundingBox();
    assert(narrowPreview.x >= 0 && narrowPreview.x + narrowPreview.width <= 391);
    assert.equal(await first.locator('[data-mouse-video]').evaluate(el => el.scrollLeft), 0);
    const AxeBuilder = require('@axe-core/playwright').default;
    const a11y = await new AxeBuilder({ page: first }).include('[data-mouse-video]').analyze();
    fs.writeFileSync(path.join(evidence, 'browser-a11y.json'), JSON.stringify(a11y, null, 2));
    assert.equal(a11y.violations.length, 0, JSON.stringify(a11y.violations.map(v => v.id)));
    checks.push('keyboard frame navigation, 390px preview bounds/table scroll isolation, axe zero violations (manual/incomplete checks retained)');

    const exportResponse = first.waitForResponse(r => r.url().endsWith('/exports') && r.request().method() === 'POST');
    await first.locator('[data-video-export]').click();
    const response = await exportResponse;
    assert.equal(response.status(), 201);
    const bundle = await response.json();
    const exportUrl = apiBase + '/exports/' + bundle.exportId;
    const bytes = await (await context.request.get(exportUrl)).body();
    const manifest = JSON.parse(bytes);
    assert.equal(require('node:crypto').createHash('sha256').update(bytes).digest('hex'), bundle.manifestChecksum.replace('sha256:', ''));
    assert(bytes.includes(Buffer.from('"previews"')));
    assert.equal(manifest.capabilities.trainingEligible, false);
    assert.deepEqual(await (await context.request.get(exportUrl)).body(), bytes);
    const source = await (await context.request.get(apiBase + '/sources/' + fixture.sourceId)).json();
    const nonce = await (await context.request.get(apiBase + '/write-nonce')).json();
    const successor = await context.request.post(apiBase + '/sources/' + fixture.sourceId + '/reviews', {
      headers: { 'X-Mouse-Video-Nonce': nonce.nonce }, data: {
        requestId: require('node:crypto').randomUUID(), analysisId: fixture.analysisId,
        expectedBaseRevision: source.reviewRevision, reason: 'Synthetic successor after pinned export',
        sourceUse: 'INSPECTION_ONLY', corrections: [],
      },
    });
    assert.equal(successor.status(), 201);
    assert.deepEqual(await (await context.request.get(exportUrl)).body(), bytes);
    checks.push('browser export creation, immutable repeat GET, pinned previews, training remains ineligible');
    const reviews = await (await context.request.get(apiBase + '/sources/' + fixture.sourceId + '/reviews')).json();
    fs.writeFileSync(path.join(evidence, 'browser-reviews.json'), JSON.stringify(reviews, null, 2));
    assert(reviews.items.some(r => r.sourceUse === 'TRAINING_REQUESTED'));
    assert(reviews.items.some(r => (r.corrections || []).some(c => c.kind === 'TARGET_REGION')));
    if (fixture.reconciliationJobIds) {
      const ids = fixture.reconciliationJobIds;
      for (let i = 0; i < ids.length; i++) {
        const cancelled = first.waitForResponse(r => r.url().endsWith('/cancel') && r.request().method() === 'POST');
        await first.locator('[data-video-jobs] button').first().click();
        const response = await cancelled;
        const id = response.url().match(/\/jobs\/([^/]+)\/cancel$/)[1];
        assert(ids.includes(id));
        assert.equal((await response.json()).status, 'NEEDS_RECONCILIATION');
        await expect(first.locator('[data-video-jobs] button')).toHaveCount(ids.length - i - 1);
        if (i === 0) {
          const next = ids.find(value => value !== id);
          const before = jobReads[next] || 0;
          await expect.poll(() => jobReads[next] || 0, { timeout: 6000 }).toBeGreaterThan(before);
        }
      }
      const before = ids.map(id => jobReads[id] || 0);
      await first.waitForTimeout(2600);
      assert.deepEqual(ids.map(id => jobReads[id] || 0), before);
      checks.push('lost-worker cancellation stays reconciliation-required, next-job polling restarts, settled polling stops');
    }
    assert.equal(errors.length, 0, errors.join('\n'));
    fs.writeFileSync(path.join(evidence, 'browser-results.json'), JSON.stringify({ checks, errors, pass: true }, null, 2));
    console.log(JSON.stringify({ pass: true, checks, errors }));
  } finally {
    await context.tracing.stop({ path: path.join(evidence, 'browser-trace.zip') });
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });

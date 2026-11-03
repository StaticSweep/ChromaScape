// SC-2150 R1.3 assisted-first UI interaction qualification against a disposable real backend.
//
// Requires an instance with the assisted analysis profile provisioned plus one completed assisted
// analysis: SC2150_BASE_URL, SC2150_SOURCE_ID, SC2150_ANALYSIS_ID, SC2150_EVIDENCE. Refuses the
// normal 8090 service. Assisted suggestions are detector output, never human truth.
const { chromium, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

async function allObservations(request, apiBase, sourceId, analysisId) {
  const items = [];
  let cursor = null;
  do {
    const suffix = '?limit=500&kind=CURSOR_VISIBILITY' + (cursor ? '&cursor=' + encodeURIComponent(cursor) : '');
    const response = await request.get(`${apiBase}/sources/${sourceId}/analyses/${analysisId}/observations${suffix}`);
    assert.equal(response.status(), 200);
    const page = await response.json();
    assert.equal(page.available, true);
    items.push(...page.items);
    cursor = page.nextCursor;
  } while (cursor);
  return items;
}

(async () => {
  const base = process.env.SC2150_BASE_URL;
  const evidence = process.env.SC2150_EVIDENCE;
  const sourceId = process.env.SC2150_SOURCE_ID;
  const analysisId = process.env.SC2150_ANALYSIS_ID;
  const url = new URL(base);
  assert.equal(url.hostname, '127.0.0.1', 'A disposable loopback server is required');
  assert(url.port && url.port !== '8090', 'Refusing the normal 8090 service');
  assert(Number(url.port) >= 18190 && Number(url.port) <= 18199, 'Disposable verification ports are 18190-18199');
  assert(evidence && sourceId && analysisId, 'SC2150_EVIDENCE, SC2150_SOURCE_ID and SC2150_ANALYSIS_ID are required');
  fs.mkdirSync(evidence, { recursive: true });
  const apiBase = base + '/api/debug/mouse-videos';
  const browser = await chromium.launch({ headless: true, executablePath: process.env.SC2150_CHROMIUM || undefined });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  const errors = [];
  const checks = [];
  const watch = page => {
    page.on('pageerror', error => errors.push('pageerror: ' + error.message));
    page.on('console', message => {
      if (message.type() === 'error') errors.push('console: ' + message.text());
    });
  };
  const deepLink = base + '/mouse/video?source=' + sourceId;
  const ordinalOf = async page => Number(await page.locator('[data-frame-ordinal]').inputValue());
  const jump = async (page, ordinal) => {
    const input = page.locator('[data-frame-ordinal]');
    await input.fill(String(ordinal));
    await input.dispatchEvent('change');
  };
  const suggestionMarkers = page => page.locator('[data-preview-overlay] circle.prediction-point');
  const statusText = page => page.locator('[data-preview-suggestion-status]');
  // Marker centre in CSS pixels against the rendered image box: the overlay uses the same
  // xMidYMid-meet box as the image, so scale is uniform per axis over the source viewBox.
  const markerAgainstImage = async (page, observation) => {
    const marker = suggestionMarkers(page).first();
    await expect(marker).toBeVisible({ timeout: 60000 });
    const box = await page.locator('[data-preview-image]').boundingBox();
    const markerBox = await marker.boundingBox();
    const metadata = await page.locator('[data-preview-image]').evaluate(image => ({
      width: image.naturalWidth, height: image.naturalHeight }));
    assert(box && markerBox && metadata.width > 0 && metadata.height > 0);
    const scaleX = box.width / metadata.width;
    const scaleY = box.height / metadata.height;
    const expected = {
      x: box.x + Number(observation.x) * scaleX,
      y: box.y + Number(observation.y) * scaleY,
    };
    const actual = { x: markerBox.x + markerBox.width / 2, y: markerBox.y + markerBox.height / 2 };
    return { dx: Math.abs(expected.x - actual.x), dy: Math.abs(expected.y - actual.y), box, expected, actual };
  };

  let observations;
  let visible;
  try {
    observations = await allObservations(context.request, apiBase, sourceId, analysisId);
    visible = observations
      .filter(item => item.visibility === 'VISIBLE' && Number.isFinite(Number(item.x)) && Number.isFinite(Number(item.y)))
      .map(item => ({ ordinal: Number(item.frameOrdinal), x: Number(item.x), y: Number(item.y) }))
      .sort((left, right) => left.ordinal - right.ordinal);
    assert(visible.length > 10, 'the assisted analysis must publish visible cursor suggestions');
    const artifactBefore = (await (await context.request.get(
      `${apiBase}/sources/${sourceId}/analyses/${analysisId}/observations?limit=1`)).json()).artifactChecksum;
    assert(artifactBefore, 'the observation artifact must be published');

    const page = await context.newPage();
    watch(page);

    // 1. Deep link to an assisted source reaches an automatic track without manual labelling.
    await page.goto(deepLink);
    await expect(page.locator('[data-video-analysis-status]')).toContainText('Automatic track ready', { timeout: 90000 });
    await page.screenshot({ path: path.join(evidence, 'ui-01-auto-track.png'), fullPage: false });
    checks.push('deep link reports Automatic track ready');

    // 2. Re-running the analyze action reuses the completed analysis and queues nothing.
    const before = await (await context.request.get(apiBase + '/sources/' + sourceId)).json();
    await page.locator('[data-video-analyze]').click();
    await expect(page.locator('[data-video-analysis-status]')).toContainText('Automatic track ready', { timeout: 90000 });
    const after = await (await context.request.get(apiBase + '/sources/' + sourceId)).json();
    assert.equal(after.analyses.length, before.analyses.length, 're-running analysis must not create an analysis');
    assert.equal(
      after.jobs.filter(job => job.kind === 'ANALYSIS').length,
      before.jobs.filter(job => job.kind === 'ANALYSIS').length,
      're-running analysis must not queue a new ANALYSIS job');
    checks.push('re-run reuses the completed analysis and queues no new job');

    // 3. The suggestion toggle hides and restores the layer, and Next/Previous follow VISIBLE
    //    suggestions with the buttons disabling at the first and last suggestion.
    const predictions = page.locator('[data-preview-predictions]');
    assert.equal(await predictions.isChecked(), true);
    await predictions.uncheck();
    await expect(statusText(page)).toHaveText('Automatic suggestions hidden.');
    assert.equal(await suggestionMarkers(page).count(), 0);
    await predictions.check();
    const lastSuggestion = visible[visible.length - 1].ordinal;
    const firstSuggestion = visible[0].ordinal;
    await jump(page, visible[visible.length - 2].ordinal);
    await expect(page.locator('[data-frame-next]')).toBeEnabled();
    await page.locator('[data-frame-next]').click();
    await expect.poll(() => ordinalOf(page)).toBe(lastSuggestion);
    await expect(statusText(page)).toContainText('Suggested cursor on frame ' + lastSuggestion);
    await expect(page.locator('[data-frame-next]')).toBeDisabled();
    await expect(suggestionMarkers(page)).toHaveCount(1);
    const lastObservation = visible[visible.length - 1];
    const lastMap = await markerAgainstImage(page, lastObservation);
    assert(lastMap.dx <= 2 && lastMap.dy <= 2, JSON.stringify(lastMap));
    await jump(page, visible[1].ordinal);
    await page.locator('[data-frame-prev]').click();
    await expect.poll(() => ordinalOf(page)).toBe(firstSuggestion);
    await expect(page.locator('[data-frame-prev]')).toBeDisabled();
    checks.push('suggestion toggle plus Next/Previous follow VISIBLE ordinals and disable at the ends');

    // 4. A frame without a usable suggestion draws no marker and says so; Next suggestion recovers.
    const visibleOrdinals = new Set(visible.map(item => item.ordinal));
    const ambiguousOrdinals = new Set(
      observations.filter(item => item.visibility !== 'VISIBLE').map(item => Number(item.frameOrdinal)));
    const unsampled = 573;
    const probe = ambiguousOrdinals.has(unsampled) || !visibleOrdinals.has(unsampled)
      ? unsampled
      : (observations.map(item => Number(item.frameOrdinal)).find(ordinal => !visibleOrdinals.has(ordinal)) ?? 573);
    await jump(page, probe);
    await expect(statusText(page)).toContainText(
      ambiguousOrdinals.has(probe) ? 'uncertain or not visible' : 'No sampled cursor prediction for this frame');
    await expect(suggestionMarkers(page)).toHaveCount(0);
    await page.screenshot({ path: path.join(evidence, 'ui-02-no-suggestion.png'), fullPage: false });
    await page.locator('[data-frame-next]').click();
    await expect.poll(() => ordinalOf(page)).toBeGreaterThan(probe);
    const recovered = await ordinalOf(page);
    assert(visibleOrdinals.has(recovered), 'Next suggestion must land on a VISIBLE suggestion');
    await expect(statusText(page)).toContainText('Suggested cursor on frame ' + recovered);
    checks.push(`frame ${probe} shows no suggestion marker and Next suggestion recovers to ${recovered}`);

    // 5. With suggestions off the buttons step exactly one frame, including across a rendered
    //    index page boundary; with suggestions on the same boundary keeps the selected row current.
    await predictions.uncheck();
    await expect(statusText(page)).toHaveText('Automatic suggestions hidden.');
    await jump(page, 100);
    await expect(page.locator('[data-frame-rows] tr[aria-current="true"]')).toHaveCount(1);
    const windowStart = async () =>
      Number(await page.locator('[data-frame-rows] tr').first().locator('td').first().innerText());
    assert.equal(await windowStart(), 0);
    await page.locator('[data-frame-next]').click();
    await expect.poll(() => ordinalOf(page)).toBe(101);
    assert.equal(await windowStart(), 1, 'the rendered index page must advance across the boundary');
    await page.locator('[data-frame-prev]').click();
    await expect.poll(() => ordinalOf(page)).toBe(100);
    assert.equal(await windowStart(), 0, 'the rendered index page must move back across the boundary');
    await predictions.check();
    const boundarySuggestion = visible.filter(item => item.ordinal >= 100)[0];
    await jump(page, boundarySuggestion.ordinal);
    const currentRow = page.locator('[data-frame-rows] tr[aria-current="true"]');
    await expect(currentRow).toHaveCount(1);
    assert.equal(Number(await currentRow.locator('td').first().innerText()), boundarySuggestion.ordinal);
    await page.locator('[data-frame-next]').click();
    await expect.poll(() => ordinalOf(page)).toBeGreaterThan(boundarySuggestion.ordinal);
    await expect(page.locator('[data-frame-rows] tr[aria-current="true"]')).toHaveCount(1);
    checks.push('suggestions off step one frame across the index page boundary; suggestions on keep the row current');

    // 6. Marker geometry survives a viewport change and still maps onto the source coordinates.
    await page.setViewportSize({ width: 1440, height: 900 });
    const geometryOrdinal = visible[Math.floor(visible.length / 3)].ordinal;
    await jump(page, geometryOrdinal);
    const geometryObservation = visible.find(item => item.ordinal === geometryOrdinal);
    await expect(page.locator('[data-preview-image]')).toBeVisible({ timeout: 60000 });
    const wide = await markerAgainstImage(page, geometryObservation);
    assert(wide.dx <= 2 && wide.dy <= 2, JSON.stringify(wide));
    await page.screenshot({ path: path.join(evidence, 'ui-03-wide.png'), fullPage: false });
    await page.setViewportSize({ width: 800, height: 1000 });
    await jump(page, visible[Math.floor(visible.length / 3) + 1].ordinal);
    const narrowObservation = visible[Math.floor(visible.length / 3) + 1];
    await expect(page.locator('[data-preview-image]')).toBeVisible({ timeout: 60000 });
    const narrow = await markerAgainstImage(page, narrowObservation);
    assert(narrow.dx <= 2 && narrow.dy <= 2, JSON.stringify(narrow));
    assert(narrow.box.width <= 800);
    await page.screenshot({ path: path.join(evidence, 'ui-04-narrow.png'), fullPage: false });
    fs.writeFileSync(path.join(evidence, 'ui-geometry.json'), JSON.stringify({ wide, narrow }, null, 2));
    checks.push('marker centre maps to the observation source coordinates within 2 px at 1440x900 and 800x1000');

    // 7. Switching to another source while a preview request is in flight never shows A's marker.
    const clip = path.join(evidence, 'switch-target.mp4');
    execFileSync('ffmpeg', ['-v', 'error', '-nostdin', '-y', '-f', 'lavfi', '-i',
      'testsrc2=size=160x96:rate=30:duration=2', '-c:v', 'libx264', clip]);
    const nonce = (await (await context.request.get(apiBase + '/write-nonce')).json()).nonce;
    const upload = await context.request.post(apiBase + '/imports/upload', {
      headers: { 'X-Mouse-Video-Nonce': nonce, 'X-Mouse-Video-Request-Id': crypto.randomUUID() },
      multipart: {
        metadata: { name: 'metadata', mimeType: 'application/json',
          buffer: Buffer.from('{"declaredOrigin":"GENERATED","taskKey":"ui-switch-target"}') },
        file: { name: 'file', mimeType: 'video/mp4', buffer: fs.readFileSync(clip) },
      },
    });
    assert.equal(upload.status(), 202, await upload.text());
    const imported = await upload.json();
    const importDeadline = Date.now() + 180000;
    let importJob;
    while (Date.now() < importDeadline) {
      importJob = await (await context.request.get(apiBase + '/jobs/' + imported.jobId)).json();
      if (['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT'].includes(importJob.status)) break;
      await page.waitForTimeout(250);
    }
    assert.equal(importJob.status, 'SUCCEEDED', JSON.stringify(importJob));
    const otherSourceId = imported.sourceId;
    const otherSource = await (await context.request.get(apiBase + '/sources/' + otherSourceId)).json();
    let released;
    const release = new Promise(resolve => { released = resolve; });
    let delayed = false;
    await page.route('**/frame-previews**', async route => {
      if (delayed) return route.continue();
      delayed = true;
      await Promise.race([release, page.waitForTimeout(5000)]);
      return route.continue();
    });
    await page.setViewportSize({ width: 1440, height: 900 });
    await jump(page, visible[visible.length - 3].ordinal);
    await expect.poll(() => delayed).toBe(true);
    await page.locator('[data-video-refresh]').click();
    await page.getByRole('button', { name: 'Inspect source ' + otherSourceId, exact: true }).click();
    await expect(page.locator('[data-video-metadata]')).toContainText(otherSource.originalChecksum);
    released();
    await expect(page.locator('[data-video-analysis-status]')).toBeVisible();
    await page.waitForTimeout(1500);
    await expect(suggestionMarkers(page)).toHaveCount(0);
    await expect(page.locator('[data-preview-image]')).toBeHidden();
    await expect(page.locator('[data-preview-suggestion-status]')).toContainText('No automatic cursor suggestions loaded');
    await expect(page.locator('[data-video-metadata]')).toContainText(otherSource.originalChecksum);
    await expect(page.locator('[data-video-analysis-status]')).toContainText('Generate suggested cursor positions');
    await page.unroute('**/frame-previews**');
    await page.screenshot({ path: path.join(evidence, 'ui-05-source-switch.png'), fullPage: false });
    checks.push('a delayed preview response from source A never marks source B and B keeps its own identity');

    // 8. A reviewer point correction saves, reloads and stays human review evidence: the detector
    //    artifact is unchanged and the correction lives only in the review revision.
    const saved = await context.request.get(apiBase + '/sources/' + sourceId + '/reviews');
    const reviewsBefore = (await saved.json()).items.length;
    await page.locator('[data-video-refresh]').click();
    await page.getByRole('button', { name: 'Inspect source ' + sourceId, exact: true }).click();
    await expect(page.locator('[data-video-analysis-status]')).toContainText('Automatic track ready', { timeout: 90000 });
    const correctionOrdinal = visible[visible.length - 5].ordinal;
    await jump(page, correctionOrdinal);
    const image = page.locator('[data-preview-image]');
    await expect(image).toBeVisible({ timeout: 60000 });
    const stage = await image.boundingBox();
    assert(stage);
    await image.click({ position: { x: stage.width * 0.4, y: stage.height * 0.6 } });
    await page.locator('[data-correction-kind]').selectOption('CURSOR_VISIBILITY');
    await expect(page.locator('[data-field="visibility"]')).toHaveValue('VISIBLE');
    const pointX = Number(await page.locator('[data-field="x"]').inputValue());
    const pointY = Number(await page.locator('[data-field="y"]').inputValue());
    await page.locator('[data-correction-add]').click();
    await expect(page.locator('[data-review-draft-count]')).toHaveText('1');
    await page.locator('[data-review-reason]').fill('SC-2150 assisted UI human point correction');
    const saveResponse = page.waitForResponse(
      response => response.url().endsWith('/reviews') && response.request().method() === 'POST');
    await page.locator('[data-review-save]').click();
    assert.equal((await saveResponse).status(), 201);
    await page.reload();
    await expect(page.locator('[data-video-analysis-status]')).toContainText('Automatic track ready', { timeout: 90000 });
    const reviewsAfter = await (await context.request.get(apiBase + '/sources/' + sourceId + '/reviews')).json();
    assert.equal(reviewsAfter.items.length, reviewsBefore + 1);
    const stored = reviewsAfter.items[0];
    const storedCorrection = (stored.corrections || []).find(item =>
      item.kind === 'CURSOR_VISIBILITY' && Number(item.frameOrdinal) === correctionOrdinal);
    assert(storedCorrection, JSON.stringify(stored));
    assert.equal(storedCorrection.visibility, 'VISIBLE');
    assert.equal(Number(storedCorrection.x), pointX);
    assert.equal(Number(storedCorrection.y), pointY);
    assert.match(stored.reason, /human point correction/);
    assert.equal(stored.sourceUse, 'INSPECTION_ONLY');
    const artifactAfter = (await (await context.request.get(
      `${apiBase}/sources/${sourceId}/analyses/${analysisId}/observations?limit=1`)).json()).artifactChecksum;
    assert.equal(artifactAfter, artifactBefore, 'the detector observation artifact must stay unchanged');
    await expect(page.locator('[data-review-history] li').first()).toContainText('corrections/v1');
    await expect(page.locator('[data-review-history] li').first()).toContainText('1 correction(s)');
    await page.locator('[data-review-history] button').first().click();
    await expect(page.locator('[data-review-history-detail]')).toContainText('human point correction');
    checks.push('human point correction persists across reload and never rewrites the detector artifact');

    // 9. Accessibility and page-error gates.
    const AxeBuilder = require('@axe-core/playwright').default;
    const a11y = await new AxeBuilder({ page }).include('[data-mouse-video]').analyze();
    fs.writeFileSync(path.join(evidence, 'ui-a11y.json'), JSON.stringify(a11y, null, 2));
    assert.equal(a11y.violations.length, 0, JSON.stringify(a11y.violations.map(violation => violation.id)));
    assert.equal(errors.length, 0, errors.join('\n'));
    fs.writeFileSync(path.join(evidence, 'ui-results.json'), JSON.stringify({
      pass: true, sourceId, analysisId, otherSourceId, checks, errors,
      observations: { total: observations.length, visible: visible.length },
    }, null, 2));
    console.log(JSON.stringify({ pass: true, checks }));
  } finally {
    await context.tracing.stop({ path: path.join(evidence, 'ui-trace.zip') });
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });

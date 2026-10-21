// Delay a real preview response and prove a newer frame retains the displayed image identity.
const { chromium, expect } = require('@playwright/test');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async () => {
  const base = process.env.SC2150_BASE_URL;
  const evidence = process.env.SC2150_EVIDENCE;
  const url = new URL(base);
  assert(url.hostname === '127.0.0.1' && url.port && url.port !== '8090');
  const fixture = JSON.parse(fs.readFileSync(path.join(evidence, 'fixture-results.json')))[0];
  const browser = await chromium.launch({headless:true});
  const context = await browser.newContext();
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  let held;
  let sawHeld;
  const intercepted = new Promise(resolve => { sawHeld = resolve; });
  try {
    await page.goto(base + '/mouse/video');
    await page.getByRole('button', {name:'Inspect source ' + fixture.sourceId, exact:true}).click();
    await page.locator('[data-review-analysis]').selectOption(fixture.analysisId);
    await page.route('**/frame-previews/*/media', async route => {
      if (!held) { held = route; sawHeld(); } else await route.continue();
    });
    await page.locator('[data-frame-rows] button').nth(3).click();
    let timer;
    try {
      await Promise.race([intercepted, new Promise((_,reject) => { timer = setTimeout(() => reject(new Error('No delayed preview request')), 60000); })]);
    } finally { clearTimeout(timer); }
    await page.locator('[data-frame-rows] button').nth(4).click();
    const image = page.locator('[data-preview-image]');
    await expect(image).toBeVisible({timeout:60000});
    await expect(page.locator('[data-frame-ordinal]')).toHaveValue('4');
    const selectedSrc = await image.getAttribute('src');
    assert(!held.request().url().endsWith(selectedSrc));
    await held.continue();
    await page.waitForTimeout(500);
    assert.equal(await image.getAttribute('src'), selectedSrc);
    await expect(image).toBeVisible();
    await expect(page.locator('[data-frame-ordinal]')).toHaveValue('4');
    assert.equal(errors.length,0,errors.join('\n'));
    fs.writeFileSync(path.join(evidence,'stale-preview-results.json'),JSON.stringify({pass:true,
      delayedFrame:3, selectedFrame:4, selectedImageUnchanged:true, errors},null,2)+'\n');
    console.log('PASS delayed frame 3 response cannot replace displayed frame 4');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode=1; });

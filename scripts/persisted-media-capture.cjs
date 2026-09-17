// Actual UI tour of persisted spans; never a new model run or synthetic chart.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
async function main() {
  const [base, evidencePath, output, playwrightRoot] = process.argv.slice(2);
  const origin = new URL(base);
  assert(origin.protocol === 'http:' && origin.hostname === '127.0.0.1' &&
         !origin.username && !origin.password);
  const evidence = JSON.parse(await fs.readFile(evidencePath, 'utf8'));
  assert.equal(evidence['external-export'], false);
  assert.equal(evidence['content-enabled'], false);
  const trace = evidence['process-b'].trace;
  assert.match(trace['trace-id'], /^[0-9a-f]{32}$/);
  const {chromium} = require(path.join(path.resolve(playwrightRoot), 'node_modules/playwright'));
  const browser = await chromium.launch({headless: true});
  let video;
  try {
    const context = await browser.newContext({viewport: {width: 1440, height: 1000},
      recordVideo: {dir: output, size: {width: 1440, height: 1000}}});
    const page = await context.newPage(); video = page.video();
    await page.route('**/*', route => {
      const req = route.request();
      return req.method() === 'GET' && new URL(req.url()).origin === origin.origin
        ? route.continue() : route.abort();
    });
    async function shot(name) {
      const text = await page.locator('body').innerText();
      assert(!/langfuse\.observation\.(input|output)|Authorization\s*[:=]|(?:api[_-]?key|secret)\s*[:=]/i.test(text));
      await page.screenshot({path: path.join(output, `oscope-${name}.png`), fullPage: true});
    }
    assert.equal((await page.goto(new URL('/oscope/telemetry?window=24h', origin).href,
      {waitUntil: 'networkidle'})).status(), 200);
    await shot('index'); await page.waitForTimeout(7000);
    assert.equal((await page.goto(new URL(`/oscope/telemetry/traces/${trace['trace-id']}`, origin).href,
      {waitUntil: 'networkidle'})).status(), 200);
    assert((await page.locator('body').innerText()).includes(evidence.run['run-id']));
    const names = await page.locator('.otel-span-name').allTextContents();
    for (const family of trace['span-families']) assert(names.includes(family));
    await shot('waterfall'); await page.waitForTimeout(7000);
    for (const family of ['model.chat', 'tool', 'steer']) {
      // Maintained viewer5723 renders <details><summary>. Use real expansion,
      // not injected attributes/CSS or guessed UI affordances.
      const span = page.locator('.otel-span-name').filter({hasText:
        new RegExp(`^\\s*${family.replaceAll('.', '\\.')}\\s*$`)}).first();
      const detail = span.locator('xpath=ancestor::details[1]');
      const summary = detail.locator('summary');
      await summary.scrollIntoViewIfNeeded();
      if (await detail.getAttribute('open') === null) await summary.click();
      assert(await detail.locator('.otel-span-meta').isVisible());
      await shot(family.replace('.', '-')); await page.waitForTimeout(7000);
    }
    await context.close();
    await video.saveAs(path.join(output, 'oscope-persisted-tour.webm'));
  } finally { await browser.close(); }
}
main().catch(() => {console.error('Persisted media capture failed; response content not logged.'); process.exitCode = 1;});

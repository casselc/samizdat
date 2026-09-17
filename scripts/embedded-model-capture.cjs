// Read-only capture of a qualified, reopened local demo. Never starts a model.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

async function main() {
  const [base, evidencePath, output, playwrightRoot] = process.argv.slice(2);
  assert(base && evidencePath && output && playwrightRoot,
    'usage: node scripts/embedded-model-capture.cjs BASE EVIDENCE OUTPUT PLAYWRIGHT_ROOT');
  const url = new URL(base);
  assert(url.protocol === 'http:' && url.hostname === '127.0.0.1' &&
    !url.username && !url.password && url.pathname === '/' && !url.search && !url.hash,
    'capture requires a credential-free loopback base URL');
  const evidence = JSON.parse(await fs.readFile(evidencePath, 'utf8'));
  assert(['completed', 'exhausted'].includes(evidence.run.status));
  assert.equal(evidence.verification.tests, 6);
  assert.equal(evidence.verification.assertions, 6);
  assert.equal(evidence.verification.failures, 0);
  assert.equal(evidence.verification.errors, 0);
  assert.equal(evidence['external-export'], false);
  assert.equal(evidence['content-enabled'], false);
  const recovered = evidence['process-b'];
  assert.equal(recovered.closed, true);
  assert.equal(recovered.graceful, true);
  assert.equal(recovered.trace['content-enabled'], false);
  assert.equal(recovered.trace['span-families'].length, 9);
  const traceId = recovered.trace['trace-id'];
  assert.match(traceId, /^[0-9a-f]{32}$/);
  const { chromium } = require(path.join(path.resolve(playwrightRoot), 'node_modules', 'playwright'));
  await fs.mkdir(output, {recursive: true});
  const browser = await chromium.launch({headless: true});
  try {
    const page = await browser.newPage({viewport: {width: 1440, height: 1000}});
    // All requests are read-only loopback. No HAR/traces/HTML/content artifact.
    await page.route('**/*', route => {
      const request = route.request();
      const target = new URL(request.url());
      return request.method() === 'GET' && target.origin === url.origin
        ? route.continue() : route.abort();
    });
    for (const [name, route] of [['index', '/oscope/telemetry?window=24h'],
                              ['trace', `/oscope/telemetry/traces/${traceId}`]]) {
      const response = await page.goto(new URL(route, url).href, {waitUntil: 'networkidle'});
      assert.equal(response.status(), 200);
      const text = await page.locator('body').innerText();
      assert(!/langfuse\.observation\.(input|output)|Authorization\s*[:=]|(?:api[_-]?key|secret)\s*[:=]/i.test(text),
        'refusing screenshot with content/credential markers');
      if (name === 'trace') {
        assert(text.includes(evidence.run['run-id']));
        const names = await page.locator('.otel-span-name').allTextContents();
        for (const family of recovered.trace['span-families']) assert(names.includes(family));
      }
      await page.screenshot({path: path.join(output, `oscope-${name}.png`), fullPage: true});
    }
  } finally { await browser.close(); }
}
main().catch(() => { console.error('Demo capture failed; no response content logged.'); process.exitCode = 1; });

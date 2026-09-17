// Safety boundary for the actual HTTP TUI: no request can mutate the reader.
const http = require('node:http');
const fs = require('node:fs');
const [base, ready, ledger] = process.argv.slice(2);
const target = new URL(base);
if (target.protocol !== 'http:' || target.hostname !== '127.0.0.1' ||
    target.username || target.password) throw Error('loopback only');
let gets = 0, rejected = 0;
const server = http.createServer((req, res) => {
  if (req.method !== 'GET') {
    rejected++;
    res.writeHead(405); res.end('Read-only capture'); return;
  }
  const destination = new URL(req.url, target);
  if (destination.origin !== target.origin) { res.writeHead(400); res.end(); return; }
  // These exact read handlers are used by the real TUI poller and trace page.
  const run = '67d79ff7-11d9-459a-8cb9-af49b733f506';
  const safe = ['/v1/runs', '/v1/harness/layout', '/v1/harness/project',
    '/oscope/telemetry', '/oscope/telemetry/refresh',
    '/oscope/telemetry/live.js', '/oscope/telemetry/viewer.js',
    '/favicon.ico'].includes(destination.pathname) ||
    new RegExp(`^/v1/runs/${run}(?:/(?:steps|approvals)|/branches/B1(?:/turns/(?:[1-9]|1[0-2]))?)?$`).test(destination.pathname) ||
    /^\/oscope\/telemetry\/traces\/[0-9a-f]{32}$/.test(destination.pathname);
  if (!safe) { rejected++; res.writeHead(403); res.end('Not a capture read route'); return; }
  gets++;
  const upstream = http.request(destination, {method: 'GET'}, reply => {
    res.writeHead(reply.statusCode, reply.headers); reply.pipe(res);
  });
  upstream.setTimeout(15000, () => upstream.destroy());
  upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
  upstream.end();
});
server.listen(0, '127.0.0.1', () => {
  fs.writeFileSync(ready, JSON.stringify({base: `http://127.0.0.1:${server.address().port}`}));
});
process.on('SIGTERM', () => {
  fs.writeFileSync(ledger, JSON.stringify({gets, rejected}));
  server.close(() => process.exit(rejected ? 1 : 0));
  setTimeout(() => process.exit(2), 2000).unref();
});

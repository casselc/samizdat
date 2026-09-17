#!/usr/bin/env python3
"""Loopback-only demo transport controls. Python stdlib; never calls a model.

Run with --jolt EXACT_SELECTED_BINARY --wrapper MANDATORY_CHEZ_WRAPPER.
HTTP body bytes are bounded, not HTTP headers or arbitrary bytes outside the
declared HTTP message. A dishonest smaller Content-Length ends that message;
this is not a proof about bytes sent beyond its boundary.
"""
import argparse
import http.server
import json
import pathlib
import subprocess
import threading
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jolt', required=True)
parser.add_argument('--wrapper', required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
payload = b'{"status":"ok","all_models_loaded":[{"model_name":"selected"}]}'
unicode_payload = payload[:-1] + b',"note":"' + ('é' * 32000).encode('utf-8') + b'"}'
hits = []
bad_headers = []
stopping = threading.Event()

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_): pass
    def do_GET(self):
        mode = self.path[1:]
        hits.append(mode)
        if self.headers.get('Authorization') or self.headers.get('Proxy-Authorization'):
            bad_headers.append(mode)
        self.close_connection = True
        try:
            if mode == 'partial-header':
                self.wfile.write(b'HTTP/1.1 200 OK\r\nContent-Length: ')
                self.wfile.flush()
                stopping.wait(11)
                return
            if mode == 'slow-header' and stopping.wait(11): return
            self.send_response(302 if mode == 'redirect' else 200)
            if mode == 'redirect':
                self.send_header('Location', '/redirect-target')
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            seed = unicode_payload if mode.startswith('unicode') else payload
            length = 65537 if mode.endswith('over') or mode == 'lying' else 65536
            body = seed + b' ' * (length-len(seed)) if mode not in ('valid', 'slow-header', 'trickle') else seed
            if mode.startswith('known') or mode in ('valid', 'slow-header'):
                self.send_header('Content-Length', str(len(body)))
            elif mode == 'lying': self.send_header('Content-Length', '65538')
            elif mode.startswith('chunk'): self.send_header('Transfer-Encoding', 'chunked')
            self.send_header('Connection', 'close')
            self.end_headers()
            if mode == 'trickle':
                for byte in body:
                    self.wfile.write(bytes([byte])); self.wfile.flush()
                    if stopping.wait(.5): return
            elif mode.startswith('chunk'):
                for offset in range(0, len(body), 1024):
                    piece = body[offset:offset+1024]
                    self.wfile.write(('%x\r\n' % len(piece)).encode()+piece+b'\r\n')
                self.wfile.write(b'0\r\n\r\n')
            else: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError): pass

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = False
thread = threading.Thread(target=server.serve_forever)
thread.start()
port = server.server_address[1]
cases = [('valid', True, None), ('known-limit', True, None), ('unknown-limit', True, None),
         ('chunk-limit', True, None), ('unicode-limit', True, None),
         ('unicode-over', False, 'health-size'), ('known-over', False, 'health-size'),
         ('unknown-over', False, 'health-size'), ('chunk-over', False, 'health-size'),
         ('lying', False, 'health-size'), ('redirect', False, 'health-http'),
         ('slow-header', False, 'health-timeout'), ('partial-header', False, 'health-timeout'),
         ('trickle', False, 'health-timeout')]
case_expr = '['+' '.join('["%s" %s %s]' % (p, str(e).lower(), ':'+r if r else 'nil')
                         for p, e, r in cases)+']'
expr = '''(require '[samizdat.demo.embedded-model :as d])
(let [failed (atom 0)]
  (doseq [[path expected expected-reason] %s]
    (let [start (System/nanoTime)
          result (try
                   (d/assert-loaded-model! "selected"
                     (d/bounded-health-get! (str "http://127.0.0.1:%s/" path)
                                           (+ (System/currentTimeMillis) 30000)))
                   {:ready? true}
                   (catch Throwable e
                     (prn :fixed-failure path
                          (select-keys (ex-data e) [:phase :reason :http-status]))
                     (assoc (select-keys (ex-data e) [:reason :http-status]) :ready? false)))
          elapsed (quot (- (System/nanoTime) start) 1000000)]
      (prn :framing-case path :expected expected :actual result :elapsed-ms elapsed)
      (when (or (not= expected (:ready? result))
                (not= expected-reason (:reason result))
                (and (= path "redirect") (not= 302 (:http-status result)))
                (> elapsed 15000)) (swap! failed inc))))
  (System/exit @failed))''' % (case_expr, port)
command = [str(pathlib.Path(args.wrapper).resolve()), str(pathlib.Path(args.jolt).resolve()),
           '-Srepro', '-A:telemetry:embedded-telemetry:telemetry-test', '-e', expr]
exitcode = 1
child = None
try:
    env = {'HOME': str(pathlib.Path.home()), 'PATH': '/usr/bin:/bin',
           'http_proxy': 'http://127.0.0.1:1', 'HTTPS_PROXY': 'http://127.0.0.1:1',
           'CURL_HOME': '/does/not/exist'}
    child = subprocess.Popen(command, cwd=root, env=env)
    try: exitcode = child.wait(timeout=150)
    except subprocess.TimeoutExpired:
        child.terminate()
        try: child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill(); child.wait(timeout=5)
        exitcode = 124
finally:
    stopping.set()
    server.shutdown(); server.server_close(); thread.join(timeout=5)
    settled = child is not None and child.poll() is not None and not thread.is_alive()
    print(json.dumps({'fixture_exit': exitcode, 'owned_jolt_terminal': settled,
                      'requests': len(hits), 'redirect_target_hits': hits.count('redirect-target'),
                      'authentication_headers': len(bad_headers)}), flush=True)
expected_hits = [path for path, _, _ in cases]
request_matrix_ok = sorted(hits) == sorted(expected_hits)
print(json.dumps({'request_matrix_exact': request_matrix_ok}), flush=True)
raise SystemExit(exitcode or (1 if not settled or not request_matrix_ok or bad_headers else 0))

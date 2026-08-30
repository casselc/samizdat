#!/bin/sh
# The COMPLETE durable state at the closure interruption point, captured
# BEFORE the kill.
#
# WHY THIS SCRIPT EXISTS IN THIS SHAPE. The convergence run's equivalent
# committed a file containing one line — the run-id — and nothing else. It was
# `set -eu` with `curl -m 30` as its second statement, against a controller
# that was at that moment inside a 900-second closure verification. curl timed
# out, `set -e` did what it is for, and the script exited having written the
# one line it had already printed. Nothing checked afterwards that the file
# said anything.
#
# So: the DB is the source, not the HTTP surface — the authoritative journal
# does not care whether the server is answering. Every section is guarded so
# one failure cannot silence the rest. And the file is VERIFIED for the fields
# §18 requires before anybody is allowed to send a signal.
set -u
EV=/home/chuck/opencode/src/js2-closure-evidence
TGT=/home/chuck/opencode/src/js2-closure-target
CTRL=/home/chuck/opencode/src/samizdat-controller-closure
DB="$EV/js2-closure.sqlite3"
OUT="$EV/pre-kill.txt"

{
python3 - "$DB" "$TGT" <<'PY'
import sqlite3, sys, json, os, subprocess, datetime
db, tgt = sys.argv[1], sys.argv[2]
c = sqlite3.connect('file:%s?mode=ro' % db, uri=True)
def one(sql, a=()):
    r = c.execute(sql, a).fetchone()
    return r[0] if r else None
def rows(sql, a=()):
    cur = c.execute(sql, a); cols=[d[0] for d in cur.description]
    return [dict(zip(cols,r)) for r in cur.fetchall()]

print("captured-at:", datetime.datetime.now().isoformat())
r = rows("SELECT id,status,max_turns,provider,model,terminal_reason FROM runs ORDER BY rowid")[-1]
rid = r['id']
print("run-id:", rid)
print("run-status:", r['status'])
print("run-terminal-reason:", r['terminal_reason'])
print("durable-budget-max-turns:", r['max_turns'])
print("provider/model:", r['provider'], "/", r['model'])
print("last-completed-turn:", one("SELECT max(turn) FROM turns WHERE run_id=?", (rid,)))
print("turn-count:", one("SELECT count(*) FROM turns WHERE run_id=?", (rid,)))
print("budget-extensions:", one("SELECT count(*) FROM budget_extensions"))

b = rows("SELECT * FROM evaluator_bindings WHERE run_id=?", (rid,))
assert b, "NO BINDING"
b = b[-1]
print("binding-id:", b['binding_id'])
print("instance-id:", b['instance_id'])
print("spec-coordinate:", b['spec_id'])
print("runtime-coordinate:", b['runtime'])
print("context-spec-coordinate:", b['context_spec'])
print("orientation-digest:", b['orientation_digest'])
bid = b['binding_id']

ev = rows("""SELECT e.id, e.binding_seq,
               (SELECT count(*) FROM evaluator_completions k WHERE k.eval_id=e.id) AS completed
             FROM evaluator_evals e WHERE e.binding_id=? ORDER BY e.binding_seq""", (bid,))
print("eval-count:", len(ev))
print("pending-eval-count:", sum(1 for x in ev if not x['completed']))
print("receipt-count:", one("""SELECT count(*) FROM evaluator_receipts r
      JOIN evaluator_evals e ON e.id=r.eval_id WHERE e.binding_id=?""", (bid,)))

def outcomes(op):
    return one("""SELECT count(*) FROM evaluator_receipts r
                  JOIN evaluator_evals e ON e.id=r.eval_id
                  WHERE e.binding_id=? AND r.op=? AND r.phase='outcome'""", (bid, op))
print("edit-outcome-count:", outcomes(':project/edit'))
print("run-outcome-count:", outcomes(':project/run'))
print("op-outcome-breakdown:", json.dumps(rows("""
      SELECT r.op, count(*) n FROM evaluator_receipts r
      JOIN evaluator_evals e ON e.id=r.eval_id
      WHERE e.binding_id=? AND r.phase='outcome' GROUP BY r.op ORDER BY r.op""", (bid,))))

print("--- edit receipts ---")
for x in rows("""SELECT r.phase, substr(coalesce(r.args,''),1,160) args,
                        substr(coalesce(r.result,''),1,200) result
                 FROM evaluator_receipts r JOIN evaluator_evals e ON e.id=r.eval_id
                 WHERE e.binding_id=? AND r.op=':project/edit' ORDER BY r.rowid""", (bid,)):
    print("  ", x['phase'], "|", x['args'], "|", x['result'])

print("--- run receipts ---")
for x in rows("""SELECT r.phase, substr(coalesce(r.args,''),1,200) args,
                        substr(coalesce(r.result,''),1,420) result
                 FROM evaluator_receipts r JOIN evaluator_evals e ON e.id=r.eval_id
                 WHERE e.binding_id=? AND r.op=':project/run' ORDER BY r.rowid""", (bid,)):
    print("  ", x['phase'], "|", x['args'], "|", x['result'])

print("--- inference causality ---")
print("epochs:", json.dumps(rows(
  "SELECT id,turn,model,closed_at FROM inference_epochs WHERE run_id=? ORDER BY rowid", (rid,))))
print("invocation-count:", one("SELECT count(*) FROM inference_invocations WHERE run_id=?", (rid,)))

print("--- sci context lifecycle identity ---")
ctx = rows("SELECT id,turn,data FROM events WHERE run_id=? AND kind='evaluator-context' ORDER BY id", (rid,))
print("context-events:", len(ctx))
for x in ctx: print("  ", x['id'], "turn", x['turn'], x['data'])
PY
echo "--- target digests ---"
for f in src/samizdat/util.clj test/samizdat/util_test.clj; do
  [ -f "$TGT/$f" ] && sha256sum "$TGT/$f"
done
echo "--- target mtimes ---"
for f in src/samizdat/util.clj test/samizdat/util_test.clj; do
  [ -f "$TGT/$f" ] && stat -c '%n mtime=%Y bytes=%s' "$TGT/$f"
done
echo "--- target tree ---"
git -C "$TGT" rev-parse HEAD
git -C "$TGT" status --porcelain
echo "--- execution-provider invocation count ---"
# The provider's counter is in-process; its DURABLE shadow is the number of
# completed project/run outcomes, which is printed above. What is checkable
# from outside the process is the manager table, next.
grep -c 'project-run invocation' "$EV/serve-1.log" 2>/dev/null || echo "0 (no log marker)"
echo "--- manager table ---"
smolvm machine ls 2>&1 || echo "(smolvm machine ls failed)"
echo "--- controller integrity ---"
git -C "$CTRL" rev-parse HEAD
echo "controller tracked-dirty lines: $(git -C "$CTRL" status --porcelain --untracked-files=no | wc -l)"
} > "$OUT" 2>&1

# VERIFY BEFORE ANYBODY SENDS A SIGNAL. A snapshot nobody checked is what the
# convergence run committed.
sync
[ -s "$OUT" ] || { echo "SNAPSHOT EMPTY: $OUT" >&2; exit 1; }
missing=''
for field in run-id: run-status: last-completed-turn: pending-eval-count: \
             binding-id: instance-id: spec-coordinate: runtime-coordinate: \
             context-spec-coordinate: orientation-digest: \
             durable-budget-max-turns: edit-outcome-count: run-outcome-count: \
             invocation-count: ; do
  grep -q "^$field" "$OUT" || missing="$missing $field"
done
for section in '^--- edit receipts ---$' '^--- run receipts ---$' \
               '^--- target digests ---$' '^--- target mtimes ---$' \
               '^--- manager table ---$' \
               '^--- sci context lifecycle identity ---$'; do
  grep -q "$section" "$OUT" || missing="$missing $section"
done
if [ -n "$missing" ]; then
  echo "SNAPSHOT INCOMPLETE, missing:$missing" >&2
  exit 1
fi
edits=$(sed -n 's/^edit-outcome-count: //p' "$OUT")
runs=$(sed -n 's/^run-outcome-count: //p' "$OUT")
pend=$(sed -n 's/^pending-eval-count: //p' "$OUT")
echo "snapshot: $(wc -l < "$OUT") lines, $(wc -c < "$OUT") bytes -> $OUT"
echo "precondition: edits=$edits runs=$runs pending=$pend"
if [ "${edits:-0}" -gt 0 ] && [ "${runs:-0}" -gt 0 ] && [ "${pend:-1}" -eq 0 ]; then
  echo "PRECONDITION MET: safe to SIGKILL"
  exit 0
fi
echo "PRECONDITION NOT MET: do not kill yet" >&2
exit 2

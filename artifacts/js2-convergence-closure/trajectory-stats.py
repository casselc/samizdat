#!/usr/bin/env python3
"""Trajectory summary for the JS2 evidence document.

The M4 attempt-2 script plus everything JS2 §28 adds: the execution receipts,
the leverage shapes an eval can have (observe / run / branch), and the number
of model roundtrips between an edit and the test evidence about it.
"""
import sqlite3, json, sys, collections, os, re

DB = os.environ.get("JS2DB", "/home/chuck/opencode/src/js2-closure-evidence/js2-closure.sqlite3")
c = sqlite3.connect("file:%s?mode=ro" % DB, uri=True)
rid = c.execute("select id from runs order by rowid desc limit 1").fetchone()[0]
run = c.execute("select id,status,model,max_turns,started_at,ended_at,terminal_reason "
                "from runs where id=?", (rid,)).fetchone()
print("run:", json.dumps(dict(zip(
    ["id", "status", "model", "max_turns", "started_at", "ended_at", "terminal_reason"], run))))

turns = c.execute("select turn,tool_name,category from turns where run_id=? order by turn",
                  (rid,)).fetchall()
print("turns:", len(turns))
print("  by tool:", json.dumps(dict(collections.Counter(t[1] for t in turns))))
print("  by category:", json.dumps(dict(collections.Counter(t[2] for t in turns))))

row = c.execute("select binding_id from evaluator_bindings where run_id=?", (rid,)).fetchone()
if not row:
    print("no bounded binding"); sys.exit(0)
bid = row[0]

binding = c.execute("select context_spec,runtime,orientation_digest from evaluator_bindings "
                    "where run_id=?", (rid,)).fetchone()
print("binding:")
print("  context-spec:", binding[0][:400])
print("  runtime:", binding[1][:120])
print("  orientation-digest:", binding[2])

evals = c.execute("""select e.id,e.binding_seq,e.source,
                       (select status from evaluator_completions k where k.eval_id=e.id) st
                     from evaluator_evals e where e.binding_id=? order by e.binding_seq""",
                  (bid,)).fetchall()
print("evals:", len(evals), json.dumps(dict(collections.Counter(e[3] for e in evals))))
defs = [e[1] for e in evals if "(def " in e[2] or "(defn " in e[2]]
print("  evals containing def/defn:", defs)

ops = c.execute("""select r.eval_id, r.op from evaluator_receipts r
                   join evaluator_evals e on e.id=r.eval_id
                   where e.binding_id=? and r.phase='outcome' order by r.rowid""",
                (bid,)).fetchall()
per_eval = collections.defaultdict(list)
for eid, op in ops:
    per_eval[eid].append(op)
counts = [len(per_eval.get(e[0], [])) for e in evals]
print("operations total:", len(ops))
print("  by op:", json.dumps(dict(collections.Counter(o[1] for o in ops))))
print("  per-eval:", counts)
print("  multi-operation evals:", sum(1 for n in counts if n > 1))
print("  ops/eval: %.2f" % (len(ops) / len(evals) if evals else 0))
print("  ops/model-turn: %.2f" % (len(ops) / len(turns) if turns else 0))

# ── JS2: the execution side ────────────────────────────────────────────────
RUN = ":project/run"
EDIT = ":project/edit"
OBS = {":project/read", ":project/list", ":project/search", ":project/stat"}

runs_ops = [o for o in ops if o[1] == RUN]
print("project/run:")
print("  executions:", len(runs_ops))
print("  runs/model-turn: %.2f" % (len(runs_ops) / len(turns) if turns else 0))
run_evals = {eid for eid, op in ops if op == RUN}
print("  evals containing a run:", len(run_evals))
print("  multi-operation evals containing a run:",
      sum(1 for e in evals if len(per_eval.get(e[0], [])) > 1 and RUN in per_eval.get(e[0], [])))

shapes = collections.Counter()
for e in evals:
    o = per_eval.get(e[0], [])
    if RUN not in o:
        continue
    src = e[2]
    observed = any(x in OBS for x in o)
    # A source that reads the structured result and decides on it, rather than
    # returning it whole for the model to read next turn.
    branched = bool(re.search(r"\b(if|cond|when|when-not|if-not|filter|remove|every\?|some)\b", src))
    analyzed = bool(re.search(r":exit|:stdout|:stderr|:status|get-in", src))
    key = ("observe+" if observed else "") + "run" + ("+analyze" if analyzed else "") + ("+branch" if branched else "")
    shapes[key] += 1
print("  eval shapes containing a run:", json.dumps(dict(shapes)))

# Repeated equivalent executions: the same argv asked for twice.
run_sigs = collections.Counter()
for a, in c.execute("""select r.args from evaluator_receipts r
                       join evaluator_evals e on e.id=r.eval_id
                       where e.binding_id=? and r.op=? and r.phase='intent'""", (bid, RUN)):
    run_sigs[a] += 1
print("  repeated equivalent executions:",
      json.dumps({k[:160]: v for k, v in run_sigs.items() if v > 1}))

# Model roundtrips between an edit and the next test evidence about it.
order = []
for eid, op in ops:
    if op in (EDIT, RUN):
        order.append(op)
gaps, pending = [], None
seq = {e[0]: e[1] for e in evals}
edit_at = None
for e in evals:
    o = per_eval.get(e[0], [])
    if EDIT in o:
        edit_at = e[1]
    if RUN in o and edit_at is not None:
        gaps.append(e[1] - edit_at)
        edit_at = None
print("  model roundtrips between an edit and its next execution:", gaps)

# ── repeated identical observations ────────────────────────────────────────
sig = collections.Counter()
for op, in c.execute("""select r.op||' '||coalesce(r.args,'') from evaluator_receipts r
                        join evaluator_evals e on e.id=r.eval_id
                        where e.binding_id=? and r.phase='intent'""", (bid,)):
    sig[op] += 1
print("  repeated observation signatures:",
      json.dumps({k[:160]: v for k, v in sig.items() if v > 1 and not k.startswith(RUN)}))

print("epochs:")
for e in c.execute("select id,turn,provider,model,adapter,config_digest,closed_at "
                   "from inference_epochs where run_id=? order by rowid", (rid,)):
    print("  ", json.dumps(dict(zip(
        ["id", "turn", "provider", "model", "adapter", "config_digest", "closed_at"], e))))
print("invocations:", c.execute("select count(*) from inference_invocations where run_id=?",
                                (rid,)).fetchone()[0])

print("sci context lifecycle:")
for i, d in c.execute("select id,data from events where run_id=? and kind='evaluator-context' "
                      "order by id", (rid,)):
    print("  ", i, d[:400])

print("interventions:")
for i in c.execute("select id,kind,issued_by,status,applied_at_turn,substr(payload,1,160) "
                   "from interventions where run_id=? order by id", (rid,)):
    print("  ", json.dumps(list(i)))

print("ship-verify events:")
for i, d in c.execute("select id,data from events where run_id=? and kind='ship-verify' order by id",
                      (rid,)):
    print("  ", i, d[:2000])

print("edit receipts:")
for ph, a, r in c.execute("""select r.phase,r.args,substr(coalesce(r.result,''),1,200)
                             from evaluator_receipts r
                             join evaluator_evals e on e.id=r.eval_id
                             where e.binding_id=? and r.op=? order by r.rowid""", (bid, EDIT)):
    print("  ", ph, (a or "")[:160], "|", r)

print("run receipts:")
for ph, a, r in c.execute("""select r.phase,r.args,substr(coalesce(r.result,''),1,400)
                             from evaluator_receipts r
                             join evaluator_evals e on e.id=r.eval_id
                             where e.binding_id=? and r.op=? order by r.rowid""", (bid, RUN)):
    print("  ", ph, (a or "")[:200], "|", r)

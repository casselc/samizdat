#!/bin/sh
cd /home/chuck/opencode/src/js2-closure-evidence
python3 - <<'PY'
import sqlite3, json
c=sqlite3.connect('file:js2-closure.sqlite3?mode=ro',uri=True)
def q(sql,a=()):
    cur=c.execute(sql,a); cols=[d[0] for d in cur.description]
    return [dict(zip(cols,r)) for r in cur.fetchall()]
r=q("SELECT id,status,max_turns,terminal_reason FROM runs ORDER BY rowid")[-1]
rid=r['id']; print("RUN",json.dumps(r))
print("BRANCH",json.dumps(q("SELECT id,status,inactive_reason FROM branches WHERE run_id=?",(rid,))))
ts=q("SELECT turn,tool_name,category,substr(coalesce(result,''),1,200) AS result FROM turns WHERE run_id=? ORDER BY turn",(rid,))
print("TURNS",len(ts))
for t in ts[-6:]: print("  ",json.dumps(t))
b=q("SELECT binding_id FROM evaluator_bindings WHERE run_id=?",(rid,))
if b:
    bid=b[0]['binding_id']
    ev=q("""SELECT e.id,e.binding_seq,
              (SELECT count(*) FROM evaluator_completions k WHERE k.eval_id=e.id) AS done,
              (SELECT status FROM evaluator_completions k WHERE k.eval_id=e.id) AS st,
              substr(e.source,1,200) AS src FROM evaluator_evals e WHERE e.binding_id=? ORDER BY e.binding_seq""",(bid,))
    print("EVALS",len(ev),"pending",sum(1 for x in ev if not x['done']))
    for x in ev[-4:]: print("  ",json.dumps(x))
    ops=q("""SELECT r.op,count(*) n FROM evaluator_receipts r
             JOIN evaluator_evals e ON e.id=r.eval_id
             WHERE e.binding_id=? AND r.phase='outcome' GROUP BY r.op""",(bid,))
    print("OPS",json.dumps(ops))
    edits=q("""SELECT count(*) n FROM evaluator_receipts r JOIN evaluator_evals e ON e.id=r.eval_id
               WHERE e.binding_id=? AND r.op=':project/edit' AND r.phase='outcome'""",(bid,))[0]['n']
    runs=q("""SELECT count(*) n FROM evaluator_receipts r JOIN evaluator_evals e ON e.id=r.eval_id
              WHERE e.binding_id=? AND r.op=':project/run' AND r.phase='outcome'""",(bid,))[0]['n']
    pend=sum(1 for x in ev if not x['done'])
    print("SIGKILL PRECONDITION (JS2 §22): edits=%d runs=%d pending=%d => %s"
          % (edits, runs, pend, "READY" if (edits>0 and runs>0 and pend==0) else "not yet"))
    last=q("""SELECT r.op,substr(coalesce(r.result,''),1,300) res FROM evaluator_receipts r
              JOIN evaluator_evals e ON e.id=r.eval_id
              WHERE e.binding_id=? AND r.op=':project/run' AND r.phase='outcome'
              ORDER BY r.rowid DESC LIMIT 2""",(bid,))
    for x in last: print("  RUN:",json.dumps(x))
print("EPOCHS",json.dumps(q("SELECT id,turn,model,closed_at FROM inference_epochs WHERE run_id=? ORDER BY rowid",(rid,))))
print("INVOCATIONS",len(q("SELECT id FROM inference_invocations WHERE run_id=?",(rid,))))
ev2=q("SELECT id,kind,substr(coalesce(data,''),1,220) AS data FROM events WHERE run_id=? ORDER BY id DESC LIMIT 6",(rid,))
print("EVENTS")
for e in ev2[::-1]: print("  ",json.dumps(e))
PY

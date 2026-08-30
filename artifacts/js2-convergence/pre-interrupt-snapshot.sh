#!/bin/sh
# Durable state at the JS2 interruption point, captured BEFORE the kill.
#
# JS2 §22 requires the interruption to fall after BOTH a committed project
# mutation and a completed project/run receipt, so the replay evidence covers
# an execution as well as a write. This script is what proves the precondition
# held before the kill, and the counters it prints are the ones a faithful
# replay must not move.
set -eu
cd /home/chuck/opencode/src/js2-converge-evidence
RID=${JS2_RUN_ID:?set JS2_RUN_ID}
TGT=/home/chuck/opencode/src/samizdat-target-conv
SUFFIX=${1:-1}
echo "run-id: $RID"
curl -s -m 30 "http://127.0.0.1:3994/v1/runs/$RID" > "pre-interrupt-run-$SUFFIX.json"
python3 - "$SUFFIX" <<'PY'
import json, sys
d=json.load(open('pre-interrupt-run-%s.json' % sys.argv[1])); e=d['evaluator']
print("status:", d['run']['status'], "max_turns:", d['run']['max_turns'])
print("binding:", json.dumps({k:e['binding'][k] for k in
      ('binding-id','instance-id','spec-id','runtime','orientation-digest','profile','capabilities','timeout-ms')}))
print("evaluations:", json.dumps(e['evaluations']))
print("operations.order:", e['operations']['order'])
print("operations.multi-operation:", e['operations']['multi-operation'])
print("epochs:", json.dumps([{k:x[k] for k in ('id','turn','provider','model','adapter','config_digest','closed_at')} for x in e['inference-epochs']]))
print("invocations:", len(e['inference-invocations']))
PY
echo "--- durable counters (replay must not move these) ---"
python3 -c "
import sqlite3
c=sqlite3.connect('file:js2-converge.sqlite3?mode=ro',uri=True)
print('evals        ', c.execute('select count(*) from evaluator_evals').fetchone()[0])
print('receipts     ', c.execute('select count(*) from evaluator_receipts').fetchone()[0])
print('completions  ', c.execute('select count(*) from evaluator_completions').fetchone()[0])
print('edit outcomes', c.execute(\"select count(*) from evaluator_receipts where op=':project/edit' and phase='outcome'\").fetchone()[0])
print('run outcomes ', c.execute(\"select count(*) from evaluator_receipts where op=':project/run' and phase='outcome'\").fetchone()[0])
print('pending evals', c.execute('select count(*) from evaluator_evals e where not exists(select 1 from evaluator_completions k where k.eval_id=e.id)').fetchone()[0])
print('last turn    ', c.execute('select max(turn) from turns').fetchone()[0])
print('budget       ', c.execute('select max_turns from runs order by rowid desc limit 1').fetchone()[0])
print('sci context lifecycle:')
for (dta,) in c.execute(\"select data from events where kind='evaluator-context' order by id\"): print('  ',dta)
"
echo "--- committed mutation receipts ---"
python3 -c "
import sqlite3
c=sqlite3.connect('file:js2-converge.sqlite3?mode=ro',uri=True)
for ph,a,r in c.execute(\"select phase,substr(coalesce(args,''),1,110),substr(coalesce(result,''),1,150) from evaluator_receipts where op=':project/edit' order by rowid\"):
    print(' ',ph,a,'|',r)
"
echo "--- completed execution receipts (environment + input coordinates) ---"
python3 -c "
import sqlite3
c=sqlite3.connect('file:js2-converge.sqlite3?mode=ro',uri=True)
for ph,a,r in c.execute(\"select phase,substr(coalesce(args,''),1,140),substr(coalesce(result,''),1,400) from evaluator_receipts where op=':project/run' order by rowid\"):
    print(' ',ph,a,'|',r)
"
echo "--- machine manager: nothing running before the kill ---"
smolvm machine ls 2>&1 || true
echo "--- target tree ---"
git -C "$TGT" status --porcelain
sha256sum "$TGT/src/samizdat/util.clj" "$TGT/test/samizdat/util_test.clj"
stat -c '%n mtime=%Y bytes=%s' "$TGT/src/samizdat/util.clj" "$TGT/test/samizdat/util_test.clj"
echo "--- controller integrity ---"
git -C /home/chuck/opencode/src/samizdat-controller-conv rev-parse HEAD
git -C /home/chuck/opencode/src/samizdat-controller-conv status --porcelain --untracked-files=no | wc -l

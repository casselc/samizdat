#!/bin/sh
# ci/js1-smolvm/consumer-run.sh — the clean-consumer lane: boot the pinned
# guest pack with the samizdat checkout mounted READ-ONLY, network
# DISABLED, scratch VM-local, and run bin/js1 check, bin/js1 smoke, and
# the SAMIZDAT_JS1_BOUNDARY_TEST=1 durable-restart suite inside it.
#
# Producer/consumer separation: this script performs no pin relaxation and
# no provisioning — it re-runs preflight.sh and producer-gate.sh and
# refuses unless both are green. The guest never sees the host checkout
# writably, never gets a network, and its only durable outputs are the
# bounded logs + receipts this script captures under the runner-owned CI
# dir. Teardown (stop + delete --force) runs under a trap, always.
#
# Receipts are EDN-lines: one complete map per line, appended as events
# happen, so a mid-lane failure never leaves an unclosed form.
#
# Exit 0: every guest step green. Exit 1: refused (prerequisites) or a
# step failed — receipts.edn names which.

set -eu
. "$(dirname "$0")/lib-lock.sh"

init_run_dir
RECEIPT="$RUN_DIR/receipts.edn"

# ── Prerequisites: compose the gates (fail closed before any VM) ─────────
if ! sh "$JS1CI_SCRIPT_DIR/preflight.sh" >> "$LOG_DIR/consumer-prereqs.log" 2>&1; then
  receipt_event "$RECEIPT" "consumer" "refused" " :reason :preflight-not-green"
  fail "preflight.sh did not pass — refusing to start any guest (see $LOG_DIR/consumer-prereqs.log)"
fi
if ! sh "$JS1CI_SCRIPT_DIR/producer-gate.sh" >> "$LOG_DIR/consumer-prereqs.log" 2>&1; then
  receipt_event "$RECEIPT" "consumer" "refused" " :reason :producer-gate-not-green"
  fail "producer-gate.sh did not pass — refusing to start any guest (see $LOG_DIR/consumer-prereqs.log)"
fi

LAUNCHER=${JS1_SMOLVM_LAUNCHER:-"$HOME/$(lock_get :smolvm/launcher-relpath)"}
PACK=${JS1_SMOLVM_PACK:-"$HOME/$(lock_get :guest-pack/path-relpath)"}
PACK_SHA=$(lock_get :guest-pack/sha256)

CPUS=$(lock_get :machine/cpus)
MEM=$(lock_get :machine/mem-mib)
SRC_RO=$(lock_get :guest/mount-source-ro)
GUEST_WORK=$(lock_get :guest/work-dir)
GUEST_JOLT=$(lock_get :guest/jolt-home)
GUEST_CHEZ=$(lock_get :guest/chez)
GUEST_HOME=$(lock_get :guest/home)

T_SETUP=$(lock_get :deadline/setup-seconds)
T_CHECK=$(lock_get :deadline/check-seconds)
T_SMOKE=$(lock_get :deadline/smoke-seconds)
T_BOUNDARY=$(lock_get :deadline/boundary-seconds)
T_TEARDOWN=$(lock_get :deadline/teardown-seconds)
T_TOTAL=$(lock_get :deadline/total-seconds)

# Guest environment — mirrors guest-recipe.edn :guest-env exactly (the
# static harness test pins that agreement). Unquoted expansion at the exec
# site is intentional: each word is one flag, values carry no spaces.
GUEST_ENV="-e HOME=$GUEST_HOME -e JOLT_HOME=$GUEST_JOLT -e JOLT_CHEZ=$GUEST_CHEZ -e JOLT_QUIET=1 -e PATH=/usr/local/bin:/usr/bin:/bin -e LC_ALL=C -e JS1_GUEST_SRC_RO=$SRC_RO -e JS1_GUEST_WORK=$GUEST_WORK"

# ── Teardown: unconditional, best-effort, bounded ────────────────────────
MACHINE_CREATED=0
teardown() {
  _rc=$?
  set +e
  if [ "$MACHINE_CREATED" = "1" ]; then
    timeout -s TERM -k 15 "$T_TEARDOWN" "$LAUNCHER" machine stop --name "$MACHINE_NAME" \
      >> "$LOG_DIR/teardown.log" 2>&1
    _stop=$?
    timeout -s TERM -k 15 "$T_TEARDOWN" "$LAUNCHER" machine delete --name "$MACHINE_NAME" --force \
      >> "$LOG_DIR/teardown.log" 2>&1
    _del=$?
    _gone=$("$LAUNCHER" machine ls --json 2>/dev/null | tr -d ' \n' \
            | grep -c "\"$MACHINE_NAME\"" || true)
    receipt_event "$RECEIPT" "consumer" "teardown" \
      " :teardown/stop-exit $_stop :teardown/delete-exit $_del :teardown/machine-gone $( [ "$_gone" = "0" ] && printf 'true' || printf 'false')"
    [ "$_gone" = "0" ] || warn "machine $MACHINE_NAME still listed after delete --force"
  fi
  exit "$_rc"
}
trap teardown EXIT INT TERM

# guest_exec <logfile> <guest-timeout-s> <step> — one bounded exec in the
# guest; the step receipt is recorded even when the step fails. The guest
# --timeout is the workload bound; the host `timeout` wrapper is the hard
# backstop (+45s margin). Per the verified smolvm 1.7.5 semantics in this
# ecosystem: exec --timeout is a HOST WAIT (exit 124 + 'command timed out
# after <N>ms'), the guest process may outlive it — teardown stop+delete
# is the kill backstop.
guest_exec() {
  _log=$1; _to=$2; _step=$3
  _t0=$(now_epoch)
  if run_logged "$_log" "$(( _to + 45 ))" \
    "$LAUNCHER" machine exec --name "$MACHINE_NAME" --workdir / \
    $GUEST_ENV \
    --timeout "${_to}s" \
    -- sh "$SRC_RO/ci/js1-smolvm/guest-setup.sh" "$_step"; then
    _rc=0
  else
    _rc=$?
  fi
  _dt=$(( $(now_epoch) - _t0 ))
  receipt_event "$RECEIPT" "consumer" "step" \
    " :step :$_step :exit $_rc :seconds $_dt :log \"logs/$(basename "$_log")\" :log-sha256 \"$(sha256sum "$_log" | cut -d' ' -f1)\""
  return "$_rc"
}

require_marker() { # <logfile> <marker> <step>
  grep -qF "$2" "$1" || fail "step $3 exited 0 but its log lacks the success marker '$2' — refusing to claim the step (see $1)"
}

# ── Source manifest (host side; compared against the guest's) ────────────
HOST_TREE_SHA=$( ( cd "$JS1CI_REPO_ROOT" \
  && find . -path ./.git -prune -o -type f -print0 | LC_ALL=C sort -z \
     | xargs -0 sha256sum ) | tee "$LOG_DIR/source-manifest.sha256" | sha256sum | cut -d' ' -f1 )
REPO_HEAD=$(git -C "$JS1CI_REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)

receipt_event "$RECEIPT" "consumer" "begin" \
  " :machine/name \"$MACHINE_NAME\" :machine/cpus $CPUS :machine/mem-mib $MEM :guest-pack/path \"$PACK\" :guest-pack/sha256 \"$PACK_SHA\" :source/tree-sha256 \"$HOST_TREE_SHA\" :samizdat/head \"$REPO_HEAD\" :network :disabled :source-mount :read-only :scratch :vm-local"

# ── Create + start + status contract ─────────────────────────────────────
deadline_check "$T_TOTAL" "machine create"
run_logged "$LOG_DIR/create.log" "$T_SETUP" \
  "$LAUNCHER" machine create --name "$MACHINE_NAME" --from "$PACK" \
  -v "$JS1CI_REPO_ROOT:$SRC_RO:ro" --cpus "$CPUS" --mem "$MEM" \
  || fail "machine create failed (see $LOG_DIR/create.log)"
MACHINE_CREATED=1

run_logged "$LOG_DIR/start.log" "$T_SETUP" \
  "$LAUNCHER" machine start --name "$MACHINE_NAME" \
  || fail "machine start failed (see $LOG_DIR/start.log)"

run_logged "$LOG_DIR/status.log" 60 \
  "$LAUNCHER" machine status --name "$MACHINE_NAME" --json \
  || fail "machine status failed (see $LOG_DIR/status.log)"
_status=$(tr -d ' \n' < "$LOG_DIR/status.log")
# The exact ready contract for this lane (the status JSON shape verified
# against smolvm 1.7.5 by the ecosystem's live isolation suites).
case $_status in *"\"name\":\"$MACHINE_NAME\""*) : ;; *) fail "status contract: name mismatch in: $_status" ;; esac
case $_status in *'"state":"running"'*) : ;; *) fail "status contract: not running: $_status" ;; esac
case $_status in *'"network":false'*) : ;; *) fail "status contract: NETWORK ENABLED — refusing the guest: $_status" ;; esac
case $_status in *'"ports":0'*) : ;; *) fail "status contract: ports exposed — refusing: $_status" ;; esac
case $_status in *'"mounts":1'*) : ;; *) fail "status contract: expected exactly 1 mount (source RO): $_status" ;; esac
say "machine $MACHINE_NAME running: network disabled, 0 ports, 1 RO mount"
receipt_event "$RECEIPT" "consumer" "status-contract" " :status/verified true"

# ── prepare: RO mount → VM-local scratch, manifest agreement ─────────────
deadline_check "$T_TOTAL" "guest prepare"
guest_exec "$LOG_DIR/setup.log" "$T_SETUP" "prepare" \
  || fail "guest prepare failed (see $LOG_DIR/setup.log)"
require_marker "$LOG_DIR/setup.log" "GUEST-PREPARE-OK" "prepare"
GUEST_TREE_SHA=$(sed -n 's/^GUEST-MANIFEST \([0-9a-f]\{64\}\).*/\1/p' "$LOG_DIR/setup.log" | head -1)
[ -n "$GUEST_TREE_SHA" ] || fail "guest printed no GUEST-MANIFEST digest (see $LOG_DIR/setup.log)"
[ "$GUEST_TREE_SHA" = "$HOST_TREE_SHA" ] \
  || fail "guest source tree digest $GUEST_TREE_SHA != host $HOST_TREE_SHA — the guest does not see exactly this checkout; refusing evidence"
say "guest source copy verified against host manifest"
receipt_event "$RECEIPT" "consumer" "manifest-verified" " :guest/tree-sha256 \"$GUEST_TREE_SHA\""

# ── check: locate + pin-check the baked runtime stack ────────────────────
deadline_check "$T_TOTAL" "bin/js1 check"
guest_exec "$LOG_DIR/check.log" "$T_CHECK" "check" \
  || fail "bin/js1 check failed in the guest (see $LOG_DIR/check.log)"
require_marker "$LOG_DIR/check.log" "js1 runtime stack: OK" "check"
require_marker "$LOG_DIR/check.log" "$(lock_get :jolt/sha)" "check"
require_marker "$LOG_DIR/check.log" "$(lock_get :sci/sha)" "check"
require_marker "$LOG_DIR/check.log" "$(lock_get :jolt-crypto/sha)" "check"

# ── smoke: the JS1 seam evidence in the guest ────────────────────────────
deadline_check "$T_TOTAL" "bin/js1 smoke"
guest_exec "$LOG_DIR/smoke.log" "$T_SMOKE" "smoke" \
  || fail "bin/js1 smoke failed in the guest (see $LOG_DIR/smoke.log)"
require_marker "$LOG_DIR/smoke.log" "SANDBOX-TEST OK" "smoke"
require_marker "$LOG_DIR/smoke.log" "GUEST-SMOKE-OK" "smoke"
receipt_event "$RECEIPT" "consumer" "smoke-summary" \
  " :smoke/summary \"$(grep -E '^Ran [0-9]+ tests' "$LOG_DIR/smoke.log" | head -1)\""

# ── boundary: durable restart across OS-process boundaries, in the guest ─
deadline_check "$T_TOTAL" "js1 boundary suite"
guest_exec "$LOG_DIR/boundary.log" "$T_BOUNDARY" "boundary" \
  || fail "js1 boundary suite failed in the guest (see $LOG_DIR/boundary.log)"
require_marker "$LOG_DIR/boundary.log" "0 failures, 0 errors" "boundary"
require_marker "$LOG_DIR/boundary.log" "GUEST-BOUNDARY-OK" "boundary"
receipt_event "$RECEIPT" "consumer" "boundary-summary" \
  " :boundary/summary \"$(grep -E '^Ran [0-9]+ tests' "$LOG_DIR/boundary.log" | head -1)\""

receipt_event "$RECEIPT" "consumer" "final" " :verdict :pass"
say "consumer lane PASS — evidence in $RUN_DIR"

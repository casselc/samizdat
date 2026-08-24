#!/bin/sh
# ci/js1-smolvm/lib-lock.sh — shared plumbing for the JS1×SmolVM lane.
# POSIX sh. Sourced by preflight.sh, producer-gate.sh, consumer-run.sh and
# build-guest-pack.sh; never executed directly.
#
# Owns: lock reads (the lock is one-scalar-per-line EDN so grep and
# clojure.edn read the SAME bytes), the runner-owned CI dir discipline
# (this harness never touches host temp dirs), bounded log capture,
# deadline arithmetic, and EDN receipt emission. Every gate fails CLOSED:
# a missing pin, a drifted pin, or an unknown shape is a refusal with a
# remedy, never a skip.

set -eu

# ── Layout ────────────────────────────────────────────────────────────────
# This file lives at <repo>/ci/js1-smolvm/lib-lock.sh.
JS1CI_SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
JS1CI_REPO_ROOT=$(CDPATH= cd -- "$JS1CI_SCRIPT_DIR/../.." && pwd -P)
JS1CI_LOCK="$JS1CI_SCRIPT_DIR/runtime-lock.edn"
JS1CI_RECIPE="$JS1CI_SCRIPT_DIR/guest-recipe.edn"

say()  { printf '%s\n' "js1-smolvm: $*"; }
warn() { printf '%s\n' "js1-smolvm: WARNING: $*" >&2; }
fail() { printf '%s\n' "js1-smolvm: FAIL: $*" >&2; exit 1; }

# ── Lock reads ────────────────────────────────────────────────────────────
# lock_get <namespaced-key> → the scalar on that line, quotes stripped.
# The lock keeps exactly one scalar per line; the static test enforces it.
lock_get() {
  # One scalar per line; the map's closing brace only ever rides the last
  # line, and no scalar value ends in '}', so stripping one trailing '}'
  # after right-trimming is exact for this file (the static test pins the
  # discipline).
  _v=$(grep -E "^ $1 " "$JS1CI_LOCK" | head -1 \
       | sed -E 's/^ [^ ]+ //; s/[[:space:]]*$//; s/}$//; s/[[:space:]]*$//')
  [ -n "$_v" ] || fail "runtime-lock.edn has no line for $1 — lock is incomplete or format drifted (want: one scalar per line)"
  case $_v in
    \"*\") _v=${_v#\"}; _v=${_v%\"} ;;
  esac
  printf '%s' "$_v"
}

# ── CI dir discipline ─────────────────────────────────────────────────────
# Every explicit host-side work product lives under
# $JS1_SMOLVM_CI_DIR/<run-id>. The harness never touches host /tmp; the
# unavoidable exceptions are OS/tooling state (smolvm's own machine store
# under its state dir, git's own plumbing, /dev/kvm) and are documented in
# docs/JS1_SMOLVM_CI.md.
require_ci_dir() {
  [ -n "${JS1_SMOLVM_CI_DIR:-}" ] || fail "JS1_SMOLVM_CI_DIR is unset.
  Set it to an absolute, runner-owned directory for this lane's artifacts
  (the workflow uses \${{ runner.temp }}/js1-smolvm). The harness refuses
  to guess a location, and never uses host /tmp."
  case $JS1_SMOLVM_CI_DIR in
    /*) : ;;
    *) fail "JS1_SMOLVM_CI_DIR must be absolute, got: $JS1_SMOLVM_CI_DIR" ;;
  esac
  case $JS1_SMOLVM_CI_DIR in
    /tmp|/tmp/*|/var/tmp|/var/tmp/*)
      fail "JS1_SMOLVM_CI_DIR must not be under /tmp or /var/tmp (got $JS1_SMOLVM_CI_DIR) — every explicit work dir for this lane is runner-owned under the CI artifact dir" ;;
  esac
  mkdir -p "$JS1_SMOLVM_CI_DIR" || fail "cannot create JS1_SMOLVM_CI_DIR=$JS1_SMOLVM_CI_DIR"
}

init_run_dir() {
  require_ci_dir
  # Run identity: explicit JS1_SMOLVM_RUN_ID wins (the workflow sets it from
  # github.run_id); otherwise reuse the run this CI dir started within the
  # last hour (so the three stages of one local invocation share a run
  # dir); otherwise mint one.
  if [ -n "${JS1_SMOLVM_RUN_ID:-}" ]; then
    RUN_ID=$JS1_SMOLVM_RUN_ID
  elif [ -f "$JS1_SMOLVM_CI_DIR/.latest-run" ] \
       && [ $(( $(now_epoch) - $(stat -c %Y "$JS1_SMOLVM_CI_DIR/.latest-run") )) -lt 3600 ]; then
    RUN_ID=$(cat "$JS1_SMOLVM_CI_DIR/.latest-run")
  elif [ -f "/proc/sys/kernel/random/uuid" ]; then
    RUN_ID="run-$(date +%Y%m%dT%H%M%SZ)-$(cat /proc/sys/kernel/random/uuid)"
  else
    RUN_ID="run-$(date +%Y%m%dT%H%M%SZ)-$$"
  fi
  printf '%s\n' "$RUN_ID" > "$JS1_SMOLVM_CI_DIR/.latest-run.$$"
  mv "$JS1_SMOLVM_CI_DIR/.latest-run.$$" "$JS1_SMOLVM_CI_DIR/.latest-run"
  RUN_DIR="$JS1_SMOLVM_CI_DIR/$RUN_ID"
  LOG_DIR="$RUN_DIR/logs"
  mkdir -p "$LOG_DIR" || fail "cannot create run dir $RUN_DIR"
  # Harness-spawned children inherit a runner-owned TMPDIR, not host /tmp.
  TMPDIR="$RUN_DIR/.tmpdir"
  mkdir -p "$TMPDIR"
  export TMPDIR
  if [ -f "/proc/sys/kernel/random/uuid" ]; then
    _uuid=$(cat /proc/sys/kernel/random/uuid)
  else
    _uuid="$$"
  fi
  MACHINE_NAME="$(lock_get :machine/name-prefix)$_uuid"
  export RUN_ID RUN_DIR LOG_DIR MACHINE_NAME
}

# ── Time / deadlines ──────────────────────────────────────────────────────
now_epoch() { date +%s; }
now_iso()   { date -u +%Y-%m-%dT%H:%M:%SZ; }

RUN_T0=$(now_epoch)

# deadline_check <budget-seconds> <stage> — refuse if the total deadline
# for the lane is already exhausted before starting <stage>.
deadline_check() {
  _elapsed=$(( $(now_epoch) - RUN_T0 ))
  if [ "$_elapsed" -ge "$1" ]; then
    fail "deadline exhausted before $2 could start (${_elapsed}s >= ${1}s budget)"
  fi
  say "deadline: ${_elapsed}s elapsed of ${1}s budget, starting: $2"
}

# ── Bounded log capture ───────────────────────────────────────────────────
# cap_log <file> — truncate an oversized step log to head+tail of half the
# per-step budget each, with a banner replacing the middle. Runs AFTER the
# step (the guest-side bound is the exec --timeout; this is the artifact
# bound). Uses only RUN_DIR scratch — never host /tmp.
cap_log() {
  _f=$1
  _max=$(lock_get :log/max-step-bytes)
  [ -f "$_f" ] || return 0
  _sz=$(stat -c %s "$_f")
  if [ "$_sz" -gt "$_max" ]; then
    _half=$(( _max / 2 ))
    _tmp="$RUN_DIR/.caplog.tmp"
    { head -c "$_half" "$_f"
      printf '\njs1-smolvm: [LOG TRUNCATED: %s bytes total, middle %s bytes elided]\n' "$_sz" "$(( _sz - _max ))"
      tail -c "$_half" "$_f"
    } > "$_tmp"
    mv "$_tmp" "$_f"
    warn "log $(basename "$_f") truncated to $_max bytes (was $_sz)"
  fi
}

# run_logged <logfile> <host-timeout-seconds> <argv...> — run one harness
# command with a host-side hard timeout, capturing stdout+stderr to the
# log, then capping it. Returns the command's exit code (124/137 on
# timeout) without aborting the caller.
run_logged() {
  _log=$1; _to=$2; shift 2
  set +e
  timeout -s TERM -k 30 "$_to" "$@" > "$_log" 2>&1
  _rc=$?
  set -e
  cap_log "$_log"
  return "$_rc"
}

# ── Receipts (EDN-lines) ──────────────────────────────────────────────────
# One complete EDN map per line, append-only. A mid-lane failure can never
# leave an unclosed form: every line parses on its own, in order.
# receipt_event <file> <stage> <event> [extra flat kvs as a pre-formatted
# string] — values are harness-generated scalars only (paths, digests,
# fixed keywords, ints), never free-form guest output.
receipt_event() {
  _f=$1; _stage=$2; _event=$3; _extra=${4:-}
  printf '{:receipt/schema 1 :stage "%s" :event "%s" :time "%s" :run/id "%s" :lock/sha256 "%s"%s}\n' \
    "$_stage" "$_event" "$(now_iso)" "${RUN_ID:-unknown}" \
    "$(sha256sum "$JS1CI_LOCK" | cut -d' ' -f1)" "$_extra" >> "$_f"
}

# repo_provenance — "HEAD (N tracked file(s) modified)" for receipts.
repo_provenance() {
  if [ -d "$JS1CI_REPO_ROOT/.git" ]; then
    _h=$(git -C "$JS1CI_REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)
    _n=$(git -C "$JS1CI_REPO_ROOT" status --porcelain --untracked-files=no 2>/dev/null | wc -l | tr -d ' ')
    printf '%s (%s tracked file(s) modified)' "$_h" "$_n"
  else
    printf 'unknown (not a git checkout)'
  fi
}

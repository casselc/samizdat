#!/bin/sh
# ci/js1-smolvm/preflight.sh — host gates for the JS1×SmolVM lane.
#
# Proves the host is one this lane is defined for (Linux x86_64 with KVM),
# the runner-owned CI dir is explicit and never host /tmp, the required
# tooling exists, the pinned smolvm launcher is present, and no stale
# js1ci-* machines survive from a previous run (any found are deleted and
# reported — teardown is not optional in this lane).
#
# Exit 0: all gates pass (receipt written). Exit 1: refused, actionable
# reason on stderr (receipt still written when a CI dir is available).

set -eu
. "$(dirname "$0")/lib-lock.sh"

VERDICT=":pass"

init_run_dir
REFUSALS="$RUN_DIR/refusals.txt"
: > "$REFUSALS"
LOG="$LOG_DIR/preflight.log"
: > "$LOG"

refuse() {
  # Record and continue collecting refusals so one run reports them all.
  printf '%s\n' "js1-smolvm: REFUSE: $*" | tee -a "$REFUSALS" >&2
}

# ── Platform: Linux x86_64 with KVM ───────────────────────────────────────
_os=$(uname -s)
_arch=$(uname -m)
[ "$_os" = "Linux" ] || refuse "host OS is $_os, need Linux (SmolVM KVM lane). Use the self-hosted linux/x64/kvm runner."
[ "$_arch" = "x86_64" ] || refuse "host arch is $_arch, need x86_64. The lock declares :platform/arch \"x86_64\"; do not silently retarget."
_kvm=$(lock_get :platform/kvm-device)
if [ ! -c "$_kvm" ]; then
  refuse "$_kvm is not a character device — KVM is unavailable. On the self-hosted runner: load kvm_amd/kvm_intel and udev-export /dev/kvm."
elif [ ! -r "$_kvm" ] || [ ! -w "$_kvm" ]; then
  refuse "$_kvm exists but is not rw for $(id -un). Remedy on the runner: usermod -aG kvm <runner-user> (or the equivalent udev rule), then re-dispatch."
fi

# ── Tooling (host side; the guest side is baked into the pack) ────────────
for t in git awk sed grep find sort xargs sha256sum timeout date stat head tail cut tr uname id cat; do
  command -v "$t" >/dev/null 2>&1 || refuse "required tool '$t' is not on PATH"
done

# ── The pinned smolvm launcher ────────────────────────────────────────────
LAUNCHER=${JS1_SMOLVM_LAUNCHER:-"$HOME/$(lock_get :smolvm/launcher-relpath)"}
if [ ! -x "$LAUNCHER" ]; then
  refuse "smolvm launcher not executable at $LAUNCHER. Provision smolvm $(lock_get :smolvm/version) on the runner (expected layout: \$HOME/$(lock_get :smolvm/launcher-relpath) + smolvm-bin + lib/), or set JS1_SMOLVM_LAUNCHER."
fi

# ── Repo layout ───────────────────────────────────────────────────────────
for rel in "$(lock_get :inventory/wrapper)" "$(lock_get :inventory/preflight)" \
           "$(lock_get :inventory/producer)" "$(lock_get :inventory/consumer)" \
           "$(lock_get :inventory/guest-setup)" "$(lock_get :inventory/lock)" \
           "$(lock_get :inventory/recipe)" "$(lock_get :inventory/fixtures)" \
           "$(lock_get :inventory/boundary-runner)" "$(lock_get :inventory/workflow)"; do
  [ -e "$JS1CI_REPO_ROOT/$rel" ] || refuse "inventory file missing from the checkout: $rel"
done
[ -x "$JS1CI_REPO_ROOT/bin/js1" ] || refuse "bin/js1 is not executable in this checkout"

# ── Stale machine sweep (js1ci-* must not survive between runs) ───────────
STALE=""
if [ -x "$LAUNCHER" ]; then
  STALE=$("$LAUNCHER" machine ls --json 2>/dev/null \
          | tr -d ' \n' \
          | grep -o '"name":"'"$(lock_get :machine/name-prefix)"'[^"]*"' \
          | sed 's/"name":"//; s/"//' || true)
  for m in $STALE; do
    warn "stale machine from an earlier run: $m — deleting (teardown is mandatory)"
    "$LAUNCHER" machine stop --name "$m" >/dev/null 2>&1 || true
    "$LAUNCHER" machine delete --name "$m" --force >/dev/null 2>&1 \
      || refuse "could not delete stale machine $m — remove it with: $LAUNCHER machine delete --name $m --force"
  done
fi

# ── Verdict + receipt ─────────────────────────────────────────────────────
{
  printf 'preflight at %s\n' "$(now_iso)"
  printf 'os=%s arch=%s kvm=%s\n' "$_os" "$_arch" "$_kvm"
  printf 'launcher=%s\n' "$LAUNCHER"
  printf 'run-dir=%s\n' "$RUN_DIR"
  if [ -s "$REFUSALS" ]; then printf 'refusals:\n'; cat "$REFUSALS"; fi
} >> "$LOG"

if [ -s "$REFUSALS" ]; then VERDICT=":refused"; fi
_stale_edn=$( [ -n "$STALE" ] && { for m in $STALE; do printf '"%s" ' "$m"; done | sed 's/ $//'; } || true )
receipt_event "$RUN_DIR/preflight-receipt.edn" "preflight" "verdict" \
  " :verdict $VERDICT :preflight/launcher \"$LAUNCHER\" :preflight/stale-swept [$_stale_edn] :samizdat/provenance \"$(repo_provenance)\""

if [ "$VERDICT" != ":pass" ]; then
  cat "$REFUSALS" >&2
  fail "preflight refused (see $LOG and $RUN_DIR/preflight-receipt.edn)"
fi
say "preflight OK (run dir: $RUN_DIR)"

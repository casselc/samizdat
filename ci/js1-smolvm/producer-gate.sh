#!/bin/sh
# ci/js1-smolvm/producer-gate.sh — pin and inventory gates for the
# JS1×SmolVM lane. Producer-side: proves the lock agrees with the repo's
# own pin authorities (bin/js1, deps.edn), the smolvm executor on this host
# is exactly the pinned build (version AND the real binary's sha256 — a
# version string alone is never trusted), the guest pack exists and matches
# the locked digest, and the repo-side inventory is complete.
#
# FAIL CLOSED everywhere: any drift or absence exits 1 with the remedy on
# stderr and a receipt in the run dir. An absent/unbuilt pack is a
# :refused verdict — never a fabricated or substituted guest.

set -eu
. "$(dirname "$0")/lib-lock.sh"

init_run_dir
REFUSALS="$RUN_DIR/refusals.txt"
: > "$REFUSALS"
LOG="$LOG_DIR/producer-gate.log"
: > "$LOG"

refuse() { printf '%s\n' "js1-smolvm: REFUSE: $*" | tee -a "$REFUSALS" >&2; }

WRAPPER="$JS1CI_REPO_ROOT/bin/js1"
DEPS="$JS1CI_REPO_ROOT/deps.edn"

# ── Gate 1: lock pins == bin/js1 pins (no restatement drift) ─────────────
# bin/js1 is the runtime authority; the lock only restates it for CI.
wrapper_var() { grep -E "^$1=" "$WRAPPER" | head -1 | cut -d= -f2-; }

w_jolt_sha=$(wrapper_var JOLT_SHA)
w_jolt_branch=$(wrapper_var JOLT_BRANCH)
w_jolt_url=$(wrapper_var JOLT_URL)
w_sci_sha=$(wrapper_var SCI_SHA)
w_sci_version=$(wrapper_var SCI_VERSION)

[ "$w_jolt_sha" = "$(lock_get :jolt/sha)" ] \
  || refuse "lock :jolt/sha $(lock_get :jolt/sha) != bin/js1 JOLT_SHA $w_jolt_sha. The lock restates the wrapper; fix the lock, never the wrapper from here."
[ "$w_jolt_branch" = "$(lock_get :jolt/branch)" ] \
  || refuse "lock :jolt/branch != bin/js1 JOLT_BRANCH ($w_jolt_branch)"
[ "$w_jolt_url" = "$(lock_get :jolt/url)" ] \
  || refuse "lock :jolt/url != bin/js1 JOLT_URL ($w_jolt_url)"
[ "$w_sci_sha" = "$(lock_get :sci/sha)" ] \
  || refuse "lock :sci/sha $(lock_get :sci/sha) != bin/js1 SCI_SHA $w_sci_sha"
[ "$w_sci_version" = "$(lock_get :sci/version)" ] \
  || refuse "lock :sci/version $(lock_get :sci/version) != bin/js1 SCI_VERSION $w_sci_version"

# ── Gate 2: lock crypto pin == deps.edn's jolt-crypto :git/sha ────────────
# Same extraction discipline as bin/js1's crypto_sha_from_deps.
deps_crypto_sha=$(awk '
  /jolt-lang\/jolt-crypto/ {inblock=1}
  inblock && /:git\/sha/ {
    if (match($0, /"[0-9a-f]{40}"/)) { print substr($0, RSTART + 1, 40); exit }
  }' "$DEPS")
[ -n "$deps_crypto_sha" ] || refuse "no jolt-lang/jolt-crypto :git/sha found in deps.edn — the JS1 digest substrate pin is missing"
[ "$deps_crypto_sha" = "$(lock_get :jolt-crypto/sha)" ] \
  || refuse "lock :jolt-crypto/sha $(lock_get :jolt-crypto/sha) != deps.edn pin $deps_crypto_sha. deps.edn is the single place that pin is stated; fix the lock."

# ── Gate 3: smolvm executor — version AND real-binary digest ─────────────
LAUNCHER=${JS1_SMOLVM_LAUNCHER:-"$HOME/$(lock_get :smolvm/launcher-relpath)"}
[ -x "$LAUNCHER" ] || refuse "smolvm launcher missing at $LAUNCHER (run preflight.sh for the full remedy)"

ver_out=$("$LAUNCHER" --version 2>/dev/null || echo "unreadable")
[ "$ver_out" = "smolvm $(lock_get :smolvm/version)" ] \
  || refuse "smolvm --version reports '$ver_out', need exactly 'smolvm $(lock_get :smolvm/version)'. Do not silently switch executor versions; re-provision the pinned release."

# Resolve the wrapper symlink chain to the real directory, then digest the
# ACTUAL smolvm-bin — never a descriptor file beside it.
_launcher_resolved=$(readlink -f "$LAUNCHER")
_bin="$(dirname "$_launcher_resolved")/$(lock_get :smolvm/bin-relpath)"
if [ ! -f "$_bin" ]; then
  refuse "smolvm-bin not found beside the launcher at $_bin — incomplete installation"
else
  _bin_sha=$(sha256sum "$_bin" | cut -d' ' -f1)
  [ "$_bin_sha" = "$(lock_get :smolvm/bin-sha256)" ] \
    || refuse "smolvm-bin sha256 $_bin_sha != locked $(lock_get :smolvm/bin-sha256). The executor bytes changed; investigate before any guest evidence is trusted."
fi

# ── Gate 4: guest pack — present, digest-pinned, or REFUSED ──────────────
# JS1_SMOLVM_BUILD_LANE=1 is set ONLY by build-guest-pack.sh: the build is
# what produces the pack, so its presence cannot be a precondition. Every
# other gate (pin drift, executor digest, inventory) still runs, and the
# build lane re-verifies the finished pack's digest before reporting it.
PACK=${JS1_SMOLVM_PACK:-"$HOME/$(lock_get :guest-pack/path-relpath)"}
PACK_STATUS=$(lock_get :guest-pack/status)
PACK_SHA=$(lock_get :guest-pack/sha256)

if [ "${JS1_SMOLVM_BUILD_LANE:-}" = "1" ]; then
  say "build lane: guest-pack presence gate skipped (this run produces the pack)"
elif [ "$PACK_STATUS" != ":built" ] || [ "$PACK_SHA" = "nil" ]; then
  refuse "the JS1 guest pack is not provisioned under this lock (:guest-pack/status is $PACK_STATUS).
  Remedy (producer lane, needs network + KVM, ~once per lock change):
    1. ci/js1-smolvm/build-guest-pack.sh            # builds per guest-recipe.edn, prints the pack sha256
    2. pin that sha256 into ci/js1-smolvm/runtime-lock.edn :guest-pack/sha256 and set :guest-pack/status :built
    3. place the pack at $PACK (or set JS1_SMOLVM_PACK)
  No pack, no guest run — this lane never fabricates one."
elif [ ! -f "$PACK" ]; then
  refuse "lock says the pack is built at sha256 $PACK_SHA but $PACK does not exist. Provision the pack artifact for this runner (see docs/JS1_SMOLVM_CI.md § Provisioning)."
else
  _actual=$(sha256sum "$PACK" | cut -d' ' -f1)
  [ "$_actual" = "$PACK_SHA" ] \
    || refuse "guest pack sha256 $_actual != locked $PACK_SHA. The pack bytes changed — treat as a supply-chain event, not a cache miss."
fi

# ── Gate 5: repo-side inventory is complete ───────────────────────────────
for key in :inventory/wrapper :inventory/preflight :inventory/producer \
           :inventory/consumer :inventory/guest-setup :inventory/lock \
           :inventory/recipe :inventory/fixtures :inventory/boundary-runner \
           :inventory/workflow; do
  rel=$(lock_get "$key")
  [ -e "$JS1CI_REPO_ROOT/$rel" ] || refuse "inventory file missing: $rel ($key)"
done

# ── Verdict + receipt ─────────────────────────────────────────────────────
VERDICT=":pass"
[ -s "$REFUSALS" ] && VERDICT=":refused"
{
  printf 'producer-gate at %s\n' "$(now_iso)"
  printf 'jolt=%s sci=%s (%s) crypto=%s\n' "$w_jolt_sha" "$w_sci_sha" "$w_sci_version" "$deps_crypto_sha"
  printf 'smolvm=%s bin-sha256=%s\n' "$ver_out" "${_bin_sha:-unverified}"
  printf 'pack=%s status=%s\n' "$PACK" "$PACK_STATUS"
  if [ -s "$REFUSALS" ]; then printf 'refusals:\n'; cat "$REFUSALS"; fi
} >> "$LOG"

_pack_sha_edn=$( [ "$PACK_SHA" = "nil" ] && printf 'nil' || printf '"%s"' "$PACK_SHA" )
receipt_event "$RUN_DIR/producer-receipt.edn" "producer-gate" "verdict" \
  " :verdict $VERDICT :pins/jolt \"$w_jolt_sha\" :pins/sci \"$w_sci_sha\" :pins/sci-version \"$w_sci_version\" :pins/jolt-crypto \"$deps_crypto_sha\" :smolvm/version \"$ver_out\" :smolvm/bin-sha256 \"${_bin_sha:-unverified}\" :guest-pack/path \"$PACK\" :guest-pack/status $PACK_STATUS :guest-pack/sha256 $_pack_sha_edn :samizdat/provenance \"$(repo_provenance)\""

if [ "$VERDICT" != ":pass" ]; then
  cat "$REFUSALS" >&2
  fail "producer gate refused (see $LOG and $RUN_DIR/producer-receipt.edn)"
fi
say "producer gates OK"

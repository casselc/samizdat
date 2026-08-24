#!/bin/sh
# ci/js1-smolvm/build-guest-pack.sh — PRODUCER lane: build the pinned JS1
# guest pack per guest-recipe.edn. Needs network + KVM. Run deliberately,
# once per lock/recipe change, on a machine that may reach the network;
# the consumer lane then runs the pack offline, digest-verified.
#
# NOT run by the workflow and not run by any consumer step. On completion
# it prints the pack sha256 to pin into runtime-lock.edn — a human edits
# the lock; this script never does.
#
# Flow:
#   1. preflight + producer pin gates (build-lane mode: pack presence is
#      skipped, every pin-drift and executor gate still runs)
#   2. fetch + verify (or pin-on-first-build) the alpine minirootfs
#   3. stage the pinned Chez payload (digest-verified against the recipe)
#   4. create a BUILD vm from the unpacked rootfs (network ON, build only),
#      provision: apk packages, baked jolt checkout at the exact pin with
#      the vendor/sci submodule, warm gitlibs/m2 via the pinned jolt's own
#      resolver (jolt -P + bin/js1 path against a copy of this checkout)
#   5. stop, `smolvm pack create --from-vm`, sha256 the pack, emit
#      pack-manifest.edn, delete the build vm
#
# Fail-closed throughout: any pin mismatch aborts; the build VM is always
# torn down. Nothing here touches the consumer lane's evidence value —
# the consumer re-verifies the pack digest and re-runs bin/js1's own pin
# checks inside the sealed guest.

set -eu
. "$(dirname "$0")/lib-lock.sh"

init_run_dir
BUILD_DIR="$RUN_DIR/build"
mkdir -p "$BUILD_DIR"
LOG="$LOG_DIR/build-guest-pack.log"
: > "$LOG"

say "producer build lane — needs network + KVM; log: $LOG"

# ── 1. gates (build-lane mode) ────────────────────────────────────────────
JS1_SMOLVM_BUILD_LANE=1 sh "$JS1CI_SCRIPT_DIR/preflight.sh" >> "$LOG" 2>&1 \
  || fail "preflight refused — see $LOG"
JS1_SMOLVM_BUILD_LANE=1 sh "$JS1CI_SCRIPT_DIR/producer-gate.sh" >> "$LOG" 2>&1 \
  || fail "producer pin gates refused — see $LOG (pin drift is never built over)"

LAUNCHER=${JS1_SMOLVM_LAUNCHER:-"$HOME/$(lock_get :smolvm/launcher-relpath)"}
PACK=${JS1_SMOLVM_PACK:-"$HOME/$(lock_get :guest-pack/path-relpath)"}
BUILD_VM="$(lock_get :machine/name-prefix)build-$(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo $$)"

build_teardown() {
  _rc=$?
  set +e
  timeout -s TERM -k 15 120 "$LAUNCHER" machine stop --name "$BUILD_VM" >> "$LOG" 2>&1
  timeout -s TERM -k 15 120 "$LAUNCHER" machine delete --name "$BUILD_VM" --force >> "$LOG" 2>&1
  exit "$_rc"
}
trap build_teardown EXIT INT TERM

# ── 2. base rootfs: fetch + verify/pin-on-first-build ────────────────────
BASE_URL=$(grep -E '^ +:url ' "$JS1CI_RECIPE" | head -1 | sed -E 's/^[^"]*"([^"]*)".*/\1/')
BASE_SHA=$(grep -E '^ +:sha256 ' "$JS1CI_RECIPE" | head -1 | sed -E 's/^ +:sha256 +//; s/[[:space:]]*$//')
BASE_TAR="$BUILD_DIR/base.tar.gz"
[ -n "$BASE_URL" ] || fail "guest-recipe.edn :base/:url unreadable"
command -v curl >/dev/null 2>&1 || fail "curl is required for the producer build"
say "fetching base rootfs: $BASE_URL"
curl -fsSL --retry 3 --retry-delay 5 -o "$BASE_TAR" "$BASE_URL" \
  || fail "base rootfs fetch failed"
_actual_base=$(sha256sum "$BASE_TAR" | cut -d' ' -f1)
if [ "$BASE_SHA" = "nil" ]; then
  warn "recipe :base/sha256 is unpinned (pin-on-first-build)."
  warn "THIS build's base tarball sha256: $_actual_base"
  warn "pin it into guest-recipe.edn :base/sha256 before treating any derived pack as locked."
else
  [ "$_actual_base" = "$BASE_SHA" ] \
    || fail "base rootfs sha256 $_actual_base != recipe pin $BASE_SHA — refusing to build"
  say "base rootfs verified: $_actual_base"
fi

ROOTFS="$BUILD_DIR/rootfs"
mkdir -p "$ROOTFS"
tar -xzf "$BASE_TAR" -C "$ROOTFS"

# ── 3. Chez payload, digest-verified against the recipe ──────────────────
CHEZ_SHA=$(grep -E '^ +:payload-sha256 ' "$JS1CI_RECIPE" | head -1 | sed -E 's/^[^"]*"([^"]*)".*/\1/')
CHEZ_TAR="$BUILD_DIR/chez-payload.tar"
# Deterministic tar: sorted names, zeroed metadata — reproduces the pinned
# digest byte-for-byte from the build host's /usr/local Chez 10.4.1.
tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner -cf "$CHEZ_TAR" \
  -C /usr/local lib/csv10.4.1 bin/scheme bin/petite bin/chez bin/scheme-script \
  || fail "cannot stage the Chez payload from /usr/local (need a threaded Chez 10.4.1 ta6le install)"
_actual_chez=$(sha256sum "$CHEZ_TAR" | cut -d' ' -f1)
[ "$_actual_chez" = "$CHEZ_SHA" ] \
  || fail "chez payload sha256 $_actual_chez != recipe pin $CHEZ_SHA. The build host's Chez differs from the recipe — do not build; reconcile first."
mkdir -p "$ROOTFS/usr/local"
tar -xf "$CHEZ_TAR" -C "$ROOTFS/usr/local" \
  || fail "cannot extract the Chez payload into the rootfs"
say "chez payload verified and staged"

# Copy the provisioning script + this repo's dependency pins into the
# build context dir the build VM mounts.
CTX="$BUILD_DIR/ctx"
mkdir -p "$CTX/repo"
cp "$JS1CI_REPO_ROOT/deps.edn" "$CTX/repo/deps.edn"
cp -r "$JS1CI_REPO_ROOT/bin" "$CTX/repo/bin"
# The warm step resolves deps.edn + bin/js1 path only; src/test are not
# needed to warm caches (jolt -P expands deps without loading sources).
mkdir -p "$CTX/repo/src" "$CTX/repo/test"

# ── 4. build VM: create from the rootfs, provision, warm ─────────────────
JOLT_SHA=$(lock_get :jolt/sha)
JOLT_URL=$(lock_get :jolt/url)
JOLT_BRANCH=$(lock_get :jolt/branch)
SCI_SHA=$(lock_get :sci/sha)
SCI_VERSION=$(lock_get :sci/version)
GUEST_JOLT=$(lock_get :guest/jolt-home)
GUEST_HOME=$(lock_get :guest/home)

say "creating build VM $BUILD_VM (network ON — producer build only)"
timeout -s TERM -k 30 600 "$LAUNCHER" machine create --name "$BUILD_VM" \
  --image "$ROOTFS" --net --cpus 4 --mem 8192 \
  -v "$CTX:/build:ro" >> "$LOG" 2>&1 \
  || fail "build VM create failed — smolvm $(lock_get :smolvm/version) must accept an unpacked rootfs dir as --image (documented for machine run); see $LOG"
timeout -s TERM -k 30 300 "$LAUNCHER" machine start --name "$BUILD_VM" >> "$LOG" 2>&1 \
  || fail "build VM start failed — see $LOG"

bx() { # build-vm exec, bounded; output lands in the log AND on stdout
  _to=$1; shift
  _out="$BUILD_DIR/.bx-out"
  if timeout -s TERM -k 30 "$_to" "$LAUNCHER" machine exec --name "$BUILD_VM" \
    -e HOME="$GUEST_HOME" -e PATH=/usr/local/bin:/usr/bin:/bin -e LC_ALL=C \
    --timeout "${_to}s" -- "$@" > "$_out" 2>&1; then
    _rc=0
  else
    _rc=$?
  fi
  cat "$_out" >> "$LOG"
  cat "$_out"
  return "$_rc"
}

say "provisioning: apk packages"
bx 300 sh -c 'apk add --no-cache git >> /build-provision.log 2>&1' \
  || fail "apk provisioning failed — see $LOG"

say "provisioning: jolt checkout at $JOLT_SHA"
bx 900 sh -c "
  set -eu
  git clone --branch $JOLT_BRANCH $JOLT_URL $GUEST_JOLT
  git -C $GUEST_JOLT checkout $JOLT_SHA
  git -C $GUEST_JOLT submodule update --init vendor/sci
" || fail "jolt clone/checkout failed — see $LOG"

# Pin-verify INSIDE the build VM (the consumer re-verifies via bin/js1).
bx 60 git -C "$GUEST_JOLT" rev-parse HEAD > "$BUILD_DIR/.jolt-head" \
  || fail "cannot rev-parse the baked jolt checkout"
grep -q "$JOLT_SHA" "$BUILD_DIR/.jolt-head" \
  || fail "baked jolt checkout is not at the pin — see $LOG"
bx 60 git -C "$GUEST_JOLT/vendor/sci" rev-parse HEAD > "$BUILD_DIR/.sci-head" \
  || fail "cannot rev-parse the baked vendor/sci"
grep -q "$SCI_SHA" "$BUILD_DIR/.sci-head" \
  || fail "baked vendor/sci is not at the pin — see $LOG"
bx 60 cat "$GUEST_JOLT/vendor/sci/resources/SCI_VERSION" > "$BUILD_DIR/.sci-version" \
  || fail "cannot read the baked SCI_VERSION"
grep -q "$SCI_VERSION" "$BUILD_DIR/.sci-version" \
  || fail "baked SCI_VERSION is not $SCI_VERSION — see $LOG"

say "warming caches with the pinned jolt's own resolver"
bx 300 sh -c "cp -a /build/repo /tmp/warm-repo && mkdir -p /tmp/warm-repo"
bx 1500 sh -c "
  set -eu
  cd /tmp/warm-repo
  export JOLT_HOME=$GUEST_JOLT JOLT_QUIET=1 JOLT_CHEZ=/usr/local/bin/scheme
  $GUEST_JOLT/bin/jolt -P
  ./bin/js1 path
" || fail "cache warm failed (jolt -P / bin/js1 path) — see $LOG"

# Inventory the warm caches for the manifest.
bx 120 sh -c "find $GUEST_HOME/.gitlibs $GUEST_HOME/.m2 -type f 2>/dev/null | LC_ALL=C sort | xargs sha256sum" \
  > "$BUILD_DIR/cache-inventory.sha256" \
  || warn "cache inventory incomplete — see $LOG"

say "stopping build VM"
timeout -s TERM -k 30 120 "$LAUNCHER" machine stop --name "$BUILD_VM" >> "$LOG" 2>&1 \
  || fail "build VM stop failed — see $LOG"

# ── 5. pack + digest + manifest ──────────────────────────────────────────
mkdir -p "$(dirname "$PACK")"
timeout -s TERM -k 30 900 "$LAUNCHER" pack create --from-vm "$BUILD_VM" -o "$PACK" >> "$LOG" 2>&1 \
  || fail "pack create --from-vm failed — see $LOG"
PACK_ACTUAL=$(sha256sum "$PACK" | cut -d' ' -f1)

MANIFEST="$(dirname "$PACK")/pack-manifest.edn"
{
  printf '{:manifest/schema 1\n'
  printf ' :built-at "%s"\n' "$(now_iso)"
  printf ' :base {:url "%s" :sha256 "%s" :pinned? %s}\n' "$BASE_URL" "$_actual_base" "$( [ "$BASE_SHA" = "nil" ] && printf 'false' || printf 'true')"
  printf ' :chez-payload-sha256 "%s"\n' "$CHEZ_SHA"
  printf ' :jolt "%s"\n' "$JOLT_SHA"
  printf ' :sci "%s"\n' "$SCI_SHA"
  printf ' :sci-version "%s"\n' "$SCI_VERSION"
  printf ' :pack-sha256 "%s"\n' "$PACK_ACTUAL"
  printf ' :cache-inventory "%s"\n' "$BUILD_DIR/cache-inventory.sha256"
  printf '}\n'
} > "$MANIFEST"

receipt_event "$RUN_DIR/build-receipt.edn" "producer-build" "built" \
  " :guest-pack/path \"$PACK\" :guest-pack/sha256 \"$PACK_ACTUAL\" :base/pinned? $( [ "$BASE_SHA" = "nil" ] && printf 'false' || printf 'true')"

cat <<EOF
js1-smolvm: pack built: $PACK
  sha256: $PACK_ACTUAL
  manifest: $MANIFEST

TO PIN THIS PACK (a human edits the lock — this script never does):
  1. $([ "$BASE_SHA" = "nil" ] && echo "pin the base sha256 $_actual_base into guest-recipe.edn :base/sha256, then " || true)review $MANIFEST
  2. set ci/js1-smolvm/runtime-lock.edn :guest-pack/sha256 "$PACK_ACTUAL"
  3. set :guest-pack/status :built
The consumer lane refuses until all three are true.
EOF

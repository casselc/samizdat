#!/bin/sh
# ci/js1-smolvm/guest-setup.sh — runs INSIDE the SmolVM guest only.
# Invoked by consumer-run.sh via `smolvm machine exec`; never run on the
# host. POSIX sh, busybox-compatible (the guest is an Alpine rootfs).
#
# Steps:
#   prepare   copy the read-only source mount to VM-local scratch and
#             print the tree manifest digest (the host compares it against
#             its own — proves the guest sees exactly the checkout)
#   check     bin/js1 check   (locate + pin-check the baked runtime stack)
#   smoke     bin/js1 smoke   (the JS1 seam evidence, 28/268 at this pin)
#   boundary  SAMIZDAT_JS1_BOUNDARY_TEST=1 durable-restart suite through
#             the recorded -Scp classpath replay and the fixture runner
#
# Environment is passed explicitly by the consumer (HOME, JOLT_HOME,
# JOLT_CHEZ, JOLT_QUIET, PATH, LC_ALL per guest-recipe.edn). Scratch is
# VM-local (/work): nothing here writes to the read-only source mount, and
# nothing on the host is writable from in here.
#
# Guest /tmp is VM-local scratch and destroyed with the machine — the
# harness never uses HOST temp dirs, and the boundary suite's own phase
# dirs live in that guest-local scratch.

set -eu

SRC_RO=${JS1_GUEST_SRC_RO:-/opt/samizdat-src}
WORK=${JS1_GUEST_WORK:-/work}
PROJ="$WORK/samizdat"
FIXTURE_RUNNER="test/fixtures/js1/boundary_runner.clj"

# Same manifest discipline as the host side: sorted relative paths,
# per-file sha256, one tree digest over the whole stream. .git excluded
# (provenance is recorded separately via git rev-parse on the host).
tree_manifest_sha() {
  ( cd "$1" && find . -path ./.git -prune -o -type f -print0 \
      | LC_ALL=C sort -z | xargs -0 sha256sum ) | sha256sum | cut -d' ' -f1
}

step=${1:-}
case $step in
  prepare)
    mkdir -p "$PROJ" "$WORK/out"
    # RO mount → VM-local writable copy. Incidental writes (.cpcache, the
    # boundary suite's store files) land in scratch, never in the mount.
    cp -a "$SRC_RO/." "$PROJ/"
    printf 'GUEST-MANIFEST %s\n' "$(tree_manifest_sha "$PROJ")"
    printf 'GUEST-PREPARE-OK\n'
    ;;
  check)
    cd "$PROJ"
    bin/js1 check
    printf 'GUEST-CHECK-OK\n'
    ;;
  smoke)
    cd "$PROJ"
    bin/js1 smoke
    # SANDBOX-TEST OK is printed by the suite itself on success.
    printf 'GUEST-SMOKE-OK\n'
    ;;
  boundary)
    cd "$PROJ"
    # The recorded-classpath replay: bin/js1 composes the JS1 roots through
    # the pinned runtime's own resolver; -Scp replays them with no
    # dependency expansion (offline, deterministic). The runner fixture
    # drives samizdat.js1-boundary-test's suite mode, which spawns fresh
    # OS-process children for record/resume/mismatch/unsettled phases.
    CP=$(bin/js1 path)
    SAMIZDAT_JS1_BOUNDARY_TEST=1 \
    SAMIZDAT_JOLT_BIN="$JOLT_HOME/bin/jolt" \
    "$JOLT_HOME/bin/jolt" -Scp "$CP" run "$PROJ/$FIXTURE_RUNNER"
    printf 'GUEST-BOUNDARY-OK\n'
    ;;
  *)
    echo "guest-setup: unknown step '$step' (want: prepare|check|smoke|boundary)" >&2
    exit 2
    ;;
esac

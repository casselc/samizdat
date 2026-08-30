#!/bin/sh
# JS2 canary controller launcher.
#
# Runs the frozen JS2 controller checkout as the harness process, with the
# disposable target checkout as :run :root. Nothing here is committed into the
# controller tree; the controller checkout stays byte-identical to its SHA.
#
# Two isolation substrates are in play and they are deliberately DIFFERENT:
#   SAMIZDAT_VERIFY_ENV=bwrap        controller ACCEPTANCE, over a private copy
#   project execution (smolvm)       model DEVELOPMENT, in an ephemeral machine
# Neither can be reached by the other's authority, and neither can be selected
# by the model.
set -eu

CTRL=/home/chuck/opencode/src/samizdat-controller-js2
TARGET=/home/chuck/opencode/src/samizdat-canary-target-js2
JOLT=/home/chuck/opencode/src/jolt-js2
EV=/home/chuck/opencode/src/js2-evidence

JOLT_CHEZ=/usr/local/bin/scheme
PATH="$JOLT/bin:$PATH"
export JOLT_CHEZ PATH

# Trusted controller configuration, read once at process start. Never accepted
# from a run/resume request or a model tool.
HARNESS_PROVIDER=${JS2_PROVIDER:-openai}
HARNESS_BASE_URL=${JS2_BASE_URL:-http://127.0.0.1:13399/v1}
HARNESS_MODEL=${JS2_MODEL:-fireworks.kimi-k2p7-code}
HARNESS_TEMPERATURE=0.2
HARNESS_MAX_TOKENS=16384
HARNESS_ROOT="$TARGET"
HARNESS_DB="$EV/js2-canary.sqlite3"
HARNESS_MAX_TURNS=60
HARNESS_BEAM_WIDTH=1
HARNESS_PORT=3993
HARNESS_NREPL_PORT=7893
HARNESS_BUDGET_CEILING=200
HARNESS_BUDGET_PRINCIPAL=js2-operator
HARNESS_BUDGET_TOKEN=$(cat "$EV/.budget-token")

# The controller's acceptance environment.
SAMIZDAT_VERIFY_ENV=bwrap
# The model's development environment: the pinned guest image, by digest.
SAMIZDAT_SMOLVM_IMAGE="$EV/worker-image.tar"
SAMIZDAT_SMOLVM_IMAGE_SHA256=c5329b4b7ecb1ac816cb86c1f6e7f57737d5240a2306be84847c8279e80ad984
# The clean-target closure baseline, for the coverage delta.
SAMIZDAT_CLOSURE_BASELINE="$EV/closure-baseline.edn"

export HARNESS_PROVIDER HARNESS_BASE_URL HARNESS_MODEL HARNESS_TEMPERATURE \
       HARNESS_MAX_TOKENS HARNESS_ROOT HARNESS_DB HARNESS_MAX_TURNS \
       HARNESS_BEAM_WIDTH HARNESS_PORT HARNESS_NREPL_PORT \
       HARNESS_BUDGET_CEILING HARNESS_BUDGET_PRINCIPAL HARNESS_BUDGET_TOKEN \
       SAMIZDAT_VERIFY_ENV SAMIZDAT_SMOLVM_IMAGE SAMIZDAT_SMOLVM_IMAGE_SHA256 \
       SAMIZDAT_CLOSURE_BASELINE

cd "$CTRL"
# Raise the SOFT limit only, and only upward. `ulimit -n N` sets the hard limit
# too, and lowering a hard limit is irreversible for the whole process tree —
# which is how the first JS2 canary attempt ended up with every project/run
# failing EMFILE inside the guest while the guest's own limits were untouched.
if [ "$(ulimit -Sn)" -lt 65536 ] 2>/dev/null; then
  ulimit -Sn 65536 2>/dev/null || ulimit -Sn "$(ulimit -Hn)" 2>/dev/null || true
fi
exec "$JOLT/bin/jolt" \
  -Sdeps "{:deps {borkdude/sci {:local/root \"$JOLT/vendor/sci\"}}}" \
  -M -m samizdat.core

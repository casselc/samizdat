#!/bin/sh
set -eu

workspace=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
wrapper=${SAMIZDAT_DEMO_WRAPPER:-/home/chuck/ai-src/tools/jolt-with-chez-10.4.1}
jolt=${SAMIZDAT_DEMO_JOLT:-/home/chuck/ai-src/worktrees/jolt-aea91781-release-137/target/release/jolt}
libchdb=${SAMIZDAT_DEMO_CHDB_LIB:-/home/chuck/.cache/jolt-chdb/26.7.3/linux-amd64/libchdb.so}
base_url=${SAMIZDAT_DEMO_BASE_URL:-http://marvin.asymptote-city.ts.net:13305/v1}
model=${SAMIZDAT_DEMO_MODEL:-Qwen3.6-27B-MTP-GGUF}
timeout_ms=${SAMIZDAT_DEMO_TIMEOUT_MS:-1800000}
output=${SAMIZDAT_DEMO_OUTPUT:-target/embedded-model-demo}

cd "$workspace"
exec "$wrapper" "$jolt" \
  -M:telemetry:embedded-telemetry:embedded-model-demo -- \
  --wrapper "$wrapper" --jolt "$jolt" \
  --libchdb "$libchdb" \
  --base-url "$base_url" --model "$model" --timeout-ms "$timeout_ms" \
  --output "$output" "$@"

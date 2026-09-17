#!/usr/bin/env bash
# Local retained-public-fixture presentation only. Root must reserve runtime first.
set -euo pipefail
: "${PLAYWRIGHT_ROOT:?Provide the existing Playwright installation root}"
repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
jolt=/home/chuck/ai-src/worktrees/jolt-aea91781-release-137/target/release/jolt
wrapper=/home/chuck/ai-src/tools/jolt-with-chez-10.4.1
checkout=/home/chuck/ai-src/worktrees/samizdat-52-hybrid-tool-wrapper
# File identity gates precede all Jolt/native execution. No local builds.
printf '%s  %s\n' \
 7adde574ec02b1297cf3af792ba525f19ffa44be167093d07e54d4345fbe490b "$jolt" \
 36ad4e999882821ef13cf2d0f52c93f48e6ee1b4498b35a201c23ce4b8d15bf5 /home/chuck/.cache/jolt-chdb/26.7.3/linux-amd64/libchdb.so \
 4b1e622539d49a8186c1964c54813783e7047b47328c6369fc9efb7a3aede863 /home/chuck/ai-src/ftxui-jolt/native/libftxui_jolt.so \
 fb1830e3392a038205b7190d9d5c76597a1b1e73211ed0305163c5f2ce8079fc "$wrapper" | sha256sum -c -
[[ $(git -C /home/chuck/ai-src/ftxui-jolt rev-parse HEAD) == 3e591e98e86747fd59afe735034d8fbe569ac758 ]]
[[ $(git -C "$checkout" rev-parse HEAD) == ba7f35a65b3273b5241a9286f9b0926e72e4cbaf ]]
source_data=/home/chuck/ai-src/artifacts/samizdat-live-a-b-2026-09-16-parser-52/2b4ebdd8-8be6-41e6-aaf6-c9c8180e81f7
out=$(mktemp -d /home/chuck/ai-src/artifacts/samizdat-persisted-media-2026-09-17.XXXXXXXX)
data="$out/data"
mkdir "$data" "$out/tui" "$out/browser"
snapshot() {
  (cd "$1" && find durable project -type f -print0 | sort -z | xargs -0 sha256sum;
   sha256sum samizdat.sqlite3;
   if [[ -f samizdat.sqlite3-wal ]]; then sha256sum samizdat.sqlite3-wal; fi)
}
sql_snapshot() {
  sqlite3 -readonly "$1/samizdat.sqlite3" 'SELECT id,status FROM runs ORDER BY id; SELECT count(*) FROM turns; SELECT id,run_id,kind,status,applied_at_turn FROM interventions ORDER BY id;'
}
snapshot "$source_data" > "$out/original-before.sha256"
sql_snapshot "$source_data" > "$out/original-identity.txt"
cp -a -- "$source_data/durable" "$source_data/project" "$source_data/samizdat.sqlite3" "$data/"
# SQLite WAL is part of retained state if present; never discard it.
for suffix in -wal -shm; do
  if [[ -f "$source_data/samizdat.sqlite3$suffix" ]]; then cp -a -- "$source_data/samizdat.sqlite3$suffix" "$data/"; fi
done
sql_snapshot "$data" > "$out/copy-before-identity.txt"
cmp "$out/original-identity.txt" "$out/copy-before-identity.txt"
snapshot "$data" > "$out/copy-before.sha256"
viewer= proxy=
run_group() (
  local label=$1 duration=$2 pid pgid result=1 settled=0 observer= root_start requested remaining
  shift 2
  [[ "$duration" =~ ^([0-9]+)s$ ]] || return 64
  requested=${BASH_REMATCH[1]}
  remaining=$((globalwork_deadline-$(date +%s)-10))
  [[ "$remaining" -gt 0 ]] || return 124
  [[ "$requested" -le "$remaining" ]] || requested=$remaining
  duration="${requested}s"
  printf '%s\n' "$BASHPID" > "$out/$label.helper-pid"
  start_token() {
    local row tail
    [[ -r "/proc/$1/stat" ]] || return 1
    row=$(<"/proc/$1/stat"); tail=${row##*) }
    read -ra fields <<< "$tail"
    printf '%s\n' "${fields[19]}"
  }
  live_owned() {
    [[ "$1" =~ ^[0-9]+$ && "$1" -gt 1 && -n "$2" ]] &&
      [[ $(start_token "$1" 2>/dev/null || true) == "$2" ]]
  }
  settle_group() {
    local original=$? owner token remaining=0
    touch "$out/$label.observer-stop"
    if [[ -n "$observer" ]]; then wait "$observer" || true; fi
    # Snapshot identities, including observed descendants which changed groups.
    sort -u "$out/$label.owned-identities" -o "$out/$label.owned-identities"
    while read -r owner token; do
      if live_owned "$owner" "$token"; then kill -TERM "$owner" 2>/dev/null || true; fi
    done < "$out/$label.owned-identities"
    for ((n=0;n<50;n++)); do
      remaining=0
      while read -r owner token; do
        if live_owned "$owner" "$token"; then remaining=$((remaining+1)); fi
      done < "$out/$label.owned-identities"
      [[ "$remaining" == 0 ]] && break
      sleep .1
    done
    if [[ "$remaining" != 0 ]]; then
      while read -r owner token; do
        if live_owned "$owner" "$token"; then kill -KILL "$owner" 2>/dev/null || true; fi
      done < "$out/$label.owned-identities"
      for ((n=0;n<50;n++)); do
        remaining=0
        while read -r owner token; do
          if live_owned "$owner" "$token"; then remaining=$((remaining+1)); fi
        done < "$out/$label.owned-identities"
        [[ "$remaining" == 0 ]] && break
        sleep .1
      done
    fi
    if [[ ! -f "$out/$label.exit" ]]; then
      set +e; wait "$pid"; result=$?; set -e
      printf '%s\n' "$result" > "$out/$label.exit"
    fi
    [[ "$remaining" == 0 ]] && settled=1
    printf '%s\n' "$settled" > "$out/$label.observed-tree-settled"
    [[ "$settled" == 1 ]] || original=1
    exit "$original"
  }
  setsid timeout --kill-after=5s "$duration" "$@" > "$out/$label.log" 2>&1 &
  pid=$!
  [[ "$pid" =~ ^[0-9]+$ && "$pid" -gt 1 ]]
  root_start=$(start_token "$pid")
  printf '%s %s\n' "$pid" "$root_start" > "$out/$label.owned-identities"
  trap settle_group EXIT
  (
    while [[ ! -f "$out/$label.observer-stop" ]]; do
      # Observe parent relations while alive; retained start tokens prevent PID
      # reuse mistakes. This is bounded observed-tree proof, not containment of
      # arbitrary hostile descendants which evade every observation.
      ps -eo pid=,ppid= | awk -v root="$pid" '
        {parent[$1]=$2} END {seen[root]=1; changed=1;
        while(changed){changed=0;for(p in parent)if((parent[p] in seen)&&seen[parent[p]]&&!(p in seen)){seen[p]=1;changed=1}}
        for(p in seen) if(seen[p]) print p}' |
      while read -r owner; do
        token=$(start_token "$owner" 2>/dev/null || true)
        if [[ -n "$token" ]]; then printf '%s %s\n' "$owner" "$token" >> "$out/$label.owned-identities"; fi
      done
      sleep .1
    done
  ) &
  observer=$!
  printf '%s\n' "$observer" > "$out/$label.observer-pid"
  # setsid is asynchronous: wait for its actual transition instead of racing it.
  pgid=
  for ((n=0;n<50;n++)); do
    pgid=$(ps -o pgid= -p "$pid" | tr -d ' ' || true)
    [[ "$pgid" == "$pid" ]] && break
    live_owned "$pid" "$root_start" || break
    sleep .02
  done
  [[ "$pgid" == "$pid" ]] || return 1
  printf '%s\n' "$pid" > "$out/$label.pgid"
  set +e; wait "$pid"; result=$?; set -e
  printf '%s\n' "$result" > "$out/$label.exit"
  return "$result"
)
cleanup() {
  local final_status=$? original_compare identity_compare
  set +e
  # Signal completion, then JOIN the owner so its reviewed shutdown runs first.
  touch "$out/capture-done"
  if [[ -n "$proxy" ]]; then kill -TERM "$proxy" 2>/dev/null || true; wait "$proxy" || true; fi
  if [[ -n "$viewer" ]]; then wait "$viewer"; owner_exit=$?; printf '%s\n' "$owner_exit" > "$out/owner.exit"; fi
  # Preserve protection/identity observations even when presentation failed.
  snapshot "$source_data" > "$out/original-after.sha256"
  cmp "$out/original-before.sha256" "$out/original-after.sha256" > "$out/original-compare.log" 2>&1
  original_compare=$?
  printf '%s\n' "$original_compare" > "$out/original-compare.exit"
  sql_snapshot "$data" > "$out/copy-after-identity.txt"
  cmp "$out/copy-before-identity.txt" "$out/copy-after-identity.txt" > "$out/copy-identity-compare.log" 2>&1
  identity_compare=$?
  printf '%s\n' "$identity_compare" > "$out/copy-identity-compare.exit"
  snapshot "$data" > "$out/copy-after.sha256"
  if [[ "$original_compare" != 0 || "$identity_compare" != 0 ]]; then final_status=1; fi
  exit "$final_status"
}
trap cleanup EXIT
globalwork_deadline=$(($(date +%s)+270))
(
 cd /home/chuck/ai-src/worktrees/samizdat-52-hybrid-tool-wrapper
 timeout --kill-after=5s 360s env -i HOME=/home/chuck PATH=/home/chuck/.local/bin:/usr/bin:/bin \
   MEDIA_OUTPUT="$out" MEDIA_DATA="$data" "$wrapper" "$jolt" -Srepro -A:telemetry:telemetry-test \
   "$repo/scripts/persisted-media-viewer.clj"
) > "$out/owner.log" 2>&1 &
viewer=$!
for ((i=0;i<100;i++)); do
 [[ -f "$out/ready.json" ]] && break
 kill -0 "$viewer" || { echo 'Viewer terminated before readiness'; exit 1; }
 sleep 1
done
[[ -f "$out/ready.json" ]]
base=$(node -e 'console.log(require(process.argv[1]).base)' "$out/ready.json")
node "$repo/scripts/persisted-media-proxy.cjs" "$base" "$out/proxy-ready.json" "$out/proxy-ledger.json" > "$out/proxy.log" 2>&1 &
proxy=$!
for ((i=0;i<10;i++)); do [[ -f "$out/proxy-ready.json" ]] && break; sleep 1; done
base=$(node -e 'console.log(require(process.argv[1]).base)' "$out/proxy-ready.json")
export SAMIZDAT_TUI_LAYOUT="$repo/scripts/persisted-media-tui.edn"
printf -v SAMIZDAT_DEMO_TUI_COMMAND 'cd -- %q && env -i HOME=/home/chuck PATH=/home/chuck/.local/bin:/usr/bin:/bin SAMIZDAT_TUI_LAYOUT=%q %q %q -Srepro -Sdeps %q -m samizdat.tui.core %q' \
 "$checkout" "$SAMIZDAT_TUI_LAYOUT" "$wrapper" "$jolt" '{:paths ["src" "resources" "tui"] :deps {jlt-commons/ftxui-jolt {:local/root "/home/chuck/ai-src/ftxui-jolt"}}}' "$base"
export SAMIZDAT_DEMO_TUI_COMMAND
(cd "$out/tui" && run_group vhs 90s /home/chuck/go/bin/vhs "$repo/scripts/persisted-media-tui.tape")
[[ -d "$out/tui/frames.png" && -f "$out/tui/frames.png/frame-text-00001.png" &&
   -f "$out/tui/frames.png/frame-cursor-00001.png" ]]
# Original frames retain the complete captured timeline. Explicit8fps at both
# inputs and output preserves the held43-second tour rather than inheriting
# VHS's25fps color-margin stream. Overlay only the genuinely captured cursor.
run_group webm 20s ffmpeg -hide_banner -loglevel error -y \
 -framerate 8 -start_number 1 -i "$out/tui/frames.png/frame-text-%05d.png" \
 -framerate 8 -start_number 1 -i "$out/tui/frames.png/frame-cursor-%05d.png" \
 -filter_complex '[0:v][1:v]overlay=shortest=1,scale=1280:-1:flags=lanczos[frames]' \
 -map '[frames]' -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -threads 2 \
 -pix_fmt yuv420p -r 8 -an "$out/tui/samizdat-persisted-tour.webm"
run_group palette 20s ffmpeg -hide_banner -loglevel error -y -i "$out/tui/samizdat-persisted-tour.webm" \
 -vf 'fps=5,scale=1280:-1:flags=lanczos,palettegen=max_colors=128' -frames:v 1 "$out/tui/palette.png"
run_group gif 20s ffmpeg -hide_banner -loglevel error -y -i "$out/tui/samizdat-persisted-tour.webm" -i "$out/tui/palette.png" \
 -filter_complex '[0:v]fps=5,scale=1280:-1:flags=lanczos[frames];[frames][1:v]paletteuse=dither=bayer' "$out/tui/samizdat-persisted-tour.gif"
run_group browser 55s node "$repo/scripts/persisted-media-capture.cjs" "$base" "$source_data/evidence.json" "$out/browser" "$PLAYWRIGHT_ROOT"
touch "$out/capture-done"
kill -TERM "$proxy"; wait "$proxy"; proxy=
wait "$viewer"; printf '0\n' > "$out/owner.exit"; viewer=
sql_snapshot "$data" > "$out/copy-after-identity.txt"
cmp "$out/copy-before-identity.txt" "$out/copy-after-identity.txt"
snapshot "$data" > "$out/copy-after.sha256"
snapshot "$source_data" > "$out/original-after.sha256"
cmp "$out/original-before.sha256" "$out/original-after.sha256"
node -e 'const fs=require("node:fs"); const out=process.argv[1]; const r=JSON.parse(fs.readFileSync(out+"/retirement.json")); const p=JSON.parse(fs.readFileSync(out+"/proxy-ledger.json")); if (!r["terminal?"] || !r["closed?"] || !r["graceful?"] || r.exit!==143 || p.rejected || !p.gets) process.exit(1)' "$out"
ffprobe -v error -show_entries format=duration,size -show_entries stream=width,height,nb_frames -of json "$out/tui/samizdat-persisted-tour.gif" > "$out/tui-metadata.json"
node -e 'const r=require(process.argv[1]); if (+r.format.duration<40 || +r.format.duration>60) process.exit(1)' "$out/tui-metadata.json"
printf 'Media evidence: %s\n' "$out"

# JS1 × SmolVM CI lane (pilot)

A bounded, non-model-facing CI harness that reruns the **existing** JS1
producer-side seam evidence inside a pinned, network-disabled SmolVM guest
on a self-hosted Linux x86_64 KVM runner. It changes no runtime behavior:
`bin/js1`, the evaluator, capabilities, model tools, and every ordinary
invocation are byte-for-byte untouched, and `.github/workflows/tests.yml`
is unmodified.

**Status: provisioned-statically, unexecuted.** No guest pack exists yet
(`runtime-lock.edn` records `:guest-pack/status :unbuilt`), so every lane
entry point **fails closed** with the provisioning remedy. Nothing here
fabricates a run.

## What the lane asserts — and what it does not

A green run is the same evidence `docs/JS1_RUNTIME.md` records —
`bin/js1 check` coordinates, `bin/js1 smoke` (28 tests / 268 assertions at
the pin), and the `SAMIZDAT_JS1_BOUNDARY_TEST=1` durable-restart suite —
plus one new fact: **that evidence was produced inside a digest-verified,
network-disabled guest that saw the checkout read-only**. It remains
producer-side seam evidence. It is not a real-model dogfood, not a
cross-platform lane, and not evidence about kernel development, packaging,
or any other Jolt capability. The REVISE decision in
`docs/JS1_FINDINGS.md` is unchanged by anything this lane does.

## Architecture

```
preflight.sh ──► producer-gate.sh ──► consumer-run.sh
 (host gates)     (pin/inventory)      (one SmolVM machine:
                                        create ► start ► status-contract
                                        ► prepare ► check ► smoke
                                        ► boundary ► teardown, always)
```

- **preflight.sh** — Linux + x86_64 + accessible `/dev/kvm`; required
  tools; the pinned launcher present; the CI dir explicit, absolute, and
  never under host `/tmp`; stale `js1ci-*` machines swept (deleted and
  reported).
- **producer-gate.sh** — the lock restates `bin/js1`'s pins and
  `deps.edn`'s jolt-crypto pin *exactly* (drift = refusal); the smolvm
  launcher reports exactly `smolvm 1.7.5` **and** the real `smolvm-bin`
  bytes hash to the locked SHA-256 (a version string is never trusted
  alone); the guest pack exists and hashes to the locked digest; the
  repo-side inventory is complete.
- **consumer-run.sh** — creates one machine from the pack with the
  checkout mounted **read-only** and **no network**; verifies the status
  contract (`running`, `network:false`, `ports:0`, exactly 1 mount);
  copies the source to **VM-local** scratch and proves the guest sees
  exactly this checkout (sorted per-file SHA-256 manifest compared
  host↔guest); runs `check`, `smoke`, and the boundary suite through the
  recorded `-Scp "$(bin/js1 path)"` replay with the fixture runner
  `test/fixtures/js1/boundary_runner.clj`; then **stops and force-deletes
  the machine under a trap** — teardown is not optional.
- **build-guest-pack.sh** — the producer build lane (network + KVM,
  manual). Fetches the pinned base rootfs (pin-on-first-build), stages the
  digest-pinned Chez 10.4.1 payload, bakes the exact jolt/sci checkout
  with `.git` intact, warms caches with the pinned jolt's own resolver,
  packs with `smolvm pack create --from-vm`, and prints the digest a human
  pins into the lock. It never edits the lock itself.

## Trust anchors

| Anchors | What is verified | Where pinned |
|---|---|---|
| jolt / sci / sci version | commit equality, clean tracked tree, `SCI_VERSION` — re-verified *in the guest* by `bin/js1 check` | `bin/js1`, restated in `runtime-lock.edn` |
| jolt-crypto | `:git/sha` read from `deps.edn` at run time | `deps.edn`, restated in the lock |
| smolvm executor | `smolvm --version` == 1.7.5 **and** SHA-256 of the real `smolvm-bin` | `runtime-lock.edn` |
| guest pack | SHA-256 of the pack file before any boot | `runtime-lock.edn` (`nil` while `:unbuilt`) |
| guest inputs | per-jar SHA-256s, Chez payload digest, base tarball digest | `guest-recipe.edn` + `pack-manifest.edn` (build side) |
| source identity | sorted per-file SHA-256 manifest, host vs guest copy | computed per run, compared |

A descriptor fetched from beside an archive is never an independent trust
anchor: the build records what it actually fetched into
`pack-manifest.edn`, and the consumer anchor is the pack's own digest.

## Bounds

| Bound | Value (lock) |
|---|---|
| machine | 2 vCPU / 4096 MiB, name prefix `js1ci-` |
| per-step deadlines | setup 420s, check 180s, smoke 900s, boundary 1500s, teardown 240s |
| total deadline | 2400s (workflow `timeout-minutes: 45` is the outer backstop) |
| logs | per-step capture truncated to 8 MiB head+tail with an elision banner |
| exec timeout | guest `--timeout <n>s`; host `timeout` wrapper +45s; exit 124 + `command timed out after <N>ms` is a host wait, teardown stop+delete is the kill backstop |

## Host /tmp discipline

The harness creates **no** host `/tmp` paths. Every explicit work product
lives under `$JS1_SMOLVM_CI_DIR/<run-id>` (the workflow sets it to
`${{ runner.temp }}/js1-smolvm`); harness children inherit a `TMPDIR`
under the run dir. The unavoidable exceptions are OS/tooling state, not
harness work dirs: smolvm's own machine store under its state dir, git's
internal plumbing during `rev-parse`/`status`, and `/dev/kvm` itself. The
guest's `/tmp` (used by the boundary suite's phase dirs) is VM-local
scratch, destroyed with the machine.

## Failure modes (all fail closed, remedy on stderr)

- **No pack / `:unbuilt`** — build it: `ci/js1-smolvm/build-guest-pack.sh`,
  then pin the printed SHA-256 and set `:guest-pack/status :built`. The
  workflow stays red until a runner does this; that is the intended
  signal, not a broken pipeline.
- **KVM missing/inaccessible** — `usermod -aG kvm <runner-user>` (or the
  udev rule), re-dispatch.
- **Launcher version or digest mismatch** — re-provision smolvm 1.7.5;
  investigate any digest change as a supply-chain event.
- **Pin drift between lock / `bin/js1` / `deps.edn`** — fix the lock; the
  wrapper and `deps.edn` are the authorities, never edited from CI.
- **Guest manifest mismatch** — the guest does not see this checkout;
  evidence refused.
- **Status contract violation** (network on, ports, extra mounts) — the
  guest is refused before any test runs.
- **Stale `js1ci-*` machines** — swept at preflight; teardown re-verified
  after every run.

## Dispatch

Actions → `js1-smolvm` → Run workflow. Manual only; cannot gate merges.

## Static validation

`test/samizdat/js1_harness_test.clj` (registered in the ordinary suite)
validates the lock/recipe/fixtures against `bin/js1`, `deps.edn`, and the
suite sources, and pins the harness disciplines above — offline, with no
SCI, smolvm, KVM, or guest. Fixture **definitions** live in
`test/fixtures/js1/`; mutable results are CI artifacts only.

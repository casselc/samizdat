# JS1 M2 Evidence

## Coordinates

- Current-upstream M2 base: `yogthos/samizdat@e0517148f4698f325d97619dfef393df87cfe60e`
- Frozen M1 implementation: `casselc/samizdat@bd6075f6e225e43e619ab991d2942f43217de8d4`
- M1 forward-port: `844855aa94442d7ac9696c9828ce694aae0ad1f7`
- M2 implementation: `adbd565928a29440661ba0c0c5d660e358fe7566`
- Bounded Jolt: `4af2362176160f2ed0e366689d7232b1a38adfec`
- SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`
- Evaluator migration: `v20` (upstream `v19` remains rationale/standing).

## M1 entry gate

Using a clean detached Jolt worktree and `/usr/local/bin/scheme`:

```text
JOLT_CHEZ=/usr/local/bin/scheme JOLT_HOME=.../jolt-js1-m1-clean bin/js1-m1 test
13 tests, 160 assertions, 0 failures, 0 errors.
```

This re-established the frozen read profile, receipt/replay, confinement,
timeout, current-turn smoke, and zero-world-replay contract on current upstream.

## M2 gate

```text
bounded evaluator + verify + agent: 133 tests, 994 assertions, 0 failures, 0 errors
ordinary current-upstream suite:    1446 tests, 5474 assertions, 0 failures, 0 errors
```

The bounded tests cover `project/edit` anchored updates and creates, stale and
invalid zero-write refusal, protected `.samizdat/config.edn`, mutation followed
by SCI failure, receipt-driven zero-write replay, develop-profile attenuation,
and structured controller-owned `done` RED/GREEN behavior.

## Security and lifecycle evidence

- `project/edit` requires effective develop authority, exact `project/stat`
  digest (or `:absent`), root/symlink/regular-file checks, and trusted protected
  path policy before durable intent, atomic actuation, and durable outcome.
- Replay consumes edit receipts and never repeats a write.
- Bounded `done` is a ControlEvent, not a semantic operation or shell grant.
  The controller derives structured argv/cwd, scrubbed environment, timeout,
  redaction, and process reaping; RED continues and only GREEN terminates.

## Nonclaims

M2 does not implement TurnLease/stale-turn authority, scheduler changes,
provider epochs, budget extension, shared evaluators, `project/run`, generic
shell authority, JS2, SmolVM, or an upstream rebase.  The frozen M1 branch and
the dirty original Jolt checkout were not modified.

**M2: PASS — current-upstream M1 gate GREEN; bounded edit and trusted completion
GREEN; ordinary suite GREEN; evidence recorded; STOP FOR REVIEW.**

## M2 VerificationEnvironment (M2-VE)

Implemented in the working tree of `js1-m2-current-e051714` (uncommitted, per
instruction). Replaces bounded done verification's direct host spawn with a
controller-owned, fail-closed bubblewrap sandbox:
`samizdat.security.verification-env`.

- Private COPY workspace (authoritative root never bound; symlink-preserving,
  budget-bounded copy; stage deleted in a `finally` however the run ends).
- `--unshare-user` (required, not tried) `--unshare-ipc --unshare-net
  --unshare-pid --unshare-uts --die-with-parent --new-session --cap-drop ALL`.
- Explicit allowlist filesystem: `/usr`, `/usr/local`, merged-usr symlinks,
  the pinned verifier's own checkout, and the dependency caches
  (`~/.gitlibs`, `~/.m2/repository`, `~/.jolt`) read-only under a private
  `/home`. No `/etc`, no host `$HOME`, no host `/tmp`.
- Explicit scrubbed environment with `HOME`/`PWD`/`TMPDIR`/`JOLT_PWD` pinned
  to sandbox paths (a leaked harness `JOLT_PWD` was caught by the adversarial
  suite pointing the sandboxed verifier at a nonexistent host path).
- Pinned immutable verifier authority in controller code — `jolt -A:test -e`
  — NOT gates.edn (runtime-mutable by the tier this gate judges); the one
  variable argv element is the whitelisted focused expression, shared with
  the ordinary lane through one derivation (`verify/focused-expr`).
- Resource policy: `RLIMIT_FSIZE` (an output flood dies of its own SIGXFSZ),
  `RLIMIT_NPROC`, bounded capture read-back, the existing verify wall clock,
  and the scoped process facility's tree reaping.
- Spawned through the existing scoped process facility (`engine.proc`,
  extended with stdout/stderr file redirect for the bounded spool). The
  controller composes no shell and no model-authored string; the facility's
  own exec implementation is the runtime's business and no claim is made
  beyond that.
- Linux-only, fail closed: substrate probed by a real minimum-sandbox spawn;
  unavailable substrate (or unresolvable verifier) REFUSES bounded done with
  the reason. There is no fallback to a host spawn, by construction.

### M2-VE gate (this machine: Linux, bwrap 0.11.1, prlimit, unprivileged user namespaces)

```text
samizdat.verification-env-test: 13 tests, 123 assertions, 0 failures, 0 errors
samizdat.verify-test + samizdat.proc-test: 18 tests, 56 assertions, 0 failures, 0 errors
samizdat.agent-test: 90 tests, 640 assertions, 0 failures, 0 errors
bounded lane samizdat.evaluator-test (SAMIZDAT_BOUNDED_TEST=1, pinned SCI):
  22 tests, 281 assertions, 0 failures, 0 errors
ordinary current-upstream suite (jolt -M:test): 1455 tests, 5586 assertions, 0 failures, 0 errors
```

The adversarial runs are REAL spawns, not mocks: a hostile test namespace
written the way a bounded model would write it attempts outside-root writes
(stage root, `/etc`, `/usr`, `../` off the workspace), rewrites the protected
`.samizdat/config.edn`, reads `/etc/passwd` and a live host secret marker,
connects to a LIVE host loopback listener, spawns a daemon, and floods the
output. Host-side assertions: nothing landed, the authoritative tree and its
run config are byte-identical, no daemon survives (PID-namespace teardown),
the stage is cleaned up, the flood dies at `RLIMIT_FSIZE` with the capture
bounded and truncation marked, and the child environment is the pinned
sandbox env. Immutable argv authority is pinned twice: pure (a hostile
gates.edn retune changes no argv element) and end-to-end (the real run still
executes the pinned verifier and stays green under a hostile retune, and the
retuned command never executes). A hanging verification exercises the
wall-clock timeout, host-side reaping and stage cleanup. The real project's
own focused suite (`samizdat.verify-test`, dependency caches and all) runs
green inside the sandbox. Where the substrate is absent, the same tests pin
the refusal: unavailable ⇒ `{:unavailable? true :reason …}`, nothing spawned,
bounded done refused — never green, never a host spawn.

Development-loop note: `verify/focused-argv` initially built a no-namespace
argv from an empty whitelist (`focused-expr` renders an empty list happily);
the adversarial suite's "nothing verifiable ⇒ nil" assertion caught it before
merge.

### M2-VE nonclaims

M2-VE does not implement M3, `project/run`, TurnLease/stale-turn authority,
scheduler changes, provider epochs, shared evaluators, JS2, SmolVM, an
upstream rebase, or generic shell authority. Ordinary verification
(`run-verify`/`focused-cmd` through `sh -c`) is unchanged in behavior; the
bounded lane's argv prefix moved from gates.edn (`:argv-prefix`, removed) to
pinned controller code, which is the point. The workspace is a COPY, not an
overlayfs mount (portable and deterministic; `--overlay` needs kernel
support M2-VE does not require). There are no cgroup memory/CPU limits
(rlimits + wall clock + reaping only), no sandbox pooling (one stage per
verification), and no attempt to sandbox the ORDINARY lane's shell tool,
which holds shell authority by design. The frozen M1 branch, the pinned clean
Jolt checkouts, and the dirty original Jolt checkout were not modified.

**M2-VE: PASS — adversarial sandbox suite GREEN (real bwrap spawns); bounded
lane GREEN; ordinary suite GREEN; ordinary verification unchanged; evidence
recorded; STOP FOR REVIEW.**

## M2-VE SPI conformance (RFC-012)

Added in the working tree of `js1-m2-current-e051714` (uncommitted, per
instruction). Tests/fixtures/docs/manual/runner only — no `src/` changes, no
bbagent or Jolt changes, no commit, no push.

What exists now, beside the adapter:

- `test/samizdat/fixtures/spi-v1/` — the shared envelope fixture body,
  **byte-identical copies** of the bbagent ecosystem's committed
  `test/fixtures/spi-v1/` files (eleven envelopes, each with its
  `sha256sum -c`-format golden sidecar). Byte-identity was established at
  copy time (`cmp` per file); `sha256sum -c` passes in this copy too.
- `test/samizdat/execution_env_spi_test.clj` — the conformance suite. It
  carries its OWN implementation of the render grammar and the envelope
  rule set, written from RFC-012's normative rules (not lifted from the
  other repository), and renders every fixture's ported evidence inputs to
  the committed bytes, checks every golden digest, round-trips every
  fixture through EDN to itself, and validates the forged-envelope
  negatives. It then holds the M2 adapter's own envelopes (describe,
  refusal, availability, verify, and the ship-verify journal envelope) to
  every grammar-independent rule, pins the private-copy input coordinate
  (manifest of a tree == manifest of its private copy; bytes not names),
  and pins the invocation counter's claim rules.
- `samizdat.canonical-edn-test` and `samizdat.execution-env-spi-test`
  registered in `test/samizdat/test_runner.clj` in BOTH places (the
  `:require` list and the `namespaces` vector) — the canonical suite
  existed but was silently never running before this.
- `docs/RFCS/RFC-012-execution-environment-spi.md`, its index row, and
  `resources/manual.edn` entries for the SPI surface (manual-test green:
  every entry resolves).

### M2-VE SPI gate (this machine)

```text
focused: samizdat.canonical-edn-test + samizdat.execution-env-spi-test
         + samizdat.verification-env-test: 47 tests, 350 assertions,
         0 failures, 0 errors (the verification-env third of that is the
         REAL adversarial sandbox suite, real bwrap spawns)
full suite (jolt -M:test): 1489 tests, 5827 assertions, 0 failures,
         0 errors — up from 1455 by exactly the 34 newly registered tests
```

### M2-VE SPI nonclaims (read before relying on any of it)

- **This repo's envelope coordinate slots speak the canonical EDN grammar**
  (`:bb4t.coordinate/v1`, kind `:bb4t/execution-environment`) — the seam
  bb4t's own `execution/describe` computes and a bb4t keeper can check.
  They do NOT speak the SPI envelope coordinate grammar
  (`:spi.coordinate/v1`) that the fixture describe envelopes carry. A
  reader recomputing a samizdat describe envelope's coordinate per the SPI
  rule gets a different digest, by design (domain separation — pinned by
  test, documented in RFC-012). Passing the fixture-side describe
  recompute check is NOT a property of samizdat's envelopes and is not
  claimed.
- **Refusal catalogues are per-environment.** Samizdat's categories
  (not-linux, no-bubblewrap, no-prlimit, sandbox-unavailable,
  verifier-unresolvable, nothing-verifiable) are not in the other side's
  closed six-category validator; its validator would refuse samizdat's
  refusal envelopes on the catalogue check while shape and namespace pass.
  The shared surface is the `:spi.refusal/` namespace and the refusal
  shape; the difference is pinned by test, not papered over.
- **The render grammar's set, list and character spellings are
  rule-pinned, not fixture-pinned**: no fixture in the shared body carries
  a set, a list or a character, so byte-identity for those spellings rests
  on RFC-012's rules and this side's unit assertions, not on golden bytes.
- **No replay path exists on this side.** The replay envelope kind, its
  index ≤ count rule, and the counter rules a replay must keep are pinned
  (fixture + forged negatives + counter tests), but nothing constructs a
  replay envelope from a live reconstruction yet.
- **`:project-changed` is kept by the fixtures and the rule set only** — it
  cannot occur on this side (the input coordinate is taken over a
  throwaway staged copy), so no samizdat-produced envelope exercises it.
- The journal-envelope test shows the ship-verify rows' envelope data
  serializes through the journal's ordinary data.json path and reads back
  parseable with values intact. JSON key spelling there is this runtime's
  data.json behaviour (namespaced keywords serialize by name) — the
  journal's business, not the envelope's.

Development-loop notes, honestly: the jolt reader caught a duplicate map
key in a first draft of the ported timeout-result literal at load time
(`:stdout/truncated?` twice) before any test ran; and the first journal
assertions assumed JVM data.json's `ns/name` key spelling, which this
runtime's port does not keep — the assertions now pin the values, which is
what the record owes a reader anyway.

**M2-VE SPI: PASS — conformance suite GREEN (byte-identical shared goldens,
independent render implementation); canonical suite registered and GREEN;
full suite GREEN (1489); no src/bbagent/Jolt changes; evidence recorded;
STOP FOR REVIEW.**

## M2 create-race closure (Linux only)

- Jolt base Git coordinate: `4af2362176160f2ed0e366689d7232b1a38adfec`
- Jolt no-replace extension commit: `f8899905`
  (`js1-m2-verify-closure`; the source-content coordinate recorded during
  implementation was superseded by this committed coordinate.)

`jolt.publish/publish!` is a private-FFI, status-only Linux primitive. The
target-header probe compiles the real glibc declaration of `renameat2` and
checks the actual x86-64 target widths/constants and kernel-visible publish /
`EEXIST` postconditions. Its Jolt race test releases twelve complete temporary
files together and also drives a paused-loser interleaving. Samizdat exposes
only `samizdat.security.no-replace/publish-create!`; `project/edit` uses it
only for `:absent` creation, after its existing confinement checks and after the
same-directory temp is closed. The old check-then-rename create path is gone.
Its deterministic two-binding test pauses both writers immediately before the
native operation, releases them together, and observes one complete winner and
one `:existing` loser with no edit-temp litter.

### Gates and blockers

```text
JOLT_CHEZ=/usr/local/bin/scheme bin/jolt run test/chez/atomic-publish-test.clj
  ATOMIC-PUBLISH-TEST OK
sh test/chez/atomic-publish-abi-probe.sh
  ATOMIC-PUBLISH-ABI-PROBE OK
SAMIZDAT_BOUNDED_TEST=1 .../jolt/bin/jolt ... samizdat.evaluator-test
  23 tests, 286 assertions passed, 0 failures, 0 errors
```

The initial unconfigured Jolt invocation was blocked because `bin/jolt` could
not auto-discover threaded Chez; setting the target interpreter explicitly was
required. `make testbin CHEZ=/usr/local/bin/scheme` remains blocked by this
machine's missing linker dependency (`/usr/bin/ld: cannot find -luuid`), before
the built-binary/FASL smoke can run. No package was installed and no fallback
was claimed.

### Nonclaims

This closure is Linux x86-64 evidence only. It makes no claim for macOS, BSD,
Windows, another CPU ABI, another libc, a filesystem that rejects the Linux
primitive/fallback, durability without the caller's fsync policy, crash
recovery, or an atomic replacement race. It closes only M2's final-name create
race; parent-directory/path-component races remain outside this change. An
unsupported or error status refuses create—Samizdat never falls back to
check-then-rename.

## M2 execution-provider closure

Samizdat now has two controller-owned, verify-only providers behind RFC-012:
the Linux bwrap provider and a direct SmolVM provider. The latter ports the
bbagent worker/image/snapshot contract but does not invoke bbagent or expose a
model-visible run operation. Provider selection is controller process
configuration, refuses unknown/unavailable choices, and has no host-execution
fallback.

The bwrap provider now constructs its complete child environment (no inherited
credential variables), binds its private stage read-only, uses sized tmpfs
mounts including `/dev`, binds only the needed device nodes, and applies
RLIMIT_FSIZE, RLIMIT_NPROC, RLIMIT_AS, and RLIMIT_NOFILE. The SmolVM provider
requires both `SAMIZDAT_SMOLVM_IMAGE` and its SHA-256 pin; an absent or
mismatched pin refuses before machine launch.

```text
samizdat.verification-env-test + samizdat.smolvm-verification-env-test
  40 tests, 221 assertions, 0 failures, 0 errors
configured SmolVM image, sha256-pinned
  samizdat.smolvm-verification-env-test: 27 tests, 174 assertions,
  0 failures, 0 errors
```

This is Linux/KVM/SmolVM-1.7.5 evidence only. It makes no portability,
pooling, generic execution, `project/run`, M3 scheduler, or aggregate bwrap
CPU/memory-cgroup claim: bwrap has per-process RLIMIT_AS plus NPROC and a wall
clock, not an aggregate host-reservation mechanism. The earlier M2 records
above are functional-history records; this section supersedes their no-SmolVM
statement only.

## M2 exact lane closure (`bin/js1-m2`)

`bin/js1-m2` is the M2 lane `bin/js1-m1` promised: one entry point that
refuses inexact evidence and runs every M2 gate against pinned bytes.

- Samizdat under test: `d85e24b0ec960e74783a0ae9aabdccc706619e9d`
  (`fix: pin controller Chez into the sandboxed verifier environment`),
  tracked-clean at run time; the lane script itself was untracked during the
  run (the check ignores untracked files, the js1-m1 contract) and is
  committed by the closure commit that carries this record.
- Jolt: `f8899905d98a0abdcc6b4ae61dfd5c8bdb9c7277` — the
  `js1-m2-verify-closure` worktree, tracked-clean; vendor/sci
  `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`, tracked-clean.
- Interpreter/toolchain: `JOLT_CHEZ=/usr/local/bin/scheme` (csv10.4.1, ta6le),
  pinned by the lane for every Jolt invocation — host and sandboxed alike.
- Substrate: Linux, bwrap 0.11.1, prlimit, unprivileged user namespaces,
  smolvm 1.7.5 with `/dev/kvm`.

### The gate (all commands as the lane runs them)

```text
bin/js1-m2 check
  Samizdat d85e24b0ec960e74783a0ae9aabdccc706619e9d
  Jolt f8899905d98a0abdcc6b4ae61dfd5c8bdb9c7277
  SCI 32d62a5136ad3dc148588752f5bcc4cc30b14752 / 0.13.53

bin/js1-m2 test
  Jolt ABI probe (sh test/chez/atomic-publish-abi-probe.sh)
    ATOMIC-PUBLISH-ABI-PROBE OK
  Jolt publish race (bin/jolt run test/chez/atomic-publish-test.clj)
    ATOMIC-PUBLISH-TEST OK
  bounded M1/M2 (SAMIZDAT_BOUNDED_TEST=1, vendored SCI pinned via -Sdeps):
    samizdat.evaluator-test 23 tests, 286 assertions, 0 failures, 0 errors
  VE/SPI/process (verification-env + execution-env-spi + proc):
    43 tests, 324 assertions, 0 failures, 0 errors
  ordinary suite (jolt -M:test):
    1522 tests, 6007 assertions, 0 failures, 0 errors
  explicit bwrap adversarial — substrate demanded first:
    :substrate :real-spawns
    samizdat.verification-env-test 13 tests, 94 assertions,
    0 failures, 0 errors (REAL bwrap spawns)

SAMIZDAT_SMOLVM_IMAGE=/tmp/bbagent-worker-image.tar \
SAMIZDAT_SMOLVM_IMAGE_SHA256=sha256:4d52ba6f932d833cc39f5fe20a8d1f5d618226caa8ed80c80431299156acda19 \
bin/js1-m2 test-smolvm   (the optional gate; configured-only)
  :smolvm-substrate :real-machines
  samizdat.smolvm-verification-env-test 27 tests, 174 assertions,
  0 failures, 0 errors
```

### What the lane adds beyond running suites by hand

- Every Jolt invocation runs through one pinned launcher (the worktree's
  `bin/jolt`, prepended to PATH so the VE controller resolves THAT launcher
  as the sandboxed verifier) and one pinned Chez.
- The adversarial step DEMANDS the substrate: `bwrap`/`prlimit` on PATH and
  an in-tree probe that `verification-env/available?` and `resolve-verifier`
  both hold. Without them the VE suite greenly pins refusal — valid coverage,
  but not adversarial evidence — and the lane refuses instead of recording
  it. The step then re-runs the adversarial suite standalone so its summary
  line is its own, not a sum.
- `test-smolvm` is configured-only: it refuses to run without
  `SAMIZDAT_SMOLVM_IMAGE` + `SAMIZDAT_SMOLVM_IMAGE_SHA256`, demands the real
  machine substrate the same way, and prints one clear result line. A
  refusal-path green can never masquerade as a configured result.

### Development-loop note: the lane caught a missing toolchain pin

The first `bin/js1-m2 test` run failed itself at the VE step: every
sandboxed spawn died with `No threaded Chez Scheme 10.x found`. The
constructed child env pinned every sandbox path but not the verifier's
interpreter; the clean f8899905 worktree (unlike the original dirty checkout)
carries no built Chez under `.cache/local`, and this host's working threaded
Chez is named `scheme` — a name `bin/jolt` discovery never tries (`chez`
exists on PATH but aborts on an incompatible boot). Bounded done was
fail-closed refusing, correctly, everywhere. The fix is commit `d85e24b`:
`child-env` carries the controller's own `JOLT_CHEZ` resolution (toolchain
pinning, `JOLT_PWD`'s standing; `scrubbed-process-env` keeps credentials out
of the name), `coordinate` names the constructed child env so two controllers
pinning different verifier toolchains name different environments, and the
env-contract test pins both halves. The whole lane then ran green on the
clean tree.

### Nonclaims

This closure is this machine's evidence: Linux x86-64, bwrap 0.11.1, prlimit,
unprivileged user namespaces, Chez csv10.4.1/ta6le, smolvm 1.7.5 + KVM, and
the SmolVM gate's one configured image by digest. The lane makes no macOS,
BSD, Windows, non-x86-64, or aggregate-resource claim; it does not touch
Jolt, bbagent, or the frozen M1 branch; it does not start M3 (no TurnLease,
stale-turn authority, scheduler changes, provider epochs, shared evaluators,
`project/run`, JS2, or an upstream rebase); and the counts above are the
current tree's — the earlier sections of this document are functional
history, not superseded coordinates. The `JOLT_CHEZ` child-env pin is
fail-closed, not a bind: when the controller's Chez is not visible under the
sandbox allowlist, the pinned verifier dies and bounded done refuses — no
host path is ever bound for it.

**M2 exact lane: PASS — pinned Jolt f8899905 ABI/race GREEN; bounded M1/M2
GREEN; VE/SPI/process GREEN; ordinary suite GREEN (1522); explicit bwrap
adversarial GREEN on a demanded real substrate; optional configured SmolVM
GREEN (27/174); evidence recorded; STOP FOR REVIEW.**

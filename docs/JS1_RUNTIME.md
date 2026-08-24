# JS1 Runtime — pinned, reproducible invocation

This document names the exact JS1 runtime stack and the one supported way
to invoke it. `docs/JS1_FINDINGS.md` remains the JS1 **decision and
evidence** record (REVISE); this file is only the runtime how-to. A green
smoke here changes nothing there.

## What JS1 needs that ordinary samizdat does not

`samizdat.agent.sandbox` requires `jolt.sandbox`, which requires SCI
(`sci.core`). The vendored SCI source and its Maven dependencies are on
**no** samizdat source root — `deps.edn` deliberately does not declare
them, so `jolt serve`, `jolt -M:test`, `jolt -A:dev`, the GUI, and every
other ordinary invocation are byte-for-byte unchanged and never load SCI.
Under `jolt -M:test` the sandbox namespace simply fails to resolve and the
JS1 tests skip, exactly as before.

The sandbox's digest substrate (`samizdat.agent.files`) computes content
coordinates (stat digests, anchored edits) through jolt.crypto's
MessageDigest shim, so the JS1 classpath additionally needs the source of
`jolt-lang/jolt-crypto` — an ordinary samizdat dependency, already pinned
in `deps.edn`, which `bin/js1` reuses rather than restates.

The JS1 runtime therefore comes from a **Jolt source checkout at an exact
commit**, plus that checkout's `vendor/sci` submodule at the commit the
pin records.

## Pins

| Component | Coordinate |
|---|---|
| Samizdat | this repo — current source is branch `js1-bounded-samizdat` @ `321661649e174bb748adeb6970dad6c166003343` plus the uncommitted JS1 working tree (`bin/js1 check` reports the actual checkout state at run time) |
| Jolt runtime | `https://github.com/casselc/jolt` branch `js1-runtime-current-upstream` @ **`279bca18bbf50f37b8574a4e6998dee40313cd26`** ("test: wire current SCI evaluator gates"; the branch is rebased onto current upstream `edda7aec`, so the pre-rebase SHAs are superseded) |
| SCI | `borkdude/sci` **0.13.53**, vendored as the Jolt checkout's `vendor/sci` submodule @ **`32d62a5136ad3dc148588752f5bcc4cc30b14752`** |
| SCI deps | from the submodule's own `deps.edn` at that commit: `borkdude/edamame` 1.5.39, `org.babashka/sci.impl.types` 0.0.3, `borkdude/graal.locking` 0.0.2, and `org.clojure/tools.reader` 1.5.2 transitively via edamame's POM |
| jolt-crypto | `jolt-lang/jolt-crypto` @ **`1ab72aa5f73be7ec41f01086953ffb43ecd3d84e`** — the digest substrate's MessageDigest shim; pinned once in samizdat's `deps.edn` and read from there by `bin/js1` |

Why this Jolt commit: it is the tip of the branch that re-derives
`jolt.sandbox` — the isolated, capability-bounded SCI evaluator — onto
**current upstream**, now rebased onto `edda7aec`. The rebase landed the
SCI follow-ups upstream (private `map`/`newline` compatibility vars,
`Thread.getId`, `clojure.lang.Numbers` arithmetic statics, restored
nested-interrupt polling, and a persistent-SCI evaluation test — PRs
#721–#725), so the branch itself carries only: the evaluator
re-derivation (`13e43418`, "feat: rederive JS1 evaluator on current
upstream"), two scoped-process commits — `a5fb8a3b`, "feat: add scoped
Linux process termination" (the JVM `ProcessBuilder`/`Process` surface
over posix_spawn with waitpid/kill-driven exit/liveness/signalling) and
`1f859e70`, "feat: bound scoped process output" (separately bounded
stdout/stderr capture for the scoped run: `:out-bytes`/`:err-bytes`
independent byte caps, a poll-bounded drain in the same loop that polls
waitpid, and a fail-closed spawn gate so a capture pipe can never leak a
wedged run) — and the tip `279bca18`, "test: wire current SCI evaluator
gates", which wires the evaluator contract into opt-in make lanes
(`js0sandbox`/`js0authority`/`scievaluator`) and adds discriminating
`Numbers` checked-promote/unchecked-wrap/equiv rows to the sandbox suite.

`jolt.sandbox` is **byte-identical** to the previous pin across this
rebase — no sandbox or language surface changed. The scoped-process
commits are pinned because JS1 verification consumes this bounded,
scoped process primitive as the trusted controller process-scope
substrate. The re-derivation preserves the trusted, inert, versioned
language surface the JS1 safe doc/complete path and the
`samizdat.agent.sandbox` RuntimeCoordinate are built on:

- **Language coordinate** (unchanged across this lane's pins):
  `jolt.sandbox/language-coordinate` emits the `js0-lang/v1:` scheme over
  `jolt.sandbox/language-surface` — `language-surface-version` 1, lang
  `js0-pure-sci`, the same 156-symbol reviewed vocabulary — so the pinned
  coordinate is byte-identical:
  `js0-lang/v1:[:map [[:jolt.sandbox.surface/count 156] … [:jolt.sandbox.surface/version 1]]]`.
- **Capability/authority coordinate**: `jolt.sandbox/canonical-coordinate`
  keeps the `js0:` scheme over `effective-authority`, with the closed
  profile maxima `:agent/minimal`, `:agent/project-read`
  (`:project/read :project/list :project/search :project/stat`), and
  `:agent/project-develop` (those four plus `:project/edit`); context
  creation still enforces requested ⊆ authorized ⊆ profile maximum with a
  dispatch-time recheck, and `revoke!` remains the revocation
  linearization point.
- **Receipt protocol**: unchanged inert receipt domain (`nil`, booleans,
  strings, exact integers, keywords, symbols, vectors, maps; `:op/id`,
  `:op/args`, `:op/result`/`:op/error`) and fail-closed replay
  (exhaustion, operation-mismatch, args-mismatch, unconsumed checks).

Plain upstream commits (before this re-derivation) lack `jolt.sandbox`
entirely.

The SCI version is cross-checked against
`vendor/sci/resources/SCI_VERSION` because the RuntimeCoordinate names it
(`samizdat.agent.sandbox/sci-implementation` = `"sci-0.13.53"`): a
vendored tree that moved without that constant moving is exactly the
drift the check exists to catch. SCI's own dependency versions are stated
nowhere in samizdat — they are resolved from the submodule's `deps.edn`
at the pinned commit, so the pin is the single source of truth.

## The supported invocation

`bin/js1` is the only supported entry point, and the single
repository-owned place where any SCI root is constructed:

    bin/js1 check    # locate + pin-check the runtime stack, print coordinates
    bin/js1 path     # print the resolver-composed JS1 source roots (':'-joined)
    bin/js1 smoke    # run the JS1 seam evidence (test/samizdat/sandbox_test.clj)

Locating Jolt: `$JOLT_HOME` if set, else the sibling checkout `../jolt`.
The wrapper **fails closed** unless the checkout is exactly the pinned
commit with a clean tracked tree and the `vendor/sci` submodule checked
out at the pinned commit — see *Failure modes*.

### Another checkout, from scratch

Prerequisites: `git`, `sh`, and a threaded Chez Scheme 10.x (Jolt's own
requirement; set `JOLT_CHEZ` to select one, as with any jolt run).
Network is needed once per machine so jolt can fetch the four Maven jars
into the shared `~/.m2` repository; everything is offline afterwards.

    git clone <samizdat-remote> samizdat && cd samizdat
    git clone --branch js1-runtime-current-upstream https://github.com/casselc/jolt ../jolt
    git -C ../jolt checkout 279bca18bbf50f37b8574a4e6998dee40313cd26
    git -C ../jolt submodule update --init vendor/sci
    bin/js1 check
    bin/js1 smoke

No source or Maven path is ever composed by hand: `bin/js1` hands jolt a
generated `-Sdeps` alias whose only dependencies are the pinned checkout's
`vendor/sci` (`:local/root`) and the jolt-crypto coordinate from
`deps.edn`, and **jolt's own resolver** composes the roots and
fetches/extracts the jars.

### How the roots are constructed (the one place)

`bin/js1` generates this alias and selects it with `-A` (shown with
`$JOLT` already resolved; the crypto SHA is read out of `deps.edn` at run
time):

    {:aliases {:js1-rt {:replace-paths ["src" "test"]
                        :replace-deps {borkdude/sci {:local/root "$JOLT/vendor/sci"}
                                       jolt-lang/jolt-crypto {:git/url "https://github.com/jolt-lang/jolt-crypto"
                                                              :git/sha "<sha from deps.edn>"}}}}}

- `:replace-paths` / `:replace-deps` keep every other samizdat `:deps`
  coordinate (HTTP, store, nREPL, GUI natives) **out** of the evidence
  run — the same deliberate minimality as the recorded direct `-Scp`
  invocation in `test/samizdat/sandbox_test.clj`'s docstring (whose root
  list is src, test, this same jolt-crypto checkout, `vendor/sci/src`,
  and the four jar extractions), but with the roots composed and verified
  by jolt instead of by hand.
- `-Srepro` keeps a user-level `~/.clojure/deps.edn` out of the run.
- jolt resolves SCI's `deps.edn` at the pinned submodule commit: roots
  `vendor/sci/src` + `vendor/sci/resources`; jars fetched into
  `~/.m2/repository` and extracted beside each artifact as
  `<artifact>-<version>.jar.jolt/`. `org.babashka/sci.impl.types` ships a
  `.class` and no Clojure source, so jolt correctly contributes no root
  for it. jolt-crypto resolves through the shared gitlibs cache like any
  samizdat run.
- In this resolve mode jolt also loads jolt-crypto's `:jolt/native`
  declarations (libcrypto, libssl) at startup, as production does. The
  `-Scp` replay below loads no natives; `samizdat.agent.files` then
  bootstraps libcrypto itself on first digest. Both shapes are green in
  the evidence below.
- `bin/js1 path` prints the composed roots. The answer is a complete,
  recordable classpath — replay it offline with no dependency expansion:

      cd samizdat
      SAMIZDAT_SANDBOX_TEST_RUN=1 "$JOLT/bin/jolt" -Scp "$(bin/js1 path)" \
        run "$PWD/test/samizdat/sandbox_test.clj"

## Environment variables

| Variable | Effect |
|---|---|
| `JOLT_HOME` | Jolt checkout to use; default is the sibling `../jolt` |
| `JOLT_CHEZ` | passed through to jolt's launcher to select Chez Scheme 10.x |
| `SAMIZDAT_SANDBOX_TEST_RUN=1` | set by `bin/js1 smoke` itself; makes `sandbox_test.clj` self-run with a loud (non-skipping) require |

## Failure modes (all fail closed, exit 1, remedy on stderr)

- **No checkout**: `JOLT_HOME` unset and no `../jolt` — prints the clone /
  checkout / submodule commands.
- **`JOLT_HOME` not a directory**, or a directory without an executable
  `bin/jolt` or without `.git` — same instructions.
- **Wrong commit**: prints expected vs actual SHA and the provisioning
  commands. A receipt's runtime coordinate is not meaningful under any
  other tree.
- **Dirty tracked tree** (jolt or `vendor/sci`): the pin no longer
  describes the bytes jolt loads (dev source mode reads the working
  tree), so the run is refused. Untracked build output is fine.
- **`vendor/sci` not checked out / wrong submodule commit**: run the
  printed `git submodule update --init vendor/sci`.
- **`SCI_VERSION` mismatch**: the vendored tree and
  `samizdat.agent.sandbox/sci-implementation` have drifted; bump both
  deliberately, together.
- **No `jolt-lang/jolt-crypto` pin in `deps.edn`**: the digest substrate
  has no coordinate to resolve; restore the dependency (it is an ordinary
  samizdat dep) — do not paper over it by hand-editing the wrapper.

## Evidence (this workspace, 2026-08-24)

    $ bin/js1 check
    js1 runtime stack: OK
      samizdat: /home/chuck/opencode/src/samizdat
                git 321661649e174bb748adeb6970dad6c166003343 (6 tracked file(s) modified)
      jolt:     /home/chuck/opencode/src/jolt
                279bca18bbf50f37b8574a4e6998dee40313cd26 (https://github.com/casselc/jolt branch js1-runtime-current-upstream)
      sci:      /home/chuck/opencode/src/jolt/vendor/sci
                32d62a5136ad3dc148588752f5bcc4cc30b14752 (borkdude/sci 0.13.53)
      sci deps: borkdude/edamame 1.5.39, org.babashka/sci.impl.types 0.0.3,
                borkdude/graal.locking 0.0.2 (+ org.clojure/tools.reader
                1.5.2 transitively) — composed by jolt from the submodule's
                own deps.edn at the pinned commit
      crypto:   jolt-lang/jolt-crypto @ 1ab72aa5f73be7ec41f01086953ffb43ecd3d84e (digest substrate;
                pinned by samizdat's deps.edn)

    $ bin/js1 path
    <samizdat>/src:<samizdat>/test:<jolt>/vendor/sci/resources:<jolt>/vendor/sci/src:~/.gitlibs/libs/jolt-lang/jolt-crypto/1ab72aa5f73be7ec41f01086953ffb43ecd3d84e/src:~/.m2/repository/borkdude/edamame/1.5.39/edamame-1.5.39.jar.jolt:~/.m2/repository/borkdude/graal.locking/0.0.2/graal.locking-0.0.2.jar.jolt:~/.m2/repository/org/clojure/tools.reader/1.5.2/tools.reader-1.5.2.jar.jolt

    $ bin/js1 smoke        # identical from an unrelated cwd
    Ran 28 tests. 268 assertions passed, 0 failures, 0 errors.
    {:type :summary, :test 28, :pass 268, :fail 0, :error 0}
    SANDBOX-TEST OK

    # recorded-classpath replay (offline; no natives loaded):
    $ SAMIZDAT_SANDBOX_TEST_RUN=1 <jolt>/bin/jolt -Scp "$(bin/js1 path)" \
        run "$PWD/test/samizdat/sandbox_test.clj"
    Ran 28 tests. 268 assertions passed, 0 failures, 0 errors.
    SANDBOX-TEST OK

The smoke's 28/268 is byte-identical across every pin this lane has
carried (pre- and post-rebase): `jolt.sandbox` is unchanged by the
scoped-process commits and the rebase onto `edda7aec`, so the language
surface and coordinate are preserved and no receipt, snapshot, or
coordinate expectation moved with the pin. The smoke remains the same seam
evidence the recorded direct invocation in `test/samizdat/sandbox_test.clj`'s
docstring produces. The two root sets are equivalent — the wrapper's adds
`vendor/sci/resources` (which the submodule's own `deps.edn` declares) and
omits the source-less `sci.impl.types` extraction; only the composition
(jolt's resolver, not a hand-written path list) is new.

Failure modes exercised against synthetic checkouts: missing
`JOLT_HOME`, absent sibling, wrong commit, submodule not checked out, and
a dirty tracked tree each refused with the documented remedy. The
fail-closed gates are also covered deterministically by
`samizdat.js1-wrapper-test` in the ordinary suite (no SCI needed).

## Non-claims

- The smoke is **producer-side seam evidence**: the JS1
  spec/instance/binding lifecycle, receipts, and durable-replay machinery
  against the pinned runtime. It is not a real-model dogfood, not a
  process terminate/restart/resume demonstration, and not a
  cross-platform lane — the unmet PASS criteria in `docs/JS1_FINDINGS.md`
  stand, and the REVISE decision is unchanged by anything here.
- A green smoke asserts the sandbox seam on **this exact pin only**. It
  implies nothing about kernel development, packaging, or any other Jolt
  capability lane, and nothing about any other commit.
- `bin/js1` changes no ordinary invocation: it is inert unless executed,
  and `deps.edn` gains only a comment.

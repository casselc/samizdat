# RFC-012 — The execution environment SPI

**Status:** implemented for the M2 VerificationEnvironment, and for a second
speaker — the SmolVM verify environment
(`samizdat.security.smolvm-verification-env`, ported from the bbagent
ecosystem's measured execution substrate), which produces every rule this
RFC states plus the `:project-changed` run status its live-tree bracket
makes possible; the replay kind is contract-only (no replay path exists on
this side yet).

## Purpose

Specifies how this repository states, as **data**, what a sandboxed
execution was — what environment ran it, whether one could be had at all,
what a run produced, and what a replay restored — so that a *second,
independent repository* can check that statement without sharing code with
this one. The M2 VerificationEnvironment (RFC-003's bounded lane,
`samizdat.security.verification-env`) is the first speaker; the bbagent
ecosystem keeps the other side, and neither side reads the other's code.

The seam exists because an execution's evidence crosses repository
boundaries. A run envelope journaled by this harness may be read by a
project that never loaded a line of samizdat; a description computed here
must name the same environment a bb4t keeper would name. A Clojure protocol
cannot cross that boundary (it is an agreement between namespaces that
share a classloader), so the agreement is **inert EDN plus two pinned
grammars plus golden fixtures**, and every side keeps its own
implementation of the rules.

## Scope

**This layer decides** the vocabulary of execution evidence: envelope
kinds and their exact key sets, the canonical render grammar for envelope
bytes, the coordinate grammar for envelope coordinate slots, the refusal
category namespace, the invocation-index rules, and what a fixture body
must pin. It is mechanism in `src/` — the keepers
(`samizdat.security.canonical-edn`, the envelope constructors in
`samizdat.security.verification-env`) decide nothing about *when* a
coordinate is taken or *what* one names.

**It must not know** what any envelope's payload means beyond its shape:
the SPI does not know what bwrap is, what a worker image digest pins, or
why a controller refuses. Refusal categories are each environment's own
refusal points; the SPI owns only the namespace and the shape.

**It hands to whom:** envelope producers (the M2 adapter) and consumers
(the ship gate's `:ship-verify` journal rows; a future replay; any second
repository). The fixtures and their goldens are the contract — same
inputs, same bytes, same digests, or the implementations are not
conformant.

## The model

### Two coordinate grammars, deliberately separated

| grammar | algorithm | who keeps it | where this repo uses it |
|---|---|---|---|
| **canonical EDN** | sha256 over the tagged-tree print of `[:bb4t.coordinate/v1 kind tree]`; maps key-sorted, sets encoding-sorted, integers through bigint, printer pinned | this repo's `samizdat.security.canonical-edn`, independently mirrored by bb4t's `bb4t.canonical` | every coordinate slot in this repo's envelopes: the environment description (kind `:bb4t/execution-environment` — the same kind and grammar bb4t's own `execution/describe` uses) and the verify input (kind `:samizdat.ve/verify-input`) |
| **SPI envelope** | sha256 over the render grammar's print of `[:spi.coordinate/v1 kind payload]` | the fixture body (`test/samizdat/fixtures/spi-v1/`) pins the rendered bytes; this repo's test-side implementation renders them | the describe envelopes in the shared fixture set, whose coordinates are recomputed from the description beside them by that grammar |

The two grammars are domain-separated **by design**: over the same
description they must produce different digests, because they name
different things (a bb4t semantic identity vs an SPI envelope-internal
coordinate). A collision would mean one of them is not naming what it
claims; `execution-env-spi-test/the-two-coordinate-grammars-are-domain-separated-by-design`
pins the inequality.

Agreement on the *canonical EDN* grammar is what the shared golden vector
pins: `:bb4t/test-vector` over `{:a 1 :nested {:x #{3 2 1} :y [:ok]}}`
digests to the same value under this keeper and under bb4t's
(`canonical_edn_test/the-shared-golden-vectors-are-the-other-repositories-digests`).
A digest that moves means the keepers stopped keeping one contract.

### The render grammar (normative)

Canonical EDN text for envelope bytes, deterministic to the byte:

- Scalars: `nil`, `true`/`false`, integers in decimal, strings readably
  (a newline inside a string is bytes on the wire, not a line break),
  keywords and symbols in `:ns/name` form with names EDN can spell.
- Collections: elements and map entries joined by `", "`; a map entry is
  rendered key, one space, rendered value; **maps sort entries ascending
  by rendered key text**; **sets sort by rendered element text**; vectors
  and lists keep their order.
- The domain is inert EDN only. Floats, records, metadata, tagged
  literals, unspellable names, and arbitrary objects are **refused**, not
  printed — a value that cannot round-trip cannot be compared
  byte-for-byte.

### The envelope kinds (normative)

Every envelope carries `:spi/version 1` and `:spi/kind`, has an exact key
set (unknown keys refused), and must be wholly inert.

- **`:spi.environment/describe`** — `{:environment/description …inert,
  non-empty… :environment/coordinate "sha256:<64hex>"}`. A description
  that cannot say what implements it (`:executor/type` missing or not a
  keyword) is refused: a run cannot be attributed to an environment with
  no type. The M2 adapter's description names only the SHAPE (type, mode,
  operations, network, namespaces); the full policy is the private
  `coordinate` function's business.
- **`:spi.environment/availability`** — exactly one of
  `:environment/coordinate` (available) or `:environment/refusal`
  (refused): an available environment carries no refusal, a refused one
  no coordinate, so a reader never decides which to believe. A refusal is
  `{:refusal/category :spi.refusal/<name> :refusal/reason "<authored
  string>"}` — noun phrases, never host specifics, because a refusal
  crosses the same boundary a description does.
- **`:spi.execution/run`** — the run envelope the ship gate journals:
  invocation index (positive), attribution (coordinate + type), input
  (`{:input/coordinate …}` or `{:input/stability :input/project-changed}`),
  status (`:completed | :timeout | :worker-failure | :project-changed`),
  exit **iff** `:completed` (a deadline is not a program that chose a
  number), `:output/process` demotion **iff** `:project-changed` (an
  unanchored run must not pattern-match as ordinary success), streams
  `{:stream/text :stream/bytes :stream/truncated?}` with the TRUE byte
  count, duration, and disposition. Fields that are nil are dropped: an
  absent exit and a present-but-nil exit are the same refusal to invent
  one.
- **`:spi.execution/replay`** — `{:replay/invocation-index …positive…}
  {:replay/invocation-count …non-negative…}`, index ≤ count: a faithful
  replay performs nothing, so the counter it reports is the counter that
  was there before it ran.

### Refusal catalogues are per-environment

Each environment catalogues **its own refusal points, none invented**.
This environment's are (`verification-env`'s `refusal-categories`):
`:not-linux`, `:no-bwrap`, `:no-prlimit`, `:sandbox-unavailable`,
`:no-verifier-executable`, `:no-verifiable-test` →
`:spi.refusal/not-linux`, `:spi.refusal/no-bubblewrap`,
`:spi.refusal/no-prlimit`, `:spi.refusal/sandbox-unavailable`,
`:spi.refusal/verifier-unresolvable`, `:spi.refusal/nothing-verifiable`;
anything uncatalogued refuses as `:spi.refusal/unknown`. The bbagent
executor's catalogue (manager, guest image, project identity) is
different, and the difference is the point: the shared surface is the
`:spi.refusal/` namespace, the shape, and the either/or rule — pinned by
`execution-env-spi-test/refusal-catalogues-are-per-environment-while-the-namespace-is-shared`.

### The private-copy coordinate (RFC-012's input naming)

A run envelope names its INPUT by the **private copy** the verifier
actually ran against, not the authoritative tree and not the model's
account of it: the manifest (every entry relative to the root, sorted by
path; files carry byte size and content digest; links carry their target
read but never followed; the copy's exclusions applied by name at every
level and recorded) is built over the staged copy after staging and
before the spawn, and coordinated under kind `:samizdat.ve/verify-input`.
Two properties follow and are pinned: the manifest of any tree equals the
manifest of its private copy (`execution-env-spi-test/the-private-copy-names-the-same-input-as-the-authoritative-tree`),
and the coordinate follows bytes, not names — excluded writes do not move
it. `:project-changed` cannot occur for the bwrap environment: a throwaway
copy cannot move under the run. The SmolVM environment CAN produce it — its
workload sees the live tree through a read-only mount and an overlay, so it
brackets the run with the input coordinate before and after and reports a
moved project as `:project-changed` with no coordinate and a demoted
process outcome, per the run-envelope rules above.

### The invocation counter

One environment per harness process, so the counter is process-local. It
is claimed **immediately before the spawn** — the index read when a run
returns is that run's index. The substrate probe, refused requests and
failed staging never claim one: none of them attempted an execution. The
durable order of verifications is the journal's; this index distinguishes
two runs inside one process, no more.

## Conformance

`test/samizdat/fixtures/spi-v1/` holds the shared fixture body — one
canonical EDN file per envelope, each beside a golden `sha256sum`-format
`.sha256` sidecar — **byte-identical to the bbagent ecosystem's committed
fixture directory**: `sha256sum -c` passes in either, and a file that
differs between them has drifted out of the contract. The fixture values
are the ported evidence inputs of both sides' suites (stub results,
truncation bounds, refusal data shapes); every canned input is visible in
the fixture bytes it must render to.

`samizdat.execution-env-spi-test` carries this side's **independent**
implementation of the render grammar and the envelope rule set (written
from the rules above, not lifted — independence is what makes
byte-identity an agreement rather than a tautology) and checks, for every
fixture: rendered bytes equal the committed file, digests equal the
goldens, parsed fixtures re-render to themselves, and each envelope
passes the full rule set. It then holds the M2 adapter's own envelopes —
describe, refusal, availability, the verify run envelope, and the
envelopes the ship gate journals — to every grammar-independent rule,
plus this repo's own goldens (`test/samizdat/fixtures/execution_env_edn.edn`)
for the canonical-EDN side.

`samizdat.canonical-edn-test` pins the canonical EDN grammar: the shared
golden vector, order freedom, print-binding independence, integer
normalization, domain separation, and the rejection of everything alive
or ambiguous.

## Invariants

- **Byte-identity is checkable by either side.** Same fixture inputs,
  same bytes, same digests, under either keeper. Enforced: the fixture
  body is byte-identical with digests in `sha256sum -c` format, and
  `execution-env-spi-test` re-renders every fixture through this side's
  independent implementation.
- **An envelope never carries what its kind does not define.** Exact key
  sets, checked at construction on the producing side (`envelope!`)
  and by the rule set on the consuming side.
- **Nothing alive or ambiguous leaves as envelope data.** Both keepers
  reject it; producing-side construction canonicalizes through
  `canonical-tree` before the envelope is returned.
- **Attribution cannot drift from description.** The describe envelope's
  coordinate and every run envelope's attribution coordinate are
  recomputed in the same expression as the description, never cached.
- **An exit survives only when the workload exited; a changed project
  cannot look anchored.** Enforced by both rule sets; the
  `:project-changed` half is pinned by fixtures only, since this side's
  staged copy cannot move.
- **The counter moves only for real spawns.** Enforced by the claim
  ordering in `run`; pinned by
  `execution-env-spi-test/the-invocation-counter-moves-only-for-real-spawns`.
- **A refusal is catalogued and authored, never a raw failure.** The
  uncatalogued bucket refuses as `:spi.refusal/unknown`; reasons are
  stable noun phrases with no host specifics; the worker-failure error
  string is a fixed authored phrase, never the exception message.

## Known gaps

- **No replay path on this side.** The replay envelope kind, its rules,
  and the counter contract a replay depends on are kept and pinned, but
  nothing on this side constructs a replay envelope from a live
  reconstruction yet. When one exists, it must not move the counter.
- **This repo's envelope coordinate slots do not speak the SPI envelope
  coordinate grammar.** They speak the canonical EDN grammar (kind
  `:bb4t/execution-environment`) — checkable by a bb4t keeper, not by a
  re-implementation of the SPI describe recompute rule. A reader that
  recomputes a describe envelope's coordinate per the SPI grammar will
  get a different digest, by design; the two grammars name different
  things. If the ecosystem ever wants one grammar in those slots, that is
  a deliberate contract change, not a fix.
- **The other keeper's refusal catalogue is closed on its side.** Its
  validator refuses categories outside its own six; a samizdat refusal
  envelope read by that validator would fail the catalogue check while
  passing shape and namespace. The catalogue rule is per-environment
  (above); the fixtures carry each side's own.
- **Envelope version 1 only.** It stays 1 until a rule changes rendered
  bytes; then it is 2, and coordinates taken under 1 remain what they
  were.

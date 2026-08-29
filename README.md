<p align="center">
  <img src="img/logo.svg" alt="samizdat logo" width="300">
</p>

A self-hosting agentic harness for Clojure development, written in
[Jolt](https://github.com/jolt-lang/jolt) (Clojure on Chez Scheme). The
defining idea: the harness is a live image, the agent's output space is the
harness's code space, and the agentic loop itself is data — a workflow
manifest the agent can inspect and, behind a validate/soak/rollback gate,
rewrite while it runs.

Three rings:

- **kernel** — the durable journal, event bus, store, secrets, and executor
  lifecycle. Agent-immutable.
- **executor** — the [mycelium](https://github.com/mycelium-clj/mycelium)
  workflow layer (vendored, running on a jolt port of
  [maestro](https://github.com/yogthos/maestro)). The loop is an EDN manifest;
  cells are the plugin unit and declare whether they are pure or effectful.
- **capabilities** — nREPL, clojure-lsp, and shell surface as plugins; every
  plugin is a cell in the workflow graph.

Multiple agents work each non-trivial task: work is grounded in a kanban
`tasks` table, decomposition hands subagents a contract plus tests as the
spec, and collaborators share a feature workspace (artifacts, failures,
messages) while keeping private working context.

Descended from veriframe, a claim-first verification harness for mathematics;
the proof engines (Lean, Z3, SWI-Prolog, Octave) left, the durable-journal
core, resume-by-replay, gate/arbiter loop, and beam scheduler stayed.

## Running

```
jolt serve      # HTTP + nREPL, parks
jolt test       # full suite
jolt smoke      # platform probes (sqlite, https, server)
```

State lives in `samizdat.sqlite3` (moving to [dolt](https://github.com/dolthub/dolt)
via [doltera](https://github.com/jolt-lang/doltera)); a run survives restart
and resumes from its journal.

## Build-time instrumentation seams

Samizdat publishes inert compiler-aspect manifests under
`META-INF/jolt/aspects/`. They name the M2 run and scheduler lifecycles, branch
open/close boundaries, turns, model calls, tool selection and execution,
steering decisions, the outbound HTTP call site, and two Mycelium execution
seams: one accepted-workflow lifecycle and one edge decision per completed
cell. Input-schema rejection happens before execution begins and therefore
does not emit a workflow lifecycle; consumers that need attempt-level
validation telemetry should observe a separate validation seam. The
manifests contain no executable advice and introduce no OpenTelemetry
dependency. An application selects them at build time and supplies a
compatible consumer: OpenTelemetry, an event journal, policy enforcement,
profiling, or another bounded observer.

`mycelium.core/graph-artifact` projects the same runtime-editable workflow data
into deterministic provider-neutral metadata: semantic node/cell identities,
exact labeled edges, and entry/terminal cells. `mycelium.core/pre-compile`
publishes that artifact and its SHA-256 identity under `:graph` and `:graph-id`.
Mycelium's internal predicate wrappers report the matching
`[source label target]` reference only after selection, while Maestro retains
its legacy compiled dispatch-pair shape. A consumer can therefore join an
edge-decision event to the graph without inspecting predicate code or data.

The roles distinguish duration scopes from instantaneous facts. A consumer can
represent `:samizdat/control-loop`, `:samizdat/turn`, `:samizdat/model`, and
`:samizdat/tool` as spans while recording branch open/close, parsed tool
selection, and steering as events. The library fixes the semantic boundary;
the consumer owns its representation and data policy.

The manifest `:library` version is the compatibility id of the instrumented
semantic surface, not necessarily the newest resource-only Git commit. A
compiler rejects a consumer whose declared compatibility id differs, or whose
expected entry or call-site count no longer matches, instead of silently producing a
partially instrumented application.

The established Samizdat seams retain
`samizdat.instrumentation/compatibility-id`,
`35b01fddd20fa9e6d77678eadc2a2bcc6fb9ac2d`. The new graph/execution contract
uses the separately published semantic id
`samizdat.instrumentation/mycelium-compatibility-id`,
`samizdat-mycelium-graph-v1`: no older commit contains those join points, so
pinning their manifest to the old source revision would be false provenance.
Consumers depend on these neutral identity vars instead of depending on one
another or duplicating either literal.
No aspect manifest is selected by a normal `jolt` run or build, so the plain
embedded facade and its demo behavior remain unchanged.

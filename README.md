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
`META-INF/jolt/aspects/`. They name the M2 run, turn, model, and tool function
entries plus the outbound HTTP call site and semantic advice roles, but contain no executable advice
and introduce no OpenTelemetry dependency. An application selects the
manifests at build time and supplies a compatible consumer: OpenTelemetry,
an event journal, policy enforcement, profiling, or another bounded observer.

The manifest `:library` version is the compatibility id of the instrumented
semantic surface, not necessarily the newest resource-only Git commit. A
compiler rejects a consumer whose declared compatibility id differs, or whose
expected entry or call-site count no longer matches, instead of silently producing a
partially instrumented application.

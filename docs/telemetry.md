# Telemetry: the Samizdat OpenTelemetry contract

Bounded observability for the executed decision pilots
(casselc/samizdat-agent-bootstrap issue #3). This document is the contract;
the code is `samizdat.telemetry.*`.

## What it is, in one paragraph

A **versioned attribute manifest**
(`resources/samizdat/telemetry/attribute-manifest.edn`, schema
`samizdat-telemetry/1`) says how every telemetry fact is spelled, typed, who
is entitled to state it, whether it is one of the few **promoted typed
columns**, and how it is mirrored for Langfuse. A dependency-free
**contract** namespace loads and validates it and renders it as canonical
JSON for other consumers. A dependency-free, inert **hook** seam sits at the
five `samizdat.store.lifecycle` boundaries. Under the optional `:telemetry`
alias, `samizdat.telemetry.otel` wraps casselc/otel with semantic helpers,
installs the hook observer and exports through independently bounded
pipelines to a local receiver, Langfuse, or both.

Telemetry is a **view, not a source of truth**. The journal and the pilot
evidence records decide budgets, action legality, completion and what was
known at decision time; every attribute carries an `:authority` naming whose
fact it is, and `samizdat.value.basis` (`measured` / `reconstructed` /
`unavailable`) says how the value was obtained. Worker claim, protocol
verdict, infrastructure status and task completion are four attributes, never
one.

## Modes and behaviour statement (platform profile)

| build | what loads | behaviour |
|---|---|---|
| **source mode** (stock `jolt -M:test`, `jolt serve`, Jolt ≥ 0.8.0) | `samizdat.telemetry.contract`, `samizdat.telemetry.hook` (both `src/`, no new dependency) | `hook/observe!` calls the thunk directly; the lifecycle functions behave exactly as before (`test/samizdat/lifecycle_test.clj`, `test/samizdat/telemetry/hook_test.clj`). No otel namespace exists on the path. |
| **alias-enabled** (`jolt -M:telemetry …`, Jolt 0.8.3) | additionally `telemetry/samizdat/telemetry/otel.clj` and casselc/otel `87d3ac1` | `otel/init!` reads `SAMIZDAT_TELEMETRY` and installs the observer; one span per lifecycle seam, semantic wrappers for executions, generations, tools and evaluators. |
| **woven** (aspect pack `resources/META-INF/jolt/aspects/samizdat-observability-38dc6d7.edn`) | advice `samizdat.telemetry.otel/lifecycle-observer` at the same five entries | Statically validated (`test/samizdat/telemetry/aspect_manifest_test.clj`). Not qualified as a build here — see the bootstrap work product report. |

Optional aspect behaviour never becomes a runtime dependency: the stock
build does not require `otel.*`, and the hook is a no-op until something
installs an observer.

`SAMIZDAT_TELEMETRY`:

| value | destinations |
|---|---|
| unset / `off` | nothing installed; wrappers use the API no-op tracer |
| `local` | OTLP/HTTP JSON to `SAMIZDAT_OTEL_LOCAL_ENDPOINT` (default `http://127.0.0.1:4318`, standalone oscope) |
| `langfuse` | OTLP/HTTP to `LANGFUSE_HOST` + `/api/public/otel/v1/traces` |
| `dual` | both, each on its own bounded queue and worker (`otel.sdk.export/independent-batch-pipelines`) |

Langfuse credentials are never read from the command line or stored: the
Jolt side reads one environment variable **named** by
`SAMIZDAT_LANGFUSE_OTLP_HEADERS_ENV` (default `SAMIZDAT_LANGFUSE_OTLP_HEADERS`)
whose value is in `OTEL_EXPORTER_OTLP_HEADERS` syntax, e.g.
`Authorization=Basic <base64(pk:sk)>,x-langfuse-ingestion-version=4`. The
value is handed to the Langfuse exporter and nowhere else; diagnostics
(`otel/stats`, flush and shutdown results) are scalar counts by design.

A `TRACEPARENT` (and `TRACESTATE`) environment variable parents the process's
spans under the caller (`otel/with-inbound-context`), which is how a
`lifecycle_cli` run lands under the Python execution span.

## Dependencies under the alias

Only the alias adds dependencies; the stock `:deps` are unchanged.

| lib | revision | why |
|---|---|---|
| casselc/otel | `87d3ac1a9b26ec6c0bf0c44d3b5aff4c66ccb5a0` | the SDK; oscope `74263599`'s selection |
| jolt-lang/http-client → casselc/http-client | `9cb5801e8c5929387715aa6713c33b2c21fd9a2a` | otel's http-client is a linear descendant of the stock `v0.0.6` and both provide `jolt.http.*`; the alias moves the root pin to the same descendant so there is one provider. (`v0.0.6` also does not compile under Jolt 0.8.3: `jolt/http/net.clj:181` recurs across `try`.) |
| jolt-lang/jolt-crypto | `5effcc89a3258499a79a2a3d69edad9e7800d1bf` | descendant of the stock `44da69b` that otel and http-client `9cb5801` pin |

`jolt -Stree -A:telemetry` and `jolt -Spath -A:telemetry` show exactly one
checkout of each. `jolt-otel-clickhouse` is deliberately **not** a
dependency: its jolt-chdb pin resolves `casselc/db` over this project's
`jolt-lang/db`. Typed-column fragments (`contract/typed-column-fragments`,
joc v3 shape) are therefore compiled outside this repository from the
rendered JSON.

## The manifest

Each attribute: `:key`, `:type` (`:string | :boolean | :int64 | :double`),
optional closed `:domain`, `:group`, `:authority`
(`:journal | :evidence | :runtime | :import | :telemetry`), `:promote?`,
`:langfuse` (`:trace-metadata | :observation-metadata | :usage | :session |
:type | :model | :none`) and `:missing` (`:unavailable | :not-applicable`).

Groups: identity, evaluator, outcome, feedback, usage, budget, cost,
trigger, lifecycle, provenance, version, kind. Budgets are signed int64
(`samizdat.budget.<dim>.{granted,used,remaining}`, wall in ms); costs are
doubles with separate scopes (`worker`, `operational-action`, `research`) and
the reconciled labels `wall_elapsed_s` / `wall_charged_s` / `wall_credited_s`.

**Promoted set** (14, string/boolean/int64 only, `:max-promoted 16`):
`samizdat.execution.kind`, `.evaluator.id`, `.evaluator.status`,
`.evaluator.purpose`, `.worker.claimed`, `.protocol.correct`, `.infra.error`,
`.task.complete`, `.feedback.delivered`, `.budget.turns.remaining`,
`.budget.wall.remaining_ms`, `gen_ai.usage.output_tokens`,
`.observation.mode`, `.value.basis`. Everything else stays in the untyped
attribute map. Historical feature columns are not promoted.

`contract/normalize` keeps `false`, `0`, negatives and `""`; omits `nil`;
coerces declared keys to their type; and moves anything undeclared or
ill-typed to a string under `samizdat.x.<key>` while reporting it
(`normalize-result` → `:errors`). Nothing is silently dropped and nothing
undeclared can pose as a contract attribute.

The Langfuse mirror (`contract/langfuse-mapping`) is generated from the
manifest, never hand-written: `langfuse.observation.type` from
`samizdat.observation.kind` (`agent | generation | tool | evaluator | span`),
`langfuse.session.id` from `samizdat.family.id` else `samizdat.run.id`,
`langfuse.trace.metadata.<key>` / `langfuse.observation.metadata.<key>` for
the targeted attributes, `gen_ai.usage.*` native, and
`langfuse.observation.level=ERROR` only for infrastructure errors. Failed
verdicts are verdicts; there are no Langfuse scores.

Mapping version `samizdat-langfuse-mapping/2` adds two rules, both learned
from live readback of `/1`:

- **Trace metadata is root-scoped.** Langfuse keeps one `metadata` map per
  trace and applies every span's `langfuse.trace.metadata.*` to it, last
  writer wins. Under `/1` two `evaluator` children carrying different
  `samizdat.cost.action_charged_s` values raced for one trace slot. Under
  `/2` only the root observation kind (`agent`, `:root-observation-kind`)
  mirrors a trace-targeted attribute to `langfuse.trace.metadata.<key>`;
  every other kind mirrors the same attribute to
  `langfuse.observation.metadata.<key>` (`:trace-metadata-on-observation`).
  The canonical `samizdat.*` key is present on every span regardless.
  `set-facts!` reads the kind from the span it is given.
- **Observation content is synthetic-only.** `langfuse.observation.input` and
  `langfuse.observation.output` (`:content-keys`) travel only when
  `samizdat.observation.mode` (`:mode-source`) is `synthetic`
  (`:content-allowed-modes`). Live and historical-import spans never carry
  prompts, tool arguments, file contents or delivered text
  (`docs/DATA-GOVERNANCE.md`); a content key offered in any other mode, or
  with no mode, is dropped before normalisation (so it can never be
  stringified into the `samizdat.x.*` fallback) and the key *names* are
  recorded under `samizdat.x.content.refused`. Synthetic qualification uses
  stub content to exercise the input/output path end to end.

## Rendering for consumers

```
jolt telemetry-manifest OUT-DIR
```

writes `attribute-manifest.json`, `langfuse-mapping.json` and
`typed-columns.json` as canonical JSON (sorted keys, no insignificant
whitespace, byte-stable), suitable for pinning by sha256 beside the samizdat
commit that produced them.

## Tests

- stock: `jolt -M:test` (Jolt 0.8.1) includes
  `samizdat.telemetry.{contract,hook,aspect-manifest}-test`;
- alias: `jolt telemetry-test` = `jolt -M:telemetry:telemetry-test`
  (Jolt 0.8.3) adds `samizdat.telemetry.{otel,pipelines}-test`: typed
  attributes incl. false/0/negatives, the Langfuse mirror, lifecycle spans
  through the hook, observer failure isolation, suppression, TRACEPARENT
  parenting, a throwing destination beside a healthy one, bounded queue
  overflow with per-destination drop counts, bounded exactly-once shutdown,
  and that exporter errors never reach diagnostics.

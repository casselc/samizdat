# Changelog

## Unreleased

- Persist bounded per-server retirement receipts even after demo task failure,
  recording observed exits rather than guessing TERM status. Reuse confirmed
  terminal observations without resignal or output-drain waits; receipt/log
  publication failure never replaces the primary task failure. Refs #45.

- Add opt-in embedded demo run/turn counters, measured operation durations and
  fixed content-off lifecycle log records through the existing SDK owner.
  Native ingestion, fresh-reader readback and useful charts remain to qualify.
  Refs #53.

- Add explicit Lemonade loaded-model preflight before demo acquisition, with
  safe fixed diagnostics and generic-endpoint opt-out. Bound the demo-only
  curl request deadline and received body bytes, confirming direct-child
  retirement before collector acquisition. Refs #51.

- Document the current local Durable steered sample with fresh recovered-viewer
  screenshots and real harness evidence, keeping exhausted task state separate
  from successful arithmetic, telemetry and fresh-process readback checks.

- Recover complete fenced tool-call JSON followed by the observed orphan XML
  closing tags, with strict whole-input and no-extra-JSON checks. Preserve tool
  permissions and mark wrapper repairs explicitly. Refs #52.

- Refresh the observability pack's upstream provenance to 83eb99a, retaining
  the same nine run seams and explicit source-only versus woven-build limits.

- Require a fixed host-owned arithmetic verifier in the embedded model demo,
  in addition to six passing model-authored tests, before telemetry/readback
  qualification. Record the separate six-case semantic check in success evidence.

- Label the embedded demo's historical recovery summary separately from fresh
  harness success evidence, retaining the original run's unavailable observations
  and keeping current integration qualification distinct.

- Guard the real-model demo's direct-child signals against invalid/broadcast
  PIDs and preserve raw evidence when termination cannot be confirmed. Cleanup
  diagnostics do not replace the original run failure; Durable data and captured
  telemetry values are unchanged.

- Add a bounded, local Durable real-model demo with a versioned fixture,
  exactly-once steering, independent six-test verification, nine content-off
  span families, and fresh-process readback. Exhausted orchestration remains
  exhausted even when the independent application and telemetry gates pass.

- Guard background start/resume secondary storage and approval cleanup so the
  original task outcome survives and owned active state is always released.
  Release only the terminating run's approvals, with bounded secondary
  diagnostics and deterministic cleanup regressions. Refs #40.

- Integrate current upstream handoff, framed tool results, and cache/context
  diagnostics while preserving embedded telemetry and owned task cleanup.
  Reconcile databases from either v30 migration lineage without dropping rows
  or inventing historical prefix measurements; refuse incompatible existing
  column types, nullability, or defaults rather than accepting names alone.

- Close synchronous OpenAI agent runs durably on exceptional task exit,
  preserving cancellation and concurrent terminal winners without masking the
  original exception or leaving active ownership behind.

- Await the canonical completion of every owned Ebb run task before database
  and telemetry shutdown, including already-terminal exhausted runs, so the
  outer `run` and `run.rounds` spans finish and export before the SDK closes.

- Make project-image startup, introspection, and confinement tests share one
  capability-aware sandbox backend resolver, including installed bwrap on
  Linux while preserving explicit no-backend behavior where it is absent.
  Render Jolt's exact seccomp fork-refusal message as a sandbox policy refusal
  instead of an opaque runtime error, and give each sandboxed image a private
  scratch-backed Jolt compiler cache so cold release-runtime startup succeeds
  without exposing the host's shared cache to project code.

- Close failed run tasks durably instead of leaving false `running` rows,
  publish run ownership before throwable post-row setup, and cancel every turn
  task acquired before a partial-start failure.

- Mount Oscope's read-only trace, event, and chart handlers at `/oscope` on
  the existing embedded Samizdat listener, with exact Host validation,
  instrumentation suppression, bounded admission, and drain-before-Durable
  shutdown. The initial surface deliberately excludes OTLP, plot editing, and
  binary export.
- Add a validated, durable `max_total_branches` run control that strictly caps
  initial, forked, repopulated, and workflow-opened branches; `beam_width`
  remains a population target rather than an implicit ceiling.
- Qualify the embedded profile's current Oscope, OTel, chDB, ClickHouse
  exporter, and viewer-only HTTP graph without loading a listener or receiver.
- Add an opt-in `:embedded-telemetry` dependency profile that layers Oscope's
  embedded chDB profile over `:telemetry`, converges its database, data.json,
  Malli, and crypto providers, and raises Samizdat's Jolt floor to 0.8.6 so
  the embedded profile's otherwise non-transitive runtime floor is enforced.
- Prevent untracked binary and runtime files from inflating authored-line
  counts, with bounded scans and symlink-safe handling.
- Add an external-SDK-owner attachment seam that installs Samizdat's observer
  without constructing a provider. Internal and attached runtimes share content
  policy and serialized, transactional lifecycle transitions, including
  exactly-once concurrent and re-entrant shutdown.
- Add explicit local-only embedded Oscope/Durable ownership with retryable
  terminal shutdown and same-root recovery under a fresh fenced instance.
- Add an explicit embedded server launcher with required Durable-root
  configuration, content-off local-only defaults, early centralized signal
  ownership, and bounded ordered shutdown that still retires Durable after an
  application-stop failure.
- Add an optional OpenTelemetry profile for Samizdat's nine run seams, a
  versioned typed-attribute manifest, independently bounded local and Langfuse
  exporters, and a fail-open source hook. The stock profile remains free of
  telemetry dependencies. This profile is anchored to upstream main
  `22be90ddf9b05ba8406d6ec231d2748a4da22d8e` and preserves its distinct
  `:shipped`, `:failed`, and `:error` workflow outcomes. Content-enabled tool
  arguments cross the same known-value redaction boundary as tool results.

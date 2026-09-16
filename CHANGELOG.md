# Changelog

## Unreleased

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

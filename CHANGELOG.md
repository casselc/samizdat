# Changelog

## Unreleased

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
- Add an optional OpenTelemetry profile for Samizdat's nine run seams, a
  versioned typed-attribute manifest, independently bounded local and Langfuse
  exporters, and a fail-open source hook. The stock profile remains free of
  telemetry dependencies. This profile is anchored to upstream main
  `22be90ddf9b05ba8406d6ec231d2748a4da22d8e` and preserves its distinct
  `:shipped`, `:failed`, and `:error` workflow outcomes. Content-enabled tool
  arguments cross the same known-value redaction boundary as tool results.

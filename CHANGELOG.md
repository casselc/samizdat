# Changelog

## Unreleased

- Add an optional OpenTelemetry profile for Samizdat's nine run seams, a
  versioned typed-attribute manifest, independently bounded local and Langfuse
  exporters, and a fail-open source hook. The stock profile remains free of
  telemetry dependencies. This profile is anchored to upstream main
  `22be90ddf9b05ba8406d6ec231d2748a4da22d8e` and preserves its distinct
  `:shipped`, `:failed`, and `:error` workflow outcomes. Content-enabled tool
  arguments cross the same known-value redaction boundary as tool results.

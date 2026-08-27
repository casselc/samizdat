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

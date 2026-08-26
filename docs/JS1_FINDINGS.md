# JS1 Findings - PASS

## Decision

**PASS for JS1 only.** This decision accepts the bounded JS1 evaluator evidence
at the final coordinates below. It does not approve or claim a self-hosting
canary.

## Final Coordinates

- Samizdat: pushed branch `js1-bounded-samizdat` at
  `897cf534ffd12939c17048477c83fb4be4560672`.
- Jolt: `4af2362176160f2ed0e366689d7232b1a38adfec`, based on upstream
  `4c0022d4a8f0270fb8efc8393acf3882c459a823`.
- SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` (`0.13.53`).

## Executed Gates

| Gate | Result |
|---|---|
| Runtime coordinate check | `bin/js1 check` passed |
| JS1 smoke | 32 tests / 286 assertions / zero failures/errors |
| Full Samizdat suite | 1100 tests / 4071 assertions / zero failures/errors |
| Live dogfood | 5 tests / 40 assertions / zero failures/errors |

The live dogfood reached durable, quiescent RED, then underwent an intentional
kill followed by fresh-process resume/recovery to GREEN. Its final evidence is
recorded in [`JS1_LIVE_DOGFOOD.md`](JS1_LIVE_DOGFOOD.md).

## Historical Evidence

The following is historical only and is not a final-coordinate claim:

- Samizdat `8995e113`.
- Jolt `279bca18bbf50f37b8574a4e6998dee40313cd26`, upstream base `edda7aec`.
- Historical live-dogfood run `709e2b2d-c0af-40e6-9d3e-0d9624217a2b`.

## Explicit Non-Claims

- The self-hosting canary was **NOT run**; **SELF-HOSTING CANARY: PASS** is not claimed.
- No generic shell, `project/run`, network, Git mutation, JS2, multi-agent binding, or controller self-modification is claimed.
- Non-Linux and plain-JVM lanes are unexecuted.
- No performance claim is made.
- A3c guest isolation and clean-consumer SmolVM are unclaimed.
- Resume-loop provenance remains the current configuration/latest workflow, not a journaled version.
- Whole-run manifests are unsupported for JS1 and refused.
- One semantic operation authorized before `revoke!` may complete after revocation linearizes.

Operational prerequisites, verification, and abort guidance are in
[`SELF_HOSTING_HANDOFF.md`](SELF_HOSTING_HANDOFF.md). The staged-only canary
prompt is [`SELF_HOSTING_CANARY_PROMPT.md`](SELF_HOSTING_CANARY_PROMPT.md).

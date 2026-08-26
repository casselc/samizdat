# Self-Hosting Handoff

## Scope

JS1 is **PASS** at the coordinates below. The self-hosting canary is **not
run** and no canary PASS is claimed. This handoff authorizes an operator to
stage and, only under separately approved operational control, run the canary.

## Required Coordinates

- Samizdat branch `js1-bounded-samizdat`, pushed tip
  `897cf534ffd12939c17048477c83fb4be4560672`.
- Jolt `4af2362176160f2ed0e366689d7232b1a38adfec`, with upstream base
  `4c0022d4a8f0270fb8efc8393acf3882c459a823`.
- SCI `32d62a5136ad3dc148588752f5bcc4cc30b14752`, version `0.13.53`.

No tag is asserted or required by this handoff.

## Operator Prerequisites

- Use a clean Linux checkout at the required Samizdat revision and a clean Jolt
  checkout at the required revision, with Jolt's `vendor/sci` submodule at the
  required SCI revision.
- Have `git`, `sh`, and threaded Chez Scheme 10.x available. Set `JOLT_HOME`
  if Jolt is not the sibling `../jolt` checkout.
- Ensure the intended local provider endpoint is explicitly approved and
  reachable before any separately authorized canary execution. The recorded
  dogfood endpoint was `http://localhost:13305/v1` using local Lemonade Qwen3.6.
- Use a disposable target worktree and preserve the trusted source checkout
  without mutation.
- Review the staged-only prompt in
  [`SELF_HOSTING_CANARY_PROMPT.md`](SELF_HOSTING_CANARY_PROMPT.md) before any
  operational decision.

## Verification Gates

Before staging a canary, require all of the following exact gates:

    bin/js1 check
    bin/js1 smoke
    # Run the repository's full Samizdat suite using its documented standard command.

Accept only these recorded results at the required coordinates:

- `bin/js1 check` passes.
- JS1 smoke: 32 tests / 286 assertions / zero failures/errors.
- Full Samizdat suite: 1100 tests / 4071 assertions / zero failures/errors.
- Final live dogfood evidence: run `44665e68-4d2c-45cd-9ca3-2e8c419168d8`, 5
  tests / 40 assertions / zero failures/errors, durable quiescent RED, then
  intentional kill, fresh-process resume/recovery, and GREEN.

Inspect the retained dogfood artifacts at:

`~/.local/share/samizdat/js1-dogfood/run-1787760644921-687736b3-7bea-404e-b6a8-a407cbe466c5/artifacts`

Do not substitute historical runs or coordinates for these gates.

## Abort And Rollback

Abort immediately if a coordinate, clean-tree check, pin, gate result, provider
identity, authority boundary, or artifact path differs from this handoff. Do
not retry by changing pins, code, tests, prompts, or capability configuration.

On abort, stop the canary/controller process, preserve logs and artifacts, and
discard the disposable target worktree. Restore the operator environment to the
recorded Samizdat, Jolt, and SCI coordinates before any later investigation.
Do not mutate Git state, publish a tag, or claim canary PASS from an aborted or
partial run.

## Standing Limits

- Canary NOT run; PASS is not claimed.
- No generic shell, `project/run`, network, Git mutation, JS2, multi-agent binding, or controller self-modification.
- Non-Linux and plain-JVM lanes remain unexecuted; no performance claim exists.
- A3c guest isolation and clean-consumer SmolVM remain unclaimed.
- Resume provenance is current configuration/latest workflow, not a journaled version.
- Whole-run manifests are unsupported for JS1 and refused.
- An operation authorized before revocation can complete after revocation linearizes.

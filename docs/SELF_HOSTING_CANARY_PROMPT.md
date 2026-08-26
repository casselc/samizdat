# Self-Hosting Canary Prompt - Staged Only

## DO NOT EXECUTE

This document is a staging artifact only. **DO NOT EXECUTE this prompt, start a
provider, run a canary, invoke tools, modify a worktree, or claim a canary
result.** Execution requires separate explicit operator authorization.

## Proposed Prompt

> You are operating only within the separately approved, disposable canary
> target worktree. First confirm that the operator has recorded successful
> `bin/js1 check`, JS1 smoke, and full-suite gates at Samizdat
> `897cf534ffd12939c17048477c83fb4be4560672`, Jolt
> `4af2362176160f2ed0e366689d7232b1a38adfec`, and SCI
> `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`. If any coordinate
> or gate differs, stop and report the mismatch. Do not use generic shell,
> `project/run`, network, Git mutation, JS2, multi-agent behavior, or controller
> self-modification. Do not treat a whole-run manifest as supported. Preserve
> evidence for each authorized semantic operation. If authority is revoked,
> stop requesting new operations; an operation already authorized may complete
> after revocation linearizes. On any unexpected result, stop, preserve logs and
> artifacts, and report for operator rollback.

## Operator Completion Criteria

Do not mark the canary PASS unless a separately authorized execution produces
and retains its own complete evidence. JS1 PASS and the historical/final
dogfood records do not constitute a self-hosting canary PASS.

# JS1 Live Dogfood — Passed Evidence

This is live evidence, not a self-hosting canary or a JS1 PASS decision.

## Coordinate

- Samizdat: `8995e113` on `js1-bounded-samizdat`.
- Jolt: `279bca18bbf50f37b8574a4e6998dee40313cd26` on
  `js1-runtime-current-upstream`.
- SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`.
- Provider/model: local OpenAI-compatible Lemonade
  `http://localhost:13305/v1`, `Qwen3.6-27B-MTP-GGUF`.
- Run: `709e2b2d-c0af-40e6-9d3e-0d9624217a2b`.

## Result

The bounded single-player model completed the scripted task through the JS1
surface only. It reached durable RED verification at turn 10, was stopped only
after the history was quiescent, and the producer process exited `137`. A fresh
process resumed the same run, reconstructed the previously-defined SCI helper
without redefinition, read the controller-produced red evidence, made the
second anchored edit, and reached GREEN (`resume-exit 0`). The harness result
was 5 tests / 40 assertions, zero failures/errors.

The recorded semantic operations total two lists, two searches, four reads, two
stats, and two anchored edits. The reconstruction counter remained unchanged:
replay invoked zero project operations. The resumed phase added only the red
evidence read, source stat, and green edit.

Artifacts are retained under:

`~/.local/share/samizdat/js1-dogfood/run-1787629704126-ad047d12-b837-4330-b1ba-7e162c0034f0/artifacts`

The target was a detached disposable worktree; the trusted source checkout was
witnessed unchanged. This does not claim guest isolation, generic execution,
or a self-hosting canary.

---
name: repl-workflow
description: REPL-driven development in the live jolt image — prototype in eval, then WRITE the change to the file. The file on disk is the deliverable; an eval is scratch that vanishes.
---

# REPL-driven development

You work against a live jolt image: `eval` runs Clojure in the same process the
harness runs in, and defs persist across evals within your run. That is a
powerful way to develop — but it is a way to *develop*, not the deliverable. The
deliverable is the **edited file on disk**. An eval is scratch: it disappears
when the run ends, it does not show up in the diff, and a reviewer reading the
code will not see it. If your change lives only in eval, you have not made it.

## The loop

1. **Look.** `read_file` the file and function you need to change. `eval` to
   inspect the live image — call the function, look at what it returns, check
   your assumptions against the running system.
2. **Prototype.** Draft the new version of the function and `eval` it to try it
   out. Iterate here: this is where the REPL earns its keep — you see the real
   behaviour in seconds instead of guessing.
3. **Write it to the file.** Once the prototype works, put it in the file with
   `edit_file` (a targeted change) or `write_file` (a new or fully-rewritten
   file). **This step is not optional.** This is the moment the change becomes
   real. Do it as soon as the prototype is right — do not keep polishing in eval.
4. **Verify the file.** Re-`eval` `(require 'the.namespace :reload)` and call the
   function again to confirm the *file* has what you prototyped, then run the
   tests. Nothing you haven't run counts.

## The trap to avoid

The failure mode is prototyping in eval, watching it work, and calling `done` —
with the file never touched. The diff is empty, so nothing was actually built,
and it bounces straight back to you. eval is where you figure out the change;
`edit_file`/`write_file` is where you make it. Before you `done`, ask: *is my
change in the file?* If `git diff` would show nothing, you are not done.

## Practical notes

- Small, surgical edits: prototype the exact form, then `edit_file` just that
  region. Don't rewrite a whole file for a two-line change.
- After editing, `(require 'ns :reload)` picks up the file so your next eval
  runs the real thing, not your stale in-memory def.
- Keep the file compiling at each step — reload after each edit so a typo
  surfaces immediately, not three edits later.

## Working discipline

- **Plan as structure, not intention.** Before you touch files, name the exact
  namespaces, functions, and the order — "change the catch in `remember!`, then
  add its test" — not vague aims like "make it robust".
- **Never repeat a failed call.** If a call errors, returns nothing, or a test
  fails, read the message and do something *different* — inspect, narrow, or
  pick another tool. Re-issuing the same `eval`/`grep` hoping for a different
  result is the fastest way to burn your whole turn budget on nothing.
- **One tool per decision.** `read` to inspect, `grep` to locate, `edit_file` to
  change, `eval`/`shell` to run — pick the single right one instead of trying
  several.
- **Stay on your assigned part.** Re-anchor to what you were asked; do not
  "clean up" or refactor code you were not asked to touch. Success is the
  specific thing asked for, verified to work, with no unrequested changes — and
  the change is in the file.

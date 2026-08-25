# JS1 bounded live dogfood task

This is a scripted recovery contract, not an open-ended coding task. Use one
tool call per numbered step and do not improvise, combine steps, or repeat a
semantic project operation. The run is single-player and has a total budget of
14 turns across the crash and resume.

Before the crash:

1. Discover the JS1 project capability surface with `complete`, prefix
   `project/`.
2. Discover the reviewed pure language surface with `doc`, symbol `map`.
3. Discover the anchored edit contract with `doc`, symbol `project/edit`.
4. Inspect the project in one `eval`, in this exact order: call
   `project/list` on `"."`; call `project/search` for `"DOGFOOD"` under
   `{:path "."}`; then return a map containing `project/read` results for
   `TASK.md`, `src/dogfood.clj`, and `test/dogfood_test.clj` (in that order).
5. In one `eval`, define the persistent SCI helper exactly as
   `(defn dogfood-helper [x] (str "DOGFOOD-HELPER:" x))`, then call it with
   `"initial"`. Do not redefine this helper later.
6. In one `eval`, call `project/stat` on `src/dogfood.clj` and define
   `red-base` to its digest.
7. In one `eval`, make the first anchored edit with `project/edit`, using
   `red-base`, replacing the complete source with exactly:

       (ns fixture.dogfood)

       (def dogfood-state :red)

8. Call `done` with an answer that mentions the dogfood task. The fixture-owned
   focused verifier must deterministically report RED. Do not try to repair it
   in this process: the controller will kill this process at the durable red
   `ship-verify` checkpoint.

After the fresh-process resume:

9. First call `(dogfood-helper "resumed")` in `eval`. Its success proves the
   helper was reconstructed; do not redefine it.
10. In one `eval`, read `red-evidence.txt` exactly once. This is the red evidence
   emitted by the fixture-owned focused verifier before the crash.
11. In one `eval`, call `project/stat` on `src/dogfood.clj` and define
    `green-base` to its digest.
12. In one `eval`, make the second anchored edit with `project/edit`, using
    `green-base`, replacing the complete source with exactly:

        (ns fixture.dogfood)

        (def dogfood-state :green)

13. Call `done` with an answer that mentions the dogfood task. The same
    operator-configured focused verifier must report GREEN.

Never use a file or shell tool. All project inspection and both edits go only
through the normal model-facing JS1 `eval` surface and its `project/*`
operations.

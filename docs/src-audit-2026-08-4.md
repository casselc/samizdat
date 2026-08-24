# src/ architecture audit — 2026-08-4

Question: does src/ contain only general-purpose kernel/executor logic,
or is workflow/business logic baked into code that belongs in resources/
(manifests + cells, agent-modifiable at runtime)? Performed by a read-only
researcher pass over the post-fix tree (commit 42b7ddb plus the safe-state
trigger correction); every claim spot-verified on the main thread where it
bore on live code (the :claim-status producer absence and the explore-cap
Lean prose were both confirmed and fixed — see the review-4 fix log).

---

# Audit: workflow logic in `src/samizdat/` vs `resources/`

**Direct answers to the three specific questions first:**

- **Phase routing:** The turn's step sequence is **manifest-driven** for single-branch runs — `resources/manifests/loop.edn` / `worker.edn` carry the `:edges`/`:dispatches` (`:parse → {:provider-error :no-call :tool}`), and `resources/cells/loop.clj` wraps each loop.clj step as a cell. There are **no `:tool`/`:steer`/`:judge` conditionals** in loop.clj. However, `beam.clj` composes the same steps directly in code (`branch-loop/run-turn`, beam.clj:514) — a documented duplication ("the beam still composes its turns directly (karamazov-ioo.20 tracks its migration)", workflow.clj:29). The **phase names `:explore`/`:build` ARE hard-coded** in state.clj and loop.clj's `phase-valve`.
- **Arbiter settle table:** It is data shaped like a table (`def ^:private settle-called {...}`, arbiter.clj:133–146) but it lives **in code**, not in resources.
- **Cull thresholds:** Read from `gates.edn` (`:cull-threshold`, `:cull-recent-window`, `:cull-hard-multiple`, `:reframe-grace`, `:juvenile-grace` — beam.clj:137–158) **except** the mechanics multiplier `(* 2 threshold)` at beam.clj:164 and `(* 2 cull-threshold)` at state.clj:701, both hard-coded.

Also notable: **no tool in the current surface emits `:claim-status` artifacts** (the verification engines left; grep confirms only consumers remain), so the whole `:sketch/:confirmed/:empirical` machinery is workflow scaffolding for a tool surface that no longer exists in src.

---

## Findings

### src/samizdat/agent/loop.clj

1. **loop.clj:601–614** — `run-turn` composes `phase-valve → call-model → absorb → tool-step → journal-step → steer-step` in code. **KEEP** — kernel executor; the manifest path shares these step fns via cells, and the duplication is a tracked migration (ioo.20), not business logic.
2. **loop.clj:299–303** — `(str ... "The explore prologue is over: ") ... " BUILD phase — Lean verification is available and" " \`sketch\` is not. The way forward is to prove your" " claims directly."` **MIGRATE** — names a retired proof tool surface and phase vocabulary inside the kernel; the threshold is already in gates.edn (`:explore-cap`), only the message and phase transition prose are baked in. → prompt file (e.g. `prompts/explore-cap.md`) + transition rule in manifest/gates.edn.
3. **loop.clj:436–445** — `(= :sketch (:claim-status a)) (state/enter-build turn)` / `(:reframe-entered-turn branch) (state/clear-reframe)` / `(= :confirmed (:claim-status a)) (state/mark-green)`. **MIGRATE** — claim-status→phase-state transitions are the claim-first workflow's state machine encoded as `cond->` clauses in the executor; no current tool even produces these statuses. → declarative transition table (manifest key like `:transitions {[:artifact :sketch] :enter-build ...}`) or a `tool/dispatch` cell post-step.
4. **loop.clj:583–590** — `(= :turn-budget (:gate decision)) (assoc :notified-fractions ...)` / `(= :stuck (:gate decision)) (state/begin-reframe turn ...)`. **MIGRATE** — gate *effects* keyed by hard-coded gate names in the loop ("A gate is data and cannot mutate the branch, so its effect is applied here"); the effect should be declared on the gate data. → `:effect` field on the gate entry in gates.edn, dispatched generically.
5. **loop.clj:96–99** — `(and share? (= :confirmed (:claim-status artifact)) (state/advances-thesis? branch (:claim artifact)))` in `shareable?`. **MIGRATE** — the sharing entry-condition is workflow policy (which statuses count, whether relevance gates export) hard-coded in the kernel; the on/off flag is already config (`:run :share-artifacts?`). → `:share {:statuses #{:confirmed} :require-relevance? true}` in gates.edn or run config.
6. **loop.clj:188–258** — `max-call-attempts` / doubled-budget retry in `call-model`. **KEEP** — provider plumbing.
7. **loop.clj:318–388** — `absorb-response` / `no-call-step` fence mechanics. **KEEP** — protocol plumbing.
8. **loop.clj:453–484** — `journal-step!`. **KEEP** — durable-record plumbing.
9. **loop.clj:486–511** — `drain-directives!` case on directive kinds. **KEEP** — intervention-queue plumbing shared with the HTTP surface.
10. **loop.clj:120–184** — `context-block` (ledger + breadcrumbs + inbox + failures + shared artifacts, counts 5/15 hard-coded). **KEEP** — generic context assembly over stores; sizes are engine tuning, not project policy.
11. **loop.clj:107–118** — `initial-messages` with `prompt-suffix`. **KEEP** — already the manifest-injection seam (`:prompt` per manifest).

### src/samizdat/agent/beam.clj

12. **beam.clj:136–158** — cull ladder reading `(gates/threshold :cull-threshold)`, `:cull-recent-window`, `:reframe-grace`, `:cull-hard-multiple`, `:juvenile-grace` from gates.edn. **KEEP** — retention engine properly parameterized.
13. **beam.clj:164** — `(and (>= mech (* 2 threshold)) (pos? survivors))`. **MIGRATE** — the only cull number not in gates.edn; a policy multiplier ("twice that is a branch that cannot work the protocol") hard-coded. → `:cull-mechanics-multiple` entry in gates.edn.
14. **beam.clj:236–242 and 258–265** — `" A branch this new is not culled for it — you were forked to"` ... `"the reprieve ends unconditionally at "` hard-floor `" consecutive failures."`. **MIGRATE** — multi-sentence steer prose for the juvenile-grace and Pareto-reprieve policies lives in scheduler code while every other gate's prose lives in `prompts/`. → `prompts/juvenile-grace.md`, `prompts/cull-reprieve.md` with the numbers interpolated.
15. **beam.clj:52–70** — `crossover-block`: `"**Confirmed by other lineages in this run** — engine-verified," ... " and yours to build on or combine with:"`. **MIGRATE** — recombination-policy prose (and the `take-last 8` cap) baked into src. → prompt file read by `open-branch!`.
16. **beam.clj:286–351** — `repopulate` mark logic reading `:max-total-branches`/`:fork-invite-cooldown`/`:fork-invite-floor` from gates.edn. **KEEP** — width-maintenance engine; the *ask* is already a gate.
17. **beam.clj:374–415** — `spawn-children!` cap messaging. **KEEP** — cap comes from gates.edn; messages are dynamic scaffolding.
18. **beam.clj:417–440, 501–537** — turn deadline + `advance-all` futures. **KEEP** — process lifecycle plumbing (explicitly the RAX-manager principle).
19. **beam.clj:570–606** — `finish-now?` reading `(get-in ctx [:config :run :stop-on-first-done?] true)`. **KEEP** — exactly the right shape: policy switch in config.
20. **beam.clj:608–807** — `run-rounds`/`run!` scheduling, seeding, teardown. **KEEP** — plumbing.

### src/samizdat/agent/gates.clj

21. **gates.clj:68–432** — `(def gates [{:gate :human-directive :priority 0 ... :when (fn ...) :message (fn ...) ...}` — the entire gate table (15 gates: conditions, priorities, messages, predictions, windows, forced tools) is closures in src, while gates.edn holds only numeric thresholds. **MIGRATE** — this is the harness's steer policy, i.e. the workflow itself; the project's own docstring says "A gate is data" yet only its scalars are. → gates.edn entries gaining `:when`/`:message`/`:prediction` as EDN forms evaluated like manifest `:dispatches` predicates, or one cell per gate in `resources/cells/`.
22. **gates.clj:424** — `(and (state/active? branch) (pos? n) (zero? (mod n 15)))` in the `:reflection` gate. **MIGRATE** — a hard-coded cadence constant (and self-hosting-specific prose mentioning `introspect`/`reload_cells`) inside src. → `:reflection-cadence` in gates.edn; message → `prompts/reflection.md`.
23. **gates.clj:41–55** — `load-config`/`threshold` reading `gates.edn` via io/resource. **KEEP** — this is the existing seam to widen, not remove.

### src/samizdat/agent/arbiter.clj

24. **arbiter.clj:40–69** — `eligible`/`decide` priority arbitration. **KEEP** — the engine; gates are its data.
25. **arbiter.clj:93–107** — `(def ^:private forceable {"done" {...} "give_up" {...}})` — hard-coded JSON schemas for the two forceable terminal tools. **MIGRATE** — tool schemas duplicated in src instead of derived from the tool registry or declared beside the gate. → `:forceable-tools` in gates.edn or registry metadata.
26. **arbiter.clj:133–146** — `(def ^:private settle-called {:milestone #{"done"} :branch-out #{"branch_theses"} ... :studying #{"write_file" "edit_file" "shell" ...}})`. **MIGRATE** — already pure data mapping gates to compliance tool-vocabulary, living in code with a test pinning it to the registry; a project whose cells add tools cannot retune compliance without recompiling. → `:settle-called` map in gates.edn next to each gate's budget.
27. **arbiter.clj:154–214** — `settle` (incl. the elaborate `:stuck` compliance clause). **KEEP** — deterministic settle engine; its inputs (finding 26) are the data part.

### src/samizdat/agent/state.clj

28. **state.clj:33–105** — `new-branch` map (counters, mechanics, predictions). **KEEP** — kernel data model.
29. **state.clj:109–146** — claim-status accessors (`confirmed-artifacts`, `empirical-artifacts`, `banked-in-last`). **KEEP** — generic filters; the taxonomy's *enforcement* is what's misplaced elsewhere (findings 3, 5).
30. **state.clj:148–180** — `finished-key`: `[(if (:relaxation? (:last-audit branch)) 0 1) (if (contains? (:tiers-seen branch) :slow) 1 0) (count (distinct (keep :kind ...))) ...]`. **MIGRATE** — the winner-selection rubric (non-relaxation > slow-tier > engine-diversity > count) is explicitly the ported UCLA FirstProof *policy*, baked as a tuple in src. → `:finished-key` component list in gates.edn / the manifest of whichever loop uses multi-candidate selection.
31. **state.clj:212–216** — `(def ^:private claim-stopwords #{"the" ... "clpfd" "prolog" "smt" "lean" "works" ...})`. **MIGRATE** — domain vocabulary (proof-engine names) as data-in-code feeding `advances-thesis?`. → a `resources/` wordlist (or gates.edn `:relevance-stopwords`).
32. **state.clj:243–275** — `advances-thesis?` token-intersection heuristic. **KEEP** — generic relevance algorithm; only its wordlist (finding 31) is project data.
33. **state.clj:287–302** — `(def verification-tools ... #{"eval" "shell"})`. **MIGRATE** — hard-coded tool vocabulary that defines what counts as "trying something" for the stuck-gate and supervisor ("On the coding loop the engines are the REPL and the shell"); the comment itself says it's the live vocabulary a test pins. → gates.edn `:verification-tools`, retunable per project.
34. **state.clj:304–389** — the `:explore`/`:build` phase machine (`enter-build`, `explore-cap-expired?`, `:phase :explore` default at line 78). **MIGRATE** — a two-state workflow machine in kernel code, with phase-refusal policy already stubbed out of tools (base.clj:83–95 "the coding loop's phase policy plugs back in here when the loop-as-manifest work defines it"). → phase states/transitions declared in the manifest; `tools.base/phase-refusal` reads them.
35. **state.clj:313–378** — reframe primitives (`enter-reframe`, `clear-reframe`, `abandoned-log`). **KEEP** — branch-state manipulation primitives; the *policy* that triggers them is the `:stuck` gate (findings 4, 21).
36. **state.clj:391–466** — `record-outcome` counter semantics. **KEEP** — the counters are the gates' input contract; heavily kernel-entangled.
37. **state.clj:478–509** — `repeating-failure?`. **KEEP** — generic loop detection.
38. **state.clj:527–653** — residual report builder/renderers (`"PROGRESS REPORT — not a solution. Nothing below is"` ...). **KEEP** — run bookkeeping; prose is samizdat's honesty mandate rather than per-project policy (borderline: the section labels could be a prompt template if another workflow needs different residuals).
39. **state.clj:663–693** — `mark-green`/`snapshot-covers?`. **KEEP** — journal-cursor checkpoint plumbing.
40. **state.clj:695–701** — `(>= (:consecutive-failures branch) (* 2 cull-threshold))` in `safe-state-due?`. **MIGRATE** — a second hard-coded ×2 threshold multiple not in gates.edn (its own budget `:max-safe-state-aborts` is). → `:safe-state-multiple` in gates.edn.

### src/samizdat/agent/tools/

41. **tools/base.clj:27–107** — the `run-tool` multimethod, result helpers, `:default`, and the `phase-refusal` seam (currently `nil`). **KEEP** — the registry is exactly the data-driven surface the design calls for.
42. **tools/ship.clj:38–95** — `(def ^:private stopwords #{"the" ... "lean" "mathlib" "prolog" "clpfd" "swipl" "z3" "smt" "smtlib" "octave" ...})` (~90 words with proof-engine provenance sections). **MIGRATE** — a large curated wordlist, pure data, baked into the done-gate. → `resources/` wordlist consumed by `answer-tokens`.
43. **tools/ship.clj:101–102** — `(def ^:private tool-version-re #"(?i)\b(lean|mathlib|z3|swipl|swi-prolog|prolog|octave|clojure|jolt|python|node|java|deepseek)[\s-]*[0-9]+...")`. **MIGRATE** — environment-vocabulary regex as data-in-code. → same resource file as the stopwords.
44. **tools/ship.clj:216–317** — the `done` rungs: number-coverage + `engages-problem?` hard-coded; the verify rung already config-driven (`(get-in ctx [:config :run :verify-cmd])`, line 273). **MIGRATE** (the rung composition) — the code's own comment says "The coding loop's ship gate (tests pass, review passed) rebuilds on this seam as data-defined gates"; ship-gate rungs are per-workflow policy. → `:ship-gates` vector in gates.edn/manifest naming runnable rungs.
45. **tools/ship.clj:327** — `(def max-branch-theses 4)`. **MIGRATE** — a fork-budget policy constant in a tool; every other fork number is in gates.edn. → `:max-branch-theses` in gates.edn.
46. **tools/ship.clj:19–34, 319–359** — `thesis`, `give_up`, `branch_theses`. **KEEP** — generic capabilities (intent registration, terminal, fork request).
47. **verify.clj:51–68** — `focused-cmd` building `"jolt -A:test -e '...'"` with `clojure.test/run-tests`. **MIGRATE** — the focused test invocation is hard-wired to jolt/Clojure while the fallback `:verify-cmd` is config; any other project's test runner cannot use focused verification. → a `:focused-verify-cmd` template in run config / project `.samizdat/config.edn`.
48. **verify.clj:23–49** — `test-file?` (`#"(?i)(^|/)(test|spec)s?/|[_-](test|spec)\.[a-z]+$"`) and `ns-from-test-path` (`#"\.cljc?$"`). **MIGRATE** (with 47) — Clojure-layout conventions as code. → config regexes alongside `:verify-cmd`.
49. **verify.clj:76–141** — `verify-block` decision (reads `:require-test?` etc. from config) and `run-verify` with scrubbed env. **KEEP** — pure decision + security plumbing, properly parameterized.
50. **tools/manifest.clj** — manifest list/show/save with compile-validation. **KEEP** — generic self-hosting capability.
51. **tools/mutate.clj** — `reload_cells` checkpoint→reload→validate→soak. **KEEP** — the mutation protocol is the engine of the stated goal.
52. **tools/tasks.clj** — task-board CRUD. **KEEP** — generic.
53. **tools/{files,repl,shell,lsp,journal,messages,knowledge,skills,introspect}.clj** — generic I/O, store, LSP, introspection capabilities. **KEEP** — no engine/project vocabulary (grep for `jolt|clpfd|swipl|lean|mathlib|karamazov` across tools/ hits only ship.clj's data tables).

### src/samizdat/agent/{judge,supervisor,critic,decompose}.clj

54. **judge.clj:34–91** — verdict/findings parsers and the `evidence` builder. **KEEP** — pure kernel (though `evidence` hard-codes `#{"write_file" "edit_file"}` / `"shell"` at lines 77/81 — fold into finding 56's vocabulary table).
55. **judge.clj:98–117** — `(re-find #"(?i)-M:test|-A:test|run-tests|\bjolt\s+test\b|\bpytest\b|\bcargo\s+test\b|\bnpm\s+test\b" ...)` and `"Run the suite (\`jolt -M:test\`)"`. **MIGRATE** — deterministic verifier rules enumerate a fixed test-runner ecosystem and name jolt's invocation in a refusal message. → verifier-rule data (regex + message template) in gates.edn or a `resources/` rules file, mirroring finding 47.
56. **judge.clj:119–150** — `claim-block`/`source-block` ("samizdat has read_file/grep/lsp for the LOCAL repo and no web or fetch tool"). **MIGRATE** — the rule's docstring admits it encodes today's tool surface as an assumption; a project with a web tool inherits a false block. → derive the outside-reach set from the registered tool surface (registry metadata) or manifest config.
57. **judge.clj:158–194** — `(def preamble "You are a reviewer deciding whether an agent's work on a task is ...")` + `critic-prompt`. **MIGRATE** — a ~25-line judge prompt as src code while every other gate prompt is in `resources/prompts/` (the cell that calls it already lives in `resources/cells/critic.clj`). → `prompts/judge.md` with the same `{{...}}` substitution system.md already uses.
58. **supervisor.clj:21–27** — `(def shipping-tools #{"write_file" "edit_file" "shell" "reload_cells" "manifest" "done" "give_up" "thesis" "branch_theses"})`. **MIGRATE** — the studying/shipping tool taxonomy is project vocabulary in code, and `settle-called`'s `:studying` row (finding 26) duplicates part of it. → one `:tool-vocab` section in gates.edn (`:shipping`, `:verification`) consumed by both supervisor and arbiter.
59. **supervisor.clj:31–58** — `over-studying?` + `stall-nudge`. **KEEP** — detection engine; nudge prose is dynamically composed with tool names.
60. **critic.clj:44–52** — `(def objectives [:progress :momentum :distinctness :viability])`. **MIGRATE** — the scoring rubric is selection policy; `:fork-invite-floor` already keys on these names from gates.edn, so the objective set is already half-externalized. → `:critic-objectives` in gates.edn.
61. **critic.clj:140–163** — `score!` prompt: `"You are the research director over parallel proof attempts on one problem."`. **MIGRATE** — proof-domain prose hard-coded in src and wrong for the current coding surface; parse-scores' regex (lines 67, 71) is coupled to it. → `prompts/critic.md` (parameterized by the objective list from finding 60), keeping `parse-scores` in src.
62. **critic.clj:54–94, 96–132** — `parse-scores`, `dominated?`, `summary`. **KEEP** — fail-closed parsing and Pareto engine.
63. **decompose.clj:27–33** — `(def max-depth ... 3)` / `(def default-max-parts 4)`. **MIGRATE** — recursion/part budgets are workflow policy constants in src (`solve` already accepts an ops override, so the seam exists). → config `:run :decompose-max-depth` / `:max-parts` or the decompose manifest.
64. **decompose.clj:35–79** — `architect-prompt` prose in src. **MIGRATE** — same pattern as judge/critic prompts; the orchestration correctly lives in `resources/cells/decompose.clj`. → `prompts/architect.md`.
65. **decompose.clj:102–231** — `solve` recursion with injected ops. **KEEP** — pure control flow, exactly the kernel/cell split done right.

---

## Summary

**20 KEEP, 15 MIGRATE** (findings 2, 3, 4, 5, 13, 14, 15, 21, 22, 25, 26, 30, 31, 33, 34, 40, 42, 43, 44, 45, 47, 48, 55, 56, 57, 58, 60, 61, 63, 64 — that is **30 MIGRATE** items against **35 KEEP** items in the numbered list; several MIGRATEs are small data moves bundled per file).

Recount by numbered finding: KEEP = 1, 6, 7, 8, 9, 10, 11, 12, 16, 17, 18, 19, 20, 23, 24, 27, 28, 29, 32, 35, 36, 37, 38, 39, 41, 46, 49, 50, 51, 52, 53, 54, 59, 62, 65 → **35 KEEP**. MIGRATE = 2, 3, 4, 5, 13, 14, 15, 21, 22, 25, 26, 30, 31, 33, 34, 40, 42, 43, 44, 45, 47, 48, 55, 56, 57, 58, 60, 61, 63, 64 → **30 MIGRATE**.

### Top 3 migrations by (behavior-change value ÷ migration risk)

1. **Tool-vocabulary tables → gates.edn** (findings 26, 33, 58, plus judge.clj:77/81): `settle-called`, `verification-tools`, `shipping-tools` are *already pure data* — moving them to gates.edn beside the budgets they parameterize is near-zero risk, and it is what lets a project's cells redefine "what counts as compliance/verification/shipping" at runtime, which is precisely the stated goal.
2. **Domain prompts out of src** (findings 61, 57, 2, 64, 14): the critic's "research director over parallel proof attempts", the judge preamble, the Lean/sketch explore-cap message, and the cull-reprieve prose are all wrong-domain or project-flavored text in code with an existing prompt-substitution mechanism (`prompts/*.md`, `{{skills}}`) to receive them. Pure text swaps; immediate per-workflow behavior change.
3. **The gate table as data** (finding 21, subsuming 22, 25): conditions/priorities/messages as EDN predicates in gates.edn (the manifest `:dispatches` pattern proves evaluable EDN predicates already work). Highest value — it is the entire steer policy — and moderate risk since `:when` fns need a small predicate vocabulary (turn-count, consecutive-failures, thresholds) that state.clj already computes.
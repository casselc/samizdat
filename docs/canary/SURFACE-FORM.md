# Surface-form competition: an action that could never win

Follows `docs/canary/MODEL-FRAMING.md`. That document corrected a framing
confound and published the numbers below it. This one corrects a scoring
confound underneath *those* numbers, and reverses one of that document's
conclusions.

Branch `agent/onbox/decide-final-coordinate-refresh`.

## The headline

**Under raw logprob comparison, `:rollback` cannot be selected by the 2B for
any state.** It ranks last in 35 of 37 fixtures and never enters the top 3.
Eight fixtures — 22% of the eval, including 3 of the 13 counterfactual
rows — expect it, and every one was unwinnable before the model saw the
state.

**The standard remedy does not fix it.** Two independent additive
calibrations, one fixed before any number was seen, both make top-1 worse.

**So the earlier reversal is itself reversed:** MODEL-FRAMING said the #5
blocker "no longer holds for the 2B". A controller that cannot emit one of
its five actions is categorically unqualified, whatever its top-1. The
blocker holds.

## How it was found

The discovery needed no model run. `decide_qualification.clj` had always
computed a per-fixture row with every candidate's score and then thrown it
away, reporting aggregates. Issue #4 section 19 asked for those rows to be
kept. Once they were (`docs/canary/qualification-rows-*.edn`), the
per-action score distribution was a one-line question of a frozen file.

The chosen-action line `{:hold 13, :scale 10, :restart 11, :page 3}` had
been printed in every run for a full session. Rollback's absence from it was
visible and unseen. The qualification now checks it mechanically:

    STRUCTURALLY EXCLUDED [:rollback]  <- never argmax on any fixture

The gate fires on the two constant baselines (by construction), on the
fixed-order 2B run (`[:rollback :page]` — two actions, not one), and on the
counterbalanced 2B (`[:rollback]`). It does not fire on the rule or on the
seeded random scorer.

## The evidence (2B, counterbalanced, no-think)

Per-action logprob across all 37 fixtures:

| action | mean | sd | min | max |
| --- | ---: | ---: | ---: | ---: |
| hold | -1.631 | 0.510 | -2.865 | -0.796 |
| scale | -1.785 | 0.632 | -3.697 | -0.751 |
| restart | -2.136 | 1.091 | -4.510 | -0.269 |
| page | -2.156 | 0.412 | -3.056 | -1.345 |
| **rollback** | **-4.258** | 0.901 | -6.086 | **-2.924** |

Rollback's *best* score on any fixture (-2.924) is below hold's *worst*
(-2.865). The distributions do not overlap. Rank distribution for rollback
over 37 fixtures: `{3: 2, 4: 35}`.

This is a property of the token, not the decision. Comparing raw logprobs
across different candidate tokens conflates "this action fits the state"
with "this token is common" — surface-form competition (Holtzman et al.
2021). DECIDE-QUALIFICATION had already measured the 0.8B's null prior as
`[:hold :scale :restart :page :rollback]`, rollback last, without drawing
the consequence.

**The signal is real.** Rollback scores about a nat higher when rollback is
the correct answer (mean -3.478, n=8) than when it is not (-4.474, n=29).
The model moves it the right way. The movement is smaller than the offset.

## Two calibrations, both fail

**A. Mean-centring** (subtract each action's own across-fixture mean; an
oracle-ish additive shift that uses the eval set's own statistics):
top-1 54.1% → 51.4%.

**B. Content-free baseline** (Zhao et al. 2021): score each action against
the same field labels with the values replaced by a filler, per rotation,
and subtract. Fillers `N/A` and `unknown`, averaged, fixed *before* any
calibrated number was seen and not tuned afterwards.

| 2B, counterbalanced | raw | calibrated |
| --- | ---: | ---: |
| top-1 | 54.1% | **32.4%** |
| correct counterfactual | 7.7% | 7.7% |
| wrong + confident | 27.0% | 43.2% |
| chose | `{:hold 13, :scale 10, :restart 11, :page 3}` | `{:scale 23, :restart 12, :page 2}` |
| structurally excluded | `[:rollback]` | `[:hold :rollback]` |

Why it fails is in the baseline itself. The 2B's content-free scores:

    hold -1.003   page -1.870   restart -2.158   scale -2.519   rollback -2.895

and the 27B's:

    hold -0.002   page -7.419   restart -8.608   rollback -10.645   scale -10.673

A state block full of `unknown` is not content-free to these models. It is a
*state* — one where "nothing to do" is the obvious answer — and the more
capable the model, the more certain it is: the 27B puts p ≈ 0.998 on hold.
Subtracting that penalises hold and page and inflates everything else. The
method's premise, that the null input carries no information about the
answer, is false for structured state.

The calibrated scorer's margin is also anti-informative: on the threshold
sweep its precision *falls* as the threshold rises (32% → 0%).

**What this means.** The offset is real and decisive; removing it does not
rescue the model. Rollback's one-nat signal is smaller than the other
actions' state-dependent variance, so even with the prior gone it rarely
wins. That is a limitation of the model, now measured on a corrected footing
rather than hidden under a confound. The base-model verdict survives —
stronger, not weaker.

Not tried, and deliberately: a third null (the state block omitted
entirely). Choosing nulls after seeing results is tuning against the eval,
and calibration A already shows an additive shift derived from the data
itself does not help.

## #3 answered from the rows: no threshold rescues this scorer

`dev/canary/margin_sweep.clj` re-derives act/defer at every threshold from
the frozen rows. It first asserts that at the capture threshold the
re-derived decisions equal the recorded ones — true for every scorer — so
the rest of the sweep is grounded, not eyeballed.

2B counterbalanced, raw:

| threshold | coverage | precision | wrong+confident |
| ---: | ---: | ---: | ---: |
| 0.00 | 100.0% | 54.1% | 45.9% |
| 0.50 | 56.8% | 52.4% | 27.0% |
| 0.75 | 40.5% | **60.0%** | 16.2% |
| 1.25 | 10.8% | 50.0% | 5.4% |
| 1.50 | 5.4% | 0.0% | 5.4% |
| 2.75 | 0.0% | — | 0.0% |

Precision peaks at 60% and the dangerous cell reaches zero only when
coverage does. There is no knee. The two highest-margin decisions are both
wrong, and coherently: `process-crash-after-deploy` (expected rollback,
chose restart) and `auth-with-crash` (expected page, chose restart). n is
too small to quote a rate, but the mechanism is visible — the model is most
confident exactly where a surface heuristic ("crash → restart") fires
hardest, which is the failure a margin guard structurally cannot catch.

DECIDE-QUALIFICATION deferred #3 because the 0.8B's low confidence *was*
the signal and the guard read it. That reasoning does not transfer: the 2B
and 27B are confident, and their confidence does not track correctness.

## 27B

Fixed-order and counterbalanced rows reproduce MODEL-FRAMING's numbers
bit-for-bit (the jolt-llama gate makes that a check, not a hope).

**The gate does not fire on the 27B.** `never-chosen []` for all three
scorers. Rollback is *suppressed*, not excluded: counterbalanced it ranks
last in 28 of 37 fixtures, is top-1 on 2 (both of them rows that expect
it), and is actually acted on once (`regress-both`, correctly) — the other
deferred under the margin floor. Its best raw score (-0.141) clears hold's
worst (-5.612), so a state exists in which it can win, which is the whole
difference from the 2B. The earlier note in
MODEL-FRAMING that said "for either model" was written before this run and
is corrected there.

| 27B scorer | top-1 | ctrl inv (correct) | c/f change (correct) | wrong+conf | rollback rows won: top-1 (acted) of 8 | `chose` (acted) |
|---|---|---|---|---|---|---|
| D  fixed order | 56.8% | 70.6% (64.7%) | 61.5% (7.7%) | 40.5% | 1 (1) | hold 18, scale 10, restart 4, page 4, rollback 1 |
| D' counterbalanced | 54.1% | 82.4% (70.6%) | 69.2% (15.4%) | 29.7% | 2 (1) | hold 21, scale 8, page 4, restart 2, rollback 2 |
| D* + content-free calibration | 51.4% | 64.7% (52.9%) | 46.2% (15.4%) | 40.5% | 4 (3) | scale 21, restart 5, rollback 4, page 4, hold 3 |

The two rollback columns differ because `chose` counts only rows the margin
policy let through; top-1 counts the ranking regardless of defer.

The content-free baseline is the tell:

    hold      -0.002
    page      -7.419
    restart   -8.608
    rollback -10.645
    scale    -10.673

A state with every value replaced by `unknown` is the *strongest* hold
signal the 27B ever sees (p ≈ 0.998). Subtracting it hands every other
action ~7–10 nats and leaves hold with nothing, so D* acts on hold 3 times
against 14 expected and on scale 21 times against 4. It does double
rollback recall (2 of 8 rows won by top-1, to 4 of 8) — the signal that was
invisible under raw comparison is real — but the trade is a wash and then
some: row by row against D' it gains 7 and loses 8 (net top-1 20 → 19
correct), takes the healthy family from 100% to 33.3%, and returns the
dangerous cell to 40.5%. Same mechanism as the 2B, same verdict: the
premise of contextual calibration (a content-free input is uninformative)
is false for structured state.

Margin sweep on the 27B rows (faithful at 0.5 for all three): D' precision
reaches 81% only at 56.8% coverage (t=1.75), and the dangerous cell first
reaches 0 at t=4.0 with 27% coverage. D* is anti-informative here as on the
2B — precision *falls* from 51.4% to 44.4% as the threshold rises to 1.0 —
so the calibrated margin cannot be used as a guard either.

## What this changes

* **#5 — blocker reinstated.** MODEL-FRAMING's "no longer holds for the 2B"
  was wrong. The 2B is structurally unable to select rollback under the
  scoring convention `decide` uses; the 27B can but does so on 1 of the 8
  rows that need it, and the standard correction makes both worse. Neither
  is qualified. `decide` stays wired into no workflow.
* **#4 — the eval is unwinnable at 22% for the 2B under raw logprob.** That
  is not a fixture defect; the labels are right. It is a fact about the
  scoring convention that any future scorer — including a trained one — must
  be checked against. The structural-exclusion gate is that check, and it
  is mechanical: it fired on the 2B and on the fixed-order baselines, and
  correctly did not fire on the 27B.
* **#3 — answered with data.** On the 2B no threshold helps. On the 27B
  (counterbalanced) the dangerous cell reaches 2.7% only at t=3.0 with
  40.5% coverage, and 0% at 27% coverage — and the rows it keeps are the
  hold/scale rows it was already right about, not the rollback rows it was
  wrong about (c/f precision 66.7% on n=3). A guard is a coverage knob
  here, not a safety mechanism: it cannot recover an action the ranking
  suppresses.
* **The convention itself.** A trained controller with a dedicated action
  head does not have this problem: its outputs are not competing surface
  forms. That is one more reason the corpus, not a bigger base model, is
  the path — and one more thing the corpus's action spellings must get
  right before training.

## Files

* `dev/canary/decide_qualification.clj` — rows persisted with per-candidate
  evidence; `render-null`, `context-for-text`, calibrated scorer `D*`;
  `STRUCTURALLY EXCLUDED` gate.
* `dev/canary/margin_sweep.clj` — offline threshold sweep with a
  faithfulness assertion.
* `docs/canary/qualification-rows.edn` — baselines only, runs anywhere.
* `docs/canary/qualification-rows-2b.edn`, `-27b.edn` — 7 scorers × 37
  rows each, with the coordinate that produced them. Model path is a
  basename; no prompt text, no token vectors.

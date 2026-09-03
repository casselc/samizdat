# Framing, scale, and a verdict I got wrong

`docs/canary/DECIDE-QUALIFICATION.md` concluded that Qwen3.5-0.8B was
unqualified and, by extension, that no scorer had yet cleared the bar. The
first half is still true. The second was an artifact of how I was asking.

## The mistake

Every qualification number in that document was measured with a raw-completion
prompt ending in `ACTION:`, scoring `" hold"`, `" scale"`, … . I never checked
whether the model was *answering that question*.

**Domain mass** — the share of next-token belief landing on any legal action —
is the precondition every other metric depends on. Under the original framing:

| model | domain mass | what it actually wanted to emit |
| --- | ---: | --- |
| Qwen3.5-0.8B | 8.5% | `" scale"` 0.16, `" hold"` 0.13, `"\n"` 0.12 |
| Qwen3.5-2B | 54.2% | `" restart"` 0.22, `" page"` 0.16 |
| Qwen3.6-27B | **0.37%** | `"\n\n"` 0.69, `<|im_end|>` 0.26 |

Ranking the remaining 0.4% of the 27B's belief is not measuring a decision. It
is ranking tokens the model was not trying to say.

## Chat framing made it worse, not better

The obvious fix — use the chat template — collapses domain mass further,
because all three are **reasoning** models. Handed an open assistant turn they
want to think:

    <think> at p = 0.72 (0.8B), 0.92 (2B), 0.9954 (27B)

## The framing that works

Pre-closing the reasoning block (`<think>\n\n</think>\n\n`, Qwen3's documented
no-think mode) with **bare** action encodings. Under it every model's entire
top-5 is actions.

## Corrected results

Baselines for reference: majority `:hold` = 37.8% top-1 / 38.5% counterfactual.

The two right-hand columns here are **per-role accuracy**, which is what the
harness reported at the time. They are NOT responsiveness or invariance; see the
correction below, and treat them only as a like-for-like comparison across rows.

| model | framing | top-1 | c/f acc | ctrl acc | wrong+confident |
| --- | --- | ---: | ---: | ---: | ---: |
| 0.8B | completion, counterbal. | 16.2% | 23.1% | 11.8% | 0.0% |
| 0.8B | **no-think, counterbal.** | 16.2% | 23.1% | 11.8% | 2.7% |
| 2B | completion, counterbal. | 18.9% | 30.8% | 11.8% | 5.4% |
| 2B | **no-think, counterbal.** | **54.1%** | 46.2% | 58.8% | 27.0% |
| 27B | **no-think, fixed order** | **56.8%** | 30.8% | 76.5% | 40.5% |

### Re-measured: every row on the same metrics

The 27B has since been re-run under the relational metrics, so the two scorers
that clear the top-1 baseline can finally be compared on the column that matters.

| scorer (no-think) | top-1 | ctrl invariance (correct) | c/f change (correct) | wrong+confident |
| --- | ---: | ---: | ---: | ---: |
| B majority `:hold` | 37.8% | 100% (41.2%) | 0% (0%) | 62.2% |
| 2B counterbalanced | 54.1% | 70.6% (52.9%) | 53.8% (**7.7%**) | 27.0% |
| 27B fixed order | 56.8% | 70.6% (64.7%) | 61.5% (**7.7%**) | 40.5% |
| 27B counterbalanced | 54.1% | 82.4% (70.6%) | 69.2% (**15.4%**) | 29.7% |
| C rule | 100% | 100% (100%) | 100% (100%) | 0% |

**Thirty times the parameters buys almost nothing on the column that matters.**
The 2B and the 27B reach the same top-1 (54.1%) and, counterbalanced, the 27B
gets 2 of 13 counterfactuals right against the 2B's 1 — 15.4% versus 7.7%, one
extra row. Against a 0% floor that is a real difference and a tiny one; against
the rule's 100% it is not a controller.

Both models are far better at *invariance* (70–82%) than at *responsiveness*
(8–15% correct). They are good at leaving the answer alone and bad at knowing
when it should move, which is exactly the shape a constant-ish scorer has and
exactly the wrong shape for a controller.

Counterbalancing helps the 27B on every relational column while costing 2.7
points of top-1 — more evidence that a fixed action order rewards whichever
action is listed first rather than measuring a decision.

The `wrong + confident` column is the operational risk: the 27B acts and is
wrong on 30–40% of fixtures, where the 0.8B was too unconfident to act at all.
A bigger model is not a safer one here.

**Caveat added later:** every model row in this table was scored by raw
logprob comparison, under which `:rollback` is structurally excluded for
the 2B (never argmax on any fixture). 8 of the 37 fixtures, including 3
of the 13 counterfactual rows, were therefore unwinnable by the 2B by
construction — its correct-counterfactual ceiling was 76.9%, not 100%. The
27B is not excluded: it selects rollback once in 37 (correctly) and gets 1
of the 8 rollback rows. (A first draft of this caveat said "for either
model"; that was written before the 27B was re-run with the exclusion
check, and the rows show it was wrong.) The relational numbers stand as
measured; what they measure is narrower than this section implied.
`docs/canary/SURFACE-FORM.md`.

**Correction, after fixing the metrics.** The line above originally read "the 2B
clears the bar", citing 46.2% "counterfactual accuracy" against the majority's
38.5%. Those were *per-role accuracy*, not responsiveness: they measured how
often a counterfactual row was labelled correctly, never whether the scorer
CHANGED its answer between a pivot and its counterfactual. Review caught it and
the relational metrics now compare each sibling with its group pivot:

| 2B, counterbalanced | |
| --- | ---: |
| top-1 | 54.1% (majority 37.8%) |
| control invariance | 70.6% (correct 52.9%) |
| counterfactual **change rate** | 53.8% |
| **correct** counterfactual responsiveness | **7.7%** |

The top-1 gain over the majority baseline is real, but it comes from control
rows — the easy cases where the answer should stay put. On the rows where the
action *should* change, the 2B changes about half the time and is **correct
7.7% of the time**, against a 0% floor for any constant scorer.

So the honest reading is narrower than "clears the bar": framing was a genuine
confound and correcting it moved top-1 from 18.9% to 54.1%, but the model still
does not reliably respond to the state changes that ought to change the action.
The 0.8B remains unqualified at either framing.

So the earlier verdict was wrong, and wrong for a reason worth keeping: I
measured a model without first checking it was answering the question.

## Bounded reasoning made it worse

These models were trained to think, so allowing it is the natural test. 96
tokens of greedy reasoning before scoring, on the 2B:

| 2B | top-1 | wrong+confident |
| --- | ---: | ---: |
| no-think, counterbalanced | **54.1%** | 27.0% |
| +96 tokens greedy reasoning | 45.9% | **45.9%** |

It collapsed toward `:hold` (28 of 37) and became *more* confident while being
wrong more often. Caveat worth stating: 96 greedy tokens probably truncates
mid-thought, and a truncated trace may be worse than none — so this bounds
"cheap reasoning", not "reasoning".

Architecturally, reasoning also costs more than it first appears: it is N
decodes per decision instead of one scoring, it lands in the dynamic delta so
it cannot live in the reusable spine, and it makes the reasoning trace the
*evidence* for the decision — which #9's rule against journalling prompt
contents would need a deliberate answer for, not an inherited one.

## Model selection is constrained by the tokenizer

A single-token action vocabulary is the preferred controller ABI: no candidate
evaluation, one base distribution, exactly comparable. It is **not** freely
available.

| model | obvious spellings | single-token encoding exists? |
| --- | --- | --- |
| Qwen3.5/3.6 | `hold scale rollback restart page` | yes, all five, lowercase |
| LFM2.5-2.6B (non-reasoning) | `rollback`/`restart` are 2 tokens | yes, but mixed case: `" Hold" " Scale" " rollback" " restart" " Page"` |
| Nemotron3-Nano-4B | neither variant works | only via **`" revert"` for `:rollback`** |

That last row is a trap the encoding probe walked straight into. No spelling of
*rollback* is single-token under Nemotron's tokenizer, so the search
substituted a **synonym**. That is precisely the silent semantic change #8
warned about, arrived at automatically rather than deliberately. A synonym may
well be the right choice — but it is a decision about what the controller
means, not a tokenizer detail, and the probe must not make it quietly.

## What this changes

* **#4** — the harness stands, the verdict does not. A scorer must be evaluated
  at high domain mass, counterbalanced for position, or the number measures the
  prompt. Both the confounded and corrected rows are reported.
* **#5** — the "intended scorer fails qualification" blocker no longer holds
  for the 2B. The remaining honest blockers are the wrong-and-confident rate
  (27% for the 2B counterbalanced) and the absence of a safe `:defer` path in
  any concrete workflow.

  **Retracted — see `docs/canary/SURFACE-FORM.md`.** The 2B never selects
  `:rollback` for any of the 37 fixtures: under raw logprob comparison the
  token's prior puts its best score below `:hold`'s worst. Eight fixtures
  expect it. A scorer that cannot emit one of its actions is unqualified
  regardless of top-1, and the standard calibration makes it worse. The
  blocker holds. The `chose` line above — `{:hold 13, :scale 10, :restart 11,
  :page 3}` — showed this all along.
* **#8** — extend the encoding contract: a chosen encoding must be a *spelling*
  of the action, and a synonym substitution must be surfaced as a semantic
  decision rather than accepted as a search result.

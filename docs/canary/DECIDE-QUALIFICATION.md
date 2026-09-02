# Closed-domain decisions: hardening and qualification

Follows `docs/canary/EMBEDDED-DECISION.md`, which proved the shape. This
records what happened when the shape was tested rather than demonstrated.

Branch `agent/onbox/decide-hardening`, from the reviewed canary tip
`e8f0f11fffcd36f76f5473df5d1d610a497e944d`.

jolt-llama coordinate: `c668dbdd8443af4004ab6fea281a18e9ea954621`
(v0 promotion gate passed), on clean pinned llama.cpp
`b81c99b479d4c24e5eeca10de99032ebd343ef8f`.

## The headline

**The mechanism is hardened. The model is unqualified, and by a wider margin
than the first canary suggested — because the first canary was measuring the
wrong thing.**

## What the first canary got wrong

`(take 1 (llama/tokenize m " ROLLBACK"))` guaranteed one token by truncation.
Under this tokenizer:

| action | tokens | n |
| --- | --- | ---: |
| ` HOLD` | `[17021]` | 1 |
| ` SCALE` | `[75446]` | 1 |
| ` ROLLBACK` | `[423 20875 15373]` | **3** |
| ` RESTART` | `[3476 21864]` | **2** |
| ` PAGE` | `[24247]` | 1 |

So two of five actions were scored by a **fragment** — token `423` standing in
for ROLLBACK. Every published score for those actions measured something else,
and the confident margins of 1.58–1.68 came partly from that.

The fix is a deliberate encoding, not a shorter read. `dev/canary/encoding_probe.clj`
searched the spellings; lowercase is uniformly single-token and distinct:

    hold 3222   scale 5281   rollback 58377   restart 16526   page 2081

With real encodings the model's margins drop to 0.47 and below — it is
measurably *less* confident than it appeared, which is the more honest number.

## Hardening (#6, #7, #8, #9)

| Issue | Was | Is |
| --- | --- | --- |
| #6 | a scorer answering for 1 of 5 collapsed the domain and returned a confident `:act` | every authorized candidate needs exactly one finite score; distinct `:reason/no-scores`, `:reason/incomplete-scores`, `:reason/invalid-scores`; extras fail closed |
| #7 | `(or legal? (constantly true))` — no rule meant all legal | `decide` takes a `DecisionDomain` from `authorize`; there is no arity accepting a bare vocabulary; `all-legal` is a named fixture |
| #8 | encodings truncated to one token | `verify-encodings` tokenizes the whole encoding and rejects zero-token, multi-token and aliased actions |
| #9 | a descriptive `:model-id` string | allowlisted provenance: run/branch/turn, domain and legality revisions, policy revision and effective values, scorer binding id, model **sha256** |

An explicit `nil` counts as *missing* (the scorer answered with nothing); a
string or `NaN` counts as *invalid* (it answered badly). A training pipeline
needs to tell absent evidence from corrupt evidence.

The audit now keeps **every** authorized candidate with a `:scoring-status`,
plus the ones policy rejected before scoring. Previously an unscored candidate
vanished because `rank` had filtered it.

`durable` preserves qualified keywords across the journal write — `data.json`
serialises `:reason/incomplete-scores` as `"incomplete-scores"`, dropping the
half that says which vocabulary it belongs to.

## Qualification (#4)

37 frozen fixtures, 7 causal groups, mechanical labels from explicit state
fields. Matched **controls** whose label must not move are scored separately
from **counterfactual** siblings whose label must.

| scorer | top-1 | pairwise | counterfactual | control | defer | wrong+confident |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| C rule (labelling fn) | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% | 0.0% |
| B majority (`:hold`) | 37.8% | 37.8% | 38.5% | 41.2% | 0.0% | 62.2% |
| A random (seeded) | 24.3% | 46.6% | 7.7% | 41.2% | 97.3% | 2.7% |
| B constant (`:page`) | 13.5% | 13.5% | 7.7% | 17.6% | 0.0% | 86.5% |
| **D Qwen3.5-0.8B** | **13.5%** | 51.4% | 15.4% | 11.8% | **100.0%** | **0.0%** |

Baseline C scores 100% by construction — it *is* the labelling function — and
exists to prove the harness measures what it claims.

**Qwen3.5-0.8B is worse than answering `:hold` every time.** It chose `:scale`
for 36 of 37 fixtures regardless of state, so its 51.4% pairwise figure is
close to chance rather than partial signal.

The integrity test caught four rows I had authored as controls or
counterfactuals which the rule labelled the other way; `:role` is now derived
from whether the label actually moved.

## #3 — the runtime guard decision: **deferred, with evidence**

Outcome (B) from the issue's own list: *evidence does not yet justify a runtime
guard*, for a specific and measurable reason.

**The existing margin guard already caught this model completely.** Defer rate
100%, wrong-and-confident 0.0%. Every margin it produced (p50 0.173, max 0.471)
fell below the 0.5 floor in `gates.edn`, so not one bad decision was acted on.

The model's low confidence *is* the signal, and the guard already reads it
correctly. Adding a second heuristic — a ranking-change threshold, an entropy
cut, a drift detector — would close the issue without being justified by a
single observation, and would have to be tuned against data that does not yet
exist. A perfectly acceptable result, per the issue: **runtime insensitivity
guard deferred; offline qualification is the correct control at this stage.**

What *would* justify revisiting it: a scorer that passes qualification and then
produces confident wrong answers. That is the failure the margin guard cannot
catch, and it is not the failure we have.

## #10 — exact-spine integration: **validated**

    spine tokenizes to    2793 tokens
    EXACT token boundary  2792   (a BPE merge costs one token)
    reused 2792 of 3488 tokens (80.0%), appended 696
    cold 4652 ms   restore 206 ms + suffix 970 ms = 1176 ms   speedup 3.96x
    max |delta| over the domain: 0.00000000
    ranking identical, same decision act/act, same selected

Restoring one spine's state under a different spine is refused with
`:state/prefix-mismatch`, asserted rather than assumed.

## #5 — production wiring: **deliberately not done**

The issue's own gate lists five preconditions. Four are met (#6, #7, #8, #9)
and one is not: **the intended scorer does not pass qualification.** It is not
marginal — it is below the majority-class baseline.

So `decide` stays a reachable standalone capability, wired into no turn
workflow. Wiring it would mean routing a production decision to a ranker that
is worse than a constant, protected only by a margin guard that would defer
every time — which is a more complicated way of doing nothing.

The blocker is recorded on the issue with these numbers.

## What runs where

* Ordinary suite: **no model, no native library, no jolt-llama.** 39 decide
  tests including a real SQLite round trip driven by a literal scorer.
* `:canary` alias only: `canary.embedded`, `canary.exact-spine`,
  `canary.encoding-probe`, and the `--model` row of `canary.decide-qualification`.

;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.session-test
  "Short-term memory: the live session tally, and its distillation into the
  long-term store.

  The supervisor's job is to notice what is going wrong and change it, and it
  had two blind spots this closes. It could see THAT a run was going badly and
  not WHERE — a branch losing a third of its turns to unparseable calls and one
  losing them to a failing test look identical at the outcome level, and want
  opposite repairs. And it could not tell whether its own last change helped."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.lexicon :as lexicon]
            [samizdat.session :as session]
            [samizdat.store.db :as db]
            [samizdat.store.knowledge :as knowledge]))

(use-fixtures :each (fn [f] (session/reset!) (f) (session/reset!)))

(deftest the-tally-counts-tools-by-outcome-and-mechanics-separately
  ;; Both axes matter. WHICH tool and HOW it went are different diagnoses, and
  ;; the harness's own failure modes (a fence that did not parse) are the ones
  ;; a supervisor is least able to infer from outcomes.
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (session/observe-turn! {:tool "eval" :category :failure :signals {}})
  (session/observe-turn! {:tool "shell" :category :success
                          :signals {:parse-error true :auto-repaired true}})
  (let [snap (session/snapshot)]
    (is (= 3 (:turns snap)))
    (is (= {:success 1 :failure 1} (get-in snap [:tools "eval"])))
    (is (= 1 (get-in snap [:signals :parse-error])))
    (is (= 1 (get-in snap [:signals :auto-repaired])))))

(deftest a-mark-turns-the-tally-into-an-experiment
  ;; The whole point. A supervisor marks when it intervenes, and the delta is
  ;; the evidence for whether the change helped — rather than a feeling.
  (dotimes [_ 4] (session/observe-turn! {:tool "eval" :category :failure :signals {}}))
  (session/mark! "before-fix")
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (let [d (session/since "before-fix")]
    (is (= 1 (:turns d)))
    (is (= {:success 1} (get-in d [:tools "eval"]))
        "only what changed — the four earlier failures are not the delta")
    (is (nil? (get-in d [:tools "eval" :failure]))
        "a report of everything that did NOT change is how a signal gets lost")))

(deftest an-unchanged-counter-is-absent-from-the-delta-not-zero
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (session/mark! "m")
  (is (nil? (session/since "m")) "nothing happened, so there is nothing to report")
  (is (nil? (session/since "never-marked"))))

(deftest re-marking-compares-against-the-most-recent-intervention
  ;; A supervisor marking each round wants the delta since its LAST change, not
  ;; since its first — otherwise every round looks like progress.
  (session/observe-turn! {:tool "eval" :category :failure :signals {}})
  (session/mark! "sup")
  (session/observe-turn! {:tool "eval" :category :failure :signals {}})
  (session/mark! "sup")
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (is (= {:success 1} (get-in (session/since "sup") [:tools "eval"]))))

(deftest findings-fire-on-thresholds-and-name-what-they-saw
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success
                                         :signals {:parse-error true}}))
  (let [fs (session/findings)
        kinds (set (map :kind fs))]
    (is (contains? kinds :calls-not-parsing))
    (is (every? #(seq (:detail %)) fs) "a finding says what it saw")
    (is (every? #(seq (:evidence %)) fs) "and shows the numbers behind it")))

(deftest a-healthy-session-produces-no-findings
  ;; Not an empty page of zeroes: a supervisor shown noise every turn will stop
  ;; reading the block.
  (dotimes [_ 10] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (is (empty? (session/findings))))

(deftest successes-are-reported-too
  ;; A supervisor shown only what is broken will keep changing things that work.
  (dotimes [_ 5] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (dotimes [_ 3] (session/observe! [:verify :green]))
  (is (some #(= :verification-working (:kind %)) (session/findings))))

(deftest the-block-is-empty-until-something-has-happened
  (is (nil? (session/render "m")))
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (is (str/includes? (session/render nil) "1 turns")))

(deftest an-unmeasured-change-is-reported-as-such-not-as-success
  ;; The case a supervisor most needs stated plainly, because the temptation is
  ;; to stack another change on an unmeasured one.
  ;;
  ;; This used to assert a bare `since the mark` delta. The experiment block
  ;; replaced it and subsumes it: it carries the same before/after numbers AND
  ;; what was changed and what it was expected to do, which is the difference
  ;; between a measurement and an experiment.
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "fresh" {:change "c" :hypothesis "h"})
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (let [block (session/render nil)]
    (is (str/includes? block "too early"))
    (is (str/includes? block "do not stack another change"))))

;; --- distillation -----------------------------------------------------------

(deftest a-finding-becomes-a-memory-once-not-once-per-run
  ;; The same pattern recurring is not new knowledge — it is the same
  ;; knowledge, confirmed. Recurrence should show up as a RECORD, not volume.
  (let [c (db/open! ":memory:")]
    (try
      (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success
                                             :signals {:parse-error true}}))
      (let [first-pass (knowledge/distill! c (session/findings) {:run-id "r1"})
            second-pass (knowledge/distill! c (session/findings) {:run-id "r2"})]
        (is (seq first-pass))
        (is (every? (complement :repeat?) first-pass))
        (is (every? :repeat? second-pass) "the second run confirms, it does not duplicate")
        (is (= (set (map :id first-pass)) (set (map :id second-pass))))
        (is (= 1 (count (knowledge/recent c 20)))))
      (finally (db/close c)))))

(deftest a-distilled-finding-is-episodic-not-a-rule
  ;; What a session measured is a thing that happened, not a rule. Promoting an
  ;; episode to a standing rule is a judgement, and judgement is the
  ;; supervisor's — it has `remember` for that.
  (let [c (db/open! ":memory:")]
    (try
      (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success
                                             :signals {:truncated true}}))
      (knowledge/distill! c (session/findings) {:run-id "r1"})
      (is (every? #(= "episodic" (:kind %)) (knowledge/recent c 20)))
      (finally (db/close c)))))

;; --- selection: fitness, experiments, verdicts -------------------------------

(deftest fitness-scores-a-tally-per-turn-so-runs-of-different-lengths-compare
  (dotimes [_ 4] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (let [good (session/fitness)]
    (session/reset!)
    (dotimes [_ 20] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
    (is (< (Math/abs (- good (session/fitness))) 1e-9)
        "a longer run of the same quality scores the same — otherwise a long
         bad run would outscore a short good one")))

(deftest an-empty-tally-has-no-fitness-rather-than-a-neutral-one
  ;; No turns is the ABSENCE of a measurement. A supervisor shown 0.0 would
  ;; read it as neutral and act on it.
  (is (nil? (session/fitness))))

(deftest the-weights-encode-the-judgements-worth-defending
  (let [w (:weights (lexicon/policy :fitness))]
    (is (< (:tool-mechanics w) (:tool-failure w))
        "a malformed call is worse than a failed one: the failure TESTED
         something and came back negative, the malformed call produced no
         evidence and cost the same turn")
    (is (< (:verify-skipped w) (:verify-red w))
        "a red test is the gate WORKING; skipped means it was asked, could not
         answer, and the work shipped anyway")
    (is (< (:parse-error w) (:truncated w))
        "truncation is lighter and separate — the repair is more tokens, not
         more steering, so it should push a different lever")))

(deftest an-experiment-binds-a-change-to-what-happened-after-it
  ;; The selection step. Variation and measurement both existed; what was
  ;; missing was the binding, without which a change is made and never judged.
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :mechanics
                                         :signals {:parse-error true}}))
  (session/experiment! "widen" {:change "budget 50k -> 80k"
                                :hypothesis "fewer calls will fail to parse"})
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (let [v (session/verdict "widen")]
    (is (= :better (:verdict v)))
    (is (< (:before v) (:after v)))
    (is (= "budget 50k -> 80k" (:change v)))
    (is (seq (:hypothesis v)) "a change with no stated expectation cannot be wrong")))

(deftest a-change-that-moved-nothing-is-unchanged-not-better
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "noop" {:change "reworded a prompt" :hypothesis "nothing"})
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (is (= :unchanged (:verdict (session/verdict "noop")))))

(deftest a-change-that-made-things-worse-says-so
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "bad" {:change "narrowed the budget" :hypothesis "cheaper"})
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :mechanics
                                         :signals {:parse-error true}}))
  (is (= :worse (:verdict (session/verdict "bad")))))

(deftest too-early-is-a-real-verdict
  ;; A supervisor that reads three turns of noise as a result will keep
  ;; changing things on the strength of nothing.
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "fresh" {:change "x" :hypothesis "y"})
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (is (= :too-early (:verdict (session/verdict "fresh")))))

(deftest the-block-tells-the-supervisor-what-each-verdict-obliges
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "noop" {:change "c" :hypothesis "h"})
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (let [block (session/render nil)]
    (is (str/includes? block "fitness"))
    (is (str/includes? block "noop"))
    (is (str/includes? block "revert")
        "an unjustified change is debt, and the block has to say so")))

(deftest a-losing-change-keeps-being-raised-until-it-is-settled
  ;; The one thing a supervisor under selection pressure must not be able to
  ;; quietly skip. A change measured and found wanting, then left in place, is
  ;; worse than one nobody measured: the loop carries a modification the
  ;; evidence says is not helping, and the next supervisor inherits it with no
  ;; sign it was ever questioned.
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "narrow" {:change "width 5 -> 2" :hypothesis "cheaper"})
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :mechanics
                                         :signals {:parse-error true}}))
  (is (= :worse (:verdict (session/verdict "narrow"))))
  (is (= 1 (count (session/unsettled-losses))))
  (is (str/includes? (session/render nil) "have not acted on them"))

  (testing "settling it stops the nag — a block that repeats itself forever
            trains a reader to skip it, which is the opposite of the point"
    (session/reverted! "narrow" false)
    (is (empty? (session/unsettled-losses)))
    (is (str/includes? (session/render nil) "(reverted)"))))

(deftest a-winning-change-is-never-nagged-about
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :mechanics
                                         :signals {:parse-error true}}))
  (session/experiment! "fix" {:change "widened the budget" :hypothesis "fewer failures"})
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (is (= :better (:verdict (session/verdict "fix"))))
  (is (empty? (session/unsettled-losses))))

(deftest an-unfinished-experiment-is-not-nagged-about-either
  ;; `too-early` has concluded nothing, and demanding action on it would push
  ;; the supervisor to decide before the evidence exists.
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "fresh" {:change "c" :hypothesis "h"})
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (is (empty? (session/unsettled-losses))))

(deftest culling-a-branch-that-held-evidence-is-a-sharpening-failure
  ;; papers/2608.17981v1 §4.5.4, after Yue et al. on pass@k. A beam fails two
  ;; ways and they want opposite fixes: no branch ever held the answer
  ;; (expansion — widen, diversify), or one did and the harness threw it away
  ;; (sharpening — fix the rubric and the cull thresholds). End-to-end success
  ;; cannot tell them apart, and widening a beam that is losing to selection
  ;; buys nothing but cost.
  (dotimes [_ 10] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (dotimes [_ 3] (session/observe! [:beam :culled-with-evidence]))
  (let [f (first (filter #(= :selection-losing-evidence (:kind %)) (session/findings)))]
    (is (some? f) "a branch culled while holding confirmed artifacts is the signal")
    (is (str/includes? (:detail f) "SHARPENING"))
    (is (str/includes? (:detail f) "widening the beam will not help")
        "the finding has to name the fix it rules OUT, or it will be read as
         an argument for a wider beam")))

(deftest a-verdict-becomes-a-lever-fact-that-outlives-the-session
  ;; The heredity of selection. Without it a lever that was tried and made
  ;; things worse is forgotten by the next session, which is free to try it
  ;; again — variation and measurement without inheritance is thrashing with
  ;; statistics.
  (let [c (db/open! ":memory:")]
    (try
      (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
      (session/experiment! "narrow" {:change "beam width 5 -> 2" :hypothesis "cheaper"})
      (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :mechanics
                                             :signals {:parse-error true}}))
      (let [written (knowledge/distill-verdicts! c (session/experiments) {:run-id "r1"})]
        (is (= 1 (count written)))
        (is (= :worse (:verdict (first written))))
        (let [row (knowledge/get-by-id c (:id (first written)))]
          (is (= "procedural" (:kind row))
              "a verdict is a fact about a LEVER and holds beyond the run that
               found it — unlike a session finding, which is an episode")
          (is (= 1 (:failure_count row)) "the verdict IS the record")))

      (testing "trying the same lever again confirms rather than duplicates"
        (session/reset!)
        (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
        (session/experiment! "narrow-again" {:change "beam width 5 -> 2"
                                             :hypothesis "maybe this time"})
        (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :mechanics
                                               :signals {:parse-error true}}))
        (knowledge/distill-verdicts! c (session/experiments) {:run-id "r2"})
        (let [rows (filter #(= "procedural" (:kind %)) (knowledge/recent c 20))]
          (is (= 1 (count rows)) "one lever, one memory")
          (is (= 2 (:failure_count (first rows)))
              "and a lever that keeps failing sinks in the ranking on its own")))
      (finally (db/close c)))))

(deftest an-unfinished-experiment-teaches-nothing-and-is-not-written
  (let [c (db/open! ":memory:")]
    (try
      (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
      (session/experiment! "fresh" {:change "c" :hypothesis "h"})
      (session/observe-turn! {:tool "eval" :category :success :signals {}})
      (is (empty? (knowledge/distill-verdicts! c (session/experiments) {:run-id "r1"}))
          "recording it would teach the next session that the lever was tested
           when it was not")
      (finally (db/close c)))))

(deftest only-one-change-may-be-in-flight-and-that-is-enforced
  ;; The supervisor prompt has always said "one change per round". backpass's
  ;; VISION puts the general principle sharply: a rule the model can decline is
  ;; not a rule. Two changes measured over the same interval tell you nothing
  ;; about either, so letting them stack quietly destroys the measurement the
  ;; whole mechanism exists for.
  (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
  (session/experiment! "one" {:change "a" :hypothesis "h"})
  (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :mechanics
                                         :signals {:parse-error true}}))
  (is (thrown? clojure.lang.ExceptionInfo
               (session/experiment! "two" {:change "b" :hypothesis "h"})))
  (testing "settling the first frees the slot — the cap paces changes, it does
            not forbid them"
    (session/reverted! "one" false)
    (session/experiment! "two" {:change "b" :hypothesis "h"})
    (is (some #(= "two" (:name %)) (session/experiments)))))

(deftest an-unfinished-experiment-does-not-hold-the-slot
  ;; `too-early` has concluded nothing. Blocking on it would leave the
  ;; supervisor unable to act for as long as the run is short.
  (session/experiment! "fresh" {:change "a" :hypothesis "h"})
  (session/observe-turn! {:tool "eval" :category :success :signals {}})
  (session/experiment! "another" {:change "b" :hypothesis "h"})
  (is (some #(= "another" (:name %)) (session/experiments))
      "blocking on an unfinished experiment would leave the supervisor unable
       to act for as long as the run is short"))

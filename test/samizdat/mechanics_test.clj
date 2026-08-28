;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mechanics-test
  "The no-call guards (karamazov-068): a branch that produces no usable tool
  call, and the particular way a long-running one learns to produce none.

  The live failure these pin: a supervisor with 119 turns of history, whose
  context was by then almost entirely `[unloaded]` digests standing in for
  its own past turns, started emitting digests instead of calls — eight in a
  row, answered each time with a generic 'emit a tool call' that produced
  another digest, with nothing able to stop it because no call ever reached
  dispatch."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.state :as state]
            [samizdat.agent.telemetry :as telemetry]
            [samizdat.agent.supervisor :as supervisor]
            [samizdat.llm.message :as message]
            [samizdat.store.journal :as journal]))

;; --- the marker announces itself before it says anything else ---------------

(deftest the-unloaded-marker-leads-the-line
  (let [msgs [{:role "system" :content "sys"}
              {:role "user" :content "problem"}
              {:role "assistant" :content (apply str (repeat 400 "a"))}
              {:role "user" :content (apply str (repeat 400 "b")) :turn 1}
              {:role "assistant" :content "recent" :turn 9}
              {:role "user" :content "newest" :turn 9}]
        out (message/compact msgs [{:turn 1 :tool "grep" :category :neutral}]
                             {:keep-pairs 1 :threshold-chars 10})
        compacted (filter message/unloaded? (map :content out))]
    (is (seq compacted) "something was unloaded at this threshold")
    (doseq [c compacted]
      (is (str/starts-with? c "[unloaded]")
          (str "the marker must lead, so a model reading left to right sees "
               "harness bookkeeping before it sees imitable content: " c)))))

(deftest unloaded?-recognizes-the-harness-marker
  (is (message/unloaded? "[unloaded] t69 cell → neutral"))
  (is (message/unloaded? "```tool-call\n<tool_call> [unloaded]")
      "the trailing form too — a branch compacted before this change carries it")
  (is (not (message/unloaded? "a perfectly ordinary reply")))
  (is (not (message/unloaded? nil))))

;; --- the imitation gets its own answer, and its own prefill treatment -------

(defn- no-call
  "Drive no-call-step over a stubbed journal, returning the branch."
  [said]
  (with-redefs [journal/record-turn! (fn [& _] nil)]
    (aloop/no-call-step {} (state/new-branch {:id "S0" :problem "p"}) 5
                        {:parsed nil :signals {} :said said :response {}})))

(deftest an-imitated-digest-gets-a-specific-complaint
  (let [b (no-call "```tool-call\n<tool_call> [unloaded]")
        msg (str (:content (last (:messages b))))]
    (is (str/includes? msg "bookkeeping")
        "named for what it is — told merely to emit a tool call, the model
         emits another digest")
    (is (not (str/includes? msg "No ```tool-call block"))
        "not the generic complaint")))

(deftest an-imitation-turn-is-not-prefilled
  ;; The prefill is half the trap: opening inside a fence, with a context of
  ;; digest lines, the likeliest continuation is another digest.
  (is (nil? (:prefill (no-call "[unloaded] t70 cell → neutral")))
      "a clean slate to reason in")
  (is (= "```tool-call\n" (:prefill (no-call "I think I should probably...")))
      "an ordinary no-call still has prose withheld — that is what works"))

(deftest a-truncated-reply-is-still-a-truncation
  ;; Truncation outranks imitation: a reply cut off mid-digest wants more
  ;; tokens, not a lecture about bookkeeping.
  (with-redefs [journal/record-turn! (fn [& _] nil)]
    (let [b (aloop/no-call-step {} (state/new-branch {:id "S0" :problem "p"}) 5
                                {:parsed nil :signals {:truncated true}
                                 :said "[unloaded] t1 grep" :response {}})
          msg (str (:content (last (:messages b))))]
      (is (str/includes? msg "token limit")))))

;; --- the streak rung: something bites wherever the arbiter runs -------------

(deftest the-mechanics-streak-gate-forces-an-honest-give-up
  (let [g (first (filter #(= :mechanics-streak (:gate %)) (gates/gates)))
        th (gates/threshold :mechanics-streak-threshold)
        fires? (fn [n] (boolean ((:when g) {:branch (assoc (state/new-branch
                                                            {:id "S0" :problem "p"})
                                                           :consecutive-mechanics-failures n)})))]
    (is (some? g) "the gate exists in gates.edn")
    (is (= "give_up" (:tool g)))
    (is (some? (gates/threshold (:budget g))))
    (is (fires? th))
    (is (not (fires? (dec th))))
    (testing "it outranks storm — a branch making no calls at all is further
              gone than one repeating a call, and the storm window cannot see
              it because nothing reaches dispatch"
      (let [storm (first (filter #(= :storm (:gate %)) (gates/gates)))]
        (is (< (:priority g) (:priority storm)))))))

(deftest the-streak-counter-clears-on-any-well-formed-call
  ;; The rung must not fire on a branch that recovered: record-outcome clears
  ;; the mechanics tally on any category that reached a tool.
  (let [b (-> (state/new-branch {:id "S0" :problem "p"})
              (state/record-outcome {:category :mechanics :progress? false})
              (state/record-outcome {:category :mechanics :progress? false})
              (state/record-outcome {:category :neutral :progress? false}))]
    (is (zero? (:consecutive-mechanics-failures b)))))

;; --- karamazov-3y5 -----------------------------------------------------------
;; Context pressure was measured only by hitting the wall: the overflow came
;; back from the provider, and only THEN was the budget squeezed — after the
;; failed request and its backoff were already spent.

(deftest context-pressure-is-graded-against-the-operating-ceiling
  (let [policy {:operating-ceiling 1000 :advisory 0.75 :urgent 0.90}]
    (is (nil? (state/context-pressure 500 policy)) "well under: nothing")
    (is (nil? (state/context-pressure 749 policy)) "just under advisory")
    (is (= :advisory (state/context-pressure 750 policy)))
    (is (= :advisory (state/context-pressure 899 policy)))
    (is (= :urgent (state/context-pressure 900 policy)))
    (is (= :urgent (state/context-pressure 1000 policy)) "at the ceiling, not over")
    (is (= :over (state/context-pressure 1001 policy)))
    (testing "no measurement is not pressure"
      (is (nil? (state/context-pressure nil policy)))
      (is (nil? (state/context-pressure 0 policy))))
    (testing "no ceiling configured means the signal is off, not always-on"
      (is (nil? (state/context-pressure 999999 {:operating-ceiling 0
                                                :advisory 0.75 :urgent 0.9}))))))

;; --- the no-edits nudge ------------------------------------------------------
;; Run c99c6fd6 spent turns 45-50 in the REPL — six evals around one arity bug
;; in a function it had never written down — while write_file sat at 3 from
;; turn 25. The studying gate could not see it: `eval` is work, the branch was
;; not idle, and studying's threshold is 10. This is the narrower question,
;; asked sooner: you are busy, and none of it is landing on disk.

(defn- turns-of [& tools] (mapv (fn [t] {:tool t}) tools))

(deftest no-edits-fires-only-once-the-branch-has-written-something
  (let [vocab (gates/tool-vocab :file-write)
        n (gates/threshold :no-edit-turns)
        fires? (fn [turns] (supervisor/over-studying? vocab turns n))]
    (testing "opening exploration is never nagged — nothing written yet"
      (is (not (fires? (apply turns-of (repeat 12 "eval")))))
      (is (not (fires? (apply turns-of (repeat 12 "read_file"))))))
    (testing "fires once it HAS written and then stopped for the threshold"
      (is (fires? (apply turns-of "write_file" (repeat n "eval")))))
    (testing "quiet while it is still writing"
      (is (not (fires? (turns-of "write_file" "eval" "write_file" "eval")))))
    (testing "under the threshold is not yet a stall"
      (is (not (fires? (apply turns-of "write_file" (repeat (dec n) "eval"))))))
    (testing "patch counts as writing — it is a file tool like the others"
      (is (not (fires? (apply turns-of "write_file" "eval" "patch"
                              (repeat (dec n) "eval")))))
      (is (fires? (apply turns-of "patch" (repeat n "eval")))))))

(deftest the-no-edits-gate-is-declared-and-settleable
  (let [g (first (filter #(= :no-edits (:gate %)) (gates/gates)))]
    (is (some? g) "the gate exists in gates.edn")
    (is (= :max-no-edit-nudges (:budget g)) "and is bounded, so it cannot nag forever")
    (is (some? (:prediction g)) "it declares a prediction, like every gate")
    (is (= #{"write_file" "edit_file" "patch"}
           (get (gates/tool-vocab :settle-called) :no-edits))
        "settled by the thing it asks for: a file actually written")))

;; --- gate health, per branch -------------------------------------------------
;; Run ace34d83: :no-edits fired 6 times and was ignored 3 of them — every one
;; of those by T0, which had stalled. S0 got the same nudge and complied. Rolled
;; up per GATE across the run, met > 0, so the gate read as working and the
;; watch never raised :gate-ignored. The branch that was actually stuck was
;; invisible, and the supervisor's digest carried no gate health at all.

(deftest gate-health-is-reported-per-branch
  (let [rows [{:gate "no-edits" :branch_id "T0" :outcome "unmet"}
              {:gate "no-edits" :branch_id "T0" :outcome "unmet"}
              {:gate "no-edits" :branch_id "T0" :outcome "unmet"}
              {:gate "no-edits" :branch_id "S0" :outcome "met"}
              {:gate "studying" :branch_id "T0" :outcome "unmet"}
              {:gate "milestone" :branch_id "S0" :outcome "met-late"}]
        h (telemetry/gate-health rows)]
    (testing "a gate ignored by one branch is not hidden by another obeying it"
      (is (= {:fired 3 :met 0 :unmet 3} (select-keys (get h ["T0" "no-edits"])
                                                     [:fired :met :unmet])))
      (is (= {:fired 1 :met 1 :unmet 0} (select-keys (get h ["S0" "no-edits"])
                                                     [:fired :met :unmet]))))
    (testing "met-late counts as met — the advice worked, the window was wrong"
      (is (= 1 (:met (get h ["S0" "milestone"])))))))

(deftest the-digest-names-a-gate-a-branch-is-ignoring
  (let [rows (concat (repeat 3 {:gate "no-edits" :branch_id "T0" :outcome "unmet"})
                     [{:gate "no-edits" :branch_id "S0" :outcome "met"}])
        line (telemetry/gate-lines (telemetry/gate-health rows) 3)]
    (is (string? line))
    (is (re-find #"T0" line) "the branch that is ignoring it is named")
    (is (re-find #"no-edits" line))
    (is (not (re-find #"S0" line))
        "a branch that obeyed the same gate is not reported as ignoring it"))
  (testing "nothing to say when every gate is being obeyed"
    (is (nil? (telemetry/gate-lines
               (telemetry/gate-health [{:gate "g" :branch_id "B" :outcome "met"}])
               3))))
  (testing "under the firing floor is not yet evidence"
    (is (nil? (telemetry/gate-lines
               (telemetry/gate-health [{:gate "g" :branch_id "B" :outcome "unmet"}])
               3)))))

;; --- karamazov-gez: a budget bounds the EPISODE, not the run -----------------
;; Run bd56a286: :no-edits fired 3 times (its whole budget), was obeyed once,
;; and then the branch went 143 turns without writing a file with the gate
;; permanently silent. The bound exists so a nudge cannot become the thing the
;; model answers instead of the work — that reasoning is about one episode of
;; nagging, and it was implemented as a bound on the run.

(deftest a-gate-budget-re-arms-when-its-prediction-is-met
  (let [g {:gate :no-edits :budget :max-no-edit-nudges}
        cap (gates/threshold :max-no-edit-nudges)
        hist (fn [& es] {:gate-history (vec es)})
        fire (fn [t] {:gate :no-edits :turn t})
        met  (fn [t] {:gate :no-edits :turn t :settled :met})]
    (testing "under the cap it may still fire"
      (is (not (gates/budget-exceeded? g (apply hist (map fire (range 1 cap)))))))
    (testing "at the cap it is spent"
      (is (gates/budget-exceeded? g (apply hist (map fire (range 1 (inc cap)))))))
    (testing "a MET settlement ends the episode and re-arms the budget"
      (is (not (gates/budget-exceeded?
                g (apply hist (concat (map fire (range 1 (inc cap)))
                                      [(met (+ cap 1))]))))
          "the stall ended; a later stall gets its own budget"))
    (testing "and the new episode is bounded again"
      (is (gates/budget-exceeded?
           g (apply hist (concat (map fire (range 1 (inc cap)))
                                 [(met 100)]
                                 (map fire (range 101 (+ 101 cap))))))
          "re-arming must not mean unlimited"))
    (testing "another gate's settlement does not re-arm this one"
      (is (gates/budget-exceeded?
           g (apply hist (concat (map fire (range 1 (inc cap)))
                                 [{:gate :studying :turn 50 :settled :met}])))))))

;; --- the REPL session contract ----------------------------------------------
;; Run bd56a286 is the case this exists for. T0 spent 238 turns in the REPL
;; hunting a bug that was in its own tests; T1 read for 316 turns and wrote
;; nothing. Both were reachable only by nudges, and nudges are 0-for-6
;; (karamazov-qic). The answer is not a louder nudge, it is a CONTRACT: say
;; which files you are going to change before you start exploring, and land
;; them before you finish. Declaring a file is a HYPOTHESIS about where the
;; problem is, which is exactly what T0 never formed (karamazov-70b).

(deftest a-repl-session-declares-before-it-explores
  (testing "a branch with no plan has not entered a session"
    (is (nil? (state/plan {})))
    (is (not (state/planned? {}))))
  (testing "declaring records the files and the tests"
    (let [b (state/declare-plan {} {:files ["src/fps/level.clj"]
                                    :tests ["test/fps/level_test.clj"]
                                    :goal "fix wall collision"})]
      (is (state/planned? b))
      (is (= "fix wall collision" (:goal (state/plan b))))
      ;; Tests are part of what must LAND. A declared test nobody writes is
      ;; exactly the gap this contract closes, so :files is everything owed
      ;; and :tests is the subset that are tests.
      (is (= ["src/fps/level.clj" "test/fps/level_test.clj"]
             (:files (state/plan b))))
      (is (= ["test/fps/level_test.clj"] (:tests (state/plan b))))
      (is (= 2 (count (state/unwritten b))))))
  (testing "a plan with no files is not a plan — the point is committing to a file"
    (is (not (state/planned? (state/declare-plan {} {:files [] :goal "poke about"}))))
    (is (not (state/planned? (state/declare-plan {} {:goal "poke about"}))))))

(deftest a-session-is-not-done-until-the-declared-files-are-written
  (let [b (state/declare-plan {} {:files ["src/a.clj" "test/a_test.clj"]})]
    (testing "nothing written yet: both outstanding"
      (is (= #{"src/a.clj" "test/a_test.clj"} (set (state/unwritten b)))))
    (testing "writing one leaves the other"
      (let [b (state/note-write b "src/a.clj")]
        (is (= ["test/a_test.clj"] (state/unwritten b)))))
    (testing "writing both closes the session"
      (let [b (-> b (state/note-write "src/a.clj") (state/note-write "test/a_test.clj"))]
        (is (empty? (state/unwritten b)))))
    (testing "a write nobody declared does not discharge a declared file"
      (let [b (state/note-write b "src/unrelated.clj")]
        (is (= 2 (count (state/unwritten b))))))
    (testing "paths are compared leniently — ./src/a.clj is src/a.clj"
      (let [b (state/note-write b "./src/a.clj")]
        (is (= ["test/a_test.clj"] (state/unwritten b)))))))

(deftest re-declaring-replaces-the-plan-rather-than-accreting
  ;; A model that learns the bug is elsewhere must be able to say so. The
  ;; contract is "commit to a hypothesis", not "never change your mind".
  (let [b (-> (state/declare-plan {} {:files ["src/a.clj"]})
              (state/note-write "src/a.clj")
              (state/declare-plan {:files ["test/a_test.clj"]}))]
    (is (= ["test/a_test.clj"] (:files (state/plan b))))
    (is (= ["test/a_test.clj"] (state/unwritten b))
        "the new plan's files are outstanding again")))

;; --- the plan is a better arming signal than the write history --------------
;; :no-edits armed on "this branch has written before", which is a heuristic
;; groping for "is it supposed to be writing by now". A branch that has never
;; written was unreachable — T1 of run bd56a286 read for 316 turns and T0 of
;; run 90674c19 declared a plan and then explored 32 turns, both with the gate
;; unable to say anything (karamazov-gez). A DECLARED, UNLANDED PLAN answers
;; the question exactly: it is supposed to be writing, and it is not.

(deftest a-stale-plan-is-the-arming-signal
  (let [turns (fn [& ts] (mapv (fn [t] {:tool t}) ts))
        b (fn [plan-files & ts]
            (assoc (state/declare-plan {} {:files plan-files})
                   :turns (vec (apply turns ts))))
        vocab (gates/tool-vocab :file-write)
        n 4]
    (testing "no plan, no arming — a branch orienting is not stalled"
      (is (not (state/plan-stale? {:turns (apply turns (repeat 10 "eval"))} vocab n))))
    (testing "a plan whose files are all written is not stale"
      (let [x (-> (state/declare-plan {} {:files ["a.clj"]})
                  (state/note-write "a.clj")
                  (assoc :turns (apply turns (repeat 10 "eval"))))]
        (is (not (state/plan-stale? x vocab n)))))
    (testing "declared and unwritten, with no file-write in the window: stale"
      (is (state/plan-stale? (apply b ["a.clj"] (repeat n "eval")) vocab n)))
    (testing "under the window it is not yet stale"
      (is (not (state/plan-stale? (apply b ["a.clj"] (repeat (dec n) "eval")) vocab n))))
    (testing "a recent write clears it even with files still owed"
      (is (not (state/plan-stale? (apply b ["a.clj" "b.clj"]
                                         (concat (repeat (dec n) "eval") ["write_file"]))
                                  vocab n))))
    (testing "it arms on a branch that has NEVER written — the hole this closes"
      (let [never (apply b ["a.clj"] (repeat 20 "read_file"))]
        (is (empty? (filter #{"write_file"} (map :tool (:turns never)))))
        (is (state/plan-stale? never vocab n))))))

(deftest the-no-edits-gate-arms-both-ways
  ;; Two ways to be not-writing, and the gate must reach both. It used to
  ;; reach only the second, which is why a branch that had never written was
  ;; invisible to it (karamazov-gez).
  (let [gate (first (filter #(= :no-edits (:gate %)) (gates/gates)))
        n (gates/threshold :no-edit-turns)
        turns (fn [t k] (vec (repeat k {:tool t})))
        fires? (fn [b] (boolean ((:when gate) {:branch b})))]
    (testing "a DECLARED, unlanded plan arms it even with no write in the branch's life"
      (let [b (assoc (state/declare-plan {:status :active} {:files ["src/a.clj"]})
                     :turns (turns "read_file" (inc n)))]
        (is (empty? (filter #{"write_file"} (map :tool (:turns b))))
            "precondition: this branch has never written")
        (is (fires? b))
        (is (str/includes? ((:message gate) {:branch b}) "src/a.clj")
            "and the nudge names the file it owes, so it is answerable")))
    (testing "landing the plan disarms it"
      (let [b (-> (state/declare-plan {:status :active} {:files ["src/a.clj"]})
                  (state/note-write "src/a.clj")
                  (assoc :turns (turns "eval" (inc n))))]
        (is (not (fires? b)))))
    (testing "the history rule still covers a branch working without a plan"
      (let [b {:status :active
               :turns (into [{:tool "write_file"}] (turns "eval" n))}]
        (is (fires? b))))
    (testing "opening exploration with neither plan nor history is still not nagged"
      (is (not (fires? {:status :active :turns (turns "read_file" 20)}))))))

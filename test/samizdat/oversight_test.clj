;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.oversight-test
  "The supervisor as a PARALLEL STREAM.

  The mechanism under test is deliberately ignorant of supervision: it runs
  some pass function on a cadence, against a budget, in a thread that cannot
  hurt the run it watches. What that pass DOES is a cell, because the harness's
  own policy about when to think and what to think about has to be something
  the agent can rewrite at runtime."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.agent.gates :as gates]
            [samizdat.cells :as cells]
            [samizdat.agent.oversight :as ov]))

;; --- when a pass is due -----------------------------------------------------

(deftest a-pass-is-due-on-cadence-and-on-a-signal
  (testing "not due before the cadence has elapsed"
    (is (not (ov/due? {:last-at 100 :passes 0} {:now 150 :every-ms 100}))))
  (testing "due once it has"
    (is (ov/due? {:last-at 100 :passes 0} {:now 200 :every-ms 100})))
  (testing "the FIRST pass is due immediately — a supervisor that waits out a
            full cadence before its first look is blind through exactly the
            opening stretch where a run picks its approach"
    (is (ov/due? {:last-at nil :passes 0} {:now 0 :every-ms 100})))
  (testing "a signal makes a pass due early: the stream exists to notice
            trouble while it is forming, not on the next tick"
    (is (ov/due? {:last-at 100 :passes 0} {:now 110 :every-ms 100 :signal? true}))))

(deftest the-stream-is-bounded
  ;; Every pass is a model call. A supervisor that reasons on every tick of a
  ;; 300-turn run costs more than the run it is supervising.
  (testing "under budget, passes continue"
    (is (ov/due? {:last-at 0 :passes 3} {:now 999 :every-ms 1 :budget 5})))
  (testing "at budget, nothing is due again — including on a signal, or the
            bound would be advisory"
    (is (not (ov/due? {:last-at 0 :passes 5} {:now 999 :every-ms 1 :budget 5})))
    (is (not (ov/due? {:last-at 0 :passes 5}
                      {:now 999 :every-ms 1 :budget 5 :signal? true})))))

;; --- the stream cannot hurt the run ----------------------------------------

(deftest a-throwing-pass-neither-stops-the-stream-nor-escapes-it
  ;; The whole point of an observer is that its failure costs the run nothing.
  ;; watch.clj learned this already; a reasoning stream fails in more ways.
  (let [calls (atom 0)
        pass (fn [_] (swap! calls inc) (throw (ex-info "boom" {})))
        st (atom {:passes 0})]
    (is (nil? (ov/pass! {} st pass)))
    (is (nil? (ov/pass! {} st pass)))
    (is (= 2 @calls) "it kept going after the first throw")
    (is (= 2 (:passes @st)) "a throwing pass still spends its budget — an
                             observer that fails for free retries forever")))

(deftest the-stream-carries-one-context-across-passes
  ;; ITS OWN MEMORY STREAM. run-role mints a fresh branch per call, so the
  ;; supervisor in feature.edn re-reads the run cold every revision and cannot
  ;; refer to what it concluded last time. A stream that cannot remember its
  ;; own last conclusion cannot tell a change it made from a change it only
  ;; considered.
  (let [seen (atom [])
        pass (fn [{:keys [carry]}] (swap! seen conj carry) (inc (or carry 0)))
        st (atom {:passes 0})]
    (ov/pass! {} st pass)
    (ov/pass! {} st pass)
    (ov/pass! {} st pass)
    (is (= [nil 1 2] @seen)
        "each pass sees what the previous one returned")))

(deftest stopping-is-idempotent-and-ends-the-thread
  (let [stop (ov/start! {:enabled? false} (fn [_] nil))]
    (is (fn? stop) "a disabled stream still returns a stop function, so the
                    caller's teardown never has to check")
    (is (nil? (stop)))
    (is (nil? (stop)) "called twice from a crash path and a finally")))

;; --- the behaviour layer ----------------------------------------------------
;; The mechanism above is domain-blind. These cover the cells, which decide
;; what a pass looks at and whether it is worth a model call at all.

(defn- worth-a-look? [& args]
  (cells/load-cells!)
  (apply @(ns-resolve 'cells.oversight 'worth-a-look?) args))

(deftest a-healthy-run-costs-nothing
  ;; The cheap path has to be the DEFAULT, or the stream costs more than the
  ;; run it watches. A run that is shipping has nothing to tune, and saying so
  ;; would spend a model call to say nothing.
  (let [floors {:unmet-floor 2 :idle-floor 25}]
    (is (not (worth-a-look? {:unmet-gates 0 :idle-turns 3 :errors nil} floors)))
    (is (not (worth-a-look? {:unmet-gates 1 :idle-turns 24 :errors nil} floors))
        "just under both floors is still quiet — one unmet gate is noise")))

(deftest the-three-signals-that-buy-a-model-call
  (let [floors {:unmet-floor 2 :idle-floor 25}]
    (testing "steering that is being ignored — the harness's own words failing,
              which is the supervisor's actual subject"
      (is (worth-a-look? {:unmet-gates 2 :idle-turns 0 :errors nil} floors)))
    (testing "a run producing nothing"
      (is (worth-a-look? {:unmet-gates 0 :idle-turns 25 :errors nil} floors)))
    (testing "a stage crashed — a harness bug the loop survived, which recurs
              on the next run if nobody looks"
      (is (worth-a-look? {:unmet-gates 0 :idle-turns 0 :errors [{:x 1}]} floors)))))

(deftest the-stalls-this-project-actually-had-would-all-have-woken-it
  ;; Regression against the record rather than against a number I chose. Every
  ;; run in this campaign that went quiet did so with a long idle stretch; if a
  ;; threshold change stops waking on these, it has gone wrong.
  (let [floors {:unmet-floor (gates/threshold :oversight-unmet-floor)
                :idle-floor (gates/threshold :oversight-idle-floor)}]
    (doseq [[run idle] [["bd56a286 T1" 316] ["c377260b revise" 148]
                        ["d304f539 T0" 87] ["986f33d8 T0 after its one write" 47]]]
      (is (worth-a-look? {:idle-turns idle} floors)
          (str run " stalled for " idle " turns and nothing looked at it")))))

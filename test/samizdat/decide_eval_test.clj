;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.decide-eval-test
  "Integrity of the FROZEN qualification fixtures.

  Runs with no model: it checks the artifact, not a scorer. A frozen eval whose
  fixtures quietly drift is worse than no eval, because every later comparison
  is against a moving target."
  (:require [clojure.test :refer [deftest is testing]]))

(def fixtures (read-string (slurp "resources/decide-eval/v0.edn")))

(def ^:private actions #{:hold :scale :rollback :restart :page})

(deftest the-frozen-fixture-set-is-well-formed
  (is (<= 20 (count fixtures) 50) "sized as a v0 mini-eval, per issue #4")
  (is (= (count fixtures) (count (distinct (map :id fixtures)))) "ids are unique")
  (is (every? #(contains? actions (:expected %)) fixtures))
  (is (every? #(contains? #{:pivot :control :counter} (:role %)) fixtures))
  (testing "every state carries every field the labelling rule reads"
    (doseq [{:keys [id state]} fixtures]
      (is (every? #(contains? state %)
                  [:needs-human? :deploy-age-min :p95-ms :budget-ms :err-rate
                   :restarts :saturation :cpu-pct :mem-pct])
          (str id)))))

(deftest every-action-is-exercised
  (testing "an eval that never asks for an action cannot detect failing to choose it"
    (is (= actions (set (map :expected fixtures))))
    (doseq [a actions]
      (is (<= 3 (count (filter #(= a (:expected %)) fixtures)))
          (str a " needs enough rows to be more than an accident")))))

(deftest counterfactual-groups-hold-together
  (testing "whole-family holdout: siblings must never be split across a boundary,
            so each group is a unit and must contain a real contrast"
    (doseq [[g rows] (group-by :group fixtures)]
      (is (<= 3 (count rows)) (str g " is too small to be a family"))
      (is (some #(= :pivot (:role %)) rows) (str g " has no pivot")))
    (testing "and at least one group per non-control family flips its label"
      (let [flipping (for [[g rows] (group-by :group fixtures)
                           :when (< 1 (count (distinct (map :expected rows))))]
                       g)]
        (is (<= 5 (count flipping))
            "most groups must contain a counterfactual, or the eval only
             measures invariance")))))

(deftest matched-controls-really-do-not-move
  (testing "a control shares its group's pivot label -- that is what makes it a
            control rather than another counterfactual"
    (doseq [[g rows] (group-by :group fixtures)]
      (let [pivot (first (filter #(= :pivot (:role %)) rows))
            controls (filter #(= :control (:role %)) rows)]
        (when (and pivot (seq controls))
          (doseq [c controls]
            (is (= (:expected pivot) (:expected c))
                (str g "/" (:id c) " is labelled :control but its label differs
                     from the pivot; it is a counterfactual"))))))))

(deftest counterfactual-rows-really-do-move
  (doseq [[g rows] (group-by :group fixtures)]
    (let [pivot (first (filter #(= :pivot (:role %)) rows))
          counters (filter #(= :counter (:role %)) rows)]
      (when (and pivot (seq counters))
        (doseq [c counters]
          (is (not= (:expected pivot) (:expected c))
              (str g "/" (:id c) " is labelled :counter but shares the pivot's
                   label; it is a control")))))))

(deftest the-majority-class-does-not-dominate
  (testing "a fixture set where one action is most of the rows would let a
            constant scorer look competent"
    (let [freqs (frequencies (map :expected fixtures))
          top (apply max (vals freqs))]
      (is (< (/ top (double (count fixtures))) 0.5)
          (str "majority class is " top "/" (count fixtures))))))

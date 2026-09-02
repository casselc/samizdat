;; samizdat - a self-hosting agentic harness
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

(ns samizdat.decide-test
  "Closed-domain decisions, tested with no model present.

  That is the point of the scorer seam: every branch below is reachable with a
  scorer that returns a literal map, so this namespace runs in the ordinary
  suite on a machine with no inference engine, no native library and no model
  weights. The one test that does need a real model is in the canary script,
  not here."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.cells :as cells]
            [samizdat.decide :as decide]
            [samizdat.manifests :as manifests]))

(def ^:private actions
  [{:id :hold     :tokens [1]}
   {:id :scale    :tokens [2]}
   {:id :rollback :tokens [3]}])

(defn- scorer-returning [scores]
  (fn [_ctx _cands] {:scores scores}))

(deftest illegal-domains-are-refused-with-a-reason
  (testing "each refusal names WHICH rule it broke, because the journal records it"
    (is (= :domain/empty (decide/legal-domain? [] {})))
    (is (= :domain/not-a-sequence (decide/legal-domain? nil {})))
    (is (= :domain/too-large (decide/legal-domain? actions {:max-candidates 2})))
    (is (= :domain/missing-id (decide/legal-domain? [{:tokens [1]}] {})))
    (is (= :domain/duplicate-id
           (decide/legal-domain? [{:id :a :tokens [1]} {:id :a :tokens [2]}] {})))
    (is (nil? (decide/legal-domain? actions {})))))

(deftest a-clear-winner-is-acted-on
  (let [r (decide/decide {:scorer (scorer-returning {:hold -0.2 :scale -3.0 :rollback -4.0})
                          :candidates actions
                          :policy {:min-margin 0.5}
                          :model-id "test"})]
    (is (= :act (:decision r)))
    (is (= :hold (:selected r)))
    (is (< 2.7 (:margin r) 2.9))
    (is (= 3 (:n-scored r)))))

(deftest a-near-tie-defers-rather-than-guessing
  (testing "a controller may decline; acting on a coin flip is the failure mode"
    (let [r (decide/decide {:scorer (scorer-returning {:hold -1.00 :scale -1.02 :rollback -4.0})
                            :candidates actions
                            :policy {:min-margin 0.5}
                            :model-id "test"})]
      (is (= :defer (:decision r)))
      (is (= :reason/below-margin (:reason r)))
      (is (nil? (:selected r)))
      (testing "and it still records what it would have picked, so the deferral is reviewable"
        (is (= :hold (:would-have-selected r)))))))

(deftest unequal-length-candidates-are-not-compared
  (testing "carried from the jolt-llama exactness measurement, not assumed"
    (let [mixed [{:id :hold :tokens [1]} {:id :roll-back-now :tokens [1 2 3 4]}]
          r (decide/decide {:scorer (scorer-returning {:hold -1.0 :roll-back-now -3.0})
                            :candidates mixed
                            :policy {:min-margin 0.1}
                            :model-id "test"})]
      (is (= :defer (:decision r)))
      (is (= :reason/not-comparable (:reason r)))))
  (testing "and equal-length candidates are"
    (is (decide/comparable? actions))
    (is (not (decide/comparable? [{:id :a :tokens [1]} {:id :b :tokens [1 2]}])))))

(deftest a-failing-scorer-defers-instead-of-killing-the-run
  (let [r (decide/decide {:scorer (fn [_ _] (throw (ex-info "engine died" {})))
                          :candidates actions
                          :policy {:min-margin 0.5}
                          :model-id "test"})]
    (is (= :defer (:decision r)))
    (is (= :reason/scorer-failed (:reason r)))))

(deftest an-illegal-domain-never-reaches-the-scorer
  (testing "refusing early is what keeps a malformed domain out of the model"
    (let [called (atom false)
          r (decide/decide {:scorer (fn [_ _] (reset! called true) {:scores {}})
                            :candidates []
                            :policy {}
                            :model-id "test"})]
      (is (false? @called))
      (is (= :defer (:decision r)))
      (is (= :domain/empty (:domain-check r))))))

(deftest the-ordering-is-total-and-reproducible
  (testing "equal scores break by id, so a replay journals the same order"
    (let [tied [{:id :b :tokens [1]} {:id :a :tokens [1]} {:id :c :tokens [1]}]
          once (decide/rank tied {:a -1.0 :b -1.0 :c -1.0})
          twice (decide/rank (reverse tied) {:a -1.0 :b -1.0 :c -1.0})]
      (is (= [:a :b :c] (mapv :id once)))
      (is (= (mapv :id once) (mapv :id twice))))))

(deftest the-journal-record-carries-no-machine-state
  (testing "pointers, blobs, logits, tokens and prompts must never be journalled"
    (let [r (decide/decide {:scorer (scorer-returning {:hold -0.2 :scale -3.0 :rollback -4.0})
                            :candidates (mapv #(assoc % :state ::blob :ptr 140234) actions)
                            :context "a long prompt that must not be recorded"
                            :policy {:min-margin 0.5}
                            :model-id "qwen35 0.8B Q4_0"})]
      (testing "even though the CANDIDATES carried them in"
        (is (nil? (decide/leaks? r))))
      (is (not (contains? (set (mapcat keys (:domain r))) :state)))
      (is (not (contains? (set (mapcat keys (:domain r))) :ptr)))
      (testing "and the model is identified by a descriptive string, not a handle"
        (is (string? (:model-id r))))))
  (testing "leaks? actually detects a leak, so the test above is not vacuous"
    (is (= #{:state} (decide/leaks? {:domain [{:id :a :state ::blob}]})))
    (is (= #{:logits} (decide/leaks? {:a {:b [{:logits [1 2 3]}]}})))))

(deftest the-record-is-enough-to-audit-without-rerunning
  (let [r (decide/decide {:scorer (scorer-returning {:hold -0.2 :scale -3.0 :rollback -4.0})
                          :candidates actions
                          :policy {:min-margin 0.5}
                          :model-id "qwen35 0.8B Q4_0"})]
    (testing "every offered option appears with its score, not only the winner"
      (is (= 3 (count (:domain r))))
      (is (every? :score (:domain r)))
      (is (= [0 1 2] (mapv :rank (:domain r)))))
    (testing "and the numbers a reader would check are all present"
      (is (= 3 (:n-offered r)))
      (is (some? (:margin r)))
      (is (some? (:reason r))))))


;; ------------------------------------------------- the canary manifest

(def ^:private manifest
  (delay (read-string (slurp "resources/manifests/decide.edn"))))

(deftest the-canary-manifest-compiles
  (cells/load-cells!)
  (is (some? (manifests/compile-definition @manifest))))

(deftest every-invariant-the-manifest-claims-is-actually-enforced
  (testing "a rule documented as enforced that nothing checks is the dangerous
            direction, so assert the derived constraint set is non-empty and
            that nothing is silently unenforced"
    (is (= 2 (count (manifests/enforced-constraints @manifest))))
    (is (empty? (manifests/unenforced-invariants @manifest)))))

(deftest journalling-before-acting-is-a-compile-error-not-a-convention
  (cells/load-cells!)
  (testing "reordering the workflow so it acts before recording is REFUSED"
    (let [bad (assoc @manifest :edges {:start :apply :apply :score :score :end})]
      (is (thrown? Throwable (manifests/compile-definition bad)))))
  (testing "and scoring a domain that was never established as legal is too"
    (let [bad (assoc @manifest
                     :cells {:score :decide/score :apply :decide/apply
                             :start :decide/domain}
                     :edges {:score :apply :apply :start :start :end})]
      (is (thrown? Throwable (manifests/compile-definition bad))))))

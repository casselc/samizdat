;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.decompose-test
  "The pure decompose-on-stuck core: the architect prompt and decision parsing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.decompose :as dec]))

(deftest architect-prompt-carries-the-evidence
  (let [p (dec/architect-prompt {:problem "gate the remember tool"
                                 :tests "(deftest ...)"}
                                {:attempts 3 :last-failure "AssertionError: expected refusal"
                                 :depth 0})]
    (is (str/includes? p "gate the remember tool"))
    (is (str/includes? p "AssertionError"))
    (is (str/includes? p "DECOMPOSE"))
    (is (str/includes? p "FRESH_APPROACH"))))

(deftest architect-prompt-forces-fresh-approach-at-the-depth-edge
  (let [p (dec/architect-prompt {:problem "x"} {:depth (dec dec/max-depth)})]
    (is (str/includes? p "MUST choose FRESH_APPROACH"))))

(deftest parse-decision-reads-a-decompose
  (let [d (dec/parse-decision
           "{\"decision\":\"decompose\",\"reason\":\"two jobs\",\"subtasks\":[
              {\"name\":\"detect-completion\",\"description\":\"match the content against completion words\"},
              {\"name\":\"gate-on-diff\",\"description\":\"refuse when the tree changed nothing\"}]}"
           0)]
    (is (= :decompose (:kind d)))
    (is (= ["detect-completion" "gate-on-diff"] (mapv :name (:subtasks d))))
    (is (every? :description (:subtasks d)))))

(deftest parse-decision-reads-a-fresh-approach
  (let [d (dec/parse-decision
           "{\"decision\":\"fresh_approach\",\"reason\":\"wrong strategy\",\"hint\":\"regex, not substring\"}")]
    (is (= :fresh-approach (:kind d)))
    (is (= "regex, not substring" (:hint d)))))

(deftest parse-decision-honours-the-depth-budget
  (testing "a decompose too deep degrades to a fresh approach, not a split"
    (let [d (dec/parse-decision
             "{\"decision\":\"decompose\",\"subtasks\":[{\"name\":\"a\",\"description\":\"x\"}]}"
             (dec dec/max-depth))]
      (is (= :fresh-approach (:kind d)) "no split at the depth edge"))))

(deftest parse-decision-degrades-a-subtaskless-decompose
  (let [d (dec/parse-decision "{\"decision\":\"decompose\",\"subtasks\":[]}" 0)]
    (is (= :fresh-approach (:kind d)))))

(deftest parse-decision-is-nil-on-junk
  (is (nil? (dec/parse-decision "no json here" 0)))
  (is (nil? (dec/parse-decision "" 0))))

(deftest parse-decision-ignores-prose-around-the-json
  (let [d (dec/parse-decision
           "Here is my call:\n{\"decision\":\"fresh_approach\",\"hint\":\"try recursion\"}\nDone.")]
    (is (= :fresh-approach (:kind d)))
    (is (= "try recursion" (:hint d)))))

;; --- the recursion: solve (decompose-on-stuck) over injected ops ---

(def ^:private seq-fan (fn [thunks] (mapv #(%) thunks)))

(deftest solve-lands-a-unit-that-passes-directly
  (let [r (dec/solve {:id "x" :problem "p"} 0
                     {:attempt (constantly {:passed? true :answer "done"})
                      :recover (fn [& _] (throw (ex-info "should not recover a passing unit" {})))
                      :fan seq-fan})]
    (is (= :landed (:status r)))
    (is (= "done" (:answer r)))))

(deftest solve-retries-once-with-a-fresh-approach-hint
  (let [r (dec/solve {:id "x" :problem "p"} 0
                     {:attempt (fn [node] (if (:hint node)
                                            {:passed? true :answer "hinted"}
                                            {:passed? false :failure "wrong strategy"}))
                      :recover (fn [& _] {:kind :fresh-approach :hint "try recursion"})
                      :fan seq-fan})]
    (is (= :landed (:status r)))
    (is (= "hinted" (:answer r)))))

(deftest solve-decomposes-a-stuck-unit-and-assembles-the-children
  (let [attempts (atom [])
        r (dec/solve {:id "root" :problem "big task"} 0
                     {:attempt (fn [node]
                                 (swap! attempts conj (:id node))
                                 (cond
                                   (:assembly node) {:passed? true :answer (str "assembled " (:id node))}
                                   (:parent node)   {:passed? true :answer (str "built " (:id node))}
                                   :else            {:passed? false :failure "too big"}))
                      :recover (fn [& _] {:kind :decompose
                                          :subtasks [{:name "a" :description "do a"}
                                                     {:name "b" :description "do b"}]})
                      :fan seq-fan})]
    (is (= :landed (:status r)) "the parent lands once its children + assembly pass")
    (is (= 2 (count (:children r))))
    (is (every? #(= :landed (:status %)) (:children r)))
    (is (contains? (set @attempts) "root/a") "each sub-unit was attempted")
    (is (contains? (set @attempts) "root/b"))))

(deftest solve-fails-hard-at-the-depth-budget
  (let [r (dec/solve {:id "x" :problem "p"} dec/max-depth
                     {:attempt (constantly {:passed? false :failure "nope"})
                      :recover (fn [& _] {:kind :decompose :subtasks [{:name "a" :description "x"}]})
                      :fan seq-fan})]
    (is (= :failed (:status r)))
    (is (str/includes? (:reason r) "depth"))))

(deftest solve-fails-when-a-sub-unit-cannot-land
  (let [r (dec/solve {:id "root" :problem "p"} 0
                     {:attempt (constantly {:passed? false :failure "stuck"})
                      :recover (fn [node _] (when-not (:parent node)   ; child: no recovery -> child fails
                                              {:kind :decompose :subtasks [{:name "a" :description "x"}]}))
                      :fan seq-fan})]
    (is (= :failed (:status r)))
    (is (str/includes? (:reason r) "sub-unit"))))

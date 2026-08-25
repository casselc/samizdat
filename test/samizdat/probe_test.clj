;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.probe-test
  "The probe cells (LR-2, LR-3) — the policy half, in resources.

  The mechanism is tested in samizdat.infer-test, from literal tapes. What is
  tested here is the POLICY: when the probe declines to spend, what it does
  with a winner, and that a probe never becomes a turn."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.state :as state]
            [samizdat.cells :as cells]
            [samizdat.workflow :as workflow]))

(use-fixtures :once (fn [f] (cells/load-cells!) (f)))

(defn- fenced [tool]
  (str "reasoning\n```tool-call\n{\"name\": \"" tool "\", \"args\": {}}\n```"))

(defn- stuck-branch [n]
  (-> (state/new-branch {:id "B1" :problem "p"
                         :messages [{:role "system" :content "s"}
                                    {:role "user" :content "p"}]})
      (assoc :consecutive-mechanics-failures n)))

;; --- the manifest ------------------------------------------------------------

(deftest the-probe-manifest-compiles-and-is-a-turn-the-beam-can-schedule
  (let [d (workflow/read-definition
           (slurp (clojure.java.io/resource "manifests/probe.edn")))]
    (is (some? (workflow/compile-loop (workflow/turn-manifest d)))
        "the per-turn slice compiles, so the beam can drive it")
    (is (workflow/iterating? d)
        "one pass is ONE turn — else the beam would run five whole jobs at once")
    (is (contains? (set (vals (:cells d))) :probe/next-move))
    (is (= (dissoc (:cells d) :probe)
           (:cells (workflow/read-definition
                    (slurp (clojure.java.io/resource "manifests/loop.edn")))))
        "identical to the factory loop but for the one node — a controlled comparison")))

(deftest the-probe-manifest-is-in-the-catalogue-the-supervisor-reads
  ;; A manifest the supervisor cannot see is one it can never select.
  (is (contains? (set (map :name (workflow/catalog nil))) "probe")))

;; --- the cell's policy -------------------------------------------------------

(deftest the-cell-is-registered-and-declares-its-effects
  (let [spec (cell/get-cell :probe/next-move)]
    (is (some? spec))
    (is (= #{:net :db} (set (cell/effects spec)))
        "undeclared effects would make the mutation protocol's soak unsafe")))

(deftest a-branch-that-is-not-stuck-is-not-probed
  ;; The trigger is the whole cost control: a branch emitting well-formed calls
  ;; needs no help, and probing it is pure spend.
  (let [handler (:handler (cell/get-cell :probe/next-move))
        trigger (:on-mechanics-failures (gates/threshold :probe))
        data {:branch (stuck-branch (dec trigger)) :turn 3}
        out (handler {} data)]
    (is (= data out) "untouched — not even a :probe key, so nothing was spent")))

(deftest width-zero-turns-the-probe-off-entirely
  (let [handler (:handler (cell/get-cell :probe/next-move))]
    (with-redefs [gates/threshold (fn [k] (if (= k :probe)
                                            {:width 0 :on-mechanics-failures 1}
                                            (gates/threshold k)))]
      (let [data {:branch (stuck-branch 5) :turn 3}]
        (is (= data (handler {} data)))))))

(deftest a-stuck-branch-is-steered-toward-the-framing-that-produced-a-call
  (let [handler (:handler (cell/get-cell :probe/next-move))
        seen (atom [])
        ;; Every candidate but the second answers with prose; the second
        ;; produces a well-formed call. The steer must name that one.
        replies (atom 0)
        complete (fn [_tape]
                   (let [n (swap! replies inc)]
                     {:ok true
                      :response {:content (if (= 2 n) (fenced "read") "just prose")
                                 :finish-reason "stop"}}))
        branch (stuck-branch 5)]
    (with-redefs [samizdat.agent.infer/complete-fn
                  (fn [_ctx & _] (fn [t] (swap! seen conj t) (complete t)))]
      (let [out (handler {} {:branch branch :turn 4})]
        (testing "it spent one inference per candidate and no more"
          (is (= (:width (gates/threshold :probe)) (count @seen)))
          (is (= (:width (gates/threshold :probe)) (:arms (:probe out)))))
        (testing "every bounce ran off the SAME fixed prefix"
          (is (= [3 3 3] (mapv (comp count :messages) @seen))
              "one probe turn each, on a copy — they never accumulate"))
        (testing "the winner is the framing whose reply carried a usable call"
          (is (= "read" (:tool (:probe out))))
          (is (some? (:chosen (:probe out)))))
        (testing "and the branch is steered, not advanced"
          (let [added (last (:messages (:branch out)))]
            (is (= "user" (:role added)))
            (is (str/includes? (:content added) "[harness]"))
            (is (str/includes? (:content added) "read"))
            (is (= 4 (:turn added)) "stamped, so compaction knows which turn it belongs to")
            (is (= 3 (count (:messages (:branch out))))
                "ONE message added — the probe's own turns are all discarded")))))))

(deftest a-probe-adds-no-turn-row-and-no-tool-result
  (let [handler (:handler (cell/get-cell :probe/next-move))
        branch (stuck-branch 5)]
    (with-redefs [samizdat.agent.infer/complete-fn
                  (fn [_ctx & _]
                    (fn [_] {:ok true :response {:content (fenced "shell")
                                                 :finish-reason "stop"}}))]
      (let [out (handler {} {:branch branch :turn 4})]
        (is (= [] (:turns (:branch out)))
            "a probe is not a turn — the gates must not see it as one")
        (is (= (:mechanics branch) (:mechanics (:branch out)))
            "and not as a mechanics event either: it was not the branch's call")
        (is (nil? (:result out)) "no tool ran; there is no tool seam in the probe path")))))

(deftest every-probe-arm-failing-leaves-the-branch-alone
  (let [handler (:handler (cell/get-cell :probe/next-move))
        branch (stuck-branch 5)]
    (with-redefs [samizdat.agent.infer/complete-fn
                  (fn [_ctx & _] (fn [_] {:ok false :error "provider down"}))]
      (let [out (handler {} {:branch branch :turn 4})]
        (is (nil? (:tool (:probe out))))
        (is (= (:messages branch) (:messages (:branch out)))
            "no winner, no steer — a dead provider must not put prose in the tape")))))

(deftest the-candidate-list-is-data-in-resources
  ;; The point of LR-2's split: what the harness considers is editable at
  ;; runtime, with no rebuild.
  (let [txt (slurp (clojure.java.io/resource "prompts/probe-candidates.md"))
        lines (->> (str/split-lines txt)
                   (map str/trim)
                   (remove str/blank?)
                   (remove #(str/starts-with? % "#")))]
    (is (<= 2 (count lines)) "a probe needs candidates to be worth running")
    (is (every? #(str/includes? % "tool call") (take 1 lines))
        "the first candidate is the tie-break winner, so it should be the blunt one")))

;; --- the A/B arm -------------------------------------------------------------

(deftest ab-is-off-until-variants-are-configured
  (let [handler (:handler (cell/get-cell :probe/ab-model))
        data {:branch (stuck-branch 0)}]
    (is (= data (handler {} data))
        "no variants configured means no spend")))

(deftest ab-keeps-every-arm-including-a-broken-one
  (let [handler (:handler (cell/get-cell :probe/ab-model))]
    (with-redefs [gates/threshold (fn [k] (if (= k :probe)
                                            {:variants [:cheap :broken]}
                                            (gates/threshold k)))
                  samizdat.agent.infer/complete-fn
                  (fn [ctx & _]
                    (if (= :broken (::role ctx))
                      (fn [_] {:ok false :error "no such model"})
                      (fn [_] {:ok true :response {:content "an answer"
                                                   :finish-reason "stop"}})))]
      (let [out (handler {:config {}} {:branch (stuck-branch 0)})]
        (is (= #{:cheap :broken} (set (keys (:ab out))))
            "both arms are recorded; a comparison missing an arm is not one")))))

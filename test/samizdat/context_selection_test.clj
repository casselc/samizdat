;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.context-selection-test
  "The harness side of samizdat-context-selection/1: the default path is
  unchanged, a decision injects selected material once, identity is the id, and
  nothing here scores or grants anything."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.context-selection :as cs]
            [samizdat.agent.skills :as skills]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.skills]
            [samizdat.prompt :as prompt]))

(def a-decision
  "A decision is a list of ids. Everything else it carries here - a name, a
  summary, a cost - is deliberately WRONG, so the tests prove the harness reads
  none of it."
  {:seam-version "samizdat-context-selection/1"
   :outcome :selected
   :policy :selector/2
   :selected [{:id "skill:repl-workflow" :name "not-this-name" :cost-load 999999}
              {:id "tool:grep" :name "not-this-either" :cost-load 999999}
              {:id "manual:samizdat.agent.files/glob-project"
               :summary "PROSE FROM OUTSIDE THE HARNESS" :cost-load 999999}]})

(deftest with-no-decision-the-prompt-block-is-exactly-todays-catalogue
  (is (= (skills/render-catalog) (cs/skills-block))
      "no decision -> the catalogue, byte for byte")
  (is (= (skills/render-catalog) (cs/skills-block nil))))

(deftest a-decision-adds-to-the-catalogue-rather-than-replacing-it
  (let [block (cs/skills-block a-decision)]
    (is (str/includes? block (skills/render-catalog))
        "the catalogue survives: the worker may still load anything on demand")
    (is (str/includes? block "skill:repl-workflow"))
    (is (str/includes? block "tool:grep")
        "what is already in the prompt is named so the worker does not re-load it")))

(deftest the-model-facing-prose-lives-in-a-template
  (is (str/includes? (prompt/prompt "context-selected") "already loaded")
      "the sentence is in resources/prompts/context-selected.md, editable without a rebuild")
  (let [rendered (cs/render-block (cs/materialize a-decision))]
    (is (str/includes? rendered "already loaded"))
    (is (str/includes? rendered "- skill:repl-workflow") "ids render as a list")))

(deftest each-kind-is-materialised-from-the-local-resource
  (let [m (cs/materialize a-decision)
        by-id (into {} (map (juxt :id identity)) (:entries m))]
    (testing "a skill body is injected, read locally by the id's own name"
      (is (= :injected (:status (by-id "skill:repl-workflow"))))
      (is (= "repl-workflow" (:name (by-id "skill:repl-workflow")))
          "the decision's :name was ignored")
      (is (str/includes? (:text (by-id "skill:repl-workflow")) "REPL")))
    (testing "a tool on the role's surface is already in the prompt"
      (is (= :already-present (:status (by-id "tool:grep")))))
    (testing "a manual line is rendered from THIS image's manual, not the decision"
      (let [e (by-id "manual:samizdat.agent.files/glob-project")]
        (is (= :injected (:status e)))
        (is (not (str/includes? (:text e) "PROSE FROM OUTSIDE THE HARNESS"))
            "a decision cannot write prose into the model's prompt")))))

(deftest cost-is-recomputed-locally-and-never-taken-from-the-decision
  (let [m (cs/materialize a-decision)
        skill (first (filter #(= "skill:repl-workflow" (:id %)) (:injected m)))]
    (is (= (cs/cost-of (skills/load-skill "repl-workflow")) (:cost-load skill))
        "cost comes from the resolved body under the frozen estimator")
    (is (< (:injected-cost-load m) 999999)
        "the decision's inflated cost is not believed")
    (is (= (:injected-cost-load m) (reduce + 0 (map :cost-load (:injected m))))
        "and the total is the sum of what was actually injected")))

(deftest the-budget-is-enforced-by-the-harness
  (let [full (cs/materialize a-decision)
        tight (cs/materialize skills/default-dirs :all (assoc a-decision :budget 50))]
    (is (pos? (:injected-cost-load full)))
    (is (<= (:injected-cost-load tight) 50) "nothing over budget is injected")
    (is (seq (:dropped-over-budget tight)) "and what did not fit is recorded")
    (is (every? #(= :dropped-over-budget (:status %)) (:dropped-over-budget tight)))))

(deftest a-tool-off-this-roles-surface-is-not-claimed-to-be-present
  (let [m (cs/materialize skills/default-dirs #{"read_file"}
                          {:selected [{:id "tool:grep"} {:id "tool:read_file"}]})
        by-id (into {} (map (juxt :id identity)) (:entries m))]
    (is (= :already-present (:status (by-id "tool:read_file"))))
    (is (= :unresolved (:status (by-id "tool:grep"))))
    (is (= :not-on-role-surface (:reason (by-id "tool:grep")))
        "claiming a tool is in the prompt when the role cannot see it would be a lie")))

(deftest an-id-this-run-cannot-resolve-is-dropped-and-recorded
  (doseq [[id reason] {"skill:no-such-thing" :no-such-skill
                       "manual:no.such/entry" :no-such-manual-entry
                       "mystery:thing" :unknown-kind}]
    (let [m (cs/materialize {:selected [{:id id}]})]
      (is (empty? (:injected m)) id)
      (is (= 1 (count (:unresolved m))) id)
      (is (= reason (:reason (first (:unresolved m)))) id)
      (is (zero? (:injected-cost-load m)) id)
      (is (nil? (cs/render-block m)) "nothing resolved -> nothing injected"))))

(deftest identity-is-the-id-so-a-shared-display-name-cannot-shadow
  (let [m (cs/materialize {:selected [{:id "skill:repl-workflow"}
                                      {:id "skill:mycelium"}]})]
    (is (= 2 (count (:injected m))))
    (is (= #{"skill:repl-workflow" "skill:mycelium"} (set (map :id (:injected m)))))
    (is (= (reduce + 0 (map :cost-load (:injected m))) (:injected-cost-load m)))))

(deftest a-repeat-load-can-be-recognised-as-a-repeat
  (let [m (cs/materialize a-decision)]
    (is (cs/already-loaded? m "repl-workflow"))
    (is (cs/already-loaded? m "skill:repl-workflow"))
    (is (not (cs/already-loaded? m "mycelium")))))

(deftest the-skill-tool-records-every-load-and-charges-the-repeats
  (let [run-id (str (java.util.UUID/randomUUID))
        m (cs/materialize a-decision)]
    (cs/reset-accounting! run-id m)
    (is (zero? (:n-loads (cs/accounting run-id))))
    ;; the tool itself, not the helper: a load of injected material and a fresh one
    (base/run-tool {:branch {:id "B1"} :tool-name "skill" :run-id run-id
                    :args {:action "load" :name "repl-workflow"}})
    (base/run-tool {:branch {:id "B1"} :tool-name "skill" :run-id run-id
                    :args {:action "load" :name "mycelium"}})
    (let [a (cs/accounting run-id)]
      (is (= 2 (:n-loads a)) "both loads are recorded by the tool")
      (is (= 1 (:n-repeat-loads a)) "the injected one is a repeat")
      (is (pos? (:repeat-load-cost-load a)) "and the repeat is charged, not waived")
      (is (= (+ (:injected-cost-load a) (:loaded-cost-load a))
             (:context-consumed-cost-load a))
          "context consumed is injected plus loaded, repeats included"))))

(deftest telemetry-is-metadata-only
  (let [m (cs/materialize a-decision)
        attrs (cs/telemetry-attrs a-decision m)]
    (is (= "samizdat-context-selection/1" (get attrs "samizdat.context.seam_version")))
    (is (= 3 (get attrs "samizdat.context.n_selected")))
    (is (= 2 (get attrs "samizdat.context.n_injected")))
    (is (= 1 (get attrs "samizdat.context.n_already_present")))
    (is (not-any? #(str/includes? (str %) "REPL") (vals attrs))
        "no body text leaves in telemetry")))

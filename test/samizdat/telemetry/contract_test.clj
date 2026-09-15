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

(ns samizdat.telemetry.contract-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.telemetry.contract :as c]))

(def m @c/manifest)

(defn- rejects? [manifest pattern]
  (try (c/validate manifest) false
       (catch Throwable e
         (and (:samizdat.telemetry/error (ex-data e))
              (boolean (re-find pattern (.getMessage e)))))))

(deftest manifest-loads-and-is-well-formed
  (is (= "samizdat-telemetry/1" (c/schema-version m)))
  (is (= "samizdat-langfuse-mapping/2" (c/mapping-version m)))
  (is (< 40 (count (:attributes m))))
  (is (= (count (:attributes m)) (count (c/attributes m))) "keys unique")
  (testing "the closed promoted set is small, typed and never double"
    (let [p (c/promoted m)]
      (is (<= (count p) (:max-promoted m)))
      (is (= 14 (count p)))
      (is (every? #(contains? #{:string :boolean :int64} (:type (get (c/attributes m) %))) p))
      (is (= #{"samizdat.execution.kind" "samizdat.evaluator.id" "samizdat.evaluator.status"
               "samizdat.evaluator.purpose" "samizdat.worker.claimed" "samizdat.protocol.correct"
               "samizdat.infra.error" "samizdat.task.complete" "samizdat.feedback.delivered"
               "samizdat.budget.turns.remaining" "samizdat.budget.wall.remaining_ms"
               "gen_ai.usage.output_tokens" "samizdat.observation.mode" "samizdat.value.basis"}
             (set p)))))
  (testing "outcome facts are four distinct attributes"
    (is (every? (c/attributes m) ["samizdat.worker.claimed" "samizdat.protocol.correct"
                                  "samizdat.infra.error" "samizdat.task.complete"])))
  (testing "no declared attribute may be a double promoted or live under the fallback prefix"
    (is (not-any? #(str/starts-with? (:key %) (:fallback-prefix m)) (:attributes m)))))

(deftest validation-rejects-bad-manifests
  (let [dup (update m :attributes conj (first (:attributes m)))
        bad-type (update m :attributes conj {:key "samizdat.zz" :type :float :group :x :authority :runtime
                                             :promote? false :langfuse :none :missing :unavailable})
        bad-lf (update m :attributes conj {:key "samizdat.zz" :type :string :group :x :authority :runtime
                                           :promote? false :langfuse :score :missing :unavailable})
        promoted-double (update m :attributes conj {:key "samizdat.zz" :type :double :group :x :authority :runtime
                                                    :promote? true :langfuse :none :missing :unavailable})
        too-many (assoc m :max-promoted 3)
        bad-schema (assoc m :schema "samizdat-telemetry/0")
        bad-domain (update m :attributes conj {:key "samizdat.zz" :type :int64 :domain ["a"] :group :x
                                               :authority :runtime :promote? false :langfuse :none
                                               :missing :unavailable})]
    (is (rejects? dup #"duplicate"))
    (is (rejects? bad-type #"type is not supported"))
    (is (rejects? bad-lf #"langfuse target"))
    (is (rejects? promoted-double #"may be promoted"))
    (is (rejects? too-many #"too many promoted"))
    (is (rejects? bad-schema #"unsupported schema"))
    (is (rejects? bad-domain #"only string attributes may declare a domain"))
    (is (= m (c/validate m)))))

(deftest normalize-keeps-false-zero-negatives-and-omits-nil
  (let [{:keys [attributes errors]}
        (c/normalize-result m {"samizdat.worker.claimed" false
                               "samizdat.infra.error" false
                               "samizdat.evaluator.failed_count" 0
                               "samizdat.budget.wall.remaining_ms" -7331
                               "samizdat.budget.turns.remaining" 0
                               "samizdat.cost.wall_credited_s" 0
                               "samizdat.feedback.sha256" nil
                               :samizdat.evaluator.status :passed
                               "gen_ai.usage.output_tokens" 130})]
    (is (= [] errors))
    (is (false? (get attributes "samizdat.worker.claimed")))
    (is (false? (get attributes "samizdat.infra.error")))
    (is (= 0 (get attributes "samizdat.evaluator.failed_count")))
    (is (= -7331 (get attributes "samizdat.budget.wall.remaining_ms")))
    (is (= 0 (get attributes "samizdat.budget.turns.remaining")))
    (is (= 0.0 (get attributes "samizdat.cost.wall_credited_s")))
    (is (not (contains? attributes "samizdat.feedback.sha256")) "nil is absence, not a value")
    (is (= "passed" (get attributes "samizdat.evaluator.status")) "keywords name string values")
    (is (= 130 (get attributes "gen_ai.usage.output_tokens")))))

(deftest normalize-falls-back-instead-of-lying
  (let [{:keys [attributes errors]}
        (c/normalize-result m {"samizdat.evaluator.status" "kinda-passed"   ; outside domain
                               "samizdat.worker.claimed" "true"             ; wrong type
                               "samizdat.evaluator.failed_count" 1.5        ; not an int
                               "samizdat.budget.turns.remaining" true       ; bool is not int
                               "samizdat.made.up" 3                         ; undeclared
                               "samizdat.x.already" "raw"})]
    (is (= "kinda-passed" (get attributes "samizdat.x.samizdat.evaluator.status")))
    (is (not (contains? attributes "samizdat.evaluator.status")))
    (is (= "true" (get attributes "samizdat.x.samizdat.worker.claimed")))
    (is (= "1.5" (get attributes "samizdat.x.samizdat.evaluator.failed_count")))
    (is (= "true" (get attributes "samizdat.x.samizdat.budget.turns.remaining")))
    (is (= "3" (get attributes "samizdat.x.samizdat.made.up")))
    (is (= "raw" (get attributes "samizdat.x.already")))
    (is (= #{{:key "samizdat.evaluator.status" :reason :domain-or-type}
             {:key "samizdat.worker.claimed" :reason :type}
             {:key "samizdat.evaluator.failed_count" :reason :type}
             {:key "samizdat.budget.turns.remaining" :reason :type}
             {:key "samizdat.made.up" :reason :undeclared}}
           (set errors)))))

(deftest langfuse-mapping-is-derived-from-the-manifest
  (let [lf (c/langfuse-mapping m)]
    (is (= (:mapping-version m) (:mapping-version lf)))
    (is (= "langfuse.observation.type" (:observation-type-key lf)))
    (is (= "samizdat.observation.kind" (:observation-type-source lf)))
    (is (= ["agent" "generation" "tool" "evaluator" "span"] (:observation-types lf)))
    (is (= ["samizdat.family.id" "samizdat.run.id"] (:session-sources lf)))
    (is (= "gen_ai.request.model" (:model-source lf)))
    (is (= ["gen_ai.usage.cache_hit_tokens" "gen_ai.usage.input_tokens"
            "gen_ai.usage.output_tokens" "gen_ai.usage.total_tokens"] (:usage-keys lf)))
    (is (= "langfuse.trace.metadata.execution_kind"
           (get (:trace-metadata lf) "samizdat.execution.kind")))
    (is (= "langfuse.observation.metadata.evaluator_status"
           (get (:observation-metadata lf) "samizdat.evaluator.status")))
    (testing "every trace/observation-targeted attribute is mirrored, nothing else is"
      (let [targets (group-by :langfuse (:attributes m))]
        (is (= (set (map :key (:trace-metadata targets))) (set (keys (:trace-metadata lf)))))
        (is (= (set (map :key (:observation-metadata targets))) (set (keys (:observation-metadata lf)))))))
    (testing "infra errors are levels, never scores"
      (is (= [{:when {"samizdat.evaluator.status" "infra-error"} :level "ERROR"}
              {:when {"samizdat.infra.error" true} :level "ERROR"}]
             (:level-rules lf)))
      (is (not (some #(re-find #"score" (str %)) (keys lf)))))
    (testing "mapping/2: trace metadata is root-scoped, with an observation-scoped twin"
      (is (= "root-observation" (:trace-metadata-scope lf)))
      (is (= "agent" (:root-observation-kind lf)))
      (is (= (set (keys (:trace-metadata lf))) (set (keys (:trace-metadata-on-observation lf)))))
      (is (= "langfuse.observation.metadata.cost_action_charged_s"
             (get (:trace-metadata-on-observation lf) "samizdat.cost.action_charged_s")))
      (is (every? #(clojure.string/starts-with? % "langfuse.observation.metadata.")
                  (vals (:trace-metadata-on-observation lf)))))
    (testing "mapping/2: content policy is declared, synthetic-only"
      (is (= "samizdat.observation.mode" (:mode-source lf)))
      (is (= ["langfuse.observation.input" "langfuse.observation.output"] (:content-keys lf)))
      (is (= ["synthetic"] (:content-allowed-modes lf)))
      (is (= "samizdat.x.content.refused" (:content-refused-key lf))))
    (testing "the operator override is declared: live only, env-named, marked"
      (is (= {:env "SAMIZDAT_TELEMETRY_CONTENT" :modes ["live"] :marker "samizdat.telemetry.content"}
             (:content-override lf)))
      (is (= ["off" "on"] (:domain (get (c/attributes m) "samizdat.telemetry.content")))))))

(deftest content-policy-never-stringifies-refused-content
  (let [attrs {"samizdat.run.id" "r1"
               "langfuse.observation.input" {:prompt "SYNTHETIC-IN"}
               "langfuse.observation.output" "SYNTHETIC-OUT"}
        [rest content] (c/split-content attrs)]
    (is (= {"samizdat.run.id" "r1"} rest))
    (is (= {"langfuse.observation.input" "{:prompt \"SYNTHETIC-IN\"}"
            "langfuse.observation.output" "SYNTHETIC-OUT"} content))
    (testing "normalising the split map cannot leak content into the fallback"
      (is (not-any? #(re-find #"SYNTHETIC" (str %)) (vals (c/normalize m rest)))))
    (testing "synthetic mode carries content verbatim"
      (let [a (c/apply-content (c/normalize m rest) content "synthetic")]
        (is (= "SYNTHETIC-OUT" (get a "langfuse.observation.output")))
        (is (not (contains? a "samizdat.x.content.refused")))))
    (doseq [mode ["live" "historical-import" nil "bogus"]]
      (testing (str "mode " (pr-str mode) " refuses content and names the keys only")
        (let [a (c/apply-content (c/normalize m rest) content mode)]
          (is (= "langfuse.observation.input,langfuse.observation.output"
                 (get a "samizdat.x.content.refused")))
          (is (not-any? #(re-find #"SYNTHETIC" (str %)) (vals a))))))
    (is (= (c/normalize m rest) (c/apply-content (c/normalize m rest) {} "live")) "no content, no marker")))

(deftest content-override-opens-live-and-nothing-else
  (let [content {"langfuse.observation.output" "PROMPT-TEXT"}
        base {"samizdat.run.id" "r1"}]
    (is (c/content-allowed? "synthetic"))
    (is (not (c/content-allowed? "live")))
    (is (c/content-allowed? "live" true) "the override opens live")
    (is (c/content-allowed? "synthetic" true))
    (doseq [mode ["historical-import" nil "bogus"]]
      (is (not (c/content-allowed? mode true)) (str "override never opens " (pr-str mode))))
    (is (not (c/content-allowed? "live" "on")) "only boolean true counts as the override")
    (is (= "PROMPT-TEXT" (get (c/apply-content base content "live" true) "langfuse.observation.output")))
    (is (= "langfuse.observation.output"
           (get (c/apply-content base content "live" false) "samizdat.x.content.refused")))
    (let [a (c/apply-content base content "historical-import" true)]
      (is (= "langfuse.observation.output" (get a "samizdat.x.content.refused")))
      (is (not-any? #(re-find #"PROMPT" (str %)) (vals a))))))

(deftest typed-column-fragments-have-the-joc-v3-shape
  (let [frags (c/typed-column-fragments m)]
    (is (= (count (c/promoted m)) (count frags)))
    (doseq [f frags]
      (is (= #{:signal :table :location :key :type} (set (keys f))))
      (is (= :spans (:signal f)))
      (is (= "otel_traces" (:table f)))
      (is (= :span-attributes (:location f)))
      (is (contains? #{:string :boolean :int64} (:type f))))))

(deftest json-rendering-is-canonical-and-byte-stable
  (let [a (c/manifest-json m)
        b (c/manifest-json (update m :attributes #(vec (reverse (vec %)))))]
    (is (string? a))
    (is (= a (c/manifest-json m)))
    (is (not= a b) "attribute order is part of the manifest (promotion order)")
    (is (= (c/render-json {:b 1 :a {:d [1 2] :c :kw}})
           "{\"a\":{\"c\":\"kw\",\"d\":[1,2]},\"b\":1}"))
    (testing "the rendering round-trips through a plain JSON reader"
      (let [back (json/read-str a)]
        (is (= "samizdat-telemetry/1" (get back "schema")))
        (is (= (count (:attributes m)) (count (get back "attributes"))))))
    (testing "mapping and fragments render too"
      (is (str/starts-with? (c/mapping-json m) "{\"content-allowed-modes\""))
      (is (str/includes? (c/fragments-json m) "\"otel_traces\"")))))

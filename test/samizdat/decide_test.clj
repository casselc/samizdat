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
  "Closed-domain decisions, tested with NO model.

  That is the point of the scorer seam: every branch is reachable with a scorer
  that returns a literal map, so this namespace runs in the ordinary suite on a
  machine with no inference engine, no native library and no model weights. The
  tests that need a real model live in the canaries, not here."
  (:require [clojure.test :refer [deftest is testing]]
            [mycelium.cell :as cell]
            [samizdat.cells :as cells]
            [samizdat.decide :as decide]
            [samizdat.manifests :as manifests]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(def ^:private vocabulary
  [{:id :hold     :text " HOLD"     :tokens [11]}
   {:id :scale    :text " SCALE"    :tokens [12]}
   {:id :rollback :text " ROLLBACK" :tokens [13]}])

(defn- domain-of
  ([] (domain-of (decide/all-legal)))
  ([legality]
   (decide/authorize vocabulary
                     {:legality legality :id :test-domain :revision "v1"
                      :state-coord "state:abc" :authority :test})))

(defn- scorer-returning [scores]
  (fn [_ctx _cands] {:scores scores}))

(def ^:private good-scores {:hold -0.2 :scale -3.0 :rollback -4.0})
(def ^:private policy {:min-margin 0.5})

(defn- run
  ([scorer] (run scorer (domain-of)))
  ([scorer domain]
   (decide/decide {:scorer scorer :domain domain :policy policy
                   :prov-ctx {:run-id "r1" :model-id "test"}})))

;; ------------------------------------------------------------- baseline

(deftest a-clear-winner-is-acted-on
  (let [r (run (scorer-returning good-scores))]
    (is (= :act (:decision r)))
    (is (= :hold (:selected r)))
    (is (< 2.7 (:margin r) 2.9))
    (is (= 3 (:n-offered r)))
    (is (= 3 (:n-scored r)))))

(deftest a-near-tie-defers-rather-than-guessing
  (let [r (run (scorer-returning {:hold -1.00 :scale -1.02 :rollback -4.0}))]
    (is (= :defer (:decision r)))
    (is (= :reason/below-margin (:reason r)))
    (is (nil? (:selected r)))
    (testing "and records what it would have picked, so the deferral is reviewable"
      (is (= :hold (:would-have-selected r))))))

;; ------------------------------------------------- #6 scorer fails closed

(deftest a-partial-score-map-defers-instead-of-collapsing-the-domain
  (testing "THE fail-open case: 3 authorized, 1 scored, used to become :act"
    (let [r (run (scorer-returning {:hold -1.0}))]
      (is (= :defer (:decision r)))
      (is (= :reason/incomplete-scores (:reason r)))
      (is (nil? (:selected r)))
      (testing "and the record names which candidates went unscored"
        (is (= #{:scale :rollback} (set (get-in r [:score-check :missing]))))))))

(deftest every-invalid-score-shape-is-refused
  (doseq [[label bad expected]
          [["all missing"   {}                                        :reason/no-scores]
           ["nil score"     {:hold -1.0 :scale nil :rollback -3.0}    :reason/incomplete-scores]
           ["string score"  {:hold -1.0 :scale "x" :rollback -3.0}    :reason/invalid-scores]
           ["NaN"           {:hold -1.0 :scale ##NaN :rollback -3.0}  :reason/invalid-scores]
           ["+Inf"          {:hold -1.0 :scale ##Inf :rollback -3.0}  :reason/invalid-scores]
           ["-Inf"          {:hold -1.0 :scale ##-Inf :rollback -3.0} :reason/invalid-scores]]]
    (testing label
      (let [r (run (scorer-returning bad))]
        (is (= :defer (:decision r)) label)
        (is (= expected (:reason r)) label)
        (is (nil? (:selected r)) label)))))

(deftest a-nil-score-is-missing-not-invalid
  (testing "nil means the scorer did not answer; a string means it answered badly.
            Distinct because a training pipeline should tell them apart"
    (let [r (run (scorer-returning {:hold -1.0 :scale nil :rollback -3.0}))]
      (is (= :reason/incomplete-scores (:reason r)))
      (is (= [:scale] (get-in r [:score-check :missing]))))))

(deftest an-unknown-extra-id-fails-closed-and-never-enters-the-domain
  (let [r (run (scorer-returning (assoc good-scores :DELETE-EVERYTHING 0.0)))]
    (testing "fail closed: a scorer bound to a stale domain is not trustworthy
              about the current one"
      (is (= :defer (:decision r)))
      (is (= :reason/invalid-scores (:reason r)))
      (is (= [:DELETE-EVERYTHING] (get-in r [:score-check :extra]))))
    (testing "and the extra id is nowhere in the ranked domain"
      (is (not (contains? (set (map :id (:domain r))) :DELETE-EVERYTHING)))
      (is (= 3 (count (:domain r)))))))

(deftest a-scorer-exception-never-escapes-decide
  (let [r (run (fn [_ _] (throw (ex-info "engine died" {}))))]
    (is (= :defer (:decision r)))
    (is (= :reason/scorer-failed (:reason r)))))

(deftest a-malformed-scorer-result-is-refused
  (doseq [bad [nil "nope" {:scores nil} {:scores []} 42]]
    (let [r (run (fn [_ _] bad))]
      (is (= :defer (:decision r)) (str "for " (pr-str bad)))
      (is (= :reason/no-scores (:reason r)) (str "for " (pr-str bad))))))

;; ------------------------------------------- #7 legality fails closed

(deftest a-domain-with-no-legality-source-is-refused
  (testing "the fail-open default this replaces: no rule used to mean all legal"
    (let [naked {:domain/candidates vocabulary}
          r (run (scorer-returning good-scores) naked)]
      (is (= :defer (:decision r)))
      (is (= :reason/unauthorized-domain (:reason r)))
      (is (= :domain/no-legality-source (:domain-check r))))))

(deftest an-illegal-action-never-reaches-the-scorer
  (let [seen (atom nil)
        legality (decide/legality :test-policy "v3" #(not= :rollback (:id %)))
        d (domain-of legality)
        r (decide/decide {:scorer (fn [_ cands] (reset! seen (mapv :id cands))
                                    {:scores {:hold -0.2 :scale -3.0}})
                          :domain d :policy policy})]
    (is (= [:hold :scale] @seen) "the scorer never saw the rejected action")
    (is (= :act (:decision r)))
    (testing "and the rejection is recorded, so an auditor can see why it was absent"
      (is (= [:rollback] (:rejected r))))))

(deftest an-empty-authorized-domain-is-refused
  (let [d (domain-of (decide/legality :none "v1" (constantly false)))
        r (run (scorer-returning {}) d)]
    (is (= :defer (:decision r)))
    (is (= :domain/empty (:domain-check r)))))

(deftest duplicate-semantic-ids-are-refused
  (let [d (decide/authorize [{:id :hold :tokens [1]} {:id :hold :tokens [2]}]
                            {:legality (decide/all-legal) :id :d :revision "v1"})
        r (run (scorer-returning {:hold -1.0}) d)]
    (is (= :defer (:decision r)))
    (is (= :domain/duplicate-id (:domain-check r)))))

(deftest all-legal-is-explicit-and-recorded
  (testing "a caller that MEANS everything is legal can say so, and it shows"
    (let [d (domain-of)]
      (is (= :all-legal (:domain/legality-source d)))
      (is (= 3 (count (:domain/candidates d)))))))

;; ---------------------------------------------- #5 audit keeps the domain

(deftest the-full-offered-domain-survives-a-scoring-failure
  (testing "an unscored candidate must not disappear because rank filtered it"
    (let [r (run (scorer-returning {:hold -1.0}))]
      (is (= 3 (count (:domain r))) "all three offered candidates are recorded")
      (is (= #{:hold :scale :rollback} (set (map :id (:domain r)))))
      (let [by-id (into {} (map (juxt :id identity) (:domain r)))]
        (is (= :missing (:scoring-status (by-id :scale))))
        (is (= :missing (:scoring-status (by-id :rollback))))
        (is (nil? (:score (by-id :scale))))
        (testing "even the one that WAS scored is not presented as a decision"
          (is (= 3 (:n-offered r)))
          (is (zero? (:n-scored r))))))))

(deftest an-invalid-score-is-distinguished-from-a-missing-one-per-candidate
  (let [r (run (scorer-returning {:hold -1.0 :scale ##NaN}))
        by-id (into {} (map (juxt :id identity) (:domain r)))]
    (is (= :invalid (:scoring-status (by-id :scale))))
    (is (= :missing (:scoring-status (by-id :rollback))))))

(deftest a-successful-decision-records-every-candidate-with-its-rank
  (let [r (run (scorer-returning good-scores))]
    (is (= [0 1 2] (mapv :rank (:domain r))))
    (is (every? #(= :ok (:scoring-status %)) (:domain r)))
    (is (every? :score (:domain r)))))

;; --------------------------------------------------- #9 provenance

(deftest provenance-is-recorded-and-allowlisted
  (let [r (decide/decide
           {:scorer (fn [_ _] {:scores good-scores
                               :meta {:convention :teacher-forced
                                      :homogeneous? true
                                      ;; must NOT survive: not on the scorer allowlist
                                      :authority :i-say-so
                                      ;; must NOT survive: not scalar
                                      :latency-ms {:nested :thing}}})
            :domain (domain-of)
            :policy policy
            :prov-ctx {:run-id "r1" :branch-id "B1" :turn 7
                       :domain-id :test-domain :domain-revision "v1"
                       :policy-revision "gates@v9" :min-margin 0.5
                       :model-sha256 "abc123" :state-coord "state:abc"
                       ;; must NOT survive: not on the provenance allowlist
                       :secret "hunter2"}})
        p (:provenance r)]
    (testing "allowlisted coordinates are kept"
      (is (= "r1" (:run-id p)))
      (is (= 7 (:turn p)))
      (is (= "gates@v9" (:policy-revision p)))
      (is (= "abc123" (:model-sha256 p)))
      (is (= :teacher-forced (:convention p)))
      (is (true? (:homogeneous? p))))
    (testing "a scorer cannot assert authority it does not have"
      (is (nil? (:authority p))))
    (testing "unknown keys and non-scalar values are dropped"
      (is (nil? (:secret p)))
      (is (nil? (:latency-ms p))))))

(deftest provenance-survives-every-failure-path
  (testing "a deferral is exactly when you most need to know which policy ran"
    (doseq [scorer [(scorer-returning {:hold -1.0})
                    (fn [_ _] (throw (ex-info "boom" {})))
                    (fn [_ _] nil)]]
      (let [r (decide/decide {:scorer scorer :domain (domain-of) :policy policy
                              :prov-ctx {:run-id "r1" :policy-revision "gates@v9"}})]
        (is (= "r1" (get-in r [:provenance :run-id])))
        (is (= "gates@v9" (get-in r [:provenance :policy-revision])))))))

;; ------------------------------------------------------- journal safety

(deftest the-journal-record-carries-no-machine-state
  (let [r (decide/decide
           {:scorer (fn [_ _] {:scores good-scores
                               :meta {:logits [1 2 3] :session ::handle}})
            :domain (decide/authorize
                     (mapv #(assoc % :state ::blob :ptr 140234) vocabulary)
                     {:legality (decide/all-legal) :id :d :revision "v1"})
            :policy policy
            :context "a long prompt that must not be recorded"
            :prov-ctx {:prompt "nor this"}})]
    (testing "even though candidates, scorer meta and context all carried them in"
      (is (nil? (decide/leaks? r))))
    (is (not (contains? (set (mapcat keys (:domain r))) :state)))
    (is (not (contains? (set (mapcat keys (:domain r))) :tokens))))
  (testing "leaks? actually detects a leak, so the assertion above is not vacuous"
    (is (= #{:state} (decide/leaks? {:domain [{:id :a :state ::blob}]})))
    (is (= #{:logits} (decide/leaks? {:a {:b [{:logits [1 2 3]}]}})))))

;; ------------------------------------------------------- #8 encodings

(deftest action-encodings-are-verified-not-truncated
  (let [toks {" HOLD" [11] " SCALE" [12] " ROLLBACK" [13 14] " PAGE" [] " STOP" [11]}
        tk (fn [t] (get toks t))]
    (testing "a genuinely single-token vocabulary passes"
      (let [r (decide/verify-encodings [{:id :hold :text " HOLD"}
                                        {:id :scale :text " SCALE"}] tk)]
        (is (:ok? r))
        (is (= [1 1] (mapv :n-tokens (:encodings r))))))
    (testing "a multi-token encoding is a PROBLEM, not something to truncate"
      (let [r (decide/verify-encodings [{:id :rollback :text " ROLLBACK"}] tk)]
        (is (not (:ok? r)))
        (is (= :encoding/multi-token (:problem (first (:problems r)))))
        (is (= 2 (:n-tokens (first (:problems r)))))))
    (testing "an empty encoding is refused"
      (let [r (decide/verify-encodings [{:id :page :text " PAGE"}] tk)]
        (is (not (:ok? r)))
        (is (= :encoding/empty (:problem (first (:problems r)))))))
    (testing "two actions sharing a token id are refused: they are not distinct"
      (let [r (decide/verify-encodings [{:id :hold :text " HOLD"}
                                        {:id :stop :text " STOP"}] tk)]
        (is (not (:ok? r)))
        (is (= :encoding/aliased (:problem (first (:problems r)))))
        (is (= #{:hold :stop} (set (:ids (first (:problems r))))))))
    (testing "a tokenizer that throws is reported, not propagated"
      (let [r (decide/verify-encodings [{:id :x :text " X"}]
                                       (fn [_] (throw (ex-info "no tokenizer" {}))))]
        (is (not (:ok? r)))
        (is (= :encoding/tokenize-failed (:problem (first (:problems r)))))))))

(deftest comparability-requires-real-equal-length-encodings
  (is (decide/comparable? [{:id :a :tokens [1]} {:id :b :tokens [2]}]))
  (is (not (decide/comparable? [{:id :a :tokens [1]} {:id :b :tokens [1 2]}])))
  (testing "a zero-token candidate is not comparable, it is unencoded"
    (is (not (decide/comparable? [{:id :a :tokens []} {:id :b :tokens []}]))))
  (testing "and neither is one with no encoding at all"
    (is (not (decide/comparable? [{:id :a} {:id :b}])))))

(deftest unequal-length-candidates-defer
  (let [d (decide/authorize [{:id :hold :tokens [1]}
                             {:id :roll-back-now :tokens [1 2 3 4]}]
                            {:legality (decide/all-legal) :id :d :revision "v1"})
        r (decide/decide {:scorer (scorer-returning {:hold -1.0 :roll-back-now -3.0})
                          :domain d :policy {:min-margin 0.1}})]
    (is (= :defer (:decision r)))
    (is (= :reason/not-comparable (:reason r)))))

;; --------------------------------------------------- ordering stability

(deftest the-ordering-is-total-and-reproducible
  (let [tied [{:id :b :tokens [1]} {:id :a :tokens [1]} {:id :c :tokens [1]}]
        s {:a -1.0 :b -1.0 :c -1.0}]
    (is (= [:a :b :c] (mapv :id (decide/rank tied s))))
    (is (= (mapv :id (decide/rank tied s))
           (mapv :id (decide/rank (reverse tied) s))))))

;; ------------------------------------------------- the canary manifest

(def ^:private manifest
  (delay (read-string (slurp "resources/manifests/decide.edn"))))

(deftest the-canary-manifest-compiles
  (cells/load-cells!)
  (is (some? (manifests/compile-definition @manifest))))

(deftest every-invariant-the-manifest-claims-is-actually-enforced
  (is (= 2 (count (manifests/enforced-constraints @manifest))))
  (is (empty? (manifests/unenforced-invariants @manifest))))

(deftest journalling-before-acting-is-a-compile-error-not-a-convention
  (cells/load-cells!)
  (testing "reordering the workflow so it acts before recording is REFUSED"
    (is (thrown? Throwable
                 (manifests/compile-definition
                  (assoc @manifest :edges {:start :apply :apply :score :score :end})))))
  (testing "and scoring a domain that was never authorized is too"
    (is (thrown? Throwable
                 (manifests/compile-definition
                  (assoc @manifest :edges {:score :apply :apply :start :start :end}))))))


;; ------------------------------------------- the cells, still with no model

(defn- handler [id] (cells/load-cells!) (:handler (cell/get-cell! id)))

(defmacro ^:private with-run [[conn-sym run-sym] & body]
  `(let [~conn-sym (db/open! ":memory:")]
     (try
       (let [~run-sym (runs/start-run! ~conn-sym
                                       {:problem "decide cell test"
                                        :provider "literal" :model "none"
                                        :max-turns 1 :beam-width 1})]
         ~@body)
       (finally (db/close ~conn-sym)))))

(deftest the-domain-cell-refuses-to-authorize-without-a-legality-source
  (testing "the fail-open default this replaces"
    (with-run [conn run-id]
      (let [data ((handler :decide/domain) {} {:decide/vocabulary vocabulary})
            out ((handler :decide/score) {:conn conn :run-id run-id}
                 (assoc data :decide/scorer (scorer-returning good-scores)))]
        (is (= :defer (:decide/decision out)))
        (is (= :reason/unauthorized-domain (:reason (:decide/record out))))
        (is (= :domain/no-legality-source (:domain-check (:decide/record out))))))))

(deftest the-domain-cell-authorizes-when-legality-is-explicit
  (with-run [conn run-id]
    (let [data ((handler :decide/domain) {} {:decide/vocabulary vocabulary
                                             :decide/all-legal? true})
          out ((handler :decide/score) {:conn conn :run-id run-id}
               (assoc data :decide/scorer (scorer-returning good-scores)
                      :decide/scorer-id "literal@v0"))]
      (is (= :act (:decide/decision out)))
      (is (= :hold (:selected (:decide/record out)))))))

(deftest a-legality-predicate-in-the-cell-keeps-an-action-from-the-scorer
  (with-run [conn run-id]
    (let [seen (atom nil)
          data ((handler :decide/domain) {}
                {:decide/vocabulary vocabulary
                 :decide/legal? #(not= :rollback (:id %))
                 :decide/legality-source :test-policy
                 :decide/legality-revision "v3"})
          out ((handler :decide/score) {:conn conn :run-id run-id}
               (assoc data :decide/scorer
                      (fn [_ cands] (reset! seen (mapv :id cands))
                        {:scores {:hold -0.2 :scale -3.0}})))]
      (is (= [:hold :scale] @seen))
      (is (= [:rollback] (:rejected (:decide/record out))))
      (testing "and the legality coordinate reaches the record"
        (is (= :test-policy (get-in out [:decide/record :provenance :legality-source])))
        (is (= "v3" (get-in out [:decide/record :provenance :legality-revision])))))))

(deftest a-decision-round-trips-through-real-sqlite-with-its-provenance
  (testing "the journal is the causal truth, so prove the row survives"
    (with-run [conn run-id]
      (let [data ((handler :decide/domain) {} {:decide/vocabulary vocabulary
                                               :decide/all-legal? true})
            _ ((handler :decide/score) {:conn conn :run-id run-id}
               (assoc data :decide/scorer (scorer-returning good-scores)
                      :decide/scorer-id "literal@v0"
                      :decide/model-coord {:model-sha256 "abc123"
                                           :model-id "test-model"}))
            back (journal/last-note conn run-id :decide)
            g (fn [k] (or (get back k) (get back (name k))))]
        (is (some? back))
        (is (= "act" (g :decision)))
        (is (= "hold" (g :selected)))
        (is (= 3 (count (g :domain))))
        (testing "provenance survives the JSON round trip"
          (let [p (g :provenance)
                gp (fn [k] (or (get p k) (get p (name k))))]
            (is (= run-id (gp :run-id)))
            (is (= "abc123" (gp :model-sha256)))
            (is (= "literal@v0" (gp :scorer-id)))
            (is (some? (gp :policy-revision)))))
        (testing "and nothing forbidden came with it"
          (is (nil? (decide/leaks? back))))))))

(deftest a-scorer-is-never-journalled
  (with-run [conn run-id]
    (let [data ((handler :decide/domain) {} {:decide/vocabulary vocabulary
                                             :decide/all-legal? true})
          _ ((handler :decide/score) {:conn conn :run-id run-id}
             (assoc data :decide/scorer (scorer-returning good-scores)
                    :decide/context "a prompt that must not be stored"))
          back (journal/last-note conn run-id :decide)]
      (is (nil? (decide/leaks? back)))
      (is (not (contains? (set (map name (keys back))) "scorer")))
      (is (not (contains? (set (map name (keys back))) "context"))))))

(deftest qualified-keywords-survive-the-journal
  (testing "data.json drops a keyword's namespace; durable puts it back"
    (is (= {:reason "reason/below-margin" :domain-check "domain/empty"}
           (decide/durable {:reason :reason/below-margin
                            :domain-check :domain/empty})))
    (is (= {:a [{:b "x/y"}]} (decide/durable {:a [{:b :x/y}]})))
    (testing "and an unqualified keyword is left alone"
      (is (= {:a :ok} (decide/durable {:a :ok}))))))

(deftest an-incomplete-score-map-is-journalled-as-a-deferral-with-the-full-domain
  (with-run [conn run-id]
    (let [data ((handler :decide/domain) {} {:decide/vocabulary vocabulary
                                             :decide/all-legal? true})
          _ ((handler :decide/score) {:conn conn :run-id run-id}
             (assoc data :decide/scorer (scorer-returning {:hold -1.0})))
          back (journal/last-note conn run-id :decide)
          g (fn [k] (or (get back k) (get back (name k))))]
      (is (= "defer" (g :decision)))
      (is (= "reason/incomplete-scores" (g :reason)))
      (testing "all three offered candidates are still in the durable record"
        (is (= 3 (count (g :domain))))))))

;; ------------------------------------------------ ADR-002: the contracts

(deftest the-domain-carries-where-it-came-from-and-the-policy-that-selects
  (let [d (decide/authorize vocabulary
                            {:legality (decide/all-legal) :id :d :revision "v1"
                             :based-on {:run/id "r1" :branch/id "B1" :turn 7
                                        :manifest :loop :state/version 41}
                             :policy-revision "abc123"})
        r (decide/decide {:scorer (scorer-returning good-scores) :domain d :policy policy})]
    (is (= 41 (get-in d [:domain/based-on :state/version])))
    (is (= "abc123" (:domain/policy-revision d)))
    (testing "and the record carries both, so a later reader can tell this domain from a lookalike"
      (is (= :loop (get-in r [:based-on :manifest])))
      (is (= "abc123" (:policy-revision r))))))

(deftest rejection-keeps-the-reason-not-only-the-id
  (let [legality (decide/legality :test-rule "r1" (fn [c] (not= :rollback (:id c))))
        d (domain-of legality)]
    (is (= [:rollback] (:domain/rejected d)))
    (is (= [{:id :rollback :reason :rejected/not-legal}] (:domain/rejected-with-reason d)))))

(deftest an-op-outside-the-closed-vocabulary-is-refused
  (let [bad (decide/authorize [{:id :hold :op :steer :target :stuck :tokens [1]}
                               {:id :wat :op :launch-missiles :tokens [2]}]
                              {:legality (decide/all-legal) :id :d :revision "v1"})
        ok (decide/authorize [{:id :hold :op :steer :target :stuck :tokens [1]}
                              {:id :cull :op :cull :tokens [2]}]
                             {:legality (decide/all-legal) :id :d :revision "v1"})]
    (is (= :domain/unknown-op (decide/domain-problem bad {})))
    (is (nil? (decide/domain-problem ok {})))
    (testing "a candidate with no :op is a bare id and stays valid"
      (is (nil? (decide/domain-problem (domain-of) {}))))
    (testing "every op the contract names is in the set"
      (is (= #{:continue :steer :block :complete :cull :spare :branch :escalate :defer}
             decide/ops)))))

(deftest evidence-about-another-domain-is-refused-before-it-is-read
  (let [stale (fn [_ _] {:evaluated-domain-id :some-other-domain :scores good-scores})
        r (run stale)]
    (is (= :defer (:decision r)))
    (is (= :reason/stale-domain (:reason r)))
    (is (= :some-other-domain (get-in r [:score-check :evaluated-domain-id]))
        "the record names which domain the scorer thought it was answering about"))
  (testing "a result naming THIS domain is accepted"
    (let [fresh (fn [_ _] {:evaluated-domain-id :test-domain :scores good-scores})
          r (run fresh)]
      (is (= :act (:decision r))))))

(deftest entropy-is-recorded-beside-the-margin
  (let [r (run (scorer-returning good-scores))]
    (is (number? (:entropy r)))
    (is (< 0.0 (:entropy r) (Math/log 3.0)) "between certainty and uniform over three"))
  (testing "a uniform distribution has maximal entropy and a single candidate none"
    (let [three (decide/rank [{:id :a :tokens [1]} {:id :b :tokens [1]} {:id :c :tokens [1]}]
                             {:a -1.0 :b -1.0 :c -1.0})]
      (is (< (Math/abs (- (Math/log 3.0) (decide/entropy three))) 1e-9))
      (is (nil? (decide/entropy (decide/rank [{:id :a :tokens [1]}] {:a -1.0})))))))

(deftest the-model-state-id-reaches-provenance-and-the-state-does-not
  (let [scorer (fn [_ _] {:scores good-scores
                          :meta {:model-state-id "ms-9" :scorer-id "s"
                                 :state (byte-array 4) :handle 12345}})
        r (run scorer)]
    (is (= "ms-9" (get-in r [:provenance :model-state-id])))
    (is (nil? (decide/leaks? r)) "the allowlist dropped :state and :handle")))

(deftest revalidation-demotes-a-stale-act-to-a-deferral
  (let [d (decide/authorize vocabulary
                            {:legality (decide/all-legal) :id :d :revision "v1"
                             :authority :ops
                             :based-on {:run/id "r1" :turn 7 :state/version 41}})
        r (decide/decide {:scorer (scorer-returning good-scores) :domain d :policy policy})]
    (is (= :act (:decision r)))
    (testing "fresh: same version, same authority, budget and invariants hold"
      (let [v (decide/revalidate r d {:state/version 41 :authority :ops})]
        (is (:revalidated? v))
        (is (= :fresh (get-in v [:revalidation :outcome])))
        (is (= :act (:decision v)))
        (is (= :hold (:selected v)))))
    (testing "the state moved on: not applied, and the record says what would have been"
      (let [v (decide/revalidate r d {:state/version 42 :authority :ops})]
        (is (= :defer (:decision v)))
        (is (= :reason/stale-revision (:reason v)))
        (is (= :stale-revision (get-in v [:revalidation :outcome])))
        (is (nil? (:selected v)))
        (is (= :hold (:would-have-selected v)))))
    (testing "the authority changed"
      (is (= :reason/authority-changed
             (:reason (decide/revalidate r d {:state/version 41 :authority :someone-else})))))
    (testing "the budget no longer admits it"
      (is (= :reason/budget-exceeded
             (:reason (decide/revalidate r d {:state/version 41 :authority :ops :budget-ok? false})))))
    (testing "an invariant is violated"
      (is (= :reason/invariant-violated
             (:reason (decide/revalidate r d {:state/version 41 :authority :ops :invariants-ok? false})))))
    (testing "a domain of unknown origin cannot be shown fresh"
      (let [d0 (domain-of)
            r0 (decide/decide {:scorer (scorer-returning good-scores) :domain d0 :policy policy})]
        (is (= :stale-revision (get-in (decide/revalidate r0 d0 {:state/version 41}) [:revalidation :outcome])))))
    (testing "a deferral stays a deferral, with the revalidation recorded"
      (let [dr (decide/decide {:scorer (scorer-returning {:hold -1.0 :scale -1.01 :rollback -4.0})
                               :domain d :policy policy})
            v (decide/revalidate dr d {:state/version 41 :authority :ops})]
        (is (= :defer (:decision v)))
        (is (= :reason/below-margin (:reason v)))
        (is (= :fresh (get-in v [:revalidation :outcome])))))))

(deftest a-model-state-ref-carries-identity-and-never-the-state
  (let [ref {:model-state/id "ms-1" :model/coordinate "qwen3.5-0.8b" :model/revision "sha"
             :model/representation :gguf-q8 :runtime/backend :jolt-llama :runtime/revision "r"
             :training-abi/version "v0" :graph/revision 3 :state/version 41
             :prefix-token-hash "abc" :prefix-token-count 512
             :native-state/hash "def" :native-state/bytes 1048576 :blob/ref "blob://1"}]
    (is (nil? (decide/model-state-ref-problem ref)))
    (is (= :model-state/missing-field (decide/model-state-ref-problem (dissoc ref :blob/ref))))
    (is (= :model-state/carries-state (decide/model-state-ref-problem (assoc ref :state (byte-array 1)))))
    (is (= :model-state/bad-token-count (decide/model-state-ref-problem (assoc ref :prefix-token-count "512"))))
    (is (= :model-state/not-a-map (decide/model-state-ref-problem nil)))))

(deftest a-decision-is-a-durable-row-and-the-apply-step-updates-it
  (cells/load-cells!)
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        handler (fn [id] (:handler (cell/get-cell id)))]
    (try
      (let [ctx {:conn c :run-id rid}
            data ((handler :decide/domain) ctx
                  {:decide/vocabulary vocabulary :decide/all-legal? true
                   :decide/authority :ops :decide/manifest :loop :decide/state-version 41
                   :branch {:id "B1"} :turn 7})
            scored ((handler :decide/score) ctx (assoc data :decide/scorer (scorer-returning good-scores)
                                                      :decide/scorer-id "test"))
            rows (journal/decisions c rid)]
        (is (= 41 (get-in data [:decide/authorized :domain/based-on :state/version])))
        (is (= 1 (count rows)))
        (is (= "act" (:decision (first rows))))
        (is (= 41 (:state_version (first rows))))
        (is (= "loop" (:manifest (first rows))))
        (is (= "hold" (:selected (first rows))))
        (is (= 0 (:revalidated (first rows))))
        (is (= 3 (get-in (first rows) [:record :n-offered])))
        (testing "apply with the state moved on: nothing applied, the row says why"
          (let [applied ((handler :decide/apply) ctx (assoc scored :decide/current {:state/version 42 :authority :ops}))
                row (first (journal/decisions c rid))]
            (is (false? (:decide/applied? applied)))
            (is (nil? (:decide/action applied)))
            (is (= :reason/stale-revision (:decide/deferred-reason applied)))
            (is (= 1 (:revalidated row)))
            (is (= "defer" (:decision row)))
            (is (= "hold" (:would_have_selected row)))))
        (testing "apply with the same state: applied, fresh"
          (let [applied ((handler :decide/apply) ctx (assoc scored :decide/current {:state/version 41 :authority :ops}))]
            (is (true? (:decide/applied? applied)))
            (is (= :hold (:decide/action applied)))
            (is (= :fresh (get-in applied [:decide/revalidation :outcome])))))
        (testing "apply without the current state: NOT applied; the row says why"
          (let [applied ((handler :decide/apply) ctx scored)
                row (first (journal/decisions c rid))]
            (is (false? (:decide/applied? applied)))
            (is (nil? (:decide/action applied)))
            (is (= :reason/unrevalidated (:decide/deferred-reason applied)))
            (is (false? (get-in applied [:decide/record :revalidated?])))
            (is (= :hold (get-in applied [:decide/record :would-have-selected])))
            (is (= 0 (:revalidated row)))
            (is (= "defer" (:decision row)))
            (is (= "reason/unrevalidated" (:reason row)))))
        (testing "apply without the current state but explicitly allowed (a fixture, a canary):
                  applied as scored, and the record and the row both say it was never checked"
          (let [applied ((handler :decide/apply) ctx (assoc scored :decide/allow-unrevalidated? true))
                row (first (journal/decisions c rid))]
            (is (true? (:decide/applied? applied)))
            (is (= :hold (:decide/action applied)))
            (is (false? (get-in applied [:decide/record :revalidated?])))
            (is (true? (get-in applied [:decide/record :unrevalidated-allowed?])))
            (is (= 0 (:revalidated row)))
            (is (= "act" (:decision row)))
            (is (.contains (str (:revalidation row)) "unrevalidated-allowed")))))
      (finally (db/close c)))))

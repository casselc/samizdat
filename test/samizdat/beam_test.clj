;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.beam-test
  "The beam's ROUND as a manifest.

  The inner turn has been a manifest since karamazov-ioo.20; the round was a
  loop/recur in compiled code, which made the harness's own scheduling policy
  the one thing a project could not change about itself. These tests pin the
  three things that had to survive the move: the round's ORDER, its three
  endings, and the driver's ownership of the crash record and teardown — the
  two things a manifest cannot own."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [mycelium.cell :as cell]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.state :as state]
            [samizdat.cells :as cells]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as workflow]))

(use-fixtures :once (fn [f] (cells/load-cells!) (f)))

(defn- manifest []
  (workflow/read-definition
   (slurp (clojure.java.io/resource "manifests/beam.edn"))))

(defn- branch [id & {:as extra}]
  (merge (state/new-branch {:id id :problem "p"
                            :messages [{:role "system" :content "s"}
                                       {:role "user" :content "p"}]})
         extra))

(defn- ctx-for [conn run-id & {:as extra}]
  (merge {:conn conn :run-id run-id :max-turns 3 :config {}
          :beam-width 2 :iterating-loop? true}
         extra))

;; --- the manifest ------------------------------------------------------------

(deftest the-round-manifest-compiles
  (is (some? (workflow/compile-loop (manifest)))))

(deftest the-round-manifest-is-in-the-catalogue
  ;; A scheduler the supervisor cannot see is one it can never adapt.
  (is (contains? (set (map :name (workflow/catalog nil))) "beam")))

(deftest every-round-node-is-a-registered-cell
  (doseq [[node cell-id] (:cells (manifest))]
    (is (some? (cell/get-cell cell-id))
        (str node " -> " cell-id " is not registered"))))

(deftest the-round-is-not-mistaken-for-a-turn
  ;; workflow/iterating? decides whether the beam schedules a manifest per
  ;; branch. The SCHEDULER must never be scheduled that way — five concurrent
  ;; copies of the thing that runs the beam is not a wider beam.
  (is (not (workflow/iterating? (manifest)))
      "the round has no :llm/infer of its own, so it is not a turn"))

(deftest the-order-constraints-are-compile-time-errors
  ;; The reasons are in cells/beam.clj. What matters here is that an edit which
  ;; breaks them is REFUSED rather than quietly producing a beam that decides
  ;; a branch's fate on last round's evidence.
  (testing "scoring must precede the retention pass"
    (is (thrown? Exception
                 (workflow/compile-loop
                  (assoc-in (manifest) [:edges :score] :settle)))))
  (testing "a branch must be written down before its slot is refilled"
    (is (thrown? Exception
                 (workflow/compile-loop
                  (assoc-in (manifest) [:edges :settle] :spawn))))))

;; --- the three endings, driven end to end ------------------------------------

(defn- drive
  "Run the real scheduler manifest with the model call stubbed out. `advance`
  is (fn [branches turn] -> branches), standing in for a round of real turns."
  [conn run-id advance & {:as ctx-extra}]
  (let [branches (:branches ctx-extra [(branch "B1") (branch "B2")])
        ctx (ctx-for conn run-id (dissoc ctx-extra :branches))]
    (doseq [b branches] (runs/open-branch! conn run-id {:branch-id (:id b)}))
    (with-redefs [beam/advance-all (fn [_ctx bs turn] (advance bs turn))
                  ;; The critic is a sub-LLM call; retention treats absent
                  ;; scores as "no opinion", which is the path under test.
                  beam/ensure-scored (fn [_ctx bs _turn] bs)]
      (beam/run-rounds ctx branches 1))))

(deftest a-shipped-branch-completes-the-run
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (try
      (let [r (drive c rid (fn [bs _turn]
                             (mapv #(if (= "B1" (:id %))
                                      (assoc % :status :done :final-answer "shipped it")
                                      %)
                                   bs)))]
        (is (= :completed (:status r)))
        (is (= "shipped it" (:answer r)))
        (testing "and the run row says so"
          (is (= "completed" (:status (runs/get-run c rid))))))
      (finally (db/close c)))))

(deftest the-turn-cap-exhausts-the-run-and-reports-residuals
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (try
      ;; Never ships: the cap is what ends this.
      (let [r (drive c rid (fn [bs _turn] bs))]
        (is (= :exhausted (:status r)))
        (is (some? (:report r)) "the residual report is what a resume reads")
        (is (string? (:report-text r))))
      (finally (db/close c)))))

(deftest an-empty-beam-exhausts-rather-than-spinning
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (try
      (let [r (drive c rid (fn [bs _turn]
                             (mapv #(assoc % :status :culled
                                           :inactive-reason "test") bs)))]
        (is (= :exhausted (:status r))))
      (finally (db/close c)))))

(deftest the-abort-flag-stops-the-run-without-its-cooperation
  ;; Checked at the top of every round, before anything else happens: a stop
  ;; must not need the round to agree to it.
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        rounds (atom 0)]
    (try
      (let [abort (atom false)
            r (drive c rid
                     (fn [bs _turn] (swap! rounds inc) (reset! abort true) bs)
                     :abort abort)]
        (is (= :aborted (:status r)))
        (is (= 1 @rounds) "the round after the flag was set never ran"))
      (finally (db/close c)))))

(deftest an-abort-set-before-the-first-round-runs-nothing
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        rounds (atom 0)]
    (try
      (let [r (drive c rid (fn [bs _turn] (swap! rounds inc) bs)
                     :abort (atom true))]
        (is (= :aborted (:status r)))
        (is (zero? @rounds)))
      (finally (db/close c)))))

;; --- what the driver still owns ----------------------------------------------

(deftest a-crash-mid-round-is-recorded-before-it-is-rethrown
  ;; gen-11 threw here and the exception reached a tty and nowhere else; the
  ;; row stayed 'running' for the nine hours the run had been dead.
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (try
      (is (thrown-with-msg?
           Exception #"boom"
           (drive c rid (fn [_bs _turn] (throw (ex-info "boom" {}))))))
      (is (= "failed" (:status (runs/get-run c rid)))
          "a crash that leaves no trace is indistinguishable from a slow round")
      (finally (db/close c)))))

(deftest teardown-sees-the-branches-as-they-stood-when-the-round-died
  ;; A thrown manifest hands nothing back, so the driver keeps its own window.
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        disposed (atom [])]
      (try
        (with-redefs [beam/advance-all
                      (fn [_ctx bs _turn]
                        (mapv #(assoc % :marked-by-the-round true) bs))
                      beam/ensure-scored (fn [_ctx bs _turn] bs)
                      beam/record-inactive!
                      (fn [_ctx bs] (reset! disposed bs) (throw (ex-info "die" {})))]
          (is (thrown? Exception
                       (beam/run-rounds (ctx-for c rid) [(branch "B1")] 1))))
        (is (every? :marked-by-the-round @disposed)
            "the round's own progress is visible to teardown, not the input")
        (finally (db/close c)))))

;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.verification-integration-test
  "Verification evidence through the ACTUAL shipping and completion path.

  samizdat.verification-status-test pins the store contract by calling
  `finish-run!` directly. That cannot show that a real `done` produces the
  evidence, that the cells which finish a run carry it, or that what the API
  returns matches what the ship gate actually did. These run the beam.

  Each case drives a fake provider from the workspace's own state rather than a
  call counter: side calls (critic, selection) reach the same fake, so counting
  is not a stable way to sequence a worker's turns."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.gates :as gates]
            [samizdat.api.runs :as api-runs]
            [samizdat.engine.proc :as proc]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [samizdat.userspace :as userspace]))

(def ^:private test-file "test/core_test.clj")
(def ^:private touched ";; touched")

(defn- seeded-root
  "A project root holding one test namespace. `git?` decides whether the ship
  gate has a baseline to diff against — the whole difference between the
  `passed` and `skipped` cases."
  [git?]
  (let [d (str (fs/create-temp-dir {:prefix "verification-integration"}))]
    (.mkdirs (io/file d "test"))
    (spit (io/file d test-file) "(ns core-test)\n")
    (when git?
      (proc/run {} "sh" "-c"
                (str "cd " d " && git init -q . && git add -A && "
                     "git -c user.email=a@b -c user.name=t commit -qm base")))
    d))

(defn- call [nm args]
  (str "working\n```tool-call\n" (json/write-str {:name nm :args args}) "\n```"))

(defn- worker
  "Claim a task, make one real edit, then ship. Keyed on the workspace so the
  sequence survives however many auxiliary calls the loop makes."
  [root claimed?]
  (fn [& _]
    (let [f (io/file root test-file)
          done? (and (.exists f) (str/includes? (slurp f) touched))]
      {:finish-reason "stop"
       :content (cond
                  (not @claimed?)
                  (do (reset! claimed? true)
                      (call "task" {:action "create" :title "add the touched marker"
                                    :claim_now true}))
                  (not done?)
                  (call "edit_file" {:path test-file
                                     :old_text "(ns core-test)"
                                     :new_text (str "(ns core-test)\n" touched)})
                  ;; The answer has to share substantive terms with the
                  ;; problem - a lexical ship rung refuses one that does not,
                  ;; and a placeholder answer would be testing that rung
                  ;; instead of verification.
                  :else (call "done"
                              {:answer (str "Added the touched marker to the "
                                            "core-test namespace in "
                                            test-file ".")}))})))

(defn- ship-a-run
  "Run the beam to completion and return everything needed to judge it:
  the row, the API's view, and the ship-verify event the gate wrote."
  [{:keys [git? run-config verify-unknown]}]
  (let [root (seeded-root git?)
        c (db/open! ":memory:")
        orig gates/threshold]
    (with-redefs [gates/threshold (fn [k] (if (= k :verify-unknown)
                                            verify-unknown
                                            (orig k)))
                  llm/chat (worker root (atom false))]
      (try
        (beam/run! {:conn c
                    ;; :width as well as :beam-width - the loop repopulates to
                    ;; the config width, and a second branch would share this
                    ;; run's single `claimed?` atom and scramble the sequence.
                    :config {:run (merge {:root root :width 1} run-config)}
                    :llm-adapter :a :llm-config {:max-tokens 200}
                    :beam-width 1 :max-turns 6
                    :problem (str "Add a touched marker to the core-test "
                                  "namespace in " test-file ".")})
        (catch Throwable _ nil)))
    (let [rid (:id (db/fetch-one c ["SELECT id FROM runs LIMIT 1"]))
          sv (db/fetch-one c ["SELECT data FROM events WHERE kind = 'ship-verify'
                                ORDER BY id DESC LIMIT 1"])]
      {:conn c
       :row (runs/get-run c rid)
       :api (:run (api-runs/get-run c rid))
       :ship-verify (some-> (:data sv) str (json/read-str :key-fn keyword))})))

(deftest a-real-green-check-is-recorded-as-passed
  (let [{:keys [conn row api ship-verify]}
        (ship-a-run {:git? true
                     :run-config {:verify-cmd "true"}
                     :verify-unknown :trust})]
    (testing "the run shipped"
      (is (= "completed" (str (:status row))))
      (is (str/includes? (str (:final_answer row)) "touched marker")))
    (testing "the gate actually ran and was green"
      (is (true? (:ran ship-verify)))
      (is (true? (:green ship-verify))))
    (testing "and the API says so without anyone reading the journal"
      (is (= "passed" (get-in api [:verification :status])))
      (is (= "green" (get-in api [:verification :reason])))
      (is (= "true" (get-in api [:verification :check]))
          "the identity of the check that ran, not merely that one did"))
    (db/close conn)))

(deftest a-missing-baseline-under-trust-completes-as-skipped
  ;; ws-opt trial 3 exactly: no git checkout, so the gate has no evidence, and
  ;; :trust lets the run ship anyway. Correct under that policy - and the point
  ;; is that the run now SAYS so.
  (let [{:keys [conn row api ship-verify]}
        (ship-a-run {:git? false
                     :run-config {:verify-focused? true}
                     :verify-unknown :trust})]
    (testing "completion is allowed"
      (is (= "completed" (str (:status row))))
      (is (str/includes? (str (:final_answer row)) "touched marker")))
    (testing "the gate did not run"
      (is (false? (:ran ship-verify)))
      (is (false? (:blocked ship-verify))))
    (testing "and the run is not mistakable for a verified one"
      (is (= "skipped" (get-in api [:verification :status])))
      (is (= "no-git-baseline" (get-in api [:verification :reason])))
      (is (not= "passed" (get-in api [:verification :status]))
          "a skipped check must never read as a passing one"))
    (db/close conn)))

(deftest a-missing-baseline-under-refuse-does-not-complete
  (let [{:keys [conn row ship-verify]}
        (ship-a-run {:git? false
                     :run-config {:verify-focused? true}
                     :verify-unknown :refuse})]
    (testing "done was refused, synchronously, before the branch could finish"
      (is (true? (:blocked ship-verify)))
      (is (= "no-git-baseline" (:why ship-verify))))
    (testing "and no successful completion was recorded"
      (is (not= "completed" (str (:status row))))
      (is (nil? (:final_answer row)))
      (is (nil? (runs/verification-of row))
          "a refused ship records no assurance, because nothing shipped"))
    (db/close conn)))

(deftest the-accepted-runs-evidence-agrees-with-its-ship-verify-event
  ;; The row and the event are computed SEPARATELY, from the same inputs, and
  ;; written by separate operations - there is no mechanism forcing them to
  ;; match. This pins that they do agree on the accepted path, which is the
  ;; case a consumer reads. It does not establish that they cannot diverge.
  (doseq [[label spec] {"green"   {:git? true
                                   :run-config {:verify-cmd "true"}
                                   :verify-unknown :trust}
                        "skipped" {:git? false
                                   :run-config {:verify-focused? true}
                                   :verify-unknown :trust}}]
    (let [{:keys [conn api ship-verify]} (ship-a-run spec)
          v (:verification api)]
      (testing (str label ": the two accounts describe the same event")
        (is (= (= "passed" (:status v)) (boolean (:ran ship-verify)))
            "the row claims the check ran exactly when the event says it ran")
        (when-not (:ran ship-verify)
          (is (= (:why ship-verify) (:reason v))
              "and gives the same reason for not running it")))
      (db/close conn))))

(deftest the-cells-that-actually-ran-carry-the-verification-threading
  ;; The cells are userspace: editable without a rebuild, resolved through
  ;; samizdat.userspace rather than loaded from src/. A source commit says what
  ;; the repository holds, NOT which cell body a run executed - a deployment
  ;; carrying an older stored copy would run that one instead. So identify the
  ;; bodies this process actually resolves, and assert on those.
  (doseq [nm ["beam" "loop" "board"]]
    (let [body (userspace/body :cell nm)]
      (is (some? body) (str "cell " nm " resolved to nothing"))
      (is (str/includes? (str body) ":verification")
          (str "the " nm " cell this process would run does not thread "
               ":verification (identity: length " (count (str body))
               ", hash " (hash (str body)) "). A run under this cell would "
               "complete with no assurance recorded, which reads as "
               "\"no evidence\" rather than as a false pass.")))))

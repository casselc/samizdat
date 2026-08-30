;; The offline half of the JS2 §22 recovery claim.
;;
;; Reconstructs the canary's REAL durable history — which contains committed
;; project/edit AND project/run receipts — in a fresh process, and observes the
;; execution provider's invocation counter and the manager's machine table
;; either side of it. A faithful replay consumes its receipts and launches
;; nothing; the counter is process-local, so "zero" here is a fact rather than
;; an interpretation.
(require '[samizdat.store.db :as db]
         '[samizdat.store.evaluator :as store]
         '[samizdat.security.project-execution-provider :as pep]
         '[samizdat.engine.proc :as proc]
         '[clojure.string :as str])

(def db-path (or (jolt.host/getenv "JS2DB")
                 "/home/chuck/opencode/src/js2-closure-evidence/js2-closure.sqlite3"))
(def run-id (jolt.host/getenv "JS2_RUN_ID"))
(def root (or (jolt.host/getenv "JS2_TARGET")
              "/home/chuck/opencode/src/js2-closure-target"))

(defn machines []
  (str/trim (str (:out (proc/run {:timeout-ms 15000} "smolvm" "machine" "ls")))))

(defn digest [p]
  (str/trim (str (:out (proc/run {:timeout-ms 20000} "sha256sum" p)))))

(defn mtime [p]
  (str/trim (str (:out (proc/run {:timeout-ms 20000} "stat" "-c" "%Y" p)))))

(def files [(str root "/src/samizdat/util.clj")
            (str root "/test/samizdat/util_test.clj")])

(let [conn (db/open! db-path)
      reconstruct! (requiring-resolve 'samizdat.evaluator/reconstruct!)
      binding-row (store/binding-for-run conn run-id)
      bid (:binding_id binding-row)
      history (store/history conn bid)
      receipts (mapcat :receipts history)
      edits (filter #(and (= :project/edit (:op %)) (= :done (:phase %))) receipts)
      runs (filter #(and (= :project/run (:op %)) (= :done (:phase %))) receipts)]
  (println "== durable history being replayed ==")
  (println "binding      " bid)
  (println "evaluations  " (count history)
           "completed" (count (filter #(= :completed (:status %)) history)))
  (println "edit receipts" (count edits))
  (println "run receipts " (count runs))
  (println "PRECONDITION (JS2 §22): both > 0 =>"
           (and (pos? (count edits)) (pos? (count runs))))
  (println)
  (println "== before reconstruction ==")
  (println "execution-provider invocation-count" (pep/invocation-count))
  (println "machines" (pr-str (machines)))
  (doseq [f files] (println "  " (digest f) "mtime=" (mtime f)))
  (println)
  (let [t0 (System/currentTimeMillis)
        rebuilt (reconstruct! conn run-id root)
        ms (- (System/currentTimeMillis) t0)]
    (println "== reconstruction ==")
    (println "elapsed-ms" ms)
    (println "binding-id  " (:binding/id rebuilt)
             "unchanged?" (= bid (:binding/id rebuilt)))
    (println "instance-id " (:instance/id rebuilt))
    (println "spec-id     " (get-in rebuilt [:spec :spec/coordinate]))
    (println "runtime     " (get-in rebuilt [:spec :runtime-coordinate]))
    (println "context-spec" (get-in rebuilt [:spec :context-spec :context/coordinate]))
    (println "capabilities" (pr-str (get-in rebuilt [:spec :context-spec :context/capabilities])))
    (println "orientation-digest" (:orientation-digest rebuilt)
             "unchanged?" (= (:orientation_digest binding-row)
                             (:orientation-digest rebuilt)))
    (println "live SCI context (FRESH)"
             ((requiring-resolve 'samizdat.evaluator/live-context-id) rebuilt))
    (println)
    (println "== after reconstruction ==")
    (println "execution-provider invocation-count" (pep/invocation-count)
             "  <= ZERO MEANS REPLAY LAUNCHED NO ENVIRONMENT")
    (println "machines" (pr-str (machines)))
    (doseq [f files] (println "  " (digest f) "mtime=" (mtime f)))
    (println)
    (println "durable counters after replay:")
    (let [h2 (store/history conn bid)
          r2 (mapcat :receipts h2)]
      (println "  evaluations  " (count h2))
      (println "  receipts     " (count r2))
      (println "  edit outcomes" (count (filter #(and (= :project/edit (:op %))
                                                      (= :done (:phase %))) r2)))
      (println "  run outcomes " (count (filter #(and (= :project/run (:op %))
                                                      (= :done (:phase %))) r2)))))
  (db/close conn))

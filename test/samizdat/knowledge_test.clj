(ns samizdat.knowledge-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [samizdat.store.db :as db]
            [samizdat.agent.tools.base :as base]
            [samizdat.store.knowledge :as knowledge]))

(def conn (atom nil))

(use-fixtures :each (fn [f] (reset! conn (db/open! ":memory:")) (f)))

(deftest remember-returns-an-id
  (let [id (knowledge/remember! @conn {:content "fred likes fish"})]
    (is (string? id))
    (is (pos? (count id)))))

(deftest recall-finds-by-substring
  (knowledge/remember! @conn {:content "fred likes fish"})
  (java.lang.Thread/sleep 1100)
  (knowledge/remember! @conn {:content "fred hates dogs" :kind "fact"})
  (knowledge/remember! @conn {:content "barney likes birds"})
  (is (= ["fred hates dogs" "fred likes fish"]
         (mapv :content (knowledge/recall @conn "fred")))))

(deftest recent-limits
  (dotimes [_ 3] (knowledge/remember! @conn {:content "row"}))
  (is (= 2 (count (knowledge/recent @conn 2)))))

(deftest forget-deletes
  (let [id (knowledge/remember! @conn {:content "unique needle here"})]
    (knowledge/forget! @conn id)
    (is (empty? (knowledge/recall @conn "needle")))))

(deftest remember-rethrows-non-collision-failures-instead-of-retrying
  ;; RFC-000 R2-15: same as messages/send! — only a UNIQUE collision is an
  ;; id-allocation problem worth retrying; anything else must propagate.
  (let [real-execute db/execute!
        inserts (atom 0)]
    (with-redefs [db/execute!
                  (fn [conn q & opts]
                    (when (str/includes? (str (first q)) "INSERT INTO knowledge")
                      (swap! inserts inc)
                      (throw (ex-info "disk I/O error" {:errno 5})))
                    (apply real-execute conn q opts))]
      (is (thrown-with-msg? Exception #"disk I/O error"
                            (knowledge/remember! @conn {:content "nope"}))))
    (is (= 1 @inserts) "a non-collision failure is not retried")))

(deftest kind-defaults-to-note
  (knowledge/remember! @conn {:content "kindless"})
  (is (= ["note"] (distinct (mapv :kind (knowledge/recall @conn "kindless"))))))

(deftest get-by-id-returns-row-and-nil-for-miss
  (let [id (knowledge/remember! @conn {:content "exact row payload"})
        row (knowledge/get-by-id @conn id)]
    (is (map? row))
    (is (= id (:id row)))
    (is (= "exact row payload" (:content row))))
  (is (nil? (knowledge/get-by-id @conn "k-nope"))))

(deftest breadcrumb-index-bounded-and-has-ids
  (let [long (str "HEAD " (apply str (repeat 200 "x")) " TAIL-END-MARKER")
        id (knowledge/remember! @conn {:content long :kind "note"})
        idx (knowledge/breadcrumb-index @conn "")]
    (is (string? idx))
    (is (str/includes? idx id))
    (is (<= (count idx) 700))
    (is (str/includes? idx "HEAD"))
    (is (not (str/includes? idx "TAIL-END-MARKER")))))

(deftest breadcrumb-index-nil-on-empty-db
  (is (nil? (knowledge/breadcrumb-index @conn ""))))

(deftest breadcrumb-index-relevance-ranked
  (knowledge/remember! @conn {:content "beta unrelated"})
  (knowledge/remember! @conn {:content "alpha needle here"})
  (let [idx (knowledge/breadcrumb-index @conn "needle")]
    (is (string? idx))
    (is (str/includes? idx "alpha"))))

(deftest forget-tool-deletes-and-reports
  ;; review4: the store fn existed but no surface reached it — recall could
  ;; surface a wrong fact with no way to drop it.
  (let [id (knowledge/remember! @conn {:content "the earth is flat"})]
    (let [r (base/run-tool {:branch {:id "B1"} :conn @conn
                            :tool-name "forget" :args {:id id}})]
      (is (= :neutral (:category r)) "forgetting is bookkeeping, like remember")
      (is (str/includes? (:result r) "Forgot")))
    (is (nil? (knowledge/get-by-id @conn id)) "the memory is gone"))
  (testing "an unknown id fails honestly"
    (let [r (base/run-tool {:branch {:id "B1"} :conn @conn
                            :tool-name "forget" :args {:id "k-none"}})]
      (is (= :failure (:category r)))
      (is (str/includes? (:result r) "No memory"))))
  (testing "a missing id argument is malformed, not a crash"
    (let [r (base/run-tool {:branch {:id "B1"} :conn @conn
                            :tool-name "forget" :args {}})]
      (is (str/includes? (:result r) "Missing")))))

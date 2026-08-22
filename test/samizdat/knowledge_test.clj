(ns samizdat.knowledge-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [samizdat.store.db :as db]
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


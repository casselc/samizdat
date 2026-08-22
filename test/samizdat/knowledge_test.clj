(ns samizdat.knowledge-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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


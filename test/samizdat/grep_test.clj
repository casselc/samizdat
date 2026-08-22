(ns samizdat.grep-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.files :as files]))

(def ^:private fixture-root
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "grep-test-" (System/nanoTime)))
    (.mkdirs)))

(.mkdirs (io/file fixture-root "sub"))

(spit (io/file fixture-root "a.clj")
      "(ns demo)\n\n(defn hello []\n  :hello)\n\n(defn bye []\n  :bye)\n")

(spit (io/file fixture-root "notes.txt")
      "(defn hidden [] :nope)\n")

(spit (io/file fixture-root "sub" "b.clj")
      "(defn nested []\n  :nested)\n")

(deftest finds-matching-lines-with-line-numbers
  (let [hits (files/grep-project (str fixture-root) "\\(defn")]
    (is (= 3 (count hits)))
    (is (= #{{:path "a.clj" :line 3 :text "(defn hello []"}
            {:path "a.clj" :line 6 :text "(defn bye []"}
            {:path "sub/b.clj" :line 1 :text "(defn nested []"}}
           (set hits)))))

(deftest ignores-non-clojure-extensions
  (is (empty? (files/grep-project (str fixture-root) "hidden"))))

(deftest no-match-is-empty
  (is (empty? (files/grep-project (str fixture-root) "zzz-no-such-thing"))))

(deftest paths-are-relative-to-root
  (let [hits (files/grep-project (str fixture-root) "defn nested")]
    (is (= ["sub/b.clj"] (mapv :path hits)))
    (is (= ["(defn nested []"] (mapv :text hits)))))

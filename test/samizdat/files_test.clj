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

(ns samizdat.files-test
  "read_file / write_file — the tools that let the agent read and change the
  project tree. Writes are confined to the project root; a path that escapes it
  is refused, so a self-modifying agent cannot reach outside its own repo."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.files :as files]))

(defn- ctx [root tool args]
  {:tool-name tool :args args :branch {:id "B1"} :root root})

(deftest read-file-bounds-and-missing
  (let [root (str "/tmp/samizdat-files-" (random-uuid))]
    (fs/create-dirs root)
    (spit (str root "/hello.txt") "line one\nline two\n")
    (try
      (testing "reads a file under the root"
        (let [r (files/read-file (ctx root "read_file" {:path "hello.txt"}))]
          (is (= :neutral (:category r)))
          (is (str/includes? (:result r) "line one"))))
      (testing "a missing file is a mechanics miss, not a failure"
        (is (= :mechanics (:category (files/read-file (ctx root "read_file" {:path "nope.txt"}))))))
      (testing "a path escaping the root is refused"
        (is (= :mechanics (:category (files/read-file (ctx root "read_file" {:path "../etc/passwd"})))))
        (is (= :mechanics (:category (files/read-file (ctx root "read_file" {:path "/etc/passwd"}))))))
      (finally (fs/delete-tree root)))))

(deftest write-file-confined-to-root
  (let [root (str "/tmp/samizdat-files-" (random-uuid))]
    (fs/create-dirs root)
    (try
      (testing "writes a new file under the root, creating parent dirs"
        (let [r (files/write-file (ctx root "write_file"
                                       {:path "src/new/thing.clj" :content "(ns thing)"}))]
          (is (= :success (:category r)))
          (is (:progress? r))
          (is (= "(ns thing)" (slurp (str root "/src/new/thing.clj"))))))
      (testing "overwrites an existing file"
        (files/write-file (ctx root "write_file" {:path "a.txt" :content "v1"}))
        (files/write-file (ctx root "write_file" {:path "a.txt" :content "v2"}))
        (is (= "v2" (slurp (str root "/a.txt")))))
      (testing "a path escaping the root is refused and writes nothing"
        (let [r (files/write-file (ctx root "write_file"
                                       {:path "../escape.txt" :content "x"}))]
          (is (= :mechanics (:category r)))
          (is (not (fs/exists? (str root "/../escape.txt")))))
        (is (= :mechanics (:category (files/write-file
                                      (ctx root "write_file"
                                           {:path "/tmp/abs-escape.txt" :content "x"}))))))
      (finally (fs/delete-tree root)))))

(deftest write-file-repairs-unbalanced-clojure
  (let [root (str "/tmp/samizdat-files-" (random-uuid))]
    (fs/create-dirs root)
    (try
      (testing "a trailing-truncated Clojure file is closed and noted"
        (let [r (files/write-file (ctx root "write_file"
                                       {:path "trunc.clj" :content "(defn f [] (+ 1 2)"}))]
          (is (= :success (:category r)))
          (is (:repaired? r))
          (is (str/includes? (:result r) "auto-closed"))
          (is (= "(defn f [] (+ 1 2))" (slurp (str root "/trunc.clj"))))))
      (testing "a non-clojure file is written verbatim, no repair"
        (files/write-file (ctx root "write_file" {:path "notes.txt" :content "(unbalanced"}))
        (is (= "(unbalanced" (slurp (str root "/notes.txt")))))
      (testing "a mid-file imbalance is written as-is with a clear warning"
        (let [r (files/write-file (ctx root "write_file"
                                       {:path "mid.clj" :content "(f)) (g)"}))]
          (is (str/includes? (:result r) "does not balance"))
          (is (= "(f)) (g)" (slurp (str root "/mid.clj"))))))
      (finally (fs/delete-tree root)))))

(deftest write-then-read-roundtrips
  (let [root (str "/tmp/samizdat-files-" (random-uuid))]
    (fs/create-dirs root)
    (try
      (files/write-file (ctx root "write_file" {:path "round.clj" :content "(+ 1 2)"}))
      (is (str/includes? (:result (files/read-file (ctx root "read_file" {:path "round.clj"})))
                         "(+ 1 2)"))
      (finally (fs/delete-tree root)))))

(deftest a-long-file-pages-instead-of-dead-ending
  ;; The truncation marker used to be `… [truncated]` and nothing else. Live,
  ;; against a 7KB brief, that had the model read the same file six times
  ;; through four different tools, get the identical first 4014 characters
  ;; every time, and then spend four more turns writing a chunked reader in
  ;; `eval` — ten turns of forty to read the file it was told to start from.
  (let [dir (str (fs/create-temp-dir))
        big (str/join "\n" (map #(str "line " % " " (apply str (repeat 60 \x)))
                                (range 400)))
        _ (spit (str dir "/big.txt") big)
        read #(files/read-file {:branch {:id "B1"} :root dir :args %})
        p1 (:result (read {:path "big.txt"}))]
    (testing "the first page names the call that continues it"
      (is (str/includes? p1 "read_file"))
      (is (str/includes? p1 "offset")))
    (testing "that call returns DIFFERENT content, not the same first page"
      (let [next-line (Integer/parseInt (second (re-find #"\"offset\": (\d+)" p1)))
            p2 (:result (read {:path "big.txt" :offset next-line}))]
        (is (pos? next-line))
        (is (not= p1 p2))
        (is (str/includes? p2 (str "line " next-line " ")))
        (is (not (str/includes? p2 "line 0 ")))))
    (testing "paging reaches the end, where nothing more is offered"
      (let [last-page (:result (read {:path "big.txt" :offset 395}))]
        (is (str/includes? last-page "line 399"))
        (is (not (str/includes? last-page "Continue with")))))
    (testing "limit bounds the page in lines"
      (let [r (:result (read {:path "big.txt" :offset 10 :limit 3}))]
        (is (str/includes? r "line 10 "))
        (is (str/includes? r "line 12 "))
        (is (not (str/includes? r "line 13 ")))))
    (testing "a short file still comes back whole and offers no continuation"
      (spit (str dir "/small.txt") "a\nb\nc")
      (let [r (:result (read {:path "small.txt"}))]
        (is (str/includes? r "c"))
        (is (not (str/includes? r "Continue with")))))))

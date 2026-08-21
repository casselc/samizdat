;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

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

(deftest write-then-read-roundtrips
  (let [root (str "/tmp/samizdat-files-" (random-uuid))]
    (fs/create-dirs root)
    (try
      (files/write-file (ctx root "write_file" {:path "round.clj" :content "(+ 1 2)"}))
      (is (str/includes? (:result (files/read-file (ctx root "read_file" {:path "round.clj"})))
                         "(+ 1 2)"))
      (finally (fs/delete-tree root)))))

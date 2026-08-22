;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.edit-test
  "edit_file: surgical string replacement, ported from dirge's edit tool. Exact
  match first, then a line-trimmed fallback for whitespace drift; ambiguous
  matches are reported with line numbers, not guessed; replace_all splices
  every occurrence; and an edit that unbalances Clojure is flagged."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.files :as files]))

(defmacro with-root [[root] & body]
  `(let [~root (str "/tmp/samizdat-edit-" (random-uuid))]
     (fs/create-dirs ~root)
     (try ~@body (finally (fs/delete-tree ~root)))))

(defn- ctx [root args] {:tool-name "edit_file" :args args :branch {:id "B1"} :root root})

(defn- write [root path content] (spit (str root "/" path) content))
(defn- read* [root path] (slurp (str root "/" path)))

(deftest exact-single-match
  (with-root [root]
    (write root "a.clj" "(defn f [] :old)\n")
    (let [r (files/edit-file (ctx root {:path "a.clj" :old_text ":old" :new_text ":new"}))]
      (is (= :success (:category r)))
      (is (:progress? r))
      (is (= "(defn f [] :new)\n" (read* root "a.clj"))))))

(deftest not-found-is-a-clear-miss
  (with-root [root]
    (write root "a.clj" "(defn f [] 1)\n")
    (let [r (files/edit-file (ctx root {:path "a.clj" :old_text ":absent" :new_text ":x"}))]
      (is (= :mechanics (:category r)))
      (is (str/includes? (:result r) "not found"))
      (is (= "(defn f [] 1)\n" (read* root "a.clj")) "the file is untouched"))))

(deftest ambiguous-match-is-reported-not-guessed
  (with-root [root]
    (write root "a.clj" "(x)\n(x)\n(x)\n")
    (let [r (files/edit-file (ctx root {:path "a.clj" :old_text "(x)" :new_text "(y)"}))]
      (is (= :mechanics (:category r)))
      (is (str/includes? (:result r) "3 times"))
      (is (re-find #"(?m)Line 1" (:result r)))
      (is (re-find #"(?m)Line 3" (:result r)))
      (is (= "(x)\n(x)\n(x)\n" (read* root "a.clj")) "nothing changed on an ambiguous match"))))

(deftest replace-all-splices-every-occurrence
  (with-root [root]
    (write root "a.clj" "(x)\n(x)\n(x)\n")
    (let [r (files/edit-file (ctx root {:path "a.clj" :old_text "(x)" :new_text "(y)"
                                        :replace_all true}))]
      (is (= :success (:category r)))
      (is (= "(y)\n(y)\n(y)\n" (read* root "a.clj"))))))

(deftest line-trimmed-fallback-tolerates-whitespace-drift
  ;; The ~95% case: the model's old_text has different leading/trailing
  ;; whitespace than the file. Exact fails; the line-trimmed fallback matches.
  (with-root [root]
    (write root "a.clj" "(defn f []\n    (let [x 1]\n      x))\n")
    (let [r (files/edit-file (ctx root {:path "a.clj"
                                        ;; model dropped the indentation; the
                                        ;; trimmed lines still match the file
                                        :old_text "(let [x 1]\nx))"
                                        :new_text "(let [x 2]\n      x))"}))]
      (is (= :success (:category r)))
      (is (str/includes? (:result r) "line-trimmed"))
      (is (str/includes? (read* root "a.clj") "(let [x 2]")))))

(deftest an-edit-that-unbalances-clojure-is-flagged
  (with-root [root]
    (write root "a.clj" "(defn f [] (+ 1 2))\n")
    (let [r (files/edit-file (ctx root {:path "a.clj"
                                        :old_text "(+ 1 2))" :new_text "(+ 1 2)"}))]
      ;; removed a closing paren → the file no longer reads
      (is (str/includes? (:result r) "does not balance")))))

(deftest edit-a-missing-or-escaping-file
  (with-root [root]
    (is (= :mechanics (:category (files/edit-file
                                  (ctx root {:path "nope.clj" :old_text "a" :new_text "b"})))))
    (is (= :mechanics (:category (files/edit-file
                                  (ctx root {:path "../escape.clj" :old_text "a" :new_text "b"})))))))

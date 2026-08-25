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

(ns samizdat.lisp-test
  "Delimiter repair for the Clojure the agent writes. Models drop trailing
  parens; this closes a trailing truncation and trims a trailing over-close,
  lexer-aware (strings, char literals, comments don't count), and never
  touches a mid-file imbalance — closing that would silently re-parent code."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.lisp :as lisp]))

(deftest balanced-passes-through
  (doseq [s ["(defn f [] 1)"
             "(ns x)\n(defn f [x] (+ x 1))"
             "(str \"a)b\" \\( \\))"       ; parens in string + char literals
             "(f) ; a trailing ) in a comment )"
             ""]]
    (is (= :balanced (:status (lisp/balance s))) (str "should be balanced: " (pr-str s)))
    (is (= s (:content (lisp/balance s))))))

(deftest closes-a-trailing-truncation
  (testing "one unclosed form gets its closer"
    (let [r (lisp/balance "(defn f [] (+ 1 2)")]
      (is (= :repaired (:status r)))
      (is (= "(defn f [] (+ 1 2))" (:content r)))
      (is (re-find #"(?i)auto-clos" (:note r)))))
  (testing "several nested unclosed delimiters close innermost-first"
    ;; open: (defn … (let [ … (* x 2) ] closes the vec, then (let and (defn
    ;; remain open — a real trailing truncation of two forms.
    (let [r (lisp/balance "(defn f [x]\n  (let [y (* x 2)]")]
      (is (= :repaired (:status r)))
      (is (= "(defn f [x]\n  (let [y (* x 2)]))" (:content r)))
      (is (= :balanced (:status (lisp/balance (:content r)))))))
  (testing "a repaired string actually reads"
    (let [r (lisp/balance "(map inc [1 2 3")]
      (is (= :repaired (:status r)))
      (is (some? (read-string (str "(do " (:content r) ")")))))))

(deftest trims-a-trailing-overclose
  (testing "one extra trailing closer is removed"
    (let [r (lisp/balance "(defn f [] 1))")]
      (is (= :repaired (:status r)))
      (is (= "(defn f [] 1)" (:content r)))
      (is (re-find #"(?i)removed" (:note r)))))
  (testing "several extra trailing closers"
    (let [r (lisp/balance "(inc 1)))")]
      (is (= :repaired (:status r)))
      (is (= "(inc 1)" (:content r))))))

(deftest never-touches-a-mid-file-imbalance
  (testing "a stray closer with real code after it is left for the model"
    (let [r (lisp/balance "(defn f [] 1)) (defn g [] 2)")]
      (is (= :unbalanced (:status r)))
      (is (nil? (:content r)) "no silent rewrite of mid-file code")))
  (testing "a mismatched pair is not auto-repaired"
    (let [r (lisp/balance "(defn f [] (vec [1 2)])")]
      (is (= :unbalanced (:status r))))))

(deftest strings-and-comments-do-not-count
  (testing "an unclosed delimiter INSIDE a string is not a code imbalance"
    (is (= :balanced (:status (lisp/balance "(def s \"an open ( paren\")")))))
  (testing "a delimiter in a line comment is ignored"
    (is (= :balanced (:status (lisp/balance "(def x 1) ; ) ] } trailing junk"))))))

(deftest an-unterminated-string-is-its-own-status
  ;; a#5 (docs/RFCS/RFC-000-index.md): a string whose last quote was ESCAPED used to
  ;; scan as "closed exactly at EOF", so the file read as balanced and
  ;; write_file wrote the broken text with no warning.
  (let [escaped-final-quote (str "\"x " "\\" "\"")   ; "x \"
        escaped-backslash   (str "(def s \"x " "\\" "\\" "\")")] ; (def s "x \")
    (testing "a string ending at an escaped quote is unterminated"
      (is (= :unterminated-string (:balance (lisp/scan escaped-final-quote)))))
    (testing "inside a form it does not silently repair"
      (let [r (lisp/balance (str "(def s " escaped-final-quote))]
        (is (= :unbalanced (:status r)))
        (is (nil? (:content r)) "no rewrite of text with a broken string")))
    (testing "an escaped backslash before a real closing quote still closes"
      (is (= :balanced (:balance (lisp/scan escaped-backslash)))))))

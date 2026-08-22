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

(ns samizdat.repl-test
  "The in-process eval seam: the agent develops REPL-first against the live
  harness image — eval a form, see the value and any output, inspect a var,
  complete a name — the way the plan says development should work."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.repl :as repl]))

(deftest eval-returns-value-and-output
  (testing "a form's value is captured, printed readably"
    (let [r (repl/eval-code "(+ 1 2 3)")]
      (is (:ok r))
      (is (= "6" (:value r)))))
  (testing "stdout is captured alongside the value"
    (let [r (repl/eval-code "(do (println \"side effect\") :done)")]
      (is (:ok r))
      (is (= ":done" (:value r)))
      (is (str/includes? (:out r) "side effect"))))
  (testing "an exception is data, not a thrown error"
    (let [r (repl/eval-code "(/ 1 0)")]
      (is (not (:ok r)))
      (is (str/includes? (:error r) "Divide by zero"))))
  (testing "a read error is reported, not thrown"
    (let [r (repl/eval-code "(+ 1 2")]
      (is (not (:ok r)))
      (is (some? (:error r))))))

(deftest defs-persist-within-a-session
  ;; REPL-first development: define, then use, across calls in one session.
  (let [sess (repl/new-session)]
    (repl/eval-code "(def scratch-x 41)" sess)
    (is (= "42" (:value (repl/eval-code "(inc scratch-x)" sess))))
    (testing "a defn is callable on the next eval"
      (repl/eval-code "(defn scratch-double [n] (* 2 n))" sess)
      (is (= "20" (:value (repl/eval-code "(scratch-double 10)" sess)))))))

(deftest sessions-are-isolated
  (let [a (repl/new-session) b (repl/new-session)]
    (repl/eval-code "(def only-in-a 1)" a)
    (is (:ok (repl/eval-code "only-in-a" a)))
    (is (not (:ok (repl/eval-code "only-in-a" b)))
        "a def in one session is not visible in another")))

(deftest can-reach-the-live-harness-image
  ;; The homoiconic point: the agent evaluates against the running harness, so
  ;; it can inspect and exercise samizdat's own code, not a fresh sandbox.
  (let [r (repl/eval-code "(require '[samizdat.lisp :as l]) (:status (l/balance \"(+ 1 2\"))")]
    (is (:ok r))
    (is (= ":repaired" (:value r)))))

(deftest doc-of-a-project-var
  (testing "arglists and docstring of samizdat's own code"
    (let [r (repl/doc-sym "samizdat.lisp/balance")]
      (is (= '([s]) (:arglists r)))
      (is (str/includes? (:doc r) "delimiter"))))
  (testing "an unknown symbol is reported, not thrown"
    (is (:not-found (repl/doc-sym "samizdat.lisp/nope")))))

(deftest complete-a-prefix
  (testing "public symbols of a namespace matching a prefix"
    (let [ms (repl/complete "samizdat.lisp/b")]
      (is (some #{"samizdat.lisp/balance"} ms))))
  (testing "a bare prefix completes across clojure.core"
    (is (some #{"reduce"} (repl/complete "redu")))))

(deftest eval-is-bounded-so-a-runaway-cannot-hang-the-harness
  ;; An agent's infinite loop or heavy computation used to peg a core and hang
  ;; the harness — eval ran unbounded on a thread the harness waits on. It is
  ;; now bounded; a form that overruns the deadline times out.
  (testing "a form past the deadline times out instead of hanging"
    (let [s (repl/new-session)
          r (repl/eval-code "(Thread/sleep 100000)" s 200)]
      (is (false? (:ok r)))
      (is (= "timeout" (:error-type r)))
      (is (str/includes? (:error r) "timed out"))))
  (testing "a normal form still returns its value"
    (let [s (repl/new-session)
          r (repl/eval-code "(+ 1 2)" s)]
      (is (:ok r))
      (is (= "3" (:value r)))))
  (testing "the agent can raise the timeout for a form that genuinely needs it"
    (let [s (repl/new-session)
          r (repl/eval-code "(do (Thread/sleep 150) :slow-but-ok)" s 3000)]
      (is (:ok r))
      (is (= ":slow-but-ok" (:value r))))))

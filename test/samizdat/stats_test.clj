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
(ns samizdat.stats-test
  "The reliability statistics the arena's summary reads (karamazov-a6mj.5):
  a Beta posterior on k passes of n, its credible interval, the probability
  the pass rate sits in the Goldilocks zone, pass@k and pass^k, and
  P(arm A > arm B). Reference values are scipy's (betainc, betaincinv,
  200-point Gauss–Legendre for P(A>B)), computed once and pinned here, so
  the hand-rolled incomplete beta is held to the library it replaces."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.stats :as stats]))

(defn- close? [expected actual tol]
  (and (number? actual) (< (Math/abs (- (double expected) (double actual))) tol)))

(deftest the-regularized-incomplete-beta-matches-scipy
  (is (close? 0.5248 (stats/betainc 2 3 0.4) 1e-9) "a closed form: I_0.4(2,3)")
  (is (close? 0.5 (stats/betainc 0.5 0.5 0.5) 1e-9) "symmetric at the middle")
  (is (close? 0.4 (stats/betainc 1 1 0.4) 1e-12) "Beta(1,1) is uniform")
  (is (close? 0.10591207961185312 (stats/betainc 20.5 0.5 0.9375) 1e-9)
      "a half-integer with mass piled at 1 — the Jeffreys posterior of 20/20")
  (is (= 0.0 (stats/betainc 2 3 0.0)))
  (is (= 1.0 (stats/betainc 2 3 1.0))))

(deftest the-inverse-recovers-the-quantile
  (is (close? 0.17673609713125732 (stats/betaincinv 2.5 1.5 0.025) 1e-6))
  (is (close? 0.9612523822148348 (stats/betaincinv 2.5 1.5 0.975) 1e-6))
  (is (close? 0.4 (stats/betaincinv 2 3 0.5248) 1e-6) "inverts the closed form above"))

(deftest a-credible-interval-is-honest-at-small-n
  ;; 2 of 3 is not 67%; it is somewhere between a fifth and nearly certain,
  ;; and saying so is the point of reporting the interval.
  (let [[lo hi] (stats/cred-int 2 3)]
    (is (close? 0.1767 lo 1e-3))
    (is (close? 0.9613 hi 1e-3)))
  (let [[lo hi] (stats/cred-int 14 20)]
    (is (close? 0.4828 lo 1e-3))
    (is (close? 0.8639 hi 1e-3)))
  (testing "zero passes is not zero: the Jeffreys prior keeps the upper end open"
    (let [[lo hi] (stats/cred-int 0 3)]
      (is (< lo 0.001))
      (is (close? 0.5356 hi 1e-3)))))

(deftest the-goldilocks-zone-says-when-a-task-carries-signal
  ;; thinkingbox's rule: a test at 0% or 100% carries no signal; trust the
  ;; rate when P(6.25% < p < 93.75% | data) >= 0.95.
  (is (close? 0.1059 (stats/prob-in-zone 20 20) 1e-3) "20/20 — almost surely too easy")
  (is (close? 0.1059 (stats/prob-in-zone 0 20) 1e-3) "0/20 — almost surely too hard (or broken)")
  (is (close? 0.9996 (stats/prob-in-zone 14 20) 1e-3) "14/20 — trustworthy signal")
  (is (close? 0.9480 (stats/prob-in-zone 2 3) 1e-3) "2/3 — just under the bar; run more"))

(deftest pass-at-k-and-pass-power-k
  (testing "pass@k, the unbiased estimator: at least one of k passes"
    (is (close? 0.7 (stats/pass-at-k 20 14 1) 1e-12))
    (is (close? (- 1.0 (/ 6.0 15504.0)) (stats/pass-at-k 20 14 5) 1e-12) "1 - C(6,5)/C(20,5)")
    (is (= 1.0 (stats/pass-at-k 20 19 5)) "fewer failures than k: certain")
    (is (= 0.0 (stats/pass-at-k 20 0 5))))
  (testing "pass^k, deliberately biased: all k pass, (c/n)^k so c < k still differentiates"
    (is (close? (Math/pow 0.7 5) (stats/pass-power-k 20 14 5) 1e-12))
    (is (close? 0.7 (stats/pass-power-k 20 14 1) 1e-12))
    (is (= 0.0 (stats/pass-power-k 0 0 3)) "no runs is no reliability, not an error")))

(deftest the-probability-one-arm-beats-another
  (is (close? 0.9724 (stats/prob-a-gt-b 14 20 8 20) 2e-3) "14/20 against 8/20")
  (is (close? 0.5 (stats/prob-a-gt-b 10 20 10 20) 1e-6) "the same record is a coin flip")
  (is (< (stats/prob-a-gt-b 8 20 14 20) 0.03) "and the reverse comparison agrees"))

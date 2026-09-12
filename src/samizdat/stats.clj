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
(ns samizdat.stats
  "Reliability statistics for a handful of runs (karamazov-a6mj.5): a Beta
  posterior on k passes of n, its credible interval, the probability the pass
  rate sits in the Goldilocks zone, pass@k and pass^k, and P(arm A > arm B).
  thinkingbox's eval_utils.py, without scipy.

  WHY A POSTERIOR AND NOT A P-VALUE. The arena's summary refuses a
  significance test on purpose: at n=3 an accept/reject turning on one run is
  a search trace, not a result. That is right about p-values and wrong about
  a posterior — a 95% credible interval on 2 of 3 is honest about being
  [0.18, 0.96], which is the point. Report the interval; let a person read it.

  MECHANISM ONLY. Pure arithmetic, no opinion about which column is a pass.
  Jeffreys prior Beta(1/2, 1/2) throughout, as thinkingbox's: it keeps the
  upper end of 0-of-n open and the lower end of n-of-n, which a flat prior
  understates and a point estimate loses altogether.

  The regularized incomplete beta is Numerical Recipes' betacf (a modified
  Lentz continued fraction) behind the symmetry that keeps it convergent;
  lgamma is the Lanczos approximation. Held to scipy in stats-test.")

;; --- special functions ------------------------------------------------------

(def ^:private lanczos-coefficients
  [76.18009172947146 -86.50532032941677 24.01409824083091
   -1.231739572450155 0.1208650973866179e-2 -0.5395239384953e-5])

(defn lgamma
  "ln Γ(x) for x > 0, Lanczos (g = 5, n = 6): relative error under 2e-10."
  [x]
  (let [x (double x)
        y x
        tmp (+ x 5.5)
        tmp (- tmp (* (+ x 0.5) (Math/log tmp)))
        ser (loop [i 0 y (inc y) ser 1.000000000190015]
              (if (< i 6)
                (recur (inc i) (inc y) (+ ser (/ (nth lanczos-coefficients i) y)))
                ser))]
    (+ (- tmp) (Math/log (/ (* 2.5066282746310005 ser) x)))))

(defn- betacf
  "The continued fraction for the incomplete beta, evaluated by the modified
  Lentz method. Converges quickly for x < (a+1)/(a+b+2); `betainc` uses the
  symmetry relation to keep it there."
  [a b x]
  (let [tiny 1e-30
        qab (+ a b) qap (inc a) qam (dec a)
        d0 (- 1.0 (/ (* qab x) qap))
        d0 (/ 1.0 (if (< (Math/abs d0) tiny) tiny d0))]
    (loop [m 1 c 1.0 d d0 h d0]
      (let [m2 (* 2 m)
            aa (/ (* m (- b m) x) (* (+ qam m2) (+ a m2)))
            d (+ 1.0 (* aa d)) d (if (< (Math/abs d) tiny) tiny d)
            c (+ 1.0 (/ aa c)) c (if (< (Math/abs c) tiny) tiny c)
            d (/ 1.0 d)
            h (* h d c)
            aa (/ (* (- (+ a m)) (+ qab m) x) (* (+ a m2) (+ qap m2)))
            d (+ 1.0 (* aa d)) d (if (< (Math/abs d) tiny) tiny d)
            c (+ 1.0 (/ aa c)) c (if (< (Math/abs c) tiny) tiny c)
            d (/ 1.0 d)
            del (* d c)
            h (* h del)]
        (if (or (< (Math/abs (- del 1.0)) 3e-14) (> m 300))
          h
          (recur (inc m) c d h))))))

(defn betainc
  "The regularized incomplete beta I_x(a, b) = P(X <= x) for X ~ Beta(a, b)."
  [a b x]
  (let [a (double a) b (double b) x (double x)]
    (cond
      (<= x 0.0) 0.0
      (>= x 1.0) 1.0
      :else
      (let [bt (Math/exp (+ (- (lgamma (+ a b)) (lgamma a) (lgamma b))
                            (* a (Math/log x))
                            (* b (Math/log (- 1.0 x)))))]
        (if (< x (/ (inc a) (+ a b 2.0)))
          (/ (* bt (betacf a b x)) a)
          (- 1.0 (/ (* bt (betacf b a (- 1.0 x))) b)))))))

(defn betaincinv
  "The x with I_x(a, b) = p, by bisection: I_x is monotone in x, and sixty
  halvings of [0, 1] put it inside 1e-18 — good enough for a quantile and
  simpler than a Newton step near a singular endpoint."
  [a b p]
  (let [p (double p)]
    (cond
      (<= p 0.0) 0.0
      (>= p 1.0) 1.0
      :else
      (loop [lo 0.0 hi 1.0 i 0]
        (let [mid (/ (+ lo hi) 2.0)]
          (if (>= i 60)
            mid
            (if (< (betainc a b mid) p)
              (recur mid hi (inc i))
              (recur lo mid (inc i)))))))))

;; --- the posterior ----------------------------------------------------------

(defn beta-post-params
  "The Beta posterior after k passes of n under a Beta(a0, b0) prior —
  Jeffreys, 1/2 and 1/2, by default. Throws on k outside [0, n]."
  ([k n] (beta-post-params k n 0.5 0.5))
  ([k n a0 b0]
   (when-not (<= 0 k n)
     (throw (ex-info "beta-post-params needs 0 <= k <= n" {:k k :n n})))
   [(+ a0 k) (+ b0 (- n k))]))

(defn cred-int
  "The central credible interval on the pass rate after k of n, at `level`
  (0.95 by default): [low high]."
  ([k n] (cred-int k n 0.95))
  ([k n level]
   (let [[a b] (beta-post-params k n)
         lo (/ (- 1.0 level) 2.0)]
     [(betaincinv a b lo) (betaincinv a b (- 1.0 lo))])))

(defn prob-in-zone
  "P(lower < p < upper | k of n): the probability the true pass rate sits in
  the Goldilocks zone, 6.25%..93.75% by default. thinkingbox trusts a task's
  rate to carry signal at 0.95 and above; at 0% or 100% a task says nothing."
  ([k n] (prob-in-zone k n 0.0625 0.9375))
  ([k n lower upper]
   (let [[a b] (beta-post-params k n)]
     (- (betainc a b upper) (betainc a b lower)))))

;; --- pass@k, pass^k ---------------------------------------------------------

(defn pass-at-k
  "The unbiased pass@k: the probability at least one of k draws from n runs
  with c passes is a pass, 1 - C(n-c, k)/C(n, k), as a running product so
  the binomials never overflow."
  [n c k]
  (cond
    (or (zero? n) (zero? k)) 0.0
    (< (- n c) k) 1.0
    :else (- 1.0 (reduce * 1.0 (map #(- 1.0 (/ (double k) %))
                                    (range (inc (- n c)) (inc n)))))))

(defn pass-power-k
  "pass^k: the probability all k independent runs pass, (c/n)^k. The BIASED
  estimator on purpose, as thinkingbox's: the unbiased C(c,k)/C(n,k) is zero
  whenever c < k and tells the hard cases apart from each other not at all.
  0.0 with no runs — no reliability, not an error."
  [n c k]
  (if (zero? n) 0.0 (Math/pow (/ (double c) n) k)))

;; --- one arm against another -------------------------------------------------

(defn- gauss-legendre
  "Nodes and weights of the n-point Gauss–Legendre rule on [-1, 1], by
  Newton on the Legendre recurrence (Numerical Recipes gauleg)."
  [n]
  (let [m (quot (inc n) 2)
        eps 3e-14]
    (loop [i 0 xs [] ws []]
      (if (>= i m)
        ;; mirror to the full rule
        (let [xs-all (vec (concat (map - xs) (reverse (if (odd? n) (rest (reverse xs)) xs))))
              ws-all (vec (concat ws (reverse (if (odd? n) (rest (reverse ws)) ws))))]
          [xs-all ws-all])
        (let [z0 (Math/cos (/ (* Math/PI (+ i 0.75)) (+ n 0.5)))
              [z pp] (loop [z z0]
                       (let [[p1 p2] (loop [j 1 p1 1.0 p2 0.0]
                                       (if (> j n)
                                         [p1 p2]
                                         (recur (inc j)
                                                (/ (- (* (dec (* 2 j)) z p1) (* (dec j) p2)) j)
                                                p1)))
                             pp (/ (* n (- (* z p1) p2)) (- (* z z) 1.0))
                             z1 (- z (/ p1 pp))]
                         (if (< (Math/abs (- z1 z)) eps)
                           [z1 pp]
                           (recur z1))))]
          (recur (inc i) (conj xs z) (conj ws (/ 2.0 (* (- 1.0 (* z z)) pp pp)))))))))

(def ^:private gl-200 (delay (gauss-legendre 200)))

(defn- beta-pdf [x a b]
  (Math/exp (- (+ (* (dec a) (Math/log x)) (* (dec b) (Math/log (- 1.0 x))))
               (- (+ (lgamma a) (lgamma b)) (lgamma (+ a b))))))

(defn prob-a-gt-b
  "P(p_A > p_B | kA of nA, kB of nB) = ∫₀¹ f_A(x) F_B(x) dx, by 200-point
  Gauss–Legendre on [0, 1] — thinkingbox's rule to the point. The nodes stay
  off the endpoints, where a Jeffreys posterior with k = 0 or k = n is
  singular but integrable; the error that leaves is well under what a sweep
  of this size can resolve."
  [kA nA kB nB]
  (let [[aA bA] (beta-post-params kA nA)
        [aB bB] (beta-post-params kB nB)
        [xs ws] @gl-200]
    (reduce + 0.0
            (map (fn [x w]
                   (let [t (/ (inc x) 2.0)]
                     (* (/ w 2.0) (beta-pdf t aA bA) (betainc aB bB t))))
                 xs ws))))

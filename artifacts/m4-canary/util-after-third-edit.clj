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

(ns samizdat.util
  "Miscellaneous utilities."
  (:require [clojure.string :as str]))

(defmacro defn-memo
  "Define a memoized function."
  [name & body]
  `(def ~name (memoize (fn ~@body))))

(defn-memo memoize-by-generation
  "Return a function that caches the result of `f` for generation `g`."
  [f]
  (let [cache (atom {:gen 0 :val nil})]
    (fn [g]
      (let [c @cache]
        (if (= g (:gen c))
          (:val c)
          (:val (reset! cache {:gen g :val (f)})))))))

(defn truncate-middle
  "If `s` is longer than `max-len`, return it shortened to at most `max-len`
  characters by keeping the head and tail with the literal string " … "
  in the middle; otherwise return `s` unchanged.  When `max-len` is too small
  to hold the marker, return the first `max-len` characters instead."
  [s max-len]
  (let [n (count s)]
    (if (<= n max-len)
      s
      (let [marker " … "
            marker-len (count marker)
            room (- max-len marker-len)]
        (if (neg? room)
          (subs s 0 (max 0 max-len))
          (let [head-len (quot room 2)
                tail-len (- room head-len)]
            (str (subs s 0 head-len) marker (subs s (- n tail-len))))))))

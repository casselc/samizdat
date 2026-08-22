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
  "Small pure helpers shared across the codebase.

  The first code samizdat wrote about itself: truncate-middle was authored by
  the harness in a supervised self-modification run, then wired into the shell
  tool's output truncation.")

(defn truncate-middle
  "If `s` is longer than `max-len`, return it shortened to exactly `max-len`
  characters by keeping the head and tail with the literal string \" … \"
  in the middle; otherwise return `s` unchanged."
  [s max-len]
  (if (<= (count s) max-len)
    s
    (let [marker " … "
          marker-len (count marker)
          head-len (quot (- max-len marker-len) 2)
          tail-len (- max-len marker-len head-len)]
      (str (subs s 0 head-len) marker (subs s (- (count s) tail-len))))))

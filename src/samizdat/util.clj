;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

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

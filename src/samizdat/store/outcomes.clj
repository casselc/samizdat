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

(ns samizdat.store.outcomes
  "How a run can END, as far as a standing record is concerned — one
  vocabulary shared by the two records that accrue it: a workflow's row in
  `knowledge` (what select reads to choose how a run drives itself) and a
  project-authored `userspace` version (what the next supervisor reads
  before reverting a tuning).

  :shipped and :failed are evidence about the thing whose record it is.
  :error is a run the harness could not finish — a crash, an outage — which
  is evidence about the harness and must be filed as neither of the others.
  Both records used to have two buckets, and the beam's catch path wrote a
  Throwable into the second, so a provider outage taught the chooser that
  the manifest fails this project and taught the supervisor that the current
  tuning fails runs (karamazov-a6mj.1). It still has to be written down —
  five crashes reading as \"no runs\" taught nothing (blt.38) — so it has a
  counter of its own.

  Its own namespace, and a tiny one, because both stores need it and neither
  should require the other.")

(def outcomes #{:shipped :failed :error})

(defn column
  "The counter an outcome bumps. Throws on anything outside `outcomes`: a
  misspelt outcome silently counted as a failure is exactly the miscount this
  vocabulary exists to prevent."
  [outcome]
  (case outcome
    :shipped "success_count"
    :failed "failure_count"
    :error "error_count"
    (throw (ex-info (str "unknown run outcome " (pr-str outcome)
                         "; one of " (pr-str outcomes))
                    {:outcome outcome}))))

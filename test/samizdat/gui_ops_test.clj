;; samizdat - a claim-first verification harness
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

(ns samizdat.gui-ops-test
  "The decision layer the gtk-coupled gui.core wiring consumes. Pure on
  purpose: glimmer/GTK deps live only under the :gui alias so the suite
  stays headless — these fns take maps and ids, never widgets."
  (:require [clojure.test :refer [deftest is]]
            [samizdat.gui.ops :as ops]))

(deftest scroll-props-reset-only-on-selection-change
  ;; review2 #6: :scroll-top in the scrolled props on every render resets
  ;; the adjustment (glimmer-gtk applies the prop whenever present — the
  ;; value is ignored), yanking a long turn log back to the top on every
  ;; poller batch. The prop belongs only to the render after a change.
  (is (contains? (ops/scroll-props nil "A") :scroll-top)
      "a first selection starts the fresh content at the top")
  (is (not (contains? (ops/scroll-props "A" "A") :scroll-top))
      "a poller batch or hover on the same selection keeps the reader's place")
  (is (contains? (ops/scroll-props "A" "B") :scroll-top)
      "selecting a different node scrolls again")
  (is (= "B" (:scroll-top (ops/scroll-props "A" "B")))))

(deftest a-selection-that-lost-the-fetch-race-is-refetched
  ;; review2 #7: when the single-flight CAS failed, the new selection's
  ;; fetch was discarded; the in-flight fetch discarded its own result
  ;; because the selection moved; nothing re-triggered on a finished run.
  ;; The completing fetch must re-run for a pending DIFFERENT selection —
  ;; and must not re-run for the same one, or the busy guard's coalescing
  ;; (one fetch per branch per poller burst) regresses.
  (is (true? (ops/refetch-after? "B" "A")) "a different pending selection refetches")
  (is (false? (ops/refetch-after? "A" "A")) "the same selection stays coalesced")
  (is (false? (ops/refetch-after? nil "A")) "no pending selection, no refetch"))

(deftest an-empty-run-list-means-unknown-not-none
  ;; review2 #8: list-runs answers {:ok false} (not a throw) on a dead or
  ;; busy server, and the empty list used to read as "no runs" —
  ;; disconnecting the tailed run, worst case right after start-new-run!.
  ;; nil means "keep tailing whatever is tailed".
  (is (nil? (ops/tail-target "r1" []))
      "a failed fetch never disconnects the tailed run")
  (is (nil? (ops/tail-target "r1" [{:id "r2"} {:id "r1"}]))
      "a still-listed run keeps its tail")
  (is (= "r2" (ops/tail-target "r1" [{:id "r2"} {:id "r3"}]))
      "a vanished run falls back to the newest")
  (is (= "r2" (ops/tail-target nil [{:id "r2"}]))
      "no selection yet tails the newest run"))

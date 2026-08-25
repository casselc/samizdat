;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.phases
  "The branch phase machine, result transitions, and winner rubric from
  resources/phases.edn (drg-4026 #3/#30/#34).

  Same shape as gates.clj's config machinery — io/resource so the path works
  interpreted and inside an AOT binary, cached, reloadable — but a separate
  namespace for the same reason wordlists.clj is one: state.clj sits BELOW
  gates in the require graph (gates requires state) and cannot read its
  accessor, while the phase table and the winner rubric are consumed down
  there. system.clj calls reload! on every start."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- load-phases
  []
  (edn/read-string (slurp (io/resource "phases.edn"))))

(def ^:private cache (atom (load-phases)))

;; Watched by the winner rubric, which state.clj COMPILES from :finished-key.
;; Without it reload! swapped this atom and left the compiled rubric frozen at
;; namespace load — the one part of phases.edn that is forms rather than
;; lookups was the one part a reload could not reach.
(def ^:private generation (atom 0))

(defn gen
  "The phase table's generation. Derived tables cache against this."
  []
  @generation)

(defn reload! []
  (reset! cache (load-phases))
  (swap! generation inc)
  nil)

(defn table
  "The whole phases.edn map — :initial-phase, :phases, :transitions,
  :finished-key."
  []
  @cache)

(defn initial-phase
  "The phase a new branch starts in (:initial-phase)."
  []
  (:initial-phase @cache))

(defn phase
  "The phase table entry for `p` — {:cap-key ... :next ... :withholds ...}
  or nil for an unknown phase."
  [p]
  (get-in @cache [:phases p]))

(defn next-phase
  "The phase that follows `p` per the table, or nil when it has none."
  [p]
  (:next (phase p)))

(defn withholds
  "The tool names phase `p` withholds (base/phase-refusal consults this)."
  [p]
  (:withholds (phase p)))

(defn transitions
  "The result-signal transitions: get-in paths into the turn envelope,
  each mapping to the effect names loop.clj applies."
  []
  (:transitions @cache))

(defn finished-key-forms
  "The winner-rubric component forms, compiled by state.clj at load."
  []
  (:finished-key @cache))

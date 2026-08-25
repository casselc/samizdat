;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.wordlists
  "Curated wordlists from resources/wordlists.edn.

  Same shape as gates.clj's config machinery — read through io/resource so
  the path works interpreted and inside an AOT binary, cached, reloadable —
  but a separate namespace because state.clj sits BELOW gates in the require
  graph (gates requires state) and cannot read its accessor. The relevance
  guard's stopwords live here for that reason (tier 1c).

  A wordlist is data a run can retune at runtime: the two gates that read
  these lists treat a listed word as carrying no specific claim, so widening
  a list weakens its gate. The comments recording why each section exists
  live beside the words in the resource file."
  (:require [clojure.edn :as edn]
            [samizdat.userspace :as userspace]
            [clojure.java.io :as io]))

(defn- load-wordlists
  []
  (userspace/edn-body! :policy "wordlists"))

(def ^:private cache (atom (load-wordlists)))

;; Watched by ship.clj, which turns :answer-framing into a set and
;; :tool-version into a compiled Pattern at namespace load. Without a
;; generation those two were frozen and reload! moved nothing that mattered.
(def ^:private generation (atom 0))

(defn gen
  "The wordlists' generation. Derived values cache against this."
  []
  @generation)

(defn reload! []
  (reset! cache (load-wordlists))
  (swap! generation inc)
  nil)

(defn wordlist
  "Wordlist `k` (:claim-relevance, :answer-framing, :tool-version) from
  wordlists.edn. The first two are sets; :tool-version is a regex PATTERN
  STRING — re-pattern it at the use site."
  [k]
  (get @cache k))

;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.lexicon
  "The policy accessor for namespaces that cannot reach `gates`.

  Same shape as gates.clj's config machinery — read through the userspace
  seam so a project's own version wins, cached, reloadable — but a separate
  namespace, and deliberately at the BOTTOM of the require graph: it depends
  on nothing but the reader and `samizdat.userspace`. That is what lets a
  store, a tool and a cell all read policy from it. `gates` requires `state`,
  so state.clj cannot read gates' accessor, and a store namespace reaching
  up into `agent` to find one would invert the layering (RFC-009: storage
  decides nothing). This namespace is the seam both cases needed.

  It was `samizdat.agent.wordlists` and held only the relevance guard's
  stopwords. The `agent` segment was already a fiction — nothing about it is
  agent-specific — and it became a problem the moment the claim-matching
  PARAMETERS moved out of the base alongside the words they are applied with.

  What it serves is resources/wordlists.edn: curated vocabularies, and the
  numbers that decide how those vocabularies are matched. Both are data a run
  can retune at runtime, and the gates that read them treat a listed word as
  carrying no specific claim — so widening a list, or lowering a minimum,
  weakens its gate. The comments recording why each section exists live
  beside the values in the resource file."
  (:require [samizdat.userspace :as userspace]))

(defn- load-policy
  []
  (userspace/edn-body! :policy "wordlists"))

(def ^:private cache (atom (load-policy)))

;; Watched by ship.clj, which turns :answer-framing into a set and
;; :tool-version into a compiled Pattern at namespace load. Without a
;; generation those two were frozen and reload! moved nothing that mattered.
(def ^:private generation (atom 0))

(defn gen
  "The wordlists' generation. Derived values cache against this."
  []
  @generation)

(defn reload! []
  (reset! cache (load-policy))
  (swap! generation inc)
  nil)

(defn wordlist
  "Wordlist `k` (:claim-relevance, :answer-framing, :tool-version) from
  wordlists.edn. The first two are sets; :tool-version is a regex PATTERN
  STRING — re-pattern it at the use site."
  [k]
  (get @cache k))

(defn policy
  "One gates.edn section's `:value` — the policy table, read from below.

  `gates/threshold` is the same read, but `gates` requires `state` and
  `supervisor`, so every namespace beneath those is shut out of the table
  that holds their own numbers. That is how the numbers ended up compiled
  into them. This is a READ of the same resource, not a second copy: there is
  still exactly one place each value is written down.

  Uncached here on purpose — `samizdat.userspace` already caches the parsed
  body and drops that cache on any write, so an edit takes effect without
  this namespace needing a generation of its own."
  [k]
  (let [table (userspace/edn-body! :policy "gates")]
    (when-not (contains? table k)
      (throw (ex-info (str "gates.edn has no " k)
                      {:key k :available (sort (keys table))})))
    (get-in table [k :value])))

(defn budget
  "One `:context-budget` entry — how much of something the model gets to see.

  Throws on an absent key, for the reason `tuning` does: a cap that silently
  fell back to a compiled default would put the value back in the base, which
  is the thing moving it out was for."
  [field]
  (let [budgets (policy :context-budget)]
    (when-not (contains? budgets field)
      (throw (ex-info (str "gates.edn :context-budget has no " field)
                      {:field field :available (sort (keys budgets))})))
    (get budgets field)))

(defn tuning
  "One number from a `k` section's tuning map, e.g.
  `(tuning :claim-matching :min-token-length)`.

  Throws rather than defaulting when the key is absent. A matching parameter
  that silently fell back to a compiled default would put the value back in
  the base — which is the thing moving it out was for — and it would do it
  invisibly, at the one moment somebody was editing the resource and watching
  for a change."
  [k field]
  (let [section (get @cache k)]
    (when-not (contains? section field)
      (throw (ex-info (str "wordlists.edn " k " has no " field)
                      {:section k :field field :available (keys section)})))
    (get section field)))

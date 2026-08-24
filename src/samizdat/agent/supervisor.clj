;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.supervisor
  "Watching a branch's own turn pattern for trouble the failure- and
  artifact-keyed gates miss, and the correction for it.

  The failure gates (stuck) key on errors; the progress gate keys on confirmed
  artifacts. A run that INSPECTS without shipping — reads, greps, evals for turn
  after turn, all succeeding, producing nothing — trips neither, and a live run
  did exactly that (karamazov-j5t): it re-read the same file twenty turns
  running and had to be steered by hand.

  This namespace is the seat of a SUPERVISORY ROLE. Today a single gate reads
  over-studying? each turn; the same primitives are what the multi-agent
  orchestrator's supervisor sub-loop (karamazov-ioo.hc7) will read to watch a
  worker — so the detection and the correction live here, apart from any one
  consumer, and grow as the role does."
  (:require [clojure.string :as str]))

;; The shipping vocabulary lives in gates.edn (:tool-vocab :shipping, tier
;; 1a) and is passed in by the caller, keeping this namespace pure and free
;; of the config layer. Shipping tools change or run something — real
;; movement toward finishing; anything else counts as studying.

(defn shipped? [tools entry] (contains? tools (:tool entry)))

(defn over-studying?
  "The branch shipped something at some point, then spent the last `threshold`
  turns only inspecting. The arm-after-shipping guard is deliberate: a run's
  opening exploration — reading its way into an unfamiliar area — is never
  nagged; the stall is when a branch that WAS making changes has lapsed into
  reading and stopped moving."
  [tools turns threshold]
  (let [turns (vec turns)]
    (and (>= (count turns) threshold)
         (some (partial shipped? tools) turns)
         (not-any? (partial shipped? tools) (take-last threshold turns)))))

(defn recent-studying-tools
  "The distinct inspection tools the branch has leaned on in its last
  `threshold` turns — named back to it so the nudge is concrete."
  [tools turns threshold]
  (->> (take-last threshold turns) (keep :tool) (remove tools) distinct vec))

(defn stall-nudge
  "The correction: stop studying, commit and test. Names the tools it has been
  cycling so the message is not generic."
  [tools turns threshold]
  (str "You have spent " threshold " turns inspecting ("
       (str/join ", " (recent-studying-tools tools turns threshold))
       ") without changing anything. Commit your best current version to a file"
       " and test it — a rough version you refine beats more reading. If you were"
       " re-checking something already done, it is done: move to the next step"
       " or finish."))

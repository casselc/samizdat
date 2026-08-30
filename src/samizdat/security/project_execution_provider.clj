;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.project-execution-provider
  "Trusted controller selection of the MODEL-AUTHORIZED project execution
  environment.

  The sibling of samizdat.security.verification-provider, and deliberately a
  separate namespace rather than a mode of it. The two selectors answer
  different questions — 'where does the controller's acceptance gate run' and
  'where does the model's development execution run' — and a single selector
  answering both would be one edit away from letting one answer decide the
  other. RFC-012 §8: shared mechanism, separate authority.

  ONE PROVIDER TODAY. `:smolvm` — the ephemeral-machine environment over a
  read-only mount of the authoritative root. A bwrap development provider is
  imaginable and is explicitly NOT built in JS2: a second provider is an
  optimization, and this milestone is about whether the hard boundary works
  at all.

  WHICH one a run uses is settled from CODE (the standing default) and the
  HARNESS PROCESS'S OWN environment (SAMIZDAT_PROJECT_EXEC_ENV) — never from
  gates.edn or any resources/*.edn, because those are runtime-mutable by the
  very tier this boundary contains, and an execution provider the contained
  party can rewrite is a boundary it can step around.

  FAIL CLOSED, BOTH WAYS. An unavailable provider refuses `project/run` with
  its own catalogued reason; it never falls back to a host process, a shell,
  a direct toolchain spawn, ordinary Samizdat execution, or the bwrap
  VERIFY sandbox — a fallback is a different isolation than the controller
  chose, which is the whole thing being tested. An UNRECOGNIZED selection
  name is itself a refusal (:unknown-provider): a typo in controller
  configuration must stop the lane, not quietly run the default."
  (:require [clojure.string :as str]
            [samizdat.security.smolvm-project-env :as spe]))

(def selection-env
  "The trusted-controller environment variable that names the provider."
  "SAMIZDAT_PROJECT_EXEC_ENV")

(def providers
  "The project execution environments `project/run` can run in, by keyword."
  #{:smolvm})

(defn selected
  "The trusted controller's choice: :smolvm (the standing default, code)
  unless SAMIZDAT_PROJECT_EXEC_ENV names a known provider. An unrecognized
  name returns ::unknown, which every caller treats as a refusal and never
  as the default."
  []
  (let [name (some-> (System/getenv selection-env)
                     str/trim not-empty str/lower-case keyword)]
    (cond
      (nil? name) :smolvm
      (contains? providers name) name
      :else ::unknown)))

(defn available?
  "Whether the SELECTED provider can execute on this host."
  []
  (case (selected)
    :smolvm (spe/available?)
    false))

(defn unavailable-reason
  "Why the selected provider cannot execute here. An unrecognized selection
  is its own reason, spelled as a keyword like the providers' own are."
  []
  (case (selected)
    :smolvm (spe/unavailable-reason)
    :unknown-provider))

(defn validate-request
  "The model's `(project/run argv)` / `(project/run argv options)` arguments
  as the selected provider's validated request. Throws on an invalid request
  — before any staging, and having launched nothing."
  [argv options]
  (case (selected)
    :smolvm (spe/validate-request argv options)
    (throw (ex-info "No project execution provider is selected"
                    {:samizdat.project-execution/error :unknown-provider}))))

(defn run
  "Execute one validated request against `root` in the selected provider, and
  return its canonical structured result. Never throws for a run's own
  outcome."
  [root request]
  (case (selected)
    :smolvm (spe/run root request)
    {:status :refused
     :reason :unknown-provider}))

(defn coordinate
  "The selected provider's full-policy coordinate."
  []
  (case (selected)
    :smolvm (spe/coordinate)
    "unselected-provider"))

(defn environment-coordinate
  "The selected provider's canonical environment description coordinate — the
  value a run's attribution carries."
  []
  (case (selected)
    :smolvm (spe/environment-coordinate)
    nil))

(defn describe-envelope
  "The selected provider's SPI describe envelope (RFC-012)."
  []
  (case (selected)
    :smolvm (spe/describe-envelope)
    nil))

(defn availability-envelope []
  (case (selected)
    :smolvm (spe/availability-envelope)
    nil))

(defn run-envelope
  "One result as the SPI run envelope; nil when the result is not a run.

  DEVELOPMENT evidence. Nothing that decides completion reads it, and it is
  journalled and compared under keys that cannot be confused with the verify
  provider's."
  [result]
  (case (selected)
    :smolvm (spe/run-envelope result)
    nil))

(defn invocation-count
  "How many real machine executions the selected provider has attempted in
  THIS process. Process-local by construction: a resumed run starts a new
  process at zero, which is precisely what makes 'replay launched nothing' a
  checkable claim rather than a hopeful one."
  []
  (case (selected)
    :smolvm (spe/invocation-count)
    0))

(defn poisoned?
  "Whether a prior timeout left the selected provider unusable until its hard
  cleanup completes."
  []
  (case (selected)
    :smolvm (spe/poisoned?)
    false))

;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.verification-provider
  "Trusted controller selection between the bounded lane's verify
  environments.

  Two controller-owned, verify-only environments now exist, both speaking
  the same ExecutionEnvironment EDN SPI (RFC-012) and the same run shape
  the bounded `done` gate judges:

    - :bwrap  samizdat.security.verification-env — the fail-closed
              bubblewrap sandbox over a PRIVATE COPY of the root;
    - :smolvm samizdat.security.smolvm-verification-env — the fail-closed
              ephemeral-machine environment ported from bbagent's proven
              execution substrate, over a read-only mount of the root.

  WHICH one a run uses is controller policy, settled exactly the way each
  environment's own pinned authority is settled: from CODE (the standing
  default) and the HARNESS PROCESS'S OWN environment
  (SAMIZDAT_VERIFY_ENV=bwrap|smolvm) — never from gates.edn or any other
  resources/*.edn data, because those files are runtime-mutable by the very
  tier this gate judges, and a provider selection the judged party can
  rewrite is a sandbox it can switch. The environment variable is read by
  the trusted controller that owns the process, not by anything the model
  can reach.

  FAIL CLOSED, BOTH WAYS. A host whose selected provider is unavailable
  refuses bounded `done` with that provider's own catalogued reason — it
  never falls back to the other provider, because a silent fallback is a
  different sandbox than the one the controller chose, and it never falls
  back to a host spawn at all. An UNRECOGNIZED selection name is itself a
  refusal (:unknown-provider): a typo in controller configuration must stop
  the lane, not quietly run the default.

  Dispatch is through the provider namespaces' own Vars (never a resolved
  symbol table), so the existing tests that redef
  samizdat.security.verification-env's functions keep steering the default
  selection exactly as before."
  (:require [clojure.string :as str]
            [samizdat.prompt :as prompt]
            [samizdat.security.smolvm-verification-env :as smve]
            [samizdat.security.verification-env :as bve]))

(def selection-env
  "The trusted-controller environment variable that names the provider."
  "SAMIZDAT_VERIFY_ENV")

(def providers
  "The verify-only environments the bounded lane can run in, by keyword."
  #{:bwrap :smolvm})

(defn selected
  "The trusted controller's choice of verify environment: :bwrap (the
  standing default, code) unless SAMIZDAT_VERIFY_ENV names :bwrap or
  :smolvm. An unrecognized name returns ::unknown — callers treat that as a
  fail-closed refusal, never as the default."
  []
  (let [name (some-> (System/getenv selection-env)
                     str/trim not-empty str/lower-case keyword)]
    (cond
      (nil? name) :bwrap
      (contains? providers name) name
      :else ::unknown)))

(defn available?
  "Whether the SELECTED provider can run on this host. When false, bounded
  `done` refuses — never the other provider, never a host spawn. An
  unrecognized selection is unavailable, full stop."
  []
  (case (selected)
    :bwrap (bve/available?)
    :smolvm (smve/available?)
    false))

(defn unavailable-reason
  "Why the selected provider cannot run here. An unrecognized selection is
  its own reason, spelled as a keyword the same way the providers' reasons
  are."
  []
  (case (selected)
    :bwrap (bve/unavailable-reason)
    :smolvm (smve/unavailable-reason)
    :unknown-provider))

(defn focused-argv
  "The selected provider's verifier argv for `changed` — the controller's
  PINNED authority in either environment, plus the one derived focused
  expression both lanes share. nil when nothing among `changed` is a
  verifiable test."
  [changed]
  (case (selected)
    :bwrap (bve/focused-argv changed)
    :smolvm (smve/focused-argv changed)
    nil))

(defn run
  "Run the selected provider's verification of `changed` under `root`, and
  report the shape the bounded lane judges — {:green? :timeout? :exit
  :output}, plus {:unavailable? true :reason k} when that provider refused."
  [root changed timeout-ms]
  (case (selected)
    :bwrap (bve/run root changed timeout-ms)
    :smolvm (smve/run root changed timeout-ms)
    {:green? false :timeout? false :unavailable? true
     :reason :unknown-provider
     ;; The model-facing sentence is the template's, like every other word
     ;; the model reads; the reason keyword is what the journal keeps.
     :output (prompt/render "bounded-evaluator" {:ve-unavailable true})}))

(defn coordinate
  "The selected provider's full-policy coordinate — the value the journal's
  ship-verify row carries as :verify-env."
  []
  (case (selected)
    :bwrap (bve/coordinate)
    :smolvm (smve/coordinate)
    "unselected-provider"))

(defn describe-envelope
  "The selected provider's SPI describe envelope (RFC-012)."
  []
  (case (selected)
    :bwrap (bve/describe-envelope)
    :smolvm (smve/describe-envelope)
    nil))

(defn verify-envelope
  "One of the selected provider's run results as the SPI run envelope;
  nil when the result is not a run."
  [result]
  (case (selected)
    :bwrap (bve/verify-envelope result)
    :smolvm (smve/verify-envelope result)
    nil))

(defn invocation-count
  "How many verifications the selected provider has attempted this process.
  Real spawns only, in either environment."
  []
  (case (selected)
    :bwrap (bve/invocation-count)
    :smolvm (smve/invocation-count)
    0))

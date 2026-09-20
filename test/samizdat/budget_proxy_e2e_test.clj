;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.budget-proxy-e2e-test
  "The COMPOSITION: a real harness call path, over a real socket, through the real proxy.

  `budget-proxy-routing-test` shows the harness's calls reach the configured endpoint.
  The experiment's own tests show the proxy reserves and clamps whatever arrives. Neither
  proves the two together, and the ceiling depends on exactly that composition.

  So this drives `infer/complete-fn` - the real turn, including its real truncation retry
  - at a proxy standing in front of a recording upstream, and asks the upstream what it
  actually received.

  It needs the experiment's fixture, which lives in the jev-eval work product and is not
  on this repository's path. So it is ENV-GATED: set

      JEV_E2E_PROXY     the proxy's base URL   (no trailing /v1)
      JEV_E2E_UPSTREAM  the recording upstream's base URL

  by running the fixture first:

      python3 -m jev_eval.experiment.e2e_fixture --ledger <path> \\
              --max-attempts 4 --max-output-tokens 4096 \\
              --replies '[[\"thinking\", \"length\"], [\"done\", \"stop\"]]'

  Without them the deftests below do nothing and say so, rather than passing quietly:
  an env-gated test that reports success when it did not run is worse than no test."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [jolt.host]
            [jolt.http-client :as http]
            [samizdat.agent.infer :as infer]
            [samizdat.llm.registry :as registry]))

(defn- env [k]
  (let [v (jolt.host/getenv k)]
    (when-not (or (nil? v) (= "" v)) v)))

(defn- configured? []
  (and (env "JEV_E2E_PROXY") (env "JEV_E2E_UPSTREAM")))

(defn- upstream-state []
  (-> (http/get (str (env "JEV_E2E_UPSTREAM") "/__state")
                {:throw-exceptions false})
      :body
      (json/read-str :key-fn keyword)))

(defn- cfg []
  {:provider :local :base-url (str (env "JEV_E2E_PROXY") "/v1") :model "m"
   :max-tokens 4096 :temperature 0 :max-retries 0
   :timeout-ms 10000 :conn-timeout-ms 5000})

(def ^:private tape
  {:id "B1"
   :messages [{:role "system" :content "sys"}
              {:role "user" :content "## Problem\n\ndo the thing"}]
   :turns []})

(defn- one-turn []
  (let [ctx {:llm-adapter (registry/adapter-for :local) :llm-config (cfg)}]
    ((infer/complete-fn ctx {:journal? false}) tape)))

(deftest the-real-harness-through-the-real-proxy
  ;; ONE ordered scenario, not two tests. They shared a four-attempt ledger and a scripted
  ;; response queue: the truncation half needs the first "length" reply, and the exhaustion
  ;; half consumes the whole reservation. Run in the other order the first could not
  ;; succeed, so what they asserted depended on which ran first - which is not a property
  ;; of the system under test.
  (if-not (configured?)
    (println "[e2e] SKIPPED: set JEV_E2E_PROXY and JEV_E2E_UPSTREAM (see ns docstring)")
    (let [start (:count (upstream-state))]
      (is (zero? start)
          "this scenario owns the fixture: a fresh ledger and a fresh recording upstream,
           so the counts below mean what they say")

      (testing "step 1 - the turn, and its truncation retry, both reach the provider"
        (one-turn)
        (let [{:keys [seen count]} (upstream-state)]
          (is (= 2 count)
              "one turn is TWO requests when the first is truncated, which is why a turn
               cap is not a call bound")
          (testing "and the retry is clamped by the PROXY, not by the harness"
            ;; The harness asks 4096 and doubles to 8192. The fixture's limit is 4096,
            ;; BELOW the doubled ask, so 8192 must not arrive.
            (is (= [4096 4096] (mapv :max_tokens seen))
                "the harness's doubled 8192 came down to the proxy's 4096"))))

      (testing "step 2 - exhaustion stops anything further reaching the provider"
        (dotimes [_ 6] (try (one-turn) (catch Exception _ nil)))
        (let [at-cap (:count (upstream-state))]
          (is (= 4 at-cap)
              "the upstream saw EXACTLY the reservation - not merely 'no more than
               before', which also passes when nothing arrived at all")
          (try (one-turn) (catch Exception _ nil))
          (is (= 4 (:count (upstream-state)))
              "and nothing further arrives however many more turns are attempted"))))))

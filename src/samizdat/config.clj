;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns samizdat.config
  "Runtime configuration, read from the environment once at startup.

  Provider selection mirrors the TypeScript harness: an explicit
  HARNESS_PROVIDER wins, otherwise the first provider whose API key is present.
  In-process GGUF inference is not carried over — point HARNESS_BASE_URL at any
  OpenAI-compatible endpoint (including llama-server) instead."
  (:require [clojure.string :as str]))

(def ^:private providers
  {;; /beta rather than /v1, for prefix completion. A gate that names one tool
   ;; steers by ending the request mid-fence rather than by asking, which
   ;; DeepSeek serves only from the beta endpoint — on /v1 the same request is
   ;; rejected outright ("prefix is only available when using beta api").
   ;; Verified that /beta serves ordinary completions identically, so this is
   ;; not a trade: nothing else about the run changes. The adapter checks the
   ;; URL anyway and simply does not prefill against /v1, so overriding
   ;; HARNESS_BASE_URL back is safe.
   :deepseek {:base-url "https://api.deepseek.com/beta"
              :key-env  "DEEPSEEK_API_KEY"
              ;; deepseek-v4-flash is the development and test model: cheap
              ;; enough to run the beam repeatedly. deepseek-v4-pro is the
              ;; second arm. Note the TypeScript default, deepseek-reasoner,
              ;; is no longer served by the API.
              :model    "deepseek-v4-flash"}
   :glm      {:base-url "https://open.bigmodel.cn/api/paas/v4"
              :key-env  "ZHIPU_API_KEY"
              :model    "glm-5.1"}
   :openai   {:base-url "https://api.openai.com/v1"
              :key-env  "OPENAI_API_KEY"
              :model    "gpt-4o"}
   ;; A local llama-server / vLLM / LM Studio OpenAI-compatible endpoint.
   :local    {:base-url "http://127.0.0.1:8080/v1"
              :key-env  nil
              :model    "local-model"}
   ;; Ollama's NATIVE api, so no /v1 suffix. See llm/adapter/ollama.clj for
   ;; why the native surface rather than Ollama's OpenAI-compatible one.
   :ollama   {:base-url "http://127.0.0.1:11434"
              :key-env  nil
              :model    "qwen3"}})

(defn- env [k] (let [v (jolt.host/getenv k)] (when-not (str/blank? v) v)))

(defn- env-long [k] (some-> (env k) parse-long))

(defn- detect-provider []
  (or (some-> (env "HARNESS_PROVIDER") str/lower-case keyword)
      (first (for [p [:deepseek :glm :openai]
                   :let [ke (:key-env (providers p))]
                   :when (env ke)]
               p))
      :local))

(defn load-config
  "Build the config map. `overrides` is merged last so tests and REPL sessions
  can point at a fake provider or an in-memory database without touching env."
  ([] (load-config nil))
  ([overrides]
   (let [provider (detect-provider)
         defaults (or (providers provider)
                      (throw (ex-info (str "Unknown HARNESS_PROVIDER: " provider)
                                      {:provider provider
                                       :known (keys providers)})))]
     (merge
      ;; 3985 rather than a common port: 3000 is the busiest address on a
      ;; developer machine, and a harness that silently fails to bind (or
      ;; binds where something else already lives) is worse than one on an
      ;; address nothing else wants.
      {:http     {:port (or (env-long "HARNESS_PORT") 3985)}
       :nrepl    {:port (or (env-long "HARNESS_NREPL_PORT")
                            (env-long "JOLT_NREPL_PORT")
                            7888)}
       :db       {:path (or (env "HARNESS_DB") "samizdat.sqlite3")}
       :llm      {:provider    provider
                  :base-url    (or (env "HARNESS_BASE_URL") (:base-url defaults))
                  :api-key     (some-> (:key-env defaults) env)
                  :model       (or (env "HARNESS_MODEL") (:model defaults))
                  ;; Sent only when set — see llm/adapter/openai. Left unset,
                  ;; each model does whatever it does by default, which for
                  ;; deepseek-v4-pro is to think and for deepseek-v4-flash is
                  ;; not to. A run that cares should say so; POST /v1/runs
                  ;; takes reasoning_effort per run and overrides this.
                  :reasoning-effort (env "HARNESS_REASONING_EFFORT")
                  :max-tokens  (or (env-long "HARNESS_MAX_TOKENS") 16384)
                  :temperature 0.7
                  ;; Per-read inactivity bound (SO_RCVTIMEO on the socket).
                  :timeout-ms  (or (env-long "HARNESS_TIMEOUT_MS") 300000)
                  ;; Bound on the TCP handshake alone. Honoured as of
                  ;; http-client v0.0.3; before that a connect to a host that
                  ;; drops SYNs ran to the kernel's retry limit (~75s), which
                  ;; is a whole branch turn spent before the first byte.
                  :conn-timeout-ms (or (env-long "HARNESS_CONN_TIMEOUT_MS")
                                       15000)
                  ;; Total wall-clock bound on one response, across all reads.
                  ;; A peer that trickles a byte every few seconds resets the
                  ;; per-read timer forever, so :timeout-ms alone does not bound
                  ;; the call. Deliberately BELOW the turn deadline (900000) so
                  ;; the HTTP layer gives up first, with a typed exception that
                  ;; unwinds the thread and closes the socket. If the scheduler's
                  ;; deadline fires first it only abandons the branch's turn --
                  ;; the thread stays parked in the read and leaks.
                  :max-response-ms (or (env-long "HARNESS_MAX_RESPONSE_MS") 600000)}
       :run      {:max-turns  (or (env-long "HARNESS_MAX_TURNS") 80)
                  :beam-width (or (env-long "HARNESS_BEAM_WIDTH") 5)
                  ;; Cross-branch sharing of engine-confirmed artifacts. Off by
                  ;; default: shared lemmas may cost the beam its diversity, and
                  ;; whether they earn it is exactly what sweep-widths measures.
                  :share-artifacts? (= "1" (env "HARNESS_SHARE_ARTIFACTS"))
                  ;; Winner-takes-all: the first verified `done` ends the run.
                  ;; Right for a question with one answer, wrong for a research
                  ;; campaign, where it returns the cheapest qualifying result
                  ;; and terminates every other line. Off means a shipped
                  ;; branch goes inactive holding its answer while the rest
                  ;; keep exploring, and the best is ranked at the end.
                  :stop-on-first-done? (not= "0" (or (env "HARNESS_STOP_ON_FIRST_DONE")
                                                     "1"))}}
      overrides))))

(defn redacted
  "The config with the API key masked, for logging and for /health."
  [config]
  (cond-> config
    (get-in config [:llm :api-key])
    (assoc-in [:llm :api-key] "***")))

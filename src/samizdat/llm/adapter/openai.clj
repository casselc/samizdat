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
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.llm.adapter.openai
  "The OpenAI chat-completions family.

  One adapter covers OpenAI, DeepSeek, Zhipu GLM, and any local llama-server
  or vLLM endpoint, because they all speak the same wire format. The
  differences that actually exist are carried as fields on the adapter record
  rather than as separate namespaces, since a subclass whose only content is a
  base URL is not an abstraction.

  The one real variation is where the reasoning stream lives. DeepSeek and GLM
  return `reasoning_content` alongside `content`; others return nothing. The
  field name is configurable and the client folds it into <think> framing so
  the fence parser sees one string either way."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.llm.adapter :as adapter]))

(defn- tool-call->fence
  "A native OpenAI tool_call turned into the harness's text-fence convention, so
  a forced (tool_choice) response parses through exactly the same path a normal
  fenced response does. `arguments` is a JSON string; it is parsed and re-embedded
  as `args` so the downstream fence parser reads one object."
  [tc]
  (let [name (get-in tc [:function :name])
        args (try (json/read-str (str (get-in tc [:function :arguments])))
                  (catch Throwable _ {}))]
    (str "```tool-call\n"
         (json/write-str {:name name :args args})
         "\n```")))

(defn- supports-prefill?
  "Which members of the family continue a flagged trailing assistant message,
  on the endpoint actually configured.

  DeepSeek does, but ONLY from its beta base URL: on /v1 the same request is
  rejected with 'prefix is only available when using beta api', so a prefill
  sent to the wrong endpoint fails the call outright rather than degrading.
  Both halves are checked here so a /v1 config simply does not prefill and
  behaves exactly as it does today.

  Plain OpenAI and a stock local endpoint have no equivalent. Opted into by id
  rather than assumed for the family."
  [provider-id config]
  (and (= :deepseek provider-id)
       (str/includes? (str (:base-url config)) "/beta")))

(defrecord OpenAIAdapter [provider-id label reasoning-key max-tokens-key]
  adapter/Adapter
  (id [_] provider-id)
  (display-name [_] label)

  (chat-url [_ config] (str (:base-url config) "/chat/completions"))

  ;; NOT simply base-url + /models. DeepSeek's /beta is a chat-completions
  ;; variant only — it serves prefix completion, and /beta/models is a 404 —
  ;; so a run configured for prefilling reported "listed no models" at startup
  ;; and downgraded a real check to a warning. The listing lives on the stable
  ;; path either way.
  (models-url [_ config]
    (str (str/replace (str (:base-url config)) #"/beta/?$" "/v1") "/models"))

  (auth-headers [_ config]
    (if-let [k (:api-key config)]
      {"Authorization" (str "Bearer " k)}
      {}))

  (chat-body [_ config {:keys [messages max-tokens temperature prefill force-tool]}]
    (cond-> {:model (:model config)
             :messages (if (and prefill (not force-tool)
                                (supports-prefill? provider-id config))
                         ;; `:prefix true` is what makes the provider CONTINUE
                         ;; this message rather than reply after it. Without
                         ;; the flag a trailing assistant turn is just history,
                         ;; and the model answers below it in prose — the exact
                         ;; failure the prefill exists to prevent.
                         (conj (vec messages)
                               {:role "assistant" :content prefill :prefix true})
                         messages)}
      max-tokens (assoc max-tokens-key max-tokens)
      temperature (assoc :temperature temperature)
      ;; Only when set. Whether a model thinks was previously a property of
      ;; which one happened to be configured — deepseek-v4-pro does by
      ;; default, deepseek-v4-flash does not — rather than something a run
      ;; stated. This makes it explicit and recorded.
      ;;
      ;; `some?` rather than truthiness: "none" is how thinking is turned OFF
      ;; and must reach the provider, and a provider that has never heard of
      ;; the field rejects the request rather than ignoring it, so unset has
      ;; to mean absent.
      (some? (:reasoning-effort config))
      (assoc :reasoning_effort (:reasoning-effort config))

      ;; Force a specific finishing tool with native tool_choice — the
      ;; provider-agnostic way to make the model call `done`/`give_up` (works on
      ;; GLM, which ignores assistant prefill). opencode does the same. Only the
      ;; forced tool is exposed, so the model has no other call to make.
      force-tool
      (assoc :tools [{:type "function" :function force-tool}]
             :tool_choice {:type "function"
                           :function {:name (:name force-tool)}})))

  (prefill-support? [_ config] (supports-prefill? provider-id config))

  (parse-chat [_ body]
    (when-let [choice (first (:choices body))]
      (let [msg (:message choice)]
        {;; A forced tool_choice response carries the call in tool_calls, not
         ;; content — fold it into the fence convention so downstream is blind to
         ;; how the call was produced.
         :content (if-let [tc (first (:tool_calls msg))]
                    (tool-call->fence tc)
                    (:content msg))
         :reasoning (get msg reasoning-key)
         :finish-reason (or (:finish_reason choice) "stop")
         :usage (when-let [u (:usage body)]
                  ;; The cache split is conditional on the provider reporting
                  ;; it, and ABSENT rather than zero when it does not: zero
                  ;; would assert every token missed the cache, which is a
                  ;; different and false claim. The point of keeping these is
                  ;; to reason about cache behaviour across a wide beam, where
                  ;; each branch carries its own diverging prefix, and a
                  ;; fabricated zero would poison exactly that question.
                  (cond-> {:prompt-tokens (or (:prompt_tokens u) 0)
                           :completion-tokens (or (:completion_tokens u) 0)
                           :total-tokens (or (:total_tokens u) 0)}
                    (:prompt_cache_hit_tokens u)
                    (assoc :cache-hit-tokens (:prompt_cache_hit_tokens u))
                    (:prompt_cache_miss_tokens u)
                    (assoc :cache-miss-tokens (:prompt_cache_miss_tokens u))
                    ;; OpenAI reports the hit count nested instead.
                    (get-in u [:prompt_tokens_details :cached_tokens])
                    (assoc :cache-hit-tokens
                           (get-in u [:prompt_tokens_details :cached_tokens]))))})))

  (parse-models [_ body] (mapv :id (:data body)))

  (error-message [_ body]
    (when-let [e (:error body)]
      (str (or (:message e) "unknown error")
           (when-let [c (:code e)] (str " (code " c ")")))))

  (usage-cap? [_ _status body]
    ;; A 429 that means "you are out of credit" must not be retried; a 429 that
    ;; means "slow down" must be. Providers signal the first in the error text
    ;; rather than the status, so this is a text match, and it is deliberately
    ;; narrow: misreading a rate limit as a cap costs the run.
    (let [msg (str (get-in body [:error :message])
                   " " (get-in body [:error :type])
                   " " (get-in body [:error :code]))]
      ;; The English signals cover OpenAI/DeepSeek; GLM/Zhipu report a daily
      ;; usage cap as error code 1308 with a Chinese "使用上限" message, and
      ;; some gateways phrase it "per-day" — a 429 on any of these is spent
      ;; budget, not backpressure, so it must not be retried.
      (boolean (re-find #"(?i)insufficient|quota|billing|exceeded your current|payment|per-day|daily limit|1308|使用上限|每日"
                        msg)))))

(defn openai-family
  "Build an adapter for an OpenAI-compatible endpoint.

  `reasoning-key` names the field carrying a separate reasoning stream, or nil
  when the provider has none. `max-tokens-key` exists because newer OpenAI
  models renamed `max_tokens` to `max_completion_tokens` and reject the old
  one."
  [{:keys [id label reasoning-key max-tokens-key]
    :or {max-tokens-key :max_tokens}}]
  (->OpenAIAdapter id (or label (str/capitalize (name id)))
                   (or reasoning-key :__no_reasoning_field__)
                   max-tokens-key))

(def deepseek
  (openai-family {:id :deepseek :label "DeepSeek" :reasoning-key :reasoning_content}))

(def glm
  (openai-family {:id :glm :label "GLM" :reasoning-key :reasoning_content}))

(def openai
  (openai-family {:id :openai :label "OpenAI"}))

;; A local llama-server / vLLM / LM Studio endpoint. Same wire format, no key.
(def local
  (openai-family {:id :local :label "local"}))

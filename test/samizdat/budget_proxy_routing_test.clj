;; SPDX-License-Identifier: GPL-3.0-or-later
(ns samizdat.budget-proxy-routing-test
  "Does every inference the harness makes leave through ONE configurable endpoint?

  The experiment's budget proxy can only bound what reaches it. It is enforced on the
  wire, so the question here is the harness's half: does every call path - the turn, its
  truncation retry, the critic - resolve its endpoint and its output limit from the ctx's
  `:llm-config`, or does one of them build its own?

  A call site that constructed its own URL, or reached a provider directly, would be
  invisible to the proxy and would spend outside the ceiling. That is the failure mode.

  Recording is at `jolt.http-client/post`, which is the last thing before the socket and
  exactly where the proxy sits in the screen. So these drive the REAL call paths -
  `infer/complete-fn`, `critic/score!` - down to the request that would have been sent,
  rather than asserting on how the code reads."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.http-client :as http]
            [samizdat.agent.critic :as critic]
            [samizdat.agent.infer :as infer]
            [samizdat.llm.registry :as registry]
            [samizdat.store.db :as db]))

(defn- reply-body
  "An OpenAI-shaped answer. `finish` of \"length\" is a truncation, which is what makes
  infer retry at a doubled budget."
  [content finish]
  (json/write-str
   {:id "c" :object "chat.completion" :model "fake"
    :choices [{:index 0 :finish_reason finish
               :message {:role "assistant" :content content}}]
    :usage {:prompt_tokens 7 :completion_tokens 3 :total_tokens 10}}))

(defn- recorder
  "Replaces the HTTP boundary, recording every request that would have gone out.
  `replies` is an atom of a seq of [content finish]; each request consumes one."
  [seen replies]
  (fn [url opts]
    (let [[content finish] (or (first @replies)
                               ["```tool-call\n{\"name\": \"done\", \"args\": {}}\n```" "stop"])]
      (swap! replies rest)
      (swap! seen conj {:url url
                        :body (json/read-str (:body opts) :key-fn keyword)})
      {:status 200 :body (reply-body content finish)})))

(defn- cfg
  "The screen's provider config. `:max-tokens` 4096 is the screen's setting: the
  truncation retry doubles it to exactly 8192, which is why the proxy's per-request
  limit is 8192 and not lower."
  []
  {:provider :local :base-url "http://proxy.invalid/v1" :model "m"
   :max-tokens 4096 :temperature 0 :max-retries 0})

(def ^:private tape
  {:id "B1"
   :messages [{:role "system" :content "sys"}
              {:role "user" :content "## Problem\n\ndo the thing"}]
   :turns []})

;; --- the turn, and its truncation retry -------------------------------------

(deftest the-turn-and-its-retry-both-leave-through-the-configured-endpoint
  (let [seen (atom [])
        replies (atom [["thinking, no call yet" "length"]      ; truncated -> retry
                       ["```tool-call\n{\"name\": \"done\", \"args\": {}}\n```" "stop"]])]
    (with-redefs [http/post (recorder seen replies)]
      (let [ctx {:llm-adapter (registry/adapter-for :local) :llm-config (cfg)}
            complete (infer/complete-fn ctx {:journal? false})]
        (complete tape)))
    (is (= 2 (count @seen))
        "the truncation retry is a SECOND request on the wire, so it is a second attempt
         against any ceiling enforced there - one turn is not one call")
    (is (every? #(= "http://proxy.invalid/v1/chat/completions" (:url %)) @seen)
        "both went to the endpoint the config names; no call site built its own")
    (testing "Q13: the retry doubles 4096 to exactly 8192"
      (is (= [4096 8192] (mapv #(get-in % [:body :max_tokens]) @seen))
          "which is why the proxy's per-request limit is 8192: any lower and the retry is
           clamped back to the same budget it exists to escape (blt.38)"))))

;; --- the critic -------------------------------------------------------------

(deftest the-critic-leaves-through-the-same-endpoint
  (let [seen (atom [])
        replies (atom [["progress: 3\nrisk: 1" "stop"]])
        c (db/open! ":memory:")]
    (try
      (with-redefs [http/post (recorder seen replies)]
        (critic/score! {:llm-adapter (registry/adapter-for :local) :llm-config (cfg)
                        :conn c :run-id nil}
                       {:id "B1" :messages [] :turns []} [] 1))
      (is (= 1 (count @seen)) "the critic made a real call")
      (is (= "http://proxy.invalid/v1/chat/completions" (:url (first @seen))))
      (is (= 4096 (get-in (first @seen) [:body :max_tokens]))
          "and it carries the configured output limit, like every other call")
      (finally (db/close c)))))

;; --- no call site gets to the network on its own ----------------------------

(deftest every-inference-call-site-goes-through-the-shared-client
  ;; The routing guarantee rests on this. If a call site drove an HTTP client itself
  ;; instead of going through llm/chat, pointing :llm-config at the proxy would not
  ;; redirect it, and it would spend outside the ceiling with nothing to notice.
  (doseq [path ["src/samizdat/agent/select.clj" "src/samizdat/agent/reflect.clj"
                "src/samizdat/agent/trajectory.clj" "src/samizdat/agent/tools/digest.clj"
                "src/samizdat/agent/infer.clj" "src/samizdat/agent/critic.clj"]]
    (let [src (slurp path)]
      (is (re-find #"llm/chat" src)
          (str path " should reach the provider through the shared client"))
      ;; An HTTP client of its own is the thing that would bypass the endpoint - a URL in
      ;; a comment or a docstring is not, which is why this looks for the CALL and not
      ;; for the string.
      (is (not (re-find #"\(http/(post|get|put|request)\b" src))
          (str path " drives an HTTP client directly; a call site that does that cannot "
               "be redirected through the experiment proxy")))))

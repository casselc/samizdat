;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.prompt-test
  "The prompt seams, both halves.

  The prompt-to-dispatch contract: the two directions drift independently —
  a tool dispatched but undocumented is invisible to the model, and a tool
  documented but not dispatched burns turns on the :default method while the
  model reads the failure as its own mistake. Both are asserted so neither
  survives an edit.

  The renderer contract: selmer renders every prompt template (gates'
  message files and suffixes, the beam's steer prose, the loop's valve
  message, the domain prompts). Template files keep the {{...}} spelling the
  hand-rolled str/replace chains used, so the move changed the renderer,
  not the templates."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.agent.loop :as loop]
            [samizdat.agent.tools :as tools]
            [samizdat.prompt :as prompt]))

(deftest every-tool-is-documented
  (let [prompt (loop/system-prompt)
        undocumented (remove #(str/includes? prompt %) (tools/tool-names))]
    (is (empty? undocumented)
        (str "these tools are dispatched by run-tool but never mentioned in the"
             " prompt, so the model cannot call them: "
             (str/join ", " undocumented)))))

(deftest every-documented-tool-exists
  ;; The opposite drift, which is worse in one way: the model spends turns
  ;; calling something that lands on the :default method, and reads the failure
  ;; as its own mistake.
  (let [prompt (loop/system-prompt)
        known (set (tools/tool-names))
        ;; Names in the prompt are written as `name({args})`.
        mentioned (map second (re-seq #"(?m)^(\w+)\(\{?" prompt))
        phantom (remove known mentioned)]
    (is (empty? phantom)
        (str "the prompt documents tools that run-tool does not dispatch: "
             (str/join ", " phantom)))))

(deftest no-unsubstituted-placeholders
  ;; The prompt is assembled from a template; a substitution placeholder
  ;; reaching the model means an edit broke the seam. `{{env/NAME}}` is
  ;; excluded on purpose — it is documented runtime syntax the shell tool
  ;; resolves at spawn, not a template hole the loader should have filled.
  (let [prompt (loop/system-prompt)
        holes (->> (re-seq #"\{\{([^}]+)\}\}" prompt)
                   (map second)
                   (remove #(str/starts-with? % "env/")))]
    (is (empty? holes)
        (str "unfilled template placeholders: " (str/join ", " holes)))))

(deftest env-syntax-survives-selmer
  ;; selmer parses {{env/NAME}} as a nested lookup and renders it empty;
  ;; samizdat.prompt sentinel-wraps it so the documented shell-tool syntax
  ;; reaches the model verbatim. Without this, the secret-reference docs
  ;; silently vanish from the system prompt.
  (is (str/includes? (loop/system-prompt) "{{env/NAME}}"))
  (is (= "ref {{env/FOO}} end"
         (prompt/render-str "ref {{env/FOO}} end" {}))))

(deftest prompts-render-through-selmer
  ;; Placeholders interpolate: hyphenated keys (gates' {{turn-count}}),
  ;; numbers without pre-str-ing, values raw — no HTML escaping, because a
  ;; prompt full of code must not have < > & quoted into entities.
  (is (str/includes? (prompt/render "explore-cap" {:lead "L: " :cap 5})
                     "L: 5 turns"))
  (is (= "v=(->> xs (map inc)) & <plain>"
         (prompt/render-str "v={{v}}" {:v "(->> xs (map inc)) & <plain>"})))
  ;; A missing key renders empty rather than surfacing a literal {{...}} to
  ;; the model. The str/replace chains left the placeholder visible; the
  ;; content pins (what the message must contain) carry the load now.
  (is (= "a  b" (prompt/render-str "a {{absent}} b" {})))
  ;; Single braces are not template syntax — architect.md's JSON decision
  ;; block passes through untouched, and a value containing {{...}} is
  ;; inserted as text, never re-parsed.
  (is (= "{\"decision\": \"decompose\"} 1"
         (prompt/render-str "{\"decision\": \"decompose\"} {{n}}" {:n 1})))
  (is (= "a {{inner}} b"
         (prompt/render-str "a {{v}} b" {:v "{{inner}}"}))))

(deftest selmer-replaces-the-str-replace-seams
  ;; The renderer is selmer, not str/replace: the parser is selmer's (a
  ;; filter in the placeholder would fire, where a replace chain would
  ;; leave it verbatim).
  (is (= "Y" (prompt/render-str "{{v|upper}}" {:v "y"}))))

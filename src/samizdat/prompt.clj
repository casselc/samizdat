;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.prompt
  "Selmer is the template engine for every prompt seam — the gates' message
  files and suffixes, the beam's steer prose, the loop's valve message, the
  domain prompts (critic, judge, architect). Template files keep the
  {{...}} spelling the hand-rolled str/replace chains used; the move changed
  the renderer, not the templates.

  Escaping is OFF, globally, here: a prompt full of code must not have
  < > & turned into entities, and unlike an HTML page there is no injection
  surface to defend — the output feeds a model, not a browser. One semantic
  difference from str/replace chains, accepted: a missing key renders empty
  instead of surfacing a literal {{...}} to the model."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [selmer.parser :as selmer]
            [selmer.util :as selmer-util]))

(selmer-util/turn-off-escaping!)

(defn prompt
  "Raw text of resources/prompts/<name>.md — the same io/resource seam the
  hand-rolled readers used, so paths work interpreted and inside an AOT
  binary."
  [name]
  (slurp (io/resource (str "prompts/" name ".md"))))

;; system.md documents {{env/NAME}} as RUNTIME syntax the shell tool
;; resolves at spawn — it must reach the model verbatim. Selmer would parse
;; it as a nested lookup and render it empty, so the braces are swapped for
;; private-use sentinels around the render and restored after. Values are
;; inserted as nodes and never re-parsed, so only the template text needs
;; the round-trip.
(def ^:private env-open "\uE000env/")
(defn render-str
  "Render an inline template string — the gates' :message-suffix forms."
  [template ctx]
  (-> template
      (str/replace "{{env/" env-open)
      (selmer/render ctx)
      (str/replace env-open "{{env/")))

(defn render
  "Render resources/prompts/<name>.md with selmer against `ctx`."
  [name ctx]
  (render-str (prompt name) ctx))

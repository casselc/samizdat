;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.skills
  "The skill tool: load an instruction bundle on demand. `skill list` shows the
  bounded catalogue; `skill load {name}` pulls one skill's full text into
  context — so a guide (like structuring the loop with mycelium) costs context
  only when the agent reaches for it, not every turn."
  (:require [clojure.string :as str]
            [samizdat.agent.skills :as skills]
            [samizdat.agent.tools.base :as base]))

(def ^:private usage
  "Actions: list (the catalogue), load {name} (a skill's full guidance).")

(defmethod base/run-tool "skill" [{:keys [branch] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)]
    (case action
      nil
      (base/malformed branch (str "`skill` needs an `action`. " usage))

      "list"
      (base/ok branch
               (let [cat (skills/catalog)]
                 (if (seq cat)
                   (str "Skills — load one with `skill load {name}` when it is"
                        " relevant:\n"
                        (str/join "\n" (for [{:keys [name description]} cat]
                                         (str "  " name " — " description))))
                   "No skills available.")))

      "load"
      (let [name (some-> (base/arg ctx :name) str str/trim not-empty)]
        (cond
          (nil? name) (base/missing branch :name)
          :else
          (if-let [content (skills/load-skill name)]
            (base/ok branch content)
            (base/malformed branch (str "No skill named '" name "'. " usage)))))

      (base/malformed branch (str "Unknown skill action `" action "`. " usage)))))

;; samizdat - a self-hosting agentic harness
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

(ns samizdat.agent.skills
  "Skills: instruction bundles the agent loads on demand, so a guide costs
  context only when it is needed. A skill is a markdown file whose first
  meaningful line is its one-line description (the catalogue entry) and whose
  body is the guidance loaded on request. Bundled skills live in
  resources/skills/; a project can add or override them in .samizdat/skills/,
  first match by directory order — the same layering as cells and config."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-dirs ["resources/skills" ".samizdat/skills"])

(defn- md-files [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (->> (.listFiles d)
           (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
           (sort-by #(.getName ^java.io.File %))))))

(defn- describe
  "A skill's one-line catalogue description: the first non-blank line that is
  not a markdown heading, else the H1 text, else the name."
  [content name]
  (let [lines (remove str/blank? (str/split-lines content))]
    (or (some #(when-not (str/starts-with? (str/trim %) "#") (str/trim %)) lines)
        (some->> lines (some #(when (str/starts-with? (str/trim %) "#") %))
                 (re-find #"#+\s*(.+)") second)
        name)))

(defn discover
  "skill-name -> {:path :description}. A skill named in a later dir overrides an
  earlier one, so .samizdat/skills wins over resources/skills."
  ([] (discover default-dirs))
  ([dirs]
   (reduce
    (fn [acc dir]
      (reduce (fn [m ^java.io.File f]
                (let [name (str/replace (.getName f) #"\.md$" "")]
                  (assoc m name {:path (.getPath f)
                                 :description (describe (slurp f) name)})))
              acc (md-files dir)))
    {} dirs)))

(defn catalog
  "The bounded list the agent sees: [{:name :description} ...], sorted."
  ([] (catalog default-dirs))
  ([dirs]
   (->> (discover dirs)
        (map (fn [[name {:keys [description]}]] {:name name :description description}))
        (sort-by :name)
        vec)))

(defn load-skill
  "The full content of a named skill, or nil when there is no such skill."
  ([name] (load-skill default-dirs name))
  ([dirs name]
   (some-> (get-in (discover dirs) [name :path]) slurp)))

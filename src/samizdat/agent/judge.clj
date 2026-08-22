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

(ns samizdat.agent.judge
  "The pure core of the finalization critic, ported from dirge's unified judge.

  When a branch tries to finish, an LLM judge asks whether the task is actually
  complete — not whether the model believes it is. The judge is fed a
  deterministic EVIDENCE block (what files changed, what commands ran and
  whether they passed, which tools were used) so it can catch an answer that
  claims something the run never did. It is judged against the agent's OWN
  rules so it never demands a forbidden action, and it is calibrated to PASS
  when unsure — a false block wastes a whole turn.

  Everything here is pure and unit-testable; the cell that calls the model and
  routes on the verdict lives in resources/cells/critic.clj."
  (:require [clojure.string :as str]))

(def verdicts #{:complete :incomplete :abstain})

(defn parse-verdict
  "The verdict from a judge reply's VERDICT line. Whole-word and
  negation-aware — INCOMPLETE contains COMPLETE, and \"NOT COMPLETE\" negates
  it — with precedence negative > abstain > positive. FAIL-OPEN: an empty or
  tokenless reply is :complete, because a judge that cannot answer must never
  be able to wedge the loop."
  [reply]
  (let [head (or (some #(when (re-find #"(?i)\bVERDICT\b" %) %)
                       (str/split-lines (str reply)))
                 (first (str/split-lines (str reply)))
                 "")
        up (str/upper-case head)
        w? (fn [word] (boolean (re-find (re-pattern (str "\\b" word "\\b")) up)))]
    (cond
      (w? "INCOMPLETE") :incomplete
      (and (w? "NOT") (w? "COMPLETE")) :incomplete
      (w? "ABSTAIN") :abstain
      (w? "COMPLETE") :complete
      :else :complete)))

(defn findings
  "The FINDINGS section of a judge reply, verbatim, trimmed — or nil when it
  named none. What the critique passes back to the branch below the verdict."
  [reply]
  (some-> (re-find #"(?is)FINDINGS:\s*(.+)$" (str reply))
          second str/trim not-empty))

(defn- one-line [s n]
  (let [flat (-> (str s) (str/replace #"\s+" " ") str/trim)]
    (if (> (count flat) n) (str (subs flat 0 n) "…") flat)))

(defn evidence
  "A deterministic facts block over the run's turn rows: how many tool calls,
  which tools, the files the run wrote, and the shell commands it ran with
  whether each passed. Lets the judge check a claim against what the run
  actually did. `rows` are turn maps with :tool_name :args :category."
  [rows]
  (let [rows (vec rows)
        by-tool (frequencies (keep :tool_name rows))
        arg (fn [r k] (get-in r [:args k] (get (:args r) (name k))))
        files (->> rows
                   (filter #(#{"write_file" "edit_file"} (:tool_name %)))
                   (keep #(arg % :path))
                   distinct)
        cmds (->> rows
                  (filter #(= "shell" (:tool_name %)))
                  (map (fn [r] (str (if (= "failure" (some-> (:category r) name))
                                      "FAILED" "ok")
                                    ": " (one-line (arg r :command) 70))))
                  distinct)]
    (str "tool calls: " (count (keep :tool_name rows))
         "\ntools used: " (str/join ", " (sort (keys by-tool)))
         (when (seq files)
           (str "\nfiles written: " (str/join ", " files)))
         (when (seq cmds)
           (str "\ncommands run:\n  " (str/join "\n  " cmds))))))

(def preamble
  "The judge's standing instructions. Calibrated, not trigger-happy."
  (str "You are a reviewer deciding whether an agent's work on a task is "
       "actually complete and correct. You are given the agent's own rules, a "
       "transcript of what it did, a deterministic evidence block, and the "
       "answer it wants to ship.\n\n"
       "Rules:\n"
       "- Respect the agent's own instructions. Never demand something they "
       "forbid (e.g. pushing or committing if they say not to).\n"
       "- Block only on a concrete, in-scope gap you can point at. Check the "
       "answer's claims against the EVIDENCE block and flag any the run did "
       "not actually do.\n"
       "- A run that ends by asking the user a question is a correct stop, not "
       "incompleteness.\n"
       "- When unsure, PASS or ABSTAIN. A false block wastes a whole turn.\n\n"
       "Answer with a first line exactly `VERDICT: COMPLETE`, "
       "`VERDICT: INCOMPLETE`, or `VERDICT: ABSTAIN`, then optionally a "
       "`FINDINGS:` section naming what is missing or wrong."))

(defn critic-prompt
  "The judge's user message: the agent's rules, the evidence, the transcript,
  and the answer under review."
  [{:keys [rules transcript evidence answer]}]
  (str "## The agent's rules\n\n" (one-line rules 6000)
       "\n\n## Evidence (deterministic facts about the run)\n\n" evidence
       "\n\n## Transcript\n\n" (one-line transcript 12000)
       "\n\n## The answer it wants to ship\n\n" (str answer)
       "\n\nIs this task complete and correct? " preamble))

(defn critique-message
  "The single consolidated note injected back into the branch when the judge
  does not pass, so its next turn sees exactly what to fix."
  [verdict findings-text]
  (str "[critic] "
       (case verdict
         :incomplete "This may not be done yet."
         :abstain (str "Correctness could not be confirmed — add a focused test "
                       "or state the missing detail, then finish again.")
         "Reconsider before finishing.")
       (when findings-text (str "\n\n" findings-text))
       "\n\nAddress this, then finish again when it holds."))

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
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.prompt :as prompt]))

;; The judge's prompt files read through the shared samizdat.prompt seam
;; (tier 2b) — every gate message reads through the same one.

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
                   (filter #(contains? (gates/tool-vocab :file-write) (:tool_name %)))
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

;; --- deterministic finalization gates (run before the LLM judge) -----------

(defn- rules [] (gates/threshold :judge-rules))

(defn- tool-used? [rows pred]
  (boolean (some (fn [r] (and (:tool_name r) (pred r))) rows)))

(defn- ran-tests? [rows]
  (tool-used? rows
              (fn [r] (and (= "shell" (:tool_name r))
                           (re-find (re-pattern (:test-run-regex (rules)))
                                    (str (get-in r [:args :command])))))))

(defn- edited-code? [rows]
  (tool-used? rows
              (fn [r] (and (contains? (gates/tool-vocab :file-write) (:tool_name r))
                           (re-find (re-pattern (:code-ext-regex (rules)))
                                    (str (get-in r [:args :path])))))))

(defn- outside-tool? [name]
  (re-find (re-pattern (:outside-reach-regex (rules))) (str name)))

(defn verifier-block
  "A concrete, LLM-free finalization gate (dirge's verifier rung): the run
  edited code but never ran a test. Returns a critique message, or nil when
  there is nothing to say. Deterministic, so it fires before the paid judge.
  The vocabulary is gates.edn :judge-rules (drg-4026 #55) — what counts as
  a test run is project data, not kernel code."
  [rows]
  (when (and (edited-code? rows) (not (ran-tests? rows)))
    (:verifier-message (rules))))

(defn claim-block
  "A finalization gate for an unsupported claim: the answer says it tested,
  ran, or verified something, but the evidence shows no test ran. Returns a
  message or nil. Same gates.edn rules."
  [answer rows]
  (when (and (re-find (re-pattern (:claim-regex (rules))) (str answer))
             (not (ran-tests? rows)))
    (:claim-message (rules))))

(defn source-block
  "A finalization gate for an uncheckable source: the answer attributes a
  claim to something outside the repo — a URL, or phrasing like 'according
  to', 'the docs say', 'the spec says', 'the API returns' — but the run used
  no tool that reaches outside it. Returns a message or nil.

  drg-4026 #56: the premise is CHECKED, not assumed. The caller passes the
  registered tool surface; the outside-reach pattern (gates.edn) is matched
  against it and the run's own rows, and the block fires only when nothing
  that could have checked the claim exists — a project that registers a web
  tool disables this block by existing. fetch_artifact / fetch_turn contain
  no web-family words, so the local journal readers never count as reaching
  out. The surface is an argument, not a require, so this ns stays pure and
  free of the aggregator."
  [answer rows tool-surface]
  (when (and (re-find (re-pattern (:outside-claim-regex (rules))) (str answer))
             (not (some outside-tool? tool-surface))
             (not (some (comp outside-tool? :tool_name) rows)))
    (:outside-message (rules))))

(defn deterministic-block
  "The first deterministic finalization gate that fires, or nil. These are
  cheap and specific, so they run before the LLM judge and outrank it.
  `tool-surface` is the registered tool names — source-block checks the
  run's reach against it (drg-4026 #56)."
  [answer rows tool-surface]
  (or (verifier-block rows)
      (claim-block answer rows)
      (source-block answer rows tool-surface)))

(def preamble
  "The judge's standing instructions, from resources/prompts/judge.md (tier
  2b — runtime-editable). Calibrated, not trigger-happy. A unified judge:
  it decides completeness AND reviews the run's own diff for defects."
  (prompt/prompt "judge"))

(defn critic-prompt
  "The judge's user message: the agent's rules, the evidence, the transcript,
  the diff of what the run changed, and the answer under review."
  [{:keys [rules transcript evidence answer diff]}]
  (str "## The agent's rules\n\n" (one-line rules 6000)
       "\n\n## Evidence (deterministic facts about the run)\n\n" evidence
       (when (seq (str diff))
         (str "\n\n## Diff of what this run changed\n\n```diff\n"
              (str diff) "\n```"))
       "\n\n## Transcript\n\n" (one-line transcript 12000)
       "\n\n## The answer it wants to ship\n\n" (str answer)
       "\n\nIs this task complete and correct? " preamble))

(defn blocking-findings
  "The findings a review blocks on — those tagged [critical] or [high]. Returns
  the whole findings text when any are, else nil. Medium/low findings are
  advisory: worth passing back, not worth undoing a done for."
  [reply]
  (when-let [f (findings reply)]
    (when (re-find #"(?i)\[(critical|high)\]" f) f)))

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

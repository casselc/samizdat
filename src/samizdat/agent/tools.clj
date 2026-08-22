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

(ns samizdat.agent.tools
  "Tool dispatch aggregator.

  run-tool is a multimethod defined in samizdat.agent.tools.base; each tool
  group namespace below registers its defmethods on load. A multimethod
  rather than a case, because it is what lets a tool be redefined against a
  running process and picked up on the next branch turn.

  Every method takes a context and returns a result map:

    {:result   string the model sees
     :category :success | :failure | :neutral   what the cull guard reads
     :progress? bool                            what the stall guard reads
     :branch   the updated branch
     :artifact optional, recorded to the artifacts table
     :failure  optional, recorded to the shared failure log
     :done?    optional, ends the run}

  :category and :progress? are separate on purpose. A tool call can succeed
  and advance nothing, and a model making varied, well-formed, useless calls
  trips no error-keyed guard while burning the whole run.

  To add a tool: open the group namespace it belongs to (or create a new one
  beside them) and defmethod base/run-tool there. This file stays as is."
  (:require [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.repl]
            [samizdat.agent.tools.files]
            [samizdat.agent.tools.shell]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.agent.tools.tasks]
            [samizdat.agent.tools.messages]
            [samizdat.agent.tools.knowledge]
            [samizdat.agent.tools.journal]
            [samizdat.agent.tools.mutate]
            [samizdat.agent.tools.manifest]
            [samizdat.agent.tools.skills]
            [samizdat.agent.tools.introspect]
            [samizdat.agent.tools.lsp]))

;; Re-exports: loop.clj and the tests reach the tool surface through this
;; namespace and keep working unchanged.
(def run-tool base/run-tool)
(def arg base/arg)
(def ok base/ok)
(def fail base/fail)
(def malformed base/malformed)
(def missing base/missing)
(def unavailable base/unavailable)
(def phase-refusal base/phase-refusal)
(def tool-names base/tool-names)
(def answer-tokens ship/answer-tokens)
(def uncovered-tokens ship/uncovered-tokens)

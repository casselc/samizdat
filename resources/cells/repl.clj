;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; THE REPL SESSION as cells. A session is orient -> declare -> explore ->
;; land, and the `repl` manifest is where that order is written down.
;;
;; Why it exists, from run bd56a286: a strong model spent 238 turns in the
;; REPL hunting a defect that was in its own tests, and a sibling branch read
;; for 316 turns and wrote nothing at all. Neither was reachable by steering —
;; nudges are 0-for-6 across two model tiers. What was missing was not
;; encouragement but a CONTRACT: name the files you are about to change before
;; you start exploring, and land them before you stop.
;;
;; The two conditions are enforced as phases.edn refusals (eval needs a plan;
;; done needs the plan landed) because a withhold holds and advice does not.
;; These cells compute the session's SHAPE — which step a branch is in and
;; what it still owes — so the digest and any workflow can read it, and so the
;; order is data an agent can rewrite rather than control flow in src/.
(ns cells.repl
  (:require [mycelium.cell :as cell]
            [samizdat.agent.state :as state]))

(cell/defcell :repl/orient
  {:doc "Entry. The branch has no plan yet: reading is open (read_file, grep,
        lsp, shell) and the REPL is closed. This is where the hypothesis comes
        from — read the failing assertion and the code it calls, and decide
        which of the two is lying."
   :pure true
   :requires []}
  (fn [_ data]
    (assoc data :repl/step :orient
                :repl/may-eval? false)))

(cell/defcell :repl/declare
  {:doc "The commitment: files to create or edit, tests to write, one line of
        goal. An empty declaration is not one — naming no file is exactly the
        state this step exists to rule out."
   :pure true
   :requires []}
  (fn [_ {:keys [branch] :as data}]
    (assoc data :repl/step :declare
                :repl/planned? (state/planned? branch)
                :repl/plan (state/plan branch))))

(cell/defcell :repl/explore
  {:doc "The REPL, open. Bounded by having said what it is for rather than by
        a turn budget: a branch exploring against a named file is doing the
        work, and a branch exploring against nothing is the failure this
        workflow was built after."
   :pure true
   :requires []}
  (fn [_ {:keys [branch] :as data}]
    (assoc data :repl/step :explore
                :repl/may-eval? (state/planned? branch))))

(cell/defcell :repl/land
  {:doc "Exit. What the branch said it would change and has not yet written.
        Empty means the session may close; anything else is the debt `done` is
        refused on. The REPL dies with the branch — only a file survives."
   :pure true
   :requires []}
  (fn [_ {:keys [branch] :as data}]
    (let [owed (state/unwritten branch)]
      (assoc data :repl/step :land
                  :repl/unwritten owed
                  :repl/complete? (empty? owed)))))

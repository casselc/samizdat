;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.kanban-test
  "The board as a WORKFLOW: create, claim one, work it until it closes.

  Three properties, and the third is the one that had a bug:

  ONE current task per branch, so \"until it is done\" means something.
  KEPT IN FRONT of the model at both ends of the context, and — the part that
  decides whether this is affordable — WITHOUT invalidating the prefix cache.
  EXCLUSIVE between the agents that actually compete for it, which on a team is
  branches of one run rather than runs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.llm.message :as message]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [samizdat.store.tasks :as tasks]
            [samizdat.tape :as tape]))

(defn- branch [] (state/new-branch {:id "B1" :problem "p"
                                    :messages [{:role "system" :content "s"}
                                               {:role "user" :content "p"}]}))

(defn- run-task [conn run-id b args]
  (tools/run-tool {:branch b :conn conn :run-id run-id
                   :tool-name "task" :args args}))

(defmacro with-run [[c rid] & body]
  `(let [~c (db/open! ":memory:")
         ~rid (runs/start-run! ~c {:problem "p"})]
     (try ~@body (finally (db/close ~c)))))

;; --- one task at a time ------------------------------------------------------

(deftest claiming-sets-the-branchs-one-current-task
  (with-run [c rid]
    (let [id (tasks/create! c {:title "the thing" :contract "it must work"})
          r (run-task c rid (branch) {:action "claim" :id id})]
      (is (= {:id id :title "the thing"} (:task (:branch r))))
      (is (:progress? r) "picking up work is progress, unlike listing the board"))))

(deftest a-second-claim-is-refused-and-names-the-way-out
  (with-run [c rid]
    (let [a (tasks/create! c {:title "first"})
          b (tasks/create! c {:title "second"})
          held (:branch (run-task c rid (branch) {:action "claim" :id a}))
          r (run-task c rid held {:action "claim" :id b})]
      (is (= :mechanics (:category r)))
      (is (str/includes? (:result r) a) "it says which task is in the way")
      (is (str/includes? (:result r) "switch") "and how to change course")
      (is (= a (:id (:task (:branch r)))) "the current task is unchanged"))))

(deftest reclaiming-what-you-hold-is-idempotent
  ;; A branch should not have to remember whether it already claimed.
  (with-run [c rid]
    (let [id (tasks/create! c {:title "the thing"})
          held (:branch (run-task c rid (branch) {:action "claim" :id id}))
          r (run-task c rid held {:action "claim" :id id})]
      (is (= :neutral (:category r)))
      (is (str/includes? (:result r) "Already working on")))))

(deftest switching-releases-the-old-task-and-records-why
  (with-run [c rid]
    (let [a (tasks/create! c {:title "first"})
          b (tasks/create! c {:title "second"})
          held (:branch (run-task c rid (branch) {:action "claim" :id a}))
          r (run-task c rid held {:action "switch" :id b
                                  :reason "blocked on a decision"})]
      (is (= b (:id (:task (:branch r)))))
      (testing "the set-down task goes back on the board, not into limbo"
        (let [old (tasks/get-task c a)]
          (is (nil? (:branch_id old)))
          (is (= "open" (:status old)))))
      (testing "and why is in the record"
        (is (str/includes? (:result r) "blocked on a decision"))))))

(deftest switching-needs-a-reason
  (with-run [c rid]
    (let [id (tasks/create! c {:title "t"})
          r (run-task c rid (branch) {:action "switch" :id id})]
      (is (= :mechanics (:category r)))
      (is (str/includes? (:result r) "reason")))))

(deftest closing-the-current-task-frees-the-slot
  (with-run [c rid]
    (let [id (tasks/create! c {:title "t"})
          held (:branch (run-task c rid (branch) {:action "claim" :id id}))
          r (run-task c rid held {:action "close" :id id})]
      (is (nil? (:task (:branch r)))
          "so the next turn asks for the next task rather than pointing at finished work")
      (is (= "done" (:status (tasks/get-task c id)))))))

(deftest closing-somebody-elses-task-does-not-free-your-slot
  (with-run [c rid]
    (let [mine (tasks/create! c {:title "mine"})
          other (tasks/create! c {:title "other"})
          held (:branch (run-task c rid (branch) {:action "claim" :id mine}))
          r (run-task c rid held {:action "close" :id other})]
      (is (= mine (:id (:task (:branch r))))))))

;; --- in front of the model, and free -----------------------------------------

(deftest the-claim-pins-the-task-statement-into-the-tape
  (with-run [c rid]
    (let [id (tasks/create! c {:title "the thing" :contract "must work"
                               :tests "the suite is green"})
          b (:branch (run-task c rid (branch) {:action "claim" :id id}))
          added (last (:messages b))]
      (is (= "user" (:role added)))
      (is (true? (:pinned? added)))
      (is (= id (:task-id added)) "stamped, so a later turn knows which task it is")
      (testing "and it carries the delegation spec, which is what makes it workable"
        (is (str/includes? (:content added) "must work"))
        (is (str/includes? (:content added) "the suite is green"))))))

(deftest a-pinned-statement-is-never-compacted-away
  ;; THE POINT of pinning. A task matters MORE the longer it runs, so ageing it
  ;; out of the context as the branch works is exactly backwards.
  (let [pinned {:role "user" :content (apply str (repeat 500 "t"))
                :pinned? true :task-id "sz-1"}
        filler (fn [i] [{:role "assistant" :content (str "a" i (apply str (repeat 400 "x")))}
                        {:role "user" :content (str "r" i (apply str (repeat 400 "y")))}])
        msgs (into [{:role "system" :content "S"}
                    {:role "user" :content "P"}
                    pinned]
                   (mapcat filler (range 1 20)))
        out (message/compact msgs [] {:keep-pairs 2 :threshold-chars 1000})]
    (is (= pinned (nth out 2))
        "the task statement survives verbatim while everything around it unloads")
    (is (not (contains? (set (tape/due-indices msgs 2 #{"user" "assistant"})) 2))
        "and it is not even a candidate")
    (testing "the rest of the region really did compact, so this is not a no-op"
      (is (some :compacted? out)))))

(deftest the-current-task-costs-nothing-per-turn
  ;; The reminder rides the per-turn context block, which is APPENDED at the end
  ;; of the array — where the prefix-cache boundary already is. A block held at
  ;; a fixed early position and rewritten on change would invalidate every
  ;; cached token behind it; this asserts the prefix genuinely does not move.
  (with-run [c rid]
    (let [id (tasks/create! c {:title "the thing"})
          b (:branch (run-task c rid (branch) {:action "claim" :id id}))
          before (vec (:messages b))
          ;; Two turns' worth of appends, as the loop makes them.
          t1 (state/add-message b "user" "result 1" {:turn 1})
          t2 (state/add-message t1 "user" "result 2" {:turn 2})]
      (is (= before (vec (take (count before) (:messages t2))))
          "nothing before the newest append changed — the cache stays warm")
      (is (= (:task b) (:task t2)) "and the current task rides along untouched"))))

;; --- exclusive between the agents that actually compete ----------------------

(deftest two-branches-of-one-run-cannot-both-hold-a-task
  ;; THE BUG migration v12 fixes. The guard was (run_id IS NULL OR run_id = ?),
  ;; which is exclusive BETWEEN runs and a no-op WITHIN one — so on a team
  ;; workflow, where the competing implementors are branches of a single run,
  ;; two workers both claimed the same task and both believed they held it.
  ;; That is precisely the case the board exists to arbitrate.
  (with-run [c rid]
    (let [id (tasks/create! c {:title "one part of the feature"})]
      (is (some? (tasks/claim! c id rid "W0")))
      (is (nil? (tasks/claim! c id rid "W1"))
          "the second worker on the SAME run is refused")
      (is (nil? (tasks/claim! c id "another-run" "W0"))
          "and so is another run")
      (is (= "W0" (:branch_id (tasks/get-task c id)))))))

(deftest a-released-task-is-claimable-by-a-different-branch
  ;; What makes re-tasking work: a part its worker could not finish goes back,
  ;; rather than staying attributed to a branch that has stopped.
  (with-run [c rid]
    (let [id (tasks/create! c {:title "part"})]
      (tasks/claim! c id rid "W0")
      (tasks/release! c id "W0")
      (is (some? (tasks/claim! c id rid "W1")))
      (is (= "W1" (:branch_id (tasks/get-task c id)))))))

(deftest release-only-works-for-the-holder
  (with-run [c rid]
    (let [id (tasks/create! c {:title "part"})]
      (tasks/claim! c id rid "W0")
      (tasks/release! c id "W1")
      (is (= "W0" (:branch_id (tasks/get-task c id)))
          "a branch cannot put down work it is not holding"))))

(deftest the-board-says-who-holds-what
  ;; On a team this is the difference between coordinating and colliding.
  (with-run [c rid]
    (let [mine (tasks/create! c {:title "taken" :run-id rid})
          free (tasks/create! c {:title "open" :run-id rid})]
      (tasks/claim! c mine rid "W0")
      (let [r (run-task c rid (branch) {:action "list"})]
        (is (str/includes? (:result r) "@W0"))
        (is (str/includes? (:result r) "open"))
        (is (not (re-find (re-pattern (str free "[^\\n]*@")) (:result r)))
            "an unclaimed task shows no holder")))))

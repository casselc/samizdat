;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.userspace-test
  "The base/userspace seam.

  What is under test is the property that makes userspace userspace: a project
  gets its OWN copy of the shipped template, evolves it, and neither the
  harness's files nor another project's copy is affected. A layer that is
  shared is not userspace no matter which directory it lives in."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.store.db :as db]
            [samizdat.store.userspace :as store]
            [samizdat.store.workflows :as workflows]
            [samizdat.userspace :as us]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.gates :as gates]
            [samizdat.manual :as manual]
            [samizdat.prompt :as prompt]))

(def ^:dynamic *conn* nil)

(defn- with-project [f]
  (let [c (db/open! ":memory:")]
    (try (binding [*conn* c] (f))
         (finally (db/close c)))))

(use-fixtures :each with-project (fn [f] (try (f) (finally (us/unbind!)))))

;; --- the store ---------------------------------------------------------------

(deftest saves-are-appended-never-updated
  ;; The edit history of a system that rewrites itself is the most valuable
  ;; thing in its database.
  (is (= 1 (store/save! *conn* :cell "loop" "v1")))
  (is (= 2 (store/save! *conn* :cell "loop" "v2")))
  (is (= 3 (store/save! *conn* :cell "loop" "v3")))
  (is (= "v3" (:body (store/load-latest *conn* :cell "loop"))))
  (is (= "v1" (:body (store/load-version *conn* :cell "loop" 1)))
      "an older version stays readable — that is what makes rollback possible")
  (is (= [1 2 3] (mapv :version (store/versions *conn* :cell "loop")))))

(deftest an-edit-that-changed-nothing-is-still-recorded
  (store/save! *conn* :cell "loop" "same")
  (store/save! *conn* :cell "loop" "same")
  (is (= 2 (count (store/versions *conn* :cell "loop")))
      "what the supervisor TRIED is a fact; suppressing it makes the history lie"))

(deftest seed-installs-once-and-never-overwrites
  (store/seed! *conn* :cell "loop" "the template")
  (store/save! *conn* :cell "loop" "the project's own version")
  (store/seed! *conn* :cell "loop" "the template")
  (is (= "the project's own version" (:body (store/load-latest *conn* :cell "loop")))
      "seeding an evolved project must not drag it back to the template")
  (is (= 2 (count (store/versions *conn* :cell "loop")))))

(deftest revert-is-an-edit-not-a-deletion
  (store/save! *conn* :policy "gates" "good")
  (store/save! *conn* :policy "gates" "bad")
  (is (= 3 (store/revert! *conn* :policy "gates" 1)))
  (is (= "good" (:body (store/load-latest *conn* :policy "gates"))))
  (is (= "bad" (:body (store/load-version *conn* :policy "gates" 2)))
      "the failed edit stays where it can be read")
  (is (nil? (store/revert! *conn* :policy "gates" 99))))

(deftest kinds-are-separate-namespaces
  (store/save! *conn* :cell "loop" "a cell")
  (store/save! *conn* :manifest "loop" "a manifest")
  (is (= "a cell" (:body (store/load-latest *conn* :cell "loop"))))
  (is (= "a manifest" (:body (store/load-latest *conn* :manifest "loop")))))

(deftest an-unknown-kind-fails-loud
  ;; A row filed under a typo'd kind is a row nothing will ever read again.
  (is (thrown-with-msg? Exception #"unknown userspace kind"
                        (store/save! *conn* :celll "loop" "x"))))

(deftest latest-bodies-picks-the-newest-of-each
  (store/save! *conn* :cell "a" "a1")
  (store/save! *conn* :cell "a" "a2")
  (store/save! *conn* :cell "b" "b1")
  (store/save! *conn* :manifest "c" "c1")
  (is (= {"a" "a2" "b" "b1"} (store/latest-bodies *conn* :cell))
      "the newest version of each name, and nothing from another kind"))

;; --- the read seam -----------------------------------------------------------

(deftest unbound-reads-the-shipped-template
  (us/unbind!)
  (is (re-find #"defcell :loop/assemble" (us/body :cell "loop")))
  (is (re-find #"tool call" (us/body :prompt "system")))
  (is (map? (us/edn-body :manifest "loop")))
  (is (map? (us/edn-body :policy "gates")))
  (testing "and stores nothing, because there is no project to store it in"
    (is (= [] (us/versions :cell "loop")))))

(deftest a-bound-project-seeds-itself-on-first-read
  (us/bind! *conn*)
  (let [first-read (us/body :cell "loop")]
    (is (re-find #"defcell :loop/assemble" first-read))
    (is (= [1] (mapv :version (us/versions :cell "loop")))
        "reading is what gives the project its copy")
    (is (= first-read (us/template :cell "loop"))
        "and the copy starts identical to the template")))

(deftest the-project-evolves-and-the-template-does-not-follow
  ;; THE property. Two projects, one harness, divergent loops.
  (us/bind! *conn*)
  (us/body :cell "loop")
  (us/save! :cell "loop" "(ns cells.loop) ;; this project's own idea")
  (is (= "(ns cells.loop) ;; this project's own idea" (us/body :cell "loop")))
  (is (re-find #"defcell :loop/assemble" (us/template :cell "loop"))
      "the shipped template is untouched — another project still starts from it")
  (testing "a second project starts from the template, not from this one"
    (let [other (db/open! ":memory:")]
      (try (us/bind! other)
           (is (re-find #"defcell :loop/assemble" (us/body :cell "loop")))
           (finally (db/close other))))))

(deftest userspace-the-harness-never-shipped-is-first-class
  ;; A cell the supervisor wrote has no template by definition.
  (us/bind! *conn*)
  (is (nil? (us/body :cell "invented-by-the-agent")))
  (us/save! :cell "invented-by-the-agent" "(ns cells.invented)")
  (is (= "(ns cells.invented)" (us/body :cell "invented-by-the-agent")))
  (is (nil? (us/template :cell "invented-by-the-agent"))))

(deftest body-bang-fails-loud-and-says-where-it-looked
  (us/bind! *conn*)
  (is (thrown-with-msg? Exception #"cells/nope\.clj"
                        (us/body! :cell "nope"))))

(deftest an-unbound-save-says-so-rather-than-throwing
  (us/unbind!)
  (is (nil? (us/save! :cell "loop" "x"))
      "a REPL or a test editing userspace with no project is a real situation"))

(deftest seed-all-returns-the-projects-bodies-not-the-templates
  (us/bind! *conn*)
  (let [bodies (us/seed-all! :cell ["loop" "critic" "does-not-ship"])]
    (is (contains? bodies "loop"))
    (is (contains? bodies "critic"))
    (is (not (contains? bodies "does-not-ship"))
        "a name with no template and no project version is simply absent"))
  (testing "an evolved cell comes back evolved"
    (us/save! :cell "loop" "evolved")
    (is (= "evolved" (get (us/seed-all! :cell ["loop"]) "loop")))))

(deftest seed-all-unbound-is-the-template-itself
  (us/unbind!)
  (let [bodies (us/seed-all! :cell ["loop" "does-not-ship"])]
    (is (re-find #"defcell" (get bodies "loop")))
    (is (not (contains? bodies "does-not-ship")))))

;; --- manifests came across ---------------------------------------------------

(deftest the-manifest-shim-reads-and-writes-the-one-store
  (workflows/save! *conn* "loop" "{:description \"mine\"}")
  (is (= "{:description \"mine\"}" (:edn (workflows/load-latest *conn* "loop")))
      "the shim keeps the :edn key its callers destructure")
  (is (= "{:description \"mine\"}"
         (:body (store/load-latest *conn* :manifest "loop")))
      "and the row is in the one userspace table")
  (is (= ["loop"] (mapv :name (workflows/names *conn*)))))

(deftest manifest-rows-from-before-the-migration-are-carried-across
  ;; v11 copies the workflows table in. A project that had already evolved its
  ;; loop must not silently lose that work on upgrade.
  (let [c (db/open! ":memory:")]
    ;; The old table still exists and still holds its rows; v11's copy is what
    ;; makes them readable through the new one.
    (db/execute! c ["INSERT OR IGNORE INTO workflows (name, version, edn, created_at)
                     VALUES (?, ?, ?, ?)" "legacy" 7 "{:description \"old\"}" (db/now)])
    (db/execute! c ["INSERT OR IGNORE INTO userspace (kind, name, version, body, created_at)
                     SELECT 'manifest', name, version, edn, created_at FROM workflows"])
    (is (= "{:description \"old\"}" (:edn (workflows/load-latest c "legacy"))))
    (is (= 7 (:version (workflows/load-latest c "legacy"))))))

;; --- the `cell` tool: the supervisor's edge into userspace --------------------

(defn- run-cell [conn args]
  (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                   :conn conn
                   :tool-name "cell"
                   :args args}))

(deftest the-cell-tool-reports-a-project-with-no-versions-of-its-own
  (us/bind! *conn*)
  (let [r (run-cell *conn* {:action "list"})]
    (is (re-find #"shipped templates" (:result r))
        "a project running the template should be told that, not shown an empty list")))

(deftest the-cell-tool-shows-the-template-before-the-project-has-edited-it
  (us/bind! *conn*)
  (let [r (run-cell *conn* {:action "show" :name "loop"})]
    (is (re-find #"defcell :loop/assemble" (:result r)))))

(deftest the-cell-tool-lists-versions-and-says-when-there-are-none
  (us/bind! *conn*)
  (is (re-find #"still the shipped template"
               (:result (run-cell *conn* {:action "versions" :name "critic"}))))
  (us/save! :cell "critic" ";; mine")
  (is (re-find #"v1" (:result (run-cell *conn* {:action "versions" :name "critic"})))))

(deftest the-cell-tool-reverts-and-keeps-the-abandoned-version-readable
  (us/bind! *conn*)
  (us/save! :cell "critic" ";; v1")
  (us/save! :cell "critic" ";; v2 was a bad idea")
  (let [r (run-cell *conn* {:action "revert" :name "critic" :version "1"})]
    (is (= :neutral (:category r)))
    (is (re-find #"stored as v3" (:result r)))
    (is (= ";; v1" (us/body :cell "critic")))
    (is (= ";; v2 was a bad idea" (:body (store/load-version *conn* :cell "critic" 2)))
        "the version left behind stays readable — the next supervisor sees the attempt")))

(deftest the-cell-tool-refuses-a-revert-to-a-version-that-never-existed
  (us/bind! *conn*)
  (us/save! :cell "critic" ";; v1")
  (let [r (run-cell *conn* {:action "revert" :name "critic" :version "9"})]
    (is (= :mechanics (:category r)))
    (is (re-find #"No v9" (:result r)))
    (is (re-find #"v1" (:result r)) "and says what versions there are")))

(deftest the-cell-tool-complains-usefully-about-a-missing-argument
  (us/bind! *conn*)
  (doseq [args [{} {:action "show"} {:action "save" :name "critic"}]]
    (let [r (run-cell *conn* args)]
      (is (= :mechanics (:category r)) (str "for " (pr-str args)))
      (is (seq (:result r))))))

(deftest an-unknown-cell-action-lists-the-real-ones
  (us/bind! *conn*)
  (let [r (run-cell *conn* {:action "frobnicate"})]
    (is (re-find #"Unknown cell action" (:result r)))
    (is (re-find #"revert" (:result r)))))

;; --- every layer is per-project ----------------------------------------------

(deftest all-four-kinds-resolve-to-the-project-and-fall-back-to-the-template
  ;; The acceptance criterion for the seam: the supervisor's prompt tells it
  ;; that cells, manifests, thresholds and prompts all belong to this project.
  ;; This is what makes that true rather than aspirational.
  (us/bind! *conn*)
  (testing "a policy threshold"
    (is (= 3 (gates/threshold :cull-threshold)) "the template's value")
    (us/save! :policy "gates"
              (pr-str (assoc-in (us/edn-body :policy "gates")
                                [:cull-threshold :value] 99)))
    (gates/reload-config!)
    (is (= 99 (gates/threshold :cull-threshold))
        "this project decided its branches get more rope"))
  (testing "a prompt"
    (us/save! :prompt "cull-reprieve" "this project's own reprieve wording")
    (is (= "this project's own reprieve wording" (prompt/prompt "cull-reprieve"))))
  (testing "the manual — which capabilities the agent is told it has"
    (us/save! :policy "manual"
              (pr-str [{:group "Mine"
                        :entries [{:name 'samizdat.tape/depth
                                   :summary "how long the tape is"}]}]))
    (is (= ["Mine"] (mapv :group (manual/groups)))))
  (testing "and none of it wrote the harness's own files"
    (is (re-find #":cull-threshold" (us/template :policy "gates")))
    (is (not (re-find #"99" (us/template :policy "gates"))))
    (is (not= "this project's own reprieve wording" (us/template :prompt "cull-reprieve")))
    (is (re-find #"The tape" (us/template :policy "manual")))))

(deftest a-second-project-is-unaffected-by-the-first
  (us/bind! *conn*)
  (us/save! :policy "gates"
            (pr-str (assoc-in (us/edn-body :policy "gates")
                              [:cull-threshold :value] 99)))
  (gates/reload-config!)
  (is (= 99 (gates/threshold :cull-threshold)))
  (let [other (db/open! ":memory:")]
    (try
      (us/bind! other)
      (gates/reload-config!)
      (is (= 3 (gates/threshold :cull-threshold))
          "two projects on one binary, two cull thresholds — the whole point")
      (finally (db/close other)))))

(deftest unbinding-restores-the-template-everywhere
  (us/bind! *conn*)
  (us/save! :prompt "cull-reprieve" "project wording")
  (us/unbind!)
  (is (not= "project wording" (prompt/prompt "cull-reprieve"))
      "a test or a bare REPL sees the harness as shipped")
  (gates/reload-config!)
  (is (= 3 (gates/threshold :cull-threshold))))

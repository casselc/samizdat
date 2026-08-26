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

(ns samizdat.mutation-test
  "The self-modification protocol: the agent edits a cell on disk, and the
  kernel runs checkpoint -> reload -> validate -> soak -> commit or rollback.
  A good edit commits and changes behavior; a broken one (syntax, wiring, or a
  cell that throws on valid input) rolls back cleanly and is journaled, so a
  bad edit can never brick the loop."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [jolt.fs :as fs]
            [mycelium.cell :as cell]
            [samizdat.cells :as cells]
            [samizdat.manifests :as manifests]
            [samizdat.mutation :as mut]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.userspace :as us]))

(def ^:private root (atom nil))

;; A minimal all-pure loop, so the whole protocol (including soak's dry-run)
;; runs with no IO. two cells: start bumps :n, end passes through.
(defn- write-cells! [dir start-body]
  (fs/create-dirs dir)
  (spit (str dir "/mini.clj")
        (str "(ns cells.mini (:require [mycelium.cell :as cell]))\n"
             "(cell/defcell :mini/start {:doc \"start\" :pure true}\n  " start-body ")\n"
             "(cell/defcell :mini/end {:doc \"end\" :pure true}\n"
             "  (fn [_ d] (assoc d :verdict :done)))\n")))

(def ^:private mini-def
  '{:cells {:start :mini/start :end :mini/end}
    :edges {:start {:go :end} :end :end}
    :dispatches {:start [[:go (fn [d] true)]]}})

(defn- opts []
  {:dirs [(str @root "/cells")]
   :loop-def mini-def
   :soak-input {:n 0}})

(use-fixtures :each
  (fn [f]
    (cell/clear-registry!)
    (reset! root (str "/tmp/samizdat-mut-" (random-uuid)))
    (try (f) (finally (fs/delete-tree @root) (cell/clear-registry!)))))

;; --- a good edit commits ----------------------------------------------------

(deftest a-valid-edit-commits-and-takes-effect
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  (is (= 1 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0}))))
  ;; edit the cell on disk to a new valid behavior, then apply the protocol
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n + 10))")
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :committed (:status r)))
    (is (= 10 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))
        "the committed edit is live in the registry")))

;; --- a syntax error rolls back ----------------------------------------------

(deftest a-syntax-error-rolls-back
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  ;; corrupt the cell file with unbalanced/invalid code
  (spit (str @root "/cells/mini.clj") "(ns cells.mini)\n(this is not valid")
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :rolled-back (:status r)))
    (is (str/includes? (str/lower-case (:reason r)) "reload"))
    (testing "the prior good cell survives the failed reload"
      (is (= 1 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))))))

;; --- a wiring break rolls back (validate) -----------------------------------

(deftest a-cell-that-vanishes-fails-validate-and-rolls-back
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  ;; rewrite the file to define only :mini/start — :mini/end that the manifest
  ;; wires to is gone, so compile-loop cannot resolve it.
  (spit (str @root "/cells/mini.clj")
        (str "(ns cells.mini (:require [mycelium.cell :as cell]))\n"
             "(cell/defcell :mini/start {:doc \"s\" :pure true} (fn [_ d] d))\n"))
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :rolled-back (:status r)))
    (is (str/includes? (str/lower-case (:reason r)) "validate"))
    (testing "both original cells are restored"
      (is (some? (cell/get-cell :mini/start)))
      (is (some? (cell/get-cell :mini/end))))))

;; --- a cell that throws on valid input rolls back (soak) ---------------------

(deftest a-cell-that-throws-on-soak-rolls-back
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  ;; compiles fine and wires fine, but throws when actually run on {:n 0}
  (write-cells! (str @root "/cells") "(fn [_ d] (throw (ex-info \"boom\" {})))")
  (let [r (mut/apply-cell-edit! (opts))]
    (is (= :rolled-back (:status r)))
    (is (str/includes? (str/lower-case (:reason r)) "soak"))
    (testing "the last good cell is restored — the loop is not bricked"
      (is (= 1 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))))))

;; --- the record -------------------------------------------------------------

(deftest a-rollback-is-journaled-as-a-negative-constraint
  (with-open []
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
      (cells/load-cells! (:dirs (opts)))
      (spit (str @root "/cells/mini.clj") "(ns cells.mini)\n(broken")
      (mut/apply-cell-edit! (assoc (opts) :conn conn :run-id rid))
      (let [events (journal/events-since conn rid 0 100)]
        (is (some #(= "mutation-rolled-back" (:kind %)) events)
            "the failed mutation is on the record for the agent to learn from")))))

;; --- the store-backed proposal (per-project userspace) -----------------------

(deftest a-good-proposal-commits-a-project-version
  ;; The point of the store path: the edit is a version of THIS project's cell,
  ;; and the shipped template is never written.
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  (let [c (db/open! ":memory:")]
    (try
      (us/bind! c)
      (let [body (str "(ns cells.mini (:require [mycelium.cell :as cell]))\n"
                      "(cell/defcell :mini/start {:doc \"s\" :pure true}\n"
                      "  (fn [_ d] (update d :n + 100)))\n")
            r (mut/propose-cell! (assoc (opts) :name "mini" :body body))]
        (is (= :committed (:status r)))
        (is (= 1 (:version r)))
        (is (= 100 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))
            "the committed proposal is live in the registry")
        (is (= body (us/body :cell "mini"))
            "and durable in the project's store"))
      (finally (us/unbind!) (db/close c)))))

(deftest a-bad-proposal-never-enters-the-projects-history
  ;; The inversion versus the file path: nothing is written until the candidate
  ;; survives, so the version history holds only bodies that were once live.
  ;; The ATTEMPT is recorded in the journal instead.
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (try
      (us/bind! c)
      (testing "a syntax error"
        (let [r (mut/propose-cell! (assoc (opts) :name "mini" :body "(ns cells.mini"
                                          :conn c :run-id rid))]
          (is (= :rolled-back (:status r)))
          (is (re-find #"did not load" (:reason r)))))
      (testing "a cell that breaks the wiring"
        (let [r (mut/propose-cell!
                 (assoc (opts) :name "mini" :conn c :run-id rid
                        :body (str "(ns cells.mini (:require [mycelium.cell :as cell]))\n"
                                   "(cell/defcell :mini/other {:doc \"o\" :pure true}\n"
                                   "  (fn [_ d] d))\n")))]
          ;; :mini/start survives in the registry from the load above, so the
          ;; loop still compiles — what matters is that nothing was stored.
          (is (contains? #{:committed :rolled-back} (:status r)))))
      (testing "nothing bad reached the store"
        (is (not (re-find #"cells\.mini$" (str (us/body :cell "mini"))))
            "a truncated body is not what the project would load next run"))
      (testing "and the attempt is journaled with its reason"
        (is (seq (filter #(re-find #"mutation-rolled-back" (str (:kind %)))
                         (journal/events-since c rid 0)))))
      (finally (us/unbind!) (db/close c)))))

(deftest an-edit-that-breaks-another-manifest-never-goes-live
  ;; Validating one loop-def let a cell wired only into the beam or a team
  ;; manifest commit whatever it broke — the active loop never references it,
  ;; so its validate passed trivially (karamazov-blt.2). :extra-defs carries
  ;; every other manifest through the same compile.
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  ;; a second cell, wired only into a NON-loop manifest
  (binding [*ns* *ns*]
    (load-string (str "(ns cells.other (:require [mycelium.cell :as cell]))\n"
                      "(cell/defcell :other/thing {:doc \"x\" :pure true}\n"
                      "  (fn [_ d] d))\n")))
  (let [extra {"beamish" '{:cells {:start :other/thing :end :mini/end}
                           :edges {:start {:go :end} :end :end}
                           :dispatches {:start [[:go (fn [d] true)]]}}}
        ;; the candidate re-declares :other/thing to require a ctx key no
        ;; driver provides — invisible to the mini loop, fatal to "beamish"
        candidate (str "(ns cells.other (:require [mycelium.cell :as cell]))\n"
                       "(cell/defcell :other/thing {:doc \"x\" :pure true"
                       " :requires [:no-such-ctx-key]}\n"
                       "  (fn [_ d] d))\n")
        r (mut/propose-cell! {:name "other" :body candidate
                              :loop-def mini-def
                              :extra-defs extra
                              :compile-fn manifests/compile-definition
                              :soak-input {:n 0}})]
    (is (= :rolled-back (:status r)))
    (is (str/includes? (str (:reason r)) "beamish")
        "the reason names which manifest the edit broke")
    (is (empty? (:requires (cell/get-cell :other/thing)))
        "the registry was restored — the candidate's :requires is gone")))

(deftest the-dir-protocol-refuses-a-store-mode-image
  ;; After a store-mode load, loaded-file-content is keyed by store NAMES;
  ;; apply-cell-edit!'s rollback would spit those names into the cwd as files
  ;; and its reload would regress live cells to the templates
  ;; (karamazov-blt.7). It now refuses instead of corrupting.
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (let [c (db/open! ":memory:")]
    (try
      (us/bind! c)
      (cells/load-cells!)                        ;; the PRODUCTION, store-mode load
      (let [r (mut/apply-cell-edit! (opts))]
        (is (= :rolled-back (:status r)))
        (is (= :store-mode-image (:reason r))
            "the reason is data; the sentence is a caller's to render"))
      (finally (us/unbind!) (db/close c)))))

(deftest an-unbound-proposal-is-not-reported-committed
  ;; userspace/save! returns nil when no project store is bound, and the
  ;; commit branch used to report {:status :committed :version nil} — the tool
  ;; then told the model "Saved as v … live on your next turn" about an edit
  ;; that vanishes on restart (karamazov-blt.8). The candidate IS live in the
  ;; image (it compiled and soaked), so this is not a rollback either; the
  ;; caller hears exactly what happened.
  (write-cells! (str @root "/cells") "(fn [_ d] (update d :n inc))")
  (cells/load-cells! (:dirs (opts)))
  (us/unbind!)
  (let [r (mut/propose-cell!
           {:name "mini"
            :body (str "(ns cells.mini (:require [mycelium.cell :as cell]))\n"
                       "(cell/defcell :mini/start {:doc \"start\" :pure true}\n"
                       "  (fn [_ d] (update d :n + 7)))\n"
                       "(cell/defcell :mini/end {:doc \"end\" :pure true}\n"
                       "  (fn [_ d] (assoc d :verdict :done)))\n")
            :loop-def mini-def
            :soak-input {:n 0}})]
    (is (= :live-unsaved (:status r))
        "not :committed — nothing entered any project's history")
    (is (nil? (:version r)))
    (is (= :unbound (:reason r))
        "the reason is data; the sentence is the tool's to render")
    (is (= 7 (:n ((:handler (cell/get-cell :mini/start)) {} {:n 0})))
        "the candidate stays live in this process, as validate and soak left it")))

(deftest the-shipped-template-is-never-written
  (let [c (db/open! ":memory:")
        before (us/template :cell "loop")]
    (try
      (us/bind! c)
      (us/save! :cell "loop" ";; this project's own loop")
      (is (= ";; this project's own loop" (us/body :cell "loop")))
      (is (= before (us/template :cell "loop"))
          "the harness's own file is untouched — that is what makes it a template")
      (finally (us/unbind!) (db/close c)))))

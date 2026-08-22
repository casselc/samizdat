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

(ns samizdat.cells-test
  "The cell loader: the kernel is cell-agnostic — it loads whatever cell
  definitions live in resources (and .samizdat overrides), registers them, and
  can reload them into the live image. No cell is baked into src."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [jolt.fs :as fs]
            [mycelium.cell :as cell]
            [samizdat.cells :as cells]))

(def ^:private tmp (atom nil))

(defn- cell-file! [dir id-kw body]
  (fs/create-dirs dir)
  (spit (str dir "/" (name id-kw) ".clj")
        (str "(ns cells.gen." (name id-kw)
             " (:require [mycelium.cell :as cell]))\n"
             "(cell/defcell " id-kw " {:doc \"a generated cell\" :pure true}\n"
             "  " body ")\n")))

(use-fixtures :each
  (fn [f]
    (cell/clear-registry!)
    (reset! tmp (str "/tmp/samizdat-cells-" (random-uuid)))
    (try (f) (finally (fs/delete-tree @tmp) (cell/clear-registry!)))))

;; --- loading ----------------------------------------------------------------

(deftest loads-every-cell-file-in-a-dir
  (let [d (str @tmp "/cells")]
    (cell-file! d :gen/a "(fn [_ data] (assoc data :a 1))")
    (cell-file! d :gen/b "(fn [_ data] (assoc data :b 2))")
    (cells/load-cells! [d])
    (is (some? (cell/get-cell :gen/a)))
    (is (some? (cell/get-cell :gen/b)))
    (testing "the handlers actually run"
      (is (= {:a 1} ((:handler (cell/get-cell :gen/a)) {} {})))))
  (testing "loaded reports what was registered and from where"
    (is (contains? (set (keys (cells/loaded))) :gen/a))))

(deftest a-later-dir-overrides-an-earlier-cell
  ;; builtin (resources/cells) then project (.samizdat/cells): a project cell
  ;; with the same id wins.
  (let [base (str @tmp "/base") proj (str @tmp "/proj")]
    (cell-file! base :ov/c "(fn [_ data] (assoc data :from :base))")
    (cell-file! proj :ov/c "(fn [_ data] (assoc data :from :proj))")
    (cells/load-cells! [base proj])
    (is (= {:from :proj} ((:handler (cell/get-cell :ov/c)) {} {})))))

;; --- transactional rollback -------------------------------------------------

(deftest a-broken-cell-file-rolls-the-whole-load-back
  (let [d (str @tmp "/cells")]
    ;; a good cell registered from a PRIOR load — must survive a failed reload
    (cell-file! d :keep/good "(fn [_ data] data)")
    (cells/load-cells! [d])
    (is (some? (cell/get-cell :keep/good)))
    ;; now add a broken file and a would-be new cell, then reload
    (cell-file! d :new/one "(fn [_ data] data)")
    (spit (str d "/broken.clj") "(ns cells.gen.broken)\n(this is not valid clojure")
    (is (thrown? Exception (cells/load-cells! [d])))
    (testing "the registry is restored to its pre-load state — no partial load"
      (is (some? (cell/get-cell :keep/good)) "the previously-good cell survives")
      (is (nil? (cell/get-cell :new/one)) "the new cell from the failed load is not left registered"))))

;; --- hot reload -------------------------------------------------------------

(deftest reload-picks-up-an-edited-cell
  (let [d (str @tmp "/cells")]
    (cell-file! d :hot/x "(fn [_ data] (assoc data :v 1))")
    (cells/load-cells! [d])
    (is (= 1 (:v ((:handler (cell/get-cell :hot/x)) {} {}))))
    ;; edit the cell on disk and reload — the live registry reflects it
    (cell-file! d :hot/x "(fn [_ data] (assoc data :v 2))")
    (cells/load-cells! [d])
    (is (= 2 (:v ((:handler (cell/get-cell :hot/x)) {} {}))))))

;; --- the shipped loop cells load from resources -----------------------------

(deftest the-loop-cells-load-from-resources
  ;; No cell is compiled into src: loading from resources/cells registers the
  ;; whole loop. This is the acceptance — the kernel is cell-agnostic.
  (cells/load-cells!)
  (doseq [id [:loop/assemble :llm/infer :llm/parse :tool/dispatch
              :journal/record :gate/arbiter :loop/route :loop/finish]]
    (is (some? (cell/get-cell id)) (str id " loaded from resources")))
  (testing "every loaded loop cell declares its effects (pure or a set)"
    (doseq [id (keys (cells/loaded))]
      (is (cell/effects-declared? (cell/get-cell id))
          (str id " must declare :pure or :effects")))))

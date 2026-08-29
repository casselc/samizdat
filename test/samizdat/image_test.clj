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

(ns samizdat.image-test
  "The sandboxed project image, spawned for real.

  ONE TEST HERE STARTS A PROCESS, on purpose. Everything this round exists to
  do is a property of a running image under a real kernel policy — that the
  cwd is the project, that a write to $HOME is refused, that the shell is not
  reachable — and none of it can be asserted against a string. The pure parts
  are tested as pure parts; the confinement is tested by trying to escape it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.repl.image :as image]
            [samizdat.security.sandbox :as sandbox]))

;; --- pure -------------------------------------------------------------------

(deftest spawn-argv-wraps-for-the-backend
  (is (= ["jolt" "nrepl-server" "7888"]
         (image/spawn-argv :none "/p.sb" 7888)))
  (is (= ["sandbox-exec" "-f" "/p.sb" "jolt" "nrepl-server" "7888"]
         (image/spawn-argv :seatbelt "/p.sb" 7888))))

(deftest free-port-is-actually-free
  (let [p (image/free-port)]
    (is (pos? p))
    (is (with-open [s (java.net.ServerSocket. p)] (= p (.getLocalPort s)))
        "free-port handed back a port something was already holding")))

;; --- the running image ------------------------------------------------------

(defn- project!
  "A minimal project tree with one readable asset."
  []
  (let [root (str (fs/create-temp-dir))]
    (spit (str root "/deps.edn") (pr-str {:paths ["src"]}))
    (fs/create-dirs (str root "/resources"))
    (spit (str root "/resources/asset.txt") "asset-ok")
    root))

(deftest a-sandboxed-image-works-on-the-project-and-cannot-leave-it
  ;; The whole bead, end to end. If this passes, karamazov-zrq is closable
  ;; once the routing in round 4 points eval here.
  (let [root (project!)
        home (System/getenv "HOME")
        escape (str home "/SAMIZDAT-IMAGE-TEST-ESCAPE.txt")
        im (image/start! {:root root
                          :backend (sandbox/backend-for :auto (System/getProperty "os.name"))
                          :sandbox-spec {:deny-read [(str home "/.ssh") "/etc"]
                                         :exec-roots ["/opt/homebrew" "/usr/bin"]}})]
    (try
      (is (some? im) "the image did not come up at all")
      (when im
        (testing "it evaluates"
          (is (= "3" (:value (image/eval-in im "(+ 1 2)")))))

        (testing "relative paths resolve in the PROJECT, not the harness"
          ;; The bug warn-if-not-cwd! exists to shout about. A harness-rooted
          ;; image answers this with samizdat's own tree.
          (let [r (image/eval-in im "(str (.getCanonicalFile (java.io.File. \".\")))")]
            (is (:ok r))
            (is (str/includes? (str (:value r)) (str (fs/canonicalize root)))
                "the image's cwd is not the project root")))

        (testing "the project's own files are readable — this is the point of it"
          (is (= "\"asset-ok\""
                 (:value (image/eval-in im "(clojure.string/trim (slurp \"resources/asset.txt\"))")))))

        (when (= :seatbelt (:backend im))
          (testing "but it cannot write outside the project"
            (image/eval-in im (str "(try (spit \"" escape "\" \"x\") (catch Throwable _ nil))"))
            (is (not (.exists (java.io.File. escape)))
                "the image wrote into $HOME"))

          (testing "and it cannot reach a shell"
            (let [r (image/eval-in im "(do (require 'jolt.process)
                                           (try (:out (jolt.process/sh \"echo\" \"X\"))
                                                (catch Throwable e (str \"DENIED \" (.getMessage e)))))")]
              (is (str/includes? (str (:value r)) "DENIED")
                  "the REPL shelled out of the sandbox")))

          (testing "and it cannot read the harness's own source"
            ;; The first move of the observed escape.
            (let [r (image/eval-in im "(try (slurp \"src/samizdat/agent/tools/introspect.clj\")
                                            (catch Throwable _ :DENIED))")]
              (is (= ":DENIED" (:value r)))))))
      (finally
        (image/stop! im)
        (.delete (java.io.File. escape))))))

(deftest stopping-an-image-kills-it-and-is-idempotent
  (let [root (project!)
        im (image/start! {:root root
                          :backend (sandbox/backend-for :auto (System/getProperty "os.name"))
                          :sandbox-spec {:deny-read [] :exec-roots ["/opt/homebrew" "/usr/bin"]}})]
    (is (some? im))
    (when im
      (is (image/alive? im))
      (image/stop! im)
      (is (not (image/alive? im)) "the image survived its own teardown")
      (testing "the profile does not outlive the image it confined"
        (is (not (.exists (java.io.File. ^String (:profile im))))))
      (testing "stopping twice is not an error"
        (is (nil? (image/stop! im)))))))

(deftest stopping-nothing-is-not-an-error
  ;; start! returns nil on failure and callers tear down in a finally, so this
  ;; is the common path on a bad profile — it must not mask the real error.
  (is (nil? (image/stop! nil))))

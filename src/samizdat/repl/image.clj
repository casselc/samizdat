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

(ns samizdat.repl.image
  "The PROJECT image: a `jolt nrepl-server` subprocess rooted at the project,
  under the sandbox, that the harness evaluates into over loopback.

  WHY A SUBPROCESS AT ALL, separately from the sandbox. Three bugs share one
  cause — `eval` running in the harness process:

  - Confinement. A run could slurp harness source by relative path, patch it,
    and `(require … :reload)` it into the LIVE image, past the file tools'
    root confinement, the shell policy, and the mutation protocol
    (karamazov-zrq, run a3ba69bb S2).
  - Classpath. Workers on another project complained `eval` could not see
    that project's namespaces, because the harness image's source roots are
    samizdat's.
  - Working directory. `(slurp \"README.md\")` inside `eval` read SAMIZDAT's
    README and answered plausibly, because relative paths resolved against
    the harness's cwd. `samizdat.repl/warn-if-not-cwd!` exists only to shout
    about that, and this is the fix it could not be.

  Moving evaluation into a process whose cwd IS the project root fixes all
  three, and it fixes the last two even with `:sandbox :none` — which is why
  the container case loses nothing that matters.

  THE PROFILE LIVES WHERE THE IMAGE CANNOT WRITE. The image gets its own
  scratch directory rather than all of /tmp, and the profile is written
  somewhere outside every writable path. A confined process that can rewrite
  its own confinement has none the moment anything restarts it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.fs :as fs]
            [jolt.process :as process]
            [nrepl.transport :as transport]
            [samizdat.agent.gates :as gates]
            [samizdat.prompt :as prompt]
            [samizdat.security.sandbox :as sandbox]))

(defn connect-timeout-ms
  "How long to wait for the image's nREPL port to answer. gates.edn
  :image-connect-ms.

  Userspace-tunable, and safe to be: shrinking it cannot buy an escape,
  because an image that fails to start fails the eval and never falls back to
  the harness image."
  []
  (gates/threshold :image-connect-ms))

(defn free-port
  "A port nothing is listening on. Racy by nature — the gap between closing
  this socket and the child binding it is unavoidable without passing an
  inherited fd — but it is the standard answer and a lost race surfaces as a
  clean bind failure rather than a wrong answer."
  []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn spawn-argv
  "The argv that starts the image: `jolt nrepl-server <port>`, wrapped for the
  backend. Pure."
  [backend profile-path port]
  (sandbox/wrap backend profile-path ["jolt" "nrepl-server" (str port)]))

(defn- await-port!
  "Block until `port` accepts a connection, or `deadline-ms` passes. True when
  it came up."
  [port deadline-ms]
  (let [end (+ (System/currentTimeMillis) deadline-ms)]
    (loop []
      (if (try (with-open [_ (java.net.Socket. "127.0.0.1" (int port))] true)
               (catch Exception _ false))
        true
        (when (< (System/currentTimeMillis) end)
          (Thread/sleep 100)
          (recur))))))

(defn start!
  "Start a project image at `root` and return it, or nil when it could not be
  started.

  `:backend` decides confinement; `:sandbox-spec` is the paths it confines,
  minus the scratch and profile locations, which are made here precisely so
  the image cannot write the file that confines it."
  [{:keys [root backend sandbox-spec]}]
  (let [scratch (str (fs/create-temp-dir))
        ;; A SEPARATE directory from the scratch, and not under any writable
        ;; path: this is the file that says what the image may do.
        profile-dir (str (fs/create-temp-dir))
        profile (str (io/file profile-dir "image.sb"))
        port (free-port)]
    (spit profile (sandbox/seatbelt-profile
                   (assoc sandbox-spec
                          :project-root root
                          :scratch-paths [scratch])))
    (let [argv (spawn-argv backend profile port)
          proc (process/process argv {:dir (str root)})]
      (if (await-port! port (connect-timeout-ms))
        (do (log/info "project image up on" port "rooted at" root
                      (if (= :none backend) "(unsandboxed)" (str "under " (name backend))))
            {:proc proc :port port :root (str root) :backend backend
             :profile profile :scratch scratch
             :transport (transport/connect "127.0.0.1" port)})
        (do (log/error "project image did not come up on" port
                       "— profile at" profile)
            (try (process/destroy-tree proc) (catch Exception _ nil))
            nil)))))

(defn- reap-timeout-ms
  "How long to wait for a killed image to actually be gone. gates.edn
  :image-reap-ms.

  DESTROYING IS ASYNCHRONOUS. `destroy-tree` signals and returns, and the
  first version of `stop!` returned with it — so the image was still alive the
  instant afterwards, and a long-lived `serve` process would have leaked one
  sandboxed nREPL server per run, each holding a port. Teardown is not done
  until the process is gone. The kill itself is unconditional, so this only
  decides how long to watch before warning."
  []
  (gates/threshold :image-reap-ms))

(defn- reaped?
  "Poll until `proc` is gone or the deadline passes."
  [proc]
  (let [end (+ (System/currentTimeMillis) (reap-timeout-ms))]
    (loop []
      (cond
        (not (try (process/alive? proc) (catch Exception _ false))) true
        (< end (System/currentTimeMillis)) false
        :else (do (Thread/sleep 50) (recur))))))

(defn stop!
  "Tear an image down: close the connection, kill the process TREE, wait for it
  to actually die, drop the profile. Idempotent and never throws — teardown
  runs on paths that are already failing, and a teardown that throws loses the
  original error."
  [{:keys [proc transport profile scratch] :as image}]
  (when image
    (try (some-> transport transport/close) (catch Exception _ nil))
    (try (some-> proc process/destroy-tree) (catch Exception _ nil))
    (when (and proc (not (reaped? proc)))
      ;; Say so rather than leaking quietly. A survivor holds a port and a
      ;; sandbox, and the next run's free-port will simply route around it.
      (log/warn "project image did not die within" (reap-timeout-ms)
                "ms — it may be holding port" (:port image)))
    ;; The profile is the last thing to go: it is evidence while anything is
    ;; still running, and litter afterwards.
    (doseq [d [profile scratch]]
      (try (when d (fs/delete-tree (io/file d))) (catch Exception _ nil))))
  nil)

(defn- collect
  "Drain one nREPL eval exchange into `{:value :out :err}`.

  The value and the `done` status arrive in the SAME message, so a loop that
  returns on `done` before reading the message it saw it in loses the answer
  every time — which is what the first version of this did."
  [t]
  (loop [acc {:value nil :out [] :err []}]
    (let [m (transport/recv t)
          acc (cond-> acc
                (get m "value") (assoc :value (get m "value"))
                (get m "out") (update :out conj (get m "out"))
                (get m "err") (update :err conj (get m "err")))]
      (if (some #{"done"} (get m "status"))
        (-> acc
            (update :out #(str/join "" %))
            (update :err #(str/join "" %)))
        (recur acc)))))

(defn eval-in
  "Evaluate `code` in the image. Same result shape as `samizdat.repl/eval-code`
  so the caller cannot tell which image answered except by asking."
  [{:keys [transport]} code]
  (try
    (transport/send transport {"op" "eval" "code" (str code)})
    (let [{:keys [value out err]} (collect transport)]
      (if (str/blank? err)
        {:ok true :value value :out out}
        {:ok false :error err :out out}))
    (catch Exception e
      {:ok false
       :error (prompt/render "image-down" {:detail (ex-message e)})
       :error-type "image-down"})))

(defn alive?
  [{:keys [proc]}]
  (boolean (try (some-> proc process/alive?) (catch Exception _ false))))

;; samizdat - a claim-first verification harness
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

(ns samizdat.telemetry.hook
  "The fail-open observation seam the store calls at its lifecycle
  boundaries. Dependency-free and inert by default: with no observer
  installed `observe!` simply calls the thunk, so the stock build carries no
  telemetry dependency and no behaviour change (source mode). The :telemetry
  alias installs an observer (samizdat.telemetry.otel/install!); a woven
  build would instead advise the same seams directly.

  The observer receives `(observer kind attrs thunk)` and must call the thunk
  exactly once, returning its value. Whatever the observer does wrong — throws
  before, throws after, forgets to call it — the seam guarantees the thunk
  still runs exactly once and its own value or exception is what the caller
  sees. Telemetry can lose a span; it can never change a store write.")

(defonce ^{:doc "nil, or (fn [kind attrs thunk] ...)."} observer (atom nil))

(defn install! [f] (reset! observer f))
(defn uninstall! [] (reset! observer nil))
(defn installed? [] (some? @observer))

(defn observe!
  "Run `thunk` under the installed observer, if any. See the namespace doc for
  the exactly-once guarantee."
  [kind attrs thunk]
  (if-let [o @observer]
    (let [state (volatile! {:ran false})
          thunk* (fn []
                   (let [{:keys [ran ok value error]} @state]
                     (cond
                       ;; A second call from a misbehaving observer replays
                       ;; the recorded outcome; the real thunk never re-runs.
                       (and ran ok) value
                       ran (throw error)
                       :else
                       (do (vswap! state assoc :ran true)
                           (try (let [v (thunk)]
                                  (vswap! state assoc :ok true :value v)
                                  v)
                                (catch Throwable e
                                  (vswap! state assoc :ok false :error e)
                                  (throw e)))))))]
      (try (o kind attrs thunk*)
           (catch Throwable _ nil))
      (let [{:keys [ran ok value error]} @state]
        (cond (not ran) (thunk)
              ok value
              :else (throw error))))
    (thunk)))

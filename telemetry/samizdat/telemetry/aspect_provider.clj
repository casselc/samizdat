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
(ns samizdat.telemetry.aspect-provider
  "Advice provider for the observability pack under
  resources/META-INF/jolt/aspects/:

    samizdat-observability-run-83eb99a.edn  nine harness run seams, verified against
                                            upstream main 83eb99a (role :samizdat.telemetry/run)

  The :samizdat.telemetry/lifecycle role is kept for the pilot lineage's
  lifecycle pack (samizdat.store.lifecycle is not on this tree).

  A woven build advises the entries directly: each role wraps its join point
  in one span (lifecycle.<op> / seam.<op>) carrying the aspect id and the
  weaver's site id. The :proceed-v1 advice sees no arguments and no result,
  so a woven span carries identity only; the source-mode hook
  (samizdat.telemetry.hook) is what sees the seam's facts. Both paths use the
  same tracer, contract and pipelines."
  (:require [clojure.string :as str]
            [samizdat.telemetry.otel :as otel]))

(defn op-name [join-point]
  (let [id (:id join-point)]
    (if (keyword? id) (name id) (str id))))

(defn lifecycle-around
  "Around advice: span lifecycle.<op> around proceed. The application value is
  preserved by the weaver's invoke-around; this function's own return is ignored."
  [join-point proceed]
  (otel/with-observation [sp :span (str "lifecycle." (op-name join-point))
                          {"samizdat.lifecycle.op" (op-name join-point)
                           "samizdat.execution.kind" "lifecycle"
                           "samizdat.aspect.id" (some-> (:id join-point) str)
                           "samizdat.aspect.site_id" (some-> (:site-id join-point) str)
                           "samizdat.aspect.build_identity" (some-> (:build-identity join-point) str)}]
    (proceed)
    :ignored-provider-result))

(defn run-around
  "Around advice for the harness run seams: span seam.<id-name> around proceed,
  identity only (the source-mode hook carries the seam's facts)."
  [join-point proceed]
  (otel/with-observation [sp :span (str "seam." (op-name join-point))
                          {"samizdat.execution.kind" "run"
                           "samizdat.aspect.id" (some-> (:id join-point) str)
                           "samizdat.aspect.site_id" (some-> (:site-id join-point) str)
                           "samizdat.aspect.build_identity" (some-> (:build-identity join-point) str)}]
    (proceed)
    :ignored-provider-result))

(def aspect-provider
  {:schema 1
   :libraries {'yogthos/samizdat "83eb99a"}
   :roles {:samizdat.telemetry/lifecycle 'samizdat.telemetry.aspect-provider/lifecycle-around
           :samizdat.telemetry/run 'samizdat.telemetry.aspect-provider/run-around}})

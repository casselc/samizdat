;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.roles
  "WHICH WORLD EACH ROLE SEES. Mechanism only — the table is resources/roles.edn.

  Every role used to be built the same way: the implementer's system prompt
  plus a role suffix. That made role differentiation SUBTRACTIVE — a
  supervisor was handed 31 tools written for somebody building the project,
  and its own prompt then spent a paragraph arguing it back out of them. The
  argument does not always work: one supervisor spent 108 of a run's 211 turns
  hunting a source tree it was never going to be allowed to open
  (karamazov-i1u).

  Here a role is CONSTRUCTED. `surface` is what it may call, `scope-catalogue`
  is the tool documentation filtered to that surface, and a call outside it is
  refused rather than discouraged — so the prompt no longer has to talk the
  model out of a capability the prompt itself advertised.

  What this does NOT do is seal roles off from each other. Each already runs
  on its own branch with its own message stream and that is unchanged; the
  supervisor investigates across the boundary on purpose, and what it learns
  arrives as a tool result in its own stream rather than as inherited
  context."
  (:require [clojure.string :as str]
            [samizdat.userspace :as userspace]))

(defn table
  "The role table from roles.edn, through the userspace seam so a project can
  retune its own roles without a rebuild."
  []
  (userspace/edn-body! :policy "roles"))

(defn names [] (sort (keys (table))))

(defn spec [role] (get (table) (keyword role)))

(defn doc [role] (str (:doc (spec role))))

(defn sees
  "What this role's opening context is assembled from. Documentation for the
  reader and for a supervisor deciding what to change."
  [role]
  (:sees (spec role) #{}))

(defn all-tool-names
  "Every registered tool name. Resolved late so this namespace does not depend
  on the tool tree at load — roles.edn is read by prompt assembly, which runs
  earlier than the tool registry in some entry points."
  []
  (when-let [v (resolve 'samizdat.agent.tools/tool-names)]
    ;; tool-names is a FN, not a var holding a set: deref the var to get it,
    ;; then call it.
    (set (@v))))

(defn surface
  "The tool names `role` may call: a set, or `:all`.

  `:all` stays a SENTINEL rather than expanding to the registry. Expanding it
  needs samizdat.agent.tools loaded, and roles are read during prompt
  assembly, which in some entry points runs first — so an expansion would
  quietly return the empty set and hand the implementor NO tools. A sentinel
  cannot fail that way: the two callers below both treat it as unrestricted
  without ever asking what the registry holds."
  [role]
  (let [t (:tools (spec role) :all)]
    (cond
      (= :all t) :all
      (set? t) t
      (coll? t) (set (map str t))
      :else #{})))

(defn may-use?
  "Whether `role` may call `tool`. A role the table does not name is
  unrestricted — roles are opt-in, so adding one cannot silently disarm a
  workflow that never had one."
  [role tool]
  (let [s (surface role)
        denied (set (map str (:denied (spec role))))]
    (and (not (contains? denied (str tool)))
         (or (nil? (spec role)) (= :all s) (contains? s (str tool))))))

;; --- the tool catalogue, filtered -------------------------------------------
;;
;; system.md documents each tool as a `name({args})` line at column zero
;; followed by indented prose. The prose stays HAND WRITTEN — a generated
;; prompt reads like one — and only WHICH entries appear is computed.

(def ^:private entry-head
  "A tool catalogue entry's opening line: `name({…})` hard against the margin."
  #"^([a-z_][a-z0-9_]*)\(\{")

(defn scope-catalogue
  "`text` with every tool entry the role may not use removed, prose and all.
  A nil role, or one the table does not name, gets the text unchanged."
  [text role]
  (if (or (nil? (spec role)) (= :all (surface role)))
    text
    (let [allowed (surface role)]
      (->> (str/split-lines (str text))
           (reduce (fn [{:keys [out keep?] :as acc} line]
                     (if-let [[_ nm] (re-find entry-head line)]
                       ;; A new entry decides for itself and for the indented
                       ;; prose that follows it.
                       (let [k (contains? allowed nm)]
                         (assoc acc :keep? k :out (cond-> out k (conj line))))
                       ;; Anything else belongs to the entry above it, unless
                       ;; we are not inside one at all.
                       (if (and (not keep?) (re-find #"^\s+\S" line))
                         acc
                         (assoc acc :out (conj out line) :keep? true))))
                   {:out [] :keep? true})
           :out
           (str/join "\n")))))

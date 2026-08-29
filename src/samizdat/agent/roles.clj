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

(def ^:private section-head
  "A catalogue section: `### Doing work`. Sections are what group entries, and
  a section's PROSE is written about the entries under it."
  #"^###\s+(.*)$")

(defn- sections
  "The text split into `{:head line-or-nil :lines [...]}` at every `###`,
  in order. Anything before the first heading is one headless section."
  [lines]
  (reduce (fn [acc line]
            (if (re-find section-head line)
              (conj acc {:head line :lines []})
              (if (seq acc)
                (update-in acc [(dec (count acc)) :lines] conj line)
                [{:head nil :lines [line]}])))
          []
          lines))

(defn- filter-entries
  "One section's lines with every entry the role may not call removed, along
  with the indented prose that belongs to it."
  [lines allowed]
  (:out (reduce (fn [{:keys [keep?] :as acc} line]
                  (if-let [[_ nm] (re-find entry-head line)]
                    ;; A new entry decides for itself and for the indented
                    ;; prose that follows it.
                    (let [k (contains? allowed nm)]
                      (assoc acc :keep? k :out (cond-> (:out acc) k (conj line))))
                    ;; Anything else belongs to the entry above it, unless we
                    ;; are not inside one at all.
                    (if (and (not keep?) (re-find #"^\s+\S" line))
                      acc
                      (assoc acc :out (conj (:out acc) line) :keep? true))))
                {:out [] :keep? true}
                lines)))

(defn scope-catalogue
  "`text` with every tool entry the role may not use removed, prose and all —
  and any catalogue SECTION whose entries were all removed dropped whole.

  The section rule is most of the win, and filtering entries alone missed it.
  The harness-mutation section is nine tool entries wrapped in four paragraphs
  explaining what cells and manifests are, why the loop belongs to the
  project, and where the line between base and userspace falls. Strip the
  entries and every word of that prose stays — thousands of characters telling
  a role about a capability it has just been shown it does not have, which is
  worse than telling it nothing.

  HAD entries and lost them all, not merely `has none`: a section that never
  documented a tool is prose standing on its own — the breadcrumb index, the
  turn format — and belongs to every role.

  A nil role, or one the table does not name, gets the text unchanged."
  [text role]
  (if (or (nil? (spec role)) (= :all (surface role)))
    text
    (let [allowed (surface role)]
      (->> (sections (str/split-lines (str text)))
           (mapcat (fn [{:keys [head lines]}]
                     (let [entries (keep #(second (re-find entry-head %)) lines)]
                       (if (and head (seq entries) (not-any? allowed entries))
                         nil
                         (cond->> (filter-entries lines allowed)
                           head (cons head))))))
           (str/join "\n")))))

# Structuring the loop: cells and manifests (mycelium)

Load this before you edit a cell or a manifest. Your agentic loop is a
**mycelium workflow**: a graph of **cells** (small data transforms) wired by a
**manifest** (edges + dispatch). This is how to change it well.

## The shape

- A **cell** is a function `(fn [ctx data] -> data')` registered by id.
- **`ctx`** (mycelium calls it resources) is the run's infrastructure —
  `:conn :run-id :llm-adapter :llm-config :root :max-turns`. Read-only.
- **`data`** is the workflow state flowing through the graph — for the loop it
  carries `:branch` and `:turn` plus per-turn products (`:call :parsed :signals
  :said :result :tool :verdict`). Cells add to it.
- A **manifest** (EDN) names the cells, the edges between them, and the
  dispatch predicates that pick an edge. `resources/manifests/loop.edn` is the
  default loop; the workflows table can hold many named ones.

## Writing a cell

Cells live in `resources/cells/*.clj` and are loaded at runtime. Declare its
effects — `:pure true` for a data-only transform, or `:effects [...]` naming
what it touches (`:net :db :fs :proc`). The manifest's static checks use this.

```clojure
(cell/defcell :loop/route
  {:doc "Decide the turn's verdict from the branch." :pure true}
  (fn [ctx {:keys [branch turn] :as data}]
    (assoc data :verdict (if (state/active? branch) :continue :done))))

(cell/defcell :llm/infer
  {:doc "One model call." :effects [:net :db]}
  (fn [ctx {:keys [branch] :as data}]
    (assoc data :call (turn/call-model ctx branch))))
```

Rules that matter:

1. **Return the data map, adding keys** — `(assoc data :new k)`. Downstream
   cells see everything upstream cells added. (Generic mycelium lets you return
   only the new keys; samizdat's loop cells thread the whole map with `assoc`
   because the branch and turn ride along — follow the existing cells.)
2. **Never return nil.** A cell must return a map. If it has nothing to add,
   return `data` unchanged.
3. **Declare effects honestly.** An undeclared effect is a compile warning; a
   pure cell that actually calls the model or writes the db is a lie the soak
   cannot catch.
4. **Errors are data, not exceptions.** Set a key (`:call {:ok false ...}`) and
   let a dispatch predicate route it, the way `:parse` routes `:provider-error`.
5. **One cell, one transform.** A cell that does two things wants to be two
   cells wired in sequence.

## The manifest

```clojure
{:cells {:start :loop/assemble   ; node-name -> cell-id
         :route :loop/route
         :finish :loop/finish}
 :edges {:start :route           ; node -> next node
         :route {:continue :start ; or a dispatch map: decision -> node
                 :done     :finish}
         :finish :end}            ; :end terminates
 :dispatches {:route [[:continue (fn [d] (= :continue (:verdict d)))]
                      [:done     (fn [d] (= :done (:verdict d)))]]}
 :constraints [{:type :must-follow :if :dispatch :then :journal}]}
```

- **Edges** are either one successor node, or a map of `decision -> node`.
- **Dispatch predicates** only READ a key the cell already computed
  (`:verdict`, `:critic/decision`). Keep them trivial — the decision logic
  belongs in the cell, the predicate just reads it, so the routing stays
  visible in the manifest. Predicates are EDN forms evaluated at compile time.
- **Constraints** make an invariant a compile-time error (e.g. a dispatched
  tool call must always be journalled).
- A node named in `:cells` must be reachable and its dispatch must cover every
  edge, or the compile fails — before anything runs.

## Changing it safely

You have three tools; use them in this order.

- **`reload_cells`** — after you EDIT a cell file in `resources/cells/`. It
  checkpoints, reloads, re-compiles the loop, and soaks (dry-runs) the change.
  Pass or fail, a bad edit is rolled back and the file restored. This is how a
  cell's behavior changes.
- **`manifest`** — to change the WIRING or add a whole alternative loop.
  `manifest save {name, edn}` compiles the manifest the way the loader will
  before storing it, so a manifest that can't run can't be saved. Which
  manifest a run uses is config (`:run :loop`); save a new version of the
  active one to tune it, or a new name to propose an alternative.
- **`introspect`** — see the wiring and this run's health before and after.

## Adding a step to the loop (worked example)

The `critic` manifest adds one node to the default loop: a judge on the `:done`
path. The recipe generalizes — add a node, point an edge at it, dispatch out.

1. Write the cell (`resources/cells/critic.clj`, id `:gate/critic`) that sets a
   decision key: `(assoc data :critic/decision :ship)` or `:revise`.
2. In the manifest, add the node to `:cells`, retarget the edge
   (`:route {:done :critic ...}`), add its out-edges
   (`:critic {:ship :finish :revise :start}`), and a dispatch on the key
   (`:critic [[:ship (fn [d] (= :ship (:critic/decision d)))] ...]`).
3. `manifest save` it (it compiles or it is refused), then run under it.

## Common mistakes

- Returning `nil` from a cell, or a value that is not a map → downstream break.
- Putting decision logic in a dispatch predicate instead of the cell → the
  routing stops being readable in the manifest.
- Editing a cell and not calling `reload_cells` → the running loop still uses
  the old version (the file change alone does nothing until you reload).
- A new node that nothing routes to, or whose dispatch misses an edge → the
  compile refuses it. Read the error; it names the gap.

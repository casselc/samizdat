# RFC-001 — Base and userspace

**Status:** implemented.

## Purpose

samizdat's agent modifies the loop it runs in, while it runs, per project. This
RFC specifies the seam that makes that possible: which half of the system is
fixed, which half belongs to the project, and how a read resolves.

## Scope

**This layer decides** where a definition comes from — the project's own version
or the shipped template — and nothing else.

**It must not know** what any body means. It moves text keyed by `(kind, name,
version)`; a cell, a manifest, a threshold table and a prompt are the same thing
to it.

**It hands** bodies to the loaders (`cells`, `workflow`, `gates`, `phases`,
`prompt`, `manual`) and rows to nobody else.

### The two halves

| | Base | Userspace |
|---|---|---|
| lives in | `src/`, compiled | `resources/` as a template, the project's db at runtime |
| holds | capabilities | the loop that assembles them |
| changed by | a rebuild | the agent, at runtime |
| examples | how to call a provider, run a tool, reach the db, render a template, compile a workflow | cells, manifests, policy tables, prompts |

The test for any new code: *could the agent change this about itself, at
runtime, without a rebuild?* If no, and the thing is a behaviour rather than a
capability, it is on the wrong side.

The asymmetry that justifies the rule: a capability in the base is available to
every project forever, while a **decision** in the base is one no project can
revise — including the agent whose job is to improve the loop.

## Model

```
resources/            TEMPLATE          shipped, read-only at runtime
   │  seed on first read (version 1)
   ▼
userspace table       THE PROJECT'S     append-only, versioned
   │  latest version
   ▼
loaders               LIVE IMAGE        cells load-stringed, manifests compiled
```

Four kinds, one lifecycle — seed, read latest, append on edit, revert by
re-appending:

| kind | body | consumed by |
|---|---|---|
| `:cell` | Clojure source | `samizdat.cells` → `load-string` |
| `:manifest` | EDN | `samizdat.workflow` → mycelium compile |
| `:policy` | EDN | `gates`, `phases`, `wordlists`, `manual`, `prompt-chain` |
| `:prompt` | markdown | `samizdat.prompt` → selmer |

A project is one database. The db path is cwd-relative (`samizdat.sqlite3`), so
a project *is* a directory, and two projects on one binary diverge without
either being able to affect the other.

## API

### `samizdat.userspace` — the read seam

Every loader in the base comes through here instead of `io/resource`.

| fn | contract |
|---|---|
| `(bind! conn)` | Point reads at this project. Returns the previous binding. Called once, by `system/start!`. |
| `(unbind!)` | Detach. Reads fall back to the template. |
| `(bound?)` / `(conn)` | Whether a project is bound, and its connection. |
| `(body kind name)` | The project's newest version, else the template **seeded as version 1 on the way past**, else `nil`. |
| `(body! kind name)` | As `body`, throwing when absent. For a caller whose operation is meaningless without it. |
| `(edn-body kind name)` / `(edn-body! …)` | `body` parsed as EDN. |
| `(template kind name)` | The shipped body, ignoring the project. `nil` when nothing ships under that name. |
| `(template-path kind name)` | The classpath resource a template lives at. |
| `(save! kind name body)` | Append a version. Returns the new version number, or `nil` when unbound. |
| `(revert! kind name version)` | Re-append an older body as the newest. |
| `(versions kind name)` / `(names kind)` | History of one; catalogue of a kind. |
| `(seed-all! kind template-names)` | Seed each named template, return `{name body}` for the kind. |
| `(invalidate!)` | Drop the read cache. |

**Contract — unbound is valid and serves the template.** A test, a REPL session
or a tool with no run behind it gets exactly the behaviour the harness had
before this layer existed. This is what allowed the layer to be introduced
without a flag day, and it is load-bearing: callers must not check `bound?`
before reading.

**Contract — `save!` returns `nil` rather than throwing when unbound.** Editing
userspace outside a run is a real situation; the caller should hear that nothing
was stored.

**Contract — seeding never overwrites.** A project that has evolved past version
1 is untouched by a later read.

### `samizdat.store.userspace` — the store

Pure SQL over one table. `(kind, name, version)` primary key, `body` TEXT,
append-only.

| fn | contract |
|---|---|
| `(load-latest conn kind name)` | Newest row, or `nil`. |
| `(load-version conn kind name version)` | That row, or `nil`. |
| `(versions conn kind name)` | Oldest first. |
| `(save! conn kind name body)` | Append; returns the new version. **No content comparison** — an edit that changed nothing is still a fact about what was tried. |
| `(seed! conn kind name body)` | Install as version 1 iff no version exists. Idempotent; returns the latest row either way. |
| `(revert! conn kind name version)` | Re-append that version's body as a new version. `nil` when it does not exist. |
| `(names conn kind)` | `{name, version, versions}` per name. |
| `(latest-bodies conn kind)` | `{name body}` at the newest version of each — one query for a loader that needs a whole kind. |

`kinds` is `#{:cell :manifest :policy :prompt}`; an unrecognised kind throws
rather than filing a row nothing will read again.

## Protocol

```
system/start!
  ├─ db/open! (project db)         [out] conn
  └─ userspace/bind! conn          [in]  every later read resolves per-project

cells/load-cells!         [in] userspace/seed-all! :cell   → load-string
workflow/load-loop!       [in] store.workflows (shim over :manifest)
gates/load-config         [in] userspace/edn-body! :policy "gates"
phases/load-phases        [in] userspace/edn-body! :policy "phases"
prompt/prompt             [in] userspace/body! :prompt
manual/entries            [in] userspace/edn-body! :policy "manual"

system/stop!
  └─ userspace/unbind! BEFORE db/close   (a read against a closed handle throws;
                                          the same read against no handle serves
                                          the template)
```

## Invariants

| invariant | enforced by |
|---|---|
| A read resolves to the project's version when it has one. | `body`'s `or` chain; `userspace-test`. |
| The shipped template is never written. | Only `save!`/`seed!` write, and both write to the db. Asserted by `the-project-evolves-and-the-template-does-not-follow`. |
| Two projects on one binary cannot affect each other. | Separate connections; asserted by `a-second-project-is-unaffected-by-the-first`. |
| Unbound reads behave as the pre-store harness did. | `read-body`'s `if-let` fallback; asserted by `unbound-reads-the-shipped-template`. |
| History is append-only; a revert is an edit. | No `UPDATE` in the store. |
| Nothing in `src/` decides what the harness does. | **Nothing mechanical.** Reviewed by hand; see the audit trail in `docs/provenance.md`. This is a convention, and the RFC set exists partly to keep it visible. |

## Performance

Reads are cached per `(kind, name)` and invalidated wholesale on any write or
(un)bind. Coarse deliberately: a prompt renders on every gate message and a
threshold is read inside compiled predicates, so a query per read would put
SQLite in the path of string interpolation — while a stale cell is the bug that
looks like the supervisor's edit silently not taking.

`nil` is cached too: an absent optional prompt is looked up on every render, and
re-querying for a row that is not there costs the same as one that is.

## Known gaps

- The base/userspace rule has no mechanical check. A behaviour added to `src/`
  will not fail a test.
- `store/workflows.clj` remains as a shim over `:manifest` so `workflow` and the
  manifest tool keep their `name`-only signatures. New code should use
  `samizdat.userspace`.
- Migration v11 copied the pre-existing `workflows` rows into `userspace` and
  left the old table in place. Nothing reads it; it is not dropped, so a
  rollback to a pre-v11 binary still resolves.

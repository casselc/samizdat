;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.cell-prelude
  "AOT preload for the namespaces the shipped cells depend on but nothing else
  in src requires.

  Cells are load-stringed, not AOT-compiled (see samizdat.cells). A namespace a
  cell needs — samizdat.agent.planner, telemetry, judge — is therefore first
  compiled on that nested load-string path, and on a `-dirty` jolt build that
  path intermittently fails to intern the namespace's vars, poisoning the AOT
  cache: the cell then can't resolve `planner/parse-plan` and the run dies, until
  `~/.jolt/aot-cache` is cleared by hand (karamazov-fv6, and the earlier
  prompt-digest crash it echoes).

  Requiring those namespaces HERE, from a normal src namespace on the compile
  graph (core -> workflow -> cells -> this), forces them to compile the normal
  way first, so the cache entry is good and the later load-string reference
  reuses it. A preload, not a dependency — nothing here is called; the `require`
  is the whole point. Add a namespace here whenever a new shipped cell reaches
  for one that nothing in src already pulls in."
  (:require [samizdat.agent.gitdiff]
            [samizdat.agent.judge]
            [samizdat.agent.planner]
            [samizdat.agent.telemetry]
            [samizdat.engine.proc]))

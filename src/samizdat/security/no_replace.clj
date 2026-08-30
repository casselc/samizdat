;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.no-replace
  "The one trusted Samizdat consumer of Jolt's Linux no-clobber publication
  primitive. This is deliberately a semantic bridge, not a generic FFI or file
  API: project/edit alone owns root confinement, symlink policy, temp creation,
  content bounds, and cleanup."
  (:require [jolt.publish :as publish]))

(defn publish-create!
  "Attempt the final create publication without replacing target.

  Owner/interval contract: the caller owns `tmp`, creates it in target's parent,
  writes and closes it before this call, and retains responsibility for it on
  every non-:published answer. Neither path is retained or mutated by this
  bridge. On :published the kernel consumes tmp's directory entry and makes the
  target name own that file; the caller's finally may only attempt the now-absent
  temp cleanup. tmp and target must not be mutated or aliased by another writer
  while the call is in flight. The operation is synchronous; no pointer, handle,
  callback, or completion escapes into Samizdat.

  The underlying operation may block in the filesystem and is collect-safe in
  Jolt. This bridge exposes only its closed status vocabulary; an unexpected
  runtime value fails closed rather than becoming a create success. Linux only:
  callers must treat :unsupported as a refusal, not as permission to fall back
  to check-then-rename."
  [tmp target]
  (let [status (publish/publish! tmp target)]
    (if (contains? #{:published :exists :unsupported :error} status)
      status
      (throw (ex-info "Jolt no-replace bridge returned an unknown status"
                      {:samizdat.no-replace/error :unknown-status
                       :status status})))))

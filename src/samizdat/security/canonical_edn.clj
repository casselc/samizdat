;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.canonical-edn
  "The canonical EDN contract keeper — samizdat's INDEPENDENT implementation
  of the repository-neutral ExecutionEnvironment EDN SPI's canonicalization
  grammar (RFC-012).

  The contract, not the code, is what is shared. The bbagent ecosystem keeps
  its own keeper over the same rules, and neither repository reads the
  other's: a shared library would make the two sides one artifact, and then
  one repository could not hold the other to a contract it had already
  imported. Independence is the point — each side keeps the rules, and the
  GOLDEN FIXTURES (test/samizdat/fixtures/execution_env_edn.edn) pin that the
  two keepers still agree: same fixture value, same kind, same digest. A
  digest that moves means the repositories no longer keep one contract, and
  the conformance test fails rather than letting the drift ride.

  The grammar, stated once here and held by the fixtures:

    - the domain is deliberately small INERT EDN: nil, booleans, strings,
      characters, keywords, symbols, integers, and vectors/lists/maps/sets
      over exactly that domain;
    - nothing ALIVE or AMBIGUOUS is canonicalizable: values carrying
      metadata, records, floats, ratios, lazy sequences, atoms, functions,
      classes and objects are rejected, because a coordinate over a value
      that cannot round-trip would name something no reader could
      reconstruct;
    - the form is ORDER-FREE: maps are keyed-sorted, sets are
      encoding-sorted, integers are normalized through bigint (1 and 1N are
      one value), and the printer is pinned (*print-length*/*print-level*
      nil) so the ambient dynamic bindings of the moment cannot change a
      coordinate's meaning;
    - coordinates are DOMAIN-SEPARATED: sha256 over the canonical print of
      [:bb4t.coordinate/v1 kind tree], where `kind` must be a qualified
      keyword. The :bb4t.coordinate/v1 tag belongs to the CONTRACT's
      vocabulary, not to any repository — it is why the same description
      yields the same digest under either keeper.

  This namespace is mechanism only: it decides nothing about when a
  coordinate is taken or what one names, and it knows nothing about
  verification, sandboxes or journals."
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn- reject!
  [value reason]
  (throw (ex-info "Value is outside the canonical data domain"
                  {:samizdat.canonical-edn/error reason
                   :value/type (some-> value class .getName)})))

(defn- without-meta!
  [value]
  (when (and (instance? clojure.lang.IMeta value)
             (seq (meta value)))
    (reject! value :metadata))
  value)

(declare canonical-tree)

(defn- canonical-pr-str [value]
  (binding [*print-length* nil
            *print-level* nil
            *print-readably* true
            *print-dup* false]
    (pr-str value)))

(defn- encoded [value]
  (canonical-pr-str (canonical-tree value)))

(defn canonical-tree
  "Converts the inert EDN domain to an ordered tagged tree — the form both
  keepers digest. Public because the conformance tests pin the tree shape
  itself; nothing outside this namespace should need it."
  [value]
  (without-meta! value)
  (cond
    (nil? value) [:nil]
    (boolean? value) [:boolean value]
    (string? value) [:string value]
    (char? value) [:character (str value)]
    (keyword? value) [:keyword (namespace value) (name value)]
    (symbol? value) [:symbol (namespace value) (name value)]
    (integer? value) [:integer (str (bigint value))]
    (record? value) (reject! value :record)
    (map? value) [:map (->> value
                            (map (fn [[k v]]
                                   [(canonical-tree k) (canonical-tree v)]))
                            (sort-by (comp canonical-pr-str first))
                            vec)]
    (vector? value) [:vector (mapv canonical-tree value)]
    (list? value) [:list (mapv canonical-tree value)]
    (set? value) [:set (->> value
                            (sort-by encoded)
                            (mapv canonical-tree))]
    :else (reject! value :unsupported-type)))

(defn canonical-string
  "The canonical print of `value`'s tagged tree — the exact bytes both
  keepers digest."
  [value]
  (canonical-pr-str (canonical-tree value)))

(defn sha-256
  "The lowercase hex SHA-256 of a string's UTF-8 bytes."
  [^String value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes value StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn sha-256-path
  "The SHA-256 hex of a file's bytes, read as a stream.

  A verify input coordinate digests whatever the staged workspace happens to
  contain, which is bounded by the copy budget rather than by anything this
  namespace chose, so the file is walked in bounded chunks rather than held
  in memory whole.

  Each chunk is re-sized to EXACTLY what was read before it reaches the
  digest: this harness runs on a runtime whose java.security.MessageDigest is
  a host shim that ignores update's offset/length arguments and consumes the
  whole array (jolt.crypto's md method table), so a short read handed over as
  (buffer, 0, n) would digest the buffer's uninitialized tail. Exact-size
  chunks are the one spelling that means what it says there — and mean the
  same thing on a stock JVM. The copy is java.util.Arrays/copyOfRange, not a
  lazy (byte-array (take …)): this function also digests guest-image-sized
  archives (hundreds of MiB, the SmolVM verify environment's pinned image),
  and the lazy spelling boxed its way through ~65k elements per 64 KiB chunk
  — minutes for a file stat computes in half a second."
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (byte-array 65536)]
    (with-open [stream (java.nio.file.Files/newInputStream
                        (java.nio.file.Paths/get (str path)
                                                 (make-array String 0))
                        (make-array java.nio.file.OpenOption 0))]
      (loop []
        (let [read (.read stream buffer)]
          (when-not (neg? read)
            (.update digest (java.util.Arrays/copyOfRange buffer 0 read))
            (recur)))))
    (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                    (.digest digest)))))

(defn coordinate
  "A domain-separated SHA-256 coordinate for inert EDN, spelled
  \"sha256:<hex>\". `kind` must be a qualified keyword: the kind is the
  coordinate's domain tag, and an unqualified one would let coordinates from
  different meanings collide."
  [kind value]
  (when-not (qualified-keyword? kind)
    (throw (ex-info "Coordinate kind must be a qualified keyword"
                    {:samizdat.canonical-edn/error :kind-not-qualified
                     :coordinate/kind kind})))
  (str "sha256:"
       (sha-256
        (canonical-pr-str
         [:bb4t.coordinate/v1 kind (canonical-tree value)]))))

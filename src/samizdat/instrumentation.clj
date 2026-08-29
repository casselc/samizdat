(ns samizdat.instrumentation
  "Provider-neutral identity for Samizdat's inert instrumentation surface.")

(def compatibility-id
  "Compatibility id for the currently published semantic join points."
  "35b01fddd20fa9e6d77678eadc2a2bcc6fb9ac2d")

(def mycelium-compatibility-id
  "Semantic compatibility id for the provider-neutral graph and execution
  seams. This is deliberately not a source revision: the seam is new in the
  uncommitted first slice, so naming an older commit would publish false
  provenance. Consumers bind to this v1 data/arity contract."
  "samizdat-mycelium-graph-v1")

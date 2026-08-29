(ns samizdat.instrumentation
  "Provider-neutral identity for Samizdat's inert instrumentation surface.")

(def compatibility-id
  "Compatibility id for the currently published semantic join points."
  "35b01fddd20fa9e6d77678eadc2a2bcc6fb9ac2d")

(def mycelium-compatibility-id
  "Semantic compatibility id for the provider-neutral graph and execution
  seams. This exact source revision introduced the reviewed v1 data and arity
  contract; consumers fail closed when their selected revision differs."
  "dd13b4b933d3db80a319d2c7b27af4ee6767fca5")

(ns samizdat.instrumentation
  "Provider-neutral identity for Samizdat's inert instrumentation surface.")

(def compatibility-id
  "Compatibility id for the currently published semantic join points."
  "5bcf270fced63507e70ce10baa1b580c1b42a5c6")

(def mycelium-compatibility-id
  "Semantic compatibility id for the provider-neutral graph and execution
  seams. This exact source revision introduced the reviewed v1 data and arity
  contract; consumers fail closed when their selected revision differs."
  "8dad4c353ab5d6be417dabd497b6886d72e65f00")

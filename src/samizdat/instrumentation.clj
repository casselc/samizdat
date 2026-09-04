(ns samizdat.instrumentation
  "Provider-neutral identity for Samizdat's inert instrumentation surface.")

(def compatibility-id
  "Compatibility id for the currently published semantic join points."
  "71f24e427649a82db96576694f6967c171e72453")

(def mycelium-compatibility-id
  "Semantic compatibility id for the provider-neutral graph and execution
  seams. This exact source revision introduced the reviewed v1 data and arity
  contract; consumers fail closed when their selected revision differs."
  "8dad4c353ab5d6be417dabd497b6886d72e65f00")

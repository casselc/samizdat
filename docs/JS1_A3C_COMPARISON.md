# JS1 and Frozen A3c Comparison

## Coordinates

- bbagent A3c evidence: tag `bbagent-a3c` / `fc5e5b9`; runtime evidence was
  produced at `740d117`.
- bb4t A3c: tag `bb4t-a3c` / `227d3854`.
- JS1 live coordinate: Samizdat `8995e113`, Jolt `279bca18`, SCI `0.13.53`.

## Comparable Results

Both systems provide a persistent bounded SCI context, capability discovery,
digest-anchored project mutation, trusted verification, and durable replay.
JS1's live run proves process-kill recovery, reconstructed definitions, and
zero replayed semantic operations at project-operation granularity. Its live
run used 14 turns / 14 tool calls with one failed `project/search` argument
order before recovery; the scripted contract then reached RED and GREEN.

## Deliberate Differences and Non-Claims

A3c additionally proves a pinned guest execution boundary and `project/run`.
JS1 intentionally has neither: it has five read/edit project capabilities and
a controller-owned scoped verifier, but no model-facing shell, network, or
execution capability. JS1's SmolVM guest pack is unbuilt, so A3c host-isolation
and clean-consumer claims are not retained or implied. Latency measurements are
not comparable: A3c measures guest executions; JS1 records no latency claim.

Conclusion: JS1 retains the bounded-programming and no-repeat replay properties
that are in its smaller authority surface. It does not claim A3c execution
isolation or arbitrary-project-code containment.

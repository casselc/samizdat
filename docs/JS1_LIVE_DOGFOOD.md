# JS1 Live Dogfood - Final Evidence

This is final JS1 evidence. It is not a self-hosting canary result.

## Coordinate

- Samizdat: pushed `js1-bounded-samizdat` at
  `897cf534ffd12939c17048477c83fb4be4560672`.
- Jolt: `4af2362176160f2ed0e366689d7232b1a38adfec`, upstream base
  `4c0022d4a8f0270fb8efc8393acf3882c459a823`.
- SCI: `32d62a5136ad3dc148588752f5bcc4cc30b14752` / `0.13.53`.
- Provider/model: local Lemonade Qwen3.6 at `http://localhost:13305/v1`.
- Run: `44665e68-4d2c-45cd-9ca3-2e8c419168d8`.
- Artifacts:
  `~/.local/share/samizdat/js1-dogfood/run-1787760644921-687736b3-7bea-404e-b6a8-a407cbe466c5/artifacts`.

## Result

The final run completed 5 tests / 40 assertions with zero failures/errors. It
reached durable, quiescent RED, was intentionally killed, then resumed and
recovered in a fresh process before reaching GREEN.

This proves the stated JS1 dogfood recovery path only. It does not claim a
self-hosting canary, generic execution, guest isolation, or a clean-consumer
SmolVM lane.

## Historical Evidence

The earlier run `709e2b2d-c0af-40e6-9d3e-0d9624217a2b` is historical evidence
only. Its coordinates and artifacts do not replace or qualify the final
coordinate above.

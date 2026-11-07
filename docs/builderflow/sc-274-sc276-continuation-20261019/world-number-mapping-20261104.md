# World number mapping — SC-274 P3.4 (4 November 2026)

Question the plan asks (P3.4): how does the **live** world number a capture records become the
**simulator** world a scenario runs in? Answer: it does not, anywhere in the current code — and that
is a named blocker, not an oversight to paper over.

## What is captured

Every tick carries a world observation. The server rejects a tick world without a positive integral
`worldId` (`ReplayEvidenceValidator`, `worldId.canConvertToInt() && worldId.asInt() > 0`), and
`recording_af17d0dc` carries `worldId`, `regionId` and `plane` per tick.

## What the compiler does with it

`ScenarioManifestV2Compiler.initialStateInput` reads `worldId` from the earliest tick's
`playerState.world.worldId` (falling back to a top-level `worldId`), and `buildInitialState`:

- requires an integral `worldId > 0`, else `SIM_INITIAL_WORLD_INCOMPLETE` naming the start tick;
- requires an integral `regionId > 0` — region identity is never inferred from a world id or a
  coordinate;
- writes both into `initialState` verbatim (`state.put("worldId", worldId.asInt())`);
- takes `collisionRevision` from the pinned simulator catalog, and refuses a captured value that
  contradicts the pin.

`scenario-manifest-v2.schema.json` requires `initialState.worldId` as an integer. There is no
`simulatorWorldId`, no mapping table, and no translation step in the compiler, the curation snapshot or
the preflight service (`grep` for `simulatorWorld|simWorld|worldMap|world_mapping` over
`src/main/java` returns nothing).

## Consequence

The manifest's world id is the **live server's** world number. Whether the simulator can honour that
number is a property of the simulator's world support, not of the compiler:

- causal use is already gated — `recording_af17d0dc` preflights `playbackEligible=true`,
  `simulationEligible=false`, `CAUSAL_SIMULATION / XRSPS_CAPABILITY_UNAVAILABLE`;
- the narrowed SC-274 text keeps this out of scope for the story and moves it to **SC-2273** row 2
  ("the live-world → simulator-world mapping is resolved **or a named blocker**"), with SC-276 as the
  system that owns native world support.

## Blocker statement for the ledger

> **World mapping (row 2): NAMED BLOCKER.** The capture records the live world number and the
> compiler preserves it verbatim, with region identity and a pinned collision revision. No
> live-to-simulator world mapping exists in ChromaScape, and none is required to produce a
> byte-identical manifest. Causal equivalence for that world number depends on SC-276 native world
> support and is tracked by SC-2273.

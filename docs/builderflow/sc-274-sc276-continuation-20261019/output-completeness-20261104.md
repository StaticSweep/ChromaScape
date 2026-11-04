# Output completeness — SC-274 P3.3 (4 November 2026)

Scope: how each element of SC-274's "Output" list is produced, and what evidence exists for it today.
**No manifest can exist yet**: the v2 compiler requires a registered causal capability before it
emits anything, so every row below is a code-location fact plus a named blocker, not an observed
manifest. `recording_af17d0dc` preflights `playbackEligible=true`, `simulationEligible=false`,
`actionCount=0` (`acceptance/row5-af17d0dc-preflight.json`), so it cannot demonstrate an Output row
either.

| Output element | Produced by | Evidence today | Status |
|---|---|---|---|
| Minimal initial player state: tile/plane, skills, HP/prayer/run, inventory/equipment/**bank**, varps/task state | `ScenarioManifestV2Compiler.buildInitialState` from the earliest tick's `playerState`; `initialState.player` is required by schema, `bank`/`bankOpen`/`travelProfile` are optional | `recording_af17d0dc` carries per-tick player state with world/region/plane; no manifest exists | Blocked: needs the genuine capture; **bank contents are not in tick telemetry**, so they need an operator-supplied/curated value or `SCENARIO_INCOMPLETE` |
| Relevant NPC / object / ground-item / resource-node state and world-view / collision reference | `initialState.npcs`, `.objects`, `.groundItems` (all required), `collisionRevision` from the pinned catalog with a refusal when the capture contradicts it | Compiler code and refusal tests exist; no manifest | Blocked: needs the capture; a Fishing spot must be observed with its `spotId` |
| Seed, fixed-clock origin, tick duration, id namespace | `initialState`'s sibling `determinism` block — schema requires `seed`, `clockOrigin`, `tickDurationMs`, `idNamespace` | Code only | Blocked: same capability gate |
| Allowed semantic actions and decision gates | `actions` (required) from the recorded action stream, mapped to the kernel vocabulary with `ActionProvenance` (`OBSERVED`/`DERIVED`/`OPERATOR_SUPPLIED`/`DEFAULTED`) | `recording_af17d0dc` has **0 typed actions**, so it cannot supply any | Blocked: needs a capture whose actions land in the typed stream |
| Stop / success / failure conditions, maximum ticks/actions | `expectations`, `termination`, `tolerances` (required) | Code only | Blocked |
| Source evidence mapping for every inferred/declared field | Per-field provenance emitted by the compiler (`provenance` on derived and observed nodes, `DEFAULTED` on action gaps) | Provenance enforcement tests pass on fixtures | Partially evidenced at fixture level; genuine side blocked |
| Explicit `OBSERVED | DERIVED | OPERATOR_SUPPLIED | DEFAULTED` | Same field, `ActionProvenance` enum plus curation overrides | Fixture-level tests | Partially evidenced |
| Content checksum and a stable rejection/warning list | `manifestChecksum`; refusal matrix with one stable source-linked code per case | **Closed** — nine refusal cases byte-identical across two fresh JVMs (`wp1/refusal-a.json`, `wp1/refusal-b.json`) | Closed (fixture level) |

## What this row needs to become "closed"

1. A registered causal capability (SC-2201) so a manifest can be produced at all.
2. A genuine capture whose action stream is non-empty, with per-tick `world{worldId, regionId, plane}`
   and Fishing-spot `spotId`.
3. A published HELD_OUT curation for that recording, then one compile — after which this table can be
   produced from an actual manifest instead of from code.

Under the narrowed SC-274 text the row stays in SC-274 (it is recording-side), and the causal/compile
half is SC-2273's.

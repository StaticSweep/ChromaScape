# SC-274 producer and world evidence — 1 November 2026

Continuation of the original-scope plan. This increment fixes two source-evidence gaps found
by the 31 October closure audit and one compiler refusal path. **SC-274 remains In Review**:
no acceptance row is closed, no runtime gate was changed and the tracker was not written.

## Baseline (read-only)

Live `GET http://127.0.0.1:8090/api/replay/capabilities` still reports
`observedPlayback=false`, `policyReplay=false`, `causalSimulation=false`,
`certifiable=false`, `fidelity=SYNTHETIC_CONTRACT`, and empty
`xrsps.buildCommit/cacheRevision/contentRevision`. No runtime is registered, so criteria 4
and 5 remain blocked on the runtime lane.

The 31 October audit findings for `recording_af17d0dc` are unchanged and are not amended:
its frozen run context has no `graphRevision`, its snapshot NPCs have no authoritative
`spotId`, and its start tick has no valid world id. That recording stays as playback
evidence only; the fixes below apply to future captures.

## Changed files

Micro6, worktree `.worktrees/sc274-micro6-producer-20261101` (branch
`codex/sc274-producer-20261101`, based on `8f5fff2d07`):

- `ChromaScapeStableTargetIds.java`: adds a family-only `fishingSpotId(FishingSpot)` and
  `observedFishingSpotId(FishingSpot, contentHint)`. The content-id switch is shared with the
  existing option-aware overload, so behaviour for captured actions is unchanged.
- `TickBatchCollector.java`: resolves a fishing-spot `spotId` on every observed tick from the
  client's content family, preferring an exact same-batch content pin. Unsupported families
  and absent hints stay absent; nothing is invented.
- `PlayerStateTracker.java`: records a `world` observation node with `worldId` from
  `client.getWorld()` plus region and plane when a player position is known. No world is
  emitted when the client reports none.
- Tests: `ChromaScapeStableTargetIdsTest` and `PlayerStateTrackerTest` cover the fallback,
  the preference order, unsupported families and the presence/absence of the world node.

ChromaScape, worktree `.worktrees/sc274-completion` (branch
`codex/sc274-completion-20261015`):

- `ScenarioManifestV2Compiler.java`: `buildInitialState` accepts a pinned collision revision.
  A captured `collisionRevision` still wins; otherwise the value is derived from the pinned
  simulator content/cache revision; without either it still fails
  `SIM_INITIAL_WORLD_INCOMPLETE`. The 5-argument overload is package-private so the
  derivation can be tested directly.
- `ScenarioCompilationEvidence.java`: labels a derived `/initialState/collisionRevision` as
  `DERIVED` with a `worker-build:<commit>/contentRevision:<rev>` source reference instead of
  presenting it as captured evidence. Operator overrides still take precedence and remain
  `OPERATOR_SUPPLIED`.
- `ScenarioManifestV2CompilerTest.java`: covers pinned-revision supply, captured-value
  precedence, refusal without either source, derived provenance labelling, and the
  producer-shaped snapshot contract (nested `world.worldId`, per-tick `spotId`/`spotType` on
  an authoritative NPC pin).

## Acceptance results

This is source-evidence plumbing, not acceptance completion.

- The producer now has a truthful path to the three identities the compiler requires, without
  editing the finalized recording and without fabricating missing values.
- The compiler no longer presents a catalog-derived collision revision as observed evidence.
- `graphRevision` is **not** solved: the run context reference is supplied by the executor
  task metadata (`behavior_run_context`) and the capture start payload, and no builder for it
  exists in ChromaScape, micro6, leeuwbotbackend or the launcher. Supplying
  `graphId`/`graphVersion`/`graphRevision` there remains an operator/allocator prerequisite.
- Criterion 4 remains blocked: no registered, qualified causal runtime exists in this
  environment. SC-2201 (Linux qualification) and SC-276 native world support are still
  separate open gates.

## Tests

Actually run, on this host, JDK 17.0.17.10 (`C:\Program Files\Eclipse Adoptium`),
`GRADLE_USER_HOME=O:\.gradle`, `--offline`:

- Micro6 `:client:test` with seven debugrecorder classes (StableTargetIds, PlayerStateTracker,
  TickBatchCollector, ChromaScapePlayerActionCapture, BehaviorCaptureOutbox,
  BehaviorSessionStartProvenance, DebugRecorderPlayerStateOrdering): **44 tests, 0 failures,
  0 errors, 0 skipped**, BUILD SUCCESSFUL. Requires
  `-PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java`.
- Micro6 `:client:checkstyleMain` and `:client:checkstyleTest`: **SKIPPED by project
  configuration** (reported as skipped, not passed).
- ChromaScape `replayTest --tests ScenarioManifestV2CompilerTest`: **62 tests, 0 failures,
  0 errors, 0 skipped**, BUILD SUCCESSFUL, including the pinned golden manifest checksum.
- Google Java Format 1.17.0 (`--dry-run`, the version pinned by Spotless) on the three
  touched ChromaScape Java files: clean after formatting.
- Repository-wide `spotlessCheck` fails on pre-existing violations across 1380 unrelated
  files; it is not a signal about this change and was not used to claim a pass.

Not run: native/PostgreSQL integration lanes, full repository suites, live/deployed service
behavior, any capture, and any runtime qualification.

## Remaining work

1. Supply `graphId`/`graphVersion`/`graphRevision` in the capture run-context reference and
   verify it against `behavior_graph_publications` before the next recording.
2. Produce the updated client artifact (build, development signer, source bundle/snapshot)
   and verify the source-trust harness before capturing.
3. Take one new genuine bounded Fishing capture, finalize it, publish a new held-out
   curation, and compile the full manifest twice from independent clean directories.
4. Register/qualify a runtime (SC-2201) and complete SC-276 authoritative world support
   before the runnable-scenario and bank/travel criteria can be claimed.
5. Provider-origin first input (SC-1498) still requires the exact one-use human approval.
6. Reconcile the acceptance matrix; only then consider a Done transition.

## Risks

The converter checksum is unaffected by this increment (no conversion semantics changed).
`worldId` from `client.getWorld()` is a live-server world number; it is captured evidence,
not a simulator world identity. Mapping it to a pinned simulator world remains a compiler
or curation concern and must not be assumed. The derived collision revision describes the
pinned catalog, not the live world's collision data. Deployed 8090 code and the logged-in
client were not changed or restarted.

## Rollback

Revert the two scoped commits. Existing recordings, curations, manifests, checksums, signed
bundles and the live service are untouched. No schema, migration, trust root, credential or
capability flag changed.

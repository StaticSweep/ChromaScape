# SC-810 — genuine Fishing run through ChromaScape on the signed packaged artifact (2026-09-12)

## Outcome

A real Fishing Training run was dispatched **through ChromaScape's own dispatch path** and started
on a live, logged-in OSRS client running the **signed packaged artifact**, with ChromaScape
**reusing** that client rather than relaunching it.

| Field | Value |
|---|---|
| iterationId | `buildergoal_3958a3af` |
| status | `RUNNING`, `taskStartConfirmed: true` |
| taskKey | `FISHING_TRAINING` ("Fish Training → ∞") |
| runSessionId | `debug_a3b56cb6f4b9479d82607762f43b6d29` |
| runRecordingId | `recording_2a98057f` |
| taskStartedAt | `2026-09-12T04:11:21.407Z` |
| settingsSha256 | `sha256:ff833fbb4515dedc72be0279bbcdf29bc30002063cb469dc0a42511a7daf0a40` |
| accountHash / micro6ResolvedAccountHash | `649bd3950515bd2f` / `649bd3950515bd2f` (match) |
| targetClientId | `418a4f7d-be75-4d36-9a1d-f5a344e69958` |
| reusedExecutor | **true** |
| world | 380 (`worldMatched: true`) |

Executor identity read from `/health` **while the task was running**:

| Field | Value |
|---|---|
| build_id | `04718fe3bf0f86a27f8eb9d18d769f721748727d` |
| artifact_digest | `sha256:9c5fe625ff57807f4d059afbe756e0727c2daf323707c8891b6f754f686b8319` |
| source_snapshot_id | `srcsnap_1c1527f1843cf6e36d490e0c` |
| account_hash | `649bd3950515bd2f` |
| current_task | `Fish Training → ∞` |

Client-side confirmation from the launch log (`micro6-packaged-launch13.log`):

```
06:11:20 [AccountBuilder] New task assigned: Fish Training → ∞
06:11:21 [AccountBuilder] DebugRecorder: Task settings logging enabled - extracted 51 settings
06:11:21 [DebugRecorderPlugin] ChromaScape session start awaiting confirmation
         (clientRequestId=f0ce0de4-473f-4270-9f31-…)
06:11:21 [TaskExecutionLogger] Started task logging for session: 8523b2e9-881c-4fdf-a495-f3ee5098dabb
06:11:21 [DebugRecorderPlugin] Started task execution logging for: Fish Training → ∞
         (Session: 8523b2e9-881c-4fdf-a495-f3ee5098dabb, Start Tick: 68)
```

Nothing here is synthetic: a real account logged into a real world, a real task assigned by
ChromaScape, and a real recorder session opened against the running artifact.

## The blocker that had to be fixed first: a 5-second port probe

`Micro6TargetRelaunchService.probeListeningPort` spawns a PowerShell process running
`Get-NetTCPConnection` and bounded it at **5,000 ms**. On this workstation — running a Micro6
client with screen capture, a second ChromaScape instance, a 22-container Docker stack and
intermittent Gradle builds — a cold PowerShell probe was **measured at 8,354 ms**. The probe is
fail-closed, so every timeout produced `PortProbeState.UNKNOWN`, which simultaneously:

- made `resolveVerifiedExecutorPid` return null → replacement refused with
  `"...is owned by a Builder Goal process; replacement is blocked (owner=unknown)"`, and
- made `readHealthyExecutorTarget` bail at *"port is not listening yet"* → `localExecutorObserved:
  false` → `canReuseExistingExecutor` false → **a healthy, dispatch-ready executor was never
  reused**.

That single timeout explains both failure modes seen repeatedly this session, and explains why
behaviour looked non-deterministic: it depended purely on momentary machine load. One earlier run
did reuse successfully, during a quieter moment.

**Fix** (`Micro6TargetRelaunchService`): the probe bound is now a documented constant,
`PORT_PROBE_WAIT_MS = 30_000L`. The bound stays finite, so a genuinely hung probe still fails
closed rather than stalling a launch — this raises the ceiling, it does not weaken the
fail-closed property. The `taskkill` backstop keeps its own 5 s bound (unchanged).

After the fix, `reusedExecutor: true` on every subsequent dispatch.

## Two further issues found along the way

**1. Default stop target made the task refuse to start.** With empty settings the task took its
internal default of *Level Target 20*; the account is already past Fishing 20, so it reported the
goal as already reached. The catalog schema advertises a default of `99` while the task's own
`ConfigurableField` default is `20` — a real divergence between advertised and effective defaults.

**2. `stopConditionValue` cannot be supplied directly for this task.** Its schema entry is
`type: integer` but also carries `"options":["visible"]` plus `options_by_dependency` (a
visibility hint for its `depends_on: stopCondition` relationship). ChromaScape's validator treats
*any* definition with an `options` array as an enum
(`BuilderGoalRunnerService` ~line 3649), so an integer value is rejected with
`"Task setting has an invalid enum value: stopConditionValue"`. Sending `stopCondition: "Unlimited"`
and omitting the numeric value avoids both problems.

**3. The fish/location dependency map is internally inconsistent.** With `targetFish: "Best
Available"` the task selected `TROUT` and `locationName: "Random"` resolved to `Al Kharid`, which
has no trout spots, failing with *"Static fish/tool/location compatibility validation failed for
TROUT at Al Kharid"*. The dependency map lists `"TROUT": ["Random"]` — i.e. trout has no concrete
valid location — while `SHRIMP` explicitly lists `Al Kharid`. The successful run therefore used an
explicit, schema-valid pair: `targetFish: SHRIMP`, `locationName: Al Kharid`.

**4. A failed task leaves the custom queue halted.** After the TROUT failure, a subsequent dispatch
was accepted and queued (`Custom Queue (1 tasks)` in the heartbeat) but never started; the executor
stayed `IDLE` with `script_loop_alive: true`. A client restart cleared it. Items 1-4 are recorded
as observed defects; none were patched.

## Settings used for the successful run

```json
{"stopCondition": "Unlimited", "targetFish": "SHRIMP", "locationName": "Al Kharid"}
```

## Byte-for-byte source verification against the signed bundle (performed live, mid-run)

The authoritative source endpoints were exercised against the **running** executor while the
Fishing task was executing. They require a complete audit context, so each read carried
`X-Source-Actor`, `X-Source-Purpose`, `X-Correlation-ID` and `X-Source-Environment:
local-development`, plus the matching bearer token (metadata token for `manifest-read`, content
token for `file-read`).

**1. Manifest identity — byte-identical.** `GET /api/v1/source-manifests/FISHING_TRAINING` from the
live executor, compared with the promoted bundle's manifest on disk
(`bundles/srcbundle_7c5226066eb527205273c425/manifests/FISHING_TRAINING.json`):

| Source | SHA-256 | Bytes |
|---|---|---|
| live executor response | `10086419fce0d0c1903401874702ca2c56b2b0b2186d152836b7ac1ed9cb9820` | 833,754 |
| signed bundle on disk | `10086419fce0d0c1903401874702ca2c56b2b0b2186d152836b7ac1ed9cb9820` | 833,754 |

`cmp` reports no difference. The served manifest also declares
`artifact_digest: sha256:9c5fe625...` and `build_id: 04718fe3bf...`, matching the running artifact.

**2. File content — hash matches the signed manifest.** `GET
/api/v1/source-snapshots/srcsnap_1c1527f1843cf6e36d490e0c/files/file_1a4614c082f9642c8c0cbb75f1fcf9a2`
(20,492 bytes) against the `content_hash` that manifest declares for that file id:

| Value | SHA-256 |
|---|---|
| declared in signed manifest | `sha256:6fb83c3e162695ded50a44e2f519fa2a5103298c1b78320524d4e6b314093a6e` |
| computed from served bytes | `sha256:6fb83c3e162695ded50a44e2f519fa2a5103298c1b78320524d4e6b314093a6e` |
| response `ETag` header | `sha256:6fb83c3e162695ded50a44e2f519fa2a5103298c1b78320524d4e6b314093a6e` |

This closes the chain end to end: the running process is the signed artifact; the source it serves
is byte-identical to the signed bundle's manifest; and the file bytes it returns hash to exactly
what that signed manifest declares. Note this verifies the *served* source against the *bundle*,
not against the developer checkout — the checkout is never consulted by these endpoints.

## Still outstanding
- The remaining negative-security matrix, restart-persistence and cross-revision protection proofs.
- `micro6RevisionId` is null on this run; source-binding provenance still needs to be asserted
  explicitly rather than inferred from `source_snapshot_id`.
- Signing key remains the **development** key; `devtoken123` rotation still undecided.
- Profile config changes (`debugrecorder.chromaScapeUrl`, `accountbuilder.chromaScapeBaseUrl`,
  `accountbuilder.localBuilderExecutorAdvertisedBaseUrl`) must be reverted during cleanup; backup at
  `C:\Dev\SC810\session-scratch\TheSomaliMan-profile.backup-before-8195.properties`.

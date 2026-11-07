# Phases 1-3 execution results — 19 October 2026

Status: **phases 1-3 executed**. The native continuation/restart lanes pass on the frozen
candidate, and the provenance fix is deployed and proven on the owned isolated 8181 service.
This is authored, non-certifying evidence. It does not close any SC-274/SC-276 acceptance row,
does not qualify genuine source/trust, and does not authorize shared rollout.

Practical checklist: [step-by-step execution plan](step-by-step-execution-plan.md). Acceptance
rows remain in [delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md).

## Evidence roots

| Purpose | Path |
|---|---|
| Native continuation lane (this run) | `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\native-continuation-20261019` |
| Isolated 8181 relaunch/API checks | `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019` |
| Owned database | container `sc274-source-trust-postgres-20261019`, loopback 55439 |
| Owned 8181 service | PID 167028, `http://127.0.0.1:8181` |

Key frozen hashes:

| Artifact | SHA-256 |
|---|---|
| `phase1-freeze.json` | recorded in that file |
| `native-orchestration-frozen-inputs.json` | `eb5834eb23f6e80b25cdd794686b2b9cce1a9dd8d34ba55858b2a6efb2002668` |
| `native-orchestration-authored-input.json` | `df16ae1a2dedfe02fc107283f3d876423034aabce95a69317f88b53bc035029c` |
| `native-orchestration-frozen-closure.json` | `7f28540f3b3b3d71db78f29690fc8f35f695feab7cd4449cfd0bed12e6b6433e` |
| `native-db-continuation-run4-result.json` | `d55e592fa6cbf9f0c11503ed346fc170ac07d202758ce04968f2395c8f79c428` |
| `phase3-provenance-api-results.json` | `78dce18b55cc132cd74f96099c0c05c75e002e1d4c4e096d95e0a027635f1486` |
| `phase3-post-restart-reads-results.json` | `7a0ce6b467c8595813434881db0ec96af36cb87bff721575da0baa796b79218c` |

## Phase 1 — frozen inputs

- Compiler candidate `C:\Dev\IdeaProjects\.worktrees\sc274-completion`, branch
  `codex/sc274-completion-20261015`, HEAD `c791c6d47102bf5971497ab5ece5f48883b79b82`
  (56 tracked modified files, 74 untracked files at freeze).
- Native candidate `C:\Dev\IdeaProjects\.worktrees\sc276-completion`, branch
  `codex/sc274-sc276-completion-20261015`, HEAD `1903922a1132e4a9f30dd6c630510b7eb523fc75`.
- Native identity recomputed from the actual working tree: adapter `0.1.0`, build
  `1903922a…`, cache `sha256:15dfb886…`, content `sha256:fde3dcfe14db1f8a0ad282a718e9122e2ec6aebb3d3d0e75d610befdfed37e6b`.
  The 16 October authored input pinned `70db1bab…`; five runtime files changed since then
  (`server-replay-identity.ts`, `runtime-module-guard.ts`, `worker-main.ts` and their tests),
  so the authored bundle was regenerated against the current closure. It was not edited by hand.
- Listeners at freeze: 8090 PID 195400 (shared, unchanged), 8180 PID 171232, 8181 PID 172692,
  55439 owned PostgreSQL. 8182 and 8098 were not listening. Command lines were checked rather
  than treating historical PIDs as ownership.
- `phase1-freeze.json` records the HEADs, diff/untracked counts, toolchain, listener commands,
  owned-container labels and the candidate/running-build hashes. The candidate
  `CurrentSliceRunService.java` differs from the running trust-exp copy only by the provenance
  gate at freeze time.

## Phase 2 — native continuation and restart lanes

The lane used a new owned disposable PostgreSQL container per invocation, the regenerated
authored three-variant bundle, `SC274_NATIVE_REPLAY_ROOT=C:\Dev\IdeaProjects\.worktrees\sc276-completion`,
a new output directory and a new restart root
with `owner-token` plus an empty `artifacts` directory. Every container was verified by ID,
`codex.task`/`codex.owner` labels and loopback port, then stopped by that exact ID in `finally`.

Two concrete host-boundary defects were found and corrected in the **authored lane**, not in
production behavior:

1. **Cold worker startup exceeded the 60 s transport bound.** The worker takes 63-65 s to answer
   `capabilities` on this host (guards, Bzip2 WASM and kernel construction). The authored
   `NativeDatabaseContinuation.NativeTransport` ceiling was raised to a bounded 180 s. This is a
   test-transport deadline; worker resource budgets and production limits are unchanged.
   `docs/replay/native-database-continuation.md` was updated.
2. **`ReplayProperties` is a `@ConfigurationProperties` bean**, so `application.yml` rebinding
   overwrote programmatic test values: the run deadline reverted to 300 s and the artifact root
   to the YAML default. The first multi-variant prepare now takes ~7 minutes, so prepared runs
   aged past 300 s before the separate resume JVM expired them, producing `TIMED_OUT`
   (`RUN_DEADLINE_EXCEEDED`) instead of `FAILED` (`LEASE_EXPIRED`). The lane now publishes
   `replay.worker.run-timeout-seconds=3600` and the owned restart artifact root through
   `@DynamicPropertySource`. Production defaults are unchanged.

Preserved failed runs (not deleted): `run1` (60 s transport timeout), `run2` (`TIMED_OUT` with
300 s budget), `run3` (same, with DB diagnostics proving a 343 s-old run was judged against
300 s). `run3` also captured the evidence that `created_at` for queued children is stored in
local time while `started_at` is UTC; that did not cause the failure.

Final invocation `run4` (21:24-21:42 UTC):

| Suite | Tests | Failures | Errors | Skipped | Result |
|---|---:|---:|---:|---:|---|
| `replayNativeCheckpointPersistenceTest` | 9 | 0 | 0 | 0 | pass |
| `replayNativeDatabaseContinuationTest` | 1 | 0 | 0 | 0 | pass |
| `replayNativeDatabasePrepareTest` | 1 | 0 | 0 | 0 | pass |
| `replayNativeDatabaseResumeTest` | 1 | 0 | 0 | 0 | pass |

Verified evidence (`native-db-continuation-run4-result.json`):

- one same-JVM continuation with 3 authored variants through 6 fresh native workers;
- prepare JVM PID 136000, resume JVM PID 54296 (different JVMs);
- 3 restored variants, 4 expired-attempt recoveries, 23 authenticated HTTP artifact downloads;
- 19 finalized content-addressed artifact files retained under the owned restart root;
- input and all frozen source hashes unchanged after the run; owned container stopped.

All results remain `AUTHORED_TEST_ONLY`, `DRAFT`, `INCOMPLETE`, `HELD_OUT`,
`certifiable=false`, `controlAllowed=false`, `SYNTHETIC_CONTRACT`. They are not genuine
recording admission, production sandbox, authentication-stack or Watch evidence.

## Phase 3 — provenance fix on owned 8181

Tests in the compiler candidate (Java 21, Gradle 8.14.2):

| Class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `CurrentSliceRunServiceProvenanceTest` | 3 | 0 | 0 | 0 |
| `Micro6SourceManifestClientTest` | 10 | 0 | 0 | 0 |
| `SourceTransportTokenBindingTest` | 7 | 0 | 0 | 0 |
| `SourceConfigurationValidatorTest` | 4 | 0 | 0 | 0 |

Build and relaunch:

- Copied only `CurrentSliceRunService.java` (sha256 `a7edb37c…`) from the candidate into the
  isolated build mirror `C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp`; the mirror
  differs by exactly the `Boolean.TRUE.equals(run.getVerified())` gate and its comment.
- `gradlew.bat classes` succeeded in the mirror. `javap` on the rebuilt class shows both
  `ScriptStabilizationRun.getVerified()` and `STABILIZATION_NOT_VERIFIED`.
- Replaced only the owned 8181 Java process (PID 172692, verified by its
  `sc274-chromascape-trust-exp` classpath and `chromascape-full-service\home` user home).
  The owned PostgreSQL container and all rows were preserved (1 stabilization row before and
  after); the role password was rotated and new purpose tokens were generated into
  `phase3-secrets.json` (verification root only, never a command line).
- Relaunched PID 167028 from the rebuilt mirror; ready in 31.5 s. Shared 8090 was not touched.

Full-API refusal (`phase3-provenance-api.py`, 4/4 checks):

- the legacy row remains `PASS | verified=false | AUTHORED_FIXTURE`;
- `POST /api/v1/ai-routine/runs/from-stabilization/sc274exp-authored-fixture-attempt-0001`
  with the real dev-signed source identity now returns `422 STABILIZATION_NOT_VERIFIED`;
- zero new rows in `current_slice_runs`, `source_manifest_references`, `source_run_bindings`,
  `source_content_objects` or `source_read_audit_events`;
- a repeated identical request returns the same refusal with the same row counts.

Positive path: **blocked, as the plan permits.** The owned database contains no
`verified=true` stabilization row, and no approved original-trust bundle or acceptance
amendment exists. The authored fixture was not promoted to `verified=true`. The `verified`
column is `NOT NULL`, so a null row is not database-reachable; the null case remains covered by
the focused entity-level test.

The pre-existing stage E `SOURCE_BOUND` run was not deleted. After restart it is still readable
and its authenticated manifest/symbol/file reads pass (`phase3-post-restart-reads-results.json`,
4/4). It is explicitly historical pre-fix evidence: a post-fix retry of that legacy attempt is
now refused, and this run is not counted as a post-fix positive-path result.

## Files changed by this increment

- `src/test/java/com/chromascape/web/replay/worker/NativeDatabaseContinuation.java` (new file in
  this worktree): bounded 180 s authored-lane transport deadline, replacing 60 s.
- `src/test/java/com/chromascape/web/replay/worker/NativeCheckpointPersistenceTest.java` (new
  file in this worktree): restart artifact root and 3600 s run budget published as dynamic
  properties because of `@ConfigurationProperties` rebinding.
- `docs/replay/native-database-continuation.md`: records the measured deadline change.
- `C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp\src\main\java\com\chromascape\web\aiplayer\routine\service\CurrentSliceRunService.java`:
  now contains the provenance gate already present in the compiler candidate.
- Evidence scripts/results under
  `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\{native-continuation-20261019,phase3-*}`.

No shared service, shared database, canonical trust, replay consumer or tracker state changed.

## Commands actually run (abbreviated)

```powershell
# freezes
tsx freeze-native-orchestration.cjs
# native lane (four Gradle tasks)
node run-native-continuation.cjs run4
# provenance tests
gradlew.bat test --tests CurrentSliceRunServiceProvenanceTest --tests Micro6SourceManifestClientTest `
  --tests SourceTransportTokenBindingTest --tests SourceConfigurationValidatorTest
# isolated rebuild + relaunch
gradlew.bat classes   # in sc274-chromascape-trust-exp
python relaunch-8181-phase3.py
python phase3-provenance-api.py
python phase3-post-restart-reads.py
```

## Remaining work and blockers (phases 4-10)

- **Phase 4 (live isolated registry 8182): not started.** The pinned revision's durable fence
  requires Linux; the existing mechanism evidence stands. A live route from a Linux container to
  the Windows runtime cannot use container `localhost`, and the owned 8180 runtime is bound to
  `127.0.0.1`. A scoped route or a separate owned runtime must be designed and reviewed; the
  runtime bind was deliberately not broadened. No owned secret/grants/fence directory was created.
- **Phase 5 (Launcher allocation): blocked.** 8180 is direct-launched and unregistered; no
  supported Launcher allocation, forwarded machine/session/executor identity or selectable
  registry record exists.
- **Phase 6 (approved trust and genuine input): blocked externally.** No bundle accepted by the
  original trust root or acceptance amendment, no genuine catalog/profile/run context/capture.
  A human login is required for capture.
- **Phases 7-8 (compiler/native acceptance): partially executable.** Authored compiler,
  negative, migration and isolation work can continue, but the nine SC-274 and eight SC-276 rows
  cannot be closed without phase 6 and the remaining native parity/isolation evidence.
- **Phases 9-10 (packaging/client and shared rollout): not authorized.** Shared 8090, the shared
  registry and canonical trust remain unchanged. No story is marked complete and no Shortcut or
  Felix state is claimed.

## Rollback

- Stop owned PID 167028 and relaunch the previous mirror if the provenance gate must be removed;
  the owned database is unchanged and can be preserved.
- Revert `CurrentSliceRunService.java` in `sc274-chromascape-trust-exp` and rebuild.
- Revert the two test-file changes and the doc update in the compiler worktree. Preserve all
  dated evidence directories; they are the record of the failed and passing invocations.
- The native lane's containers are already stopped/removed; no shared resource was modified.

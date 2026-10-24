# Step 1: stage E evidence reconciliation and fixture eligibility audit

Observed 19 October 2026. Read-only reconciliation plus one boundary fix. No service was
started, no shared state was touched and no genuine-source claim is made.

## Evidence preserved

The stage E record is unchanged. Hashes from
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\stage-e-persistence`:

| Artifact | SHA-256 | Bytes |
|---|---|---:|
| `phase1-results.json` | `37ad17dd8510d7a3cb5343883efa1747ba6432142290ac7ace15879b1816d866` | 11176 |
| `phase1-results.recorded.json` | `37ad17dd8510d7a3cb5343883efa1747ba6432142290ac7ace15879b1816d866` | 11176 |
| `phase2-console.log` | `f8597963031a013d1c94d6750b79957eb5f3f62327731a124a6192285b2db2fb` | 2097 |
| `tamper-results.json` | `30c6f5703668e8903e1fd42f0ac629c9187765e06125b47cbfd9b22ec1273b58` | 2317 |
| `harness-fixture-sc274exp-authored-fixture-attempt-0001.json` | `f12ad135f093eacc99cf4ef527ac91fce11a6de24eed49c4cecd94305a0d6087` | 802 |
| `persistence_e2e.py` | `e09ee60d63be903be3ebcf3b091c34bf6573039cc0239d43d5cc14accdf9e58f` | 24534 |
| `owned_service.py` | `475342ee267f8fecda7322ffdc1c18d1da97b56b17319f9b57932a9d6f0bcc59` | 7804 |

`phase1-results.json` and `phase1-results.recorded.json` are byte-identical. The single
recorded failure remains the audit-scope defect described in
`stage-e-persistence-progress-20261019.md`; the corrected correlation-scoped sequence is
`CACHE_MISS_VERIFIED`, `CACHE_HIT`. The 30/31 result is not relabelled.

`phase2-console.log` remains console-only evidence. No `phase2-results.json` was created
because the script crashed at the old tamper step before writing it (WinError 206, no SQL
executed). The tamper increment is a separate 9/9 run.

## Fixture eligibility audit

Inspected path: legacy `harness-manifest/v1` ingestion →
`ScriptStabilizationRun` → `CurrentSliceRunService.create` → `SourceRunOrchestrationService`
→ `SourceManifestBindingService.bindRun` → `SOURCE_BOUND`.

Confirmed:

- `StabilizationIngestionService.ingestHarnessManifest` persists `verified=false`
  (`legacy_unverified`), while only the certified `/report-bundles` path persists
  `verified=true`. The authored fixture row is `verified=f`, `outcome=PASS`,
  `tier=AUTHORED_FIXTURE`.
- Source verification is independent of stabilization provenance. The real development
  signature made the source reference `VERIFIED/VERIFIED`, but did not alter the fixture's
  tier or evidence mode.
- **Defect found and fixed:** `CurrentSliceRunService.create` filtered only on
  `outcome == PASS` and never checked `run.getVerified()`. A legacy unverified fixture could
  therefore be bound to a genuinely verified source and reach `SOURCE_BOUND`. The error text
  ("SC-572 PASS attempt is not verified") already described the intended stronger gate.
- Fixed by requiring `Boolean.TRUE.equals(run.getVerified())` in addition to `PASS`
  (`CurrentSliceRunService.java:93-100`). The default `ScriptStabilizationRun` constructor
  still sets `verified=true`, so certified report-bundle flows are unaffected; legacy
  harness rows are now refused with `422 STABILIZATION_NOT_VERIFIED` before any run,
  manifest reference or binding is written.

Replay eligibility was rechecked: `ReplayEvidenceValidator`, `ReplayV2PreflightService`,
`RecordingIntegrityService` and the compiler consume tick snapshots, curation and run
context. They do not read the stabilization source binding as captured evidence, so the
source-binding fix closes the only promotion path found in this audit.

## Tests

New focused test `CurrentSliceRunServiceProvenanceTest` (3 cases):

- legacy `PASS` + `verified=false` → `422 STABILIZATION_NOT_VERIFIED`, repository never saves;
- `PASS` + `verified=null` → rejected, never saves;
- `PASS` + `verified=true` → accepted, stage `BOUND`.

Command and result:

```
gradlew.bat test --tests "com.chromascape.web.aiplayer.routine.service.CurrentSliceRunServiceProvenanceTest"
BUILD SUCCESSFUL in 3m 30s
```

No Gradle suite beyond that class was run in this increment.

## Exit status

Corrected stage E results are traceable by hash, the original failure is preserved, and
fixture evidence can no longer satisfy the source-bound gate. Original trust-root
acceptance, genuine capture and registry allocation remain open.

## Rollback

Revert the five added lines in `CurrentSliceRunService.java` and delete
`CurrentSliceRunServiceProvenanceTest.java`. No data, service or configuration was changed.

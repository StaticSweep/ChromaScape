# Steps 5-6: authored compiler and persistence checks

Observed 19 October 2026. Authored, non-certifying checks only. No genuine capture,
original-trust acceptance or native parity is claimed.

## Step 5: SC-274 compiler and supporting contracts

Commands (this worktree, Java 21 / Gradle 8.14.2):

```
gradlew.bat test --tests "com.chromascape.web.replay.manifest.ScenarioManifestV2CompilerTest"
gradlew.bat test \
  --tests "com.chromascape.web.replay.catalog.ReplayEvidenceValidatorTest" \
  --tests "com.chromascape.web.replay.manifest.ScenarioManifestImportValidatorTest" \
  --tests "com.chromascape.web.replay.catalog.ReplayV2SelectedPreflightTest" \
  --tests "com.chromascape.web.replay.manifest.BehaviorRunContextGraphTest" \
  --tests "com.chromascape.web.replay.manifest.PublishedCurationSnapshotTest" \
  --tests "com.chromascape.web.replay.manifest.NativeNpcBindingContractTest"
```

Results (JUnit XML):

| Class | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `ScenarioManifestV2CompilerTest` | 56 | 0 | 0 | 0 |
| `ScenarioManifestImportValidatorTest` | 26 | 0 | 0 | 0 |
| `PublishedCurationSnapshotTest` | 9 | 0 | 0 | 0 |
| `ReplayEvidenceValidatorTest` | 8 | 0 | 0 | 0 |
| `ReplayV2SelectedPreflightTest` | 6 | 0 | 0 | 0 |
| `NativeNpcBindingContractTest` | 5 | 0 | 0 | 0 |
| `BehaviorRunContextGraphTest` | 3 | 0 | 0 | 0 |

Logs:

- `step5-sc274-compiler-auth-test.log`,
  `sha256:f3b60f11f1df1b33b2c78a2ee65bfc0d8b2e70b52e9a1fa4208dea44d9bbad63`
- `step5-sc274-supporting-tests.log`,
  `sha256:3bfc0775f1ba04e67b1c0ed5a9fedbbea0a39c3b1a492a7723f9256a0757ec37`

These exercise authored deterministic compilation, provenance/curation contracts, tick
evidence, import refusal, selected preflight, run-context pinning and NPC binding. They
do **not** establish deterministic bytes for a genuine immutable input, exact
provenance/curation of a captured run, or the genuine-input gate.

## Step 6: authored persistence lane

`replayNativeCheckpointPersistenceTest`: **BUILD SUCCESSFUL**. Real disposable PostgreSQL,
artifact storage and native checkpoint persistence, authored and non-certifying. Log:
`step6-native-checkpoint-persistence.log`,
`sha256:6f3ee4af39f25254c2268e44735d48877a90096d972c9f4efe624f4fd1fc1799`.

`replayNativeDatabaseContinuationTest`: **fails closed at its prerequisite gate**, as designed:

```
Required native/database prerequisite: SC276_NATIVE_CHECKPOINT_JDBC_URL
```

The task also requires `SC276_NATIVE_DATABASE_INPUT`, `SC276_NATIVE_DATABASE_OUTPUT` and
`SC274_NATIVE_REPLAY_ROOT`, and resolves
`SC274_NATIVE_REPLAY_ROOT/server/src/replay/worker-main.ts`. No such worker source exists
under `C:\Dev\IdeaProjects` (checked `xrsps-typescript/server/src/replay`; absent), so native
parity, zero-mutation refusal, fresh-worker restore and cross-JVM continuation remain
unproved. Log: `step6-native-database-continuation.log`,
`sha256:fb177dd7ef4fd41a6f29c6bcaeaac78bcaafe95568ea4db0b55b8b45933a4d2f`.

## Not established

Follow-up correction (19 October 2026): the workspace-wide worker absence claim above is
incorrect. `C:\Dev\IdeaProjects\.worktrees\sc276-completion\server\src\replay\worker-main.ts`
exists (6523 bytes), and that candidate has a local tsx installation. Its HEAD is
`1903922a1132e4a9f30dd6c630510b7eb523fc75`; this does not freeze uncommitted work or qualify
the package. The canonical checkout lacks the file. The recorded task failed first at the
missing JDBC environment variable, so it never established whether the candidate worker
could run. Configure owned prerequisites and inspect candidate identity/closure before
claiming an external worker blocker. No native continuation test was rerun in this review.

- Genuine immutable input, original configured trust root or acceptance amendment.
- Native production handler parity for every advertised method.
- Runtime closure, egress/filesystem/process isolation or resource budgets.
- Paired worker/client packaging, real client lifecycle or human Watch sign-off.
- Fresh-install/supported-upgrade migration matrix or shared rollout.

## Formatting and rollback

`spotlessJavaCheck` is not a usable signal in this worktree: it reports violations in 1357
pre-existing files. It was not applied (that would rewrite unrelated work). The two edited
files follow the surrounding style.

No service, database or shared state was changed by these checks. Roll back by discarding
the test outputs in `build/` and, if desired, reverting the Step 1 code/test change.

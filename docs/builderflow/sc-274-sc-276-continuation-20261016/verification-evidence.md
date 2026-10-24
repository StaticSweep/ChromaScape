# SC-274 / SC-276 Verification Evidence, 16 October 2026

This is verification of the implemented continuation increment described in
`implementation-evidence.md`. Both stories remain incomplete against the full
scope in `plan.md`. No runtime fidelity or tracker state is promoted by this note.

## Acceptance results

| Verified increment | Actual result | Boundary |
| --- | --- | --- |
| Complete captured snapshot schema compatibility | Full schema 1/2/4 projections accepted; mixed, missing and unsupported schemas rejected; deterministic compiler output and unchanged source covered | Redacted schema 3 remains refused; required initial evidence is still mandatory |
| Current physical schema | Eight real migrations, identity `sc276-current-v2`, actual fingerprint `sha256:4800631e4d50386bf90f374ca1a92c010ec06f0056e742e9af4225312d3126b2`; strict full JPA/startup/drift/old-contract checks pass | Existing nonempty schemas are not automatically upgraded; no shared database was changed |
| Transaction and artifact persistence | All nine cases pass, including atomic registration, fencing, publication, reservation/receipt validation and owner-scoped branching | Authored DRAFT/INCOMPLETE/HELD_OUT records, not public compiler authority |
| Native/database continuation | All three variants pass through six fresh native processes with actual PostgreSQL transactions and artifact files | One JUnit JVM; no independent recovery/HTTP export or production sandbox qualification |
| Same-attempt source continuation | Prefix actions remain one-use; continuing after a checkpoint does not rebind accepted counters; an unrelated attempt fence is refused | Committed progress is independently validated by persistence |

The native/database variants are `initial-stop` (checkpoint tick 0, zero consumed
actions), `action-success` (checkpoint tick 0, one consumed action), and
`per-native-tick-failure-before-second-source` (running checkpoint tick 1, one
consumed action). Each restores from a finalized database/artifact anchor in a
fresh process and matches the uninterrupted complete snapshot, trace, native
outcome evidence, Java chain hash, event ordinal and persisted sample/event suffix.
Terminal restores issue neither reset nor another gameplay step. Owner-scoped
idempotent child branching and finalized child artifacts are also checked.

The runtime still advertises `SYNTHETIC_CONTRACT`, `certifiable=false` and
`controlAllowed=false`. Adapter `0.1.0`, build
`1903922a1132e4a9f30dd6c630510b7eb523fc75`, cache
`sha256:15dfb8861c95ed8747ed7aae612444cf779d662507bbf04978b9c5067eb82397`,
content `sha256:70db1bab5c42cc97ece5aa27066c81e209ffb41a841d021dcca44ba9c6ec3959`.
No runtime source changed during this increment.

## Tests

All new verification below completed on 16 October 2026. Exact command lines and
XML are preserved under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016`.

| Command/task | Result | Evidence |
| --- | --- | --- |
| Owned PostgreSQL eight-migration fingerprint exporter | Actual fingerprint above; owned fixture stopped successfully | `native-db-v8-current-schema-export-result.json` |
| `gradlew.bat aiPlayerSchemaTest --rerun --no-daemon --max-workers=2 --console=plain` | 22 passed; zero failures/errors/skips | `native-db-current-suites-result.json` |
| Focused `gradlew.bat replayTest --tests ...` | 379 passed; zero failures/errors/skips; frozen source hashes unchanged | `native-java-focused-final-result.json` |
| Eight-file scoped `spotlessJavaCheck` and full `checkstyleMain` | Both passed; Checkstyle emitted 4,600 project-wide warnings | `native-java-focused-final.log`, `native-java-focused-final-checkstyle-main.xml`, `scoped-format.init.gradle` |
| `gradlew.bat replayNativeCheckpointPersistenceTest --rerun --no-daemon --max-workers=2 --console=plain` | Final repeat: 9 passed; zero failures/errors/skips | `native-db-continuation-current-result.json` |
| `gradlew.bat replayNativeDatabaseContinuationTest --rerun --no-daemon --max-workers=2 --console=plain` | 1 test, all three variants/six fresh workers passed; zero failures/errors/skips | Same result record; `native-db-continuation-current-output\native-database-continuation.json` |
| Scoped `git diff --check` | Passed for the touched source/schema/test files | Actual native PowerShell command during final review |

Both database suite records confirm unchanged frozen source inputs and successful
cleanup of their exact random-owner-labelled container IDs. No shared database,
application service or WSL instance was restarted. Initial failed runs remain
archived; no stale XML, skipped prerequisite or interrupted run is counted as a pass.

Evidence SHA-256:

- Focused final result: `7aced13b13fc07c3afcced8dff7cb856557e830c1f5700fac7afb2074446b3ba`.
- Native/database final result: `29d7366930dbe70c36ea3daa59def483a4bb5f294fc534aebd0d6f9078fae96d`.
- Three-variant parity output: `379ace1ac7f59747ee9bd70de3fe295ff3ea3b6eec2e96ba492baf969b13cde2`.
- Checkstyle XML: `bb403955c9e0c99c4291c46ca08400bb599c0afa31946dd2fec3e62e32afc26c`.

Separate existing runtime evidence was reconciled to its actual completed result:
266 discovered, 264 passed, zero failed/cancelled and two explicit certification
skips. Its source-frozen log is in the runtime candidate's
`docs\builderflow\sc-276-completion-20261016\logs\native-final.log`, SHA-256
`df751dca2d52e5c4c859aa4ebc5b76b4f15f34f28bb733fbcf90ff2061bde083`.
Those skips and earlier compiled-dist integration cases are not final-source
certification proof.

## Risks

- This is an authored native/storage join. Genuine finalized recording provenance,
  selected published curation and independent authoritative activity/oracle evidence
  remain required. Legacy catalog eligibility flags do not override the compiler's
  source audit. `recording_b83c37e5` remains blocked.
- Native world/nonempty termination public admission stays gated. Complete closure,
  ESM/filesystem policy, CPU/memory/wall-time supervision, OS egress, cancellation
  and full parallel/noninterference qualification remain open. The Windows native
  transport remains test-only; production Linux sandbox requirements are unchanged.
- Actual LIVE_XRSPS server/browser Watch, SC-1991 client/region/plane/travel parity,
  genuine typed capture, fresh JVM, recovery/HTTP export, privacy/performance matrix
  and clean final release/CI/upgrade/rollback qualification were not run here.
- The candidates contain substantial pre-existing uncommitted work. No supported
  release pin, merge readiness or deployment is established. Checkstyle warnings
  remain; only the scoped formatting check is claimed.
- Felix canonical task retrieval still returns `Project not found`. Existing
  SC-274 task `task_1789960012362_td2qnusp` is last known `in_review`, not freshly
  verified. SC-276 task resolution is unavailable. Shortcut reads succeeded and
  showed SC-274 blocked/In Review and SC-276 blocked/To Do. No tracker mutation or
  synchronization succeeded; these local notes are not Felix notes. No duplicate
  task, gate waiver or successful outage replay is claimed.

## Rollback

No deployment, shared database migration, service restart, commit, push or merge
occurred. Preserve the existing dirty candidates and all archived evidence.
Before adopting V8, review and commit the schema identity/fingerprint together;
the strict guard rejects incompatible existing nonempty development schemas.
If this candidate is withdrawn, retain the previously supported worker/schema
package; do not edit released V1-V7 or point tests at application data. Remove or
revert only this reviewed increment after preserving other existing changes.
The disposable verification containers have already been stopped and removed.

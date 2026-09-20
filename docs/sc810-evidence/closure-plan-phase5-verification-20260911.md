# SC-810 Phase 5 — final native verification (2026-09-11)

## Scope

`micro6-sc810-integration` at commit `95e7bd20da618f0c4b4919b0009ca26f1f53e5a6` (the corrected
delivery lineage — see `closure-plan-phase4-integration-lineage-20260911.md`). All commands run
natively on Windows via PowerShell (`Start-Process`, `-RedirectStandardOutput`/`-Error`),
never through WSL, per `micro6-sc810-integration/AGENTS.md`. Compile/test authorization is the
user's own explicit Phase 5 command list.

## Step 1 — compile

```
gradlew.bat :client:compileJava :client:compileTestJava --no-daemon
```
`BUILD SUCCESSFUL in 35s`. Both tasks reported `UP-TO-DATE` — no source changed since this
worktree's last build. One pre-existing, unrelated warning: ANTLR tool version 4.11.1 used for
code generation doesn't match the runtime version 4.13.1 (non-fatal, not in scope).

## Step 2 — test

The plan's exact command (`:client:test :client:sc810SourceTests`) does not exist:
```
Cannot locate tasks that match ':client:sc810SourceTests' as task 'sc810SourceTests' not found
in project ':client'.
```
No custom Gradle task by that name exists anywhere in `runelite-client/build.gradle.kts` (only
`sc967CandidateTest`, `micro6HarnessTests`, `micro6RuntimeCertification`,
`micro6WindowsFishingE2E`). Verified this isn't a real gap: `Sc810RealBundleVerificationTest`
lives under ordinary `src/test/java` with no special exclusion, and the plain `test` task's own
comment states "A normal test run must compile and execute the complete unit/integration suite"
— it excludes only the `runtime-certification` and `windows-e2e-fishing` tags. `:client:test`
alone already covers the SC-810 real-bundle test; the referenced task name was inaccurate.

Ran `:client:test --no-daemon` alone:

```
1595 tests completed, 5 failed, 6 skipped
BUILD FAILED in 11m 17s
```

**`Sc810RealBundleVerificationTest` is not among the 5 failures — it passed.**

### The 5 failures, classified (both pre-existing, both out of SC-810's repository-hygiene/
### verification scope, neither touched by this session)

**Schema/mirror drift (2 failures, same root cause)** —
`TaskSourceManifestSchemaEqualityTest.canonicalSchemaBytesArePinned()` and
`.chromaScapeMirrorIsByteIdenticalWhenCheckoutIsAvailable()`:
- The canonical schema (`src/main/resources/micro6-contracts/task-source-manifest-v1.schema.json`)
  currently hashes to `sha256:6708a4dbbc07489e5268cc9234430cee926c06bfacf37c2954f33005b02f1438`.
- The test's pinned constant (`TaskSourceManifestSchemaEqualityTest.java:23-24`) and the
  ChromaScape-side mirror (`ChromaScape/src/main/resources/contracts/source/task-source-manifest-v1.schema.json`)
  both still expect the older `sha256:64fc9939b8328562715fe9e4db8a1f3d70e600f171feae1137b1ef2cc7307990`.
- The canonical schema was changed on this branch at some point without updating the pin and the
  mirror together, exactly the drift this test exists to catch. Real, correctly-caught, genuinely
  broken contract sync — not a test bug, not something this session introduced (no schema or test
  file was touched this session).

**Missing Gradle test-task wiring (3 failures, same root cause)** —
`TaskSourceProcessorPathTest.generatedFilesExistOnlyUnderIsolatedCompilerOutputs()`,
`.lombokGeneratedMembersAreVisibleOnlyWithProcessorPath()`,
`.customProcessorGeneratedTypeIsAttributableAndDeterministic()`:
- All three fail in the shared helper `lombokJarFromResolvedProcessorPath()`
  (`TaskSourceProcessorPathTest.java:414-419`), which reads JVM system property
  `micro6.source.compiler.processorpath` and fails fast with `"CONFIGURATION: ... is unset;
  Gradle must supply configurations.annotationProcessor (Lombok) to tests"` when blank.
- `runelite-client/build.gradle.kts:1093-1096` (`tasks.withType<Test>`) only sets
  `glslang.path` as a system property — nothing sets `micro6.source.compiler.processorpath`.
  The test task was never wired to pass Lombok's resolved `annotationProcessor` classpath
  through. A build-config gap, not a code defect in the indexer these tests exercise.

Neither failure was fixed inline — both are real feature/build-config work outside repository
hygiene and dispatch-readiness verification, and fixing them risks sweeping unrelated changes
into SC-810 commits. Tracked here as non-blocking findings needing their own follow-up (per the
original plan's Phase 12 instruction to track, not silently absorb, unrelated fixes).

## Step 3 — check

See `closure-plan-phase5b-check-20260911.md` (recorded separately once `:client:check` completes
— it depends on `:client:test`, which is not green, so `check` cannot be called green either;
documented honestly per this phase's own instruction not to call it green).

## Net effect on SC-810 readiness

Neither failure touches Fishing Training, source-bundle generation/signing, or dispatch
readiness — the SC-810-specific test (`Sc810RealBundleVerificationTest`) passed. Both failures
are real, correctly-caught defects in *other* parts of the codebase that existed before this
session and are unrelated to what SC-810 is verifying.

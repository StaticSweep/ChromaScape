# Remaining implementation plan review - 22 October 2026

## Review scope

Read the latest attempt execution report and current fresh-JVM test source. Rechecked
retained manifest bytes, child metadata, closure-test log and transport result JSON.
This is a source/artifact review, not a rerun of runtime suites or a current health probe.
Current full plan: [remaining-implementation-plan-20261022.md](remaining-implementation-plan-20261022.md).

## Findings

1. **Child-process cleanup is incomplete.** In
   `src/test/java/com/chromascape/web/replay/compiler/ScenarioManifestFreshJvmDeterminismTest.java`,
   children start at line 37, followed by assertions around bounded waits. There is no
   finally cleanup. A second-spawn failure, timeout, interruption or assertion failure
   can leave a child running. Add bounded cleanup for every started process and test failure paths.
2. **Curation determinism is not asserted completely.** Line 61 checks only the first
   checksum node with isNull(). A missing field is a MissingNode, and the second checksum
   is not compared. Require both textual/nonblank fields and equality. The retained results
   happen to contain equal checksums; that does not replace regression assertions.
3. **WP5 remains partial.** NODE_API_DIAL_NOT_OS_SANDBOX is Node API enforcement. Recorded
   handshake/refusal timing does not establish full replay budgets, cancellation, resource
   controls, OS-level egress or scenario throughput. Preserve the 24/24 guard passes within scope.
4. **Input freeze is not portable packaging.** Recorded archives preserve current source,
   but junction dependencies, temporary Windows esbuild and an absent build mirror remain.
   All 27 compiler inventory paths exist, but their historical exact bytes are unproved.

## Verified artifact checks

- Retained manifest A equals B, 28,079 bytes, SHA-256
  `b37d005c3a3b3c56d12d52766308ae907fc1a4f22646e0a479f480b9eea26076`.
- Retained child metadata has different PIDs and equal nonblank curation checksums.
- Closure/guard log records 24 passing tests; transport JSON records passed=true.
- Prior review matched the freeze, compiler/native patch/archive and transport artifact
  hashes against the attempt report. None of those checks establishes current service availability.

Evidence root:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-20261022T014931Z`.

## Remaining sequence

1. Fix the two test defects and preserve/reconstruct reviewed owned build inputs.
2. Reconcile historical metrics/actions; keep genuine incomplete-input refusals.
3. Finish method parity, durable-state and provenance/privacy gates.
4. Build portable dependencies and test actual isolation, cancellation, budgets and performance.
5. Resolve released migration authority; test supported Flyway upgrades and recovery.
6. Obtain authorized host-clock remediation and operational renewal ownership.
7. Obtain supported allocation, approved trust and genuine input.
8. Qualify paired packages, real client/Watch and human sign-off.
9. Run the full final matrix on frozen inputs and perform separately authorized delivery.

The full plan retains dependencies, verification/exit criteria, rollback and all 17 acceptance
rows. Local work continues while external inputs wait. No acceptance row is closed here.

## Follow-up verification - 23 October 2026

Findings 1 and 2 are fixed and verified locally. `ScenarioManifestFreshJvmDeterminismTest`
now reaps every started child with bounded graceful/forced termination, requires nonblank and
equal manifest/curation identity fields, and includes a nonterminating-child refusal test.
Focused run: 3/3 tests green; manifests remain equal at 28,079 bytes. Evidence:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-20261022T224420Z`
(`fresh-jvm-determinism-test-hardened.log`, `junit-fresh-jvm-hardened.xml`, `fresh-jvm-hardened\`).

WP3 is reconciled. b83c is a 55-to-58 inventory total (+3) with 23 catches (14 trout +
9 salmon); the single positive addition is the 18-feather withdrawal. The typed stream has
11 rows, with 2 accepted-unresolved menu rows absent from the typed table, yielding
`TYPED_ACTION_STREAM_INCOMPLETE` + `BEST_EFFORT`. The preflight text now says "net inventory
total change of exactly 5" without changing the requirement. Focused tests are 22/22 green,
and post-restart live b83c/3a calls return HTTP 200 with the corrected text.

Operational findings: the canonical `REPLAY_WORKER_ENABLED=true` boot failure was fixed by
annotating the public `ReplayWorker` constructor with `@Autowired`. 8090 now boots with the
canonical value, Spring reports the `replay.worker.enabled=true` condition matched,
`ReplayWorkerLifecycleTest` is 3/3 green, and b83c/3a preflight responses are byte-identical
to the earlier worker-disabled run. The related test run is 28/31 green; the three failures are in
unmodified `FishingDatasetBuilderServiceTest` and are caused by a null run-context fixture
(`RUN_CONTEXT_REQUIRED`), not by this change. Full suite not rerun. WP4 acceptance and all
external WP8 gates remain open.

## Changed files

At initial review: updated the full remaining plan's status, ordering and fresh-JVM checklist;
added this review. No product/test implementation, shared service, database, credentials or
tracker state changed.

23 October follow-up: modified `FishingPreflightService.java`,
`FishingPreflightServiceTest.java`, `FishingRecordingLoaderTest.java`, and added `@Autowired`
plus its import to `ReplayWorker.java`; the service now runs with canonical
`REPLAY_WORKER_ENABLED=true`. No tracker state changed.

## Tests

Artifact assertions above were executed during review. No Gradle/native runtime suite rerun.

23 October follow-up: focused WP3 tests 22/22 green (`FishingPreflightServiceTest` 11/11,
`FishingRecordingLoaderTest` 11/11); WP4 determinism 3/3 green; `ReplayWorkerLifecycleTest`
3/3 green; `compileJava` and `compileTestJava` succeeded; related aiplayer/conversion run
28/31 green with three pre-existing `FishingDatasetBuilderServiceTest` fixture failures.
The full 319-test suite was not rerun. Document structure and links are checked separately
before handoff.

## Risks and rollback

Findings 1 and 2 are fixed; remaining findings are implementation work. Historical successful
runs are narrower than final acceptance. Canonical worker bean creation and lifecycle are now
verified, but no replay job was executed, so production worker behavior remains unexercised.
Preserve evidence and current uncommitted changes; reverting these documentation changes
requires no service or database rollback. Felix/Shortcut remain skipped.

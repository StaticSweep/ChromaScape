# SC-274 / SC-276 Implementation Evidence, 16 October 2026

Canonical projects: `C:\Dev\IdeaProjects\ChromaScape` and
`C:\Dev\IdeaProjects\xrsps-typescript`. Work remains in the existing dirty
candidate worktrees; no commit, push, merge, deployment or shared service restart
was performed. The complete scope, exclusions, linked rules, Given/When/Then
acceptance and P1-P11 implementation/test checklists remain in `plan.md` and its
linked predecessor plan. This note records the implemented continuation increment,
not completion of either story.

## Changed files

Relative to compiler candidate `C:\Dev\IdeaProjects\.worktrees\sc274-completion`:

- `src\main\java\com\chromascape\web\replay\catalog\ReplayEvidenceValidator.java`:
  accept complete full-snapshot versions 1, 2 and 4; reject schema drift within a
  recording. Required initial evidence and exact source/action checks remain in force.
  Redacted canonical schema 3 still requires its own adapter and is refused.
- `src\main\java\com\chromascape\web\replay\manifest\ScenarioManifestV2Compiler.java`:
  use that shared supported-version policy and require one schema throughout the
  immutable recording. No source snapshot is rewritten.
- `src\main\resources\db\ai-player-current\V8__sc276_source_cursor_and_capture_identity.sql`:
  forward reconciliation of current capture identity/quality/combat sections and
  committed native source progress, with the existing canonical bounds/checks.
  Missing historical state remains NULL. Released V1-V7 are unchanged.
- `src\main\resources\db\ai-player-current-identity.properties`:
  `sc276-current-v2` and the actual eight-migration PostgreSQL catalog fingerprint
  `sha256:4800631e4d50386bf90f374ca1a92c010ec06f0056e742e9af4225312d3126b2`.
  Existing nonempty schemas must pass the strict contract; no automatic upgrade,
  baseline or repair is introduced.
- `src\main\java\com\chromascape\web\replay\worker\ReplayWorker.java`:
  share reset-event persistence with the explicit native/database helper, bind
  native source initialization once per execution/attempt, retain that binding
  while continuing an accepted source suffix, and reject a different attempt fence.
  Fresh branch executions initialize independently from verified restore anchors.
- `src\test\java\com\chromascape\web\replay\catalog\ReplayEvidenceValidatorTest.java`
  and `src\test\java\com\chromascape\web\replay\manifest\ScenarioManifestV2CompilerTest.java`:
  complete schema 2/4, deterministic immutable compilation, missing required state,
  unsupported/null versions and mixed-version refusal.
- `src\test\java\com\chromascape\web\replay\worker\NativeCheckpointPersistenceTest.java`:
  expect all eight migrations and verify published snapshot bytes and content
  address exactly. JSON parsing narrows small LongNodes to IntNodes; Java node
  type equality is not the persisted snapshot contract.
- `src\test\java\com\chromascape\web\replay\worker\NativeDatabaseContinuation.java`:
  persist real reset events and initialize trusted source progress before manual
  prefix steps, using the shared production paths and actual transaction services.
- `src\test\java\com\chromascape\web\replay\worker\ReplayWorkerNativeOutcomeTest.java`:
  cover continuation after an accepted prefix without rebinding, exact one-use
  source action indices and refusal of an unrelated attempt fence.
- `docs\SC-665-CURRENT-SCHEMA.md`, `docs\replay\native-checkpoint-persistence.md`,
  `docs\replay\native-database-continuation.md`
  and this continuation directory: current schema contract, evidence and remaining plan.

Runtime candidate `C:\Dev\IdeaProjects\.worktrees\sc276-completion`:

- `docs\builderflow\sc-276-completion-20261016\verification-evidence.md`:
  reconcile the completed native regression result: 266 discovered, 264 passed,
  zero failed/cancelled, two explicit certification skips. No runtime source was
  changed by this continuation increment.

## Tests

Commands, source hashes, exact XML and outcomes are preserved under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016`.

- Actual owned PostgreSQL 16 fingerprint export:
  `native-db-v8-current-schema-export-result.json`; eight migrations applied and
  the exact owned fixture stopped successfully.
- `aiPlayerSchemaTest`: **22 passed, zero failures/errors/skips**.
  `native-db-current-suites-result.json` records strict full application entity
  validation, startup validation, drift/old-contract refusal, sentinel preservation
  and exact owned fixture cleanup.
- Focused `replayTest` selection: **379 passed, zero failures/errors/skips**.
  `native-java-focused-final-result.json` lists all selected patterns, actual
  commands, class XML and unchanged source hashes.
- Eight-file scoped `spotlessJavaCheck` and full `checkstyleMain`: passed in that
  same invocation. Checkstyle reported 4,600 project-wide warnings; a warning-free
  repository is not claimed. The full Checkstyle XML was archived separately.
- `replayNativeCheckpointPersistenceTest`: **9 passed, zero failures/errors/skips**;
  the current native/database invocation repeats this suite before native execution.
- `replayNativeDatabaseContinuationTest`: **1 test passed, all three variants
  passed in six fresh native workers, zero failures/errors/skips**. The current
  invocation also repeated all 9 persistence cases successfully. Frozen sources
  were unchanged, and exact-ID/random-owner-verified container cleanup succeeded.
  `native-db-continuation-current-result.json` and its output directory retain
  actual commands, XML, database anchors, requests and both sides of parity checks.
- Scoped `git diff --check`: passed for the touched compiler/worker/schema/test files.
- Runtime source regression remains the actual completed 264 passes and two
  certification skips recorded in the runtime evidence, not a fresh certification run.

The initial persistence assertion failure and the first native/database helper
ordinal failure remain archived. Both were investigated rather than waived:
canonical bytes replace Java number-node type comparison, and reset events now
reach committed storage through production handling. The initially incomplete
modern-schema fixture was corrected to supply its required initial NPC; required
evidence checks were not loosened.

## Remaining work

The complete P1-P11 plan remains open. Required before story completion:

1. The authored native/database join is verified; qualify fresh JVM/recovery/HTTP
   export independently and extend the join to eligible genuine compiler inputs.
2. Use a genuine finalized source with exact immutable BehaviorRunContext, published
   curation, initial/dynamic world evidence and dataset/privacy lineage. Legacy
   catalog eligibility flags and authored fixtures cannot supply that authority.
   `recording_b83c37e5` retains its precise blocked historical verdict.
3. Complete native world/termination admission and the full supported activity,
   interruption, resource and independent production-oracle matrix before changing
   public simulation gates or advertised fidelity.
4. Complete reachable closure/handler/ESM/filesystem enforcement, OS egress and
   CPU/memory/wall-time supervision, cancellation/teardown and parallel isolation.
5. Implement/verify the separate experimental LIVE_XRSPS lifecycle and actual
   SC-1991 browser/client Watch, reconnect, viewer noninterference, region/plane
   travel and genuine typed capture wherever parity is claimed.
6. Select reviewed clean release inputs, build/package them, execute complete CI
   and upgrade/rollback/privacy qualification, review the existing mixed PRs, and
   publish only work that passes those gates.
7. Reconcile the existing Felix task/notes and Shortcut after canonical project
   resolution and actual workflow validation succeed.

SC-274 existing Felix task: `task_1789960012362_td2qnusp`, last known `in_review`,
current status not verified because canonical task retrieval returns
`Project not found`. SC-276 task resolution remains unavailable; no duplicate was
created. Shortcut was read successfully: SC-274 blocked/In Review, SC-276 blocked/
To Do. No tracker write, state transition or synchronization marker was made.
The successful Felix error response does not satisfy the transport-outage rule;
these local documents are not claimed as synced Felix notes.

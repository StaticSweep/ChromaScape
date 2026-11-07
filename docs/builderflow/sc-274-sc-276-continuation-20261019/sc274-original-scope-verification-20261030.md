# SC-274 original-scope verification — 30 October 2026

SC-274 remains In Review and incomplete. Its original seven criteria and the 6 October travel/historical amendment remain authoritative. The owner approved narrowing SC-276 only. No SC-274 criterion was waived or marked complete in this run.

## Implemented plan

1. Read current Shortcut story, comments, dependencies, repository rules and sync mapping.
2. Scan canonical pending records and resolve the recorded Felix identity before any new task write.
3. Run compiler/manifest/import/provenance regressions on the candidate.
4. Compile the same immutable authored input from two independently cloned, clean directories with build cache disabled; compare exact bytes and source pins.
5. Run compiler/JPA/controller/export/import/ownership/role and rollback tests against owned disposable PostgreSQL, preserving failed attempts.
6. Repair the actual migration-subset defect discovered by this run without changing production SQL or disabling validation.
7. Recheck available capture eligibility and original historical evidence read-only.
8. Persist results, preserve unresolved acceptance, and remove only owned disposable resources.

## Changed files

- `src/test/java/com/chromascape/web/replay/integration/ReplayMigrationIntegrationTest.java`: the staged replay test migration list now includes the existing V71 outcome and V72 committed-source-cursor migrations; the empty-database test asserts V72's column exists. Released migrations and JPA validation are unchanged.
- This evidence report and a dated pointer in `remaining-implementation-plan-20261029.md`.
- Harnesses, independent clean clones, exact outputs, logs, JUnit and tracker requests/responses under `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261030\sc274-execution`.

No production Java/native code, live recording, shared schema, account permission, trust root or runtime qualification changed.

## Acceptance results

| Requirement | Result and remaining limit |
|---|---|
| Determinism | PASS on immutable authored fixture: two clean Git clones, two fresh compilation processes, no build cache, byte-identical 28,079-byte manifests. SHA-256 b37d005c3a3b3c56d12d52766308ae907fc1a4f22646e0a479f480b9eea26076; compiler 2.4.0; manifest checksum sha256:34325901dc7866785880c50effe232d1917bc67856fddee207cd3fa22fa829ac. Genuine-input acceptance remains open. |
| Exact field/action provenance | Current compiler/import regressions pass, including source/curation/role/identity tampering. Evidence on a complete genuine input remains missing. |
| Stable refusal | Current regressions pass for gaps, order/duplicates, missing state, target ambiguity, incompatible identity and malformed import. Historical source gaps remain real. |
| Runnable genuine Fishing | BLOCKED: no qualifying context-bound finalized capture found in the checked shared store. Authored fixture runs cannot substitute. |
| Incomplete-scenario boundary | Current tests pass: readable/importable incomplete inputs cannot acquire causal eligibility by resealing a checksum, changing DB flags or claiming native support. |
| Roles/privacy | Three role cases and owner/foreign/missing/principal refusals pass through production compiler/JPA/MockMvc on an owned DB. This is supplied-principal authored evidence; genuine authorized-input and socket-level privacy acceptance remain open. |
| Bounded import/API | Parser bounds, inert code/network fields, schema/depth/checksum refusals and exported-package-to-validator round trip pass. This run used actual MVC/controller/service boundaries with supplied principals, not deployed socket authentication. The final genuine-input/API acceptance is still open. |
| Travel amendment | Authored multi-region/plane, bank/travel target-timing and ambiguous-widget regressions pass; no genuine complete bank/travel capture or production eligibility claim. |
| Historical b83c | Original packet/video hashes still match the earlier audit. No decoder was rerun and no old blocked verdict was promoted. Missing context/curation and previously documented action/completeness gaps remain. |

Both independent clones pin cbea6a7a891b46fad6f08e1884e7ecea583b337d and are clean before and after compilation. Main source and fixture bytes are unchanged by the later test-only migration staging fix. The evidence records the exact clone trees, JVM identities, exits and outputs. They are independent clean directories sharing the reviewed dependency cache; this is not an independent-host claim.

## Tests

- `replayTest --tests com.chromascape.web.replay.manifest.* --tests com.chromascape.web.replay.compiler.*`: **129 tests, 0 failures/errors/skips**.
- PostgreSQL/MVC/role/security lane after the test setup repair: **42 tests, 0 failures/errors/skips**: 16 Sc274CompilerTransactionIntegrationTest, 1 empty-chain migration, 12 curation replacement, 4 curation policy MVC and 9 principal/ownership tests.
- Combined current passing tests: **171**, zero failures/errors/skips; separate clean-directory compiler executions: **2/2 pass**, output bytes equal.
- Initial PostgreSQL lane is retained: 25 passing checks and one initialization failure because the helper omitted V72, so Hibernate correctly refused missing native_source_cursor. This failure was fixed in the helper, not suppressed.
- Touched-file Spotless passed with an explicit single-file target. The IDE-hook attempt reported SKIPPED and is not formatting evidence; the first explicit attempt identified line-ending differences, corrected only on the touched file. The final reviewed comments and source were formatted/checked without repository-wide formatting.
  The final checker explicitly uses LF to preserve the tracked source's line endings; failed harness configuration attempts are retained. No whole-file EOL churn was committed.
- Not run: full repository suite, production socket authentication/privacy deployment, current full decoder audit, genuine capture/gameplay, native production qualification, human Watch or release migration compatibility.

## Input inventory and historical integrity

A bounded SQL transaction against the existing shared PostgreSQL used BEGIN READ ONLY and a statement timeout. It wrote no rows:
- 121 recording sessions, 106 finalized; zero populated run_context and zero task_key.
- Two published curations total. The b83c and 3a historical recordings have no context, task key or published curation.
- No credentials, account names or raw private recording payloads were placed in this report or tracker comment.

SC-1498 is still In Review. Its current comments retain the missing approved live run; its exact human run/account/provider/budget/one-use authorization gate was not satisfied by this generic implementation request.

The current 8090 recording and preflight GET attempts timed out at the 12-second bound. The read-only database inventory is independent of those failures. No shared service was restarted to make a probe pass.

The historical packet archive remains 300,069 bytes, SHA-256 c67cb5c0f2891475c580c0336eba630cf71336bb2943e71d7bfeaf55ea281a0e. The video remains 55,958,936 bytes, SHA-256 af97adea0bb2d08d866e0da51f0c9c382f4bc1673dece8236e88eee549727fb1. Matching bytes preserve the previous audit; they do not prove missing actions or complete source state.

## Felix and Shortcut

Canonical project: `C:\Dev\IdeaProjects\ChromaScape`.
Recorded existing task: `task_1789960012362_td2qnusp`; configured MCP returned Task not found. The canonical project list and bounded metadata-copy search did not recover it. No replacement task or invented workflow transition was created.

A story-linked Felix continuation plan was created as `note_1793383300090_26c4edf1`. Existing SC-274 pending records remain pending: successful note storage is not restoration of the missing task or replay of its historical workflow. Shortcut remains In Review; no Done or state-sync marker is warranted.

Implementation Evidence is `note_1793383660891_e2cb85fe`; Verification Evidence is
`note_1793383661203_c8c05411`, both stored under the canonical project and linked to story 274.
The plan checklist was reconciled to the actual local execution. No replacement task was created.

## Remaining work

1. Recover an authoritative backup/export of the original Felix task and linked specification/checklists; restore the same identity and replay pending records idempotently.
2. Obtain a genuine finalized Fishing recording carrying exact BehaviorRunContext, source/profile/task/graph/schema/cache identities, complete typed observations/actions and one published owned curation.
3. Run the compiler and source-provenance audit on that input, then causal handoff on the supported runtime; retain all genuine run evidence and distinguish synthetic simulator output.
4. Complete the authorized private-input and deployed authentication/API matrix, including ownership, dataset-role separation, bounded malformed/private refusal and zero writes on rejection.
5. Satisfy the original bank-to-travel-to-Fishing amendment where evidence permits. For b83c keep a precise blocked verdict until original gaps are actually resolved; a fresh recording does not rewrite it.
6. Reconcile every original acceptance/test item and exact source pin, then use validated Felix review/Done gates and configured Shortcut markers. Do not close on the basis of the fixture-only passes.

## Risks

The two clean builds and all 171 tests establish scoped authored behavior. They cannot supply absent human authorization, genuine source lineage, deployed authentication, an independent oracle or production qualification. The missing task metadata also prevents a truthful workflow completion.

## Rollback

Revert only the test-helper/documentation follow-up if necessary; retain failed logs, JUnit, clean clones and original capture bytes. Restore no retired credential and alter no source/trust identity. The newly owned test container and anonymous volume were removed only after matching its exact ID and ownership label; shared containers/databases/services were untouched.

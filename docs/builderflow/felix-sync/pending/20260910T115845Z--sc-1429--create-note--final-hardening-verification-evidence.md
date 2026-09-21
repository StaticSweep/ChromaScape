---
schema_version: 1
offline_record_id: ff23e98b-7b79-41b9-b720-a0bc59dccdaa
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1429"
shortcut_tag: shortcut:sc-1429
felix_task_id: task_1788438246897_fzmkmv44
operation: create_note
target_note_id: null
title: SC-1429 / SC-1449 final hardening verification evidence
note_type: Verification Evidence
stable_tags:
  - shortcut:sc-1429
  - shortcut:sc-1449
  - verification-evidence
  - change:multi-repo
  - offline-sync:ff23e98b-7b79-41b9-b720-a0bc59dccdaa
parent_id: task_1788438246897_fzmkmv44
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1429"
intended_felix_task_state: in_progress
intended_felix_spec_state: in_progress
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
created_at: 2026-09-10T11:58:45Z
---

## Task resolution

Reuse the existing Felix task `task_1788438246897_fzmkmv44` under workflow
`shortcut_delivery` and canonical project `C:\Dev\IdeaProjects\ChromaScape`. Never
create a second task for SC-1429. The task is linked to Shortcut story 1429, tagged
`shortcut:sc-1429`, and classified with the single authoritative classification tag
`change:multi-repo`. This evidence also covers the related non-live SC-1449 boundary;
it does not authorize live gameplay or create a second Felix task.

Scope is the reviewed SC-1429 / SC-1449 final hardening candidate at these exact
source snapshots:

- Micro6: `C:\Dev\IdeaProjects\micro6`, branch `sc-572-remediation`, HEAD
  `0579be811cf0957d9d3daf59a8e6a483ba4765c9`.
- ChromaScape review checkout: `C:\Dev\IdeaProjects\ChromaScape-sc1449-review`,
  branch `sc-1449-review-20260908`, HEAD
  `e5cdf81db75d76cf086a1b421282e1f5aefb25aa`.

The reviewed delivery scope is:

- Micro6 v2 stop lifecycle hardening, malformed-request boundary, and handler-level
  concurrency/fault/budget coverage.
- The retained ChromaScape V45 schema-scoped replay foreign-key regression test.
- The dated evidence package at
  `C:\Dev\IdeaProjects\ChromaScape-sc1449-review\docs\builderflow\verification\sc1429-sc1449-20260910\`.

Exclusions are live gameplay, positive reservation, start, authority consumption,
Fishing, SC-1498, Micro6 client dispatch, operator-authentication changes, v1
contract-resource changes, published V45 migration edits, repository-wide formatting,
unrelated dirty or staged files, `.env.local`, build output, tokens, and SC-1618
material. No commit or push is part of this record.

## Note payload

# SC-1429 / SC-1449 final hardening verification evidence

Evidence package:
`C:\Dev\IdeaProjects\ChromaScape-sc1449-review\docs\builderflow\verification\sc1429-sc1449-20260910\`

## Acceptance results

1. **PASS — exclusive stop ownership.**
   `concurrentStopLoserDoesNotRepeatTeardown` uses two handler invocations and a
   persistence barrier. The loser returned HTTP 202 with `STOPPING` and
   `terminalReconciled=false`; teardown remained one. The winner later returned HTTP
   200 with durable `STOPPED`.
2. **PASS — duplicate while stopping.** A duplicate stop request returns HTTP 202,
   does not repeat teardown, and reports the durable `STOPPING` state.
3. **PASS — kill switch is global.** Handler tests establish the global dispatch hold
   for a request already stopping and for terminal reservations, retaining the
   kill-switch reason.
4. **PASS — terminal governor release.** Parameterized terminal coverage includes
   `STOPPED`, `COMPLETED`, `CANCELLED`, and `FAILED`; stale request-scoped bindings are
   released while the global hold remains active.
5. **PASS — malformed requests.** The production handler receives null, numeric,
   object, null-array, missing-member, blank, overlong, and wrong-type values without
   test-helper coercion. Malformed input returns typed HTTP 400 `BAD_REQUEST`, wrong
   idempotency returns HTTP 409 `IDEMPOTENCY_CONFLICT`, and no durable state, governor,
   or kill-switch mutation occurs.
6. **PASS — evidence failure uncertainty.** Evidence and reconciliation-write fault
   tests return HTTP 503 `TRANSPORT_UNCERTAIN`, retain denial, require reconciliation,
   and never report `STOPPED` as a fabricated success.
7. **PASS — durable terminal budget races.** Durable `COMPLETED`, `STOPPED`, and
   `CANCELLED` outcomes remain authoritative over local expiry; no budget failure
   metadata is fabricated and completion time comes from durable state.
8. **PASS — genuine budget exhaustion.** Durable failure retains the exact
   `FAILED/BUDGET_EXHAUSTED` metadata.
9. **PASS in focused production tests; process fault injection remains open.**
   Unavailable durability produces `NEEDS_RECONCILIATION`/
   `GAMEPLAY_V2_DURABILITY_UNAVAILABLE`, `completedAt=0`, and a denied governor. A
   separate live process fault-injection run was not performed.
10. **PASS — V45 migration safety.** The retained test covers each optional child
    table absent, both parent-table absence cases, a same-named constraint in another
    schema, and Flyway rerun idempotency. Published
    `src/main/resources/db/migration/V45__schema_scoped_replay_foreign_keys.sql`
    was not edited.
11. **PASS — disabled boundary.** With both live flags false, all five authenticated
    routes returned HTTP 503 `V2_DISABLED`; missing and incorrect operator keys
    returned HTTP 401. No Micro6 request, port-8098 connection, reservation-row
    insertion, authority consumption, or gameplay-flag change was observed.
12. **PASS with classified exceptions — native gates.** Micro6 capture-contract,
    compile, focused, and full tests passed. ChromaScape compile, focused, and full
    tests passed. Micro6 Checkstyle and ChromaScape style remain non-green because of
    repository-wide unrelated parser/warning/Spotless findings; Micro6
    `:client:spotlessCheck` is unavailable. The exact diagnostics and classification
    are preserved in the evidence logs.
13. **PASS — reviewed final index.** `final-worktrees.txt` records exact branches,
    HEADs, staged names, and successful `git diff --cached --check`. Only the two
    approved Micro6 files, the retained migration test, and the dated evidence
    package are staged.
14. **PASS — no gameplay authority.** Both flags stayed false. No positive
    reservation, start, gameplay, Fishing run, or live SC-1498 execution occurred.

## Changed files

Micro6 staged files:

- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\Micro6BuilderExecutorServer.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\Micro6BuilderExecutorServerTest.java`

ChromaScape review staged files:

- `C:\Dev\IdeaProjects\ChromaScape-sc1449-review\src\test\java\com\chromascape\web\migration\SchemaScopedReplayForeignKeysMigrationTest.java`
- The fourteen required files under the dated evidence package listed in
  `changed-files.txt`, including the native command logs and final index snapshot.

The V45 production migration, `.env.local`, tokens, unrelated dirty paths, build
output, recorder files, and SC-1618 files are not staged.

## Tests

All native Gradle commands below were run from Windows PowerShell with the repository
contract-property argument where required. XML reports, not console estimates, were
used for counts.

### Micro6

- `gradlew.bat :client:verifyCaptureContract "-PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java" --no-daemon --console=plain` — passed; revision `54b6cd4fca11a42e52635ce7f27682526d8c1b50`, source hash `983d8efa42760aa9e654e5a44ce233d8078b089fd4349f1bd026571fc3a7fe6c`.
- `gradlew.bat :client:compileJava :client:compileTestJava "-PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java" --no-daemon --console=plain` — passed.
- Focused specification filters — 192 tests, 0 failures, 0 errors, 0 skipped.
- Full `:client:test` — 1,486 tests, 0 failures, 0 errors, 0 skipped.
- `:client:checkstyleMain :client:checkstyleTest --continue` — failed on repository-wide existing parser/style findings; no direct diagnostic implicated the two approved files.
- `:client:spotlessCheck` — unavailable; Gradle reports that the task does not exist.
- Scoped `git diff --check` for both approved files — passed.

### ChromaScape

- `gradlew.bat compileJava compileTestJava --no-daemon --console=plain` — passed.
- Focused specification filters — 125 tests, 0 failures, 0 errors, 0 skipped,
  including `SchemaScopedReplayForeignKeysMigrationTest`.
- Full `gradlew.bat test --no-daemon --console=plain` — 2,274 tests, 0 failures,
  0 errors, 17 skipped.
- `gradlew.bat checkstyleMain checkstyleTest spotlessCheck --continue --no-daemon --console=plain` — failed on existing repository-wide warnings and Spotless violations; no repository-wide formatter was run.
- All four v1/v2 production resource pairs compare byte-for-byte and match the
  required hashes; details are in `resource-hashes.txt`.
- Disabled startup reached HTTP 200 `/debugger`, and the route matrix is in
  `disabled-route-results.json`.
- Final staged review: `git diff --cached --check` passed; staged names are in
  `final-worktrees.txt`.

### Evidence references

- `micro6-focused.log`, `micro6-full.log`, `micro6-checkstyle.log`
- `chromascape-focused.log`, `chromascape-full.log`, `chromascape-style.log`
- `disabled-route-results.json`
- `restart-recovery-results.md`
- `resource-hashes.txt`
- `verification.md`

## Remaining work

- Complete application-level ChromaScape persistence restart and Micro6
  journal/timer/corruption evidence against the final candidate. Focused contract-double
  tests do not substitute for this process-boundary evidence.
- Resolve or explicitly accept the unrelated repository-wide style/tooling exceptions.
- Recover Felix transport and replay all pending records oldest-first; no Felix state,
  note, checklist, or Shortcut transition is claimed from this run.
- Keep SC-1449 open pending Felix reconciliation, exact human approval, and the
  separately authorized bounded SC-1498 run plus kill-switch restoration proof.
- No commit or push was made or authorized.

## Risks

- Both candidate repositories contain broad pre-existing dirty and concurrent-work
  artifacts. Future operations must preserve them and stage explicit paths only.
- Full style gates remain non-green for unrelated baseline/toolchain reasons.
- Process-boundary recovery evidence is incomplete.
- Felix MCP task/note tools were unavailable in this session; this pending outbox
  record is the durable replay source.
- No live behavior has been authorized or tested; this evidence cannot be used as
  proof of the separately approved SC-1498 execution.

## Rollback

No commit or push was made. If the candidate is rejected, remove only the reviewed
paths from the index or use a targeted revert after an explicitly authorized future
commit. Do not reset, clean, stash, or broadly checkout either repository. Keep both
gameplay flags false and preserve fail-closed reconciliation behavior.

## Checklist reconciliation

### Implementation checklist

- [x] Release request governors after proven terminal state — verified by the
  terminal retry/kill-switch handler tests and `verification.md` acceptance 4.
- [x] Preserve exclusive STOPPING ownership — verified by
  `concurrentStopLoserDoesNotRepeatTeardown` and acceptance 1–2.
- [x] Make malformed request handling typed and mutation-free — verified by the
  malformed matrix and acceptance 5.
- [x] Keep evidence/reconciliation uncertainty fail closed — verified by the two
  fault tests and acceptance 6/9.
- [x] Preserve exact budget terminal mapping — verified by the budget race and
  exhaustion tests and acceptance 7–9.
- [x] Retain and verify V45 migration coverage without editing V45 — verified by
  the focused ChromaScape suite and acceptance 10.
- [x] Record disabled-mode route evidence without live execution — verified by
  `disabled-route-results.json` and acceptance 11/14.
- [x] Record exact branches, HEADs, commands, results, resource hashes, and staged
  scope — verified by the dated package and `final-worktrees.txt`.
- [ ] Complete process-boundary restart, journal corruption, timer persistence, and
  storage-fault evidence — not run; documented in `restart-recovery-results.md`.
- [ ] Replay Felix and synchronize Shortcut — pending transport recovery.

### Test checklist

- [x] Micro6 capture-contract verification.
- [x] Micro6 compile and focused handler/governor/reservation/contract tests.
- [x] Micro6 full test suite.
- [x] Micro6 scoped whitespace check.
- [x] ChromaScape compile and focused migration/v2 tests.
- [x] ChromaScape full test suite.
- [x] ChromaScape disabled startup and authenticated route matrix.
- [x] Contract resource byte comparison and expected-hash verification.
- [x] Final staged-name and cached-diff review.
- [ ] Micro6 repository-wide Checkstyle green — classified unrelated failure.
- [ ] ChromaScape repository-wide Checkstyle/Spotless green — classified unrelated failure.
- [ ] Micro6 `:client:spotlessCheck` — unavailable task.
- [ ] Application-level process-boundary recovery evidence — not run.

## State reconciliation

When Felix recovers, replay this record only after resolving the task by recorded ID
and scanning for `offline-sync:ff23e98b-7b79-41b9-b720-a0bc59dccdaa`. Search for the
same tag before creating the note; an existing match makes replay idempotent. Replay
older pending records for task `task_1788438246897_fzmkmv44` first, oldest-first.
Create this Verification Evidence note only after preserving unrelated task/note
content and validating the recorded evidence.

The intended current task state remains `in_progress`; no `in_review` or `done`
transition is requested by this record because process-boundary evidence, Felix
replay, exact human approval, and the separately authorized live run remain open.
If later gates satisfy validation, the next permitted order is: validate the
Implementation Evidence and Verification Evidence notes, then transition Felix only
through its configured gate. Shortcut synchronization may occur only after the
corresponding Felix transition succeeds.

## Shortcut reconciliation

The active mapping file is
`C:\Dev\IdeaProjects\ChromaScape\.felix\shortcut-sync.json`. It maps `planned`,
`started`, `review`, and `done`, while `blocked` and `cancelled` are null. This
record requests no Shortcut transition and emits no completion marker. Story 1429
and related story 1449 remain open. When a Felix transition is actually accepted,
search Shortcut comments for the exact marker before posting one; never post a
`[felix-sync:task_1788438246897_fzmkmv44:done]` marker from this pending record.

## Sync result

Pending. Felix task resolution, Verification Evidence note creation, checklist
reconciliation, state transition, and Shortcut synchronization have not succeeded in
this session. No successful external mutation is claimed.

---
schema_version: 1
offline_record_id: c7fdd5c0-1b13-40eb-b0f3-ccb2df0a832c
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1429"
shortcut_tag: shortcut:sc-1429
felix_task_id: task_1788438246897_fzmkmv44
operation: create_note
target_note_id: null
title: SC-1429 native Windows verification evidence — 2026-09-17
note_type: documentation
stable_tags:
  - shortcut:sc-1429
  - change:multi-repo
  - verification-evidence
  - offline-sync:c7fdd5c0-1b13-40eb-b0f3-ccb2df0a832c
parent_id: task_1788438246897_fzmkmv44
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1429"
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
created_at: 2026-09-17T12:03:07Z
---

## Task resolution

Reuse existing Felix task `task_1788438246897_fzmkmv44` under workflow
`shortcut_delivery`, canonical project `C:\Dev\IdeaProjects\ChromaScape`, stable tag
`shortcut:sc-1429`, source link `shortcut_story:1429`, and authoritative classification
`change:multi-repo`. Never create a duplicate task.

Verified exact final pull-request heads in clean isolated native Windows worktrees:

- ChromaScape PR 2, `C:\Dev\IdeaProjects\ChromaScape-sc1429-verify-final`, detached
  `2d3f74ae1c7585506fcfd23a85a8052d7d198e5b`.
- Micro6 PR 141, `C:\Dev\IdeaProjects\micro6-sc1429-verify-final`, detached
  `96106cc90eb131e20ff022f93ee658a9e952c854`.
- Locked contracts dependency, `C:\Dev\IdeaProjects\contracts-sc1429-verify`, detached
  `7f4fa47ea3f195ff12ca759a0c556b21fedbfc9f`.

Scope is native Windows completion verification, resource parity, PR-boundary review,
and durable evidence. Exclusions: no code changes, broad formatting, runtime/client
launch, server start, gameplay, reservation, dispatch, credentials, commit, push, merge,
or Shortcut completion. GitHub Actions budget failures remain waived only as described in
the Shortcut handoff; no green CI result is claimed.

## Note payload

# SC-1429 native Windows verification evidence

## Acceptance results

1. **PASS — exact immutable heads.** Both fetched PR heads equal the Shortcut-recorded
   SHA values. Separate detached worktrees were created without moving user branches.
2. **PASS — ChromaScape focused verification.** Seven XML reports contain 73 tests,
   0 failures, 0 errors, and 0 skipped.
3. **PASS — ChromaScape complete gate.** Eight XML reports contain 74 tests, 0 failures,
   0 errors, and 0 skipped. `checkstyleMain`, `checkstyleTest`, and `spotlessCheck` passed.
   `git diff --check` passed and the exact-head worktree remained clean.
4. **PASS — Micro6 locked contract dependency.** An initial run correctly failed closed
   because the default shared contracts checkout was at revision `54b6cd4...`, while
   `capture-contract.lock` requires `7f4fa47...`. A clean detached contracts worktree was
   created at the locked revision and supplied through the repository-supported
   `-PaiRoutineCaptureContractsDir` property. `:client:verifyCaptureContract` then passed;
   no verifier was disabled or bypassed.
5. **PASS — Micro6 Java 11 compile.** `:client:java11ContractCompile` passed with the
   locked dependency.
6. **PASS — Micro6 focused verification and engine discovery.** Five XML reports contain
   29 tests, 0 failures, 0 errors, and 0 skipped: resource equality 2, v2 conformance 15,
   v2 idempotency 10, JUnit 4 `MapClueTest` 1, and JUnit 4 `MiningAnimationTest` 1. The
   contract reports prove Jupiter execution and the two JUnit 4 reports prove Vintage
   execution. `git diff --check` passed and the exact-head worktree remained clean.
7. **PASS — frozen resources.** Every v1/v2 schema and fixture matches its pinned SHA-256
   in both repositories and every pair is byte-identical.
8. **PASS — static PR boundary.** ChromaScape PR 2 changes contract/provider code,
   resources, tests, `.gitattributes`, and build test configuration only. Micro6 PR 141
   changes contract code, contract tests, and the Java 11 compile task only. The PR file
   lists contain no SC-1449 dispatch gate, runtime launcher, raw-input implementation,
   credential, provider transcript, generated output, or gameplay execution path.
9. **PASS — PR availability.** Both PRs are open, mergeable, and still point to the
   verified exact heads.
10. **BLOCKED — human approval.** GitHub reports an empty `reviewDecision` for both PRs.
    Existing reviews are `COMMENTED`; neither PR has an approving review. The story must
    remain In Review pending human approval.
11. **WAIVED, not passed — hosted CI.** GitHub check runs remain failed because jobs did
    not start under the documented Actions budget condition. Native exact-head gates are
    green, but no green hosted CI result is claimed.
12. **BLOCKED — Felix reconciliation.** Felix failed twice with the server-side error
    `SQLite package has not been found installed`. This evidence is therefore in the
    durable outbox and no Felix note, checklist, or state transition is claimed.

## Tests

ChromaScape, from
`C:\Dev\IdeaProjects\ChromaScape-sc1429-verify-final`:

- `gradlew.bat test --tests com.chromascape.web.gameplay.contract.GameplayContractConformanceTest --tests "com.chromascape.web.gameplay.contract.v2.*" --tests "com.chromascape.web.gameplay.provider.*" --no-daemon --console=plain` — passed; 73/0/0/0.
- `gradlew.bat test checkstyleMain checkstyleTest spotlessCheck --no-daemon --console=plain` — passed; 74/0/0/0 and all quality tasks green.
- `git diff --check` — passed.
- Final `git status --short` — empty.

Micro6, from `C:\Dev\IdeaProjects\micro6-sc1429-verify-final`, with
`CHROMASCAPE_PROJECT_DIR=C:\Dev\IdeaProjects\ChromaScape-sc1429-verify-final` and
`-PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts-sc1429-verify\java`:

- `gradlew.bat :client:java11ContractCompile ... --no-daemon --console=plain` — passed.
- `gradlew.bat :client:test ...` with the five required class filters — passed in 9m;
  29 tests, 0 failures, 0 errors, 0 skipped.
- XML reports: resource equality 2, v2 conformance 15, v2 idempotency 10,
  `MapClueTest` 1, `MiningAnimationTest` 1.
- `git diff --check` — passed.
- Final `git status --short` — empty.

Pinned SHA-256 values verified in both repositories:

- V1 schema: `010cba11ada57f328b43968be894f4875edd2ef8ee582c6eb5a924153e461469`.
- V1 fixtures: `c55ad78af5b6367803bc7098e0ef0a03e03d8efff6465bd4eae7829aa743c19c`.
- V2 schema: `1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b`.
- V2 fixtures: `96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549`.

Byte lengths also match pairwise: V1 schema 8770, V1 fixtures 8952, V2 schema 23654,
and V2 fixtures 52932 bytes.

## Risks

- No human approving review exists; this is the active completion blocker.
- Felix remains unavailable, so required evidence and checklist reconciliation are not
  yet present in the durable Felix task.
- Hosted CI is not green; the documented budget waiver permits the native handoff but
  does not turn failed check runs into successful evidence.
- Micro6 emits existing compile warnings and Gradle deprecation warnings; the prescribed
  compile and filtered test tasks nevertheless completed successfully.
- No live or runtime behavior was tested or authorized. This evidence cannot prove or
  authorize SC-1449 dispatch or SC-1498 gameplay.

## Rollback

No delivery code, branch, commit, PR, or Shortcut state was changed. Verification added
only detached Git worktrees and this outbox evidence record. If cleanup is later desired,
remove only the three explicitly named verification worktrees through their owning Git
repositories after confirming they remain clean. Do not reset, clean, stash, or alter any
user branch. Keep both PRs unmerged and all live-control flags unchanged.

## Checklist reconciliation

### Implementation checklist

- [x] Fetch final PR refs and prove expected commit identities.
- [x] Create clean isolated exact-head worktrees without moving user branches.
- [x] Capture and preserve clean final worktree state.
- [x] Execute native Windows ChromaScape verification.
- [x] Execute native Windows Micro6 verification with the locked dependency.
- [x] Recompute pinned hashes and prove binary equality.
- [x] Audit final PR file boundaries against the exclusions.
- [x] Confirm both PRs remain open, mergeable, and at the verified heads.
- [ ] Obtain a human approving review.
- [ ] Replay Felix records oldest-first and reconcile task notes/checklists/state.
- [ ] Synchronize Shortcut only after the corresponding Felix transition succeeds.

### Test checklist

- [x] ChromaScape focused contract/provider tests.
- [x] ChromaScape complete tests, Checkstyle, and Spotless.
- [x] ChromaScape whitespace and clean-worktree checks.
- [x] Micro6 locked capture-contract verification.
- [x] Micro6 Java 11 contract compilation.
- [x] Micro6 required Jupiter and Vintage tests.
- [x] Micro6 XML report inspection.
- [x] Micro6 whitespace and clean-worktree checks.
- [x] Four pinned resource SHA-256 checks in both repositories.
- [x] Four pairwise binary equality checks.
- [x] Static safety audit of both PR file lists.
- [ ] Hosted CI green — waived budget condition; explicitly not claimed.

## State reconciliation

When Felix recovers, replay all pending SC-1429 records oldest-first. Resolve the task by
`task_1788438246897_fzmkmv44` or `shortcut:sc-1429`; never create a duplicate. Search for
`offline-sync:c7fdd5c0-1b13-40eb-b0f3-ccb2df0a832c` before creating this note. Link it as
Verification Evidence and preserve unrelated task content. Check the verified automated
items above only after inspecting this evidence. Keep the task in review while human
approval is absent. Transition to done only after approval is documented and Felix's
validation gate succeeds.

## Shortcut reconciliation

Shortcut story 1429 remains In Review. No transition or comment is intended from this
record. After human approval and a successful Felix done transition, search existing
comments for `[felix-sync:task_1788438246897_fzmkmv44:done]`, transition Shortcut to
workflow state `500000010`, and post at most one completion comment containing the exact
marker and evidence references. Never post the marker before Felix succeeds.

## Sync result

Pending. Native exact-head verification is complete and green within the authorized
scope. Human approval and Felix reconciliation remain outstanding. No Felix write,
Shortcut mutation, merge, commit, push, runtime launch, or gameplay is claimed.

---
schema_version: 1
offline_record_id: bc0a0cbd-e6d9-4f44-afd6-0d4cab32d158
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1429"
shortcut_tag: shortcut:sc-1429
felix_task_id: task_1788438246897_fzmkmv44
operation: create_note
target_note_id: null
title: SC-1429 completion verification plan — 2026-09-17
note_type: documentation
stable_tags:
  - shortcut:sc-1429
  - change:multi-repo
  - verification-plan
  - offline-sync:bc0a0cbd-e6d9-4f44-afd6-0d4cab32d158
parent_id: task_1788438246897_fzmkmv44
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1429"
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
created_at: 2026-09-17T11:30:28Z
---

## Task resolution

Reuse Felix task `task_1788438246897_fzmkmv44` under workflow
`shortcut_delivery` for canonical project `C:\Dev\IdeaProjects\ChromaScape`.
Never create another task for `shortcut:sc-1429`. The task's authoritative
classification is `change:multi-repo`. The source is Shortcut story 1429.

Scope is completion verification of the already implemented provider-neutral SC-1429
contract at the final pull-request heads recorded by the Shortcut handoff:

- ChromaScape PR 2: `2d3f74ae1c7585506fcfd23a85a8052d7d198e5b`.
- Micro6 PR 141: `96106cc90eb131e20ff022f93ee658a9e952c854`.

The active clean worktrees are currently stale and must not be tested as final evidence:

- `C:\Dev\IdeaProjects\ChromaScape-sc1429-delivery-lf` is at
  `a4193394c63af187870dd94653b66b463690fd3a` and does not yet contain the recorded final
  ChromaScape object locally.
- `C:\Dev\IdeaProjects\micro6-sc1429-delivery` is at
  `ff9c127b17c349f9a9510080f4495625ffa75085`; the recorded final Micro6 object exists
  locally on `origin/codex/sc-1429-contract-delivery` but is not checked out.

First fetch PR refs without force-updating user branches, resolve both expected commits,
and use clean isolated worktrees at the exact immutable heads. Preserve all unrelated
worktrees and user changes.

Exclusions: no PR merge, push, commit, broad formatting, runtime/client launch, server
start, gameplay, reservation, dispatch, credential use, SC-1449 implementation, SC-1498
execution, or live-control change. GitHub Actions budget failure is recorded but was
waived for this handoff; no green CI result may be claimed.

Linked project rules:

- Use native Windows only for both builds and tests.
- Micro6 compilation/testing is authorized by the user's 2026-09-17 request; runtime
  launch remains unauthorized.
- Use Windows paths in Felix and Shortcut artifacts.
- Keep both PRs unmerged pending separate authorization.

## Note payload

# SC-1429 completion verification plan

## Scope and exclusions

Verify the exact final ChromaScape and Micro6 PR heads, prove the contract resources are
unchanged and byte-identical, confirm required focused tests and quality gates, re-audit
the PR boundaries for forbidden runtime capabilities, obtain or identify the remaining
human approval, and reconcile Felix evidence before completion. Do not modify delivery
code unless verification exposes an in-scope defect and a separate remediation record is
created. Do not merge either PR.

## Given/When/Then acceptance criteria

1. Given the Shortcut-recorded PR heads, when verification starts, then each isolated
   worktree resolves to the exact expected SHA and is clean before and after testing.
2. Given ChromaScape at its exact head, when the focused and complete prescribed native
   Windows Gradle gates run, then all tests pass and Checkstyle, Spotless, and
   `git diff --check` are green.
3. Given Micro6 at its exact head, when the Java 11 compilation and focused native
   Windows test gates run, then compilation passes, all named suites pass, and XML
   reports prove both Jupiter and Vintage discovery.
4. Given the four frozen v1/v2 resources in both repositories, when SHA-256 and binary
   comparisons run, then every hash equals the pinned value and each cross-repository
   pair is byte-identical.
5. Given the PR diffs, when the safety boundary is audited, then no SC-1449 dispatch,
   runtime launch, raw input, credential, provider transcript, generated output, or
   gameplay authority has entered either PR.
6. Given all automated gates pass, when review status is checked, then all threads remain
   resolved and a human approval exists; otherwise approval remains an explicit blocker.
7. Given Felix becomes available, when pending records are replayed oldest-first, then
   Implementation Evidence and Verification Evidence are linked, every checked item is
   backed by actual output, and the task transitions only through validated gates.
8. Given Felix accepts the final done transition, when Shortcut synchronization runs,
   then story 1429 moves to Done and exactly one
   `[felix-sync:task_1788438246897_fzmkmv44:done]` marker exists. Until then no done
   marker or completion claim is permitted.

## Implementation checklist

- [x] Fetch the current Shortcut story, comments, linked items, and workflow state.
- [x] Read both repository AGENTS.md files and active Shortcut mappings.
- [x] Confirm both named delivery worktrees exist and are clean.
- [x] Identify that both active worktree heads differ from the final recorded heads.
- [ ] Fetch PR 2 and PR 141 refs and verify the expected commit identities.
- [ ] Create or select clean isolated worktrees at the exact final heads without moving
      or overwriting unrelated user branches.
- [ ] Capture baseline branch, head, remote, Java, Gradle, and worktree state.
- [ ] Execute native Windows ChromaScape verification.
- [ ] Execute native Windows Micro6 verification.
- [ ] Recompute all pinned hashes and prove binary equality.
- [ ] Audit final PR file lists and diffs against the exclusions and safety boundary.
- [ ] Confirm review-thread and approval status without merging.
- [ ] Write complete Implementation Evidence and Verification Evidence.
- [ ] Replay Felix records oldest-first and reconcile checklists/state when transport
      recovers.
- [ ] Synchronize Shortcut only after the corresponding Felix transition succeeds.

## Test checklist

- [ ] ChromaScape focused contract/provider tests at exact head.
- [ ] ChromaScape complete `test checkstyleMain checkstyleTest spotlessCheck` gate.
- [ ] ChromaScape `git diff --check` and clean final status.
- [ ] Micro6 `:client:java11ContractCompile` at exact head.
- [ ] Micro6 focused resource equality, v2 conformance, v2 idempotency, MapClue, and
      MiningAnimation tests.
- [ ] Micro6 XML proof for Jupiter and Vintage reports.
- [ ] Micro6 `git diff --check` and clean final status.
- [ ] SHA-256 verification for v1 schema, v1 fixtures, v2 schema, and v2 fixtures.
- [ ] Binary equality for each ChromaScape/Micro6 resource pair.
- [ ] Static safety audit of both final PR diffs.
- [ ] Review-thread resolution and human approval check.

## Conditional design artifacts

No new architecture, ERD, API contract, UI mockup, observability design, security model,
or data migration is required because this is verification of an existing multi-repo
contract implementation. The relevant design artifacts are the existing SC-1429 v1/v2
schemas, fixtures, GoalPlan/TaskSkill contract, ExecutionEnvelope contract, signed
receipt design, and their pinned hashes. If verification requires a code correction,
stop completion reconciliation, document the defect and impact, and update the existing
plan/evidence before changing delivery state.

## Checklist reconciliation

Planning and read-only preflight items above are checked with evidence from Shortcut,
repository rule files, sync mappings, and native Git inspection. No test or completion
item is checked yet. Existing historical evidence is not substituted for exact-head
verification.

## State reconciliation

Felix calls failed twice with `SQLite package has not been found installed`; therefore
Felix is currently unavailable under the outage rule. Replay all pending records for
SC-1429 oldest-first. Search for
`offline-sync:bc0a0cbd-e6d9-4f44-afd6-0d4cab32d158` before creating this note. Preserve
unrelated Felix content. The intended current task state is `in_review`, matching
Shortcut, but no Felix transition is claimed. After verification, add authoritative
Implementation Evidence and Verification Evidence and transition to done only if the
workflow validates every acceptance and test item.

## Shortcut reconciliation

Shortcut story 1429 is already In Review. No Shortcut mutation is intended during
planning or test execution. After Felix actually reaches done, search comments for the
exact done marker, move Shortcut to workflow state `500000010`, and post at most one
completion comment with evidence references. Human approval or any failed mandatory gate
keeps the story In Review.

## Sync result

Pending. The plan is durably recorded in the Felix outage outbox. No Felix note,
checklist update, transition, Shortcut transition, PR merge, commit, push, or test result
is claimed by this record.

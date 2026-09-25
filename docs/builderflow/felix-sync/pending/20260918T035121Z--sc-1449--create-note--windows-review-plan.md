---
schema_version: 1
offline_record_id: d3b1eccc-07ff-4924-8067-b48eb909662b
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1449"
shortcut_tag: shortcut:sc-1449
felix_task_id: task_1789544291561_fyqzr7wil
operation: create_note
target_note_id: null
title: SC-1449 Windows review plan — 2026-09-18
note_type: documentation
stable_tags: [shortcut:sc-1449, change:multi-repo, verification-plan, offline-sync:d3b1eccc-07ff-4924-8067-b48eb909662b]
parent_id: task_1789544291561_fyqzr7wil
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1449"
created_at: 2026-09-18T03:51:21Z
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
---

## Task resolution

Reuse Felix task `task_1789544291561_fyqzr7wil`, workflow `shortcut_delivery`, canonical
project `C:\Dev\IdeaProjects\ChromaScape`, source `shortcut_story:1449`, stable tag
`shortcut:sc-1449`, and classification `change:multi-repo`. Never create a duplicate.
Felix lookup failed twice during this run, so this plan is recorded in the outage outbox.

Scope: review and verify the current SC-1449 ChromaScape/Micro6 action-gate candidate on
native Windows without disturbing the user's running main application. Use the dedicated
`C:\Dev\IdeaProjects\micro6-sc1449-closure` worktree, not the mixed SC-1618 checkout, and
the existing `C:\Dev\IdeaProjects\ChromaScape-sc1449-review` worktree. Preserve every
unrelated staged and unstaged change.

Exclusions: no port 8090/8098 binding, service restart, client launch, live flag change,
reservation, authority consumption, gameplay dispatch, bounded Fishing run, commit, push,
merge, or Shortcut completion during this non-live phase.

## Note payload

# SC-1449 Windows review plan

## Scope and exclusions

Freeze and audit the dedicated review candidates; run native Windows compile, focused and
full tests, style checks, whitespace checks, resource/freeze checks, and offline
journal/restart/timer/corruption tests. Classify baseline style failures without modifying
unrelated files. Defer application HTTP restart, cross-process reserve/status/stop,
kill-switch proof, and live Fishing until a coordinated port window and explicit live
authorization.

## Given/When/Then acceptance criteria

1. Given concurrent SC-1618 work, when SC-1449 is verified, then only dedicated review
   worktrees are used and unrelated changes remain untouched.
2. Given the gate candidate, when native compile and focused/full tests run, then all
   SC-1449 lifecycle, stop, store, governor, migration, and transport tests pass.
3. Given restart/corruption/timer requirements, when offline production-store tests run,
   then journal recovery, corruption rejection, durable timers, idempotency, and
   reconciliation remain fail-closed without binding application ports.
4. Given style gates, when they run, then in-scope failures are fixed before delivery and
   unrelated baseline/tooling failures are recorded precisely.
5. Given the safety boundary, when final diffs are inspected, then no raw input, provider
   execution path, credential, direct Rs2 action route, or unauthorized gameplay is added.
6. Given service-level acceptance, when ports 8090/8098 or gameplay would be required,
   then work pauses for coordination rather than disrupting the running application.

## Implementation checklist

- [x] Fetch current Shortcut story, comments, links, and review state.
- [x] Read project rules and Shortcut mapping.
- [x] Scan pending outbox and attempt Felix recovery twice.
- [x] Identify dedicated ChromaScape and Micro6 review worktrees.
- [x] Exclude the mixed canonical Micro6 SC-1618 checkout.
- [ ] Freeze exact candidate paths and diffs.
- [ ] Run native Windows compile and focused tests.
- [ ] Run native Windows full tests and style gates.
- [ ] Run offline restart/journal/corruption/timer verification.
- [ ] Audit final diffs, resources, and clean/scoped status.
- [ ] Create Implementation Evidence and Verification Evidence.
- [ ] Coordinate service-level and live verification separately.
- [ ] Replay Felix and synchronize Shortcut only after truthful completion.

## Test checklist

- [ ] Micro6 capture-contract verification and compile.
- [ ] Micro6 SC-1449 executor/store/governor/gate focused suites.
- [ ] Micro6 full `:client:test`.
- [ ] Micro6 Checkstyle and scoped whitespace checks.
- [ ] ChromaScape compile and SC-1449 focused suites.
- [ ] ChromaScape full tests, Checkstyle, and Spotless.
- [ ] ChromaScape migration/restart persistence tests.
- [ ] Offline journal corruption and durable timer tests.
- [ ] Disabled-route HTTP and live cross-process tests — deferred to port window.
- [ ] Bounded Fishing and kill-switch restoration — deferred, explicit live authorization required.

## Conditional design artifacts

Existing SC-1449 architecture, API, ERD, lifecycle and evidence artifacts remain
authoritative. This review introduces no new API, schema, UI, or data design. If a defect
requires source changes, update the existing artifacts and evidence rather than creating a
parallel gate or lifecycle.

## Checklist reconciliation

Only preflight and isolation items are checked. Historical results do not substitute for
the current native run.

## State reconciliation

Keep Felix and Shortcut in review during verification. On recovery, replay pending SC-1449
records oldest-first and search for the offline-sync tag before creating this note.

## Shortcut reconciliation

No Shortcut mutation is intended during this phase. Do not post a review/done marker until
the corresponding Felix transition succeeds, unless the user explicitly overrides that
ordering as they did for SC-1429.

## Sync result

Pending. No Felix write, state transition, service restart, live action, commit, push, or
merge is claimed.

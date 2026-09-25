---
schema_version: 1
offline_record_id: f6602888-5b9b-40a9-97d2-3bda819d8683
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1449"
shortcut_tag: shortcut:sc-1449
felix_task_id: task_1789544291561_fyqzr7wil
operation: create_note
target_note_id: null
title: SC-1449 Windows non-live verification evidence — 2026-09-18
note_type: documentation
stable_tags: [shortcut:sc-1449, change:multi-repo, verification-evidence, offline-sync:f6602888-5b9b-40a9-97d2-3bda819d8683]
parent_id: task_1789544291561_fyqzr7wil
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1449"
created_at: 2026-09-18T14:41:01Z
intended_felix_task_state: in_review
intended_felix_spec_state: spec_ready
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
---

## Task resolution

Reuse Felix task `task_1789544291561_fyqzr7wil`, workflow `shortcut_delivery`, canonical
project `C:\Dev\IdeaProjects\ChromaScape`, source `shortcut_story:1449`, stable tag
`shortcut:sc-1449`, and classification `change:multi-repo`. Never create a duplicate.
Felix was unavailable, so this evidence is recorded for oldest-first replay.

Native Windows verification used dedicated review worktrees and did not touch the user's
running main application:

- Micro6 candidate: `C:\Dev\IdeaProjects\micro6-sc1449-closure`, branch
  `codex/sc-1449-closure-20260911`, base HEAD
  `0e95a10d5b1aadfc3e30e315f5d41642008baceb`, with scoped uncommitted candidate changes.
- ChromaScape candidate: `C:\Dev\IdeaProjects\ChromaScape-sc1449-review`, branch
  `sc-1449-review-20260908`, HEAD `e5cdf81db75d76cf086a1b421282e1f5aefb25aa`, with
  existing staged evidence/migration test and scoped unstaged gate changes.
- Baseline comparator: `C:\Dev\IdeaProjects\micro6-sc1429-sc1449-frozen`, same base HEAD
  and no scoped candidate diff.

The canonical `C:\Dev\IdeaProjects\micro6` checkout was excluded because it is now on
SC-1618 and contains overlapping concurrent changes.

## Note payload

# SC-1449 Windows non-live verification evidence

## Acceptance results

1. **PASS — isolated review.** Verification used dedicated worktrees and preserved the
   mixed SC-1618 checkout and all unrelated staged/unstaged content.
2. **PASS — Micro6 contract and compilation.** `:client:verifyCaptureContract`,
   `:client:compileJava`, and `:client:compileTestJava` passed against locked contracts
   revision `54b6cd4fca11a42e52635ce7f27682526d8c1b50`.
3. **PASS — Micro6 SC-1449 focused/offline suite.** Ten XML reports contain 212 tests,
   0 failures, 0 errors, and 0 skipped. Coverage includes the executor HTTP boundary,
   reservation journal, dispatch-hold store, governor, v1/v2 envelope gates, dispatch
   integration, contract conformance, idempotency, restart recovery, corruption rejection,
   authority reuse, durable action/attempt budgets, uncertain outcomes, stop ownership,
   kill switch, malformed requests, and evidence faults.
4. **PASS — ChromaScape focused/offline suite.** Fifteen XML reports contain 101 tests,
   0 failures, 0 errors, and 0 skipped. Coverage includes live-gate service/controller,
   auth, cross-boundary HTTP via test endpoints, gateway mapping, recovery coordinator,
   JPA store, database restart, concurrency, rollback, and V45 migration safety.
5. **PASS — ChromaScape full regression.** 2,274 tests ran: 0 failures, 0 errors, 17
   skipped. Build successful in 17m23s.
6. **CLASSIFIED BASELINE — Micro6 full regression.** 1,614 tests ran: 5 failures, 0
   errors, 1 skipped. The failures are two `TaskSourceManifestSchemaEqualityTest` pin/mirror
   mismatches and three `TaskSourceProcessorPathTest` missing processor-path configuration
   failures. Re-running those exact two classes in the clean same-base comparator produced
   the identical 2+3 failures, proving they are not introduced by the SC-1449 candidate.
7. **CLASSIFIED BASELINE — repository-wide style.** ChromaScape Checkstyle tasks passed
   with warnings, while Spotless failed on broad existing files; none of the scoped
   SC-1449 files appeared in Spotless's violation output. Micro6 Checkstyle failed on broad
   existing parser/tab/style debt; no direct diagnostics named the three SC-1449 candidate
   files. Micro6 has no Spotless task.
8. **PASS — scoped whitespace and safety review.** `git diff --check` passed for all
   scoped candidate paths. Added-line screening found no raw mouse/keyboard, packet,
   WebSocket, reflection, direct Rs2 action, or credential route. Matches for “token” were
   internal dispatch-hold ownership tokens, not credentials.
9. **NOT RUN — service-level/live boundary.** No application bound 8090 or 8098, no
   service was restarted, no live flag changed, no reservation/authority was consumed, no
   client launched, and no gameplay/Fishing occurred. Cross-process reserve/status/stop,
   application restart, live timer/corruption, kill-switch restoration, and bounded Fishing
   remain separately coordinated acceptance work.

## Tests

### Micro6

- `gradlew.bat :client:verifyCaptureContract :client:compileJava :client:compileTestJava
  -PaiRoutineCaptureContractsDir=C:\Dev\IdeaProjects\contracts\java --no-daemon
  --console=plain` — passed.
- `gradlew.bat :client:test ... --tests Micro6BuilderExecutorServerTest --tests
  '...builder.gameplay.*'` — 212/0/0/0, passed.
- Full `:client:test` — 1,614 tests; 5 baseline failures, 1 skipped.
- Same failing two classes on clean comparator — identical 5 failures.
- Repository-wide Checkstyle — failed on broad existing debt; candidate paths had no
  direct diagnostic.
- Scoped `git diff --check` — passed.

### ChromaScape

- Focused orchestration v2, AccountBuilder orchestration, and migration suite —
  101/0/0/0, passed.
- Full `test` — 2,274 tests, 0 failures, 0 errors, 17 skipped, passed.
- `checkstyleMain checkstyleTest` — tasks passed with existing warnings. The live-gate
  service has 13 warnings: three missing-Javadoc and ten declaration-distance warnings.
- `spotlessCheck` — failed on broad existing formatting debt; scoped candidate files were
  absent from the violation list.
- Scoped staged and unstaged `git diff --check` — passed.

## Risks

- Candidate changes remain uncommitted and coexist with broader historical worktree dirt;
  only explicit paths may be staged or committed.
- The Micro6 full suite and repository-wide style gates are not globally green, though the
  exact failures reproduce without the candidate and the 212 in-scope tests are green.
- ChromaScape's live-gate service retains 13 Checkstyle warnings.
- Service-level and live acceptance remain unverified and could reveal integration defects
  not observable in offline tests.
- Felix remains unavailable; no note/checklist/state success is claimed.

## Rollback

No source edit, formatting application, staging change, commit, push, merge, runtime
launch, or tracker transition was performed. Preserve all worktrees. If later rejecting the
candidate, remove only explicitly reviewed candidate paths after capturing their patch;
never reset, clean, stash, or broadly checkout these repositories.

## Checklist reconciliation

### Implementation checklist

- [x] Isolate current candidates from concurrent SC-1618 work.
- [x] Freeze candidate base identities and scoped diffs.
- [x] Run native Windows compilation and focused tests.
- [x] Run full tests and classify baseline failures with a clean comparator.
- [x] Run style and scoped whitespace checks.
- [x] Exercise offline restart/journal/corruption/timer behavior through production-store
  and boundary tests.
- [x] Audit the candidate safety boundary.
- [ ] Coordinate application service/port window.
- [ ] Run cross-process reserve/status/stop, restart, timer, corruption, and kill-switch
  restoration proof.
- [ ] Run separately authorized bounded Fishing acceptance if still required.
- [ ] Freeze exact final staged set, commit, push, and review PR.
- [ ] Replay Felix and synchronize Shortcut.

### Test checklist

- [x] Micro6 contract verification and compile.
- [x] Micro6 executor/store/governor/gate focused suite.
- [x] Micro6 full suite with baseline classification.
- [x] Micro6 Checkstyle assessment and scoped whitespace check.
- [x] ChromaScape focused gate/persistence/restart/migration suite.
- [x] ChromaScape full suite.
- [x] ChromaScape Checkstyle/Spotless assessment and scoped whitespace check.
- [x] Offline journal corruption and durable timer tests.
- [ ] Disabled-route live application probe in a coordinated port window.
- [ ] Real ChromaScape-to-Micro6 service boundary tests.
- [ ] Bounded Fishing and live kill-switch restoration.

## State reconciliation

Keep Felix in review. On recovery, replay all pending SC-1449 records oldest-first and
search for `offline-sync:f6602888-5b9b-40a9-97d2-3bda819d8683` before creating this note.
Reconcile only evidence-backed items and preserve unrelated content.

## Shortcut reconciliation

Keep Shortcut SC-1449 in In Review. No transition or synchronization marker is intended
until remaining service/live gates and delivery steps are resolved.

## Sync result

Pending. Non-live native Windows verification is complete within scope. Service/live
acceptance, final staging/commit/push/review, Felix reconciliation, and Shortcut completion
remain outstanding.

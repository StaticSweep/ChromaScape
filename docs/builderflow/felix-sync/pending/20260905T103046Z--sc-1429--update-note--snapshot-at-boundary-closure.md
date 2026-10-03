---
schema_version: 1
offline_record_id: offline-20260905-sc1429-snapshot-boundary-001
sync_status: conflict
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: 1429
shortcut_tag: shortcut:sc-1429
felix_task_id: task_1788438246897_fzmkmv44
operation: update_note
target_note_id: note_sc1429_v2_implementation_evidence
title: SC-1429 implementation evidence — sc-1429/v2 contract, fixtures, and conformance
note_type: documentation
stable_tags:
  - shortcut:sc-1429
  - implementation-evidence
  - golden-fixtures
  - change:multi-repo
  - luna-max
parent_id: null
created_at: 2026-09-05T10:30:46Z
intended_felix_state: in_progress
intended_shortcut_transition: none
---

## Task resolution

- Task: `task_1788438246897_fzmkmv44` (workflow `shortcut_delivery`) under
  `C:\Dev\IdeaProjects\ChromaScape`, tagged `shortcut:sc-1429`.
- Shortcut story: `1429` (SC-1429 stays open; latent contract/integration risk only,
  no production v2 call sites or live dispatch).
- Scope: close the sealed-eligibility snapshot-at-boundary TOCTOU race, fail closed
  on malformed non-object catalogs with stable codes, add mutation regression
  tests, and correct the evidence blockers wording.
- Exclusions: durable provider sessions (SC-1430), orchestration (SC-1431),
  TaskSkill lifecycle (SC-1445), live gate (SC-1449), operator Fishing recording
  (SC-1498); no v1-to-v2 conversion; no gameplay dispatched.

## Note payload

The evidence note `note_sc1429_v2_implementation_evidence` was updated with a
"Fifth hardening (2026-09-05 — snapshot-at-boundary TOCTOU closure)" section:

1. `GameplayV2ValidatedEligibility.seal` deep-copies catalog, observation,
   planning input and eligibility on entry and runs validation, replay, digest
   calculation and storage exclusively against those snapshots.
2. The sealed adapter overload deep-copies the caller-owned catalog on entry and
   uses that one snapshot for catalog binding, schema validation and GoalPlan
   validation; the replay-bound overload snapshots all four inputs once so seal
   and sealed adaptation observe identical bytes.
3. Non-object catalogs (array/scalar) fail closed with `NODE_CATALOG_MISMATCH`
   at binding/adapter checks (`INVALID_IMMUTABLE_TASK_CATALOG_SNAPSHOT` at seal
   time) instead of escaping as `ClassCastException`.
4. New regression tests: `mutationAfterSealEntryCannotAffectTheBoundSeal`
   (all four digests + result pinned against the pre-mutation fixtures),
   `malformedNonObjectCatalogFailsClosedWithStableCode`,
   `mutatedSnapshotCatalogCannotPassTheSealedAdapterBoundary`.
5. "Test results" and "Environment blockers" sections corrected to distinguish:
   scoped WSL v2 JUnit EXECUTED (53/53), full suite NOT executed, native-Windows
   gates NOT executed. Gradle `test` itself is blocked by a stale unrelated
   build output (`NoSuchFileException` for `PatchLoopIterationResponse.class`
   in `:compileJava`); the v2 packages compile clean in isolation.

Changed files (Windows paths for Felix identity):

- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\contract\v2\GameplayV2ValidatedEligibility.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\provider\v2\GameplayV2ProviderAdapter.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\gameplay\contract\v2\GameplayV2SealedEligibilityTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-notes\notes\note_sc1429_v2_implementation_evidence.md`

## Checklist reconciliation

- Implementation checklist (all verified this session):
  - [x] Seal deep-copies all four inputs on entry; validation/replay/digests use snapshots only.
  - [x] Adapter deep-copies catalog on entry; binding/schema/GoalPlan use one snapshot.
  - [x] Non-object catalogs fail closed with stable codes.
  - [x] Evidence note updated (fifth hardening, file list, corrected test/blocker wording).
- Test checklist:
  - [x] Scoped v2 JUnit via JUnit Platform launcher: FOUND=53 OK=53 FAIL=0 SKIP=0
        (Conformance 6, EligibilityFilter 7, Idempotency 6, MutationMatrix 11,
        SealedEligibility 11, ProviderEquivalence 12).
  - [x] `javac` of v2 main + test packages: EXIT 0.
  - [x] Resource pins re-verified: schema
        `1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b`,
        fixtures `96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549`,
        cross-repo `cmp` identical.
  - [ ] Native-Windows `gradlew.bat test`, `:runelite-client:test`, Checkstyle/Spotless —
        still outstanding. Gradle `test` on WSL is blocked by the stale
        `PatchLoopIterationResponse.class` output above (unrelated to v2 sources).
- Prior pending record
  `20260905T010000Z--sc-1429--update-note--sealed-eligibility-boundary-closure.md`:
  its unchecked resource-pin item is now verified `[x]` (hashes above); replay
  oldest-first with that correction applied.

## State reconciliation

- Resolve task `task_1788438246897_fzmkmv44` by ID (never create a duplicate).
- Apply the evidence-note update above to `note_sc1429_v2_implementation_evidence`
  (preserve unrelated content; add `offline-sync:offline-20260905-sc1429-snapshot-boundary-001`
  tag on replay; check for that tag first for idempotency).
- Keep task `in_progress`; SC-1429 stays open pending the native-Windows gate.
- No Shortcut transition on this replay (no `[felix-sync:...]` marker to post).

## Shortcut reconciliation

- No Shortcut state change or comment on this replay; SC-1429 remains open.
- Remaining blockers to report when syncing: native-Windows Gradle/JUnit +
  `:runelite-client:test` + Checkstyle/Spotless; full-repo Gradle suite
  (stale-output blocker above); Felix synchronization itself.

## Sync result

Pending.

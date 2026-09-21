---
schema_version: 1
offline_record_id: offline-20260905-sc1429-sealed-boundary-001
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
created_at: 2026-09-05T01:00:00Z
intended_felix_state: in_progress
intended_shortcut_transition: none
---

## Task resolution

- Task: `task_1788438246897_fzmkmv44` (workflow `shortcut_delivery`) under
  `C:\Dev\IdeaProjects\ChromaScape`, tagged `shortcut:sc-1429`.
- Shortcut story: `1429` (SC-1429 stays open; latent contract/integration risk only,
  no production v2 call sites or live dispatch).
- Scope: close the sealed-eligibility adapter-boundary bypass, correct the ordinal
  regression tests, normalize Micro6 formatting, update the evidence note.
- Exclusions: durable provider sessions (SC-1430), orchestration (SC-1431),
  TaskSkill lifecycle (SC-1445), live gate (SC-1449), operator Fishing recording
  (SC-1498); no v1-to-v2 conversion; no gameplay dispatched.

## Note payload

The evidence note `note_sc1429_v2_implementation_evidence` was updated with a
"Fourth hardening (2026-09-05 — sealed-eligibility boundary closure)" section:

1. Seal is immutable: `GameplayV2ValidatedEligibility.seal` deep-copies the
   eligibility result; `result()` returns a fresh deep copy per call.
2. Seal is catalog-bound: canonical catalog/observation/planning/eligibility digests
   captured at seal time; `checkCatalogBinding` recomputes from catalog bytes and
   rejects substitution/stale digests with `NODE_CATALOG_MISMATCH`; sealed adapter
   overload enforces before any other check plus schema-validates catalog and result.
   Overload stays public (replay and sealed adaptation are in different packages);
   the binding is the bypass defense.
3. Seal-time validation: catalog/observation/planning/eligibility each validated with
   `validateWithRules` before replay; catalog `catalogDigest` recomputed from bytes.
4. `confirmLegacyReconciliation` documented as trusted-reconciler-only (SC-1449 owns
   durable one-use consumption).
5. Both `headNodeNameNeedNotBeLiteral` tests re-sign plan+envelope digests after the
   ordinal change so the second assertion exercises the ordinal rule; Micro6
   formatting normalized.

Changed files (Windows paths for Felix identity):

- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\contract\v2\GameplayV2ValidatedEligibility.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\provider\v2\GameplayV2ProviderAdapter.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\contract\v2\GameplayV2EnvelopeRules.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\gameplay\contract\v2\GameplayV2IdempotencyTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\gameplay\contract\v2\GameplayV2SealedEligibilityTest.java` (new)
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-notes\notes\note_sc1429_v2_implementation_evidence.md`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\gameplay\contract\GameplayContractV2EnvelopeRules.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\gameplay\contract\GameplayContractV2IdempotencyTest.java`

## Checklist reconciliation

- Implementation checklist (all verified this session):
  - [x] Sealed eligibility deep-copy + digest binding + seal-time validation.
  - [x] Adapter catalog-binding enforcement + null-seal fail-closed.
  - [x] Legacy reconciliation trusted-reconciler documentation.
  - [x] Ordinal tests re-sign digests; Micro6 formatting normalized.
  - [x] Evidence note updated (fourth hardening, file list, test results,
        verification commands).
- Test checklist:
  - [x] `GameplayV2SealedEligibilityTest` 8/8 via Gradle (`50/50` v2 total:
        Conformance 6, EligibilityFilter 7, Idempotency 6, MutationMatrix 11,
        SealedEligibility 8, ProviderEquivalence 12).
  - [x] Functional harness PASS=8 FAIL=0 (seal-valid, seal-mutation-after,
        seal-result-copy, seal-sub-catalog, seal-adapter-sub, seal-unknown-prop,
        ordinal-pass, ordinal-fail); Micro6 ordinal harness M6-ORDINAL-OK.
  - [x] `compileJava`/`compileTestJava` EXIT 0; `checkstyleMain`/`checkstyleTest`
        BUILD SUCCESSFUL (warnings only, no errors; v2 deltas pre-existing style
        warnings plus one import-wrap warning on the new test).
  - [ ] Native-Windows `gradlew.bat test`, `:runelite-client:test`, Spotless —
        still outstanding (WSL `spotlessCheck` fails on 502 pre-existing files
        with CRLF drift, none under `gameplay/`).
  - [x] Resource pins re-verified: schema
        `1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b`,
        fixtures `96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549`,
        cross-repo `cmp` identical.

## State reconciliation

- Resolve task `task_1788438246897_fzmkmv44` by ID (never create a duplicate).
- Apply the evidence-note update above to `note_sc1429_v2_implementation_evidence`
  (preserve unrelated content; add `offline-sync:offline-20260905-sc1429-sealed-boundary-001`
  tag on replay; check for that tag first for idempotency).
- Keep task `in_progress`; SC-1429 stays open pending the native-Windows gate.
- No Shortcut transition on this replay (no `[felix-sync:...]` marker to post).

## Shortcut reconciliation

- No Shortcut state change or comment on this replay; SC-1429 remains open.
- Remaining blockers to report when syncing: native-Windows Gradle/JUnit +
  `:runelite-client:test` + Checkstyle/Spotless; empty-blob index normalization
  before final commit; Felix synchronization itself.

## Sync result

Pending.

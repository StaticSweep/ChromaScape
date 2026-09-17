---
schema_version: 1
offline_record_id: offline-20260906-sc1429-signed-receipt-001
sync_status: pending
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
created_at: 2026-09-06T15:14:44Z
intended_felix_state: in_progress
intended_shortcut_transition: none
---

## Task resolution

- Task: `task_1788438246897_fzmkmv44` (workflow `shortcut_delivery`) under
  `C:\Dev\IdeaProjects\ChromaScape`, tagged `shortcut:sc-1429`. The task ID does
  not resolve through this session's Felix MCP instance (`felix_tasks get`
  returns "Task not found" under both `C:\Dev\IdeaProjects\ChromaScape` and
  `C:\Dev\IdeaProjects\micro6`); no duplicate task or note was created.
- Shortcut story: `1429` (SC-1429 stays open; no state transition; a progress
  comment with no transition was posted to demonstrate remote sync).
- Scope: close the SC-1429 trust-boundary bypass on reconciliation receipts
  (eighth hardening, 2026-09-06): the seventh-hardening receipt was public and
  caller-forgeable, so legacy promotion now requires an HMAC-signed receipt from
  the SC-1449 trusted reconciler boundary.
- Exclusions: durable provider sessions (SC-1430), orchestration (SC-1431),
  TaskSkill lifecycle (SC-1445), live gate changes (SC-1449), operator Fishing
  recording (SC-1498); no v1-to-v2 conversion; no gameplay dispatched.

## Note payload

The evidence note `note_sc1429_v2_implementation_evidence` was updated with an
"Eighth hardening (2026-09-06 — signed-receipt trust-boundary closure)" section
plus scope corrections:

1. `issueTrusted` mints HMAC-SHA256 (`hmac-sha256:`) receipts carrying signer
   `keyId`, `expiresAt` (`confirmedAt < expiresAt <= confirmedAt + 900s`) and a
   signature over the canonical bytes of the eight unsigned fields; only the
   holder of the `>=32`-byte reconciler secret (SC-1449 boundary) can mint.
   Unsigned `issue` is `@Deprecated` and never authorizes promotion.
2. Trusted `confirmLegacyReconciliation(..., receipt, signingSecret,
   expectedKeyId, now)` verifies signature (constant-time), key ID, expiry,
   request identity, recomputed envelope digest and `CONFIRMED` outcome, then
   advances via compare-and-set. The unsigned overload is `@Deprecated` and
   unconditionally returns `false`. Mirrored on both repositories.
3. New negative test `ordinaryCallerCannotForgeExactConfirmedReceipt` in both
   harnesses: an exact unsigned `CONFIRMED` forgery is rejected; wrong secret,
   wrong key ID and expired receipts are rejected; the unsigned overload rejects
   even a validly signed receipt; the signed receipt advances exactly once.
4. Scope corrections: the SC-1449 `GameplayV2LiveGateService` (ChromaScape) /
   `GameplayEnvelopeV2Gate` (Micro6) production v2 consumers were reviewed
   read-only for sealed-boundary compatibility (no receipt API usage) and left
   unstaged as SC-1449 work; earlier "no production v2 call sites" claims are
   corrected; command record uses Windows paths; the 105-column test import
   lines are rule-ignored Checkstyle imports in new (not pre-existing) files.
5. "Test results" updated: ChromaScape six v2 suites FOUND=59 OK=59 FAIL=0
   SKIP=0; Micro6 idempotency + conformance FOUND=19 OK=19 FAIL=0 SKIP=0;
   v2 resource pins re-verified byte-identical cross-repo; `git diff --check`
   clean on all touched gameplay v2 paths.

Changed files (Windows paths for Felix identity):

- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\contract\v2\GameplayV2ReconciliationReceipt.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\contract\v2\GameplayV2EnvelopeRules.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\gameplay\contract\v2\GameplayV2IdempotencyTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-notes\notes\note_sc1429_v2_implementation_evidence.md`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\gameplay\contract\GameplayContractV2ReconciliationReceipt.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\gameplay\contract\GameplayContractV2EnvelopeRules.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\gameplay\contract\GameplayContractV2IdempotencyTest.java`

## Index normalization

The SC-1429 `A` (intent-to-add, empty-blob `e69de29`) index entries were
normalized with `git add` on exactly the SC-1429 path set in both repositories
(contract/v2 + provider/v2 main and test sources, v2 schema/fixtures, the
evidence note), plus the previously untracked SC-1429 files
(`GameplayV2ReconciliationReceipt.java`,
`GameplayV2SealedEligibilityTest.java`,
`GameplayContractV2ReconciliationReceipt.java`, the evidence note). Verified
with `git ls-files -s` (real blobs, no `e69de29` on SC-1429 paths).
SC-1449 live-gate sources/tests (`GameplayV2LiveGateService`,
`GameplayEnvelopeV2Gate` families) intentionally left unstaged. No commit was
created.

## Checklist reconciliation

- Implementation checklist (all verified this session):
  - [x] Signed receipt (`issueTrusted` + `keyId`/`expiresAt`/`signature`) on both repos.
  - [x] Trusted confirm overload verifies signature/key ID/expiry/identity/digest/outcome.
  - [x] Unsigned `issue` + unsigned confirm overloads deprecated and fail closed.
  - [x] Exact-forgery negative tests green on both repos.
  - [x] Live-gate consumer scope reviewed and documented; left unstaged.
  - [x] Evidence note corrected (live-gate scope, Windows paths, import claim).
  - [x] Index normalized; commit-content verification via `git ls-files -s`.
- Test checklist:
  - [x] Scoped `javac` clean (ChromaScape v2 main+test; Micro6 v2 contract main+test).
  - [x] ChromaScape six v2 suites via launcher: FOUND=59 OK=59 FAIL=0 SKIP=0.
  - [x] Micro6 idempotency + conformance via launcher: FOUND=19 OK=19 FAIL=0 SKIP=0.
  - [x] Resource pins re-verified: v2 schema
        `1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b`,
        v2 fixtures `96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549`,
        cross-repo `cmp` identical.
  - [x] `git diff --check` clean on all touched gameplay v2 paths (both repos).
  - [ ] Native-Windows `gradlew.bat test`, `:client:test`, Checkstyle/Spotless —
        still outstanding (no Windows runner in this session).
- Prior pending records
  `20260905T010000Z--sc-1429--update-note--sealed-eligibility-boundary-closure.md`,
  `20260905T103046Z--sc-1429--update-note--snapshot-at-boundary-closure.md` and
  `20260905T170305Z--sc-1429--update-note--provider-output-snapshot-closure.md`:
  unchanged; replay oldest-first with this record last.

## State reconciliation

- Resolve task `task_1788438246897_fzmkmv44` by ID (never create a duplicate).
- Apply the evidence-note update above to `note_sc1429_v2_implementation_evidence`
  (preserve unrelated content; add `offline-sync:offline-20260906-sc1429-signed-receipt-001`
  tag on replay; check for that tag first for idempotency).
- Keep task `in_progress`; SC-1429 stays open pending the native-Windows gate.
- No Shortcut transition on this replay (progress comment only, no state change).

## Shortcut reconciliation

- Progress comment posted to story 1429 (no state transition; SC-1429 remains open).
- Remaining blockers to report when syncing: native-Windows Gradle/JUnit +
  `:client:test` + Checkstyle/Spotless; final commit itself.

## Sync result

Pending.

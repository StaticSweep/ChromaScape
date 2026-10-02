---
# SC-1429 implementation evidence

Shortcut: [SC-1429](https://app.shortcut.com/leeuwbot/story/1429)  
Felix task: `task_1788438246897_fzmkmv44`  
Felix note: `note_sc1429_v2_implementation_evidence`  
Repositories: `C:\Dev\IdeaProjects\ChromaScape`, `C:\Dev\IdeaProjects\micro6`  
Evidence refreshed: 2026-09-07  
Status: **OPEN — implementation reviewed; final native ChromaScape/style and synchronization gates remain.**

## Current repository state

### ChromaScape

- Branch: `integration/chromascape-consolidated-20260901`
- HEAD: `471262bfeefa0c1d0a91a367227a3fd78586814e`
- The two v2 resource files are already committed at this HEAD.
- The SC-1429 implementation/evidence candidate consists of 17 staged paths listed below.
- The latest evidence-note working bytes supersede the older staged note and must be explicitly
  staged before an authorized commit.
- The repository contains extensive unrelated staged, dirty and untracked work. Preserve it.

### Micro6

- Branch: `sc-572-remediation`
- Current HEAD: `a78f237f81d3ca2c1655dce71d35512749c9a72a`
- Native verification candidate: `0aa382b092dfdd2a6bceff385c19eb8fe30a088d`
- No reviewed SC-1429 contract, test, resource, `.gitattributes` or capture-contract-lock path
  changed between the verified candidate and current HEAD. The native SC-1429 results therefore
  apply to the current SC-1429 bytes.
- Commit `0aa382b092...` is broad: approximately 171 paths mix SC-1429, SC-1449, recorder,
  generated/Felix and unrelated plugin work. Do not rewrite, push or represent it as an isolated
  SC-1429 commit without explicit review.

## ChromaScape SC-1429 candidate

The 17 staged implementation/evidence paths are:

1. `.gitattributes`
2. `docs/builderflow/felix-notes/notes/note_sc1429_v2_implementation_evidence.md`
3. `src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ContractValidator.java`
4. `src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2Contracts.java`
5. `src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2EligibilityFilter.java`
6. `src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2EnvelopeRules.java`
7. `src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ReconciliationReceipt.java`
8. `src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ValidatedEligibility.java`
9. `src/main/java/com/chromascape/web/gameplay/provider/v2/ClaudeGameplayV2ProviderAdapter.java`
10. `src/main/java/com/chromascape/web/gameplay/provider/v2/CodexGameplayV2ProviderAdapter.java`
11. `src/main/java/com/chromascape/web/gameplay/provider/v2/GameplayV2ProviderAdapter.java`
12. `src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ContractConformanceTest.java`
13. `src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2EligibilityFilterTest.java`
14. `src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2IdempotencyTest.java`
15. `src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2MutationMatrixTest.java`
16. `src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2SealedEligibilityTest.java`
17. `src/test/java/com/chromascape/web/gameplay/provider/v2/GameplayV2ProviderEquivalenceTest.java`

These resources are already committed in ChromaScape HEAD:

- `src/main/resources/contracts/gameplay/sc-1429-gameplay-contract-v2.schema.json`
- `src/main/resources/contracts/gameplay/sc-1429-golden-fixtures-v2.json`

The pending Felix replay record is evidence/synchronization material, not production code:

- `docs/builderflow/felix-sync/pending/20260907T203100Z--sc-1429--update-note--sc968-resolution-and-native-micro6-verification.md`

Before final commit, the `.gitattributes` index must contain only the two SC-1429 LF additions.
The unrelated deletion of `src/test/resources/micro6-fixtures/* text eol=lf` must remain outside
the SC-1429 index unless separately authorized.

## Explicit SC-1449 exclusion

The untracked ChromaScape files under these directories are SC-1449 material and are excluded:

- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\orchestration\v2`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\gameplay\orchestration\v2`

The previously reported `GameplayV2GateMigrationIntegrationTest.java:200` compilation problem is
no longer present in the working file (`setSchemas(new String[] {schema})` is present), but no new
native ChromaScape compilation is claimed. Micro6 live-gate files are already mixed into the broad
`0aa382b092...` commit; they are SC-1449 provenance and are not SC-1429 acceptance evidence.

## Implemented trust boundary

Static review confirms the earlier high- and medium-severity findings are addressed:

- Eligibility sealing snapshots catalog, observation, planning input and result at entry.
- Schema and semantic validation run before the sealed object is constructed; closed-schema
  unknown properties are rejected.
- The catalog's declared digest is recomputed and verified.
- Canonical catalog, observation, planning-input and eligibility-result digests are stored.
- `result()` returns a fresh deep copy; caller mutation cannot alter sealed state.
- Provider adaptation rechecks the sealed catalog binding and snapshots provider/catalog inputs.
- Uncatalogued and unreplayed compatibility adapter overloads fail closed.
- GoalPlan catalog allowlists, graph rules, identity digests, node ordering and ordinal rules are
  enforced after replay.
- Legacy ledger access never promotes a request-ID-only record.
- Receipt-less and unsigned reconciliation overloads return `false` without mutation.
- Trusted receipts bind eight canonical fields and use HMAC-SHA-256 with a key of at least 32 bytes.
- Confirmation checks signature in constant time, key ID, expiry/TTL, request identity, recomputed
  envelope digest, `CONFIRMED` outcome and verifier identity before compare-and-set.
- The ordinal regression tests re-sign the changed plan and update the envelope digest before
  asserting the ordinal failure.

The SC-1449 live gate remains responsible for durable, one-use receipt consumption. SC-1429 only
defines and validates the shared contract-level proof.

## Cross-runtime golden vector and resources

Both runtimes independently assert the same canonical receipt bytes and signature:

`hmac-sha256:decd6deb4a9238a065fb4a368bd72bcbfa1280c1b89e1b5275750462d4f1cb53`

The ChromaScape and Micro6 resource mirrors are byte-identical:

| Resource | SHA-256 |
|---|---|
| v2 schema | `1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b` |
| v2 fixtures | `96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549` |

The fixture's `executionEnvelope.schemaDigest` matches the schema bytes. Both repositories contain
narrow LF rules for the two v2 resources.

## Verification evidence

### Micro6 native Windows — verified candidate `0aa382b092...`

Commands were run from `C:\Dev\IdeaProjects\micro6-sc1429-verify`:

- `gradlew.bat :client:verifyCaptureContract` — passed, exit 0,
  2026-09-07T20:14:30+02:00 through 2026-09-07T20:15:26+02:00.
- `gradlew.bat :client:compileJava :client:compileTestJava` — passed, exit 0;
  recorded Gradle duration 9m 19s.
- Focused `GameplayContractV2IdempotencyTest` plus `GameplayContractV2ConformanceTest` —
  20 passed, 0 failed, 0 skipped.
- `GameplayContractResourceEqualityTest` — 2 passed, 0 failed, 0 skipped.
- SC-968 coordinates and revision agree:
  `1.0.0-sc1452-983d8efa4276` / `2902a4b50f9e3ba671e85db6ca60bb7de1abfca6`.

SC-968 is resolved and is not an active SC-1429 blocker.

Micro6 Checkstyle is not closed. The retained XML files named
`sc967CandidateCheckstyleMain.xml` and `sc967CandidateCheckstyleTest.xml` contain no `<error>`
records and do not establish the reported later failure. A retained native log or a candidate versus
baseline comparison must identify exact files, rules, lines, exit code and timestamps.

### ChromaScape native Windows — prior evidence, current gate outstanding

Prior focused evidence recorded 60 passed, 0 failed, 0 skipped. Prior Checkstyle exited 0 with
existing warnings. Prior Spotless and full-suite runs were not green because of unrelated repository
work, and the retained runs do not prove the exact current candidate. The former untracked SC-1449
compile error has been corrected, but the current isolated SC-1429 candidate has not been rerun.

No test that was not run is claimed as passed.

## Acceptance checklist

- [x] Mutable sealed-eligibility bypass closed.
- [x] Catalog substitution and stale catalog digest rejected.
- [x] Schema/semantic validation precedes sealing.
- [x] Signed reconciliation proof enforced; unsigned paths fail closed.
- [x] Atomic legacy promotion and ordinal regression coverage implemented.
- [x] Cross-runtime signature vector pinned.
- [x] Resource mirrors and hashes verified.
- [x] SC-968 resolved.
- [x] Native Micro6 compile, focused 20-test and resource 2-test gates passed.
- [ ] ChromaScape exact-candidate native compile, focused tests and style gates rerun.
- [ ] ChromaScape full suite rerun and failures compared/classified.
- [ ] Micro6 Checkstyle result made auditable and SC-1429 attribution closed.
- [ ] Latest evidence bytes and idempotent outbox record staged.
- [ ] Felix records replayed successfully against the existing task/note.
- [ ] Shortcut final evidence synchronized.
- [ ] Explicit commit/push authorization received.

## Felix and Shortcut synchronization

Reuse only Felix task `task_1788438246897_fzmkmv44` and note
`note_sc1429_v2_implementation_evidence`. Never create duplicates. Replay these records oldest-first:

1. `20260905T010000Z--sc-1429--update-note--sealed-eligibility-boundary-closure.md`
2. `20260905T103046Z--sc-1429--update-note--snapshot-at-boundary-closure.md`
3. `20260905T170305Z--sc-1429--update-note--provider-output-snapshot-closure.md`
4. `20260906T151444Z--sc-1429--update-note--signed-receipt-trust-boundary.md`
5. `20260907T133218Z--sc-1429--update-note--cross-runtime-vector-resource-parity-and-native-proof.md`
6. `20260907T203100Z--sc-1429--update-note--sc968-resolution-and-native-micro6-verification.md`

Each append/update must use its stable offline record ID or explicit marker to avoid duplicate replay.
Shortcut SC-1429 remains open until every mandatory gate is evidenced.

## Remaining work

1. Stage the corrected note and idempotent newest outbox record by explicit path.
2. Remove only the unrelated fixture-rule deletion from the staged `.gitattributes` hunk while
   preserving that working-tree change.
3. On native Windows, construct an isolated ChromaScape candidate from HEAD plus only the SC-1429
   staged paths; do not include untracked SC-1449 or unrelated staged work.
4. Run compile, focused tests, Checkstyle, Spotless and the full suite with timestamps and exit codes.
5. With explicit native-Windows authorization, retain auditable Micro6 Checkstyle diagnostics or a
   candidate-versus-baseline comparison. Do not launch Micro6.
6. Replay Felix oldest-first, synchronize Shortcut evidence, obtain commit authorization, then
   commit only the reviewed candidate.

## Risks and rollback

- Both working trees contain unrelated material; use explicit paths and verify index blobs before
  commit. Never use reset, clean, destructive checkout or broad staging.
- Micro6 `0aa382b092...` has mixed provenance. Review it before push; do not rewrite it automatically.
- If the ChromaScape candidate is rejected before commit, remove only explicitly identified
  SC-1429 paths from the index while leaving working files intact.
- If an authorized SC-1429-only ChromaScape commit is later rejected, revert only that reviewed
  commit. Do not alter unrelated commits or Micro6 history.
- Do not move Felix or Shortcut to Done until the native/style/sync/commit gates are complete.


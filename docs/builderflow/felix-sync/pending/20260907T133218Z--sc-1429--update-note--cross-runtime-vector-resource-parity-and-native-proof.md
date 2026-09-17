---
schema_version: 1
offline_record_id: offline-20260907T133218Z-sc1429-cross-runtime-vector-resource-parity-native-proof
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: "1429"
shortcut_tag: shortcut:sc-1429
felix_task_id: task_1788438246897_fzmkmv44
operation: update_note
target_note_id: note_sc1429_v2_implementation_evidence
title: SC-1429 cross-runtime vector, resource parity, and native proof
note_type: Implementation Evidence
stable_tags:
  - shortcut:sc-1429
  - change:multi-repo
  - offline-sync:offline-20260907T133218Z-sc1429-cross-runtime-vector-resource-parity-native-proof
parent_id: task_1788438246897_fzmkmv44
intended_entity_links:
  - entity_type: shortcut_story
    entity_id: "1429"
intended_felix_task_state: in_progress
intended_felix_spec_state: in_progress
intended_shortcut_transition: null
intended_shortcut_idempotency_marker: null
created_at: 2026-09-07T13:32:18Z
---

## Task resolution

Reuse Felix task task_1788438246897_fzmkmv44 and update the existing note
note_sc1429_v2_implementation_evidence. Never create a task or note. The Shortcut
source is story 1429, tagged shortcut:sc-1429; the authoritative project identity is
C:\Dev\IdeaProjects\ChromaScape. Classification is change:multi-repo.

Scope is the SC-1429 v2 contract, signed-receipt trust boundary, cross-runtime golden
vector, byte-identical LF resources, tests, and evidence. Exclusions are all unrelated
dirty/staged/untracked work and the SC-1449 live-gate consumer files. No runtime or
gameplay action is authorized. SC-1429 must remain open until native gates, Felix
replay, and Shortcut evidence synchronization succeed.

## Note payload

The following is the complete authoritative content intended for the existing Felix
Implementation Evidence note.

~~~~markdown
---
# SC-1429 implementation evidence

Shortcut: [SC-1429](https://app.shortcut.com/leeuwbot/story/1429)
Felix task: task_1788438246897_fzmkmv44
Target Felix note: note_sc1429_v2_implementation_evidence
Canonical projects: C:\Dev\IdeaProjects\ChromaScape,
C:\Dev\IdeaProjects\micro6
Evidence refreshed: 2026-09-07
Status: **OPEN**. Closure is blocked by the native verification and synchronization gates
listed under [Remaining work](#remaining-work).

## Changed files

### ChromaScape staged SC-1429 set (19 paths)

These are the exact SC-1429 paths in the ChromaScape index. Forty-seven other paths are
already staged by the user and were preserved; they are not included below.

- .gitattributes
- docs/builderflow/felix-notes/notes/note_sc1429_v2_implementation_evidence.md
- src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ContractValidator.java
- src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2Contracts.java
- src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2EligibilityFilter.java
- src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2EnvelopeRules.java
- src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ReconciliationReceipt.java
- src/main/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ValidatedEligibility.java
- src/main/java/com/chromascape/web/gameplay/provider/v2/ClaudeGameplayV2ProviderAdapter.java
- src/main/java/com/chromascape/web/gameplay/provider/v2/CodexGameplayV2ProviderAdapter.java
- src/main/java/com/chromascape/web/gameplay/provider/v2/GameplayV2ProviderAdapter.java
- src/main/resources/contracts/gameplay/sc-1429-gameplay-contract-v2.schema.json
- src/main/resources/contracts/gameplay/sc-1429-golden-fixtures-v2.json
- src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2ContractConformanceTest.java
- src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2EligibilityFilterTest.java
- src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2IdempotencyTest.java
- src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2MutationMatrixTest.java
- src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2SealedEligibilityTest.java
- src/test/java/com/chromascape/web/gameplay/provider/v2/GameplayV2ProviderEquivalenceTest.java

ChromaScape branch and HEAD are
integration/chromascape-consolidated-20260901 /
7b522e6daa462669c706c64a32aa014c8ae8e33f. None of the 19 SC-1429 paths is committed
at that HEAD; they are the staged candidate. The working-tree bytes match the index
bytes for every one of these paths after the final explicit stage.

### Micro6 staged SC-1429 delta (2 paths)

- .gitattributes
- runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2IdempotencyTest.java

Micro6 branch and HEAD are sc-572-remediation /
dd8a9ac60b02872de542a06fb08e8f3fe292d6f7. The staged test delta adds the
cross-runtime golden vector; the LF policy is narrow and intentional. No Micro6
implementation rewrite was made.

### Micro6 committed SC-1429 set in dd8a9ac60b (12 paths)

The following SC-1429 paths are already present in the Micro6 HEAD commit:

- runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2Canonical.java
- runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2Eligibility.java
- runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2EnvelopeRules.java
- runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2ReconciliationReceipt.java
- runelite-client/src/main/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2Validator.java
- runelite-client/src/main/resources/gameplay-contract/sc-1429-gameplay-contract-v1.schema.json
- runelite-client/src/main/resources/gameplay-contract/sc-1429-gameplay-contract-v2.schema.json
- runelite-client/src/main/resources/gameplay-contract/sc-1429-golden-fixtures-v1.json
- runelite-client/src/main/resources/gameplay-contract/sc-1429-golden-fixtures-v2.json
- runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/GameplayContractResourceEqualityTest.java
- runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2ConformanceTest.java
- runelite-client/src/test/java/net/runelite/client/plugins/microbot/accountbuilder/integration/builder/gameplay/contract/GameplayContractV2IdempotencyTest.java

That commit is mixed: it also contains the unrelated
ChromaScapeWebSocketClient.java and Micro6BuilderExecutorServer.java, and the
SC-1449-owned FileGameplayV2ReservationStore.java and
GameplayEnvelopeGateTest.java. History was not rewritten.

### Inventory and preservation

The relevant existence inventory is:

| Relevant group | HEAD | Index | Working tree |
|---|---:|---:|---:|
| ChromaScape staged v2 implementation/tests/resources | no | yes | yes |
| ChromaScape evidence note | no | yes | yes |
| ChromaScape .gitattributes | yes | yes | yes |
| Micro6 committed v2 implementation/resources/tests | yes | yes | yes |
| Micro6 staged golden-vector test | yes | yes | yes |
| Micro6 .gitattributes | yes | yes | yes |
| Both v2 resource mirrors | Chroma: no; Micro6: yes | yes | yes |
| ChromaScape SC-1449 live-gate files | no | no | untracked |
| Micro6 GameplayEnvelopeV2Gate.java and test | no | no | untracked |

Both reconciliation receipt classes, both envelope-rule classes, and both idempotency
tests were compared at HEAD/index/working-tree level. The index and working-tree
blobs match for all staged candidate paths; no e69de29 intent-to-add placeholder
remains. The pre-existing ChromaScape
ReplayRecordingActionIngestionServiceTest.java, the Micro6 gameplay reservation
working changes, and all other unrelated dirty/staged/untracked paths were preserved
and are not claimed as this candidate.

## SC-1449 boundary

The following paths are explicitly excluded from the SC-1429 staged set and were not
edited or staged:

- C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\gameplay\orchestration\v2\GameplayV2LiveGateService.java
- C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\gameplay\orchestration\v2\GameplayV2LiveGateServiceTest.java
- C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\gameplay\GameplayEnvelopeV2Gate.java
- C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\gameplay\GameplayEnvelopeV2GateTest.java

They are current working-tree SC-1449 material and remain outside the candidate.
The separately named GameplayEnvelopeGateTest.java was already mixed into
dd8a9ac60b; that provenance is reported, not silently rewritten. No SC-1449
verification is used as proof for SC-1429, and no gameplay runtime was launched.

## Contract and trust-boundary design

The signed receipt boundary is implemented symmetrically in both repositories:

- issue remains public only as a deprecated, unsigned compatibility factory.
  Unsigned receipts never authorize promotion.
- Receipt-less and unsigned confirmation overloads are deprecated/fail closed and
  return false without changing the ledger.
- Only issueTrusted can create an authorizing receipt. It signs canonical JSON
  for the eight unsigned fields with HMAC-SHA-256.
- Verification requires a secret of at least 32 bytes, the expected key ID, a
  constant-time signature comparison, a non-expired expiresAt, an expiry no more
  than 900 seconds after confirmedAt, the request ID, idempotency key, recomputed
  envelope digest, CONFIRMED outcome, and nonblank verifier identity. The
  confirmation-time future-skew rule is also enforced.
- The ledger advances only through compare-and-set. A receipt cannot advance the same
  legacy record twice.
- toString() does not expose the signing secret or signature.
- No public unsigned, digest-hint, legacy, or caller-supplied compatibility path
  bypasses signature verification.

The sealed-eligibility boundary was also statically re-reviewed and remains covered by
tests: defensive snapshots, a defensive result() accessor, schema validation before
sealing, deterministic replay, catalog digest recomputation, stale/substituted catalog
rejection, provider-output snapshotting, and ordinal tests that reach the ordinal rule.

## Cross-runtime golden receipt vector

Both idempotency tests contain
goldenReceiptSignatureVectorIsStableAcrossRuntimes. Neither repository reads the
other repository's output. Both independently assert the pinned canonical JSON,
computeSignature, issueTrusted, and verifySignature.

| Field | Pinned value |
|---|---|
| idempotencyKey | sc1429-golden-key-01 |
| requestId | sc1429-golden-req-01 |
| envelopeDigest | sha256:aa5edbf178c2c63008346fe0e0091c0951e695d05f9a93f6c269ddbd47b60323 |
| externalOutcome | CONFIRMED |
| verifierIdentity | golden-"verifier"\\test (quote and backslash escaping) |
| confirmedAt | 2026-09-03T12:04:00Z |
| keyId | sc-1429-golden-key-01 |
| expiresAt | 2026-09-03T12:14:00Z |
| signing secret | sc1429-golden-signing-secret-0123456789abcdef |

Pinned canonical JSON:

text
{"confirmedAt":"2026-09-03T12:04:00Z","envelopeDigest":"sha256:aa5edbf178c2c63008346fe0e0091c0951e695d05f9a93f6c269ddbd47b60323","expiresAt":"2026-09-03T12:14:00Z","externalOutcome":"CONFIRMED","idempotencyKey":"sc1429-golden-key-01","keyId":"sc-1429-golden-key-01","requestId":"sc1429-golden-req-01","verifierIdentity":"golden-\"verifier\"\\\\test"}


Pinned signature:

text
hmac-sha256:decd6deb4a9238a065fb4a368bd72bcbfa1280c1b89e1b5275750462d4f1cb53


The standalone Jackson-versus-Gson canonicalization/HMAC check passed with identical
canonical bytes and the pinned signature. The ChromaScape focused native run reported
the vector-containing 60-test suite green. The Micro6 JUnit vector was not allowed to
be claimed as passed because its native Gradle invocation stopped at the pre-existing
SC-968 POM validation error.

## Resource parity and line endings

The pre-copy comparison found semantic JSON equality; the discrepancy was line endings
only. Existing canonical LF content was preserved, all four files are now LF, and no
hash was changed to accept CRLF bytes.

The narrow policies are:

ChromaScape .gitattributes:

gitattributes
src/main/resources/contracts/gameplay/sc-1429-gameplay-contract-v2.schema.json text eol=lf
src/main/resources/contracts/gameplay/sc-1429-golden-fixtures-v2.json text eol=lf


Micro6 .gitattributes:

gitattributes
runelite-client/src/main/resources/gameplay-contract/sc-1429-gameplay-contract-v2.schema.json text eol=lf
runelite-client/src/main/resources/gameplay-contract/sc-1429-golden-fixtures-v2.json text eol=lf


Raw SHA-256, refreshed with native Windows certutil at 2026-09-07T13:32Z:

| Resource | SHA-256 |
|---|---|
| ChromaScape schema | 1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b |
| ChromaScape fixtures | 96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549 |
| Micro6 schema | 1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b |
| Micro6 fixtures | 96813afb0a3af5dcb79cf41d32d86db2bcf0e921d8c94968a887492253d7f549 |

executionEnvelope.schemaDigest in the final fixture is
sha256:1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b.

Commands and results:

bat
fc.exe /b C:\Dev\IdeaProjects\ChromaScape\src\main\resources\contracts\gameplay\sc-1429-gameplay-contract-v2.schema.json C:\Dev\IdeaProjects\micro6\runelite-client\src\main\resources\gameplay-contract\sc-1429-gameplay-contract-v2.schema.json
REM EXIT=0 — FC: no differences encountered

fc.exe /b C:\Dev\IdeaProjects\ChromaScape\src\main\resources\contracts\gameplay\sc-1429-golden-fixtures-v2.json C:\Dev\IdeaProjects\micro6\runelite-client\src\main\resources\gameplay-contract\sc-1429-golden-fixtures-v2.json
REM EXIT=0 — FC: no differences encountered

certutil -hashfile C:\Dev\IdeaProjects\ChromaScape\src\main\resources\contracts\gameplay\sc-1429-gameplay-contract-v2.schema.json SHA256
certutil -hashfile C:\Dev\IdeaProjects\ChromaScape\src\main\resources\contracts\gameplay\sc-1429-golden-fixtures-v2.json SHA256
certutil -hashfile C:\Dev\IdeaProjects\micro6\runelite-client\src\main\resources\gameplay-contract\sc-1429-gameplay-contract-v2.schema.json SHA256
certutil -hashfile C:\Dev\IdeaProjects\micro6\runelite-client\src\main\resources\gameplay-contract\sc-1429-golden-fixtures-v2.json SHA256
REM all four certutil commands EXIT=0


## Tests

### ChromaScape native Windows record

The commands below are the exact commands recorded for the candidate verification:

bat
cd /d C:\Dev\IdeaProjects\ChromaScape
gradlew.bat compileJava compileTestJava
gradlew.bat test --tests "com.chromascape.web.gameplay.contract.v2.*" --tests "com.chromascape.web.gameplay.provider.v2.*"
gradlew.bat checkstyleMain checkstyleTest
gradlew.bat checkstyleMain checkstyleTest spotlessCheck
gradlew.bat test
git diff --check


Recorded results:

- The focused v2/provider run reported **60 tests, 0 failures, 0 skipped**.
- checkstyleMain checkstyleTest exited 0; existing warnings were reported and no
  SC-1429 source warning was identified.
- The combined style command exited 1 because Spotless reported the pre-existing
  violation in src/main/java/com/chromascape/web/aiplayer/routine/service/IsolatedPatchWorker.java.
- The full test run exited 1 after **2,226 tests**, with **65 failures** and
  **17 skipped**. The failures were classified as pre-existing/unrelated to the
  focused SC-1429 contract suites; the full suite is nevertheless not green.
- The latest compileJava compileTestJava run exited 1 before compiling the candidate
  because the untracked SC-1449
  GameplayV2GateMigrationIntegrationTest.java:200 has
  String cannot be converted to String[]. This is an outside-scope working-tree
  blocker, not an SC-1429 file change.
- git diff --check exited 0.

The retained native Gradle result record does not contain wall-clock start and finish
timestamps. That omission is recorded rather than reconstructed or presented as a
passing evidence field. The focused result therefore cannot by itself close the
exact-current-index native gate while the untracked SC-1449 compile error remains.

### Micro6 native Windows record

Micro6 commands were run from the repository root, not runelite-client:

bat
cd /d C:\Dev\IdeaProjects\micro6
gradlew.bat :client:tasks --all
gradlew.bat :client:compileJava :client:compileTestJava
gradlew.bat :client:test --tests "net.runelite.client.plugins.microbot.accountbuilder.integration.builder.gameplay.contract.GameplayContractV2IdempotencyTest" --tests "net.runelite.client.plugins.microbot.accountbuilder.integration.builder.gameplay.contract.GameplayContractV2ConformanceTest"
gradlew.bat :client:test --tests "net.runelite.client.plugins.microbot.accountbuilder.integration.builder.gameplay.GameplayContractResourceEqualityTest"
gradlew.bat :client:checkstyleMain :client:checkstyleTest
git diff --check


:client:tasks --all exited 0 and confirmed :client:checkstyleMain and
:client:checkstyleTest; no :client:spotlessCheck task exists. Compilation,
the two-suite test command, resource equality, and style commands exited 1 before
execution because of the pre-existing validation failure:

text
SC-968 capture contract POM version mismatch:
1.0.0-sc1452-983d8efa4276 != 1.0.0-sc968-7c846c927520


No Micro6 test count, pass count, skipped count, or style pass is claimed. The final
:client native gate remains outstanding. Its retained result record likewise lacks
wall-clock start and finish timestamps.

The Micro6 staged-candidate diff --cached --check exited 0. The whole working-tree
diff --check and diff HEAD --check exited 2 because unrelated pre-existing/generated
dirty files contain trailing-whitespace findings; no SC-1429 candidate path is
implicated.

### Failure classification

- SC-1449 test compilation error: environmental/dirty-tree blocker outside SC-1429.
- Spotless violation: pre-existing unrelated ChromaScape source violation.
- ChromaScape full-suite failures: pre-existing/unrelated failures; still unresolved.
- Micro6 POM mismatch: pre-existing repository/environment validation blocker.
- Felix transport/task-resolution failure: external synchronization blocker.

No runtime or gameplay action was performed.

## Acceptance results

- [x] Signed receipt trust boundary is fail closed in both implementations.
- [x] Unsigned promotion and receipt-less confirmation fail closed.
- [x] Signed receipt binds secret, key ID, expiry, request identity, idempotency key,
  envelope digest, outcome, and verifier identity.
- [x] Cross-runtime vector source and pinned signature are identical in both tests.
- [x] Four raw resource hashes match the required pins.
- [x] Both raw resource mirrors compare byte-for-byte equal.
- [x] LF checkout policies are present.
- [x] SC-1449 paths are excluded from the current staged set.
- [ ] ChromaScape exact-current-index native compile/full/style gate is green.
- [ ] Micro6 native compile, focused 20-test gate, resource-equality gate, and style gate
  are green.
- [ ] Native command timing is captured for every verification invocation.
- [ ] Felix note/task synchronization is replayed successfully.
- [ ] Shortcut final evidence and completion transition are synchronized.

## Felix and Shortcut synchronization

Felix remains unavailable in this session; the available Felix task/note tools did not
resolve the existing task, so no new task or note was created and no Felix state
transition is claimed. The existing task
task_1788438246897_fzmkmv44 and note
note_sc1429_v2_implementation_evidence remain the only targets.

The pending SC-1429 records must be replayed oldest-first:

1. 20260905T010000Z--sc-1429--update-note--sealed-eligibility-boundary-closure.md
2. 20260905T103046Z--sc-1429--update-note--snapshot-at-boundary-closure.md
3. 20260905T170305Z--sc-1429--update-note--provider-output-snapshot-closure.md
4. 20260906T151444Z--sc-1429--update-note--signed-receipt-trust-boundary.md
5. 20260907T133218Z--sc-1429--update-note--cross-runtime-vector-resource-parity-and-native-proof.md

Each replay must check its idempotency marker first, update the existing note without
overwriting unrelated content, and synchronize Shortcut only after Felix succeeds.
Shortcut SC-1429 remains open in Backlog. No [felix-sync:...:done] marker, Done
transition, or false Felix success is claimed.

## Remaining work

1. Resolve the outside-scope SC-1449 compile error without editing or staging its files,
   then rerun ChromaScape native verification against the exact staged candidate and
   capture start/finish timestamps, exit codes, counts, skips, failures, Checkstyle
   warnings, and Spotless output.
2. Resolve the Micro6 SC-968 POM mismatch, obtain explicit native-Windows Micro6
   authorization if another Gradle run is needed, and run the repository-root
   :client gates. Do not launch Micro6.
3. Replay the five existing SC-1429 Felix records oldest-first when Felix recovers,
   including this ninth-hardening update; then synchronize final Shortcut evidence.
4. Obtain explicit commit authorization. Do not commit or push while these gates remain
   incomplete.

## Risks

- Micro6 implementation provenance is mixed into dd8a9ac60b; history must not be
  rewritten automatically.
- The working trees contain unrelated dirty/staged/untracked changes. Any future
  verification must preserve them and must stage explicit paths only.
- The current native full-suite/style failures mean SC-1429 cannot honestly move to Done.

## Rollback

Before any authorized commit, review git diff --cached --name-status in each
repository and remove only explicitly identified SC-1429 paths from the index if the
candidate is rejected; do not use git reset, git clean, destructive checkout,
or broad staging. If an authorized commit is later rejected, revert only that
authorized commit after review. Do not alter dd8a9ac60b automatically.
~~~~

## Checklist reconciliation

- Resource semantic parity: checked. JSON comparison was equal before LF normalization;
  raw fc.exe /b comparisons exit 0 and all four hashes match.
- LF policy: checked. Both .gitattributes files contain only the two relevant
  resource rules.
- Signed-receipt boundary: checked by static review and the source tests; unsigned paths
  fail closed, trusted signatures bind the required fields.
- Golden vector: source and standalone Jackson/Gson canonical/HMAC proof checked;
  native Micro6 test remains unchecked because the Gradle blocker occurs first.
- ChromaScape native 60-test run: recorded as green, but the exact-current-index gate is
  unchecked because the later compile sees the untracked SC-1449 test.
- ChromaScape full/style gates: unchecked for closure; full suite and Spotless are
  non-green.
- Micro6 native compile, focused 20-test, resource equality, and style gates: unchecked.
- SC-1449 exclusion: checked; listed paths are absent from both current indexes.
- Felix replay and Shortcut completion synchronization: unchecked and pending.

## State reconciliation

When Felix recovers, resolve the task by the recorded ID, search for the
offline-sync:offline-20260907T133218Z-sc1429-cross-runtime-vector-resource-parity-native-proof
tag, and update note_sc1429_v2_implementation_evidence idempotently. Replay prior
SC-1429 records before this record, oldest-first. Do not transition Felix or Shortcut
to review/done until every mandatory native gate is green and its evidence is present.
Do not create a task, note, commit, or push as part of replay.

## Shortcut reconciliation

Intended Shortcut state is unchanged: story 1429 remains open in Backlog. No transition
and no completion marker is intended by this record. After Felix accepts the note and
the mandatory gates pass, check for an existing marker before posting the appropriate
state-specific synchronization comment. Until then, no [felix-sync:...:done] marker
may be emitted.

## Sync result

Pending. Felix task resolution, note update, checklist replay, and Shortcut
synchronization have not succeeded in this session. No successful external mutation is
claimed.

---
schema_version: 1
offline_record_id: offline-20260906-sc1449-remediation-chromascape-001
sync_status: pending
project: C:\Dev\IdeaProjects\ChromaScape
shortcut_story_id: 1449
shortcut_tag: shortcut:sc-1449
felix_task_id: null
operation: reconcile_task
target_note_id: null
title: SC-1449 ChromaScape v2 boundary remediation — frozen validated bytes, durable gate store, atomic start claim, full receipt verification
note_type: documentation
stable_tags:
  - shortcut:sc-1449
  - shortcut:sc-1429
  - implementation-evidence
  - verification-evidence
  - change:multi-repo
  - change:api
  - change:data
parent_id: null
created_at: 2026-09-06T00:00:00Z
intended_felix_state: in_progress
intended_shortcut_transition: started
intended_shortcut_marker: "[felix-sync:<felix-task-id>:started]"
---

## Task resolution

If no Felix task exists for SC-1449 under `C:\Dev\IdeaProjects\ChromaScape`
(search by tag `shortcut:sc-1449` first; also search `shortcut:sc-1429` — one
task per story, never a duplicate), create it with:

- workflow: `shortcut_delivery`
- project identity: `C:\Dev\IdeaProjects\ChromaScape`
- entity link: `shortcut_story` / `1449`, plus tag `shortcut:sc-1449`
- classification tag: `change:multi-repo` (ChromaScape + micro6; additionally
  `change:api` wire contract and `change:data` durable store)
- scope: remediate the eight review findings on the SC-1429/v2 live boundary:
  frozen validated bundle bytes end-to-end, cross-repository wire-contract
  alignment on `providerDecision`, durable JPA gate storage, atomic
  reservation/start CAS, STARTING lifecycle, full receipt verification,
  reconciliation restore to startable states, restart recovery from durable rows.
- exclusions: enabling either v2 flag (human gate), shadow reserve, the bounded
  SC-1498 operator Fishing run, v1 gameplay routes, SC-810/SC-1431/SC-1430 work.

## Note payload

### Implementation Evidence — SC-1449 ChromaScape remediation (2026-09-06)

## Changed files

- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/GameplayV2DispatchModels.java`
  — `V2Bundle` component renamed `providerDecision`; canonical wire key
  `PROVIDER_DECISION_FIELD`; new `ValidatedBundleSnapshot` record freezing
  canonical bundle bytes + envelope + all identities (defensive copies in
  constructor and `bytes()`); `V2State` gains `STARTING`; transition graph now
  allows `RESERVED|DELIVERY_PENDING -> STARTING`,
  `STARTING -> ACTIVE|FAILED|NEEDS_RECONCILIATION`, and
  `NEEDS_RECONCILIATION -> DELIVERY_PENDING|ACTIVE|...` (restart-safe restore);
  `V2Transport.reserve` now takes `byte[] bundleBytes` so the transport never
  re-serializes; unused `V2Snapshot`/`snapshot()` removed.
- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/Micro6GameplayV2Gateway.java`
  — reserve posts the exact validated bytes; every POST response is enriched with
  truthful `_httpSuccess`/`_httpStatus`; start/stop/status/catalog/observe unchanged.
- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/GameplayV2LiveGateService.java`
  — full rewrite: validate() freezes one `ValidatedBundleSnapshot` from the local
  validation copies (wire key `providerDecision`); all four in-memory maps removed;
  every state read/write goes through `GameplayV2GateStore`; reserve inserts
  RESERVED first (unique request/idempotency/authority/slot constraints), then and
  only then calls Micro6; start claims `STARTING` via store CAS before any network
  call; receipts require `_httpSuccess`, `success`, requestId, envelopeDigest,
  closed status, `deliveryAcknowledged` (reserve/start) and non-empty evidence;
  definitive Micro6 denials (`_httpSuccess=false`, closed denial payload) map the
  row to FAILED with the Micro6 failure code; stop persists stop evidence before
  returning STOPPED; reconcile restores remote RESERVED to DELIVERY_PENDING, remote
  ACTIVE to ACTIVE, terminal remote states to corresponding terminal states (digest
  must match); recoverAfterRestart() now reads recoverable rows from the store.
- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/GameplayV2GateStore.java`
  (new) — durable store contract: `GateRow` (request id, idempotency key,
  fingerprint, envelope digest, authority id, account/client, lease generation,
  fence, state, failure code, acknowledged, canonical request bytes, receipt JSON,
  evidence refs, timestamps), `insertReserved`, `compareAndSet`, `findRecoverable`,
  `GateConflictException`.
- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/GameplayV2GateEntity.java`
  (new) + `src/main/resources/db/migration/V40__sc1449_live_gameplay_gate.sql`
  (new) — table `gameplay_v2_gate_request` with unique idempotency key, unique
  authority id, unique (account_id, client_id, lease_generation, fence) slot,
  bytea request bytes, optimistic entity_version.
- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/GameplayV2GateRepository.java`
  (new) — Spring Data repository with conditional compare-and-set update.
- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/JpaGameplayV2GateStore.java`
  (new) — production store bound in Spring wiring; no network inside transactions.
- `src/main/java/com/chromascape/web/gameplay/orchestration/v2/InMemoryGameplayV2GateStore.java`
  (new) — unit-test store enforcing the same uniqueness families.
- `src/test/java/com/chromascape/web/gameplay/orchestration/v2/GameplayV2LiveGateServiceTest.java`
  — updated to receipts + new tests: transmitted-bytes wire-contract test,
  post-validation-mutation freeze test, shared-store restart test, remote-RESERVED
  restore test, concurrent start single-dispatch test, definitive rejection test,
  malformed start/stop receipt tests, stop evidence persistence test.
- `src/test/java/com/chromascape/web/gameplay/orchestration/v2/Micro6GameplayV2GatewayTest.java`
  — byte-exact reserve transmission + truthful `_httpStatus` assertions.
- `src/test/java/com/chromascape/web/gameplay/contract/v2/GameplayV2IdempotencyTest.java`,
  `GameplayV2MutationMatrixTest.java` — legacy reconciliation now proven through
  `issueTrusted` + signed 8-arg `confirmLegacyReconciliation` (unsigned 5-arg
  overload fails closed by design).

## Tests

Verified green in this session (native Windows, Gradle):

- `./gradlew test --tests "com.chromascape.web.gameplay.orchestration.v2.*" --tests
  "com.chromascape.web.gameplay.contract.v2.*" --tests
  "com.chromascape.web.gameplay.provider.v2.*"` — BUILD SUCCESSFUL (84 tests in
  first full v2 pass; failures fixed en route).
- `./gradlew spotlessApply spotlessCheck checkstyleMain checkstyleTest` — no v2
  violations; pre-existing warnings only in unrelated replay/debug files.
- Full `./gradlew test` — result recorded in `## Sync result` when completed.

## Remaining work

- Full ChromaScape native gate result confirmation (running at record time).
- JPA-backed restart test against real Postgres (covered logically by
  shared-store test; Postgres integration test still recommended).
- Shadow reserve + human-gate evidence and the bounded SC-1498 Fishing run
  (SC-1449 must stay open until those pass; SC-1498 stays open).

## Checklist reconciliation

- [x] Frozen validated bundle: ValidatedBundleSnapshot + byte seam + freeze tests
      (evidence: GameplayV2LiveGateServiceTest.mutationAfterValidationDoesNotChangeTransmittedBytes,
      transmittedBytesCarryCompleteBundleWithCanonicalProviderDecisionKey).
- [x] Wire contract aligned on `providerDecision` (evidence: same two tests).
- [x] Durable storage (V40 + JPA store replaces 4 in-memory maps; evidence:
      restartWithSharedDurableStoreKeepsReservationAndReconciles).
- [x] Atomic reservation/start (unique-constraint insert; STARTING CAS; evidence:
      concurrentIdenticalReserveAdmitsExactlyOneTransportCall,
      concurrentStartsDispatchExactlyOneMicro6Start).
- [x] Full receipt validation + stop evidence persistence (evidence:
      malformedStartReceiptNeedsReconciliationWithoutRedispatch,
      verifiedStopPersistsEvidenceBeforeReportingStopped, definitive
      rejection test).
- [x] Reconciliation restores DELIVERY_PENDING/ACTIVE/terminal (evidence:
      reconciliationOfRemoteReservedRestoresStartableDeliveryPending).

## State reconciliation

Intended Felix transitions, in order: `planning -> spec_ready` (plan = this
record), then `in_progress`. Verification Evidence note before `in_review`;
`done` only after the human gate, shadow reserve and SC-1498 bounded run pass.

## Shortcut reconciliation

- Intended workflow state after replay: `started` (id 500000008 per
  `.felix/shortcut-sync.json`).
- Marker: `[felix-sync:<felix-task-id>:started]` — search comments first; post at
  most once; only after the Felix `in_progress` transition actually succeeds.
- SC-1449 must NOT be closed; SC-1429 evidence can reference this record.

## Sync result

Pending — replay in a session with Felix MCP available. Record actual
`felix_task_id`, note IDs, transition timestamps and Shortcut comment result here.

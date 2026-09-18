# SC-1431.2 Implementation Evidence — lifecycle, budgets, fencing, failure normalization

Branch: `codex/sc-1431-lifecycle-hardening`.
Base: `origin/integration/chromascape-consolidated-20260901` at `97482af3` (the branch was rebased onto this head
before remediation and is now ahead of it with 0 commits behind). The four SC-1430 commits (`087ae9ca`,
`b1297922`, `66e29fff`, `4f7e038f`) sit directly above the base, and every SC-1431 commit sits above them; the
stacks are never interleaved.

## Changed files

### Remediation pass (SC-1431.3 hardening)

1. **One shared request-construction and fingerprint contract**
   - `src/main/java/.../accountbuilder/AccountBuilderOrchestrationRequestFactory.java` (new) — the sole
     production request builder; `fromBootstrap(...)` maps a bootstrap proposal into `OrchestrationRequest` and
     stamps the full immutable fingerprint via `AccountBuilderOrchestrationValidator.immutableFingerprint`
     (contract version, orchestration id, request id, idempotency key, mode, source type, decision, envelope,
     bindings, authority, observation, deadline; the fingerprint field itself excluded). The bootstrap partial
     three-field fingerprint method was deleted; `BootstrapGameplayOrchestrator` requires the factory whenever the
     durable service is wired, and `GameplayBootstrapConfiguration` passes it.
   - `src/test/java/.../orchestration/BootstrapGameplaySc1431IntegrationTest.java` (new) — production-wiring
     proof: a real validator + service + store accept the bootstrap fingerprint, the durable claim exists, replay
     returns the existing aggregate, a changed immutable field yields `IDEMPOTENCY_CONFLICT`, and fingerprint
     validation failures never touch the gateway.
2. **Atomic claim and budget**
   - `AccountBuilderOrchestrationStore.claim` now takes the validated `OrchestrationBudget` and persists the
     root, lifecycle snapshot, request snapshot, bindings, correlation, budget row and initial event in one
     transaction; `claimBudget` was removed. The service parses the frozen budget before claiming, so an invalid
     budget produces zero rows; a replay without a durable budget is an internal integrity failure, never a
     silent `CREATED` aggregate. Replay compares only the immutable budget limits (started/expires are claim-time
     artifacts).
   - Rollback/replay/concurrency/restart coverage in `AccountBuilderOrchestrationRollbackTest` (budget-insert
     fault injection leaves no part of the aggregate), `AccountBuilderOrchestrationRestartTest`,
     `AccountBuilderOrchestrationPostgresConcurrencyTest` and `AccountBuilderOrchestrationBudgetTest`.
3. **One durable bootstrap lifecycle and kill-switch intent**
   - `BootstrapGameplayOrchestrator` — when the durable service is configured, the in-memory request and
     idempotency maps, the independent terminal receipts and the bootstrap evidence store are bypassed entirely;
     dispatch and stop are derived from durable state (stop and replay work after a restart with no in-memory
     entry; stable codes `REQUEST_NOT_ACTIVE` / `REQUEST_ALREADY_TERMINAL` are preserved).
   - `killSwitch` now flows through `AccountBuilderOrchestrationService.stop(orchestrationId, reason,
     killSwitch)`, `AccountBuilderOrchestrationGateway.stop(correlation, lease, reason, killSwitch)`, both
     gateway adapters, the Micro6 transport call and the durable `GatewayReceipt.killSwitch` field (included in
     receipt evidence). The service authorizes kill-switch use against the persisted approved scope
     (`OPERATOR_UNAUTHORIZED` before any gateway access); `AccountBuilderOrchestrationController.ControlRequest`
     carries the flag over HTTP.
4. **Server-bound Micro6 evidence**
   - `Micro6AccountBuilderOrchestrationGateway` — a successful reservation must carry reservation id, lease
     generation, fence, fence-token digest and lease expiry; missing/malformed values map to
     `INVALID_GATEWAY_RECEIPT`, generation/fence mismatch to `FENCE_MISMATCH`, already-expired to
     `LEASE_EXPIRED`. No lease field is synthesized from the request or a response digest.
   - Capability acquisition is bound to the validated request (`capabilities(request)`): both the catalog and the
     client observation route are called, both HTTP stamps are validated, and only server-issued catalog and
     capability attestation digests are used (transport metadata such as `_httpStatus` never enters a contract
     digest). Wrong-client, revision-disagreeing and digest-less observations leave readiness false.
   - Authority failure semantics are distinct: `AUTHORITY_CONSUMED -> AUTHORITY_ALREADY_USED`,
     `AUTHORITY_EXPIRED -> AUTHORITY_EXPIRED`, `AUTHORITY_MISSING`/`AUTHORITY_INVALID -> AUTHORITY_INVALID`,
     `AUTHORITY_FORBIDDEN -> BOUNDARY_VIOLATION`; unknown remote codes fail closed instead of becoming `NONE`.
5. **Provider provenance artifacts**
   - `RedactedTranscriptStore.verify(reference)` (new) — canonical `sha256:` reference only, `<digest>.json`
     below the configured root, real-path check (symlink and `..` escape rejected), regular-file check,
     configured size bound (`ProviderRuntimeProperties.maxTranscriptBytes`), exact-byte SHA-256 recomputation
     and `MessageDigest.isEqual` comparison; failures are stable codes (`INVALID_EVIDENCE_REFERENCE`,
     `TRANSCRIPT_ARTIFACT_MISSING`, `TRANSCRIPT_ARTIFACT_UNREADABLE`, `TRANSCRIPT_ARTIFACT_TOO_LARGE`,
     `TRANSCRIPT_DIGEST_MISMATCH`) and never expose transcript content.
   - `GameplayProviderRuntime.findValidatedDecision` calls `verify(...)` before constructing the handoff;
     `transcriptSha256` is the verified content hash of the stored bytes, not a digest-shaped assumption.
   - `PersistedDecision.bindable()` requires a terminal `DECIDED` turn with successful normalization, every
     contract-mandated digest in canonical form, at least one task-skill digest and a verified transcript
     reference. `SnapshotBindings` now carries the observation and prompt digests, validated unconditionally for
     provider-bound decisions; `ProviderDecisionOrchestrationSource` compares every provenance digest
     unconditionally and rejects records missing any required value.
6. **Budget contract enforced in PostgreSQL**
   - `src/main/resources/db/migration/V48__sc1431_budget_integrity.sql` (new; `V47` was retained and `V48` was
     the next free number after the rebase) — non-null `started_at` (backfilled from the orchestration root
     first), expiry ordering, immutable limits and timestamps plus monotonic counters and an advancing
     optimistic-lock version via the `ab_budget_guard` trigger, and the new snapshot-binding provenance columns.
   - `Sc1431BudgetIntegritySchemaTest` (new) — stages the full migration chain on an isolated schema of the
     local PostgreSQL 16 (port 5433), proves the V47→V48 upgrade and asserts bounds, ordering, immutability,
     counter monotonicity, version advancement and parent-restricted deletion directly against the database.

### Lifecycle and budget enforcement (SC-1431.2, retained)
- `src/main/resources/db/migration/V47__sc1431_lifecycle_budget_counters.sql` — durable budget table.
- `AccountBuilderBudgetPolicy.OrchestrationBudget` — frozen SC-1429 limits (1..20 actions, 1..3 attempts,
  1000..1800000 ms); `expiresAt` = earlier of envelope deadline and `startedAt + maxElapsedMs`.
- `AccountBuilderOrchestrationService` — attempt consumed before any gateway access at reserve; action consumed
  before dispatch; a budget denial at the dispatch boundary is a proven terminal `FAILED`, never reconciliation.

### Provider decision binding (SC-1430 handoff)
- `ProviderDecisionHandoff` / `GameplayProviderRuntime` / `ProviderDecisionOrchestrationSource` — full durable
  provenance with fail-closed binding; see remediation item 5 for the strengthened contract.

### SC-1449 gateway adapter
- `Micro6AccountBuilderOrchestrationGateway` — closed v2 vocabulary (`reserve`, `start`, `status`, `stop`,
  `reconcile`, `reconcileReservation` lookup-only); every response bound to request id and envelope digest;
  transport classification (`GATEWAY_UNAVAILABLE` before submission, `DELIVERY_UNCERTAIN` after possible
  submission, `INVALID_GATEWAY_RECEIPT` for closed-shape violations). See remediation item 4 for the strict
  lease, capability and failure-mapping contract.

## Tests

All SC-1431 remediation tests (new and updated):
- `BootstrapGameplaySc1431IntegrationTest` — production wiring (real validator, service, store and shared
  factory): full-fingerprint validation reaches exactly one durable claim with one budget row; replay is
  idempotent; changed immutable fields produce `IDEMPOTENCY_CONFLICT`; the legacy partial fingerprint is
  rejected; fingerprint failure never reaches the gateway; terminal state and stop decisions survive a full
  orchestrator restart (durable state alone decides; no in-memory entry required).
- `AccountBuilderOrchestrationRollbackTest` — PostgreSQL fault injection on `ab_orchestration_budget` proves a
  budget-insert failure leaves no root, snapshot, correlation, budget or event row; receipt-phase fault
  injection still rolls back the full transition aggregate.
- `AccountBuilderOrchestrationBudgetTest` — bound boundaries, exhausted/expired rejection before gateway
  access, and the concurrent final-attempt race (exactly one winner).
- `AccountBuilderOrchestrationRestartTest` — Flyway target advanced to the new head (`48`); committed evidence
  survives a full context restart and recovery never redispatches.
- `AccountBuilderOrchestrationServiceTest` — normal stop sends `killSwitch=false`, kill stop sends `true` and
  the durable stop receipt records the requested mode without authority secrets; an out-of-scope kill switch
  is rejected with `OPERATOR_UNAUTHORIZED` before any gateway access; durable claims always carry budgets.
- `Micro6AccountBuilderOrchestrationGatewayTest` — complete realistic reservation fixtures with every
  authoritative lease field; one rejection per missing/malformed/mismatching field (no synthesized lease);
  capability acquisition calls catalog and observation once each with the request's client id, rejects
  wrong-client, revision-disagreeing and digest-less observations, and proves transport metadata never alters
  the contract digest; the stop transport call receives the requested kill-switch mode.
- `Sc1431BudgetIntegritySchemaTest` — direct Flyway/PostgreSQL proof of the V48 budget contract (upgrade from
  V47, nullability, bounds, expiry ordering, immutability trigger, counter monotonicity, version advancement,
  FK restrict) against the isolated schema on port 5433.
- `ProviderDecisionOrchestrationSourceTest` — provenance-incomplete records (missing prompt provenance, empty
  task-skill provenance) fail closed; bindings without the persisted prompt provenance are `STALE_BINDING`.
- `ProviderRuntimeHardeningTest` — byte-level transcript verification against real files: untouched artifact
  verifies with its exact byte length; a one-byte modification after terminality fails
  `TRANSCRIPT_DIGEST_MISMATCH`; deletion, empty/malformed references, path escape, symlink escape and
  oversized artifacts each fail with their stable code; exception messages never contain transcript content.

Fedora verification (all commands run from
`/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc1431-fedora`):
- Java: Corretto/OpenJDK 17.0.20.1 (`JAVA_HOME=/home/ikiddoi/.jdks/corretto-17.0.20.1`); Gradle 8.14.2.
- PostgreSQL: 16.15 (container `chromascape-postgres`, image `postgres:16-alpine`) on port 5433, database
  `chromascape_test` (plus Testcontainers-managed isolated databases for the Spring JPA proofs).
- `sh gradlew compileJava compileTestJava --no-daemon` — green.
- Focused remediation gate: `sh gradlew test -Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/
  contracts/sc967/ed25519-vectors.json --tests 'com.chromascape.web.gameplay.orchestration.BootstrapGameplay*'
  --tests 'com.chromascape.web.gameplay.orchestration.accountbuilder.*'
  --tests 'com.chromascape.web.gameplay.provider.runtime.*' --no-daemon` — **136 tests, 136 passed, 0 failed,
  0 errors, 0 skipped** across 22 JUnit XML suites (includes Testcontainers PostgreSQL concurrency, restart,
  rollback and JPA proofs plus the direct-Flyway schema proof).
- `spotlessCheck` — green after `spotlessApply` restricted to the SC-1431 files; unrelated pre-existing format
  drift in other files was reverted, never committed.
- `checkstyleMain` / `checkstyleTest` — warnings only (severity threshold unchanged from baseline).
- `git diff --check` and `git status --short` — clean at every commit boundary; each commit staged explicit
  paths only.

Safety and scope audit: no gameplay, provider process or raw input capability was launched; no SC-1429 resource
changed; no SC-1449 implementation was widened; no credentials or transcript contents were committed; no
generated build output was staged; default delivery remains disabled; transcript tampering tests used actual
stored files; the production bootstrap wiring was exercised end to end by `BootstrapGameplaySc1431IntegrationTest`.

## Remaining work

- SC-1498 must switch to reading SC-1431 terminal receipts for SC-666 finalization (interface now returns
  durable terminal evidence; the recording-side consumer change belongs to SC-666/SC-1498 scope).
- A dedicated live-delivery HTTP profile pass for the production-path matrix (disabled delivery never accesses
  Micro6, timeout → `NEEDS_RECONCILIATION`, pre-submission connection failure retryable, post-submission
  uncertainty never redispatches) is partially covered by the controller and gateway tests but not yet run as
  one enabled-profile suite.
- Windows acceptance pass: native restart behavior, real Micro6 desktop client operation, human-gated Fishing
  completion, and operator acceptance remain outside Fedora verification — this is the remaining external gate
  before the PR moves to review.
- External completion gates: SC-573 catalog dependency, SC-666 recording lifecycle, SC-1449 server-side
  enforcement verification, and end-to-end duplicate-delivery proof.
- The Shortcut story stays Backlog/blocked; no tracker mutation was performed from this implementation pass,
  and the Felix transition to `in_review` has not been attempted yet (no MCP call was made from Fedora).

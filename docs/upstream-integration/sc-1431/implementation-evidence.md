# SC-1431.2 Implementation Evidence — lifecycle, budgets, fencing, failure normalization

Branch: `codex/sc-1431-lifecycle-hardening`.
Base: `origin/integration/chromascape-consolidated-20260901` at `1f1b6b5da792e6d611b648fc11219d893b5dcd7c`.
Final code head before this evidence commit: `a4a97b646baefff8f7e3533e44be05d2a43bb1f3` (20 commits ahead,
0 behind). Remediation commits: `2cd1ae54` (replay and provider trust boundaries) and `a4a97b64` (Fedora
verification coverage); this evidence record is the next scoped commit and is the branch tip at handoff.

## Changed files

### Review remediation pass (SC-1431.5, Fedora review findings)

Two blocking correctness gaps and two trust-boundary gaps from the SC-1431.4 review were fixed.

1. **Bootstrap nullable prompt provenance** — `V49__sc1431_bootstrap_provider_identity.sql` drops the
   `NOT NULL` constraint and default from `ab_snapshot_binding.prompt_digest`, so a production
   bootstrap claim no longer attempts to insert `null` into a non-null column and abort its
   transaction. The JPA entity marks `promptDigest` nullable. The validator continues to require a
   valid prompt digest for provider sources and requires it to be absent for bootstrap sources.
2. **Provider session/turn in the immutable fingerprint** — `SnapshotBindings` gains
   `providerSessionId` and `providerTurnId`. `ProviderDecisionOrchestrationSource.prepareProduction`
   populates them from the loaded `PersistedDecision`; `bindProvenance` verifies they match the
   loaded decision; the validator requires both for provider sources and forbids them for
   bootstrap. Because the records are part of the immutable fingerprint, two distinct durable
   provider turns with identical decision and provenance content can no longer resolve as the same
   exact replay.
3. **Single non-forgeable provider service boundary** — the controller no longer accepts or
   assembles a provider `OrchestrationRequest`. `AccountBuilderOrchestrationService` exposes one
   command-based entry point, `orchestrateProvider(ProviderOrchestrationCommand)`, which owns
   preparation, exact-replay resolution with the stored capability digest, and the capability
   preflight. `orchestrateInternal` stays private and the old package-private
   `orchestrateProvider(OrchestrationRequest)` was removed.
4. **Prepared-request validation before preflight** — `AccountBuilderOrchestrationValidator.validatePrepared`
   validates every immutable invariant except the capability digest and the final fingerprint and is
   invoked inside `prepareProduction`. Mismatched request id, idempotency key, deadline, expired
   deadline, malformed authority and malformed envelope identity all fail before any Micro6 read.
5. **Evidence correction** — the SC-1431.4 safety audit incorrectly stated that no SC-1429 resource
   changed. The branch does restore the truncated test fixture
   `src/test/resources/contracts/gameplay/sc-1429-golden-fixtures-v1.json` to the canonical bytes
   (final SHA-256 `c55ad78af5b6367803bc7098e0ef0a03e03d8efff6465bd4eae7829aa743c19c`, identical to
   the main resource). That statement is corrected below.
6. **Regression coverage** — `ProviderDecisionOrchestrationIntegrationTest` now drives the
   command-based service boundary and proves zero preflight for mismatched request id, idempotency
   key, deadline, expired deadline, malformed authority and malformed envelope identity, plus
   `IDEMPOTENCY_CONFLICT` with no preflight when only the provider session id or only the turn id
   changes; `AccountBuilderOrchestrationServiceTest` asserts the only `orchestrateProvider` overload
   takes `ProviderOrchestrationCommand`; `AccountBuilderOrchestrationRestartTest` claims a
   `fromBootstrap(...)` request with a null prompt digest through `JpaAccountBuilderOrchestrationStore`
   and proves exact replay after a two-context database restart.

### Remediation pass (SC-1431.4 review findings)

Four review findings were fixed on top of the SC-1431.3 pass; every production change is exercised by a focused
regression test.

1. **Full-identity bootstrap replay** — `BootstrapGameplayOrchestrator.orchestrateThroughSc1431` no longer returns
   an existing aggregate after comparing only `envelopeDigest`. It reads the persisted capability digest through
   `AccountBuilderOrchestrationService.storedCapabilityDigest`, rebuilds the exact candidate with
   `AccountBuilderOrchestrationRequestFactory.fromBootstrapReplay(...)` and compares the complete immutable
   fingerprint. Any changed observation, provider decision, authority, envelope (budgets/deadline) or binding
   yields `IDEMPOTENCY_CONFLICT`; an exact replay performs zero capability probes.
2. **Provider validation before capability preflight** — `ProviderDecisionOrchestrationSource` is split into
   `prepareProduction(command)` (persisted-decision validation and provenance binding, no Micro6) and
   `completeProduction(prepared, preflight)` / `completeProductionReplay(prepared, storedDigest)`.
   `AccountBuilderOrchestrationController.orchestrateProvider` authenticates, prepares, resolves an existing
   aggregate (replay compare, no preflight), and only then performs `capabilityPreflight.probe(envelope)` for a
   new orchestration. Invalid decisions and exact replays make zero gateway calls.
3. **Closed provider-source vocabulary** — new `AccountBuilderOrchestrationSources` classifies the persisted
   source string strictly and asserts `providerLike` with an ASCII case-independent prefix check. The generic
   `AccountBuilderOrchestrationService.orchestrate` rejects every provider-like source before any claim, budget,
   authority or gateway access; the dedicated `orchestrateProvider` entry point accepts only an exact provider
   token. `AccountBuilderOrchestrationValidator` parses through the same classifier, so `provider:CODEX`,
   `Provider:CODEX`, `PROVIDER:codex`, `PROVIDER:`, unknown providers, whitespace padding and arbitrary strings
   all fail closed with `INVALID_CONTRACT`.
4. **Conjunctive HTTP success stamps** — `Micro6AccountBuilderOrchestrationGateway.httpStampOk` now requires
   `httpSuccess && httpStatus >= 200 && httpStatus < 300`. A `false`/`503` body can never contribute to
   `liveDeliveryReady=true`. Unknown remote failure codes now throw `INVALID_GATEWAY_RECEIPT` instead of mapping
   to a terminal `DISPATCH_REJECTED`.
5. **Regression coverage** — `BootstrapGameplaySc1431IntegrationTest` (full-fingerprint replay rejection for
   observation/decision/authority/envelope/deadline, replay survives a simulated gateway outage),
   `ProviderDecisionOrchestrationIntegrationTest` (rewritten on the SC-1429 golden fixture; valid CODEX decision
   reaches one durable claim with no gateway access, generic route rejects the same request, every drift case
   makes zero gateway calls), `AccountBuilderOrchestrationServiceTest` (generic and provider service entry-point
   bypass attempts), `AccountBuilderOrchestrationControllerHttpTest` (case-variant generic-route rejection,
   provider sequencing, replay skips preflight), and `Micro6AccountBuilderOrchestrationGatewayTest`
   (affirmative/contradictory/missing/wrongly-typed/boundary HTTP stamps with complete valid-looking bodies).

### Retained prior passes (SC-1431.3)

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
- `AccountBuilderOrchestrationRestartTest` — Flyway target advanced to the new head (`49`); committed evidence
  survives a full context restart and recovery never redispatches; a `fromBootstrap(...)` claim with a
  null prompt digest is read back and replayed exactly across a two-context restart.
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
  FK restrict) plus the V49 bootstrap/provider provenance columns (nullable `prompt_digest` and nullable
  `provider_session_id`/`provider_turn_id`) against the isolated schema on port 5433.
- `ProviderDecisionOrchestrationSourceTest` — provenance-incomplete records (missing prompt provenance, empty
  task-skill provenance) fail closed; bindings without the persisted prompt provenance are `STALE_BINDING`.
- `ProviderRuntimeHardeningTest` — byte-level transcript verification against real files: untouched artifact
  verifies with its exact byte length; a one-byte modification after terminality fails
  `TRANSCRIPT_DIGEST_MISMATCH`; deletion, empty/malformed references, path escape, symlink escape and
  oversized artifacts each fail with their stable code; exception messages never contain transcript content.

Fedora verification (worktree
`/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc1431-fedora`; toolchain Corretto/OpenJDK
17.0.20.1 at `/home/ikiddoi/.jdks/corretto-17.0.20.1`, Gradle 8.14.2, Podman API socket
`/run/user/1000/podman/podman.sock`, PostgreSQL 16 Testcontainers databases):

- `compileJava compileTestJava -Psc967VectorsPath=...` — green.
- Focused unit/integration gate (10 suites, excludes Testcontainers): `BootstrapGameplaySc1431IntegrationTest`,
  `AccountBuilderOrchestrationServiceTest`, `AccountBuilderOrchestrationControllerHttpTest`,
  `Micro6AccountBuilderOrchestrationGatewayTest`, `ProviderDecisionOrchestrationIntegrationTest`,
  `ProviderDecisionOrchestrationSourceTest`, `AccountBuilderOrchestrationBudgetTest`,
  `AccountBuilderOrchestrationConcurrencyTest`, `AccountBuilderOrchestrationLifecycleMatrixTest`,
  `ProviderRuntimeHardeningTest` — **123 tests, 123 passed, 0 failed, 0 errors, 0 skipped**.
- PostgreSQL gate (`DOCKER_HOST=unix:///run/user/1000/podman/podman.sock`, `TESTCONTAINERS_RYUK_DISABLED=true`):
  `AccountBuilderOrchestrationPostgresConcurrencyTest`, `AccountBuilderOrchestrationRestartTest`,
  `AccountBuilderOrchestrationRollbackTest`, `JpaAccountBuilderOrchestrationStoreTest`,
  `Sc1431BudgetIntegritySchemaTest` — **24 tests, 24 passed, 0 failed, 0 errors, 0 skipped** (V49 migration
  validation, concurrency, rollback, restart, bootstrap restart replay and JPA store coverage).
- Broader gate `--tests 'com.chromascape.web.gameplay.*'` with the same Podman environment — **39 suites, 308
  tests, 308 passed, 0 failed, 0 errors, 0 skipped**.
- `checkstyleMain checkstyleTest` — BUILD SUCCESSFUL; pre-existing warning baseline (severity unchanged), no
  errors.
- `spotlessCheck` — still fails, but **only** on pre-existing baseline files unrelated to SC-1431:
  `IsolatedPatchWorker.java`, `Micro6TargetRelaunchService.java`, `GameplayV2LiveGateService.java`,
  `Micro6CanonicalHash.java`, `Micro6CatalogV2Contract.java`, `RoutineAssistantGenerationContextIT.java`,
  `Sc810FlywayOnlyMigrationCompletenessTest.java`, `Sc810NarrowContextIsolationTest.java`,
  `Sc810PostgresTestConfiguration.java`, `TaskSourceManifestVerifierTest.java`,
  `Micro6TargetRelaunchServiceTest.java`, `CdiStartupRegressionTest.java`,
  `Micro6ContractFixtureCompatibilityTest.java`, `SimulatorVerifyServiceIntegrationTest.java`. A scoped
  `spotlessApply` followed by reverting those files proved **no SC-1431 file is changed by Spotless**. The
  baseline limitation is recorded rather than reformatted repository-wide.
- `git diff --check` — clean.
- Raw-input audit of `gameplay/orchestration` and `gameplay/provider`: matches are documentation comments plus
  the pre-existing `SafeProviderProcessRunner` (SC-1430 provider launch path), which is outside the SC-1431
  change set.

Safety and scope audit: no gameplay, provider process, Micro6 process or raw input capability was launched; the
SC-1429 test fixture `src/test/resources/contracts/gameplay/sc-1429-golden-fixtures-v1.json` was restored from
its truncated state to the canonical bytes (final SHA-256
`c55ad78af5b6367803bc7098e0ef0a03e03d8efff6465bd4eae7829aa743c19c`, identical to
`src/main/resources/contracts/gameplay/sc-1429-golden-fixtures-v1.json`) and no other SC-1429 resource changed;
no SC-1449 implementation was widened; no credentials or transcript contents were committed; default delivery
remains off; the exact-replay, invalid-provider-decision, provider-bypass and negative-HTTP-stamp regression
paths are each covered by tests that assert zero gateway calls where required.

## Remaining work

- **Deferred external gates (not run, not claimed):** native Windows Gradle verification, the Windows-path
  Micro6 contract test, real Micro6 catalog/observation exchange, real reserve/dispatch/status/reconcile/stop
  integration, and live CODEX provider execution. The Fedora work proves behavior against the closed and
  stubbed gateways plus Testcontainers PostgreSQL only.
- **Spotless baseline limitation:** the fourteen unrelated files listed above still violate Spotless on the
  base branch; they were deliberately not reformatted to avoid repository-wide churn. CI that enforces
  `spotlessCheck` repository-wide will remain red until those pre-existing files are fixed in their own scope.
- SC-1498 must switch to reading SC-1431 terminal receipts for SC-666 finalization (interface now returns
  durable terminal evidence; the recording-side consumer change belongs to SC-666/SC-1498 scope).
- External completion gates: SC-573 catalog dependency, SC-666 recording lifecycle, SC-1449 server-side
  enforcement verification, and end-to-end duplicate-delivery proof.
- PR creation/retargeting after the SC-1430 stack is reconciled, reviewer approval, and any required Felix /
  Shortcut evidence reconciliation. No tracker mutation or Felix transition was performed from Fedora.

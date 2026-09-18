# SC-1431.2 Implementation Evidence — lifecycle, budgets, fencing, failure normalization

Branch: `codex/sc-1431-lifecycle-hardening` (stacked on `origin/integration/chromascape-consolidated-20260901` at `6b17b722`).
The reviewed SC-1430 stack (`2cbaf476`, `85353a38`, `6695e27c`, `f5f45027`) is cherry-picked below the integration head
as prerequisite commits and is kept separate from all SC-1431 commits.

## Changed files

### Lifecycle and budget enforcement
- `src/main/resources/db/migration/V47__sc1431_lifecycle_budget_counters.sql` — durable budget table
  (`V46` was already taken by `V46__sc810_builder_goal_source_binding_state.sql`; head verified before creation).
- `src/main/java/.../accountbuilder/AccountBuilderBudgetPolicy.java` — typed `OrchestrationBudget` record parsed
  from the frozen SC-1429 budgets member; limits bounded 1..20 actions, 1..3 attempts, 1000..1800000 ms;
  `expiresAt` = earlier of envelope deadline and `startedAt + maxElapsedMs`.
- `src/main/java/.../accountbuilder/AccountBuilderOrchestrationEntities.java` — `ab_orchestration_budget` entity.
- `src/main/java/.../accountbuilder/AccountBuilderOrchestrationStore.java` — `claimBudget`, `findBudget`,
  `consumeBudget(action, attempt)` with atomic conditional-capacity semantics.
- `src/main/java/.../accountbuilder/JpaAccountBuilderOrchestrationStore.java` — single conditional UPDATE is the
  atomic capacity check; exhausted vs expired are distinguished (`BUDGET_EXHAUSTED` / `DEADLINE_EXPIRED`); claimed
  limits are immutable.
- `src/main/java/.../accountbuilder/InMemoryAccountBuilderOrchestrationStore.java` — synchronized equivalent.
- `src/main/java/.../accountbuilder/AccountBuilderOrchestrationService.java` — budget claimed with the orchestration;
  attempt consumed before any gateway access at reserve; action consumed before dispatch; a budget denial at the
  dispatch boundary is a proven terminal `FAILED`, never a reconciliation case; status/reconcile/stop consume nothing.

### Provider decision binding (SC-1430 handoff)
- `src/main/java/.../provider/runtime/ProviderDecisionHandoff.java` — `PersistedDecision` now carries session/turn/
  request ids, provider + model, decision/semantic-action digests, prompt/input digest, tool-schema digest, knowledge
  digest, goal-plan digest, task-skill digests, transcript reference + content hash, terminal provider state and the
  normalization result.
- `src/main/java/.../provider/runtime/GameplayProviderRuntime.java` — read-only lookup populates full provenance from
  the durable turn and session rows; the transcript artifact reference doubles as the content hash.
- `src/main/java/.../accountbuilder/ProviderDecisionOrchestrationSource.java` — accepts only a persisted terminal
  `DECIDED` turn; rejects missing turns, non-DECIDED turns, tampered/missing transcripts, provider/model mismatch,
  request-id mismatch and decision/action/plan/skill/tool/knowledge digest mismatch, fail-closed.

### SC-1449 gateway adapter
- `src/main/java/.../accountbuilder/Micro6AccountBuilderOrchestrationGateway.java` — adapts the reviewed
  `Micro6GameplayV2Gateway` v2 transport: `reserve -> /api/v2/gameplay/reserve`, `dispatch -> start`, `status`,
  `stop`, `reconcile` (status lookup), `reconcileReservation` (lookup only, never reserves again). Every response is
  bound to request id and envelope digest (`STALE_BINDING` / `FENCE_MISMATCH` on mismatch); connection failure before
  submission maps to `GATEWAY_UNAVAILABLE`; timeouts map to `DELIVERY_UNCERTAIN`; unparseable or closed-shape-invalid
  receipts map to `INVALID_GATEWAY_RECEIPT`; Micro6 terminal statuses map to SC-1431 terminal states.
- `src/main/java/.../accountbuilder/AccountBuilderOrchestrationGatewayConfiguration.java` — closed gateway is the
  default; the Micro6 adapter is registered only when
  `gameplay.accountbuilder-orchestration.live-delivery-enabled=true`; the single factory method makes two simultaneously
  active gateways structurally impossible (an ambiguous registration would fail startup).
- `src/main/java/.../accountbuilder/ClosedAccountBuilderOrchestrationGateway.java` — no longer self-registered; it is
  the default selected by the configuration.
- `src/main/java/.../accountbuilder/AccountBuilderOrchestrationModels.java` — additive `INVALID_GATEWAY_RECEIPT` failure code.
- `src/main/java/.../accountbuilder/AccountBuilderOrchestrationException.java` — cause-carrying constructor for transport mapping.

### Bootstrap unification
- `src/main/java/.../orchestration/BootstrapGameplayOrchestrator.java` — compatibility facade: dispatch builds exactly
  one durable `OrchestrationRequest` (mode `BOOTSTRAP_RECORDING`) and delegates authority ownership, lifecycle, stop
  and reconciliation to `AccountBuilderOrchestrationService`; results map to the bootstrap receipt contract without a
  second mutable task state; uncertain/terminal states never produce automatic retries.
- `src/main/java/.../orchestration/GameplayBootstrapConfiguration.java` — production wiring always supplies the
  SC-1431 service; the legacy gateway-only constructor remains a compatibility fallback only.

## Tests

New test classes (all in `src/test/java/com/chromascape/web/gameplay/orchestration/accountbuilder/`):
- `AccountBuilderOrchestrationBudgetTest` — exact lower/upper budget boundaries, one step outside every bound,
  overflow-immutable counters, expiry-window math, store-level exhausted/expired rejection, and concurrent final-attempt
  consumption where exactly one racer wins.
- `AccountBuilderOrchestrationLifecycleMatrixTest` — full transition graph: HELD terminality, reservation evidence
  requirement, reconciliation restricted to proven terminal outcomes, strict terminal immutability.
- `Micro6AccountBuilderOrchestrationGatewayTest` — stub v2 transport: bound reserve receipt with exact lease identity,
  stale-binding/fence-mismatch/invalid-receipt classification, connection-failure vs timeout mapping,
  lookup-only reservation reconciliation (zero reserve calls), stop/completed mappings, and readiness staying false
  until the catalog route check passes.
- `ProviderDecisionOrchestrationSourceTest` — Codex positive binding plus rejections for missing turn, nonterminal /
  ABSTAINED / CANCELLED / NEEDS_RECONCILIATION turns, tampered transcript, provider/model mismatch, request-id mismatch,
  decision digest mismatch and goal-plan drift.
- `AccountBuilderOrchestrationControllerHttpTest` — authentication before processing, wrong/missing operator key,
  default-off readiness (`liveDeliveryReady=false`), missing-body safety, stop-reason bound via the service contract,
  terminal reconcile protection, contract-version + stable-code error bodies, and secret redaction.

Updated:
- `AccountBuilderOrchestrationRestartTest` — Flyway target advanced from the SC-1431.1 head (`32`) to the new head (`47`)
  so the restart proof validates the real schema including the budget table.
- `ProviderRuntimeHardeningTest` — handoff construction updated for the strengthened source contract.

Fedora verification run (`JAVA_HOME=$HOME/.jdks/corretto-17.0.20.1`, Podman socket, `RYUK` disabled):
- `compileJava compileTestJava` — green.
- Focused gate: `accountbuilder.*`, `provider.runtime.*`, `BootstrapGameplay*` — 117 tests, all passing
  (includes Testcontainers PostgreSQL concurrency, restart, rollback and Flyway schema proofs against
  the shared Fedora PostgreSQL 16 on port 5433 with freshly created `chromascape_test` and
  `chromascape_sc1430_test` databases).
- `spotlessCheck` — green after `spotlessApply` on the SC-1431 files only; unrelated pre-existing format
  drift in other files was reverted, not committed.
- `checkstyleMain` / `checkstyleTest` — warnings only (severity threshold unchanged from baseline).
- `git diff --check` and `git status` — clean at each commit boundary.

## Remaining work

- PostgreSQL-native budget-race and restart cases inside `AccountBuilderOrchestrationPostgresConcurrencyTest`
  (the InMemory race proof and the full-schema restart proof are in place; the focused Postgres budget-race
  case should mirror the JPA conditional UPDATE under concurrent transactions).
- A `BootstrapGameplaySc1431IntegrationTest` that exercises the operator API end-to-end over the durable store.
- SC-1498 must switch to reading SC-1431 terminal receipts for SC-666 finalization (interface now returns
  durable terminal evidence; the recording-side consumer change belongs to SC-666/SC-1498 scope).
- Windows acceptance pass: native restart behavior, real Micro6 desktop client operation, human-gated Fishing
  completion, and operator acceptance remain outside Fedora verification.
- External completion gates: SC-573 catalog dependency, SC-666 recording lifecycle, SC-1449 server-side
  enforcement verification, and end-to-end duplicate-delivery proof.
- The Shortcut story stays Backlog/blocked; no tracker mutation was performed from this implementation pass.

# SC-1498.1 Implementation Evidence — SC-1431 boundary integration and delivery remediation

Branch: `sc1498/luna-fedora-corrections`.

Base: `codex/sc-1431-lifecycle-hardening` at `5a5d9b9909630028dc585ce78d4d694683ba6ddd` (SC-1431
"close bootstrap and provider identity gaps"). The two prior SC-1498 commits
(`SC-1498: durable bootstrap approval, recording and evidence state`, `SC-1498: strict Micro6 v2
gateway and bounded bootstrap orchestration`) were written against the pre-`5a5d9b99` base
`2bceb4fd`; this pass rebases them onto the SC-1431 command boundary and closes the review findings
recorded in the 2026-09-18 static review and the Fedora audit.

## Integration with SC-1431 (command-based provider boundary)

SC-1431 moved provider preparation, exact-replay resolution and the capability preflight behind
`AccountBuilderOrchestrationService.orchestrateProvider(ProviderOrchestrationCommand)`. SC-1498
previously duplicated that ordering in `BootstrapGameplayOperatorService` and submitted an
`OrchestrationRequest`. The operator now delegates delivery to the single command entry point:

- `AccountBuilderOrchestrationService.orchestrateProvider(ProviderOrchestrationCommand)` is `public`
  so the SC-1498 operator (a different package) can use it; the command still cannot be forged into
  an arbitrary provider request because the service reloads the persisted provider turn.
- New `AccountBuilderOrchestrationService.validateProviderCommand(ProviderOrchestrationCommand)`
  runs the same `prepareProduction` validation (provenance binding, `validatePrepared`) without any
  claim, authority consumption, Micro6 probe or gateway access.
- `BootstrapGameplayOperatorService.dispatch` builds the command, calls `validateProviderCommand`
  **before** consuming the one-use bind and **before** creating the durable recording projection,
  then starts the recording and calls `orchestrateProvider`. A stale or malformed command therefore
  leaves neither a bound-but-unused approval nor an orphan `STARTED` recording. The dispatch test
  asserts this order with a Mockito `InOrder`.

## Migration renumbering (V49 → V50 → V89)

SC-1431 adds `V49__sc1431_bootstrap_provider_identity.sql`. Because SC-1431 lands first, the SC-1498
migration was renamed `V49__sc1498_bootstrap_recording.sql` →
`V50__sc1498_bootstrap_recording.sql`. Rebasing onto `main` (migration head `V88`) renamed it
again to `V89__sc1498_bootstrap_recording.sql`. Migration-head assertions on `main` are advanced
to `89`
(`IsolatedPostgresTestDatabase`, `AccountBuilderOrchestrationRestartTest`,
`Sc1431BudgetIntegritySchemaTest`, `Sc1498FlywaySchemaTest` javadoc/method name).

## Micro6 v2 gateway correctness

`Micro6V2AccountBuilderOrchestrationGateway`:

1. **G1 — capability task/schema attestation.** `capabilities(request)` previously returned a single
   hard-coded `FISHING_TRAINING` task whose `schemaDigest` was the catalog hash. The validator
   (`AccountBuilderOrchestrationValidator.validateCapabilities`) looks up `envelope.taskKey()` and
   compares the entry's `schemaDigest` to the envelope's pinned `schemaDigest`, which the Micro6 gate
   fixes to `GameplayContractV2EnvelopeRules.canonicalSchemaDigest()`. The gateway now builds the
   entry for the requested task key with `GameplayV2EnvelopeRules.canonicalSchemaDigest()`. The two
   schema resources are byte-identical (SHA-256 `1d032dc935e36af0b10ad0269330c7e3df65a26b9212c81caae5bc4afd0abf0b`
   in both repositories), so the digests match end to end. This previously forced `TASK_INELIGIBLE`
   on every live v2 dispatch.
2. **G2 — request-bound capability acquisition.** `capabilities(request)` now fails closed when the
   request or its envelope is absent, requires `/catalog` and `/observe` to agree on `buildId`,
   `catalogHash` and `capabilityDigest`, and requires the observed `clientId` to equal the request
   envelope's `identities.clientId`. No value is copied out of the request and no route is preferred
   over the other.
3. **G3 — kill-switch receipt.** `stop(...)` now threads the requested kill-switch mode into
   `GatewayReceipt.killSwitch` instead of hard-coding `false`; the bounded terminal wait's
   reconciliation-only receipt preserves the observed mode. This restores the SC-1431 contract that
   the durable receipt records the requested stop mode.

## Bounded terminal wait (#7)

`awaitTerminal` was previously dead code: only the gateway's own test referenced it, so a one-run
bootstrap could never reach a proven terminal receipt (the operator script performs a single
dispatch and does not poll). The bounded, read-only wait is now wired through the orchestration
boundary:

- `AccountBuilderOrchestrationGateway.awaitTerminal(correlation, lease, deadline)` is a default
  method that performs exactly one `status` lookup; the v2 gateway overrides it with the bounded poll
  (earliest of the supplied deadline, the lease expiry and the configured terminal timeout).
- `AccountBuilderOrchestrationService.reconcileUntilTerminal(orchestrationId, deadline)` projects the
  durable terminal state after the bounded wait, or `NEEDS_RECONCILIATION` when still nonterminal.
  It never reserves or dispatches again; a missing lease degrades to the existing lookup-only
  reservation reconciliation.
- `BootstrapGameplayOperatorService.dispatch` calls it after submission when the view is not yet
  terminal, using the envelope deadline.

## Operator, configuration and robustness

- **G4 — enable gates.** `requireEnabled()` now requires `operator-enabled`, `recording-enabled` and
  `micro6-v2-enabled`; `dispatch` fails closed with `BOOTSTRAP_RECORDING_DISABLED` /
  `MICRO6_V2_DISABLED` before any recording row or Micro6 call. `dispatchIsRejectedBeforeAnyRecordingWhenAGateIsDisabled`
  proves no recording or approval interaction.
- **G5 — pending approvals.** `Availability.pendingApprovals` is now the durable count from
  `BootstrapApprovalStore.countPending()` instead of a hard-coded `0`.
- **G6 — finalization observability.** The two silent `catch (RuntimeException ignored)` blocks around
  manifest publish/finalization now log a warning with the orchestration id; recovery behavior is
  unchanged (never redispatch).
- **G7 — recovery transaction.** `BootstrapRecordingCoordinator.rebuildArtifactForRecovery` is now
  `@Transactional`, matching its peers.
- **G8 — no second delivery path.** `GameplayBootstrapConfiguration`'s legacy
  `BootstrapEvidenceStore`/`BootstrapGameplayOrchestrator` beans are gated behind
  `gameplay.bootstrap.legacy-orchestrator-enabled=true` (default off), so production wiring exposes
  only the SC-1498 path. Compatibility tests construct these types directly and are unaffected.
- **G9 — config surface.** `application.yml` gains a `gameplay.bootstrap` block and `.env.example` /
  `.env.fedora.example` document `GAMEPLAY_BOOTSTRAP_RECORDING_ENABLED`,
  `GAMEPLAY_BOOTSTRAP_MICRO6_V2_ENABLED` and `GAMEPLAY_BOOTSTRAP_MICRO6_V2_URL`; the runbook lists all
  required gates and the bounded-wait behavior.
- **G10 — approval conflict identity.** The approval conflict owner lookup now includes
  `recording_id`, so a reuse that collides only on the unique recording id resolves to the stable
  `APPROVAL_CONFLICT` with its durable owner instead of an opaque ownership failure.

## Tests

New or extended coverage:

- `Micro6V2AccountBuilderOrchestrationGatewayTest` — capability task key and canonical schema digest
  (and that it is not the catalog hash); absent-envelope, wrong-client and disagreeing-catalog fail
  closed; requested kill-switch mode recorded on the receipt. Existing bounded-wait, identity and
  evidence assertions retained.
- `BootstrapGameplayOperatorServiceTest` — validation-before-recording ordering (`InOrder`) and
  closed gates rejecting before any recording/approval interaction.
- `BootstrapApprovalStorePostgresTest` — recording-id-only reuse yields a stable `APPROVAL_CONFLICT`.
- `AccountBuilderOrchestrationRestartTest`, `IsolatedPostgresTestDatabase`,
  `Sc1431BudgetIntegritySchemaTest`, `Sc1498FlywaySchemaTest` — V89 head.

## Fedora verification

Worktree `/run/media/ikiddoi/dev-hdd/dev/Worktrees/ChromaScape-sc1498-luna`; Corretto 17.0.20.1
(`JAVA_HOME=~/.jdks/corretto-17.0.20.1`), Gradle 8.14.2, Podman API socket
`unix:///run/user/1000/podman/podman.sock`, `TESTCONTAINERS_RYUK_DISABLED=true`.

- `compileJava compileTestJava` — green.
- `./gradlew test --tests 'com.chromascape.web.gameplay.*'` — **47 classes, 357 tests, 0 failures, 0
  errors, 0 skipped**. Includes the SC-1498 bootstrap suite (7 classes, 30 tests, PostgreSQL
  Testcontainers) and the SC-1431 accountbuilder suite (15 classes, 138 tests).
- `checkstyleMain checkstyleTest` — BUILD SUCCESSFUL (pre-existing warning baseline only).
- `spotlessCheck` — fails **only** on three pre-existing baseline files unrelated to SC-1498:
  `IsolatedPatchWorker.java`, `Micro6TargetRelaunchService.java`,
  `Micro6TargetRelaunchServiceTest.java`. A scoped `spotlessApply` followed by reverting those three
  files left no SC-1498 file reformatted; the repository-wide baseline was not rewritten.
- `git diff --check` — clean.

## Remaining external gates (not run, not claimed)

Native-Windows Gradle verification, the Windows-path Micro6 contract test, real Micro6
catalog/observation exchange, real reserve/dispatch/status/reconcile/stop integration, live
CODEX/Claude provider execution, and end-to-end duplicate-delivery proof. The Fedora work proves
behavior against the closed and stubbed gateways plus Testcontainers PostgreSQL only. SC-1498 still
depends on SC-1431 merge/PR ordering, and no tracker or Felix transition is claimed here.

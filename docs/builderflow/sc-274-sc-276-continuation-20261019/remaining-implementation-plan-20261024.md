# SC-274 / SC-276: full remaining implementation plan after publication

Current full plan after the latest qualification review:
[remaining-implementation-plan-20261025.md](remaining-implementation-plan-20261025.md).
The historical scope and results below are retained.

Date: 24 October 2026. Status: planned work; no implementation executed by this update.

This is the current remaining plan after publication of ChromaScape candidate
`5cd21150c022c3877d39c90b6606253d59e9fc4e` on `codex/sc274-completion-20261015`.
It supersedes earlier remaining-work ordering and stale red-test summaries, preserving
historical evidence. The original criteria in
[delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md) remain unchanged.
No original acceptance row is closed.

## Verified baseline

- Source commit 9dfac340 was tested in a clean checkout; published tip 5cd21150 differs
  only in verification documentation. Candidate publication and remote-tip verification succeeded.
- Selected affected Java union: **716 tests, 699 passed, zero failed, 17 skipped**.
  All 32 previously failing cases were matched by suite/name and now pass.
- Artifact completion 61/61; fresh-JVM determinism/cleanup 6/6; debugger JavaScript 14/14;
  syntax checks on seven changed JS/MJS files passed.
- Model registry fixtures, provenance fixture wiring, missing-model error mapping and
  child-process cleanup fixes are implemented. Do not repeat them as outstanding defects.
- Historical metrics are reconciled. b83c net inventory delta is +3; 23 catches are a
  different metric. Historical preflight remains NOT_READY; capture content was not repaired.
- Two actual-pin authored native handoffs previously passed, including cross-process restore.
  Those results remain non-certifying and do not cover every advertised production method.
- Windows normal-operation publication works, but immediate post-link power-loss durability
  remains **unqualified**. POSIX directory forcing remains intact.
- A combined native guard run had a closure CLI timeout; a targeted rerun passed. Node API
  denial is not an OS sandbox. Repository-wide Spotless still fails; no mass formatting applied.
- Last read-only clock check measured 1.662 seconds skew and passed the five-second limit;
  Windows Time was stopped. This is historical alignment, not durable synchronization proof.
- Last service probes: registry reachable, executor stale/unselectable, source 8180 stopped.
  Refresh observations before mutation. Do not treat those observations as current availability.

Evidence: [publication-result-20261024.md](publication-result-20261024.md),
[publication-verification-20261024.json](publication-verification-20261024.json), and
[publication-review-20261024.md](publication-review-20261024.md).
Raw publication evidence is under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024\publish-review-20261024T030104Z`.

## Scope, ownership and operating rules

| Surface | Working location / prerequisite |
|---|---|
| ChromaScape candidate | `C:\Dev\IdeaProjects\.worktrees\sc274-completion` |
| Clean publication verification checkout | `C:\Dev\IdeaProjects\.worktrees\sc274-publish-verify-20261024` |
| Recovered native candidate | `C:\Dev\IdeaProjects\.worktrees\sc276-recovered-20261021` |
| Native owning repository | `C:\Dev\IdeaProjects\xrsps-typescript` |
| Selected Micro6 development source | `C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime` |
| Pinned registry source | `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1`; recorded backend pin 58098690b83a3c0914016ddbe69344657401eb47 |
| Owned compiler service mirror | Previous sc274-chromascape-trust-exp path is absent; reconstruct from verified inputs |
| New evidence | A distinct timestamped attempt under `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261024` or the actual execution date |

Read current AGENTS in each owning repository before changes. Verify actual paths, HEADs,
diffs, process ownership, container identities, mounts and generation fences. The ChromaScape
push did not publish native repository changes. Preserve the separate uncommitted SC-666
Gradle task; stage only intended paths rather than using an indiscriminate git add.

Never run wsl --shutdown. No implicit shared-service restart, host reboot, Docker reset,
database repair/reset, canonical trust replacement, game login or replay-consumer change.
Keep secrets protected and outside argv/logs/evidence. Preserve user changes, historical
attempts and fence high-water state. User participation is required for genuine login/capture
and human Watch; request it when the concrete step is ready.

Felix/Shortcut remain explicitly skipped. No tracker state, outage-recovery operation or
acceptance transition is implied. Review-branch publication is not deployment authorization.

## Execution order and dependencies

Start WP1 and WP2 investigation, then run the locally achievable part of WP4. Continue WP3
method qualification and WP5 production boundaries on those inputs. WP6 migration investigation
and WP7 operational preparation can proceed independently. WP8 obtains the missing supported
allocation/trust/genuine inputs. WP9 integrates qualified packages and real Watch; WP10 closes
the final matrix and separately authorized rollout.

Do not postpone all 17 skipped tests behind genuine capture: owned database/service cases
can proceed once their own worker/database prerequisites exist. Client/provider cases need
the corresponding real pair/provider. No parallel agents unless separately instructed.

## WP1. Qualify a supported durable artifact-publication path

**Local design/implementation actionable; target-platform contract must be explicit.**
Owning surface: ImmutableArtifactStore and its storage/integration tests.

- [ ] Specify the acknowledgement guarantee: which object/ref/directory changes must survive
  process crash and power loss, filesystem/platform scope and recovery behavior.
- [ ] Select a supported implementation: a Windows persistence mechanism with documented
  post-link guarantees, or a qualified POSIX-backed deployment for the durable lane. Moving
  the required lane to another platform needs an explicit acceptance/deployment decision.
- [ ] Implement the selected path. Preserve atomic non-replacement, content verification,
  metadata/ref ordering, quotas, dependency integrity and failure/refusal semantics.
- [ ] Make unsupported durability observable and fail closed wherever production acceptance
  requires it. Do not silently equate ordinary Windows publication with proven durability.
- [ ] Add failures at each publication boundary, including post-link persistence and partial
  metadata/ref state. Verify retries, restart recovery, concurrent writers and orphan handling.
- [ ] Test power-loss behavior only on an owned disposable VM/storage target with a reviewed
  fault method. Killing the Java process alone is not a power-loss test; never power-cycle
  the shared host or Docker/WSL environment to create evidence.

**Validation:** artifact completion, model registry/train integration and target-platform
fault/recovery tests. Reuse the 61/61 normal-operation result only where inputs stay applicable.
**Exit:** published acknowledgement matches a documented, tested persistence contract; no
durability qualification is inferred from NTFS journaling or normal-operation green tests.

## WP2. Make source, dependencies and packages reproducible

**Locally actionable.** Owning surfaces: source recovery inventory, build/packaging scripts,
dependency/cache closure and the owned service launcher.

- [ ] Freeze actual compiler/native/Micro6/backend source, dirty patches, untracked inputs,
  lockfiles, toolchain, resolver and artifact hashes. Preserve the 105 recovered native files.
- [ ] Resolve historical expected bytes for the 27 compiler inventory paths where evidence
  permits. Otherwise record the exact unrecoverable boundary; current files existing is
  not proof of historical byte recovery.
- [ ] Preserve and review native changes in their owning repository. Establish an intended
  remote/branch and publication scope; do not claim the ChromaScape push published them.
- [ ] Replace node_modules, cache and collision junction assumptions with supported immutable
  acquisition/build inputs. Package the matching platform esbuild binary through a pinned
  dependency path instead of requiring a temporary external executable.
- [ ] Reconstruct an owned compiler service/build location from the reviewed source and an
  owned database/artifact root. Protect its credentials; disable unintended consumers before launch.
- [ ] Verify clean reconstruction in an independent directory, explicit package identity and
  refusal of missing/altered/unexpected/aliased/out-of-closure dependencies.

**Exit:** another checkout can reproduce the same package and closure without developer paths,
unrecorded junctions, temp binaries or hidden runtime state. Historical recovery limits remain labelled.

## WP3. Complete compiler/native method and state acceptance

**Authored work can proceed locally after relevant WP2 inputs; genuine claims require WP8.**

- [ ] Reconcile the advertised method inventory with implementation and production/headless
  tests. Cover Fishing, glassblowing, supported Magic/combat and advertised auxiliary actions;
  either prove support or refuse/remove unsupported advertisement explicitly.
- [ ] Verify resources/levels, item/slot/option/quantity, jewelry destination/charges,
  bank/widgets, interface/NPC identity, travel/regions/planes, timing, XP/effects and interruption.
- [ ] Keep initial dynamic state, static content and spawn rules separate. Resolve later targets
  at execution ticks without injecting future observations into reset state.
- [ ] Complete source-linked provenance/curation, immutable role/privacy and bounded inert
  import/export evidence. Retain fresh-JVM fixture determinism as supporting evidence.
- [ ] Verify zero mutation for invalid/stale/duplicate/unsupported actions and future-complete
  checkpoints: pending actions, queues, timers, RNG, time/world/source cursors and stop state.
- [ ] Repeat fresh-worker/cross-JVM continuation after relevant changes; assert behavioral
  outcomes separately from checkpoint equality, RECORDING_END or INCONCLUSIVE termination.
- [ ] Preserve the historical audit's supported NOT_READY verdict. Fix only demonstrated
  decoder/compiler defects; never reinterpret banking/net quantity as fish catches to pass.

**Exit:** each advertised method and compiler/state gate has final positive/negative evidence
for its claimed scope. Two authored routes do not qualify the whole production surface.

## WP4. Execute the 17 skipped integration cases

**Owned service/database setup is locally actionable; real client/provider cases have additional inputs.**

- [ ] Use the exact skip list in publication-verification-20261024.json. Classify the 14
  XrspsDurableSessionIntegrationTest cases and three XrspsSessionRepositoryIntegrationTest
  cases by database, worker, client and configured-provider prerequisites.
- [ ] Provision disposable PostgreSQL/schema and an owned worker/artifact root. Set
  SC1991_TEST_JDBC_URL and SC1991_WORKER_ENTRYPOINT only to verified owned resources.
  Supplying the environment variables alone does not satisfy the prerequisites.
- [ ] Run service/database cases: transaction visibility, rollback of action/checkpoint/tick,
  idempotency, retry ownership, concurrent initialization, durable mismatch latching,
  recorded journal validation and cross-service restore.
- [ ] Supply a qualified native pair for real Fishing/jewelry and actual client rendering.
  Supply a reviewed configured provider and bounded request budget for provider-specific cases.
  Do not substitute mocked outcomes for tests named as actual-client/provider integration.
- [ ] Retain every case's pass/fail/blocker and test logs. Check resource ownership and remove
  only owned disposable resources; leave shared data and actor sessions untouched.

**Exit:** no unexplained skip. Passing the owned subset narrows its gate; absent actual
client/provider cases remain unverified rather than being reported as a fully green matrix.

## WP5. Prove actual isolation, cancellation and production budgets

**Local implementation/test preparation actionable after WP2.**

- [ ] Establish explicit supported-platform startup/request/run/CPU/memory/throughput limits
  and cancellation/cleanup deadlines. Authored 180-second transport/3600-second budgets are not production requirements.
- [ ] Implement missing OS-level egress/filesystem/process/thread enforcement and bounded
  resources. Keep Node API guards as defense in depth, not the OS isolation proof.
- [ ] Inject actual network/filesystem/child-process/thread escapes and resource violations
  into owned workers. Verify refusal before side effects and no surviving unowned children/artifacts.
- [ ] Test timeout/cancel during startup, execution, checkpointing, report publication and
  shutdown, including concurrent/fresh workers and retry/recovery.
- [ ] Reproduce and diagnose the closure CLI timeout under cold/concurrent load without
  silently increasing deadlines. Check deterministic closure export and final resolver bytes.
- [ ] Measure full scenario cold/warm and parallel throughput through production transport.
  Handshake times and aggregate harness durations alone do not qualify the run budget.

**Exit:** real target-platform boundaries, complete cleanup and measured production budgets
pass deliberate violations on the final reviewed pin.

## WP6. Establish migration authority and prove supported upgrades

**Local investigation/preparation actionable; release identity requires authoritative input.**

- [ ] Obtain supported deployed Flyway histories/checksums with exact release/artifact/commit.
  Settle V47-V49, V53-V57 and V58-V60 identities, V50 guard ownership and V71/V72 release status.
- [ ] Define supported starting schemas and intended merged schema, including recording-change
  tables. Local tags predating migrations and inconsistent shared dev history are not release authority.
- [ ] Prepare reviewed forward-compatible reconciliation. Renumber only proven-unreleased
  migrations; no silent released checksum/history edits, repair or shared reset.
- [ ] Run actual application/Flyway empty installs and supported upgrades on disposable stores,
  preserving data/constraints and checking validation, refusal and interrupted-upgrade recovery.
- [ ] Include registry bootstrap/install requirements. Existing SQL-only empty-install passes
  do not establish Flyway or released upgrade compatibility.

**Exit:** every supported baseline has reviewed upgrade evidence or a precise authority blocker.
Hypothetical baseline experiments stay explicitly hypothetical.

## WP7. Qualify sustained clock and grant-renewal operation

**Read-only diagnosis and owned experiments actionable; host changes retain their decision gate.**

- [ ] Refresh issuer/host/Linux/container/registry/source clock measurements with uncertainty.
  Record current Windows Time state and the source of any alignment; do not reuse 1.662 seconds indefinitely.
- [ ] Assign time-sync and issuance/renewal owners. Establish supported sustained synchronization
  and representative stability/lifecycle observations. Prepare any host remedy concretely before approval.
- [ ] Retain five-second preflight unless an explicitly reviewed requirement changes it;
  TTL stays at most 120 seconds. Renewal remaining threshold equals skew allowance plus
  publication/retry latency and safety; elapsed deadline equals TTL minus that threshold.
- [ ] Test valid renewal, retry, issuer stop/restart, revocation, expiry, atomic replacement
  and durable generation replay fencing. Never reset high-water generations to restore service.
- [ ] Refresh the owned registry route/source boundary and exact selectable endpoint when
  integration needs it. Operator-issued experiment grants remain separate from SC-800 allocation.

**Exit:** stable requirements, owners and matching operational evidence exist. A single aligned
sample or stale selectable-generation record is insufficient.

## WP8. Obtain supported allocation, approved trust and genuine inputs

**External prerequisites, followed by owned integration.**

- [ ] Identify the supported SC-800 authority/contract/deployment. Launcher forwarding and
  offline operator grant issuance are not allocation implementation.
- [ ] Launch through the supported lifecycle with real machine/session/executor/artifact
  identity; retrieve actual catalog/schema and verify immutable hashes and endpoint bindings.
- [ ] Obtain an exact-artifact bundle accepted by the original configured trust root, or an
  explicit acceptance amendment. An approved signer can provide the bundle without sharing its private key.
- [ ] Verify key/signature/environment/repository/build/tree/artifact/snapshot/content identity;
  prove allocation, source and catalog select the same instance. Test turnover/expiry/mismatch/fencing.
- [ ] Create real immutable profile/context and genuinely verified stabilization through
  supported APIs. Obtain eligible finalized evidence or prepare authorized capture with user login.
- [ ] Preserve real actions/observations/receipts, termination, privacy and dataset role.
  Do not promote AUTHORED_FIXTURE, invent identities or weaken the historical preflight gate.
- [ ] Repeat source binding, idempotency/identity refusal, authenticated reads, restart persistence
  and tamper refusal using genuine inputs. Restore only ownership-verified isolated services.

**Exit:** the original trust/allocation/genuine-input gates have distinct evidence for the
claimed scope. Development-key tests do not silently amend those gates.

## WP9. Qualify the paired package and real Watch lifecycle

**Package preparation local; final client acceptance depends on relevant WP1-WP8 outputs.**

- [ ] Build paired worker/client packages from frozen reviewed inputs; inventory adapter,
  build/cache/content/schema/assets/loader closure. Qualify worker before paired manifest publication.
- [ ] Run packaged Java/native durable lanes and reject missing/mismatched packages before execution.
- [ ] Attach a real client to the same authoritative world through an approved interaction surface.
  Exercise exact-origin authenticated Start/Retry/Watch, pause/resume, tick controls, reconnect,
  cancel/import/export, one-actor ownership and scoped teardown.
- [ ] Compare bank/widgets/travel/Fishing outcomes and receipts across observer/camera/disconnect
  variants. Prove observation cannot change causal outcomes.
- [ ] Obtain separate human Watch sign-off. Keep LIVE_XRSPS opt-in/non-certifying and qualified
  offline workers network-isolated. Native client tests do not substitute for genuine capture.

**Exit:** final pair identity, real lifecycle/noninterference and human approval are evidenced.

## WP10. Finish formatting, final validation and authorized delivery

- [ ] Resolve formatting/style requirements for intended changes without mass-reformatting
  unrelated work. Repository-wide Spotless remains a recorded failing check, not a pass.
- [ ] Freeze final multi-repository revisions, source/artifact/configuration identities and
  reviewed diffs; verify the actual native source publication and package provenance.
- [ ] Reuse prior passes only for unchanged relevant inputs. Run required affected, CI,
  differential/oracle, database, package, isolation, negative and performance/stress checks.
  Retain failures/skips/unrun cases; demonstrate deliberate regressions are detected.
- [ ] Map every original acceptance row to final evidence. Publish Implementation Evidence
  with Changed files / Tests / Remaining work and Verification Evidence with Acceptance results /
  Tests / Risks / Rollback. Do not close the story while required gates are unmet.
- [ ] Prepare concrete rollout artifacts/routes/protected credentials, clock requirements,
  renewal owner, durable fences, monitoring and component rollback. Obtain shared-rollout
  authorization before using documented wrappers. Merge/publish/release only within authorized scope.
- [ ] Verify effective configuration, source/candidate identity, auth/refusal, schema and
  operational health after rollout, preserving replay-consumer settings and historical attempts.

**Exit:** delivered inputs satisfy the complete required matrix and human/deployment gates,
or a truthful partial handoff identifies exact remaining blockers. No automatic tracker transition.

## Original acceptance mapping

| Row | Remaining WP / final evidence |
|---|---|
| 274-1 | WP2/3/8/10: final immutable genuine inputs and deterministic bytes/checksum |
| 274-2 | WP3/8/10: exact source/curation provenance |
| 274-3 | WP3/10: final source-linked incomplete/invalid refusal |
| 274-4 | WP3/4/8/9/10: genuine native Fishing causal execution |
| 274-5 | WP3/4/10: incomplete/legacy refusal and zero writes |
| 274-6 | WP3/8/10: immutable privacy and dataset role |
| 274-7 | WP3/10: bounded inert import/export on final inputs |
| 274-travel | WP3/4/8/9/10: exact bank/travel/Fishing with sufficient evidence |
| 274-historical | WP3/10: retain supported historical NOT_READY verdict and source references |
| 276-boundary | WP2/3/4/9/10: production xRSPS behind the adapter |
| 276-methods | WP3/4/9/10: every advertised production/headless method |
| 276-closure | WP2/5/9/10: portable closure, package refusal and stable bounded export |
| 276-state | WP1/3/4/9/10: future-complete durable restart/continuation |
| 276-safety | WP5/10: actual bounded OS-isolated workers |
| 276-live | WP4/7/8/9/10: allocated, authenticated opt-in lifecycle |
| 276-client | WP4/8/9/10: real client, genuine input and noninterference |
| 276-certification | WP1-WP10: complete frozen-input qualification and required human gates |

## Evidence and rollback contract

Each executed WP writes a timestamped attempt with exact files/pins, commands actually run,
structured results, XML/logs, source/artifact hashes and scope limits. Preserve previous
failed attempts and raw reports; dated corrections supersede rather than overwrite them.
Never place actual credentials/private signing material in normal evidence or Git.

Use owned disposable stores/workers for verification. Stop/delete only ownership-verified
resources; preserve shared databases, historical evidence, grants/fence volumes and generation
high-water state. Credential recovery rolls forward to fresh protected values. Migration and
platform rollback require reviewed environment-specific procedures, not a routine shared reset.
Use follow-up commits rather than force-pushing published history.

## This update

Documentation only: this full plan and pointers from older summaries. No implementation,
runtime tests, service/configuration/database change, credential rotation or tracker action
is performed by writing it. Execution estimates remain conditional on platform decisions,
release authority and genuine inputs; no zero-code-change or completion-date promise is made.

## Execution status (24 October 2026)

The locally achievable slice was executed: POSIX-only durability with fail-closed Windows
admission, the exact 105-file native recovery commit and verified bundle, 3/17 integration
cases passing against disposable PostgreSQL, and a recorded worker-qualification blocker for
the other 10. See
[implementation-evidence-20261024.md](implementation-evidence-20261024.md) and
[verification-evidence-20261024.md](verification-evidence-20261024.md). Every acceptance row
remains open.


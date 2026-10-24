# SC-274 / SC-276: full remaining implementation plan

Latest executed verification and prioritized remaining work (23 October):
[full-verification-20261023.md](full-verification-20261023.md).
This supersedes older availability/clock observations and broad green-test claims below:
547 selected unique Java tests, 515 passed/32 failed; actual-pin handoffs 2/2 passed;
native combined guard run had a closure CLI timeout; Spotless failed. Current measured
clock skew is 1.662 seconds rather than the earlier 48-second observation.

Date: 22 October 2026. Status: current remaining plan after review of attempt
`attempt-20261022T014931Z`. This document update executes no implementation.

This is the current remaining plan after the 21 October recovery, audits and authored
handoffs. It supersedes the remaining-work ordering in
[remaining-plan-after-review-20261021.md](remaining-plan-after-review-20261021.md).
The original scope and acceptance criteria in
[delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md) remain unchanged.
No acceptance row is closed by this plan.

## Current execution status after review

Review: [remaining-plan-review-20261022.md](remaining-plan-review-20261022.md).
Recorded evidence is under
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-20261022T014931Z`.
This status supersedes older baseline observations below. The full work package instructions
and 17-row acceptance mapping remain in force.

| WP | Completed subchecks | Remaining implementation / prerequisite |
|---|---|---|
| 1 Inputs/evidence | Renewal correction and six checker regressions recorded; patches/archives and source identities frozen | Missing historical expected bytes for 27 compiler paths; reviewed untracked inputs; owned build/service location; portable dependencies |
| 2 Registry | Recorded existing owned containers restarted, IDs/volumes retained, health/auth and discovery reachable | Executor unselectable; revalidate before later tests. Online selection needs heartbeat/grant and WP7/WP8 prerequisites |
| 3 Historical | Earlier audit retained; catch ledger/delta, unsupported and typed-stream accounting, and the preflight message fix are complete; post-restart live b83c/3a show the corrected message; supported NOT_READY verdict recorded; canonical ReplayWorker boot fixed and verified | Broader acceptance and final frozen-input suite remain |
| 4 Behavior/determinism | Fresh-JVM fixture outputs equal; child cleanup, bounded reaping, curation identity assertions and nonterminating-child refusal are hardened (3/3 green); two authored handoffs retained | Advertised methods, provenance/privacy/role and actual production parity |
| 5 Isolation/performance | Recorded 24/24 Node guard/closure checks; handshake/refusal measurements | OS-level egress, full scenario budgets/cancellation/resource enforcement, production throughput and final-pin evidence |
| 6 Migrations | SQL empty-install and collision inventory retained | Released history/artifact authority, reconciliation, Flyway installation and supported upgrade/data-preservation/recovery tests |
| 7 Clock/renewal | Evidence arithmetic corrected | Authorized host-clock remedy, stability verification and operational owners; no tolerance relaxation |
| 8 Allocation/trust/input | Labelled experiments retained | Supported allocation, actual catalog/schema, original-trust bundle or amendment, genuine stabilization/capture |
| 9 Package/Watch | Preparation inputs identified | Self-contained package, real lifecycle/noninterference and human sign-off |
| 10 Final delivery | Original 17-row matrix preserved | Final affected suites, frozen pins, reviewed rollout and deployment authorization |

WP3 and the locally actionable WP4 hardening are complete. WP1, WP4 acceptance and WP5
remain partial. WP2 is complete for recorded reachability restoration, not for selectable
allocation. Do not mark whole work packages done from these subchecks.

## 0. Execution update (22 October 2026)

Four locally actionable work packages were executed after this plan was written. Results:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-20261022T014931Z\execution-results-20261022.md`.
No acceptance row is closed and no tracker state changed.

| WP | Now evidenced | Still open |
|---|---|---|
| WP1 | Renewal fields corrected with preserved original hashes and a fail-closed evidence checker (6/6 tests). Input freeze across five repositories, portable patches/archives, 27-path current-state reconciliation, handoff-test hashes. | Self-contained package inputs; the `sc274-chromascape-trust-exp` path in Â§1 is **absent**; junction and external esbuild dependencies remain. |
| WP2 | Owned registry reachability restored (`docker start`, IDs/volumes preserved); 8182 health 200, auth 401/401/200, 8181 discovery 200 with one **unselectable** executor. | Supported allocation, grants/heartbeats, selectable executor, genuine catalog. Stale executor is expected. |
| WP4 | Fresh-JVM determinism: two child JVMs, byte-identical 28079-byte manifests, identical manifest and curation checksums, compiler 2.4.0. | Advertised-method parity, genuine input, privacy/curation gates, full-suite rerun. |
| WP5 | 24/24 closure/guard tests on Windows with deliberate violations; real stdio handshake cold 1839 ms, warm 126â€“142 ms, parallel 2/3/4 workers 784/227/230 ms; refusal and clean-exit checks. | Full run budget, cancellation, CPU/memory/time limits, OS-level egress isolation (current denial is `NODE_API_DIAL_NOT_OS_SANDBOX`). |

## 0.1 Execution update (23 October 2026)

WP3 reconciliation and WP4 local hardening were completed. Results:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-20261022T224420Z\execution-results-wp3-20261023.md`.
No acceptance row is closed and no tracker state changed.

| WP | Now evidenced | Still open |
|---|---|---|
| WP3 | b83c metric reconciled: initial 55, final 58, net +3; 23 catches are 14 trout + 9 salmon, while the one positive addition is the 18-feather withdrawal. Typed stream: 11 typed rows, 2 accepted-unresolved rows absent from the typed table, `TYPED_ACTION_STREAM_INCOMPLETE` + `BEST_EFFORT`. Unsupported accounting: 23 genuine, 2 supported, 2 unresolved, 57 capture loss, 70 total. Preflight text corrected without changing the exactly-five requirement; focused tests 22/22 green and post-restart live b83c/3a serve the corrected message. | Final supported-verdict write-up; broader affected suite on frozen inputs. |
| WP4 | Fresh-JVM determinism test hardened with bounded cleanup, identity/curation assertions and a nonterminating-child refusal; 3/3 green. | Production method parity, genuine input, provenance/privacy gates and full-suite rerun. |

Operational note: the canonical `REPLAY_WORKER_ENABLED=true` boot failure was fixed by
annotating the public `ReplayWorker` constructor with `@Autowired`. 8090 now boots with the
canonical value, Spring reports `ReplayWorker matched: @ConditionalOnProperty
(replay.worker.enabled=true)`, and `ReplayWorkerLifecycleTest` is 3/3 green. The read-only
b83c/3a preflight responses are byte-identical to the earlier worker-disabled run. The
`FishingDatasetBuilderServiceTest` fixture still has three pre-existing failures
(`RUN_CONTEXT_REQUIRED` because its injected provenance dependency is null); they are not
caused by the preflight text change.

## 1. Baseline and ownership

| Surface | Latest recorded state; remaining qualification |
|---|---|
| Native recovery | 105/105 native files recovered byte-exact against the preserved inventory. The recovered candidate is dirty, not a released clean pin. Twenty-seven ChromaScape inventory paths remain outside that recovery scope. |
| Historical recording | Audit and live preflight ran. b83c is NOT_READY: unsupported actions and catch delta 3 versus required 5. The 23-catch ledger uses a different metric. The 3a pre-roll has no supported fishing actions and lacks required evidence. |
| Migrations | Collision mapping complete. Recorded SQL empty installs pass: candidate 70/70, comparison 61/61, registry 2/2. Cross-lineage collisions reproduced. Released upgrade authority, Flyway-path compatibility, data preservation and recovery remain unproved. |
| Clock | Windows host approximately 48 seconds behind external UTC; Windows Time stopped. Linux/container clocks near external UTC. Existing 5-second preflight retained; no host remedy applied. |
| Authored handoffs | Compiler 2.4.0 bank/travel/Fishing and source-schedule exports pass native handoffs on recovered inputs: restore in a second process, equal final checkpoints and refusal without mutation. Outcome INCONCLUSIVE, fidelity SYNTHETIC_CONTRACT. |
| Registry | Last recorded 8181 executor response was HTTP 200 with registry_unreachable and no executors. This supersedes the earlier observation of a stale executor; refresh before acting. |
| Prior scoped evidence | Credentials, authored source persistence, grant mechanism, publication, artifact isolation and continuation results retained within their original scope. The 319-test suite was not rerun after the latest handoff changes. |
| External gates | No supported allocation authority, original-trust-approved bundle, genuine eligible input, paired package/Watch sign-off or shared-rollout authorization. |

Evidence root for the latest stage reports:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261021\attempt-20261021-remaining-plan`.
The reports are historical observations, not current service health claims.

| Purpose | Location |
|---|---|
| Compiler candidate | `C:\Dev\IdeaProjects\.worktrees\sc274-completion` |
| Owned compiler service/build mirror | `C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp` |
| Recovered native execution worktree | `C:\Dev\IdeaProjects\.worktrees\sc276-recovered-20261021` |
| Canonical native repository | `C:\Dev\IdeaProjects\xrsps-typescript` |
| Selected Micro6 development source | `C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime` |
| Pinned registry source | `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1`, recorded revision 58098690b83a3c0914016ddbe69344657401eb47 |
| New execution evidence | `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-<UTC timestamp>`; create when execution begins |

Read applicable AGENTS before working in each repository. Confirm each actual Git root,
revision and worktree path before edits. Canonical Felix project identities remain those
in the shared contract, but Felix is explicitly skipped; no Shortcut/Felix mutations.

Preserve shared 8090, shared registry/database, canonical trust, replay-consumer settings,
historical attempts and unrelated dirty work. Do not restart stopped 8098 or log in to the
game. Never run `wsl --shutdown`. Use documented Windows wrappers for authorized service
operations. Secrets remain in protected storage and outside argv, ordinary logs and evidence.

## 2. Execution order

1. Harden the new fresh-JVM test and close source/input ownership gaps (WP1/WP4).
2. Revalidate owned registry when needed for integration (WP2); do not repeat the recorded repair without cause.
3. Reconcile historical metrics and typed-action completeness (WP3).
4. Finish native behavior gaps and retain scoped fresh-JVM determinism evidence (WP4).
5. Qualify closure, actual isolation and production performance (WP5).
6. Continue migration authority investigation; implement only after the baseline is settled (WP6).
7. Prepare clock remediation and renewal operations; apply host changes only when authorized (WP7).
8. Obtain supported allocation, approved trust and genuine inputs (WP8).
9. Build paired packages and complete actual client/Watch qualification (WP9).
10. Freeze the final matrix and perform separately authorized delivery (WP10).

Independent local work continues while external inputs wait. Package build preparation can
start before genuine inputs arrive; genuine-client acceptance still requires them. Do not
spawn parallel agents unless separately instructed.

## WP1. Correct evidence and make the candidate reproducible

**Locally actionable.** Changes: dated evidence correction, source inventory, scoped test/build inputs.

- [ ] Preserve the original stage5 JSON/Markdown and their hashes. Add a dated correction for
  reversed renewal fields; update current summaries to point to it without rewriting raw results.
- [ ] With margin 56.229 seconds and TTL 120 seconds, record renewal start before **56.229
  seconds remain**, equivalent to **63.771 seconds elapsed**. This is a calculation from the
  recorded inputs, not a fresh measurement or permission to operate outside the skew limit.
- [ ] Compare stage5 generation logic with the already-corrected clock helper; remove duplicated
  arithmetic or add a regression preventing the fields being reversed again.
- [ ] Freeze compiler/native/Micro6/backend HEADs, scoped diffs, untracked files, tool versions,
  package locks, source hashes and relevant artifact identities. HEAD alone is insufficient.
- [ ] Reconcile the 27 ChromaScape inventory paths against current files. Record exact matches,
  intentional changes and unresolved recovery gaps; preserve user changes.
- [ ] Preserve the two untracked handoff tests and review their ESBUILD_BINARY_PATH allowlist
  change. Include them in the reviewed deliverable and CI/test selection; no implicit commit/merge.
- [ ] Replace machine-local cache/collision junction assumptions with documented, reproducible
  input acquisition and content hashes. An external junction is not a self-contained package.
- [ ] Record a portable source patch/archive for recovered dirty work and its base commit.
  New edits create a new candidate identity rather than being described as the recovered pin.

**Validation:** correction consistency, input-hash comparison, reproducible worker capability
smoke check and focused test-environment regressions. Run affected checks only.

**Exit:** another session can reconstruct the exact source/test inputs and distinguish recovery
from new implementation. Missing input provenance stays explicit.

## WP2. Restore owned registry reachability

**Locally actionable on ownership-verified experiment resources.** Changes: owned deployment/configuration only unless a demonstrated product defect requires code.

- [ ] Capture the current 8181 response, effective registry origin, process ownership and registry
  container state. Distinguish connection failure, timeout, HTTP auth failure and expired grants.
- [ ] Inspect owned 8182 registry/PostgreSQL health, logs, mounts, network route, protected auth
  and effective configuration. Do not print credentials or start shared replacements.
- [ ] Start/repair only the verified owned deployment through its preserved scripts when needed.
  Preserve database, grants and fence volumes; never reset the high-water generation.
- [ ] Verify authenticated registry access and the exact Docker-to-runtime source boundary.
  Pass source credentials through stdin to the bounded probe helper, not shell-expanded argv.
- [ ] If a later experiment needs selectability, issue current operator-labelled grants and
  heartbeats through the existing owned tooling. Respect WP7 preflight; do not relax it to pass.
- [ ] Require the expected endpoint_ref and selectable=true for an online-selection claim.
  A reachable registry, empty list or stale executor is a distinct result, not allocation proof.

**Validation:** explicit HTTP statuses, configured destination, source signature/identity/file
hash where applicable, expiry/refusal and fence retention after relevant changes.

**Exit:** owned reachability is restored or its precise local failure is diagnosed. Supported
SC-800 allocation and real catalog qualification remain WP8 even if this experiment works.

## WP3. Reconcile the historical recording and fix proven decoder/compiler defects

**Local investigation and authored regression work can proceed.** Changes: decoder/preflight/compiler code only when supported by raw evidence.

- [x] Trace the 23-catch UpdateInvPartial ledger and observedCatchDelta=3 from raw packets and
  snapshots through decoder, aggregation/windowing and preflight. Explain their units and scope.
- [x] Inspect all unsupported actions and their ordering. Separate genuine unsupported behavior,
  incomplete capture, decoder loss, classification errors and deliberately ignored menu actions.
- [x] Resolve the typed-stream accounting: 11 accepted typed rows, two accepted-but-unresolved
  rows and 57 unacknowledged menu clicks. Do not silently drop or convert unresolved actions.
- [x] Preserve corrected source facts: tick 342 is Cancel; snapshot tick 900 is final state.
  Keep the pre-roll 3a separate from the fishing recording.
- [x] Add minimal raw-input regression cases for demonstrated bugs and stable refusal cases
  for evidence that cannot support causal execution. Preserve existing capture bytes.
- [x] Rerun read-only live preflight after relevant changes, recording source/build identity and
  exact reasons. Do not change the exactly-five requirement merely to accept this recording.

Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-20261022T224420Z\execution-results-wp3-20261023.md`.
Focused tests 22/22 green; post-restart live b83c/3a returned HTTP 200 with the corrected
message. Canonical `REPLAY_WORKER_ENABLED=true` boot is now verified after adding
`@Autowired` to the public `ReplayWorker` constructor; `ReplayWorkerLifecycleTest` is 3/3
green and the preflight responses are unchanged.

**Validation:** expected decoded actions/catches with source references, deterministic accounting,
stable NOT_READY on incomplete input and zero mutation for rejected causal requests.

**Exit:** historical eligibility has a supported verdict. If the recording truly lacks required
evidence, retain that verdict and request a new authorized capture in WP8; do not invent content.

## WP4. Complete compiler determinism and native behavior qualification

**Locally actionable after WP1; full genuine parity also depends on WP8.**

- [x] Add a fresh-JVM compiler test: two isolated processes compile the same immutable inputs,
  compiler pin and published curation, producing byte-identical manifests/checksums.
- Recorded subcheck: the fixture test now exists and passed with distinct child PIDs and
  equal 28,079-byte manifests. This is compiler fixture evidence; repository dependencies
  are mocked, so it does not prove real database persistence or genuine-input qualification.
- [x] Harden `ScenarioManifestFreshJvmDeterminismTest`: clean up every started child in
  `finally`, including second-spawn failure, timeout, interruption and assertion failure.
  Use bounded graceful/forced termination and confirm exit; retain failure logs.
- [x] Require both published-curation fields to exist, be textual and nonblank, and equal
  each other. Reject missing/empty manifest identity metadata in both child results.
  `path(...).isNull()` alone accepts a missing field and does not compare both outputs.
- [x] Verify cleanup with an intentionally nonterminating child and refusal of missing,
  blank or differing curation metadata; rerun the normal fresh-JVM path afterward.
- [ ] Reconcile the original acceptance-to-test inventory with recovered source. Preserve the
  two authored handoff passes; do not call their INCONCLUSIVE outcomes behavioral certification.
- [ ] Exercise actual production/headless implementations for the supported method surface:
  Fishing, glassblowing, Magic, combat and advertised auxiliary methods. Include quests, exchange
  and finite order-book cases only where the product advertises support; otherwise verify refusal.
- [ ] Cover resources, requirements, item/slot/quantity, jewelry destination/charges, interface/NPC
  identity, timing, XP/effects, interruption and complete bank-to-travel-to-Fishing semantics.
- [ ] Separate initial dynamic state, static content and spawn rules. Resolve later targets at
  execution ticks across regions/planes without injecting future observations into reset state.
- [ ] Verify stale, duplicate, unsupported and malformed actions mutate nothing. Check pending
  actions, queues, timers, RNG, time/world/source cursors and stop state in durable snapshots.
- [ ] Repeat cross-process and fresh-JVM continuation after affected behavior/state changes.
  Specify asserted behavioral outcomes separately from checkpoint equality and RECORDING_END.
- [ ] Cover provenance, curation, privacy/dataset role, bounded inert import/export and stable
  source-linked compiler refusals wherever the inventory still lacks final evidence.

Evidence for the completed items:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261022\attempt-20261022T224420Z`
(`fresh-jvm-determinism-test-hardened.log`, `junit-fresh-jvm-hardened.xml`,
`fresh-jvm-hardened\`). Focused run: 3/3 tests green; equal 28,079-byte manifests.

**Validation:** focused positive/negative regressions, production/headless differential oracles,
fresh-process determinism and durable continuation on the reviewed input pin.

**Exit:** each supported method and compiler/state gate has evidence or a precise unsupported
boundary. Authored successes alone do not close genuine-input or original-trust acceptance.

## WP5. Qualify production closure, isolation and performance

**Local implementation can proceed with recovered inputs.** Changes: owning worker/adapter/package code where enforcement or performance defects are demonstrated.

- [ ] Establish explicit production startup, request, cancellation and run-budget requirements.
  Measure cold/warm and fresh/parallel workers through actual transport/configuration.
- [ ] Diagnose cost before changing limits. The 147/138-second harness durations and earlier
  180-second transport/3600-second authored budgets do not prove production performance.
- [ ] Inventory transitive modules, scripts, handlers, assets, cache/content/schema and dynamic
  loader inputs. Hash actual supplied inputs, including those currently reached via junctions.
- [ ] Reject missing, altered, unexpected, aliased, symlinked or out-of-closure dependencies
  before execution; retain the last supported package pin on failure.
- [ ] Exercise actual egress denial, filesystem and process/thread boundaries, cancellation,
  cleanup and CPU/memory/time limits using deliberate violations on the target platform.
- [ ] Implement missing enforcement and meaningful regression tests. Assert no unowned child
  processes, connections or artifacts survive refusal/cancellation.

**Exit:** actual production boundaries and measured budgets pass on reviewed inputs. Mocked
refusal, permissive authored lanes and dev junctions cannot substitute for this evidence.

## WP6. Settle migration authority and implement supported upgrades

**Local investigation/preparation is actionable; version decisions need release authority.**

- [ ] Obtain each supported deployed environment's Flyway history, exact SQL checksums and
  corresponding artifact digest/commit. Establish identities for V47-V49, V53-V57 and V58-V60.
- [ ] Establish ownership/review of the untracked recording migrations, candidate V71/V72 and
  modified V50 guard. Existing local tags predate migrations and do not settle their release status.
- [ ] Define supported starting schemas and the intended merged schema. Include recording-change
  tables absent from the candidate; equal table counts do not establish equal schemas.
- [ ] Choose a forward-compatible reconciliation from that evidence. Renumber only migrations
  proven unreleased; do not rewrite released checksums/history or use repair/reset to hide collisions.
- [ ] Run actual application/Flyway empty installs and supported upgrades on disposable stores.
  Existing psql SQL-install results are useful but do not exercise Flyway validation/history.
- [ ] Verify existing data, constraints, schema identity, deterministic failure, interrupted-upgrade
  recovery and supported repeat startup. Include registry deployment/bootstrap requirements.

**Exit:** reviewed migrations pass clean install and every supported baseline upgrade. If release
authority remains unavailable, complete the executable proposal/tests against labelled hypothetical
baselines and retain the release gate; do not call those production compatibility proof.

## WP7. Apply an authorized host-clock remedy and qualify renewal operations

**Diagnosis/preparation local; host time/service changes require separate authorization under the existing plan.**

- [ ] Re-measure host, Linux VM/container, registry and issuer against an independent reference
  with uncertainty. Treat stopped Windows Time and the recorded offset as observations, not a
  guarantee that starting the service alone fixes drift.
- [ ] Prepare the concrete Windows Time configuration/service/resync action, expected effect,
  verification and rollback. Identify clock-remedy and renewal operational owners.
- [ ] Once authorized, apply the reviewed host remedy and prove stability within the retained
  five-second preflight limit across a representative observation period and service lifecycle.
- [ ] Re-measure publication/retry latency and derive the renewal threshold correctly. TTL remains
  at most 120 seconds; do not suppress expiry checks or enlarge skew tolerance merely to pass.
- [ ] Test timely renewal, retry, issuer stoppage/restart, revocation, elapsed expiry and durable
  generation fencing. Record the maximum age/latency actually qualified.

**Exit:** clock and renewal requirements have matching operational evidence and an owner.
No Docker restart, host reboot or WSL shutdown is included as an implicit remedy.

## WP8. Obtain supported allocation, approved trust and genuine input

**External prerequisites; local integration proceeds when each input arrives.**

- [ ] Locate the supported SC-800 allocation authority/contract/deployment. Launcher forwarding
  and the offline grant issuer are not an automatic allocation implementation.
- [ ] Launch an owned runtime through the supported lifecycle with genuine machine/session/
  executor/artifact bindings. Fetch and hash actual catalog/schema artifacts.
- [ ] Prove allocation, grant, selected endpoint, catalog and authenticated source refer to the
  same intended instance. Test turnover, reconnect/reallocation, expiry and mismatch/refencing.
- [ ] Obtain an exact-artifact bundle accepted by the original configured trust root, or an
  explicit acceptance amendment. A signer can supply the bundle without disclosing its private key.
- [ ] Verify signature/key ID, environment, repository/build/tree, artifact, snapshot and content.
- [ ] Establish real immutable catalog/profile through supported APIs, task/settings/schema/graph/
  curation, BehaviorRunContext and genuinely verified stabilization evidence.
- [ ] Use genuinely eligible finalized evidence, or prepare bounded authorized capture with the
  user's login and required privacy/role/termination metadata. Do not restart 8098 to obtain it.
- [ ] Repeat source-bound creation, idempotency, changed-identity refusal, authenticated reads,
  restart persistence and tamper refusal using genuine inputs.

**Exit:** supported allocation and genuine original-trust input have their own evidence.
Do not promote AUTHORED_FIXTURE, replace canonical trust silently or invent allocation identities.

## WP9. Produce paired packages and finish real client/Watch qualification

**Build preparation can start locally; final client acceptance depends on WP4-8 as applicable.**

- [ ] Build from frozen reviewed source with adapter/build/cache/content/schema/assets inventory.
  Qualify the worker before producing its paired client/package manifest.
- [ ] Run packaged Java/native durable lanes; reject absent/mismatched packages and assets before
  execution. Remove dependencies on unrecorded developer paths/junctions.
- [ ] Attach a real client to the same authoritative world through an approved interaction surface.
- [ ] Exercise authenticated exact-origin Start/Retry/Watch, pause/resume, supported tick controls,
  reconnect/cancel/import/export, one-actor ownership and scoped teardown.
- [ ] Compare bank/widgets/travel/Fishing outcomes and receipts across watcher, camera and disconnect
  variants. Prove observers do not change causal execution.
- [ ] Obtain the required separate human Watch sign-off. Keep LIVE_XRSPS opt-in/non-certifying;
  qualified offline workers remain network-isolated.

**Exit:** paired package identity, real lifecycle/noninterference and human approval pass.

## WP10. Run the final matrix and deliver only authorized changes

- [ ] Freeze final source/configuration/artifact identities; review all multi-repository diffs and
  include intended untracked source/tests in the deliverable. Publishing/merging is separately scoped.
- [ ] Map all original acceptance rows to final evidence, preserving failed, skipped, blocked and
  unrun states. Reuse prior results only when their relevant inputs/coverage remain applicable.
- [ ] Run remaining required compiler/provenance/curation/privacy/auth, native state/method,
  differential/oracle, package, closure/isolation/resource, migration, performance/stress and CI checks.
- [ ] Prepare a concrete rollout for shared services: artifacts, route, protected credentials,
  clock requirement, renewal owner, durable fences, monitoring and component-specific rollback.
- [ ] Obtain deployment authorization, then use documented wrappers. Preserve replay-consumer
  settings and historical attempts; verify effective configuration/routes, health and auth/refusal.
- [ ] Publish Implementation Evidence with Changed files / Tests / Remaining work and Verification
  Evidence with Acceptance results / Tests / Risks / Rollback. Close no story with unmet gates.

**Exit:** delivered inputs satisfy the full required matrix and human/deployment gates, or the
handoff states the exact remaining blockers. Felix/Shortcut stay unchanged unless instructed otherwise.

## Acceptance mapping

This preserves the existing 17-row scope; WP numbers refer to this document.

| Original row | Remaining work/evidence |
|---|---|
| 274-1 | WP1/4/8/10: immutable inputs and fresh-JVM deterministic bytes/checksum |
| 274-2 | WP3/4/8/10: exact provenance and pinned curation |
| 274-3 | WP3/4/10: stable source-linked invalid/incomplete refusals |
| 274-4 | WP4/8/9/10: genuine native Fishing causal execution |
| 274-5 | WP3/4/10: incomplete/legacy causal refusal; retain applicable provenance tests |
| 274-6 | WP4/8/10: immutable privacy/dataset role |
| 274-7 | WP4/10: bounded inert-data import |
| 274-travel | WP3/4/8/9/10: exact bank/travel/Fishing |
| 274-historical | WP3/10: reconciled audit and supported verdict |
| 276-boundary | WP1/4/5/9/10: production xRSPS behind adapter |
| 276-methods | WP4/8/9/10: actual supported production/headless method parity |
| 276-closure | WP1/5/9/10: reproducible loader/package dependency refusal |
| 276-state | WP4/9/10: future-complete deterministic restore on final inputs |
| 276-safety | WP5/10: actual bounded isolated workers |
| 276-live | WP2/7/8/9/10: supported allocation and authenticated opt-in lifecycle |
| 276-client | WP4/8/9/10: genuine input, real client and observer noninterference |
| 276-certification | WP4-10: complete qualification of frozen deliverables |

## Evidence, risks and rollback

Each executed WP records exact files/pins, commands actually run, structured results, logs,
hashes, failure/skip history, scope limits and remaining work in a distinct attempt directory.
Preserve original stage reports and raw capture data. Keep secrets out of that directory.

Stop or remove only ownership-verified experiment resources. Preserve grants/fence volumes;
never lower fence generations or restore expired grants as an availability workaround.
Credential rollback uses fresh protected values, never exposed/destroyed old values. Migration
rollback must be reviewed for the actual supported release; shared restore/reset is not routine.
Reverting test/harness changes must not reintroduce token-bearing argv or default-root writes.

Primary risks remain unreconciled historical metrics, unreviewed dirty/untracked inputs,
unknown released migration identity, host-clock drift, local Docker instability and absent
genuine acceptance inputs. Successful authored tests narrow these risks but do not close them.

## This document update

Changed only this plan and the previous plan's forward link. No product code, service,
database, credential, trust root, replay consumer or tracker state changed. No runtime tests
were executed for this documentation update. Implementation scope and effort remain conditional
on the local investigations and external inputs above.


# SC-274 / SC-276 remaining implementation plan after review

Current remaining plan after the subsequent stage results:
[remaining-implementation-plan-20261022.md](remaining-implementation-plan-20261022.md).
This earlier plan is retained as historical scope and execution order.

Date: 21 October 2026. Status: plan, not execution evidence.

This is the current remaining execution sequence. It supersedes the next-action ordering
and stale baseline statements in [the earlier plan](remaining-implementation-plan-20261021.md).
That plan's 17-row acceptance mapping and the original criteria in
[delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md) remain in force.
Preserve historical results and corrections. No story acceptance row is closed here.

## Baseline already established

| Area | Recorded evidence and boundary |
|---|---|
| Credentials | Purpose-specific rotation 19/19; protected storage and SQL through stdin. Earlier destroyed-token evidence limits remain recorded. |
| Authored native continuation | Run6 persistence 9/9, continuation 1/1, prepare 1/1, resume 1/1; effective artifact roots recorded and default root unchanged. Non-certifying authored scope. |
| Source persistence | Authored positive integration 16/16, legacy unverified refusal 4/4, historical reads 4/4. Not genuine/original-trust acceptance. |
| Owned registry | Pinned backend 58098690b83a3c0914016ddbe69344657401eb47; publication/live grants 24/24, concurrent file reads 50/50 and ONLINE observations 43/43. Operator-issued experiment identities only. |
| Authenticated source route | Corrected container probe 11/11: signature, exact manifest identity, file hash and negative checks. Credentials now enter the HTTP helper through stdin. |
| Discovery correction | ChromaScape 8181 returns 200. The earlier 404 came from querying 8180. Last observed executor was stale/unselectable because its heartbeat/grant expired. |
| Clock helper | Corrected regression suite 8/8 and clock self-test 4/4. Last measured skew 47.287 seconds; live preflight correctly rejected the existing 5-second limit. Production synchronization remains open. |
| Inventories | Authored suite recorded 319 tests with 2 skips. Acceptance and migration inventories exist; inventories are not implementation or acceptance closure. |

Detailed correction evidence:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261021\review-fixes-20261021.md`.
All health, grant and process observations must be refreshed before another mutation.

## Operating boundaries

Read applicable AGENTS in every repository before implementation. Use Windows paths and
the documented Windows service wrappers on this host. Never run `wsl --shutdown`.

Work in owned candidates, disposable databases and labelled experiment resources. Preserve
shared 8090, shared registry/database, canonical trust, replay-consumer settings, historical
attempts and unrelated dirty files. Do not restart stopped 8098 or perform game login.
Credentials stay in the protected store, outside argv, ordinary logs and evidence.

Felix remains skipped by explicit user instruction; no Shortcut/Felix writes or workflow
claims. Shared rollout, trust amendments and human sign-off remain separate decision gates.
Product code changes may still be needed where tests expose real defects; this plan does
not promise that the rest is configuration-only.

## 1. Freeze the current continuation baseline

**Can start locally. Change:** secret-free inventory/evidence only.

- [ ] Record actual Git roots, HEADs, scoped diffs, untracked inputs and applicable project rules.
- [ ] Recheck listener ownership, service command lines, container labels, image identities,
  volumes, current grant generation/expiry and durable fence high-water state.
- [ ] Hash relevant source, packages and existing evidence. Preserve original failures/skips.
- [ ] Create a distinct execution-attempt folder under the V21 verification root; do not
  overwrite original result files or treat historical PIDs as current ownership proof.

**Done when:** each proposed mutation has a verified owner and reproducible input identity.
If Docker is unavailable, record the failure and continue independent source recovery/audits.

## 2. Recover the missing native candidate

**Can investigate locally. Change:** a recovered isolated native worktree and recovery record.

- [ ] Locate the owning repository using earlier native evidence. Inspect worktree metadata,
  branches, reflogs, preserved patches, archives and backups for the missing
  `C:\Dev\IdeaProjects\.worktrees\sc276-completion`.
- [ ] Identify recorded HEAD 1903922a and every recoverable uncommitted change. A clean
  checkout at that commit alone does not recover the previous candidate.
- [ ] Preserve recovery material before recreating any path. Do not prune worktree metadata,
  reset source, overwrite an existing path or silently discard an unrecovered dirty change.
- [ ] Restore into an isolated path, read its AGENTS and inventory production handlers,
  adapter boundaries, snapshot implementation, build inputs and worker entry point.
- [ ] Run the smallest relevant build/smoke checks and compare recovered inputs with run6.

**Done when:** source and dirty-work provenance are recoverable and recorded, or an exact
list of missing inputs is documented. New source work must not be called the recovered pin.

## 3. Audit the historical recording

**Can investigate locally; execution requires the actual recording and pinned decoder.**

- [ ] Locate and hash the original recording and identify the decoder/version used by its gate.
- [ ] Decode read-only and retain raw input, command, decoder identity and structured output.
- [ ] Audit ordered actions/observations, gaps, tick offsets, regions/planes, initial state,
  bank widget/item/slot/quantity, travel destinations/charges and the transition to Fishing.
- [ ] Classify observed, derived, operator-provided and absent fields. Never supply missing
  observations merely to make causal replay eligible.
- [ ] Add a source-linked regression for any actual decoder/compiler defect and rerun affected tests.

**Done when:** the historical acceptance row has a supported result, or a precise blocked
verdict identifying missing evidence. Missing capture content cannot be repaired by guessing.

## 4. Establish released migration history, then reconcile collisions

**Can investigate locally. Code changes depend on a conclusive released baseline.**

- [ ] Trace release tags, deployment artifacts and available release records for compiler,
  canonical branch and registry. Establish supported upgrade starting versions.
- [ ] Compare migration contents/checksums for V47-V49 and V58-V60. The inconsistent shared
  development Flyway table is diagnostic evidence, not authority for released numbering.
- [ ] Produce a migration mapping showing released files, unreleased candidate files, intended
  schema and data invariants. If release authority is unavailable, record exactly what is needed.
- [ ] Implement forward-compatible corrections. Renumber only files proven unreleased; preserve
  released checksums/history. Do not use Flyway repair or shared database resets as a shortcut.
- [ ] Test empty installation and every supported upgrade baseline on disposable databases,
  including registry bootstrap, data preservation, constraints, refusal and recovery.

**Done when:** released compatibility is established and clean install/upgrade tests pass,
or the unresolved release-baseline prerequisite is explicit. Schema-copy bootstrapping is insufficient.

## 5. Qualify clock safety and refresh owned experiments only as needed

**Local diagnosis is actionable; disruptive synchronization changes require separate authorization.**

- [ ] Measure host, Docker VM/container, registry and issuer clocks with uncertainty; inspect
  supported time-sync configuration read-only. Identify the source of drift.
- [ ] Test a supported non-disruptive remedy if available. Do not restart shared Docker,
  reboot the host or disable WSL forwarding under this plan.
- [ ] Establish the operational skew requirement and renewal owner. Keep the existing preflight
  limit unless a reviewed requirement changes it; do not relax limits merely to pass.
- [ ] Retain remaining-time threshold = skew allowance + publication/retry latency + safety;
  latest elapsed renewal start = TTL minus that threshold. Protocol TTL stays at most 120 seconds.
- [ ] When a later test needs an online experiment, use the documented owned issuer/heartbeat
  flow, correct generation and protected credentials. Never invent real allocation identities.
- [ ] Recheck exact endpoint_ref/selectability, renewal retries, expiry, issuer restart,
  revocation, atomic publication and durable replay fencing when relevant inputs change.

**Done when:** clock requirements and matching operational evidence exist. Experiment grant
renewal is optional test setup and does not close SC-800 allocation or genuine selection gates.

## 6. Finish compiler/native behavior and worker qualification

**Authored work can proceed after native recovery; genuine claims also need steps 7-8.**

- [ ] Use the existing acceptance-to-test inventory to select actual gaps. Retain applicable
  prior passes; implement only demonstrated missing semantics or enforcement.
- [ ] Verify deterministic compiler bytes/checksum, pinned curation and field-level provenance,
  immutable privacy/dataset role, bounded inert import/export and stable source-linked refusals.
- [ ] Prove production/headless parity for bank/travel/Fishing and every advertised supported
  method, including quantities, resources, charges, timing, effects and interruption.
- [ ] Prove stale/invalid/unsupported actions mutate nothing; separate initial dynamic state,
  static content and spawn rules. Resolve later targets at their execution ticks.
- [ ] Verify future-complete snapshots: pending actions, queues, timers, RNG, time, world,
  source cursors and stop state. Repeat fresh-worker and cross-JVM lanes after relevant changes.
- [ ] Measure actual production cold/warm startup and transport budgets. Authored 180-second
  transport/3600-second run budgets do not qualify production performance.
- [ ] Inventory loader/module/asset/cache/content closure. Exercise actual egress, filesystem,
  process/thread, cancellation, CPU/memory/time enforcement with deliberate violations.

**Done when:** applicable production behavior, durable state, dependency refusal and real
isolation/performance have evidence on the final pin. Mocks and authored fixtures stay labelled.

## 7. Obtain supported allocation and real catalog selection

**External prerequisite:** supported SC-800 allocation authority/deployment or an explicitly
reviewed mechanism with a stated acceptance scope. Launcher forwarding is not allocation.

- [ ] Confirm the owning allocation service/contract and required machine/session/executor IDs.
- [ ] Start an owned runtime through the supported Launcher lifecycle and retain actual bindings.
- [ ] Fetch real catalog/schema artifacts, verify their immutable hashes and replace fixture
  claims for this lane. Validate the endpoint itself, not just heartbeat metadata.
- [ ] Resolve through isolated ChromaScape and prove allocation, grant, catalog, source and
  selected runtime identify the same artifact and instance.
- [ ] Test turnover, reconnect/reallocation, expiry, mismatch refusal and restart fencing.

**Done when:** supported allocation and real catalog discovery pass. Offline operator-issued
experiment grants cannot substitute for this result.

## 8. Obtain approved trust and genuinely eligible input

**External prerequisite:** approved exact-artifact signed bundle, or an explicit acceptance
amendment; user participation for authorized login/capture where required.

- [ ] Obtain a bundle accepted by the original configured trust root. The signer can supply
  the bundle; the matching private key need not be disclosed to this host.
- [ ] Verify signature/key ID, environment, repository/build/tree, artifact, snapshot and content.
- [ ] Establish real catalog, immutable profile through the supported API, task/settings/schema/
  graph/curation and BehaviorRunContext. Use genuinely verified stabilization evidence.
- [ ] Select genuinely eligible finalized evidence, or prepare bounded authorized capture with
  the user's login. Preserve ordered evidence, termination, consent/privacy and dataset role.
- [ ] Repeat source-bound creation, idempotency, changed-identity refusal, authenticated reads,
  restart persistence and tamper refusal using these genuine inputs.

**Done when:** genuine acceptance has its own evidence. Do not promote AUTHORED_FIXTURE,
replace canonical trust silently or mark fixture stabilization as genuine.

## 9. Package the paired worker/client and complete Watch

**Depends on:** recovered and qualified native inputs, applicable behavior/migrations, supported
allocation and approved source where required by the acceptance row.

- [ ] Build from frozen reviewed source; record worker/client, adapter, cache, content, schema,
  assets and loader closure. Qualify the worker before publishing its paired manifest.
- [ ] Run packaged durable lanes and reject absent/mismatched dependencies before execution.
- [ ] Attach the real client to the same authoritative world. Exercise authenticated exact-origin
  Start/Retry/Watch, pause/resume, supported tick controls, reconnect, cancel and scoped teardown.
- [ ] Compare bank/widgets/travel/Fishing outcomes and receipts across watcher, camera and
  disconnect variants. Prove one-actor ownership and observer noninterference.
- [ ] Obtain the required human Watch sign-off. Keep LIVE_XRSPS opt-in and non-certifying;
  qualify offline worker network isolation separately.

**Done when:** paired package identity, actual client lifecycle and human approval have evidence.

## 10. Freeze the final matrix and prepare authorized delivery

- [ ] Map every original acceptance row to final pins, commands/results and precise limits.
  Keep blocked, failed, skipped and unrun rows distinct; do not reduce the original 17-row scope.
- [ ] Run the remaining affected compiler, provenance, native, differential/oracle, packaged,
  migration, isolation, performance/stress and required CI checks. Prove deliberate regressions fail.
- [ ] Prepare the concrete shared rollout: reviewed diffs, artifacts, route, protected credentials,
  operational renewal owner, clock requirement, monitoring, fences and component rollback.
- [ ] Obtain shared deployment authorization only when that rollout is concrete and reviewable;
  execute through documented wrappers, preserving replay settings and unrelated resources.
- [ ] Verify effective routes/configuration, health/auth/refusal, migration identity and operational
  behavior. Publish Implementation Evidence and Verification Evidence with all remaining limits.

**Done when:** all applicable gates pass for delivered inputs and required human/deployment
decisions are recorded. Otherwise deliver a truthful partial handoff; do not close the story.

## Execution order and evidence contract

Start with 1 -> 2 -> 3 -> 4. Continue step 5 diagnosis and step 6 independent qualification
as their inputs become available. Steps 7 and 8 are prerequisite requests, not reasons to
stop local work. Step 9 integrates the qualified inputs; step 10 is the final delivery gate.
Use this sequence without spawning parallel agents unless separately instructed.

Each stage records exact changed files, source/artifact pins, commands actually run, result
files, failures/skips, risks and remaining work in a distinct V21 attempt folder. Preserve
original artifacts and failed attempts. Stop only ownership-verified experiment resources.
Keep grants/fence volumes and never lower the generation high-water state. Credential rollback
uses fresh protected values, never restoration of exposed or removed secrets.

This update changes documentation only. It executes no recovery, migration, service restart,
credential rotation, product test or deployment. Effort remains uncertain until recovery and
release-baseline investigations expose the remaining implementation scope.

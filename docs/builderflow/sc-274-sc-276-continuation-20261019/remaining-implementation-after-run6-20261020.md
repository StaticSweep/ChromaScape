# SC-274 / SC-276: full remaining implementation plan after run6 review

Current sequence after WP1-WP6 review (21 October):
[full remaining implementation plan](remaining-implementation-plan-20261021.md).
It preserves completed scoped evidence and adds the grant-publication and authenticated-route
verification gaps; older next-action summaries below are historical.

Date: 20 October 2026. Status: remaining work, not an implementation-completion claim.

This is the current execution plan after review of the credential and artifact-isolation
increment. It supersedes older next-action summaries. Preserve the original
[17 acceptance rows](delivery-plan-after-step2-review.md) and the detailed file ownership in
[implementation-plan-20261020.md](implementation-plan-20261020.md). No acceptance is waived.

## Current baseline: do not redo completed work without cause

| Area | Current evidence and limit |
|---|---|
| Credential implementation | Protected store ACL and stdin SQL handling reviewed. Purpose-correct rotation complete for the owned 8181 experiment: 19/19 checks pass, each retired token fails with 401 on its own route/header, each replacement passes the same context, write/capture probes make zero writes. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020\purpose-rotation-20261020.md`. Attempt-1 destruction gap retained. |
| Native test artifact isolation (WP2) | Corrected unconditional binding and effective-root assertions; run6 persistence9/9, continuation1/1, prepare1/1, resume1/1; archived XML hashes match. Default root unchanged. No blanket rerun required. |
| Native continuation | Three authored variants, fresh workers, cross-JVM restore/recovery and HTTP artifacts evidenced. Non-certifying; not production performance, parity or sandbox qualification. |
| Post-fix provenance | Legacy verified=false PASS refused with422 and zero writes, repeatable. Historical pre-fix debugger reads4/4. Authored-fixture positive path now passes16/16 through the supported report-bundle route (`AUTHORED_FIXTURE`, source-bound, idempotent, changed-identity refusal, restart persistence, durable tamper refusal). Genuine/original-trust positive admission remains open. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020\positive-source-integration-20261020.md`. |
| Registry grants | Linux mechanism harness passes. Owned Linux registry now runs on `127.0.0.1:8182` from pinned5809869 with owned PostgreSQL, read-only grants file, protected auth token and durable fence volume. Live operator grants pass23/23; ChromaScape's real registry client resolves the intended endpoint. Launcher allocation/selection8182 remains open. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020\registry-and-live-grants-20261020.md`. |
| Compiler | Authored compiler/supporting tests passed; genuine-input and full final acceptance remain open. |
| Shared deployment | Token/provenance changes are not assumed active on shared8090. Shared rollout remains separate. |

Evidence roots: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019` (V19)
and `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020` (V20).
Preserve failed run5 and prior rotation attempts with their actual limitations.

## Safety and execution boundaries

- Never run `wsl --shutdown`.
- Preserve shared8090, shared registry/database, canonical trust and existing replay attempts.
  Keep replay consumers disabled. Shared rollout requires concrete reviewed authorization.
- Do not restart the stopped8098 session or perform game login implicitly.
- Reinspect process command lines, container IDs/labels, paths and ports before mutations.
- Keep authored fixtures, development signatures, genuine capture and certification distinct.
  Never manually promote fixture eligibility or invent allocation/provenance.
- Preserve unrelated dirty changes and original failed evidence. Use isolated builds/state.
- Felix explicitly stays skipped. No Shortcut/Felix writes, markers or task-state claims.

## 1. Finish purpose-correct credential-rotation verification (remaining WP1)

**Change:** V19 `relaunch-8181-phase3.py` and its verification helpers. Retain the reviewed
`experiment_secrets.py` ACL store and stdin SQL implementation unless a new defect appears.

- [x] Replace the loop that sends every retired token to `manifest_status` with a purpose-to-
  request mapping based on the actual controller/filter contract.
- [x] For each purpose, show the old token fails on the route/header where it previously had
  authority, paired with the new token reaching the authorized path under the same context.

| Token purpose | Verification surface |
|---|---|
| debugger_metadata | Existing historical manifest or symbol read with valid metadata audit context |
| debugger_content | Existing file read with valid content audit context and correct content hash |
| stabilization_read | Protected stabilization read with its actual auth header |
| stabilization_write | Supported protected write using a bounded request guaranteed to make zero writes; confirm counts unchanged |
| capture | Protected capture/run-create path using its actual auth header; an authorized business refusal such as STABILIZATION_NOT_VERIFIED may be expected; confirm zero writes |

- [x] Distinguish authentication failure from validation/business failure. A new token need not
  receive200 on a deliberately non-writing request, but must demonstrably pass authentication.
- [x] Current retired values may already be destroyed. Do not reconstruct them or claim retroactive
  proof. If necessary, perform one additional scoped rotation using the current protected tokens
  as the known predecessor; preserve the older gap explicitly.
- [x] Keep all token values out of argv/logs/evidence. Check ACLs before reads and writes.
- [x] Archive route, purpose, safe request metadata, expected/actual status and zero-write counts.
  Retire obsolete files only after required checks; retain secret-free historical records.

**Done when:** all five purposes have meaningful old/new checks, or an exact historical gap is
retained without a false retirement claim. No native test rerun is required for this helper change.
Rollback uses fresh protected credentials, never restoration of exposed values.

**Completed 20 October 2026:** 19/19 checks. Attempt 3 used the current protected values as the
known predecessor; the attempt-1 destroyed-token gap remains explicit. Evidence is
`work-package-1-purpose-rotation.json` and `purpose-rotation-20261020.md` in
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020`. Current-token reruns:
post-restart reads4/4, provenance refusal4/4.

## 2. Complete eligible post-fix positive source integration (remaining WP3)

**Change:** owned8181 test/configuration first; product services only for demonstrated defects.

- [x] Recheck exact candidate/mirror source and running build identity before any rebuild.
- [x] Inspect supported verified report-bundle ingestion and prerequisites for a legitimate,
  explicitly labelled development positive test. An approved original-trust bundle is a separate
  genuine-acceptance requirement; do not conflate it with every development integration test.
- [x] If supported eligible input is available, create it through its normal API, not direct SQL
  `verified=true`. Preserve AUTHORED_FIXTURE/development scope where applicable and refuse any
  route that cannot legitimately produce the required prerequisite.
- [x] Run source-bound creation, exact persisted identities, idempotency, changed-identity refusal,
  authenticated manifest/file/symbol reads, restart persistence and durable tamper refusal.
- [x] Preserve the passing legacy refusal and historical-read results. Rerun affected tests only
  if the service/input changes; label pre-fix historical rows separately.
- [x] If no eligible prerequisite exists, record the precise missing producer/input as blocked.
  Do not manufacture a passing row or claim positive-path completion.

**Done when:** positive-path behavior is evidenced for its legitimate scope, or a bounded
investigation establishes a specific prerequisite blocker. Original-trust/genuine admission
does not pass merely because development integration does.

**Completed 20 October 2026 (authored scope):** the supported report-bundle route accepted a
complete `AUTHORED_FIXTURE` bundle built from the real owned 8180 source identity. 16/16 checks
pass for source-bound creation, exact persisted identities, bundle/run idempotency,
changed-identity refusal with 409 `SOURCE_BUILD_MISMATCH`, authenticated reads, restart
persistence and durable content tamper refusal. Genuine/original-trust admission remains open.
Evidence: `work-package-3-positive-source-integration.json` and
`positive-source-integration-20261020.md` in
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020`.

## 3. Implement the owned Linux registry deployment and runtime route (WP4)

**Locations:** `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1` pinned to
`58098690b83a3c0914016ddbe69344657401eb47`; owned deployment artifacts under a new V20 increment.

- [x] Read pinned backend rules/operations and inventory required database/auth/startup consumers.
- [x] Write a concrete route design specifying actual container source, Windows destination,
  runtime listener, permitted clients, grant-origin constraints, authentication and teardown.
- [x] Probe from the actual Linux network context. Neither container localhost nor an assumed
  host alias proves access to Windows loopback-only8180.
- [x] Prefer a separate owned runtime/listener on a narrowly scoped reachable interface if needed.
  Preserve8180. Any relay must comply with origin/identity policy and avoid general forwarding.
  Do not weaken grant validation or broaden existing bind addresses implicitly.
- [x] Provision owned dependencies, protected secret, atomic grants publication path and durable
  fences. Run Linux registry with intended host loopback exposure on free8182 and ownership labels.
- [x] Verify auth, readiness and reachability before registering runtime claims.

**Done when:** actual owned registry and intended runtime route work, with repeatable startup,
ownership and rollback. This is locally actionable design/deployment work, not automatically an
external blocker. Stop only owned resources; preserve fence high-water state.

**Completed 20 October 2026:** image built from pinned5809869; owned PostgreSQL/registry on an
owned network; only `127.0.0.1:8182` published; protected token auth; schema-only owned DB
bootstrap; probed container-to-Windows8180/8181 route; 11/11 deployment checks. The route design and
commands are in `registry-and-live-grants-20261020.md`; evidence is
`work-package-4-registry-deployment.json`.

## 4. Complete live operator-issued grants (WP5)

- [x] Use documented issuer and labelled experiment identities against live8182.
- [x] Drive isolated ChromaScape's real registry client and verify returned routes identify the
  intended instance. Direct source HTTP success is not runtime-selection proof.
- [x] Test <=120s TTL, timely renewal, atomic replacement, revocation, missing file/key, issuer
  failure and wrong signature/allocation/machine/session/executor/account/artifact/environment/origin.
- [x] Restart retaining fences; reject stale generation/replay and accept current valid claims.
- [x] Prove renewal stoppage fails closed and record finite soak/reconnect observations.

**Done when:** live grant integration passes. Existing mechanism tests need rerunning only for
changed relevant inputs. Experiment renewal tooling does not complete SC-800 automatic issuance.

**Completed 20 October 2026:** 23/23 live checks: signed DEV grant, ONLINE selection through
ChromaScape's real client, 10-iteration atomic replacement soak, generation-2 renewal, restart
with fence retention, generation-1 replay refusal, revocation via replacement snapshot, missing
file fail-closed, issuer empty-key/public-origin/unverified-artifact/wrong-allocation refusals,
tampered signature and wrong machine/session/executor/account/environment refusals, short-expiry
renewal-stoppage fail-closed, final generation10 ONLINE. The pinned mechanism harness passes7
TestF1 scenarios plus the issuer package (`TestF1ChildResolve` is child-only and reported SKIP in
the parent process). Docker VM clock skew (~45-50s ahead of Windows) shortens the effective TTL
and is recorded as a risk. Evidence: `work-package-5-live-grants.json`,
`work-package-5-mechanism-harness.log` and `registry-and-live-grants-20261020.md`.

## 5. Prove supported Launcher allocation and selection (WP6)

**Change:** supported Launcher/runtime configuration first; inspect existing registration contract.

- [ ] Start a separate owned runtime through Launcher rather than inventing identity for8180.
- [ ] Obtain actual allocation and forwarded machine/session/executor identity; bind grants to
  real session, artifact, origin, environment and generation.
- [ ] Verify authenticated heartbeat freshness/expiry, ownership and complete catalog/schema/build.
- [ ] Select via isolated ChromaScape registry discovery. Source, metadata and catalog must resolve
  to the same allocated instance. Reject stale and mismatched records.
- [ ] Test reconnect/reallocation/restart and recovery through supported lifecycle paths.

**Done when:** properly allocated runtime selection passes. Ask for login only where a real
contract or capture requires it; do not use login as an HTTP-testing prerequisite by assumption.

**Investigated 20 October 2026; blocked on supported allocation input.** The Launcher's Micro6
probe/heartbeat code reads and forwards `endpoint_ref`/`allocation_id`/`fencing_generation` but
does not allocate them. The pinned mule-service consumes a matching signed grant but has no
Micro6 allocation route; `cmd/endpoint-grants` is the offline operator issuer, not SC-800
automatic issuance. The owned `sc274-registry-machine-01` identity is an experiment identity,
not a Launcher allocation. No allocation was invented and no login was used. Details:
`launcher-allocation-investigation-20261020.md` in
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020`. WP6 remains open.

## 6. Resolve approved trust and genuine evidence (WP7)

**External inputs:** approved signer/deployment or explicit acceptance amendment; authorized
genuine capture/profile inputs and user login where required.

- [ ] Obtain a signed bundle accepted by the original configured root and matching the exact
  client artifact, or document an explicit reviewed acceptance amendment. No silent root replacement.
- [ ] Verify key/signature/environment/repository/build/tree/snapshot/artifact/content pins.
- [ ] Obtain genuine catalog and operator authorization; append immutable profile through SC-641 API.
- [ ] Pin task/settings/schema/graph/curation and bind real BehaviorRunContext.
- [ ] Reuse eligible finalized capture or prepare authorized capture with user login. Preserve
  action/observation order, receipts, termination, privacy and dataset role.

**Done when:** genuine immutable input has all prerequisites for its claimed scope. An approved
bundle may come from an approved signer; copying its private key to this host is unnecessary.

## 7. Complete compiler and native behavior gaps (WP8)

**Owning files:** compiler/evidence/preflight/provenance/curation/import contracts and tests in
the compiler candidate; production/headless handlers, world loading and snapshot/adapter code
in `C:\Dev\IdeaProjects\.worktrees\sc276-completion`.

- [ ] Create an acceptance-to-test gap list before new implementation; preserve applicable passes.
- [ ] Prove identical immutable inputs/compiler version yield identical manifest bytes/checksum.
- [ ] Audit every field/action to exact source or authorized fixture and pinned curation with
  explicit observed/derived/operator/default provenance.
- [ ] Keep initial dynamic state, static data and spawn rules separate; resolve targets at their
  execution tick across regions/planes without injecting later observations into reset.
- [ ] Verify exact bank/widget/slot/option/quantity, jewelry charge/destination, exchange and
  bank-to-travel-to-Fishing semantics. Implement only concrete missing behavior.
- [ ] Exercise gaps/offsets/reordering/duplicates, absent or ambiguous state, incompatible cache/
  schema and legacy incomplete evidence. Require stable source-linked refusal; playback is not eligibility.
- [ ] Verify immutable role/privacy and bounded inert-data import/export. Reaudit original historical
  recording with pinned decoders; preserve precise blocked verdict where evidence is insufficient.
- [ ] Compare every advertised Fishing/glassblowing/Magic/combat/auxiliary method with production
  requirements, resources, quantities/charges, timing, XP/effects, interruption and combat state.
- [ ] Verify stale/invalid/unsupported action refusal mutates nothing. Snapshots include pending
  work, queues/timers/RNG/time/world/source cursors/terminal state. Rerun affected continuation tests
  after changing handlers or state, not simply because the plan advanced.

**Done when:** all applicable positive/negative compiler/native cases have final evidence;
genuine-source claims still depend on6. This work can proceed in authored scope before6.

## 8. Qualify production performance, closure and isolation (WP9)

- [ ] Measure cold/warm startup and requests through the actual production transport/configuration.
  Keep180s transport/3600s budgets labelled authored-only. Investigate slow startup against explicit
  requirements rather than silently increasing production limits.
- [ ] Inventory modules/handlers/scripts/assets/content/cache and dynamic loading dependencies.
- [ ] Reject modified/missing/unexpected/aliased/symlinked/out-of-closure inputs before execution;
  preserve last supported package pin.
- [ ] Exercise enforced egress denial, read/write boundaries, process/thread restrictions,
  cancellation and CPU/memory/time budgets on actual cold/fresh/parallel workers.
- [ ] Use deliberate violations/regressions. Fix owning enforcement where absent; mocked refusal
  or authored Windows transport is not production OS isolation evidence.

**Done when:** production-path limits and actual process/OS boundaries are demonstrated.

## 9. Implement supported migration/deployment compatibility (WP10)

- [ ] Inventory actual released histories, missing production tables and reused V58-V60 numbering.
- [ ] Design and implement only necessary forward-compatible migrations/checks. Never rewrite
  released history, reset shared stores or treat schema-copy success as migration proof.
- [ ] Test empty installation and supported existing-history upgrades on disposable owned databases,
  with strict schema validation, constraints, preserved data and failure behavior.
- [ ] Run scoped formatting/style checks without rewriting unrelated pre-existing violations.

**Done when:** supported install/upgrade paths pass against reviewed schema identities. Locally
actionable diagnosis and owned tests can proceed while trust/capture is blocked.

## 10. Package paired worker/client and verify real lifecycle (WP11)

- [ ] Build from reviewed clean inputs with full adapter/build/cache/content/schema/asset inventory.
  Qualify worker before generating release/client manifest.
- [ ] Refuse absent/mismatched packages/assets before execution and run packaged Java/durable lanes.
- [ ] Establish approved computer-use surface and real client attached to the same authoritative world.
- [ ] Exercise authenticated exact-origin Start/Retry/Watch, pause/resume, supported tick controls,
  reconnect/cancel/import/export, one-actor lifecycle and scoped teardown.
- [ ] Compare structured state/receipts for bank/travel/Fishing and watcher/camera/disconnect variants
  for identical causal outcomes. Obtain separately required human Watch sign-off.
- [ ] Keep LIVE_XRSPS opt-in/non-certifying and offline qualified workers network-isolated.

**Done when:** paired package identity, actual lifecycle and noninterference pass, including human gate.

## 11. Final matrix and authorized shared rollout (WP12)

- [ ] Freeze final source/config/artifact pins and run required compiler/provenance/curation/privacy/
  auth, native state/method/closure/isolation/resource/negative, packaged, differential/oracle,
  performance/stress and CI matrices. Prove deliberate regressions are detected.
- [ ] Reuse old results only for unchanged relevant inputs/coverage. Archive commands, durations,
  XML/result hashes, failures/skips and unrun checks; do not infer certification from selected passes.
- [ ] Prepare concrete shared8090/registry deployment with route, protected credential ownership,
  operational renewal, durable fences, monitoring and per-component rollback.
- [ ] Obtain shared deployment authorization; use documented Windows wrapper and preserve replay
  consumer disablement/historical attempts. Verify effective configuration, outbound destinations,
  auth/refusal behavior and service health after rollout.
- [ ] Review scoped multi-repository diffs. Provide Implementation Evidence (Changed files/Tests/
  Remaining work) and Verification Evidence (Acceptance results/Tests/Risks/Rollback).
- [ ] Reconcile all17 acceptance rows before closure; publish/merge only reviewed authorized work.

**Done when:** required final evidence and authorized delivery pass, or an explicit partial handoff
retains each blocker. Felix remains skipped and Shortcut unchanged unless explicitly re-enabled.

## Acceptance mapping

Numbers below refer to the remaining steps in this document, not earlier phase numbering.

| Original row | Remaining evidence |
|---|---|
|274-1|6,7,11: immutable-input deterministic bytes/checksum|
|274-2|2,6,7,11: exact source/fixture provenance and curation|
|274-3|7,11: stable source-linked invalid/incomplete-input failures|
|274-4|5,6,7,10,11: native Fishing causal execution|
|274-5|2,7,11: incomplete/legacy causal refusal|
|274-6|6,7,11: immutable privacy and dataset role|
|274-7|7,11: bounded inert import|
|274-travel|6,7,10,11: exact bank/travel/Fishing|
|274-historical|7,11: original evidence or precise blocked verdict|
|276-boundary|7,8,10,11, plus applicable run6 evidence: real xRSPS behind adapter|
|276-methods|7,10,11: actual production/headless parity|
|276-closure|8,10,11: dependency/package refusal|
|276-state|7,11, plus applicable run6 evidence: complete deterministic restore|
|276-safety|8,11: enforced isolated bounded workers|
|276-live|5,10,11: authenticated opt-in lifecycle|
|276-client|6,7,10,11: actual client, noninterference and genuine capture|
|276-certification|7-11: complete frozen-input final qualification|

## Immediate order, blockers and completion discipline

Next:5 supported Launcher allocation and selection (WP1-WP5 complete for their current scope).
Approved trust/genuine input6 is an external gate for genuine claims. Authored behavior7,
performance/isolation8 and migration9 remain locally actionable while external input waits.
Package/client10 depends on relevant state/behavior/isolation qualification; final11 runs on frozen
deliverables.

For each implementation failure: preserve exact error/input, identify configuration versus
implementation cause, apply the smallest justified change and rerun affected checks. Do not
broaden exposure, fabricate data, relax provenance/guards or extend production budgets merely
to obtain green results. No promise of zero further product-code changes is justified yet.

This writing increment is documentation only: no credential rotation, builds, tests, runtime/
database/deployment operations or tracker transitions occur by creating this file.

# SC-274 / SC-276 current remaining delivery plan

Latest after WP1-WP6 review: [remaining implementation plan, 21 October](remaining-implementation-plan-20261021.md).
Use that execution sequence; preserve earlier evidence and the original17 acceptance rows.

Current after run6 review: [full remaining implementation plan](remaining-implementation-after-run6-20261020.md).
It retains WP2's evidence and identifies the remaining purpose-specific token-rotation gap.

Latest implementation sequence (20 October, after phase1-3 review):
[remaining implementation plan](implementation-plan-20261020.md). It includes the credential
and non-restart artifact-root fixes and supersedes older next-action summaries below.

Practical checklist: [step-by-step execution plan](step-by-step-execution-plan.md), including
what to configure, which tests to run, when a code change is justified and each exit criterion.

Date: 19 October 2026. This is the current execution order after stage E persistence.
It supplements `delivery-plan-after-step2-review.md`, retaining its original scope,
17 acceptance rows, exclusions and A-N stage definitions. Earlier results remain dated
evidence, not blanket acceptance of subsequent inputs.

## Scope, boundaries and present state

Deliver deterministic provenance-preserving SC-274 scenario compilation and its linked
real xRSPS SC-276 execution, recovery, isolation and client/Watch requirements.
Development integration evidence does not replace original configured trust or genuine
capture requirements. Never run `wsl --shutdown`. No automatic game login. Preserve
unrelated dirty work, original archives, shared service/database and existing replay work.
Keep shared replay consumers disabled. No shared rollout is authorized by this planning
step. Felix is explicitly skipped; no tracker writes or transitions are implied.

Recorded completed increments:

- Item 1 registry reachability workaround: qualified by soaks and cold connections;
  finite evidence isolates the loopback/relay path, not its internal defect or permanent repair.
- Selected Micro6 revision 429e32978b: isolated home/state, separated preparation/signing
  from allowlisted serving, readiness and source/security tests. Repeat prepare/serve passed,
  but reused up-to-date compilation/JAR outputs; forced clean rebuild remains unrun.
- Purpose-specific outbound source tokens and startup validation implemented in candidate
  source; 32 focused tests and 15-case live trust harness passed. Not deployed on shared 8090.
- Stage E development-fixture source binding, debugger reads, persistence/restart and tamper
  refusal evidenced. Phase 1 has 30/31 original checks with an audit-query scope correction;
  preserve the original failure and correlation-scoped evidence rather than relabel 31/31.
  Phase 2 11/11 is console evidence only; tamper phase is 9/9. No Gradle rerun for that increment.
- Last recorded process observations: 8180 PID171232; owned 8181 PID172692; shared8090
  PID195400. Reinspect command lines before any operation; PIDs are not durable ownership.
  8098 remains stopped; do not restart a formerly logged-in session implicitly.

Evidence: `stage-e-persistence-progress-20261019.md`,
`startup-validation-and-full-service-progress.md` and linked external logs/scripts.

## Execution status update — 19 October 2026

- **Phases 1-3 of the step-by-step plan executed.** Frozen hashes, the same-JVM and cross-JVM
  native lanes (9/9 persistence, 1/1 continuation, 1/1 prepare, 1/1 resume; 3 variants,
  4 recoveries, 23 downloads) and the owned-8181 provenance relaunch/refusal are recorded in
  `phase1-3-execution-results-20261019.md`. The authored lane needed a bounded 180 s transport
  deadline and dynamic run-budget/artifact-root properties; the provenance gate now returns
  `422 STABILIZATION_NOT_VERIFIED` with zero new rows. Phases 4-10 remain blocked or
  unauthorized as described below. No acceptance row is closed.

- **Step 1 complete.** Evidence hashes are frozen in
  `step1-reconciliation-and-fixture-eligibility-20261019.md`. The audit found and fixed a real
  boundary defect: `CurrentSliceRunService` accepted a legacy `verified=false` PASS row and
  bound it to a genuinely verified source. It now requires `verified=true`
  (`422 STABILIZATION_NOT_VERIFIED` otherwise), with three focused refusal/acceptance tests.
- **Step 2 mechanism verified, live 8182 gate open.** Revision `5809869` was pinned in a clean
  detached worktree. Native Windows hosting is unsupported because the fence store's
  `directory.Sync()` returns `Access is denied`; the pinned tests fail natively with
  `ENDPOINT_FENCE_STORE_UNAVAILABLE`. The unmodified pinned source plus an additive harness
  passes on Linux, covering validity, 120 s TTL, same-generation renewal, atomic whole-file
  replacement, expiry, revocation, identity/route/signature mismatch, generation fencing and
  fresh-process replay rejection. No live owned registry was started on 8182. Evidence:
  `step2-f1-grant-mechanism-20261019.md`.
- **Steps 3-4 remain blocked.** No Launcher allocation or genuine runtime was produced; the
  owned 8180 runtime is direct-launched and unregistered. No approved original-trust bundle or
  acceptance amendment exists, and genuine capture requires user login.
- **Step 5 authored checks pass.** `ScenarioManifestV2CompilerTest` plus the evidence,
  import, selected-preflight, run-context and NPC-binding classes pass. These are authored
  tests, not genuine-source acceptance.
- **Step 6 partially executable.** `replayNativeCheckpointPersistenceTest` passes (authored,
  non-certifying). `replayNativeDatabaseContinuationTest` fails closed at its prerequisite
  gate: no `SC276_NATIVE_CHECKPOINT_JDBC_URL`, input/output paths or `SC274_NATIVE_REPLAY_ROOT`
  were configured for that run. Follow-up inspection found the worker at
  `C:\Dev\IdeaProjects\.worktrees\sc276-completion\server\src\replay\worker-main.ts`
  (6523 bytes; worktree HEAD `1903922a1132e4a9f30dd6c630510b7eb523fc75`), with a local
  tsx installation. The canonical checkout lacks that file. The workspace-wide absence
  claim was incorrect. Candidate package/closure/cache identity and owned database/input
  preparation still need qualification; native parity/refusal/continuation remain unproved.
- **Steps 7-10 remain open, with local work available.** Inspect and qualify the existing
  native candidate before treating worker/closure tests as externally blocked. Migration
  diagnosis and owned installation/upgrade tests can proceed independently. Human Watch
  evidence and shared rollout authorization remain separate gates. Whole-tree Spotless
  has reported pre-existing failures; check only changed files without rewriting the tree.

## 1. Freeze the current evidence and reconcile stage E

Reinspect owned processes, selected source/package identity and database ownership. Record
source/config diffs and evidence hashes without exposing secrets. Retain the audit correction,
console-only restart results and the failed oversized-command attempt, which changed no rows.
If a machine-readable clean rerun is needed, rerun only affected owned cases and write results
incrementally so a later crash does not discard earlier evidence.

Audit provenance boundaries: real signed source bytes are distinct from AUTHORED_FIXTURE
run/profile/attempt identities. Confirm source `VERIFIED` never upgrades a legacy unverified
stabilization fixture into captured evidence or causal certification. Inspect the eligibility
decision and downstream role/privacy propagation; add meaningful refusal tests if a gap exists.
This is a required boundary review, not a claim that a defect has been found.

Exit: corrected results are traceable and fixture evidence cannot satisfy genuine-input gates.

## 2. Qualify operator-issued grant mechanics on owned 8182 (stage F1)

User decision: proceed with a labelled operator-issued experiment, not shared deployment.

1. Resolve and pin deployed revision **5809869** from `codex/canvas-entrypoint-20261016` in
   a separate clean checkout. Read its applicable project rules. Do not test the unrelated
   current checkout 6a687de as if it were deployed code.
2. Inspect native Windows support, startup consumers, dependencies, storage and auth. Prefer
   owned native DEV registry on127.0.0.1:8182 so it can reach8180 without broadening its bind.
   If unsupported, qualify a scoped container-to-host route separately; Docker localhost is
   not Windows localhost. Bind/auth changes need their own explicit review.
3. Allocate owned secret, grants file and durable fences directory. Use documented issuer
   and experiment identities, explicitly labelled operator-issued. Do not reuse shared
   credentials/state or represent test identities as Launcher allocation.
4. Test valid grant acceptance, lifetime <=120s, renewal before expiry, atomic whole-file
   replacement, expiry, revocation and wrong origin/environment/allocation/session/executor/
   artifact/generation/signature rejection according to the actual contract.
5. Restart the owned registry while retaining fences; reject replay and stale generations.
   Preserve high-water state during rollback. Test issuer absence and renewal failure fail closed.
6. Check intended endpoints resolve to the same experiment instance. Record finite soak and
   negative results with exact revision/configuration identities.

Exit: **operator-issued grant mechanism verified** only. Any bounded issuer/renewal helper is
experiment tooling; automatic allocation/issuance/renewal remains SC-800 scope unless separately
authorized. Rollback stops owned processes; never resets fence state to admit a stale grant.

## 3. Prove Launcher allocation and registry selection (stage F2)

Inspect the supported Launcher contract and plan a separate owned runtime/session. Use real
registration and forwarded machine/session/executor identity; do not retrofit invented identity
onto the direct-launched8180 process. Preserve8180 until its replacement is qualified.

Verify supported allocation, authenticated heartbeat, freshness/expiry, ownership, fencing and
complete artifact/catalog/schema identity. Select that runtime through isolated ChromaScape's
registry client, not a direct transport override. Metadata/source/catalog must resolve to the
same allocated instance. Reject stale and mismatched records, then test renewal/restart/recovery.
Do not ask for game login merely for HTTP source or registry tests; identify any actual contract
requirement first. User performs login if later genuine capture requires it.

Exit: a properly allocated fresh selectable runtime; F1 success alone cannot close this gate.

## 4. Resolve authoritative trust and genuine input (stage G)

Obtain an approved signed bundle matching the exact client artifact and the original configured
trust root, or obtain an explicit acceptance amendment. Do not mint/replace a trust root silently.
The matching private key need not be placed on this host if an approved signer can supply the
bundle. Development success leaves this gate blocked.

Use the verified catalog and supported operator authorization to append an immutable profile
through SC-641 API. Pin task/settings/schema/graph/curation and bind a real BehaviorRunContext.
Prefer existing genuinely eligible finalized evidence; otherwise prepare authorized capture,
with user login where necessary. Preserve ordered actions, observations, receipts, termination,
role/privacy and source identity. Keep CAPTURED, audited INFERRED, AUTHORED_FIXTURE and LIVE_XRSPS
distinct. No fabricated profiles, captures or provenance.

Exit: genuinely eligible immutable input for its claimed scope. Authored/negative work below can
continue while this gate is blocked, without claiming genuine-source acceptance.

## 5. Complete SC-274 compiler acceptance (stage H)

- Identical immutable input/compiler version yields identical manifest bytes and checksum.
- Every field/action traces to source or an authorized fixture and pinned published curation,
  with OBSERVED/DERIVED/OPERATOR_SUPPLIED/DEFAULTED provenance.
- Initial dynamic state, static content and spawn rules stay separate. Resolve later targets
  at their execution ticks across regions/planes; never inject future observations into reset.
- Preserve exact bank widgets/items/slots/options/quantities, jewelry destination/charges and
  exchange NPC/interface semantics. Missing/ambiguous evidence gives stable source-linked refusal.
- Exercise tick gaps/offsets/duplicates/reordering, unsupported schema/cache and incomplete
  legacy inputs. Inspection/playback must not imply causal eligibility.
- Verify immutable role/privacy through bounded inert-data import/export and checksum/schema/
  size/depth refusal. Reaudit recording_b83c37e5 with pinned decoder; preserve precise blockers
  when insufficient evidence cannot honestly be repaired.

Exit: all nine SC-274 acceptance rows have final evidence or explicitly remain blocked.

## 6. Complete native parity and durable execution (stages I/J)

Use actual production/headless xRSPS handlers behind the adapter as the sole causal runtime.
Finish bank/travel/Fishing and every advertised glassblowing, Magic, combat and auxiliary method.
Compare resources, requirements, quantities, charges, timing, XP/effects, interruption, world
transitions and refusal behavior; advertise only individually verified methods.

Check tick/revision before atomic actions and require zero mutation on refusal. Snapshots must
include pending work, queues/timers, RNG/time, source cursors, world and terminal state. Compare
uninterrupted execution with fresh-worker restore plus identical suffixes. Require equal hashes,
traces, source counts and results. Recheck owned PostgreSQL fencing/recovery, two-JVM restart,
expired attempts, terminal restore without reset/step dispatch and exported artifacts. Validate
real production auth/ownership; test principals do not establish those boundaries.

Exit: native method parity, atomicity and deterministic durable continuation qualified on final pins.

## 7. Enforce runtime closure and worker isolation (stage K)

Inventory modules/handlers/scripts/assets/content/cache/scheduler/RNG/time and filesystem/network
dependencies. Reject missing, modified, unexpected, aliased/symlinked and out-of-closure loads,
including CJS/ESM/dynamic loading, before execution; preserve last supported pin.

Test actual egress denial, filesystem/process/thread boundaries, cancellation and CPU/memory/time
budgets with cold/fresh/parallel workers and deliberate violations. Do not weaken limits, guards
or timeouts to obtain passing results.

Exit: enforced process/OS isolation and regression-detecting negative evidence.

## 8. Package and verify actual client lifecycle (stage L)

Build paired worker/client artifacts from reviewed clean inputs with full inventory and pinned
adapter/build/cache/content/schema. Qualify worker before generating release/client manifest.
Reject missing/mismatched packages/assets before use. Run packaged Java and durable-session
integration on owned queues/artifacts/state.

Use an approved computer-use surface, SC-1991 gateway and real 3D client attached to the same
authoritative world. Exercise authenticated exact-origin Start/Retry/Watch, pause/resume,
single-tick controls, reconnect/cancel/import/export with one owned actor and scoped teardown.
Verify bank/widgets/quantities/charges/travel/Fishing/inventory/XP against structured receipts.
Compare watcher/camera/client-attached/disconnected variants for equal causal results. Obtain
separately required human browser Watch sign-off. LIVE_XRSPS remains opt-in/non-certifying;
offline qualified workers retain enforced network isolation.

Exit: paired package identity, real-client lifecycle and noninterference plus human Watch evidence.

## 9. Deployment compatibility and final matrix (stage M)

Resolve missing production migrations and reused V58-V60 numbering through a reviewed forward
design. Do not rewrite released history or reset shared stores. Test empty installation and
supported upgrades on disposable owned databases with strict validation/data preservation.
Schema copying in stage E did not test migrations.

Freeze final inputs and run required compiler/provenance/curation/privacy/auth, native method/
state/closure/isolation/resource/negative, packaged integration, differential/oracle, performance/
stress and CI matrices. Retain exact commands/hashes/durations/failures/skips. Earlier tests may
be reused only for unchanged relevant inputs and matching coverage. Deliberate regressions must
be detected; do not change certification/control flags based on selected passing suites.

Exit: required final matrix and deployment paths qualify exact delivered artifacts.

## 10. Shared rollout, review and handoff (stage N)

After isolated qualification, prepare a concrete reviewed8090 build/restart and shared registry
configuration with route, secret ownership, grant-renewal ownership, fences, monitoring and
rollback. Confirm deployment authorization before changing shared services. Preserve replay
worker=false and historical attempts. Verify effective configuration and actual outbound
connections after restart; a source diff alone is not deployment.

Review scoped multi-repository diffs and all17 original acceptance rows. Link exact evidence,
unrun checks and blockers. Provide Implementation Evidence (Changed files/Tests/Remaining work)
and Verification Evidence (Acceptance results/Tests/Risks/Rollback). Publish/merge only reviewed
authorized work. If required gates remain blocked, hand off a precise partial result rather
than marking either story complete. Felix stays skipped and Shortcut unchanged unless explicitly
re-enabled; then reuse existing task identities and reconcile records through actual gates.

## Execution order and completion rule

Immediate: evidence reconciliation -> F1 operator-grants -> F2 Launcher registration/selection
-> G genuine input -> final integrated compiler path. H-K authored/negative work and migration
diagnosis can run independently while trust/allocation are blocked. L requires qualified native,
state and isolation inputs; final M runs against frozen deliverables; N follows actual evidence.

SC-274 closure requires its nine acceptance rows plus applicable genuine-source/runtime
prerequisites. Linked SC-276 closure requires its eight rows. F1 or development-fixture E success
does not close either story. Original A-N plan remains the detailed acceptance reference.

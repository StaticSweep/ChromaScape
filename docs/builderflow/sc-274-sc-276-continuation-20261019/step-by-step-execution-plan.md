# SC-274 / SC-276: step-by-step execution plan

Updated implementation work packages after phase1-3 review:
[implementation plan, 20 October](implementation-plan-20261020.md). That sequence supersedes
earlier completion/next-action summaries here; this checklist remains historical context.

Date: 19 October 2026.

## Purpose

Finish the stories by configuring and testing the existing candidates first. Make additional
code changes only where inspection or an acceptance failure demonstrates a concrete gap.
There is not yet enough evidence to promise that no further code changes are needed.

> **Execution status, 19 October 2026: phases 1-3 executed.** The native continuation and
> cross-JVM restart lanes pass on frozen inputs, and the provenance gate is deployed and proven
> on the owned isolated 8181 service. Two host-boundary issues were corrected in the authored
> lane (cold-start transport deadline; `@ConfigurationProperties` rebinding of the test budget
> and artifact root). Results, preserved failures and the remaining phase 4-10 blockers are in
> [phase1-3-execution-results-20261019.md](phase1-3-execution-results-20261019.md). No
> acceptance row or story is closed.

This is the practical execution checklist. The complete scope and 17 acceptance rows remain
in [the original delivery plan](delivery-plan-after-step2-review.md). The broader sequencing
and completion rules remain in [the current remaining plan](current-remaining-delivery-plan.md).
This document does not mark those acceptance rows complete.

## Boundaries

- Never run `wsl --shutdown`.
- Keep shared 8090, shared registry/database, canonical trust settings and existing replay
  work unchanged during isolated qualification. Shared rollout requires authorization.
- Keep replay consumers disabled. Do not restart the stopped 8098 logged-in session implicitly.
- User performs game login where genuinely required; source HTTP tests alone do not require it.
- Preserve unrelated dirty work, original artifacts and failed/corrected evidence.
- Keep AUTHORED_FIXTURE, development trust, genuine captured evidence and certification distinct.
- Felix is explicitly skipped. No Felix or Shortcut writes or state transitions are planned.

## Working locations

| Purpose | Path |
|---|---|
| Compiler candidate | `C:\Dev\IdeaProjects\.worktrees\sc274-completion` |
| Isolated compiler build mirror | `C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp` |
| Native candidate, including existing worker | `C:\Dev\IdeaProjects\.worktrees\sc276-completion` |
| Selected isolated Micro6 source | `C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime` |
| Pinned registry worktree | `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1` |
| Evidence root | `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019` |

Recorded ports: shared ChromaScape8090; owned Micro6 runtime8180; owned ChromaScape8181;
planned owned registry8182; owned persistence database55439. Reinspect listeners and process
command lines before use; historical PIDs are not ownership proof. Allocate separate state
for native continuation rather than sharing existing experiment tables without inspection.

## 1. Freeze inputs and inspect prerequisites

**Change type:** inventory/configuration only.

- [ ] Read applicable repository instructions and record HEAD plus scoped uncommitted diffs.
- [ ] Record exact JAR/bundle/native-worker/closure/cache/content identities and evidence hashes.
- [ ] Confirm ownership of listeners, containers, writable paths and artifact directories.
- [ ] Preserve stage E's original 30/31 result, audit-correlation correction, console-only
  11/11 restart evidence and separate 9/9 tamper results.
- [ ] Confirm candidate source contains the purpose-token, startup-validation and provenance
  fixes. Do not assume the running services contain them.

**Pass condition:** subsequent commands and results can be tied to exact inputs and owned state.

## 2. Configure and run the existing native continuation lane

**Change type:** environment, disposable database and test inputs first.

- [ ] Inspect `server\src\replay\worker-main.ts` in the native candidate, its supported
  invocation, dependency installation, identity/closure and cache/content prerequisites.
  The worker exists there; its absence in the canonical checkout is not a workspace blocker.
- [ ] Set `SC274_NATIVE_REPLAY_ROOT` to the native candidate path above.
- [ ] Provision an owned disposable PostgreSQL database and configure
  `SC276_NATIVE_CHECKPOINT_JDBC_URL`; supply any credentials required by the actual test.
- [ ] Prepare the documented authored native input; set `SC276_NATIVE_DATABASE_INPUT`.
  Allocate a new non-existing output path for `SC276_NATIVE_DATABASE_OUTPUT`.
  Inspect the test's input format rather than inventing captured provenance.
- [ ] Run `replayNativeDatabaseContinuationTest` from the compiler candidate or an isolated
  build mirror containing its exact source changes. Preserve commands, logs and JUnit results.
- [ ] Configure `SC276_NATIVE_RESTART_ROOT` and run the separate
  `replayNativeDatabasePrepareTest` / `replayNativeDatabaseResumeTest` JVM lanes.
- [ ] Verify restored suffixes, state/domain hashes, trace/source counts and terminal results
  against uninterrupted execution. Verify terminal restore dispatches neither reset nor step.

**If it fails:** distinguish missing local prerequisites from a worker defect. Record the first
real failure, make the smallest justified change, and rerun affected tests. Do not bypass
identity, closure or certification checks to make a run green. Any supported development mode
must remain explicitly labelled non-certifying and cannot close the qualified-runtime gate.

**Pass condition:** native continuation/restart behavior is evidenced, or a specific tested
failure replaces the earlier missing-worker claim.

## 3. Rebuild only owned 8181 with the provenance fix

**Change type:** isolated build/relaunch and tests; existing source fix already implemented.

- [ ] Build the exact candidate changes in the mirror; do not replace live8090 build outputs.
- [ ] Run the provenance tests plus affected source orchestration/idempotency regressions.
- [ ] Relaunch only8181 after verifying ownership, preserving its owned database.
- [ ] Through the normal API, prove legacy `verified=false` and null PASS attempts are refused
  with `STABILIZATION_NOT_VERIFIED` and zero new run/manifest/binding rows.
- [ ] Test an eligible positive prerequisite through the supported verified ingestion path
  where available. Do not flip a fixture row to `verified=true` just to regain a green result.
  If no eligible positive prerequisite is available, record that gate as blocked and retain
  focused acceptance tests without calling the old positive fixture a post-fix pass.
- [ ] For the eligible path, repeat idempotency, identity mismatch, authenticated manifest/
  file/symbol reads, restart persistence and tamper rejection. Keep labels and privacy intact.
- [ ] Write results incrementally so a later harness crash does not discard earlier checks.

**Pass condition:** the new provenance boundary is active and tested end to end; positive-path
claims identify their actual prerequisite provenance. Original-root acceptance remains separate.

## 4. Configure live isolated registry8182 on Linux

**Change type:** owned deployment/network configuration first.

- [ ] Verify clean pinned revision `58098690b83a3c0914016ddbe69344657401eb47`.
  Preserve already passing Linux mechanism tests; native Windows directory-sync failure remains
  an observed unsupported path, not a reason to weaken durable fences.
- [ ] Inspect required auth/database/startup dependencies and allocate owned instances/state.
- [ ] Prepare owned secret, grants file and durable fencing directory. Keep secrets out of logs,
  version control and command-line arguments.
- [ ] Qualify an authenticated, bounded Linux-container-to-Windows-runtime route. Do not use
  container localhost as Windows localhost. Inspect policy before selecting hostname/origin.
  If loopback8180 is inaccessible, prepare a separate owned runtime or scoped routing design;
  do not silently broaden the existing runtime's bind address.
- [ ] Start the owned registry with only the intended loopback host exposure on8182.
- [ ] Use the documented operator issuer and labelled experiment identities. Verify live grant
  acceptance and ChromaScape registry transport, <=120s lifetime, renewal before expiry,
  atomic publication, revocation and identity/artifact/origin/signature refusal.
- [ ] Restart with durable fences retained; reject replay and stale generations. Test issuer
  absence and renewal failure fail closed. Preserve fence high-water state during rollback.

**Pass condition:** live operator-issued integration works on the qualified route. This does
not prove Launcher allocation. A bounded renewal helper is experiment tooling; SC-800 automatic
issuance/renewal is not completed by it.

## 5. Start a Launcher-registered owned runtime

**Change type:** supported Launcher/runtime configuration first.

- [ ] Inspect Launcher contract and registration flow; allocate a separate owned session/runtime.
- [ ] Obtain real forwarded machine/session/executor and allocation identities through that flow.
  Do not retrofit invented identities onto the direct-launched8180 instance.
- [ ] Verify authenticated heartbeat freshness/expiry, ownership, fencing and complete build/
  artifact/catalog/schema identity.
- [ ] Select the intended runtime through isolated ChromaScape's registry client. Verify source,
  metadata and catalog resolve to that same instance; reject stale and mismatched records.
- [ ] Exercise disconnect/reconnect, expiry and restart using the supported lifecycle.

**Pass condition:** properly allocated runtime selection is proven. User login is requested only
if an actual supported contract or subsequent capture requires it.

## 6. Obtain approved trust and genuinely eligible input

**Change type:** authoritative inputs/operator action, not a coding workaround.

- [ ] Obtain an approved signed bundle matching the exact client artifact and original configured
  trust root, or an explicit acceptance amendment. An approved signer may supply the bundle;
  its private key does not have to be copied onto this machine.
- [ ] Verify signature, environment, repository/build/artifact/snapshot identity and content.
- [ ] Obtain genuine catalog and authorization; append an immutable profile through SC-641 API.
- [ ] Pin task/settings/schema/graph/curation and establish the real BehaviorRunContext.
- [ ] Select genuinely eligible finalized evidence or prepare authorized capture; user logs in
  where necessary. Preserve ordered observations/actions/receipts, termination, role and privacy.

**Pass condition:** genuine-source/input eligibility is evidenced. Never substitute development
trust, legacy unverified stabilization or authored identities for this gate.

## 7. Finish compiler and native behavior acceptance

**Change type:** acceptance testing first; implementation only for demonstrated gaps.

- [ ] Repeat compilation of immutable input; require identical manifest bytes/checksum.
- [ ] Trace every field/action to source or authorized fixture and selected immutable curation.
- [ ] Verify exact bank/container quantities/options, jewelry charges/destination, exchange,
  multi-region/plane travel and Fishing semantics; do not inject future state into reset.
- [ ] Exercise stable source-linked refusal for gaps, reorder/duplicates, missing/ambiguous
  state, unsupported schema/cache and legacy incomplete evidence.
- [ ] Check role/privacy inheritance and bounded inert import/export. Retain the historical
  recording's precise blocked verdict where original evidence remains insufficient.
- [ ] Test every advertised native method against actual production/headless handlers: resources,
  requirements, timing, quantities/charges, XP/effects, interruption and combat state.
- [ ] Prove zero mutation on invalid/stale/unsupported actions and future-complete snapshots.

**Pass condition:** each applicable SC-274/compiler and SC-276/native acceptance row has exact
positive/negative evidence. Existing 56+57 authored passes alone do not establish all these claims.

## 8. Qualify closure, isolation and deployment compatibility

**Change type:** inspect/test existing enforcement; code changes if boundaries are missing.

- [ ] Inventory all runtime modules/assets/content/cache and dynamic loading dependencies.
- [ ] Reject missing/altered/unexpected/aliased/out-of-closure dependencies before execution.
- [ ] Exercise actual egress denial, filesystem/process/thread restrictions, cancellation,
  CPU/memory/time budgets and cold/fresh/parallel workers with deliberate violations.
- [ ] Diagnose migration numbering/history conflicts and missing production schema through a
  forward-compatible design. Do not rewrite released migrations or reset shared databases.
- [ ] Test empty installation and supported existing-history upgrades on disposable databases,
  with strict schema validation and data preservation. Stage E schema copying did not test this.
- [ ] Format/check changed files only; do not rewrite1357 unrelated pre-existing violations.

**Pass condition:** real enforcement and supported deployment histories pass. Fixes are scoped,
reviewable and backed by failure/retest evidence rather than weakened guards or larger timeouts.

## 9. Package, exercise actual client and run final matrix

**Change type:** packaging/test configuration; code changes only for observed missing behavior.

- [ ] Build paired worker/client from reviewed inputs with full adapter/build/cache/content/schema
  inventory; qualify worker before release/client manifest generation.
- [ ] Verify missing/mismatched artifacts/assets fail before use; run packaged Java/durable lanes.
- [ ] Use approved computer-use surface and actual client attached to the same authoritative world.
  Exercise authenticated exact-origin Start/Retry/Watch, pause/resume, supported tick controls,
  reconnect/cancel/import/export and one-actor lifecycle.
- [ ] Verify state/receipt parity and viewer/camera/disconnect noninterference. Obtain separately
  required human Watch sign-off. Keep LIVE_XRSPS opt-in and non-certifying.
- [ ] Freeze final inputs and run required compiler/provenance/privacy/auth, native state/method,
  closure/isolation/resource/negative, packaged, differential/oracle, stress/performance and CI
  checks. Record commands, durations, failures/skips and deliberate regression detection.

**Pass condition:** required final matrix and client/Watch evidence cover exact final deliverables.

## 10. Prepare and apply authorized shared rollout

**Change type:** reviewed deployment; not authorized merely by this plan document.

- [ ] Prepare concrete8090 build/restart and shared registry configuration, route, secret ownership,
  grant renewal operations, durable fences, monitoring and scoped rollback.
- [ ] Confirm authorization before shared mutations. Use the documented Windows wrapper; do not
  substitute an ad hoc restart. Keep replay worker disabled and historical attempts unaffected.
- [ ] Verify effective config, actual outbound connections, authenticated discovery/source paths,
  service health and relevant refusal behavior after rollout.
- [ ] Review scoped multi-repository changes and reconcile all17 original acceptance rows.
- [ ] Provide Changed files/Tests/Remaining work and Acceptance results/Tests/Risks/Rollback.
  Do not mark either story done while a required gate remains blocked.

**Pass condition:** authorized rollout passes and all required acceptance has final evidence.

## Practical order and change rule

Start with1 ->2 ->3, then4 ->5 ->6. Authored compiler/negative tests, migration diagnosis and
worker boundary work can proceed while authoritative trust or capture waits on external inputs.
Full genuine-input acceptance depends on6; packaging/final qualification depends on relevant
native, state and isolation gates. Shared rollout is last and separately authorized.

For every failure: record the exact input and error; determine config/input versus implementation
cause; change only what the evidence supports; rerun affected verification; preserve the original
failure. No blanket rewrite is planned, and no promise of zero further code changes is made.

## Verification of this writing increment

Documentation only. No builds, runtime tests, service restarts, database changes, secret changes
or tracker operations are performed by creating this document. Existing reported test counts
remain tied to their original evidence; unchecked rows above describe future work.

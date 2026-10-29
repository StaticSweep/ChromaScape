# SC-274 / SC-276: full remaining implementation plan

Date: 25 October 2026. Status: plan after the latest source review; not execution evidence.

This plan supersedes the remaining-work ordering in
[remaining-implementation-plan-20261024.md](remaining-implementation-plan-20261024.md).
The original criteria in [delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md)
remain unchanged. All 17 original acceptance rows are currently open.

## Baseline and claim boundaries

| Surface | Current evidence and limits |
|---|---|
| ChromaScape | Local HEAD f919b533; orphan-retry fix 6c90b610 and resource ceilings f919b533 are committed locally. The SC-666 Gradle hunk remains unrelated/uncommitted. |
| Native source | Local branch codex/sc274-native-recovered-20261024 at 3e465691, including guard 1daf4006, capability changes 816a83b4 and oracle harness 3e465691. |
| Published baseline | ChromaScape 5cd21150 was previously pushed. Later local commits and native changes are not implied published. |
| Durability | Previous admission/marker/ancestor and retained-orphan retry findings have fixes and regression coverage. Windows STRICT refuses unsupported persistence. Linux positives were reported passing; actual power-loss behavior remains untested. |
| Recovery | Original native 105-file commit/bundle retained. The 27 compiler paths without historical expected bytes remain a documented recovery boundary, not assumed recovered. |
| Capability honesty | Explicit Fishing method added; bare CAST_SPELL removed. Original Magic/glassblowing acceptance still needs scoped contracts/evidence or an approved scope amendment. |
| Oracle | Comparator and advertised-method matrix implemented. Genuine references are absent, so the shipped matrix is INCOMPLETE. Reference authority and semantic completeness remain unfinished. |
| Resource controls | prlimit applies per-process CPU/FD/file-size/core ceilings; Node receives an old-space cap. This is not complete aggregate worker-tree memory/CPU/process/storage qualification. |
| Fresh review checks | Module guard, oracle harness and matrix selection passed 24/24 on Windows Node 22.19 with explicit Windows esbuild. Two separate probes reproduced the ESM registration escape and relabel/reseal QUALIFIED result. |
| Session integration | Latest reported worker run: 14 cases, one pass, nine fail on authoritative qualification, four client/provider skips. The separate repository integration has 3/3 passes. |
| Qualification | AUTHORITATIVE_RUNTIME_QUALIFICATION.complete remains false. Clean/certifiable input identity alone does not qualify execution. |

Latest review: [qualification-review-20261025.md](qualification-review-20261025.md).
Fresh probe evidence:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261025\qualification-review-20261025T125202Z`.
Historical build/test reports retain their original source/platform scope; they are not
automatically current after later changes.

## Operating boundaries

Read current AGENTS for each owning repository. Verify actual HEADs, dirty changes, paths,
resource ownership, configuration and capabilities before mutation. Do not stage unrelated work.

Work locally in isolated checkouts and owned disposable resources. Preserve shared services,
databases, canonical trust, replay-consumer settings, historical attempts, native recovery
anchors and the SC-666 hunk. Never run wsl --shutdown. No host reboot, Docker reset, game
login, environment-wide policy change or destructive power-loss test is implicit here.

Use Linux-native JDK/Node/dependencies on the target Linux lane. Check bwrap/prlimit and actual
namespace/delegation capability, not just executable presence. A failed prerequisite is a
recorded blocker, never permission for an unsandboxed/development-authority fallback.

Secrets stay in protected storage and outside argv/logs/Git. Felix/Shortcut remain explicitly
skipped. This plan performs no commit, push, merge, deployment or tracker transition.
No further question is needed merely to implement already-scoped local engineering work.
Human participation and external authority are requested only when a concrete dependent
step is ready. Use no parallel agents unless separately instructed.

## Execution order and original WP mapping

R1-R12 below are execution increments; they do not renumber the existing work packages.

| Original WP | Remaining increments |
|---|---|
| WP1 durability | R7 |
| WP2 reproducibility | R4 |
| WP3 compiler/native behavior | R3, R6 |
| WP4 skipped integration | R6, R11 |
| WP5 safety/performance | R1, R4, R5, R6 |
| WP6 migrations | R8 |
| WP7 clock/renewal | R9 |
| WP8 allocation/trust/genuine input | R2 reference input, R10 |
| WP9 paired package/Watch | R4, R11 |
| WP10 final delivery | R12 |

Immediate sequence: R1 -> R2 local validation/binding -> R3/R4 -> R5. R6 can produce
fail-closed qualification results while evidence is incomplete. R7-R9 preparation proceeds
independently. R10 supplies the external inputs required for final R6/R11/R12 qualification.
Do not stop all local work because genuine reference captures are unavailable.

## R1. Close the builtin-ESM hook-registration escape

**Local implementation. Owner:** native runtime-module-guard and its tests.

- [ ] Add the reproduced case as a regression: after installation, import node:module through
  ESM, attempt registration of a short-circuit resolver/loader, then import unpinned source.
  Assert refusal before any side effect.
- [ ] Seal the exposed CommonJS registration properties and builtin ESM live bindings;
  replacing the CommonJS functions by writable assignment is insufficient.
- [ ] Cover pre-existing ESM namespaces/named exports, both registration APIs and attempts
  to replace entry points/extension handlers. Define and pin the trusted bootstrap boundary
  and any references it exposes. Refuse execution paths whose boundary cannot be established.
- [ ] Preserve verified-byte CJS/JSON/TypeScript loading, unsupported-path refusal and
  existing graph/bootstrap behavior. Do not restore unsafe loader fallbacks for compatibility.
- [ ] Run guard, bootstrap, closure CLI and real worker smoke checks on the pinned target
  Linux/Node version, recording both successful imports and deliberate violations.

**Exit:** no reviewed public registration path can unseat the installed boundary. The
new negative case passes alongside existing tests on the actual supported runtime.

## R2. Establish trusted oracle-reference and replay-input binding

**Local API/validation work; real approved references are external inputs.**
Owners: oracle-harness, acceptance matrix and a reviewed reference-inventory loader.

- [ ] Separate comparison success from proof of independent provenance. A caller-controlled
  INDEPENDENT_CAPTURE label plus a self-computed digest cannot establish authority.
- [ ] Define a trusted reviewed capture inventory: immutable reference ID, approved expected
  digest, capture provenance/authority, recorder/tool version and allowed method/scenario.
  Its approval/root of trust must come from outside the candidate reference being validated.
- [ ] Validate each reference against that inventory before any qualification verdict.
  Reject relabel-and-reseal substitution, unknown/conflicting IDs, malformed records and
  ambiguous or incomplete evidence. Add the fresh relabel/reseal probe as a negative test.
- [ ] Bind actual replay output to reference ID/scenario, action/initial-input digests,
  source/runtime/closure identity and comparison profile. Indexing only by method cannot
  distinguish multiple captures required for the same method.
- [ ] Enforce advertised and originally promised method coverage in the qualification path;
  do not rely solely on a separate coverage-helper unit test.
- [ ] Bound nested data sizes/counts and domain values using pinned schemas. Keep missing
  references/actual output INCOMPLETE; refuse malformed or contradictory authority evidence.

**Exit:** unapproved synthetic data cannot discharge independent-reference requirements,
even if relabelled and resealed. Without real references the final verdict remains INCOMPLETE.

## R3. Complete method contracts and oracle semantics

**Local implementation and authored differential tests; genuine fidelity claims depend on R10.**

- [ ] Reconcile original acceptance, advertised capabilities and actual implementations for
  Fishing, glassblowing, enumerated Magic/combat and auxiliary methods. Retain honest refusal
  for unsupported behavior; any reduction of original promised scope requires explicit amendment.
- [ ] Add scoped contracts for supported spell/method variants rather than advertising
  arbitrary CAST_SPELL. Complete their requirement/resource/quantity/charge/timing/XP/effect
  and interruption rules and production/headless parity evidence.
- [ ] Extend per-method oracle projections beyond position/XP/inventory/event names where
  required: bank and equipment state, jewelry charges, quest progression, exchange offers,
  combat/target state and structured event payloads/outcomes.
- [ ] Specify tick/action alignment, initial state and deterministic/random inputs. If a
  capture lacks required state or randomness, report incompleteness rather than inventing it.
  Do not introduce tolerances merely to turn a mismatch into a pass.
- [ ] Add deliberate regressions for every required field/outcome and exact failure location.
  Preserve positive/negative provenance, curation, privacy/role and inert import/export coverage.
- [ ] Retain the historical accounting/NOT_READY verdict. Do not reinterpret net inventory
  change as catches or alter the original capture to meet a gate.

**Exit:** method contracts and oracle projections cover the actual original scope. An
advertised-method list or a generic frame match alone does not qualify all gameplay semantics.

## R4. Finish executable/read closure and reproducible Linux packaging

**Local implementation. Owners:** native closure/loader/filesystem boundaries and packaging scripts.

- [ ] Inventory the reachable handler/module graph, trusted bootstrap, dependencies, schemas,
  cache/content and dynamic resolution. Define which executable/read paths are supported.
- [ ] Enforce per-read filesystem allowlists and no-write policy at the actual worker boundary;
  hashing an inventory is not read enforcement. Test aliases, symlinks, path changes, changed
  bytes and verification/load races, including JSON/data/bootstrap paths.
- [ ] Build immutable Linux package inputs from the latest reviewed native commit and lockfiles.
  Regenerate identity for the new guard/capability/oracle source; do not reuse the older
  sc276-certifiable-20261024 identity from 79382be3 as evidence for later commits.
- [ ] Replace external junction/temp-binary assumptions with pinned target-platform acquisition
  and verified resolver locations. Retain original recovery bundle and create a new verified
  bundle for later native commits without overwriting the original anchor.
- [ ] Reconstruct the owned compiler service/build location with disposable DB/artifact roots
  and protected credentials. Disable unintended consumers before any owned launch.
- [ ] Keep the 27-path historical byte-recovery limit explicit. Where acceptance permits,
  establish a new reviewed baseline rather than claiming those bytes were recovered.
- [ ] Rebuild in an independent Linux directory and compare package/closure identities.
  Reproduce the earlier closure CLI timeout under cold/concurrent load and diagnose it.

**Exit:** final inputs and execution/read boundaries are reproducible and enforced without
developer paths or unrecorded artifacts; export deadlines are qualified rather than enlarged silently.

## R5. Qualify aggregate resource, cancellation and production budgets

**Local implementation on an owned, already resource-bounded Linux environment.**

- [ ] Define production cold/warm startup, request/run, wall-clock, total memory, aggregate CPU,
  process/thread, descriptor and temporary-storage requirements and operational ownership.
- [ ] Extend inherited prlimit/Node ceilings with supported worker-tree enforcement. Account
  for native/Buffer memory outside old-space, process fan-out, many files and descendant CPU.
  A reviewed delegated cgroup or equivalent must not modify unrelated host workloads.
- [ ] Add negative cases for those aggregate limits, with reliable attribution of termination.
  Refuse launch when mandatory enforcement is unavailable; no unbounded fallback.
- [ ] Replace blocking readiness reads in resource/cancellation tests with bounded asynchronous
  reads. Exercise silent startup, cancellation and repeated interrupts without hanging tests.
- [ ] Verify cancellation during startup, execution, checkpoint/report writes and shutdown;
  prove no surviving descendants, connections or unowned artifacts, including spawn races.
- [ ] Measure full scenario cold/warm and parallel throughput through production transport
  against the requirements. Keep provisional ceilings and handshake measurements labelled.

**Exit:** actual target-platform violations are contained and cleanup/deadlines are bounded;
all promised scenario budgets have measurements on final package inputs.

## R6. Complete native qualification and rerun session integration

**Local qualification logic/harness work; final success requires R1-R5 and real R10 inputs.**

- [ ] Define a versioned qualification result with exact source/package/runtime/Node/platform,
  closure, OS policy, method and approved-reference identities plus test results and limits.
- [ ] Generate evidence from the reviewed qualification runner. Verify required components
  and pins; reject missing, stale, mismatched or caller-supplied approval assertions.
  A JSON record existing is not itself proof that qualification ran.
- [ ] Keep authoritative capability false while any required component is incomplete.
  Add unit cases for missing/failed/mismatched qualification using explicitly synthetic
  test evidence; never present those fixtures as actual fidelity qualification.
- [ ] Change the production qualification gate only after the complete original acceptance
  requirements for that gate are implemented, exercised and reviewed on frozen inputs.
- [ ] Rerun the nine known-red worker session cases after relevant readiness changes. Cover
  transaction rollback, retry/idempotency, restore, ownership, mismatch latching and journal
  replay; retain the existing reported worker-only pass and repository 3/3 within their scope.
- [ ] Execute the four separately gated client/provider cases when their real pair/provider
  prerequisites exist. Record each case, not just a suite headline. Do not relabel skips as passes.

**Exit:** genuine authoritative worker and durable-session evidence match the final pins.
Do not rerun unchanged known failures merely to change a headline, or flip the constant to clear them.

## R7. Retain durability regressions and qualify crash/power-loss claims

**Code fixes are implemented; remaining validation is target/platform dependent.**

- [ ] Retain admission locking, marker validation/revalidation, non-promoting development
  adoption, full ancestor forcing and retained-orphan retry regressions on the final source.
- [ ] Re-run affected Windows refusal/development and actual Linux STRICT positives after
  relevant changes. Distinguish force-call stubs from real filesystem primitive tests.
- [ ] Define the exact supported filesystem/storage acknowledgement and recovery contract.
  Cover crashes/faults around object/ref publication, activation, quarantine and cleanup.
- [ ] For any power-loss claim, use an owned disposable VM/storage target and a reviewed
  fault method. Process termination alone is not power-loss proof; never reset shared Docker,
  WSL or the host. If the target is unavailable, retain that precise unverified claim.
- [ ] Verify valid shared blobs survive failures and retries never acknowledge incomplete
  persistence. Keep forensic orphans and avoid unsafe cleanup/rollback of shared content.

**Exit:** qualification states exactly which persistence/failure behavior is demonstrated;
no broader durability claim is inferred from normal-operation green tests.

## R8. Establish released migration authority and supported upgrades

**Local investigation/test preparation; deployed release identity is external authority.**

- [ ] Obtain histories/checksums plus exact artifacts/commits for supported deployed baselines.
  Settle V47-V49, V53-V57, V58-V60, modified V50 and V71/V72 ownership/release status.
- [ ] Define intended merged schema and preserved data/constraints, including recording-change
  tables. Local pre-migration tags and inconsistent dev histories are not release authority.
- [ ] Implement reviewed forward-compatible corrections; renumber only proven-unreleased
  SQL. Never silently rewrite released checksums/history or use repair/shared reset to hide collisions.
- [ ] Run real application/Flyway empty install and each supported upgrade on disposable
  stores, including interruption/recovery, validation refusal and data preservation.
  Include registry bootstrap requirements. SQL-only installs do not qualify upgrades.

**Exit:** every supported target baseline has authoritative identity and upgrade evidence,
or its exact missing authority is recorded. Hypothetical baseline experiments stay labelled.

## R9. Qualify clock, registry and issuance/renewal operations

**Owned diagnosis/experiments local; host changes keep the existing authorization gate.**

- [ ] Refresh actual host/Linux/registry/issuer/source clocks with uncertainty and time-sync
  state. Do not reuse earlier 1.662-second alignment or 48-second skew as a current measurement.
- [ ] Assign sustained time-sync and issuance/renewal owners and verify representative
  drift/lifecycle behavior. Prepare any needed host remedy concretely before applying it.
- [ ] Retain the five-second preflight unless an explicitly reviewed requirement changes it.
  TTL remains at most 120 seconds; use measured skew/latency/safety for correct remaining-time
  renewal threshold and elapsed deadline.
- [ ] Test timely renewal/retry, issuer restart/stop, revocation/expiry, atomic replacement
  and durable replay fencing on owned resources. Never reset generation high-water state.
- [ ] Restore only ownership-verified source/registry services when a lane needs them.
  Require the intended endpoint reference and current selectability, not debugger health alone.

**Exit:** clock/renewal requirements, owners, routing and operational evidence agree.
Operator-issued experiment grants still do not constitute supported allocation.

## R10. Obtain approved independent references, allocation, trust and genuine input

**External inputs followed by owned integration; preparation remains locally actionable.**

- [ ] Prepare an exact reference request per original method/scenario: permitted source,
  capture conditions, required observations, provenance, expected digest and approval authority.
  Obtain genuine independent references for R2/R3; do not reseal authored data as independent.
- [ ] Locate supported SC-800 allocation authority/contract and launch an owned runtime through
  its lifecycle. Bind real machine/session/executor/artifact, grant, catalog/schema and source.
- [ ] Obtain an exact-artifact bundle accepted by the original configured source trust root,
  or an explicit amendment. An approved signer can supply the bundle without exposing its key.
- [ ] Verify signature/key/environment/repository/build/tree/artifact/snapshot/content identity;
  prove all selected endpoints and immutable catalogs address the same intended runtime.
- [ ] Create genuine immutable profile/context and verified stabilization through supported APIs.
  Select eligible finalized evidence or prepare bounded authorized capture with user login.
- [ ] Preserve actual action/observation/receipt ordering, termination, privacy and dataset role.
  Repeat binding/idempotency/refusal, reads, restart persistence and tamper tests with genuine input.

**Exit:** independent reference authority and original source trust/allocation/genuine input
have distinct evidence. They are separate trust obligations, not interchangeable flags.

## R11. Qualify the paired client/provider and Watch lifecycle

**Build preparation local; final acceptance requires qualified inputs and human participation.**

- [ ] Build/pin paired worker/client artifacts with cache/content/schema/assets and loader closure.
  Refuse absent/mismatched packages before execution; run packaged durable lanes.
- [ ] Attach a real client to the same authoritative world. Exercise authenticated exact-origin
  Start/Retry/Watch, pause/resume/tick controls, reconnect/cancel/import/export and scoped teardown.
- [ ] Exercise the actual configured provider with an approved endpoint, credentials and bounded
  budget; do not substitute a stub for a case claiming actual-provider integration.
- [ ] Prove one-actor ownership and observer/camera/disconnect noninterference by comparing
  committed state/outcomes/receipts, including bank/travel/Fishing and required method variants.
- [ ] Complete the four real client/provider cases and obtain separate human Watch sign-off.
  Keep LIVE_XRSPS opt-in/non-certifying and qualified offline execution network-isolated.

**Exit:** paired identity, actual lifecycle/provider behavior and human approval are evidenced.

## R12. Finish style, final matrix and authorized delivery

- [ ] Resolve scoped formatting/check requirements without mass-formatting unrelated work.
  Record repository-wide Spotless failures truthfully until actually resolved.
- [ ] Freeze final multi-repository commits, source/artifact/configuration/qualification pins
  and reviewed diffs. Preserve current native bundle/branch and stage only intended changes.
- [ ] Reuse prior passes only for unchanged relevant inputs. Run required affected/CI,
  differential/oracle, package, database, security/negative and performance/stress checks.
- [ ] Map each original Given/When/Then criterion to actual positive or expected-negative
  evidence. Close no row merely because a helper test passes, and invent no scope waiver.
- [ ] Produce Implementation Evidence (Changed files / Tests / Remaining work) and Verification
  Evidence (Acceptance results / Tests / Risks / Rollback) including failed/skipped/unrun cases.
- [ ] Prepare concrete publication/merge/rollout artifacts, routes, protected credentials,
  operational ownership, monitoring and per-component rollback. Obtain the required delivery
  authorization before shared deployment; publication of a review branch is not that authorization.
- [ ] Verify effective configuration, identity, auth/refusal, schema and operational health
  after authorized delivery, preserving replay-consumer settings and historical attempts.

**Exit:** the required matrix and human/deployment gates pass for delivered inputs, or a
truthful partial handoff records exact blockers. No automatic tracker transition.

## Original 17-row acceptance map

| Original row | Remaining increments / evidence |
|---|---|
| 274-1 | R2-R4/R10/R12: pinned immutable inputs and final deterministic bytes/checksum |
| 274-2 | R2-R4/R10/R12: exact provenance, curation and independent source bindings |
| 274-3 | R3/R6/R12: stable source-linked invalid/incomplete refusal |
| 274-4 | R3/R6/R10-R12: genuine native Fishing causal execution |
| 274-5 | R3/R6/R12: incomplete/legacy refusal and zero mutation/writes |
| 274-6 | R3/R10/R12: immutable privacy and dataset role |
| 274-7 | R3/R12: bounded inert import/export on final inputs |
| 274-travel | R3/R6/R10-R12: exact bank/travel/Fishing with sufficient evidence |
| 274-historical | R3/R12: preserve the supported historical NOT_READY verdict/source references |
| 276-boundary | R1/R4-R6/R11/R12: production xRSPS behind a qualified adapter |
| 276-methods | R2/R3/R6/R10-R12: original promised production/headless method parity |
| 276-closure | R1/R4/R5/R12: sealed loader, read enforcement and reproducible bounded closure |
| 276-state | R3/R6/R7/R11/R12: future-complete durable restart/continuation |
| 276-safety | R1/R4/R5/R12: actual bounded OS-isolated workers |
| 276-live | R6/R9-R12: supported allocated/authenticated opt-in lifecycle |
| 276-client | R3/R6/R10-R12: real client/provider, genuine input and noninterference |
| 276-certification | R1-R12: complete qualification on frozen delivered inputs |

## Evidence and rollback

Each increment writes a timestamped owned attempt with exact files/pins, commands actually
run, structured results, XML/logs, digests and scope limits. Preserve failures and original
records; dated corrections supersede them. Keep review probes separate from qualification evidence.

Remove only ownership-verified disposable resources. Preserve shared stores, immutable
content, grants/fence volumes, generations and recovery anchors. Credential recovery rolls
forward to fresh protected values. Migration/platform rollback needs a reviewed target-specific
procedure; shared reset or restoration of exposed secrets is not routine rollback.
Use follow-up commits rather than force-pushing published history.

## This plan update

Documentation only: this plan and pointers from earlier notes. No implementation, runtime
tests, commit/push, service/database/credential change or tracker action is performed by writing
it. Effort and completion timing remain dependent on measured gaps and actual external inputs.


## Correction note (2026-10-26)

This document was written against native HEAD 3e465691 and Java HEAD f919b533. Since then the native work
(through f4e4aa5f) was merged into xrsps-typescript `main` and pushed (now 4f935c59); the Java commits
through f919b533 are published on codex/sc274-completion-20261015. Statements that nothing is published, or
that the review performs no commit or push, describe the state when it was written, not now.

Not re-verified after the merge: whether the P1 loader-registration escape (R1) and the oracle
relabel-and-reseal finding (R2) are fixed in merged `main`. runtime-module-guard.ts on `main` now refuses
`node:module` imports and seals register/registerHooks, so R1 may be partly done; confirm with the review probe
before closing it. The qualification gate, the 17 acceptance rows and the external inputs remain open. The
SC-666 Gradle hunk is unrelated and is committed separately.

## Superseding plan (29 October 2026)

Current remaining implementation and acceptance sequencing is in
[remaining-implementation-plan-20261029.md](remaining-implementation-plan-20261029.md).
Merged-main R1/R2 probes and fresh Windows tests have since passed within their documented scope;
the old open-finding statements above are historical. The new plan preserves the original 17-row
criteria and separates completed Windows development checks from qualification and external gates.

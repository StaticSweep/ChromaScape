# SC-274 / SC-276 full remaining delivery plan — 2026-10-19

Current execution plan after the independent step 2 review: [delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md). This earlier plan remains dated history.

This reconciles the 2026-10-16 plan, 2026-10-18 increments and the independent 2026-10-19 review. It preserves P1–P11 and all 17 listed acceptance rows (nine compiler/travel/historical rows and eight runtime rows). The execution steps below are an ordering of that work, not a claim that they reproduce an earlier numbered items 3–12 list. No whole-story gate is complete. Felix remains skipped by explicit user instruction; local documentation is not a successful Felix write or transition.

Canonical projects: C:\Dev\IdeaProjects\ChromaScape, C:\Dev\IdeaProjects\micro6 and C:\Dev\IdeaProjects\xrsps-typescript. Existing compiler/native candidates: C:\Dev\IdeaProjects\.worktrees\sc274-completion and C:\Dev\IdeaProjects\.worktrees\sc276-completion.

## Current verified baseline

- Item 1: LAN registry override is present; candidate PID 195400 connects to 192.168.1.6:8084. Three independent discovery checks succeeded; debugger/workspace return 200 and anonymous stabilization 401. Historical soaks and identical replay snapshot hashes were inspected. Historical cold-idle/40-call results were reviewed in their aggregate evidence, not rerun. Keep the workaround; no WSL shutdown.
- Discovery now has one non-selectable stale/unavailable record, with missing catalog/executor identity and ENDPOINT_GRANT_AUTHORITY_NOT_CONFIGURED. It does not establish a usable Micro6 runtime.
- Item 2: original trust-root requirement remains blocked. Actual Ed25519 sign/verify proves the available Micro6 key matches its own public key and fails against configured ChromaScape trust. Development-key experiment is authorized separately; no implementation of it was found.
- The source-disabled client on 8098, PID 242640 at review, is preserved. Revalidate PID identity before any later process action.
- Selected 26-suite/188-test native pass and prior Java/client/database/recovery passes remain historical evidence for their recorded inputs. Full runtime certification and actual client Watch remain open. SYNTHETIC_CONTRACT, certifiable=false and controlAllowed=false remain in effect until their respective gates genuinely pass.
- Existing recording_b83c37e5 blocked verdict, all original archives, current dirty work and the nine pre-existing replay attempts remain preserved. Do not enable shared replay consumption during verification.

## Execution steps and exit checks

### 1. Preserve item 1 and establish immutable review inputs

- [ ] Retain registry override and documented rollback; do not replace loopback defaults globally or restart just to reconfirm success.
- [ ] Inventory each candidate's HEAD, tracked/untracked changes, artifact hashes and relevant private configuration variable names without recording secrets.
- [ ] Correct source assessment's task name to :client:prepareMicro6SourceBundle. Retain original assessment as dated history; link this update.
- [ ] Keep latest status separate from historical failures/passes; qualify localhost fallback and relay-internal root-cause claims.

Exit: exact selected source inputs and evidence references are recorded; no unrelated changes discarded. Supports all phases.

### 2. Produce an isolated development-signed Micro6 runtime

- [ ] Select the intended Micro6 source revision, including required local changes after review. Preserve the dirty original checkout. Use an isolated clean, reviewed source snapshot; do not bypass TaskSourceIdentity cleanliness or claim a different revision represents those changes.
- [ ] Reserve an unused runtime port, separate bundle root, output directory and experiment configuration. Keep 8098 untouched.
- [ ] Use existing micro6-sc810-local-ed25519 signing material in process memory through supported inputs; no new key and no secret in source/evidence/logs.
- [ ] Build the final shaded artifact and identity sidecar; run :client:prepareMicro6SourceBundle, whose producer binds the bundle to exact packaged bytes. Use :client:generateAllTaskSourceBundles only in its documented supported role. Do not invoke the nonexistent prepareAuthoritativeSourceBundle task.
- [ ] Verify promoted current.json, manifest schema/key ID/environment, artifact SHA-256/build identity, closure/content hashes and signature.
- [ ] Run focused generator, bundle/catalog, source-mode and source-endpoint security tests on the selected revision. Demonstrate wrong-key, modified manifest/content, wrong artifact and absent/stale bundle rejection.
- [ ] Launch packaged :client:runBuilderGoalAuthoritative on the owned port. Keep any game login with the user; do not automate credentials or gameplay.
- [ ] Give only an isolated ChromaScape experiment the Micro6 development public key and source transport route. Preserve the canonical original trust configuration and existing 8090 service. Determine supported isolated DB/artifact/config routing before launching the experiment; do not assume a second service may write shared state safely.
- [ ] Prove authenticated source/catalog reads, unauthenticated/wrong-token denial and ChromaScape signature/artifact validation through the actual integration path.

Exit: development-signed exact-artifact runtime is independently verified and clearly labeled. Original configured-trust gate remains blocked until an approved matching bundle is obtained or acceptance is explicitly amended. Supports P1/P7; does not complete genuine-source acceptance.

### 3. Resolve authenticated registry allocation and discovery

- [ ] Inspect the owning mule deployment's grant-authority configuration and implementation before changing anything. Diagnose ENDPOINT_GRANT_AUTHORITY_NOT_CONFIGURED separately from connectivity.
- [ ] Establish the owned runtime's supported allocation/heartbeat registration with correct authentication and ownership; do not invent endpoint references, identities or grants.
- [ ] Verify fresh executor_instance_id, endpoint_ref, build/catalog/schema hashes, lifecycle states and heartbeat/expiry consistency. ChromaScape must mark the intended Micro6 runtime selectable.
- [ ] Verify stale/expired/mismatched/unauthorized records remain non-selectable; fetch catalog through registry rather than trusting a browser-supplied executor URL.

Exit: selected runtime is fresh, authenticated and selectable; failures remain explicit. Depends on step 2 for the owned runtime; deployment diagnosis can start independently.

### 4. Establish catalog, profile, context and eligible capture

- [ ] Obtain the real verified runtime catalog; append an immutable behavior-profile version through supported SC-641 operator-authenticated APIs. Resolve missing operator authorization through the supported configuration path, not fabricated catalog/profile rows.
- [ ] Pin task/profile/schema and graph revision when applicable; bind the exact BehaviorRunContext and retain original dataset role/privacy constraints.
- [ ] Prefer an eligible finalized provider-origin recording. If a new capture is needed, use the authorized recorder flow and retain true origin, ordered semantic actions, observations, receipts and termination.
- [ ] Historical INFERRED assignment is a separate explicitly authorized, reasoned path; never relabel it CAPTURED or silently substitute an authored/xRSPS trace for provider-origin evidence.
- [ ] Verify published curation selection, immutable source/artifact/context lineage and completeness before simulation eligibility.

Exit: an input eligible for the claimed evidence scope exists. If original trust/source requirements remain unmet, keep the genuine-source gate blocked and continue only clearly labeled development/authored coverage. P1/P7/P8.

### 5. Complete deterministic compiler and travel provenance

- [ ] Reconcile compiler/contracts with current candidate without discarding newer work; preserve inert bounded import and fail-closed simulation admission.
- [ ] Produce byte-identical manifests/checksums for identical immutable input/compiler versions. Every field/action links to source or audited authorized fixture with OBSERVED/DERIVED/OPERATOR_SUPPLIED/DEFAULTED provenance.
- [ ] Separate initial state, pinned static content and deterministic dynamic spawn rules. Resolve targets at action execution tick across regions and planes; do not inject later observations into initial state.
- [ ] Validate exact bank/widget/item/slot/option/quantity, jewelry destination/charges and exchange NPC/interface semantics. Preserve published curation edits and original evidence mapping.
- [ ] Cover gaps, duplicates/reordering, off-by-one ticks, missing hidden state, unsupported schema/cache and ambiguous target/widget with stable source-linked reasons. Preserve incomplete playback usefulness without causal admission.
- [ ] Recheck role/privacy preservation and import size/schema/depth/checksum/data-only restrictions.
- [ ] Retain original historical archive hash and precise recording_b83c37e5 verdict. Captured-action mapping coverage is separate from stream completeness.

Exit: 274-1 through 274-7, 274-travel and 274-historical each have explicit positive/negative evidence or a truthful open blocker. P1/P7/P8.

### 6. Complete real native world/activity parity

- [ ] Reuse current production xRSPS handlers/world/content, explicit dependencies and SC-1991 integration. Keep xRSPS the sole causal runtime behind the adapter.
- [ ] Finish bank, jewelry, exchange, quest and regional/client projection baseline.
- [ ] Inventory the exact promised Fishing, glassblowing, alchemy, superheat, enchantment/combat methods and auxiliary movement/drop/equip/object/dialogue actions. Do not imply every method is supported from one positive example.
- [ ] Compare production/headless resources, requirements, quantities, charges, levels, timing, XP, effects, interruption, combat/NPC/player state and failure behavior using pinned reference cases.
- [ ] Prove the authored bank-to-travel-to-Fishing route independently of genuine-source capture; retain truthful fixture labels and unknown dynamic-state blockers.

Exit: 276-boundary/methods and travel world support have method-by-method evidence; advertise only verified capabilities. P2/P3/P7/P8.

### 7. Finish atomicity, determinism and durable continuation

- [ ] Validate expected tick/revision before atomic action; invalid/stale/unsupported requests mutate nothing. Enforce bounded advance and declarative stop/success/failure conditions.
- [ ] Include pending actions, queues, timers, RNG/time, world/dynamic state, source cursors and stop state in future-complete snapshot/restore/trace.
- [ ] Compare uninterrupted execution with fresh-worker restore, repeated seeds and parallel runs on exact suffix/domain hashes/trace/outcome.
- [ ] Retain and rerun affected real PostgreSQL fencing/persistence/two-JVM recovery/export tests on final inputs; verify terminal restore dispatches no step/reset and owner/auth boundaries hold through the production path.

Exit: 276-state and durable continuation criteria pass for final pins; previous test-only principal evidence is not promoted to production authentication. P5.

### 8. Qualify runtime closure and actual worker isolation

- [ ] Complete RuntimeClosureManifest coverage for modules, handlers, scripts, native assets, content/cache, scheduler, RNG/time, filesystems/network and disabled services.
- [ ] Reject missing, unexpected, modified, aliased/symlinked or out-of-closure dependencies before execution; cover CJS/ESM/dynamic loading and preserve last supported pin.
- [ ] Enforce actual offline egress denial, file read/write boundaries, subprocess/thread restrictions, cancellation, CPU/memory/time budgets and per-run isolation at the supported OS/process boundary.
- [ ] Run cold/fresh/parallel worker cases and deliberate violations/regressions. Keep existing request limits; no timeout increase or guard bypass to obtain green results.

Exit: 276-closure/safety pass through actual workers. P4/P6. Can advance alongside steps 2–7 using truthful authored fixtures.

### 9. Build paired worker/client packages

- [ ] Build worker and real client from selected clean inputs with complete closure inventories and exact adapter/build/cache/content/schema pins.
- [ ] Generate the paired release/client asset manifest only after the worker package qualifies; validate every asset/path/hash and reject missing/mismatched packages before use.
- [ ] Run packaged-worker Java integration, including the gated durable session lane on an owned disposable database. Verify cold-start behavior at existing limits.
- [ ] Keep old shared replay consumers disabled. Use owned queues/runs/artifacts for qualification; monitor untouched historical attempts when sharing infrastructure is unavoidable and authorized.

Exit: reproducible paired artifacts and passing integration evidence; a successful web-client build alone is insufficient. P4/P6/P9/P11.

### 10. Verify real actor lifecycle, browser controls and Watch

- [ ] Establish a working approved computer-use surface and inventory the actual browser/client before interaction; recheck current capability rather than assuming earlier helper failures persist.
- [ ] Reuse SC-1991 read-only gateway so the actual 3D client observes the same authoritative world. Keep opt-in LIVE_XRSPS explicitly non-certifying and networking out of the certified worker.
- [ ] Validate authenticated exact-origin controller and actor ownership. Exercise Start/Retry/Watch, pause/resume, supported single-tick controls, reconnect, cancel, import and evidence export; enforce tick-boundary changes, idempotent lifecycle and scoped teardown.
- [ ] Inspect bank widgets/quantities/charges, travel/region/plane changes, Fishing, inventory and XP against structured receipts/domain state.
- [ ] Compare headless, client-attached, watcher join/leave, camera and disconnect variants for equal causal outcomes. Screenshots alone do not prove noninterference.
- [ ] Record automated computer-use checks accurately; obtain the separately required human browser Watch sign-off as the final review of completed evidence.

Exit: 276-live/client and actual Watch requirements pass without expanding fixture claims into genuine capture. P9/P10.

### 11. Close fresh-deployment and full qualification gaps

- [ ] Reconcile missing original recording change-request/proposal/build-job migrations with reused V58–V60 numbers. Use a reviewed forward-compatible strategy; do not rewrite released history or reset shared databases.
- [ ] Prove fresh deployment from an empty owned database and supported upgrade from existing history, including strict schema validation, production table presence and data preservation.
- [ ] Run final-pin compiler/schema/import/provenance/curation/privacy/auth tests, complete native activity/state/closure/isolation/resource/negative suites, packaged client/worker integration, differential/oracle, stress/performance and CI checks required by the original matrix.
- [ ] Record exact versions/hashes, commands, durations, failures and skips. Required coverage cannot be silently omitted; deliberate regressions must be detected. Reuse prior results only when their pinned inputs and coverage remain applicable.

Exit: 276-certification and remaining compiler/privacy/deployment criteria pass for the final deliverable. No certification/control flags change merely because selected suites pass. P11 plus deployment prerequisite.

### 12. Review, rollback and truthful delivery handoff

- [ ] Complete a scoped diff/security/privacy review across all owning repositories; retain unrelated dirty work and all historical failed evidence.
- [ ] Write Implementation Evidence with Changed files, Tests and Remaining work. Write Verification Evidence with Acceptance results, Tests, Risks and Rollback. Map every original acceptance row to exact final evidence or blocker.
- [ ] Document rollback per isolated service/configuration/package and additive migration strategy. Do not use destructive reset or shared database restore as routine rollback. Do not run wsl --shutdown.
- [ ] Keep original-trust acceptance explicitly blocked until met or amended; dev-key success cannot substitute for it. Keep authored, provider-origin, inferred and LIVE_XRSPS evidence distinct.
- [ ] Publish/merge only genuinely ready reviewed work within authorization. Felix stays skipped for this continuation, so do not claim workflow advancement or Shortcut synchronization. If that instruction is later revoked, resolve original task/stable tags and pending records first; use actual validation gates and reviewed mappings, never duplicate tasks or fabricate markers.

Exit: each story is independently releasable only when all its acceptance and test requirements pass; otherwise report exact remaining blockers rather than Done.

## Dependencies and next action

Immediate next action is step 2's clean reviewed source snapshot and isolated development experiment. Registry grant diagnosis (step 3), compiler/native qualification (steps 5–8) and fresh-deployment work (step 11) can progress independently with scoped evidence. Eligible-source acceptance depends on actual trust, registration/catalog and profile/context prerequisites; do not block unrelated authored qualification merely because genuine source remains unavailable. Paired packages precede final real-client/Watch and whole-system qualification.

## References

- C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261016\plan.md
- C:\Dev\IdeaProjects\verification\sc274-sc276-20261015\native-db-configured-counter-completion-plan.json
- C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\items-1-2-independent-review.md
- C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261018\worker-startup-followup.md
- C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261018\registry-address-fix.md
- C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261018\source-runtime-assessment.md





## Subsequent independent review of the development runtime

The development-signed runtime on 8180 now passes independent source endpoint, artifact/hash and packaged-validator signature/rejection checks. The original trust-root requirement remains blocked. Full step 2 is not closed: isolate default client home/session and writable runtime state, scope signing material to preparation, and complete actual ChromaScape integration and selected-revision tests. Before registry registration, verify the executor route from the registry process/container. Detailed review and next steps: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\step2-independent-review.md. No existing service or trust configuration was changed by this review.

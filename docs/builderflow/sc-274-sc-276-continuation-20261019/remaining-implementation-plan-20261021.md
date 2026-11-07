# SC-274 / SC-276: remaining implementation plan

Current remaining sequence after execution and review fixes:
[remaining-plan-after-review-20261021.md](remaining-plan-after-review-20261021.md).
The historical plan below is retained with its original evidence boundaries and corrections.

Date: 21 October 2026. Status: planned work after static review of WP1-WP6 evidence.

This is the current remaining implementation sequence. It supersedes earlier next-action
summaries, preserves historical results and retains all17 original acceptance rows in
[delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md). Previous detailed
file ownership remains in [implementation-plan-20261020.md](implementation-plan-20261020.md).

## Baseline and claim boundaries

| Area | Existing evidence; what remains |
|---|---|
|WP1 credentials|Purpose-correct old/new rotation19/19, protected store and stdin SQL handling; prior destroyed-token evidence gap retained. No blanket rotation rerun needed.|
|WP2 artifact isolation|Run6 persistence9/9, continuation1/1, prepare1/1, resume1/1; bound owned roots, default root unchanged and archived XML hashes checked. Test-lane scope only.|
|WP3 source integration|AUTHORED_FIXTURE positive16/16 through supported report-bundle API, legacy negative4/4 and historical reads4/4. Genuine/original-trust acceptance remains open.|
|WP4 deployment|Recorded owned Linux registry8182 with owned PostgreSQL/auth/fences. Container-to-runtime route smoke passed, but exact authenticated source identity still needs verification.|
|WP5 grants|Live operator-issued checks23/23 and pinned Linux harness support mechanism/fencing/refusal. Replacement is sequentially tested; concurrent publication/readability is not proven. Catalog/schema hashes are fixtures.|
|WP6 allocation|Investigation found Launcher forwards allocation metadata but does not issue it. Supported allocation issuer input is missing; do not fabricate it.|
|WP7-WP12|Genuine input, full behavior, production isolation/performance, migrations, actual packaged client/Watch and final rollout remain unqualified.|

Review was static plus recorded evidence. Current container health was not independently
confirmed because Docker status did not complete. The recorded final ONLINE generation10
was short-lived and is not a current availability claim. The pinned registry worktree was
absent at review; restore exact source before further qualification.

## Locations, ownership and rules

| Purpose | Windows path |
|---|---|
|Compiler candidate|`C:\Dev\IdeaProjects\.worktrees\sc274-completion`|
|Owned compiler service/build mirror|`C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp`|
|Native candidate|`C:\Dev\IdeaProjects\.worktrees\sc276-completion`|
|Selected Micro6 development source|`C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime`|
|Recorded registry source location|`C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1` (restore/verify before use)|
|Evidence V19|`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019`|
|Evidence V20|`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020`|
|New evidence V21|`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261021` (create when execution begins)|

Read applicable AGENTS before work. Reinspect revisions, scoped diffs, artifacts, process
command lines, container IDs/labels and paths before mutations. Historical PIDs/labels alone
are insufficient. Use protected secret storage; do not copy credentials into ordinary evidence.

Never run `wsl --shutdown`. Preserve shared8090, shared registry/database, canonical trust,
existing replay attempts and unrelated dirty work. Keep replay consumers disabled. Do not
restart stopped8098 implicitly. User performs login when required for authorized capture.
Do not fabricate provenance/allocation, promote fixture labels or silently amend acceptance.
Felix remains explicitly skipped; no Shortcut/Felix writes or workflow claims.

## 1. Restore reproducibility and inspect current owned resources

**Change:** owned source/evidence/configuration only.

- [ ] Restore a clean isolated registry worktree/archive at exact
  `58098690b83a3c0914016ddbe69344657401eb47` from the canonical backend repository or a verified
  preserved archive. Never replace unrelated source. Verify source/archive/image identities and
  retain Dockerfile/build inputs; HEAD alone does not attest untracked/modified inputs.
- [ ] Inspect actual registry/runtime/compiler listeners and owned containers/volumes/network.
  Resolve Docker connectivity without resetting shared infrastructure or shutting down WSL.
- [ ] Record current health, grant expiry, heartbeat and fence high-water state. Expect old grants
  to have expired; use the documented issuer and appropriate current generation before testing.
- [ ] Freeze relevant source/config hashes and secret-free runtime metadata into V21.

**Exit:** reproducible source and ownership-verified live prerequisites. If Docker remains
unavailable, retain the exact error and continue independent source/test work.

## 2. Fix pre-publication grant readability and test concurrent readers

**Files:** V20 `registry-deploy\live-grants-8182.py`, issuer/deployment configuration and
additive experiment tests. Pinned production source stays unchanged unless a genuine product
defect requires a separately reviewed revision.

Observed gap: `issue()` publishes root-owned0600 output, then launches another container to
chmod0644. Registry reads between those operations may fail. Existing10-iteration soak only
reads after both complete, so it cannot prove uninterrupted replacement.

- [ ] Determine actual registry/issuer UID/GID and effective permissions on the mounted directory.
- [ ] Make the new snapshot readable by the intended registry identity before atomic rename.
  Prefer issuer execution under the registry's intended UID or a supported dedicated reader-group
  design. Verify the issuer's temporary and final file permissions, mount behavior and directory
  durability; do not rely on Windows host ACL assumptions for Linux visibility.
- [ ] Remove the post-publication permission-repair subprocess. If CLI changes are unavoidable,
  create a reviewed new revision and repeat affected mechanism tests; do not call it unmodified5809869.
- [ ] Run a bounded publisher and concurrent registry readers. Record requests during publication,
  including issued/observed generations and authority/read errors. Keep grants valid throughout
  this test so expiry/clock skew cannot explain failures.
- [ ] Use distinct complete snapshots and verify readers observe only a complete permitted old/new
  state, with no permission-denied/invalid-authority interval. Separately verify issuer failure
  preserves the prior snapshot and restart retains fences.
- [ ] Retain existing sequential soak as historical evidence; do not relabel it concurrent coverage.

**Exit:** registry-readable atomic publication under concurrent reads with exact permissions and
failure evidence. Rollback stops owned publisher/readers and restores reviewed publication tooling;
never reset fences or restore an expired/older-generation grant as an availability workaround.

## 3. Verify the authenticated registry-network-to-runtime source path

**Files:** V20 `registry-deploy\deploy-registry-8182.py` route checks and a scoped source probe.

- [ ] Replace broad wget-exit-code acceptance with explicit HTTP status, response contract and
  authenticated source checks from the actual registry network namespace/container context.
- [ ] Fetch manifest using metadata token plus correct audit headers, verify its development
  signature and compare task/environment/repository/build/tree/artifact/snapshot/manifest identity
  with the owned8180 bundle. Fetch a referenced file using content credentials and verify its hash.
- [ ] Pass scoped tokens without argv/log exposure and remove temporary probe state. Record only
  public identities, statuses, verifier results and digests. No new credentials in the registry
  container's persistent configuration merely for convenience.
- [ ] Test wrong credentials/environment/identity are refused. A generic401 or debugger200 alone
  is not proof that the expected authoritative source was reached.
- [ ] Require ChromaScape registry discovery to return the matching endpoint reference. Keep
  synthetic catalog/schema values explicitly labelled; actual catalog qualification belongs to5.

**Exit:** exact authenticated source transport proven across the intended container/host boundary.
Preserve the existing loopback listeners; no implicit LAN exposure or weakened origin checks.

## 4. Qualify clock safety and operational renewal

**Files:** owned issuer/renewal/preflight helpers and test records; production changes only after
requirements review.

- [ ] Measure issuer, registry and heartbeat clock skew with timestamps/round-trip uncertainty.
  Existing approximately45s Docker-host skew is an observation, not a production tolerance.
- [ ] Establish the supported clock-synchronization remedy without `wsl --shutdown`, a host reboot
  or shared service disruption under this plan. If remediation needs disruptive action, record
  that operational prerequisite and continue independent work.
- [ ] Implement a fail-closed preflight for the agreed maximum skew; derive renewal margin from
  <=120s TTL, skew allowance and measured publication/retry latency. Do not extend the protocol
  TTL or suppress expiry checks to hide skew.
- [ ] Test timely renewal under normal/retry conditions, issuer stoppage and restart, and elapsed
  expiry. Keep a high-water generation record and prevent stale renewal from reviving old claims.
- [ ] Identify the eventual operator/service owner for issuance/renewal. Experiment helper success
  is not SC-800 automatic allocation/renewal delivery.

**Exit:** production readiness has explicit clock/renewal requirements and matching evidence,
or a precise operational blocker. Owned experiment grant renewal remains labelled and bounded.

## 5. Obtain supported allocation and prove real Launcher/catalog integration (WP6)

**Required input:** supported SC-800 allocation issuer or explicitly reviewed development allocation
mechanism for a real Launcher-owned machine/session/executor/artifact. This input is currently absent.

- [ ] Confirm allocation ownership/contract and locate the supported issuer/deployment. Record
  exact missing input if unavailable; do not implement unrelated automatic allocation implicitly.
- [ ] Once available, start a separate owned runtime through supported Launcher lifecycle.
- [ ] Capture actual allocation, machine/session/executor and grant bindings. Replace fixture
  catalog/schema hashes with actual immutable catalog/schema artifacts and verify their digests.
- [ ] Select through isolated ChromaScape discovery; prove metadata/source/catalog all address the
  same intended allocated instance. Probe the real catalog route rather than trusting heartbeat claims.
- [ ] Verify heartbeat freshness/expiry, ownership, reconnect/reallocation, session turnover,
  fencing/restart and stale/mismatch refusal.

**Exit:** real supported allocation/catalog selection. Operator-issued fixture23/23 does not
close this gate. No login is needed unless demonstrated by the supported lifecycle/capture contract.

## 6. Resolve original trust and genuine eligible input (WP7)

- [ ] Obtain an approved bundle accepted by the original configured trust root and matching the
  exact client artifact, or document an explicit acceptance amendment. Do not silently replace
  the root. An approved signer may provide the bundle without exposing its private key here.
- [ ] Verify signature/key/environment/repository/build/tree/snapshot/artifact/content identities.
- [ ] Obtain genuine catalog/operator authorization and append immutable profile via SC-641 API.
- [ ] Pin task/settings/schema/graph/curation and establish real BehaviorRunContext.
- [ ] Select genuinely eligible finalized evidence or prepare authorized capture with user login
  as needed. Preserve ordered actions/observations/receipts, termination, role and privacy.

**Exit:** genuine immutable input for its claimed scope. Existing16/16 establishes authored
development integration only. This is an external input gate, not an invitation to fabricate data.

## 7. Finish SC-274 compiler and native behavior acceptance (WP8)

**Surfaces:** compiler/evidence/preflight/provenance/curation/import/export code and tests in the
compiler candidate; production/headless handlers, world loading and adapter snapshots in native candidate.

- [ ] Make an acceptance-to-test gap inventory before implementation and retain applicable prior passes.
- [ ] Prove repeated immutable inputs/compiler version produce identical manifest bytes/checksum.
- [ ] Trace every field/action to source or authorized fixture and pinned published curation,
  with explicit observed/derived/operator/default provenance.
- [ ] Separate initial dynamic state, pinned static content and spawn rules. Resolve later targets
  at execution ticks across regions/planes; never inject later observations into reset state.
- [ ] Verify bank widget/item/slot/option/quantity, jewelry destination/charges, exchange interface/
  NPC and bank-to-travel-to-Fishing completeness. Implement only demonstrated missing semantics.
- [ ] Cover offsets/gaps/reordering/duplicates, absent/ambiguous state and unsupported cache/schema
  with stable source-linked refusal. Inspection/playback must not imply causal eligibility.
- [ ] Verify immutable role/privacy and bounded inert import/export. Reaudit the original historical
  recording with pinned decoder; retain precise blockers if its evidence cannot support completeness.
- [ ] Test every advertised Fishing/glassblowing/Magic/combat/auxiliary method against production:
  resources/requirements/quantities/charges/timing/XP/effects/interruption and combat state.
- [ ] Verify invalid/stale/unsupported actions mutate nothing. Snapshots include pending actions,
  queues/timers/RNG/time/world/source cursors/stop state. Recheck continuation after affected changes.

**Exit:** applicable compiler/native acceptance has final positive/negative evidence. Authored
tests can proceed while5-6 wait; genuine-input claims remain gated. No need to rerun all native
lanes merely because the plan advances—rerun when relevant inputs/behavior change.

## 8. Qualify production performance, closure and actual isolation (WP9)

- [ ] Measure cold/warm startup/requests through actual production transport/configuration against
  explicit deadlines. Existing180s/3600s authored budgets do not establish production suitability.
- [ ] Diagnose startup cost and implement justified performance/config changes; do not silently
  raise production limits to hide a failure. Include timeout/cancellation/cleanup refusal cases.
- [ ] Inventory modules/scripts/handlers/assets/content/cache and dynamic loader closure.
- [ ] Reject altered/missing/unexpected/aliased/symlinked/out-of-closure dependencies before run;
  preserve last supported package pin on failure.
- [ ] Exercise actual egress denial, filesystem/process/thread boundaries, cancellation and CPU/
  memory/time budgets with cold/fresh/parallel workers and deliberately injected violations.
- [ ] Fix missing enforcement in owning code, backed by regression evidence. Mocked refusal and
  authored Windows transport are not operating-system sandbox proof.

**Exit:** production-path budgets and actual worker boundaries pass on the final pin.

## 9. Implement supported database installation and upgrade (WP10)

- [ ] Inventory actual released histories, missing tables and reused V58-V60 numbering.
- [ ] Design forward-compatible migration/check corrections before editing. Do not rewrite released
  migrations/history, reset shared stores or use a shared-database restore as routine rollback.
- [ ] Test empty install and supported existing-history upgrades on disposable owned databases,
  preserving data/constraints and checking schema/Flyway identity and failure behavior.
- [ ] Include registry bootstrap/migration requirements in the owned deployment review. The copied
  schema used by both experiment services is explicitly not migration-compatibility proof.
- [ ] Run scoped formatting/style checks; preserve unrelated pre-existing violations.

**Exit:** supported installation/upgrade paths have final evidence or an exact compatibility blocker.

## 10. Package paired worker/client and verify real lifecycle/Watch (WP11)

- [ ] Build reviewed clean worker/client inputs with full adapter/build/cache/content/schema/assets
  inventory. Qualify worker before generating release/client manifest.
- [ ] Refuse absent/mismatched packages/assets before execution; run packaged Java/durable lanes.
- [ ] Establish approved computer-use surface and real client attached to the same authoritative world.
- [ ] Exercise authenticated exact-origin Start/Retry/Watch, pause/resume, supported tick controls,
  reconnect/cancel/import/export, one-actor lifecycle and scoped teardown.
- [ ] Compare bank/widgets/travel/Fishing state/receipts and watcher/camera/disconnect variants for
  equal causal outcomes. Obtain required separate human Watch sign-off.
- [ ] Keep LIVE_XRSPS opt-in/non-certifying and qualified offline workers network-isolated.

**Exit:** paired package identity, actual lifecycle/noninterference and human gate pass.

## 11. Final matrix, authorized rollout and story closure (WP12)

- [ ] Freeze final source/config/artifact pins; run required compiler/provenance/curation/privacy/
  auth, native state/method/closure/isolation/resource/negative, packaged, differential/oracle,
  performance/stress and CI checks. Deliberate regressions must be detected.
- [ ] Reuse prior tests only for unchanged relevant inputs/coverage. Archive commands, durations,
  XML/results/hashes and failures/skips/unrun checks. Child-only harness skips must remain explained,
  not silently converted into passes or treated as omitted fresh-process coverage.
- [ ] Prepare concrete shared8090/registry rollout with route, protected credentials, renewal owner,
  clock requirements, durable fences, monitoring and per-component rollback.
- [ ] Obtain deployment authorization, then use documented Windows wrapper. Preserve disabled replay
  consumption/historical attempts. Verify effective config/outbound destination, health and auth/refusal.
- [ ] Review scoped multi-repository changes and link every acceptance row to final evidence.
  Produce Implementation Evidence (Changed files/Tests/Remaining work) and Verification Evidence
  (Acceptance results/Tests/Risks/Rollback). Publish/merge only reviewed authorized work.

**Exit:** all required story gates have evidence for actual delivered inputs, or a precise partial
handoff retains blockers. No automatic certification/control or tracker-state upgrade.

## Acceptance mapping

Numbers refer to remaining steps above. Existing scoped evidence supports these gates where
inputs and coverage remain applicable; none of the rows is marked complete by writing this plan.

| Original row | Required remaining evidence |
|---|---|
|274-1|6,7,11: deterministic immutable-input bytes/checksum|
|274-2|6,7,11: exact provenance and pinned curation|
|274-3|7,11: stable source-linked incomplete/invalid failures|
|274-4|5,6,7,10,11: genuine native Fishing causal execution|
|274-5|7,11 plus applicable provenance results: incomplete/legacy causal refusal|
|274-6|6,7,11: immutable privacy/dataset role|
|274-7|7,11: bounded inert-data import|
|274-travel|6,7,10,11: exact bank/travel/Fishing|
|274-historical|7,11: original-evidence audit or precise blocked verdict|
|276-boundary|7,8,10,11 plus applicable native evidence: real xRSPS behind adapter|
|276-methods|7,10,11: production/headless parity|
|276-closure|8,10,11: dependency/package refusal|
|276-state|7,11 plus applicable run6 evidence: deterministic future-complete restore|
|276-safety|8,11: actual isolated bounded workers|
|276-live|5,10,11: authenticated opt-in lifecycle|
|276-client|6,7,10,11: real client/noninterference/genuine capture|
|276-certification|7-11: frozen-input complete final qualification|

## Execution order and decision gates

Immediate:1 reproducibility/health ->2 readable publication/concurrent test ->3 authenticated
route identity ->4 clock/renewal. Work5 waits on supported allocation input; work6 waits on
approved trust and genuine evidence. Authored behavior7, production boundaries8 and migration9
remain independently actionable. Package/client10 needs relevant behavior/state/isolation inputs;
final11 uses frozen deliverables and separately authorized shared rollout.

Do not stop all local work because allocation/trust is blocked. Conversely, no authored fixture
or operator-issued heartbeat closes those external gates. Human login/Watch and deployment
authorization are requested only for their concrete ready-to-execute steps.

## Evidence, rollback and writing verification

Each executed step records changed files, exact pins, commands, outputs and remaining limits
under V21. Secrets stay protected and out of argv/logs/evidence. Preserve failed attempts and
original result files. Cleanup only ownership-verified resources; preserve fence high-water state.
Credential rollback rolls forward to fresh protected values, never exposed or destroyed values.

This update writes documentation only. It starts no service, runs no tests, rotates no secret,
changes no source/deployment/database and makes no Felix/Shortcut mutation. Estimated effort
remains uncertain until remaining behavior/isolation gaps are tested; no zero-code-change promise.

## Review correction (21 October 2026)

Discovery 404 was measured against 8180, not ChromaScape 8181. The corrected 8181 call returns 200 with an expired, stale/unselectable experiment executor. The missing-endpoint and different-build diagnoses above are superseded. The clock helper also mislabeled elapsed renewal time as remaining time; remaining threshold is the required margin, and elapsed deadline is TTL minus the margin. Production clock synchronization remains unresolved. Container source credentials now travel through stdin rather than child-process arguments. Full changes, tests and limits: C:/Dev/IdeaProjects/verification/sc274-sc276-continuation-20261021/review-fixes-20261021.md. Original evidence is retained.

# SC-274 / SC-276 full delivery plan after step 2 review

Date: 2026-10-19. This is the current execution plan following the independent development-runtime review. It supersedes current-status statements in full-remaining-plan.md, while preserving that document and every dated earlier result. It retains the original P1–P11 scope and 17 acceptance rows. Planning is not implementation or acceptance completion.

## Objective and boundaries

Current execution order after stage E persistence and the operator-issued grant decision:
[current remaining delivery plan](current-remaining-delivery-plan.md). That dated plan
supersedes earlier next-action/status text here, including the original planning handoff.
This document retains the full original A-N scope and 17 acceptance rows.

Current continuation evidence: [startup validation and owned full-service progress](startup-validation-and-full-service-progress.md).
The repeat prepare/serve cycle and 32 focused startup/transport/verifier tests passed;
the post-rebuild trust harness passed 15/15. Owned runtime is on 8180 and an owned full
ChromaScape service/database is on 8181/55439. Stage E remains incomplete for authenticated
persisted source-run/debugger reads; original-root acceptance and stage F remain open.
The repeat Gradle cycle reused up-to-date JAR outputs, so a forced clean rebuild remains
unrun. Historical baseline PIDs and unchecked original planning rows below must be read
with the dated progress notes; they are not current process inventory.

Deliver deterministic, provenance-preserving semantic scenario compilation (SC-274), executed by the real xRSPS causal runtime (SC-276), with verified persistence/recovery, isolation, packaged runtime/client identity and actual client/Watch behavior. Complete only the claims supported by final evidence.

The authorized Micro6 development-key experiment is a separate validation path. It may establish integration behavior but does not satisfy the original requirement to use ChromaScape's previously configured trust root. That original gate remains blocked until a matching approved signed bundle is obtained or acceptance is explicitly amended. Do not silently amend it through configuration changes.

Never run wsl --shutdown. Preserve original archives, dirty checkouts, the source-disabled 8098 client, canonical trust settings, unrelated services and existing replay attempts. No game credentials or automated login. No fabricated provenance, hidden world state, catalog/profile data, authority grants or certification. Keep offline qualified-worker execution separate from opt-in, non-certifying LIVE_XRSPS networking.

Felix is skipped by the user's instruction for this continuation. No local note or completed checklist represents a Felix write, workflow transition or Shortcut synchronization. Do not create replacement tracker tasks or post completion markers.

## Projects and evidence ownership

| Role | Windows path |
|---|---|
| Canonical compiler identity/config | C:\Dev\IdeaProjects\ChromaScape |
| Compiler candidate | C:\Dev\IdeaProjects\.worktrees\sc274-completion |
| Canonical Micro6 | C:\Dev\IdeaProjects\micro6 |
| Development source snapshot | C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime |
| Canonical native runtime | C:\Dev\IdeaProjects\xrsps-typescript |
| Native candidate | C:\Dev\IdeaProjects\.worktrees\sc276-completion |
| Backend identity | C:\Dev\IdeaProjects\leeuwbotbackend |
| Live backend Git/source | C:\Dev\IdeaProjects\leeuwbotbackend\leeuwbot-api / nested leeuwbotapi |
| Independent current evidence | C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019 |

Read active repository instructions before edits. Confirm the actual backend deployment/source revision before changing grant-authority behavior; do not infer it from an older checkout. Use Windows paths in all durable project/entity references.

## Verified baseline and unresolved claims

- Registry connectivity: LAN override to http://192.168.1.6:8084 works through candidate PID 195400 at the independent review. Historical relay-path failures and cold/soak records remain finite-sample evidence, not a proven permanent relay repair.
- Registry usability: last independent discovery returned one non-selectable stale/unavailable record with incomplete executor/catalog identity and ENDPOINT_GRANT_AUTHORITY_NOT_CONFIGURED. Re-fetch before acting; do not assume this record is the new Micro6 runtime.
- Development runtime: PID 185188 at 127.0.0.1:8180, source mode authoritative, snapshot 429e32978b1027ca913660ef38f69b92d35bb3c0. Revalidate process identity before any scoped action; PIDs are historical identifiers, not safe kill targets by themselves.
- Preserved existing client: PID 242640 at 8098, source mode disabled. No restart or login was performed in the independent review.
- Packaged JAR digest: sha256:96c912c9cdecfa49ccd7125c52b68394190dbd11d5ebe74042d065f3c06a71e0. Promoted bundle srcbundle_fdd4e78fcc5739645ef0727a; source snapshot srcsnap_0585920d2af05903264627ba.
- Independent positive checks: source manifest/file/symbols 200, served manifest equals promoted manifest, fetched file hash matches, final JAR/sidecar/bundle/manifest artifact identity agrees. Complete packaged bundle validation and served-manifest signature verification pass.
- Independent negative checks: five HTTP authorization/audit/environment rejections pass, and wrong public key, wrong key ID, modified payload and modified signature are rejected by the packaged validator.
- Remaining isolation gaps: default client home/session, runtime-created Git changes, signing material inherited by serving process. ChromaScape integration and source compatibility for the older selected revision remain unverified. Focused Gradle suites were not rerun during independent review.
- Prior Java/native/client/database/recovery passes apply only to their recorded sources. The selected 188-test native pass is not whole-runtime certification. Existing SYNTHETIC_CONTRACT, certifiable=false and controlAllowed=false policy remains unchanged.

## Execution order

### A. Freeze inputs and correct the record

Dependencies: none. Relates to all P phases.

- [ ] Pin HEAD, source/configuration diffs, packaged identities and evidence hashes for all participating candidates. Preserve all original work and failed evidence.
- [ ] Record that 429e32978b is an intentionally older source snapshot; list the features/fixes required from it and compare with the current ChromaScape contract. Do not claim canonical HEAD qualification.
- [ ] Correct task documentation by revision: selected snapshot uses generateAllTaskSourceBundles/runBuilderGoalAuthoritative; newer canonical source has prepareMicro6SourceBundle. The earlier prepareAuthoritativeSourceBundle name is incorrect.
- [ ] Correct the verification JSON timestamp: it claims 02:35 UTC but its PID was created at 02:59:17 UTC. Preserve the original record and append actual observation/correction metadata rather than inventing a historical timestamp.
- [ ] Link the corrected 24-hex snapshot ID from the earlier build note. Make clear that a helper process starting successfully is not readiness.

Exit: an exact, reviewable baseline and dated corrections exist. Deliverable: baseline inventory and corrected/superseding evidence references.

### B. Isolate client home, session and writable runtime state

Dependencies: A. Must precede game login, broader client operation or a replacement launch.

- [ ] Identify supported configuration for the actual child JVM's home/config/session/cache/plugin paths. Configure dedicated experiment paths; setting only a Gradle parent property is insufficient unless child behavior is verified.
- [ ] Inventory runtime writable paths. Current .micro6 reservation snapshot and lock/state files made the supposedly clean build checkout dirty; preserve them as runtime evidence.
- [ ] Choose a supported external runtime-state/working directory or a separate run location so generation sees a clean immutable source checkout. Do not delete state or weaken clean-tree checks to force a pass.
- [ ] Confirm no experiment config/session/log/state file resolves into the preserved client's home or canonical source checkout.
- [ ] Document owned PID/process hierarchy, port collision refusal, cleanup and rollback. If restart is necessary, replace only the experiment after confirming ownership and preserving its state.

Verification: effective child paths and process settings; before/after file inventories for owned/shared directories; unchanged 8098 process; clean build-source status after a bounded serve run. A new port alone does not pass isolation.

Exit: dedicated client and runtime state, with no observed writes into preserved namespaces.

### C. Separate signing from serving and make launch reproducible

Dependencies: A/B; design can begin alongside B.

- [ ] Review existing launcher dataflow: it currently loads the private signing key into Gradle and the client. Restrict private-key access to the preparation process; serving retains public verification material and only required scoped tokens.
- [ ] At the selected revision, authoritative launch depends on generation. Do not simply remove the key and break startup. Select or implement a supported serve-only path that validates a prebuilt bundle against exact JAR/sidecar identity and fails closed before launch. Any code change needs its focused launcher/source-mode tests.
- [ ] Fail clearly on unsupported revision/task combinations, missing bundles, wrong build/digest, dirty build inputs and occupied ports. Keep argument files private and avoid secrets in command lines, logs and exception text.
- [ ] Add bounded readiness checks for owned process, authoritative mode, exact identity and authenticated source availability. Propagate build/launch/readiness failures rather than returning success when cmd starts.
- [ ] Test repeated prepare/serve cycles without regenerating keys, overwriting unrelated files or modifying immutable source inputs.

Verification: signing process has required material; serving child is launched with an explicit allowlisted environment excluding private signing input. Positive launch and deliberately invalid bundle/artifact/mode cases produce the expected result.

Exit: reproducible prepare/serve lifecycle and scoped rollback. Deliverable: reviewed helper changes, effective configuration metadata and focused test evidence.

### D. Qualify the selected source package and contract

Dependencies: A; rerun affected tests after B/C changes.

- [ ] Run selected-revision generator, canonical JSON/schema, bundle/catalog, source-mode, endpoint-security and launch-contract tests. Use actual available test/task names from that checkout.
- [ ] Verify clean source identity, exact final JAR digest, sidecar, promoted pointer, bundle completeness, environment, signature and all referenced content objects. Retain already verified independent evidence when inputs are unchanged.
- [ ] Cover altered content, forged signature/key ID, wrong public key, wrong artifact, missing/stale bundle, malformed IDs and invalid audit context. Use private copies/in-memory mutations, never corrupt the served bundle for a test.
- [ ] Compare actual served fields and schema behavior with current ChromaScape requirements. If incompatible, selectively port reviewed fixes into a new clean source revision, rebuild/sign and repeat affected checks. Preserve the original experiment as dated evidence.

Exit: verified exact package and current-consumer compatibility, with tests tied to final selected inputs. No original-trust acceptance implied.

### E. Run the isolated ChromaScape trust experiment

Dependencies: B/C/D. No game login required.

- [ ] Inventory ChromaScape startup consumers, database/schema, migrations, Redis/queues, artifact roots, schedulers and outbound integrations. Allocate owned disposable state and an unused port; disable replay consumption and unrelated auto-start behavior before launching.
- [ ] Configure only this experiment with Micro6's development public key/key ID/environment and source route. Preserve canonical .env.local trust and service 8090. Do not silently clone credentials or write to shared production namespaces.
- [ ] Through real ChromaScape transport/validator code, consume manifest/file/symbols with the required audit headers. Pin source and artifact identity; demonstrate the accepted source is the intended 8180 package.
- [ ] Verify wrong-key, wrong-artifact, changed-content, wrong-environment and unauthorized requests fail closed. Verify a negative case cannot be masked by cached successful data.
- [ ] Compare original configured-trust behavior separately: this development bundle must remain rejected there. Record accepted-development and blocked-original results side by side.

Exit: actual ChromaScape source integration passes under scoped development trust; canonical trust/service remains unchanged. Deliverables: experiment config variable names, owned resources, positive/negative results and cleanup/rollback instructions.

### F. Resolve registry grant authority, routing and runtime selection

Dependencies: diagnosis may start after A; positive registration needs D and its own deployment prerequisites. E and F may be verified in either order without confusing direct source transport with registry selection.

- [ ] Fetch current registry state and identify the owner/deployment revision of ENDPOINT_GRANT_AUTHORITY_NOT_CONFIGURED. Determine the supported authority configuration and authentication model before changing it.
- [ ] Verify executor reachability from the registry's actual network context. A Docker container's localhost is not Windows localhost; do not register 127.0.0.1:8180 blindly or broaden bind exposure without a scoped authenticated routing design.
- [ ] Establish supported runtime allocation, endpoint grant, machine/session/executor identity and heartbeats. Do not synthesize identities, tokens or opaque endpoint references as a substitute for allocation.
- [ ] Require fresh heartbeat/expiry, complete catalog/schema/build hashes, consistent lifecycle state and ownership. ChromaScape must select the intended Micro6 record through the supported registry path.
- [ ] Verify unauthorized, stale, expired, mismatched-allocation and mismatched-artifact records remain non-selectable. Confirm metadata/source/catalog routes resolve to the same intended instance.

Exit: a fresh authenticated selectable runtime with verified catalog identity; connectivity success is recorded separately from runtime availability.

### G. Establish immutable catalog, profile, run context and source eligibility

Dependencies: E/F for the claimed integrated runtime path. Relates to P1/P7/P8.

- [ ] Obtain the genuine verified catalog and supported operator authorization; append an immutable profile version through the SC-641 API, not direct fabricated rows.
- [ ] Pin task/profile/schema and exact graph version when relevant; bind a real BehaviorRunContext before authorized capture.
- [ ] Prefer existing genuinely eligible finalized evidence. Otherwise prepare the authorized capture flow; user performs any login, and runtime/game actions remain within the explicitly authorized flow.
- [ ] Preserve original ordered semantic actions, observations, receipts, termination, dataset role and privacy. Select published curation immutably.
- [ ] Keep CAPTURED, audited INFERRED, authored fixture and LIVE_XRSPS evidence distinct. Historical reassignment requires its own explicit reason/authorization and cannot become CAPTURED.
- [ ] If original trust/authoritative-source eligibility is still unmet, report that blocker and continue only the development/authored tests whose claims remain valid.

Exit: selected input has all provenance/context/catalog/curation prerequisites for its claimed scope. A signed development bundle alone is not genuine-source acceptance.

### H. Finish deterministic compiler and travel completeness

Dependencies: final evidence needs G; authored/negative work can proceed earlier. P1/P7/P8.

- [ ] Reconcile the current compiler/contracts without losing newer candidate behavior. Same immutable input/compiler version must produce identical manifest bytes/checksum.
- [ ] Audit every manifest field/action to original evidence or authorized fixture, with explicit OBSERVED/DERIVED/OPERATOR_SUPPLIED/DEFAULTED provenance and pinned curation mapping.
- [ ] Separate initial dynamic state, pinned static content and deterministic spawn rules. Resolve later targets at their execution tick across regions/planes; never inject later observations into initial state.
- [ ] Preserve exact bank/container widget, item/slot/option/quantity, jewelry destination/charges and exchange NPC/interface semantics. Missing or ambiguous state yields stable source-linked incomplete/rejection reasons.
- [ ] Cover tick offsets/gaps, duplicate/reordered actions, unsupported schema/cache, missing state and ambiguous identity. Preserve useful playback without granting causal eligibility.
- [ ] Verify role/privacy inheritance and bounded data-only import/export. Audit original historical evidence with pinned decoders; preserve recording_b83c37e5's precise blocker if evidence is insufficient. Coverage percentage is not completeness.

Exit: all nine SC-274 acceptance rows have current positive/negative evidence or explicitly remain blocked.

### I. Finish native world and activity parity

Dependencies: authored work can run while G is blocked. P2/P3/P7/P8.

- [ ] Reuse real production/headless xRSPS handlers and pinned world/cache/content. xRSPS remains the sole causal runtime behind the adapter.
- [ ] Complete bank/jewelry/exchange/quest/client projection dependencies and multi-region/plane target loading.
- [ ] Inventory exact promised Fishing, glassblowing, alchemy/superheat, enchantment/combat and auxiliary movement/drop/equip/object/dialogue methods. Advertise only individually verified support.
- [ ] Compare production/headless resources, requirements, quantities, charges, levels, timing, XP/effects, interruption and NPC/player combat state, including refusal cases.
- [ ] Prove authored deterministic bank-to-travel-to-Fishing independently, then apply genuine-source claims only when G/H prerequisites pass.

Exit: production/headless behavior matrix is complete for every advertised method and required world transition.

### J. Complete atomicity, snapshots and durable continuation

Dependencies: relevant I capabilities; P5.

- [ ] Check expected tick/revision before atomic action, reject stale/invalid/unsupported actions with no mutation, and enforce bounded advance/termination.
- [ ] Include pending actions, queues, timers, RNG/time, world state, source cursors and stop state in future-complete snapshots and traces.
- [ ] Compare uninterrupted execution with fresh-worker restore and identical suffixes/seeds; require equal domain hashes, traces, source counts and terminal results.
- [ ] Re-run affected owned PostgreSQL persistence/fencing, two-JVM restart, expired-attempt recovery and artifact export lanes against final inputs. Prove terminal restore dispatches neither reset nor step.
- [ ] Verify production authentication/ownership boundaries; earlier test-principal results do not establish that stack.

Exit: deterministic/durable continuation passes across process boundaries and malformed/stale requests preserve state.

### K. Complete runtime closure and actual worker isolation

Dependencies: can proceed independently using scoped authored cases; P4/P6.

- [ ] Cover modules, handlers, scripts, assets, content/cache, scheduler, RNG/time, filesystem/network and disabled services in RuntimeClosureManifest.
- [ ] Reject modified, absent, unexpected, aliased/symlinked and out-of-closure dependencies before execution, including CJS/ESM/dynamic load; preserve last supported pin.
- [ ] Enforce actual egress denial, file read/write boundaries, process/thread restrictions, cancellation and CPU/memory/time budgets with per-run isolation.
- [ ] Exercise cold/fresh/parallel workers, deliberate violations and regression detection. Keep existing request limits; do not trade guard coverage or longer timeouts for green runs.

Exit: real-worker closure, OS/process boundaries and resource/cancellation/isolation requirements pass.

### L. Package paired worker/client and verify lifecycle/Watch

Dependencies: I/J/K for qualified packages; P4/P6/P9/P10/P11.

- [ ] Build paired worker/client artifacts from reviewed clean inputs with full inventory and adapter/build/cache/content/schema identity. Generate the release/client manifest only after worker qualification.
- [ ] Verify missing/mismatched packages and assets fail before use; run actual packaged-worker Java/durable-session integration on disposable owned state.
- [ ] Keep shared replay consumers disabled. Use owned runs/queues/artifacts and verify historical attempts remain unaffected where applicable.
- [ ] Establish an approved working computer-use surface; inventory actual browser/client before interacting. Reuse SC-1991 gateway and real 3D client attached to the same authoritative world.
- [ ] Exercise authenticated exact-origin Start/Retry/Watch, pause/resume, supported single-tick controls, reconnect/cancel/import/export with one owned actor, tick-boundary effects and scoped teardown.
- [ ] Verify bank/widgets/quantities/charges, travel/planes/regions, Fishing/inventory/XP against structured state/receipts.
- [ ] Compare headless/client-attached/watcher/camera/disconnect variants for equal causal outcomes. Screenshots alone do not prove viewer noninterference.
- [ ] Record agent UI observation accurately; obtain the separately required human browser Watch sign-off after the evidence is ready. Keep LIVE_XRSPS opt-in/non-certifying and networking out of the offline worker.

Exit: packaged identity, actual-client lifecycle, noninterference and required human Watch evidence pass.

### M. Resolve deployment history and final qualification

Dependencies: migration diagnosis can begin after A; final matrix depends on final H–L inputs. P11.

- [ ] Reconcile missing production change-request/proposal/build-job migrations and reused V58–V60 numbering through a reviewed forward-compatible design. Do not rewrite released migrations/history or reset shared stores.
- [ ] Prove empty-database installation and supported existing-history upgrade on owned disposable databases with strict schema checks and data preservation.
- [ ] Run final-pin compiler/schema/import/provenance/curation/privacy/auth suites, full native method/state/closure/resource/isolation/negative coverage, packaged integration, differential/oracle, performance/stress and CI matrix.
- [ ] Record exact hashes, commands, durations, failures/skips, supported pins and cleanup. Required suites cannot be silently omitted; deliberately injected regressions must be detected.
- [ ] Reuse earlier results only where both input identity and coverage remain applicable; code/config changes require affected checks to be rerun.

Exit: final deliverable satisfies required build/deployment and certification matrices. Selected earlier green suites cannot justify changing certification/control flags.

### N. Review, rollback and final handoff

Dependencies: all applicable gates above; open blockers remain open.

- [ ] Review scoped diffs, privacy/security implications and source provenance across owning repositories. Preserve unrelated dirty work and failed historical evidence.
- [ ] Produce Implementation Evidence with Changed files, Tests, Remaining work. Produce Verification Evidence with Acceptance results, Tests, Risks, Rollback. Link every acceptance row to exact final evidence.
- [ ] Document rollback for each owned process/config/package and migration; stop only verified owned experiment processes. Retain old artifacts/state/config and do not restore shared database dumps as routine rollback.
- [ ] Report the original trust-root gate independently of development success. Do not mark either whole story complete with required acceptance blocked.
- [ ] Publish/merge only ready reviewed work within authorization. Felix stays skipped, so no claimed task transitions or Shortcut synchronization. If later re-enabled, resolve original tasks/stable tags, inspect/replay pending records and validate real transitions before synchronization; never create duplicates.

Exit: independently reviewable story delivery or a precise partial handoff with every blocker and unrun check explicit.

## Dependency schedule

Immediate execution order: A -> B/C -> D -> E and F -> G. H–K authored/negative verification and migration diagnosis can proceed while source prerequisites are blocked; the agent should continue that independent work without repeatedly asking permission. Final integration/package/client evidence requires the relevant gates to pass. M's complete matrix runs only on frozen final inputs; N follows actual acceptance.

No login is required for B–F source/trust/registration verification unless a specific supported runtime identity contract demonstrably requires it. Do not ask the user to log in merely to test HTTP source endpoints.

## Acceptance matrix and required evidence

The original 17 Given/When/Then rows are retained verbatim below, with historical prior-status strings removed. Every row remains unchecked for whole-story delivery until its final evidence is reviewed. Steps map as follows: SC-274 rows to G/H plus applicable native/trust gates; 276-boundary/methods to I; 276-state to J; 276-closure/safety to K; 276-live/client to L; 276-certification to M. Original-trust acceptance is an additional explicit prerequisite, not silently replaced by development validation.
- [ ] 274-1: Given identical immutable inputs and compiler version, when compiled repeatedly, then manifest bytes and checksum are identical.
- [ ] 274-2: Given every manifest field/action, when provenance is audited, then exact source or authorized fixture and selected published curation edits are retained.
- [ ] 274-3: Given off-by-one/gaps/reordered/duplicate actions/missing state/cache mismatch, when compiled, then stable source-linked failure.
- [ ] 274-4: Given bounded native Fishing recording, when compiled and run, then causal execution succeeds without raw input dependency.
- [ ] 274-5: Given incomplete or legacy unaudited manifest, when causal execution requested, then blocked even if DB flag says COMPLETE; readable for inspection.
- [ ] 274-6: Given train/validation/held-out role and private source, when converted/imported/exported, then role and privacy survive immutably.
- [ ] 274-7: Given untrusted scenario import, when parsed, then bounded size/schema/depth/checksum with inert data only.
- [ ] 274-travel: Given sufficient bank/travel/native world/collision/action evidence, when compiled, then deterministic exact bank-to-travel-to-Fishing scenario; no later-state injection.
- [ ] 274-historical: Given recording_b83c37e5 original evidence, when audited with pinned decoder, then all gaps resolved or precise blocked verdict retained, coverage separate from completeness.
- [ ] 276-boundary: Given semantic manifest, when reset/atomic step/observe/snapshot/restore/trace/dispose invoked, then sole causal runtime is real xRSPS behind adapter.
- [ ] 276-methods: Given Fishing/glassblowing/enumerated Magic/auxiliary actions, when executed in production and headless, then exact resources/levels/timing/XP/effects/interruption and combat state agree.
- [ ] 276-closure: Given missing/unexpected/unhashed/out-of-closure dependency or incompatible pin, when worker starts, then reject before run and preserve last supported pin.
- [ ] 276-state: Given valid checkpoint including pending future work, when fresh worker restores and same suffix runs, then state/RNG/trace/stop state identical; refusals mutate nothing.
- [ ] 276-safety: Given parallel/cancelled/over-budget run or attempted egress, when actual worker executes, then isolated deterministic bounded execution with enforced denial.
- [ ] 276-live: Given opt-in local server and authenticated exact-origin controller, when start/retry/watch/pause/resume/cancel/reconnect used, then one real transient actor, tick-safe lifecycle, non-certifying evidence.
- [ ] 276-client: Given actual client attached read-only to same world, when watcher joins/leaves or actions replay, then viewer noninterference, real regional bank/widget/travel parity and genuine typed capture evidence.
- [ ] 276-certification: Given clean final build/runtime/cache/content/schema pins, when complete CI/differential/oracle/negative matrix runs, then all required tests evidenced without omitted suites/skips and deliberate regression detected.

## Verification checklist for this execution plan

- [ ] Each stage records selected source/config/artifact hashes and actual commands/results.
- [ ] Each applicable positive result has negative/refusal evidence, with no production input corruption.
- [ ] Source-package tests cover canonicalization/generation/bundle integrity/source mode/security and final launch behavior.
- [ ] ChromaScape tests use its real transport/validator and owned state, with original trust rejection retained.
- [ ] Registry tests use the actual registry network context and real allocation/auth lifecycle.
- [ ] Compiler/travel/provenance/privacy and all advertised native-method matrices are complete.
- [ ] Atomicity/determinism, actual process restore/recovery/export and production ownership/auth are verified.
- [ ] Closure/OS/filesystem/network/resource/cancellation/parallel worker checks pass without weakening limits.
- [ ] Packaged client/worker pinning, actual lifecycle/noninterference and separate human Watch are evidenced.
- [ ] Fresh install/upgrade and final full CI/stress/differential/oracle checks are complete.
- [ ] All acceptance rows, remaining blockers, risks and rollback are explicitly reconciled before delivery.

## Evidence references

- Original scope and acceptance: C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261016\plan.md
- Prior full plan: C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261019\full-remaining-plan.md
- Independent source/runtime review: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\step2-independent-review.md
- Live request/artifact results: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\independent-step2-http-and-artifact-review.json
- Packaged signature/bundle results: C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\independent-step2-signature-review.log
- Build/runtime increments: step2-dev-source-bundle.md and step2-dev-runtime-verification.md in this documentation directory.

## Execution status

2026-10-19 afternoon: stages A–C and the harness layer of E were executed, and F was diagnosed. See
stage-a-c-d-e-progress-20261019.md. Also recorded there: a ChromaScape purpose-token transport
defect and its fix, which applies uncommitted to this candidate and is not yet live on 8090; the
held registry grant-authority decision; and the preserved 8098 client being found stopped.
Checklist boxes below remain unchecked until each stage's exit criteria are fully evidenced.

2026-10-19 evening: stage E's authenticated full-service source-run persistence, debugger
manifest/file/symbol reads, restart and rejection/tamper cases passed on owned 8181 under
development trust with an explicitly labelled authored run fixture. See
stage-e-persistence-progress-20261019.md. The original trust-root gate and stage F remain open.

## Planning handoff

This writing step creates documentation only. It does not fix the launcher/isolation issues, restart a client, configure development trust, alter a database, run a new qualification suite, or change tracker state. The next concrete work is A/B/C: freeze inputs, isolate the client/state paths and separate preparation from serving, then verify the selected package through the actual ChromaScape path.

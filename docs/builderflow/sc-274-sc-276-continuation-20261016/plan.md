# SC-274 and SC-276 continuation plan — 2026-10-16

## Latest verified increment — 2026-10-18

Latest worker-loading and original-archive follow-up:
`../sc-274-sc-276-continuation-20261018/worker-startup-followup.md`.
Plain JavaScript now executes directly from each load's verified bytes while
module syntax retains the guarded transformation. Eleven focused guard/decoder
tests and TypeScript pass. The new 26-suite current-code matrix completed with
188 passes, zero failures/skips/cancellations, exit 0. All three previously
failing worker cases pass with unchanged request limits; the prior failed
matrix remains dated evidence. Full runtime/stress certification remains open.
The source-disabled existing Micro6 process was preserved. No authoritative
source bundle/signing configuration was found in the checked launch environment.
The pinned original archive recheck retains its supported blocked verdict.

This dated section supersedes earlier current-blocker statements below while retaining their historical evidence. The full P1–P11 scope and whole-story acceptance remain open.

### Item 1 registry reachability — resolved at connectivity level, 18 October 2026

The Windows WSL localhost relay (`wslrelay.exe`, `localhostForwarding` default) owns
`127.0.0.1:8084`; every observed failure was on that path (7/30 and 9/60 fresh-connection
probes), while the Docker backend proxy on `0.0.0.0:8084` showed zero observed failures in
the same runs. This isolates the path, not the relay's internal defect or a proven
permanent fix. On this host `localhost` resolves `::1`→`127.0.0.1` (client-dependent).
Applied the reviewed address correction only
(`MICRO6_RUNTIME_SELECTION_REGISTRY_BASE_URL=http://192.168.1.6:8084`, the existing
Docker-backend address) in `ChromaScape\.env.local`, then restarted through the documented
wrapper. Candidate PID 195400 now holds outbound connections to `192.168.1.6:8084` and
opens fresh connections there after idle (new local port) with `/api/micro6/executors`
returning `{"executors":[],…}` and zero error payloads across 40 calls; `/debugger` and
`/xrsps-sessions` are 200; anonymous stabilization is 401. Replay consumption is disabled
(`worker=false`) and no consumption was observed — the nine terminal `replay_attempts`
were byte-identical across a ten-minute window with zero active leases and no other
consumer process. This establishes connectivity only — no source bundle, catalog, profile
or capture. Details:
`../sc-274-sc-276-continuation-20261018/registry-address-fix.md`.

- [x] Restore candidate service on Windows port 8090 using existing canonical ignored configuration, without generating source trust material. Source validator passed, Hibernate is `validate`, Flyway applied no new migrations, debugger and session workspace return 200. Existing replay worker consumption remains disabled to protect nine pre-existing finalization runs from the older configured runtime.
- [x] Confirm public store read-only: schema V72, 82 history rows, zero failed migrations, 119 recordings, zero behavior profiles. Five provenance columns, three provenance tables and the identity trigger predated this continuation; the earlier absent-provenance claim was incorrect. Preserve the earlier DB backup and V50 idempotency edit.
- [x] Integrate compiler-side SC-1991 session workspace, paced playback, AI task coordination, client asset verification/presentation and isolation headers via per-file three-way merge while retaining current source scheduling/provenance checks. Fix semantic action hashing, Windows physical-root comparison and Spring heartbeat construction. Detailed files and backups: `../sc-274-sc-276-continuation-20261018/implementation-evidence.md`.
- [x] Run 115 focused Java tests and 54 additional session regressions with zero failures/errors/skips; native four-case full-byte inventory regression and quests TypeScript check pass. Scoped formatting and full Checkstyle pass with project warnings. Selected native matrix result is recorded separately, including its worker-reset timeout; it is not a full certification pass.
- [x] Diagnose repeated worker startup failures without relaxing acceptance bounds. Initial native matrix: 143 passed, two failed, zero skips. Both failures repeated in isolation. Profiled startup exceeded 60 seconds; implement synchronous verified-byte handoff and no-JSDoc transpilation after byte/diagnostic parity across 788 files. Nine final guard regressions and repeated TypeScript check pass. Both real worker cases still time out after optimization: startup acceptance remains unresolved, and the full final-pin matrix is not claimed.
- [x] Re-run live provenance reads on recording_c73dfff8, recording_b83c37e5 and recording_5e99d252. All return HTTP 200, null immutable context/origin and `RUN_CONTEXT_REQUIRED`, eligible=false. No provenance assignment or promotion occurred; preserve the independent historical blocked verdicts.
- [x] Trace existing authenticated catalog discovery: existing launcher credential works, but registry returns zero runtimes. Signed source manifest request returns 503. Finding startup values did not establish genuine source trust or available catalog data. No operator token is configured for SC-641 profile creation; do not ask the user to guess its location again.
- [ ] Complete genuine source/profile route, qualified native worker/client package, generated client manifest, full residual/certification matrices and actual same-world client Watch/lifecycle/noninterference. Authored fixtures remain separate from genuine capture. The native capability policy still explicitly denies certification/control.
- [ ] Computer-assisted page checks and human Watch: helper inventory recovered, but actual Firefox selection is rejected with `Computer Use was not approved to use Firefox`. The current browser connector exposes no targets. No browser page/action was inspected through computer use. Do not describe the earlier helper lock failure as the current blocker.
- [ ] Reconcile original Felix tasks/outbox and mapped Shortcut states. Original SC-274 task still returns `Project not found` on a successful response; current Felix state unverified. No duplicate task, tracker writes, successful replay or transition claims. Native repository mapping remains absent. Ten relevant existing pending records remain pending.
- [ ] Resolve fresh-deployment gap: original production recording_change_requests/proposals/build_jobs migrations are missing from this branch; V58–V60 now name different migrations. Do not add conflicting version numbers or treat live tables as fresh-deploy evidence.

Current increment evidence directory: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261018`. The canonical source configuration, runtime registry credential and stabilization tokens remain private and are excluded from documentation payloads.

User authorized continuation of both stories. Preserve existing candidate work and all prior evidence. Canonical project identities: C:\Dev\IdeaProjects\ChromaScape and C:\Dev\IdeaProjects\xrsps-typescript. Candidate worktrees: C:\Dev\IdeaProjects\.worktrees\sc274-completion and C:\Dev\IdeaProjects\.worktrees\sc276-completion.

## Additional client-package verification, 18 October 2026

Final selected native matrix after guarded WASM decoder initialization: 187 tests,
184 passed, three timed out, zero skipped/cancelled (exit 1). The source-receipt
reset, player-death replay and ring-enchantment restore failures retain the
unchanged request bounds. Focused worker passes do not establish broader startup
reliability. Fresh capabilities remain SYNTHETIC_CONTRACT, certifiable=false,
controlAllowed=false. Details and final deployment checks are in the linked
client-package continuation evidence; no whole-story acceptance row is closed.

- [x] Build the actual client production bundle on Windows as a diagnostic,
  exit 0, and hash all 39 output assets. Keep it noncertifying; do not create
  the paired release manifest without a qualified worker package.
- [x] Run the complete nine-command client unit test script, exit 0, including
  the four-case observer integrity suite with zero failures/skips.
- [x] Close client-package identity comparison gaps: enforce adapter version
  as well as build/cache/content, with nonblank textual pins. Six focused Java
  tests pass, zero failures/errors/skips; scoped formatting passes.
- [x] Recheck the two failing native worker cases without changing their
  60-second bound before the next optimization: both fail on session.reset.
  Preserve that failure evidence. Then initialize the existing WASM bzip decoder
  behind both worker guards: guarded actual-cache comparison covers 18 blocks
  and 15,924,060 identical decoded bytes. Ten decoder/module/offline regressions
  and quests typecheck pass. Both previously failing worker tests now pass at
  their unchanged request bound. Broader final-change matrix recorded separately.
- [ ] Finish the remaining qualified worker/client, genuine-source, native
  certification, lifecycle, privacy/isolation and human Watch gates above.

Evidence: `../sc-274-sc-276-continuation-20261018/client-package-continuation.md`.
The user explicitly requested skipping Felix during this continuation; no Felix
or Shortcut mutation was made. Full P1-P11 and whole-story acceptance remain open.

## Tracker resolution
SC-274 existing Felix task task_1789960012362_td2qnusp, last reported in_review; current state is unverified. Earlier configured Felix calls successfully returned Project not found, which was not an outage. On 17 October, after rechecking the active http://127.0.0.1:4301/mcp registration, two consecutive configured MCP calls failed at the HTTP transport; a further continuation retry failed identically. The outage protocol now applies. Full planning records were written to each canonical project's docs\builderflow\felix-sync\pending folder, preserving unrelated records. No Felix write, validated state transition or Shortcut synchronization succeeded. Resolve SC-276 by stable tag across both owning roots before any creation; its task identity is unresolved. The current user-supplied delivery contract governs this continuation; SC-276's earlier skip-Felix request remains dated story history. Shortcut reads still show SC-274 In Review/blocked and SC-276 To Do/blocked. ChromaScape's reviewed mapping exists; xrsps-typescript's mapping is absent, so its external synchronization must wait for a reviewed mapping. No completion marker may precede its actual validated Felix transition.

## Scope and exclusions
Continue all existing compiler/native-kernel acceptance below. First reconcile forward current-schema V8 and its real physical fingerprint, verify strict JPA/schema and native persistence/continuation, then reconcile supported captured snapshot versions without weakening completeness/identity checks. Qualify compiler/native world and termination handoff only after positive and negative evidence. Keep authored fixtures separate from genuine capture and retain recording_b83c37e5 precise blocked verdict.
No editing released V1-V7 migrations, shared database reset, fabricated world state, raw input replay, live RuneScape/Micro6 control, or promotion of synthetic fidelity. Preserve offline certified-worker versus separate experimental client-gateway boundary. No WSL shutdown.

## Linked rules and design artifacts
- C:\Dev\IdeaProjects\AGENTS.md and candidate AGENTS.md; canonical Windows MCP identities.
- C:\Dev\IdeaProjects\ChromaScape\.felix\shortcut-sync.json; shortcut_delivery gates and completion evidence headings.
- Existing full design/phase/acceptance record: C:\Dev\IdeaProjects\verification\sc274-sc276-20261015\native-db-configured-counter-completion-plan.json
- Candidate docs\replay: native checkpoint continuation/persistence/database continuation, declarative termination, source scheduling and native NPC binding contracts.
- Source and test evidence remain pinned per increment; earlier passes do not automatically apply to later changes.

## Implementation checklist
- [ ] P1: Reconcile deterministic compiler, source identities/provenance/curation/roles, bounded import and causal eligibility gates onto current ChromaScape main. Prior state: partial-explicit-Java-native-loop-checkpoint-outcome-candidate-unit-and-direct-regressions-current-guarded-handoff-failed-full-delivery-pending
- [ ] P2: Establish real current xRSPS native bank, jewelry, exchange, quest and client projection baseline from draft PR8, including explicit production handler dependencies. Prior state: partial-current-native-selected-regressions-and-authored-compiler-bank-travel-positive-verified-full-parity-and-qualification-pending
- [ ] P3: Integrate shared production/headless glassblowing and exact alchemy/superheat handlers without losing newer native profiles. Implement and verify remaining enumerated enchantment and combat methods, Fishing coverage and auxiliary drop/equip/movement/object/dialogue. Prior state: partial-bounded-authored-manual-Wind-Strike-native-kernel-atomic-refusals-hit-NPC-death-drop-respawn-player-death-guarded-fresh-worker-restore-and-rerun-verified-full-qualification-pending
- [ ] P4: Complete RuntimeClosureManifest hashing/enforcement of modules, handlers, scripts, content/cache, scheduler, RNG/time, filesystems/network and disabled services; reject missing/unexpected/out-of-closure dependencies before run; preserve last supported pin. Prior state: partial-verified-byte-CJS-transpilation-with-pinned-TypeScript-and-cold-cache-bootstrap-verified-full-handler-ESM-filesystem-OS-qualification-pending
- [ ] P5: Verify atomic action plus expected tick/revision and bounded advance; invalid/stale/unsupported no-mutation; future-complete deterministic reconstruction/snapshot/restore/trace including pending actions, queues, timers, RNG, world and stop state. Prior state: PARTIAL_VERIFIED_ANCHORED_NATIVE_RESTORE_FULL_STORY_PENDING
- [ ] P6: Enforce and test offline egress denial, file access boundaries, cancellation, per-run isolation and resource budgets in actual worker; fresh-worker and parallel matrix with deliberate regression detection. Prior state: PARTIAL_VERIFIED_NATIVE_PATH_AND_AUTHORED_JAVA_HANDOFF_FULL_STORY_PENDING
- [ ] P7: Connect exact SC-274 compiler contracts to verified native bank/widget/jewelry/exchange profiles and per-tick target resolution across regions/planes; consume selected published curation edits and exact BehaviorRunContext graph revision. Prior state: partial-closed-bank-curation-compile-import-exact-source-widget-index-and-eight-action-Java-native-route-verified-qualified-world-contract-pending
- [ ] P8: Audit original historical gaps with pinned decoders; preserve precise blocked verdict if evidence is insufficient. Prove separate valid deterministic bank-to-travel-to-Fishing scenario without later observation injection. Prior state: partial-authored-compiler-eight-action-bank-charge-travel-walk-Fishing-route-two-guarded-workers-pending-restore-tick64-identical-checkpoints-original-recording-still-blocked
- [ ] P9: Integrate opt-in LIVE_XRSPS actor lifecycle and controller security, browser Watch, pause/resume/cancel/retry/import/evidence, idempotent reconnect and tick-boundary controls using real server handlers/ticks. Keep networking out of certified worker. Prior state: pending
- [ ] P10: Verify SC1991 read-only gateway attaches actual client to same authoritative world with viewer noninterference, bank/travel regional behavior, human Watch and genuine typed capture wherever parity claimed. Prior state: pending
- [ ] P11: Run full current-pin build/inventory/CI certification matrix, privacy/performance and authoritative differential evidence; review diffs/risks/rollback, then publish and merge only genuinely ready work and reconcile Shortcut truthfully. Prior state: PARTIAL_VERIFIED_ANCHORED_NATIVE_RESTORE_FULL_STORY_PENDING

## Given/When/Then acceptance
- [ ] 274-1: Given identical immutable inputs and compiler version, when compiled repeatedly, then manifest bytes and checksum are identical. Prior status: PARTIAL_VERIFIED_NATIVE_PATH_AND_AUTHORED_JAVA_HANDOFF_FULL_STORY_PENDING
- [ ] 274-2: Given every manifest field/action, when provenance is audited, then exact source or authorized fixture and selected published curation edits are retained. Prior status: partial-closed-native-source-action-audit-original-snapshot-and-frozen-curation-provenance-verified-full-field-matrix-pending
- [ ] 274-3: Given off-by-one/gaps/reordered/duplicate actions/missing state/cache mismatch, when compiled, then stable source-linked failure. Prior status: focused-pass
- [ ] 274-4: Given bounded native Fishing recording, when compiled and run, then causal execution succeeds without raw input dependency. Prior status: partial-current-authored-compiler-exports-through-actual-Java-scheduler-guarded-native-workers-genuine-recording-and-production-eligibility-certification-pending
- [ ] 274-5: Given incomplete or legacy unaudited manifest, when causal execution requested, then blocked even if DB flag says COMPLETE; readable for inspection. Prior status: focused-pass
- [ ] 274-6: Given train/validation/held-out role and private source, when converted/imported/exported, then role and privacy survive immutably. Prior status: partial-current-owner-scoped-policy-authoring-service-MVC-role-preservation-and-broad-selected-Java-tests-pass-current-database-network-authentication-browser-and-complete-privacy-matrix-pending
- [ ] 274-7: Given untrusted scenario import, when parsed, then bounded size/schema/depth/checksum with inert data only. Prior status: focused-pass
- [ ] 274-travel: Given sufficient bank/travel/native world/collision/action evidence, when compiled, then deterministic exact bank-to-travel-to-Fishing scenario; no later-state injection. Prior status: partial-authored-compiler-eight-action-route-unchanged-Java-native-two-workers-bank-charge-path-Fishing-pending-restore-exact-tick64-checkpoints-verified-native-world-qualification-pending
- [ ] 274-historical: Given recording_b83c37e5 original evidence, when audited with pinned decoder, then all gaps resolved or precise blocked verdict retained, coverage separate from completeness. Prior status: still-blocked-partial-hash-pinned-protocol241.1-client-audit-38458-frames-78-selected-packets-1161-reflection-context-failures-server-state-and-original-tick-reconciliation-pending
- [ ] 276-boundary: Given semantic manifest, when reset/atomic step/observe/snapshot/restore/trace/dispose invoked, then sole causal runtime is real xRSPS behind adapter. Prior status: PARTIAL_VERIFIED_NATIVE_PATH_AND_AUTHORED_JAVA_HANDOFF_FULL_STORY_PENDING
- [ ] 276-methods: Given Fishing/glassblowing/enumerated Magic/auxiliary actions, when executed in production and headless, then exact resources/levels/timing/XP/effects/interruption and combat state agree. Prior status: partial-bounded-authored-manual-combat-kernel-and-guarded-workers-verified-full-recording-method-state-and-resource-matrix-pending
- [ ] 276-closure: Given missing/unexpected/unhashed/out-of-closure dependency or incompatible pin, when worker starts, then reject before run and preserve last supported pin. Prior status: partial-cold-cache-guarded-bootstrap-and-verified-byte-CJS-transpilation-verified-ESM-handler-general-filesystem-and-OS-qualification-pending
- [ ] 276-state: Given valid checkpoint including pending future work, when fresh worker restores and same suffix runs, then state/RNG/trace/stop state identical; refusals mutate nothing. Prior status: PARTIAL_VERIFIED_NATIVE_PATH_AND_AUTHORED_JAVA_HANDOFF_FULL_STORY_PENDING
- [ ] 276-safety: Given parallel/cancelled/over-budget run or attempted egress, when actual worker executes, then isolated deterministic bounded execution with enforced denial. Prior status: partial-current-node-denials-before-game-load-cold-bootstrap-and-module-tamper-tests-pass-general-filesystem-no-write-ESM-OS-egress-resource-cancellation-and-complete-isolation-pending
- [ ] 276-live: Given opt-in local server and authenticated exact-origin controller, when start/retry/watch/pause/resume/cancel/reconnect used, then one real transient actor, tick-safe lifecycle, non-certifying evidence. Prior status: pending
- [ ] 276-client: Given actual client attached read-only to same world, when watcher joins/leaves or actions replay, then viewer noninterference, real regional bank/widget/travel parity and genuine typed capture evidence. Prior status: pending
- [ ] 276-certification: Given clean final build/runtime/cache/content/schema pins, when complete CI/differential/oracle/negative matrix runs, then all required tests evidenced without omitted suites/skips and deliberate regression detected. Prior status: pending

## Test checklist
- [x] Real owned PostgreSQL export of all eight current migrations; reviewed actual catalog identity `sc276-current-v2` and fingerprint `sha256:4800631e4d50386bf90f374ca1a92c010ec06f0056e742e9af4225312d3126b2`. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016\native-db-v8-current-schema-export-result.json`.
- [x] aiPlayerSchemaTest: 22/22 passed, zero skips; strict complete entity validation, drift and old-contract refusal, sentinel preservation. Exact source hashes and XML: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016\native-db-current-suites-result.json`.
- [x] replayNativeCheckpointPersistenceTest: 9/9 passed, zero skips; SQL/fencing/artifact cases and exact canonical publication/readback. Same result record; verified owned fixture cleanup.
- [x] replayNativeDatabaseContinuationTest: passed all three variants in six fresh native workers, exact source suffix/terminal/trace/hash bindings and finalized database/artifact parity, zero failures/skips; current persistence repeat also 9/9 passed. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016\native-db-continuation-current-result.json` and its `native-db-continuation-current-output\native-database-continuation.json`. Frozen sources unchanged; exact owned container cleanup confirmed. Fresh JVM, recovery/HTTP export, genuine source admission and production sandbox remain unqualified.
- [x] Focused compiler/import/preflight/curation and native consumer contracts: 379/379 passed with zero skips. Includes complete schema 2/4, rejected mixed/unsupported schemas and committed-attempt source suffix/fence regression. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261016\native-java-focused-final-result.json`. Eight-file scoped formatting and full `checkstyleMain` passed; the latter reported 4,600 project-wide warnings.
- [x] Current two-JVM native restart/recovery/HTTP lane: 11 tests, zero failures/errors/skips; three native continuation variants, four expired-attempt recoveries, 23 checksum-verified downloads and 46 owner-boundary checks; distinct JVM PIDs 157276 / 217056, ten fresh native processes, unchanged frozen inputs and verified owned cleanup. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\native-db-restart-idle-verified-summary.json`. Public admission, production authentication/sandbox, genuine source and client Watch remain unqualified.
- [x] Bounded SC-1991 reuse assessment of locally available reference and current candidates. Native client implementation ancestry confirmed, missing compiler presentation/workspace/control integration identified and missing candidate packaged-client manifest recorded. Evidence: `C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261017\client-integration-assessment.md`. This is an assessment, not completed integration or Windows/client qualification.
- [ ] Full current-pin native method, deterministic/oracle, snapshot/restore, resource/isolation/privacy and negative matrices.
- [ ] Actual client/Watch/genuine capture criteria through SC-1991 boundary; retain unsupported historical verdict.
- [ ] Required build/style/CI and final scoped diff review; record every unrun or blocked check.

## Evidence and handoff
Before review: Implementation Evidence with Changed files, Tests, Remaining work. Before done: Verification Evidence with Acceptance results, Tests, Risks, Rollback. Never claim task writes, transitions, synchronization, test success or cleanup that did not actually succeed.

## Current continuation findings

The first native/database join retained exact terminal snapshot/trace/outcome parity but the helper omitted production reset-event persistence, producing the precise ordinal mismatch `expected -1, actual 1`. The helper now uses the production reset event path. Native source cursor initialization is once per execution/attempt and rejects a different fence; the same committed attempt can resume its suffix after an explicit checkpoint without rebinding nonzero source counters. The added regression passed in the 379-test focused run. The actual six-worker database join subsequently passed all three variants: initial-stop, action-success and per-native-tick failure before the second source action. Its frozen sources were unchanged and owned fixture cleanup succeeded. This verifies the authored storage/worker join only; the wider P1-P11 acceptance remains open.

Read-only active recording catalog retrieval succeeded and is preserved privately under the verification directory. Its legacy simulation flags do not establish immutable source identity, published curation, exact world provenance or native runtime qualification. No recording was modified or promoted, including `recording_b83c37e5`.

Felix on the configured `http://127.0.0.1:4301/mcp` still returns `Project not found` for the canonical ChromaScape task. A successful error response is not a transport outage; no duplicate task, fabricated synchronization or outage outbox success is recorded. No tracker writes or transitions succeeded. A read-only WSL service diagnostic timed out; no service restart or WSL shutdown was attempted.

## Computer-assisted execution plan (2026-10-17)

The user requested that computer use handle the recording and Watch interaction work. The agent owns UI operation and automated visual checks as well as implementation and automated tests. Do not assign routine client operation to the user by default. This adds an execution method to P7-P11; none of those phases or acceptance criteria is newly complete.

### Sequence and evidence

- [ ] Establish a working computer-use session and inventory actual browser/client windows. Select returned targets and inspect the UI before input. Verify the running application, candidate build, runtime/cache/content/schema identities and authenticated local controller match the intended evidence scope. A reachable debugger alone does not establish those identities.
- [ ] Compare existing SC-1991 client/session implementation and its pinned evidence with these candidates before adding integration code. Reuse the actual client, production synchronization and session path; identify remaining bank/travel/region/plane gaps against the current pins.
- [ ] Audit existing eligible recordings first. Select immutable finalized source, exact BehaviorRunContext/task/profile/graph, published curation and complete field/action provenance. If a fresh capture is required, use the authorized capture workflow and retain its actual origin. A generated xRSPS session or UI video cannot substitute for provider-origin recording evidence or fill missing structured state. Preserve recording_b83c37e5's independent blocked verdict.
- [ ] Finish the prerequisites found by that audit, including remaining native activity/restore, closure/resource/isolation and real-client lifecycle work. Retain public admission guards until the corresponding positive and negative acceptance evidence passes.
- [ ] Through the real local 3D xRSPS client, exercise the supported bank-to-travel-to-Fishing scenario and inspect intermediate movement, interfaces, quantities, charges, inventory and XP. Archive manifest, pins, action journal, receipts, domain hashes and visual observations. Label authored scenarios and LIVE_XRSPS results accurately.
- [ ] Exercise Start/Retry, Watch, pause/resume, single-tick controls where supported, reconnect and cancel. Verify idempotent actor ownership, tick-boundary effects and scoped teardown. Compare headless/client-attached domain outcomes and watcher/camera/disconnect variants; screenshots alone cannot establish viewer noninterference.
- [ ] Record each visual check as automated computer-use verification. SC-276 explicitly also requests "human browser Watch verification"; do not describe agent observation as a human sign-off. Make any remaining human review a short final acceptance step using the completed evidence.
- [ ] Run only the required remaining/current-change tests and archive their actual outcomes. Reconcile Felix and Shortcut after successful validated transitions. Existing broad passes remain scoped to their recorded inputs.

### Planning baseline and blockers before the restart increment

The next independent increment is a two-JVM native/database continuation lane: the first JVM commits finalized parent anchors and queued children, exits, and the second reconstructs the children using only database/artifact state and the immutable input/expected-output bundle. It must prove different JVM process identities, exact snapshot/trace/source-counter/hash/event suffix parity, and no reset/step on terminal restore. Extend this with real transactional expired-attempt/report recovery and loopback HTTP artifact downloads where the prerequisites are available. Use an explicitly owned ephemeral database and artifact directory, retain failed evidence, and keep public admission, production sandbox, genuine-source, authentication-stack and human Watch qualification separate. Existing same-JVM evidence must remain reproducible. Implementation and checks for this increment are pending.

Restart/recovery/export acceptance for this increment:

- Given immutable authored input and an owned committed parent/checkpoint/queued child, when the preparing Java process exits and a different Java process restores the child, then complete native snapshots, traces, source counts, Java hashes, event ordinals and committed suffixes match uninterrupted execution; terminal restores dispatch no reset or gameplay step.
- Given an abandoned confirmed native restore with inherited source actions and no child hash samples, or an actionless native advance with one committed sample, when the database-clock lease expires and a different Java process recovers it, then the terminal report counts committed source actions rather than samples, fences the dead attempt, and repeated recovery/report repair is idempotent.
- Given a terminal owned run, when its real loopback HTTP artifact endpoint is read, then downloaded bytes match their advertised content checksum and expected durable evidence; no principal returns 401 and a foreign principal returns 404. A test-only principal injector establishes the export/ownership boundary, not the production authentication stack.
- Given Java JSON number nodes crossing a persisted JSON boundary, when evidence equality is tested, then require exact canonical bytes while retaining number values and array order. Do not loosen production schema or completeness checks to accommodate in-memory IntNode/LongNode differences.

On 2026-10-17, native computer use failed twice before window inventory with `trusted Node process exited unexpectedly; kernel reset, rerun your request`. Browser computer use also failed; its diagnostic reported `windows sandbox failed: helper_sandbox_lock_failed: lock sandbox bin dir C:\Users\Beheerder\.codex\.sandbox-bin failed: SetNamedSecurityInfoW sandbox dir failed: 5`. Resetting the browser JavaScript session and retrying did not recover it. No application window was inspected and no UI action or recording was performed. The computer-use helper must initialize successfully before the UI checklist can run. No permission/security changes or application/service restarts were attempted.

The read-only debugger probe returned HTTP 200 at `http://127.0.0.1:8090/debugger`; candidate identity and client readiness remain unverified. Official Shortcut reads of SC-274, SC-276 and SC-1991 succeeded. SC-1991 already records partial client/session/native implementation and evidence, which must be audited for reuse rather than treated as absent. Felix get for existing task `task_1789960012362_td2qnusp` under `C:\Dev\IdeaProjects\ChromaScape` again returned `Project not found`. Pending folders were scanned; unrelated pending records were preserved. This is a local plan amendment, not a successfully stored Felix note or an outage replay. No tracker writes, workflow transitions, synchronization markers or implementation test passes are claimed for this planning step.


## Completed restart increment (17 October 2026)

The bounded restart/recovery/HTTP acceptance above passed. Current implementation and verification notes: `C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261017\implementation-evidence.md` and `C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\builderflow\sc-274-sc-276-continuation-20261017\verification-evidence.md`. Procedure: `C:\Dev\IdeaProjects\.worktrees\sc274-completion\docs\replay\native-database-restart.md`. Full P1-P11 scope and all whole-story acceptance rows remain open. The completed result does not make the browser Watch step ready: computer-use helper startup still fails, the current compiler lacks the complete SC-1991 client integration/package, and a genuine eligible source plus selected published curation is not yet selected. Felix remains in a transport outage; records are durable pending operations, not successful tracker writes.

## Source-store provenance survey (17 October 2026)

A read-only database survey of every local `chromascape-postgres` store closed the previous increment's open question ("a missing HTTP endpoint does not establish that no source context exists in storage"). Findings: the served store (`chromascape.public`) has 119 recordings with no provenance columns at all, exactly two published `HELD_OUT` curations (`recording_c73dfff8`, synthetic diagnostic fixture), four published `scenario_manifests` (three for `recording_c73dfff8`, compiler 2.0.0/2.0.1, 2026-10-05), 50 replay actions, zero published behavior graphs, and no `ai_player_behavior_profiles` table (V61+ unapplied; Flyway max V60). The experimental `sc1976_live_20261012` schema has the provenance columns but zero non-null run contexts and zero curations/actions/audits. No store anywhere contains a run context, a behavior profile or a published graph, and the candidate contract (`BehaviorRunContextService.snapshot()`, `assign()`; `CatalogStore`) gates every capture and every audited legacy assignment on a resolvable profile and the V61+ tables.

Verdict: no recording in any reachable store currently satisfies SC-274's genuine-source selection; nothing was repaired, promoted or relabeled. `recording_c73dfff8` remains the strongest genuine candidate (real account, FINALIZED session, 1 CAPTURED MICRO6_SEMANTIC_DISPATCH typed action, published curation, three historical published manifests) but is eligible only for the operator-authenticated, explicitly reasoned `INFERRED` assignment lane, never a CAPTURED claim. `recording_b83c37e5` and `recording_5e99d252` keep their independent blocked verdicts.

Precise next requirements, in order: (1) apply candidate migrations V61+ to a target store; (2) create one immutable behavior-profile version through the SC-641 catalog append path, which needs a micro6 catalog snapshot source (currently unavailable); (3) optionally publish a graph only if the capture reference carries graph fields; (4) either a fresh recorder capture bound to that profile (SC-276 client path) or the audited INFERRED assignment on `recording_c73dfff8` with operator auth and acceptance of the INFERRED label. Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261017\recording-store-provenance-survey.md` and `.json`; durable notes written to both projects' Felix outbox (`20261017T001343Z--sc-274/sc-276--create-note--source-store-survey-20261017.md`). No P1–P11 phase or 274-x/276-x row changes status from this survey.

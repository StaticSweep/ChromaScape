# SC-274 / SC-276 remaining implementation plan

Latest remaining sequence after run6 review:
[full remaining implementation plan](remaining-implementation-after-run6-20261020.md).
WP2 is evidenced; WP1 implementation is corrected but purpose-specific rotation checks
remain incomplete. The linked plan supersedes older unchecked next-action summaries here.

Date: 20 October 2026. Status: planned, not executed by this document.

This is the current implementation sequence after review of phases 1-3. It supersedes
earlier next-action and completion summaries, while preserving their historical evidence.
The original scope and 17 acceptance rows in [delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md)
remain binding. Review findings mean phases 1-3 are not an unconditional isolation sign-off.

## Implementation approach

Fix the two demonstrated issues first: experiment credential handling and artifact-root
binding in non-restart tests. Then finish owned service/registry integration and qualify
existing compiler/native behavior. Modify product code only for a demonstrated gap;
additional product changes cannot yet be ruled out. Passing authored tests do not establish
genuine-source admission, production performance, sandbox qualification or story completion.

## Repositories and resources

| Role | Location |
|---|---|
| Compiler candidate | `C:\Dev\IdeaProjects\.worktrees\sc274-completion` |
| Compiler build/service mirror | `C:\Dev\IdeaProjects\.worktrees\sc274-chromascape-trust-exp` |
| Native candidate | `C:\Dev\IdeaProjects\.worktrees\sc276-completion` |
| Micro6 selected development source | `C:\Dev\IdeaProjects\.worktrees\sc274-micro6-dev-runtime` |
| Registry candidate | `C:\Dev\IdeaProjects\.worktrees\leeuwbotapi-f1` |
| External evidence (V below) | `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019` |

Use compiler/mirror-relative paths below unless stated otherwise. Read active instructions
in each repository before implementation. Reinspect HEAD, uncommitted changes, listener
ownership, container labels and package hashes before mutations. Current evidence records
compiler c791c6d4, native1903922a and registry5809869; these are not substitutes for a fresh check.

## Constraints

- Never run `wsl --shutdown`.
- Keep shared8090, shared registry/database, canonical trust and existing replay work intact
  during owned qualification. Shared rollout requires a concrete reviewed deployment and authorization.
- Keep shared replay consumption disabled. Do not restart the stopped8098 session implicitly.
- Stop only ownership-verified processes/containers; retain failed evidence and unrelated dirty work.
- No automated game login, fabricated provenance, fixture promotion or silent acceptance changes.
- Felix remains explicitly skipped; no Shortcut/Felix writes or workflow claims.
- Required human Watch and genuine capture remain separate from agent-run authored checks.

## Work package 1: repair experiment credential handling

**Files:** `V\relaunch-8181-phase3.py`, `V\phase3-provenance-api.py`,
`V\phase3-post-restart-reads.py`, and other owned-service helpers that read their token file.

Observed defects: token JSON inherits ordinary-user read access and authenticated-user modify
access; the ALTER ROLE statement is passed through `psql -Atc`, exposing the password in argv.

Implementation:

- [ ] Create a dedicated experiment-secret directory outside ordinary evidence output, protected
  for the actual serving/test identity plus SYSTEM/administrators as required. Verify the ACL
  before generating secrets; fail closed if protection cannot be established.
- [ ] Write secret files only inside that protected directory; publish secret names/path references,
  not values, in evidence. Do not reuse the insecure file as a fallback.
- [ ] Change the SQL helper to send SQL bytes to `docker exec -i ... psql` through stdin with
  `ON_ERROR_STOP=1`; omit SQL/password literals from argv and diagnostic output.
- [ ] Update all consumers to resolve the protected secret path. Add a preflight that refuses
  broad file permissions before reading credentials.
- [ ] Rotate affected experiment tokens and database password, replacing only owned8181 after
  ownership checks. Inventory the old `phase3-secrets*.json` files and retire their credentials;
  remove secret-bearing obsolete files only after verifying exact experiment ownership. Preserve
  sanitized evidence metadata, not working secret values.
- [ ] Ensure errors, subprocess stderr and launch metadata cannot print secret values.

Verification: inspect effective ACL without printing content; inspect generated subprocess args;
prove old experiment tokens fail and new tokens work; verify DB connection/service readiness.
No shared credentials are changed. Rollback uses a newly generated protected experiment secret,
never restoration of an exposed credential. These are helper/config changes, not a product redesign.

## Work package 2: repair artifact-root binding for every test lane

**Files:** `src/test/java/com/chromascape/web/replay/worker/NativeCheckpointPersistenceTest.java`,
its continuation/restart subclasses, and `V\native-continuation-20261019\run-native-continuation.cjs`.

Observed defect: `replayLaneProperties` publishes `replay.artifacts.root` only with
`SC276_NATIVE_RESTART_ROOT`. Non-restart lanes still rely on a setter that YAML can overwrite.
Files in candidate `.chromascape/replay-artifacts` were timestamped during run4.

Implementation:

- [ ] Centralize selection of the owned artifact path for both restart and non-restart cases.
  For ordinary cases use a lifecycle-safe temporary directory; for restart cases retain the
  owned persistent restart root. Do not assume a JUnit TempDir is initialized before Spring
  evaluates dynamic-property suppliers; verify lifecycle or create an owned root explicitly.
- [ ] Register `replay.artifacts.root` through `@DynamicPropertySource` unconditionally.
  Keep restart ownership checks, and make blank/missing environment handling consistent.
- [ ] Express the authored run budget at the same binding layer where needed. Remove misleading
  duplicate setters or document them only if they have an independently tested purpose.
- [ ] Assert the effective bound absolute artifact path before any test performs writes.
  Refuse the candidate default directory and unrelated paths; normalize paths for comparison.
- [ ] Have the orchestrator record selected artifact roots and pre/post file inventories.
  Preserve existing default-directory files; do not delete them as cleanup for this correction.
- [ ] Format only affected files and run scoped whitespace/style checks.

Verification: run `replayNativeCheckpointPersistenceTest` and
`replayNativeDatabaseContinuationTest` with fresh owned DB/output. Require9/9 and1/1 with
zero skips, effective-root assertions and no new default-root writes. Check the restart-root
path as well; rerun prepare/resume if shared setup changes affect its behavior or leave doubt.
Archive result XML and hashes. Never relabel run4 as fully artifact-isolated.

Rollback: revert scoped helper/test changes, retain corrected and historical results. Do not
delete shared/default directories or reset released database state.

## Work package 3: finish post-fix source integration

**Files:** owned service helpers and persistence harnesses; inspect product
`CurrentSliceRunService`, `SourceRunOrchestrationService` and stabilization ingestion only
if a real defect appears. Existing `verified=true` gate remains enforced.

- [ ] Build the exact candidate into the mirror without replacing shared8090 outputs.
- [ ] Run provenance and affected transport/startup/orchestration/idempotency tests.
- [ ] Keep the already evidenced legacy verified=false/null refusal and zero-write behavior.
- [ ] Determine whether an authorized, explicitly labelled verified prerequisite can be produced
  through supported report-bundle ingestion for development integration. Never UPDATE the
  fixture's verified flag or claim it is a genuine capture. If no legitimate prerequisite is
  available, record the positive path blocked; retain negative and historical-read results.
- [ ] For an eligible prerequisite, run create/idempotency/identity mismatch, manifest/file/symbol
  reads, wrong-token/audit checks, restart persistence and tamper/refusal with exact restoration.
- [ ] Write result records incrementally; distinguish pre-fix historical reads from post-fix creation.

Exit: post-fix refusal is retained and positive creation is qualified for its actual evidence
scope, or its exact prerequisite blocker is explicit. Original trust remains a separate gate.

## Work package 4: implement owned Linux registry route and deployment

**Files/resources:** isolated registry worktree at exact5809869, owned compose/deployment files
under V, documented endpoint-grant issuer, new private configuration and durable fence volume.
Read the pinned backend AGENTS and operations contract before selecting paths/commands.

- [ ] Inventory pinned registry auth/database/startup dependencies. Reuse none of the shared
  registry's credentials or writable state. Native Windows fence sync is unsupported in this
  tested environment; preserve the Linux mechanism results and use Linux hosting.
- [ ] Produce a concrete route design: actual source/destination addresses, runtime bind,
  host/container boundary, permitted clients, auth, registry origin policy and teardown.
  Container localhost is not Windows8180. Do not assume host.docker.internal reaches a
  loopback-only Windows listener.
- [ ] Prefer a separate owned runtime/listener on a scoped reachable interface if required,
  retaining8180. If a relay is proposed, verify the origin/identity contract permits it and
  ensure it is authenticated and limited to the intended runtime; no open generic forwarding.
- [ ] Prove reachability from the actual Linux registry network context before registration.
- [ ] Create loopback-exposed host8182 deployment with owned dependencies, secret, atomic grants
  file publication and persistent fences. Confirm ports are free and ownership labels match.
- [ ] Start only the owned deployment; record exact source/image/config identities.

Exit: authenticated registry and intended runtime route work in the actual network context.
Rollback removes/stops only verified owned resources, retaining fence high-water evidence.
Do not modify shared registry or broaden existing8180 bind implicitly.

## Work package 5: finish live operator-issued grant integration

**Files:** experiment issuer/renewal/test scripts using the pinned backend contract;
product changes only for demonstrated contract defects.

- [ ] Use labelled operator-issued experiment claims and actual mechanism identity fields.
- [ ] Drive live registry and isolated ChromaScape paths; verify correct instance/origin mapping.
- [ ] Exercise <=120s TTL, renewal before expiry, atomic whole-file replacement, revocation by
  omission, issuer failure, missing file/key, bad signature, identity/artifact/route mismatch.
- [ ] Restart registry retaining fences; reject rollback/replay/stale generation and accept the
  current valid generation. Confirm fail-closed behavior after renewal stops.
- [ ] Keep non-DEV HTTPS/mTLS claims separate from DEV cases actually tested.

Exit: live mechanism integration qualified. Any bounded renewal helper is experiment tooling;
automatic allocation/issuance/renewal remains SC-800 unless separately scoped and authorized.

## Work package 6: implement supported Launcher allocation and selection

**Files:** inspect Launcher registration/session configuration and Micro6 launch contract;
edit supported launch configuration first. Do not invent a new registration protocol.

- [ ] Launch a separate owned runtime via Launcher with forwarded machine/session/executor
  identity and supported allocation. Do not retrofit fake identity onto direct-launched8180.
- [ ] Bind grants to the real allocation, artifact, environment, session and generation.
- [ ] Verify heartbeat freshness/expiry, ownership, lifecycle and full catalog/schema/build hashes.
- [ ] Select through isolated ChromaScape registry discovery, then prove metadata/source/catalog
  resolve to that same allocated runtime. Refuse stale and mismatched records.
- [ ] Exercise reconnect/reallocation and renewal/recovery using supported lifecycle behavior.

Exit: genuine allocation/selection integration, independent of original-trust/capture acceptance.
Request game login only if a demonstrated contract or subsequent capture actually needs it.

## Work package 7: resolve authoritative trust and genuine input

**Inputs required:** approved signer/deployment or explicit acceptance amendment; authorized
catalog/profile/capture and user login where required. No code workaround substitutes for these.

- [ ] Obtain a bundle accepted by the original configured trust root and matching the client
  artifact, or record an explicit acceptance amendment. Never replace the trust root silently.
- [ ] Verify all signature/key/environment/repository/build/snapshot/artifact/content pins.
- [ ] Obtain genuine catalog; append immutable profile through SC-641 API with supported auth.
- [ ] Pin settings/task/schema/graph/curation; establish real BehaviorRunContext.
- [ ] Select eligible finalized evidence or carry out authorized capture with user login.
  Preserve action/observation order, receipts, termination, privacy and dataset role.

Exit: eligible immutable genuine input. Development success remains separate until this passes.

## Work package 8: implement only remaining compiler/native behavior gaps

**Compiler surfaces:** `ScenarioManifestV2Compiler`, evidence/preflight validators, provenance/
curation/import/export contracts and their tests. **Native surfaces:** production/headless
handlers, world/content loading, adapter state/snapshot behavior in the native candidate.

- [ ] Build an acceptance-to-test gap inventory before editing implementations.
- [ ] Prove identical bytes/checksum for repeated immutable input/version and exact field/action
  provenance with pinned curation. Keep initial dynamic state, static content and spawn rules
  separate; resolve later targets at their execution tick across regions/planes.
- [ ] Verify exact bank items/slots/options/quantities, jewelry destination/charges, exchange
  interface/NPC and travel-to-Fishing semantics. Implement only evidenced missing behavior.
- [ ] Cover gaps/duplicates/reordering, missing/ambiguous state, unsupported schema/cache and
  incomplete legacy inputs with stable source-linked refusal; readable is not causally eligible.
- [ ] Check immutable role/privacy and bounded inert-data import/export. Preserve the historical
  recording's precise blocker where original evidence is insufficient.
- [ ] Compare every advertised native method with production resources/requirements/timing/
  quantities/charges/XP/effects/interruption/combat semantics; restrict unsupported claims.
- [ ] Check tick/revision before atomic actions; refusal mutates nothing. Snapshots include
  pending actions/queues/timers/RNG/time/world/source cursors/stop state.
- [ ] Rerun affected continuation/recovery lanes after state/handler changes.

Exit: all relevant compiler/native positive and negative cases have evidence on final inputs.
The prior56+57 authored passes and authored native continuation remain scoped supporting evidence.

## Work package 9: qualify production limits, closure and real isolation

**Surfaces:** native identity/closure/module/offline guards, production Java worker transport,
runtime sandbox/resource configuration and associated negative tests.

- [ ] Retain180s transport/3600s run settings as authored-lane settings only. Measure cold/warm
  startup through the actual production path and its configured deadlines. Investigate slow
  startup or deployment configuration with explicit performance requirements; do not silently
  increase production limits because the authored lane passed.
- [ ] Inventory modules/assets/scripts/content/cache and dynamic loaders. Reject altered,
  missing, unexpected, aliased/symlinked or out-of-closure dependencies before execution.
- [ ] Verify actual egress denial, read/write boundaries, process/thread restrictions,
  cancellation and CPU/memory/time limits with cold/fresh/parallel workers and deliberate violations.
- [ ] Fix missing enforcement in the owning implementation; do not substitute mocked refusal
  tests for operating-system/process evidence or weaken guards to obtain green results.

Exit: actual production-path budgets and worker boundaries pass. Authored Windows transport
is not production sandbox proof. Preserve last supported package pin on rejection.

## Work package 10: migrations and supported installation/upgrade

**Surfaces:** released migration inventory/current-schema definitions and disposable DB test lanes.

- [ ] Diagnose missing production tables and reused V58-V60 history using actual supported
  deployment histories. Design forward-compatible corrections; do not rewrite released files.
- [ ] Implement necessary forward migrations and compatibility checks only after that inventory.
- [ ] Test empty installation and supported existing-history upgrade on owned databases with
  strict schema validation, retained data/constraints and migration failure behavior.
- [ ] Keep stage E schema-copy qualification separate from fresh-install migration evidence.

Exit: supported installation/upgrade paths pass and deployed histories have a reviewed remedy.

## Work package 11: paired packages, real client and Watch

**Surfaces:** worker/client packaging manifests, SC-1991 gateway/session integration and UI tests.

- [ ] Build reviewed clean worker/client inputs; inventory adapter/build/cache/content/schema
  and all package assets. Qualify worker before producing final release/client manifest.
- [ ] Refuse absent/mismatched packages/assets before execution; run packaged Java/durable lanes.
- [ ] Establish approved computer-use surface and real client on the same authoritative world.
- [ ] Exercise authenticated exact-origin Start/Retry/Watch, pause/resume, supported tick controls,
  reconnect/cancel/import/export, one actor and scoped teardown.
- [ ] Compare structured receipts/state for bank/widgets/travel/Fishing and compare watcher,
  camera and disconnect variants for equal causal outcomes. Obtain human Watch sign-off.
- [ ] Keep LIVE_XRSPS opt-in/non-certifying and networking out of offline qualified workers.

Exit: package identity, real-client lifecycle and noninterference are evidenced, including human gate.

## Work package 12: final verification and authorized rollout

- [ ] Freeze final source/config/package identities; run required compiler/provenance/curation/
  privacy/auth, native method/state/closure/isolation/resource/negative, packaged integration,
  differential/oracle, performance/stress and CI matrices. Deliberate regressions must be detected.
- [ ] Reuse prior tests only for unchanged relevant inputs/coverage. Record actual commands,
  durations, hashes, failures/skips and all unrun checks. Format only scoped changed files.
- [ ] Prepare concrete8090 and shared registry rollout with route, credential ownership,
  operational grant renewal, durable fences, monitoring and component-specific rollback.
- [ ] Obtain deployment authorization, then use the documented Windows wrapper. Preserve replay
  consumer disablement and existing attempts; verify effective configuration/outbound routes,
  auth/refusal behavior and service health after deployment.
- [ ] Review scoped multi-repository diffs; link every acceptance row to exact final evidence.
  Provide Implementation Evidence with Changed files/Tests/Remaining work and Verification
  Evidence with Acceptance results/Tests/Risks/Rollback. Publish/merge only reviewed authorized work.

Exit: required gates pass for delivered inputs, or a precise partial handoff retains blockers.
No completed story or certification flag is inferred from selected authored passes.

## Acceptance mapping and dependency order

| Original acceptance | Required work packages |
|---|---|
|274-1 deterministic bytes/checksum|7,8,12|
|274-2 exact provenance/curation|3,7,8,12|
|274-3 stable incomplete/invalid-input failures|8,12|
|274-4 native Fishing causal execution|6,7,8,11,12|
|274-5 incomplete/legacy causal refusal|3,8,12|
|274-6 immutable privacy/dataset role|7,8,12|
|274-7 bounded inert import|8,12|
|274-travel exact bank/travel/Fishing|7,8,11,12|
|274-historical original evidence or precise blocker|8,12|
|276-boundary real xRSPS behind adapter|2,8,9,11,12|
|276-methods production/headless parity|8,11,12|
|276-closure dependency/package refusal|9,11,12|
|276-state complete deterministic restore|2,8,12|
|276-safety actual isolated bounded workers|9,12|
|276-live authenticated opt-in lifecycle|6,11,12|
|276-client real client/noninterference/capture|7,8,11,12|
|276-certification full final qualification|8,9,10,11,12|

Immediate order:1 ->2 ->3. Registry route/deployment4 -> live grants5 -> Launcher6.
Approved trust/genuine input7 gates genuine-source claims, but authored behavior work8,
isolation/performance9 and migration work10 can continue independently. Package/client11
requires the relevant behavior/state/isolation gates; final12 runs against frozen deliverables.
Human Watch, approved trust/capture and shared deployment authorization are distinct external gates.
F1 mechanism success does not close live registry/Launcher integration. No blanket external block
applies to work8-10.

## Evidence and rollback discipline

Each work package records exact changed files, input hashes, commands, result files and
remaining limitations. Store secret-free result JSON and archived JUnit XML under a new
dated evidence increment. Failed attempts remain preserved. Validate ownership before cleanup;
never use shared database restore or fence rollback as routine cleanup.

This writing increment changes documentation only. It runs no build/test/service/database/
credential operation, fixes neither review finding and makes no tracker transition.

## Execution update: 20 October 2026

Work packages 1 and 2 were implemented and verified after this plan was written.

- Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261020\review-fixes-1-2-execution.md`,
  `work-package-1-credential-rotation.json`, `work-package-2-artifact-isolation.json`.
- WP1: protected ACL store, stdin-only SQL, five retired tokens rejected, owned 8181 ready.
- WP2: run6 passed 9/9 persistence, 1/1 same-JVM continuation, 1/1 prepare and 1/1 resume
  with zero skips; effective roots recorded; candidate default artifacts unchanged.
- WP3 negative path retained; positive creation remains blocked on an eligible verified
  prerequisite. WP4-12 were not started and retain the blockers listed above.
- Felix and Shortcut remain skipped.

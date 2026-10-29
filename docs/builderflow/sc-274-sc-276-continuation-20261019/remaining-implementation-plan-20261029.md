# SC-274 / SC-276 — remaining implementation and acceptance plan

Date: 29 October 2026, Europe/Amsterdam. Status: plan only; original story acceptance remains open.

This document supersedes the remaining-work ordering and current-status statements in
[remaining-implementation-plan-20261025.md](remaining-implementation-plan-20261025.md).
Preserve that document and earlier evidence as history. The original scope in
[delivery-plan-after-step2-review.md](delivery-plan-after-step2-review.md) is unchanged.
No acceptance amendment, tracker transition, shared rollout or new implementation is implied by this plan.

## Later 29 October implementation and review update

The implementation described below is now present as uncommitted native changes on top of 4f935c59.
Treat the original baseline in section 1 as the earlier fresh run, not the final identity of these changes.
Evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\identity-rerun-20261029T014052Z\INDEX.md`.
All 38 recorded file hashes were checked without mismatch. A follow-up run of the new runner, spell-scope
and client-validation tests passed 19/19; review details and raw output are in
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\followup-review\`.

| Package | Updated status |
|---|---|
| P0 | Clean-tree identity regenerated before implementation edits. Differential case now executed and passed; authoritative stdio remains skipped solely for the qualification gate. Final changed-candidate freeze remains required. |
| P1 | Plain-HTTP fallback restored with vendor license/notice, parity/negative tests and recorded real Chromium LAN verification. Implementation exists; freeze/review it and retain final-package browser verification. |
| P2 | Executable runner, file hashing/verifier, provenance, structured semantic outcomes and negative tests implemented. The corrected bundle has two PASSED/seven NOT_RUN. Reviewed authority and the gate/identity transition remain open; earlier four-PASSED output is historical prequalification mechanics evidence. |
| P3 | Package digest and SC-2201 handoff written. Two independent-directory reproducibility and target qualification are explicitly deferred, not passed. |
| P5 | Spell IDs/target-domain schema and production capability validation implemented; independent parity/advertisement still open. Oracle coverage enforcement already existed. |
| P7 | Current migration inventory and release-authority request written in p7-migration-inventory-20261029.md; no upgrade rehearsal or release-lineage approval. |
| P8 | Coarse host/container/external probes and owner/runbook preparation written in p8-clock-operations-20261029.md; no clock remedy or renewal qualification. |
| P11 | acceptance-ledger-20261029.md now lists all 17 rows. No row is closed; final evidence and delivery remain required. |

Immediate local follow-ups identified by that review, with the subsequent fix status:

1. Fixed in the subsequent Windows follow-up: the runner requires structured identity/environment-bound
   acceptance reports. The default oracle command reports actual missing inputs as INCOMPLETE/NOT_RUN;
   the independent-directory package component is NOT_RUN. The verifier rejects a declared status that
   contradicts its preserved evidence even when the bytes still match their digest.
2. Fixed in the subsequent Windows follow-up: authoritativeReplayCapabilities now admits its output
   through requireScopedSpellAdvertisement. Bootstrap-safe IDs are tested against gameplay contracts;
   invalid inherited capability claims fail before publication without loading game code at discovery.
3. Review/freeze the intended native source changes and regenerate final identities. File hashes prove
   integrity, not independent authority; retain reviewed evidence origins and final package binding.
4. Keep the self-referential gate transition open until its final-identity procedure is reviewed. Do not
   promote the development override or current prequalification record into authority.

The substantive P4/P6/P9/P10 external inputs and SC-2201 execution remain outstanding. No entire package
is declared accepted merely because its preparation or harness implementation now exists.

Fix evidence: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\qualification-spell-fixes\`.
Final combined affected tests 59/59 pass without skips (including the two new oracle-input parser cases).
Server and focused test/script typechecks pass. The actual default bundle, including the input-artifact path, reports two PASSED, seven NOT_RUN and
INCOMPLETE; none of this changes the authority gate. Source changes remain uncommitted; a hashed
working-file snapshot is a review/recovery artifact, not a clean committed release identity.

## Plan audit amendment (29 October 2026, later)

Read-only audit of this plan against git state and the Shortcut stories (SC-274, 276, 1199, 1991, 2201).
Nothing was changed except this section; no commit, push, tracker write or test rerun. Findings that
are path-level only are marked as such.

| # | Finding | Evidence | Plan change |
|---|---|---|---|
| A1 | The SC-276 opt-in local LIVE_XRSPS Watch mode exists only as uncommitted files in `C:\Dev\Worktrees\xrsps-sc276-activities` (branch `codex/sc276-activity-expansion-20261002`, 34 dirty entries, 54 commits behind `main`). Not in `main` by path: `server/src/replay/live-replay-service.ts`, `live-replay-http.ts`, `client/replay/LiveReplayPanel.tsx/.css`, `server/src/game/movement/RecordedWalk.ts`, walking tests, `server/scripts/start-live-replay.ps1`, six docs. Fifteen modified files exist in `main` with unknown content differences (path comparison only, not diffed). Every other xrsps worktree has 0 commits ahead of `main`; `xrsps-replay-certification` has 14 dirty entries whose paths all exist in `main`. | git status and path lookup against `main` 4f935c59 | New package **P12** below |
| A2 | P9/P10 treat the real client/provider pair as an untouched external input. SC-1991 (In Progress) already reports a 14-test, zero-skip native gate including the actual OsrsClient and a real provider run, durable sessions, bank/jewelry/Rune Mysteries work, and draft PRs xrsps-typescript #7/#8 and ChromaScape #61/#62/#64/#65. | SC-1991 comments 2035-2043 | P9/P10 must start from SC-1991's evidence and PRs and only add what it does not cover; do not scope the same work twice |
| A3 | P3 and the checklist depend on "SC-2201 / an approved Linux target", but SC-2201 is To Do with no owner, labels, estimate or comments, and records only a WSL2 lane (no root, no delegated cgroup, no disposable VM). | SC-2201 as read | Owner and host are now an explicit prerequisite (checklist item 2, P3 step 0) |
| A4 | Tracker state contradicts evidence: SC-274 is In Review (since 3 Oct) with 3 BLOCKED and 1 NOT_RUN ledger rows; SC-276 is To Do despite substantial implementation. Last comments are 18 Oct. | Shortcut state and comments | Add a tracker-reconciliation step to P11; no write is implied by this amendment |
| A5 | About 15 overlapping remaining-plan documents exist in this folder. | directory listing | This file is the plan of record; treat the rest as history. Do not add another full plan; amend this one |
| A6 | A decision is missing: the plan says an acceptance amendment "requires explicit authority" but never makes it a decision point, although 10 of 18 method rows depend on references with no owner or date. | plan sections P4/P5 | New decision gate **D1** below |
| A7 | Roles are written as separate external parties (reference authority, release owner, operations owners, allocation owner, Watch reviewer, delivery owner), but Shortcut shows a single team member and every owner field is the same person. This is an inference from tracker data, not a confirmed fact; the owner should correct it. | SC-274/276/1199/1991/2201 owner fields; team `member_ids` | New decision **D2** and checklist change below |
| A8 | The plan does not mention SC-2208 (complete TSPS integration into xRSPS, owner amendment 2026-10-26). Closure manifests, package digests, runtime identities and the oracle matrix are pinned to current `main` 4f935c59; a large TSPS merge may force requalification. SC-2208 itself was not read for this audit, so its schedule is unknown. | Epic 272 description | New decision **D3** below |
| A9 | The production worker launcher refuses Windows, and the only Linux lane so far was WSL2 without root or delegated cgroups. The plan assumes an "approved Linux target" without saying where production would run. | SC-2201 description; plan section 1 | Host choice is now a prerequisite decision (**D4**), ahead of P3 |
| A10 | The plan is a long ledger (about 46 KB) with roughly 15 earlier versions beside it. It orders packages by dependency but not by size, so quick wins are not distinguishable from large work. | folder listing | Short execution checklist added below |

### P12. Account for uncommitted live-replay and activity work (new, local, can start now)

1. Produce a content diff of every dirty file in `xrsps-sc276-activities` and `xrsps-replay-certification` against current `main`, and a patch bundle stored under `C:\Dev\IdeaProjects\verification\` with SHA-256 hashes. This changes no repository.
2. Classify each file as already superseded in `main`, still needed, or abandoned. Record the decision per file, not per worktree.
3. For needed content (the live-replay service, controller, panel and walking), port onto a branch from current `main`. Re-run its tests there; the 2 Oct results do not carry over after 54 commits of drift.
4. Preserve the existing stash on `main` (`stash@{0}`, pre-merge dirty state of 26 Oct) and stash `1ffc50bd`; their contents have not been diffed.
5. Exit: no required behavior exists only in an uncommitted worktree, or each such item is explicitly marked dropped with an owner's approval.

**P12 status (29 October 2026, later): steps 1 and a trial of step 3 done; steps 2 and the real port remain.**

Step 1 (diff and patch bundle) is complete and changed no repository. Bundle:
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\p12-worktree-bundle\` (60 files, `MANIFEST.sha256`;
per worktree: `tracked-vs-own-head.patch`, `untracked/`, `classification.csv`, `vs-main/*.diff`).

| Worktree | Behind `main` 4f935c59 | Dirty | Identical to `main` | Differs | Not in `main` |
|---|---|---|---|---|---|
| `C:\Dev\Worktrees\xrsps-sc276-activities` | 54 | 34 | 2 | 17 | 15 |
| `C:\Dev\Worktrees\xrsps-replay-certification` | 67 | 15 | 0 | 15 | 0 |

The `vs-main` diffs mix the worktree's own edits with everything `main` gained since the fork; use
`tracked-vs-own-head.patch` for the worktree's own work. The replay-certification files look mostly superseded
(`main` already has `client/replay/`, `replay-trace.ts` and the kernel files), but that was not confirmed by content.
Do not discard that worktree yet.

Trial 3-way merge (disposable; not a port): branch `scratch/p12-live-replay-merge-20261029` from `main` 4f935c59,
worktree `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261029\p12-scratch-merge\`. The activities patch was
applied with `git apply --3way`; the 15 files absent from `main` were copied in. It is uncommitted and contains conflict
markers. It was not typechecked or tested (no `node_modules` installed).

- Tracked edits: 7 of 15 files merged cleanly, 8 conflicted (38 hunks): `capabilities.ts` 1, `semantic-action.ts` 5,
  `ServerServices.ts` 1, `ActionScheduler.ts` 1, `SpellCastingService.ts` 1, `authoritative-replay-kernel.ts` 8,
  `headless-replay-context.ts` 14, `authoritative-replay-kernel.test.ts` 7. Two of the clean files
  (`crafting/index.ts`, `authoritativeFishingService.ts`) were already identical to `main`.
- New files: all 15 copied (live-replay service and HTTP controller, `LiveReplayPanel`, `RecordedWalk.ts`, walking and
  live-replay tests/smokes, `start-live-replay.ps1`, six docs). Every relative import in the code files resolves to an
  existing path; resolution is not a typecheck.
- Not copied, because `main` already has independently created files at the same paths: `glassblowing.ts`,
  `ItemTrainingService.ts`, `replay-item-kernel.test.ts`, `replay-item-training.test.ts`. These need a per-file decision.
- `live-replay-service.ts` depends on `ActionScheduler`, `ServerServices` and `semantic-action`; the live-replay and walking
  tests also depend on `headless-replay-context`. All of these are conflicted files, so the port needs hand resolution
  against `main`'s newer design and a check against SC-1991's session model.
- The source worktrees and the real `xrsps-typescript` checkout were not modified.

Remaining P12 work: (a) per-file classification (needed, superseded, abandoned) with owner approval, starting with D1 and the
SC-1991 overlap since the live Watch mode may be partly redundant; (b) resolve the 38 hunks, install dependencies in the
scratch tree, then typecheck and run the live-replay and walking tests; (c) decide the four colliding files; (d) remove the
scratch worktree and branch when finished (`git worktree remove --force`, `git branch -D`). The stashes on `main`
(`stash@{0}`) and `1ffc50bd` are still undiffed.

### D1. Scope decision gate (owner decision, not engineering)

Before Phase B inputs are chased further, the owner decides, for each of the 10 unadvertised methods and for the historical recording, whether to (a) keep waiting for independent references, or (b) formally amend acceptance and move the remainder to a linked follow-up story. Option (b) is a deliberate scope change recorded in the story text, not a test outcome, and downstream SC-1199 depends on the result. Until D1 is made, report partial completion.

### D2. Role assignment (owner decision)

The plan's external-input checklist names ten roles. If one person fills most of them, say so and assign each role by name.
Independence then has to come from data provenance, not from a different approver: real captures from a real game or
RSProx source, a real Linux host, and review recorded with named sign-off. A matching replay output or a second pass by
the same tooling is not independent. Until roles are assigned, the Phase B inputs stay unowned and the plan cannot be
scheduled.

### D3. Ordering against SC-2208 (owner decision)

Decide whether qualification (P2/P3/P5) runs before or after the TSPS integration into xRSPS. Qualifying first risks doing
closure, package and oracle work twice if the integration changes pinned code; qualifying after delays all evidence behind
a large merge. Read SC-2208 and record its scope and expected schedule before choosing. Either way, record the xRSPS commit
that each piece of evidence was produced against.

### D4. Linux host (owner decision, prerequisite to P3)

Name where the production worker will run and where Linux qualification will run: a native Linux host or an owned
disposable VM, with root or a delegated cgroup v2 subtree. WSL2 on the current host is not sufficient for the aggregate
resource, mount-isolation and durability items in SC-2201. Record the host, owner and Node/OS pins, then assign the owner
to SC-2201.

### Execution checklist (short form; this file stays the reference)

Small, local, can start now:
1. P12 step 2: classify the dirty files from the bundle; start with the 15 live-replay files (A1). **Still open.**
2. Commit the 16 uncommitted `xrsps-typescript` qualification changes and regenerate identities (P0/P2). **Done 30 Oct: commit `791f7a3d`, identity certifiable; 59/59 tests and both typechecks pass on the committed tree. Qualification verdict unchanged at INCOMPLETE (2 PASSED, 7 NOT_RUN).**
3. Tracker reconciliation for SC-274 and SC-276 (A4). **Done 30 Oct by comment only; states left as they were because Felix was unreachable, so no Felix-state transition was claimed.**
4. Send the 18 capture requests and the P7/P8 requests that are already drafted (P4/P7/P8), once D2 names the recipients.

Decisions before further work is scheduled: D1, D2, D3, D4.

Large or externally gated, schedule only after the decisions: P3 Linux qualification, P4/P5 references and parity, P6 allocation
and trust, P7 migration rehearsal, P8 clock and renewal, P9 durable service and client (start from SC-1991), P10 Watch
sign-off, P11 final matrix and delivery.

### Corrections to earlier sections

- P3: add step 0, name the SC-2201 owner and an approved Linux host (not WSL2) before any Linux evidence is requested.
- P9/P10: start from SC-1991 evidence (see A2) and list only gaps it does not cover.
- P11: add a tracker-reconciliation step (SC-274 state, SC-276 state, SC-2201 ownership) under the standing Felix/Shortcut skip rules.
- Section 5 checklist: add "Linux host owner and approved host for SC-2201" and "owner decisions D1-D4". Read each of its ten role lines as a role to assign by name (D2), not as an outside party that already exists.
- Not read during this audit: `reference-requests-20261025.md`, `current-remaining-delivery-plan.md`, SC-2208, SC-1427, SC-281, SC-282. Their interplay with this plan is unchecked.

## 1. Verified starting point

| Surface | Baseline and scope |
|---|---|
| Native | C:\Dev\IdeaProjects\xrsps-typescript, main, 4f935c591910ebbf5ddb471c9f5bb535e09b9fea; clean at completion of the fresh run. Publication was reported earlier; this plan does not fetch or republish. |
| Java | C:\Dev\IdeaProjects\.worktrees\sc274-completion, codex/sc274-completion-20261015, 70e5a9c024b505a945db114a575d4bdf09990a9e; clean before this documentation change. |
| Fresh kernel tests | Unit 355/355 and integration 24/24: 379 passed, no failures or skips. |
| Fresh server replay tests | 17 files, 131 tests: 129 passed, zero failures, two non-certifiable-identity skips. This was not the entire server sweep. |
| Fresh live development smoke | Real guarded stdio worker: catch, increased Fishing XP, fish in inventory, cold checkpoint restore, forged-checkpoint refusal, inert stale/unknown-target refusal, identical final snapshots and traces. One test passed. Development override was explicit; this is not independent fidelity. |
| Fresh Java lane | sc666CompletionTest: 221 passed, no failures/errors/skips, including seven actual PostgreSQL cases against a new disposable database. |
| Empty JDBC URL | Actually executed. Exit 1 with the disposable-database assertion before connection; deliberately negative evidence, not a passing positive lane. |
| R1/R2 | Original module-hook and oracle relabel/reseal reproductions were blocked in the merged-main review. Fresh guard/oracle tests pass. Do not reopen these as unfixed findings without new evidence. |
| Contracts | Wind Strike and the five auxiliary contracts now exist, alongside glassblowing and the other scoped Magic contracts. Ten methods remain UNADVERTISED pending parity/capability work. |
| Authority | AUTHORITATIVE_RUNTIME_QUALIFICATION.complete=false; approved oracle-reference inventory is empty. Clean source or a reproducible identity alone does not qualify execution. |
| Windows support | Development execution is tested. The Java production worker launcher requires Linux/bwrap and refuses Windows. No native Windows production sandbox is promised by this plan. |
| Recovery/admin | Stash 1ffc50bd contains unique recovery content and five pending Felix records. Retain it. Worktree inventory is complete; deleting worktrees is not an acceptance requirement. |

Fresh raw evidence and reproducible drivers:

`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261028\fresh-tests-20261028T225750Z\`

Read `verification-results.md`, `java-results.json`, `native-results.json`, `kernel-integration.log`,
copied JUnit XML, `postchecks.json` and `evidence-hashes.json`. The attempt started on 28 October UTC
and finished on 29 October local time. Its owned container, anonymous volume and port 15467 were removed.

Earlier review and local administration evidence:

- `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261026\merged-main-review\`
- `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261027\local-checks\`

Do not rerun completed checks just to recreate green output. Rerun when code, dependencies,
configuration, target platform or an unresolved finding changes the relevant inputs.

## 2. Scope, ownership and operating rules

The deliverable is a reproducible, qualified replay/compiler path with genuine source provenance,
supported allocation, complete original method scope and human-reviewed Watch behavior. Passing
Windows development tests is one component, not a replacement for the original criteria.

Repository identities:

- Native: C:\Dev\IdeaProjects\xrsps-typescript.
- Compiler/service: canonical C:\Dev\IdeaProjects\ChromaScape; Git worktree listed above.
- Source producer: C:\Dev\IdeaProjects\micro6.
- Registry: canonical C:\Dev\IdeaProjects\leeuwbotbackend; inspect its actual deployed Git revision before changes.
- Launcher: C:\Dev\IdeaProjects\sarububotlauncher-win.

Read current repository-specific AGENTS before execution. Preserve unrelated dirty work, shared
8090/8098 services, databases, credentials, canonical trust, replay-consumer settings, historical
attempts and recovery bundles. Reconfirm current ownership and health before any service operation;
old PIDs and endpoint observations are not current inventory.

Linux execution is explicitly deferred to SC-2201. Do not start it as part of a Windows-only pass.
Current workspace policy permits wsl --shutdown only for hung/unresponsive WSL with shared-service
recovery checks; it is not a routine test or fault-injection step. No host/Docker reset, game login,
shared migration, trust replacement or power-cycle experiment is implicit here.

Felix/Shortcut remain skipped under the user's standing instruction. Preserve pending records;
do not claim any task state or synchronization. If tracker work is later re-enabled, follow the
current outbox replay/deduplication contract before new writes. No sub-agents are requested.

## 3. Dependency and execution order

| Work package | Can proceed on Windows? | Principal dependency |
|---|---|---|
| P0 current-baseline record | Yes; verified baseline above, refresh only when changed | None |
| P1 browser trace deployment compatibility | Implemented and tested; final review/freeze remains | Final supported package/browser route |
| P2 qualification assembly and evidence validation | Runner mechanics and structured semantics implemented; reviewed authority/gate transition remains | Final positive result waits for P3-P10 |
| P3 closure/package/platform handoff | Windows inventory and runner work; Linux execution in SC-2201 | Reviewed target/runtime pins |
| P4 independent oracle references | Preparation yes; collection needs approved source/participant | Capture authority and independent data |
| P5 parity, advertisement and historical reconciliation | Authored mechanics/audit yes; fidelity waits for P4 | Exact references and original scope |
| P6 allocation, source trust and genuine run | Contract checks yes; full positive path external | Supported allocator, approved bundle, genuine capture |
| P7 migration reconciliation | Inventory and disposable tests yes | Released history and release authority |
| P8 clocks, grant renewal and operations | Design/owned tests yes; host remedy external | Named owners and approved operational policy |
| P9 durable service/client/provider integration | Isolated preparation yes | P3/P5/P6 and eventual qualification |
| P10 packaged Watch and human sign-off | Preparation yes; sign-off external | Actual client/provider and pinned package |
| P11 final matrix and delivery | Preparation yes | All required evidence and release authorization |

Start with P1/P2/P3 preparation. P4 capture requests and P7/P8 authority requests can progress independently.
P4 -> P5 and P6 provide genuine inputs. SC-2201 supplies target-platform evidence to P2/P3/P9.
P9/P10 complete behavioral evidence; P2/P11 then establish and verify final production authority.
No elapsed waiting time, missing input or authored fixture counts as approval or evidence.

## P0. Keep one exact baseline and evidence ledger

1. Record HEAD, branch, dirty diff, lockfiles, Node/esbuild/JDK versions and resource ownership for each participating repository.
2. Tie every source bundle, JAR, closure, cache, package and configuration to its exact digest. Keep private values out of the ledger.
3. Mark each artifact as authored development, independent reference, target-platform qualification or human sign-off.
4. Retain original failures and dated corrections. Preserve the 27 compiler paths with no historical expected bytes as a recovery limitation; a new reviewed baseline does not recover unknown historical bytes.
5. Update stale current-status pointers without deleting historical records. Wind Strike/auxiliary contracts and the new Fishing smoke must not remain listed as unimplemented.

Exit: an evidence consumer can reconstruct each input and distinguish new candidate evidence from history.
This plan and the fresh verification already establish the starting baseline; further changes require a new pin.

## P1. Resolve trace verification on the supported browser route

Owner: native client/replay/replayTraceValidation.ts, its client tests and deployment documentation.
Status update: implementation and recorded compatibility checks exist; the steps below remain the review/final-package checklist, not a claim that the fallback is still missing.

1. Establish whether plain-HTTP LAN access is part of the supported Watch/observer flow. Do not assume that a remote LAN HTTP page has WebCrypto because localhost works.
2. If secure deployment is the intended route, implement/document the actual HTTPS/origin configuration and verify it in the supported browser. Retain explicit refusal on unsupported routes.
3. If plain-HTTP LAN viewing is required, review and extract only the SHA-256 fallback and its dependency/license/test material from stash 1ffc50bd. Do not apply the entire stash or resurrect unrelated build/config experiments.
4. Keep canonical text/byte hashing semantics identical. Never replace verification with an unchecked checksum, silently accept unverified trace data or disable tamper rejection.
5. Test WebCrypto versus fallback for canonical Unicode/number/object inputs, byte-chain hashes and trace pagination. Test altered data, malformed digests, and the browser with crypto.subtle absent.
6. Exercise an actual supported LAN browser route; an API-absence simulation is a regression check, not browser end-to-end proof.

Exit: supported routes verify traces and reject corruption, with a recorded transport decision. Preserve the stash until all unique content, including pending records, has a separately durable home.

## P2. Assemble qualification without trusting caller assertions

Owners: server/src/replay/qualification-result.ts, authoritative-replay-capabilities.ts,
runtime-closure-manifest.ts and a reviewed qualification runner/packaging integration.
Status update: runner/file-integrity and structured semantic acceptance are implemented. Finish reviewed evidence authority and the release/gate identity transition as described in the dated update above.

1. Map existing components: closure-reproducible-package, module-guard, read-guard, resource-and-cancellation, durability, oracle-matrix, worker-session-integration, client-provider-cases, human-watch-signoff.
2. Implemented locally: distinguish passing component tests from actual component qualification and consume structured, identity-bound outcomes. The real oracle command accepts bounded capture/replay inputs with approvals from the reviewed inventory; missing inputs remain incomplete. Independent-directory package acceptance stays deferred until its target producer is wired. Continue archiving and hashing actual raw evidence; a caller-supplied PASSED field or plausible digest is not proof of independent authority.
3. Bind the result to build/content/cache/closure identity, exact environment, comparison profile and evidence provenance. Reject missing, stale, duplicate, mismatched, conflicting or unverifiable components.
4. Keep explicit FAILED, INCOMPLETE and REJECTED outcomes. SKIPPED/NOT_RUN must never discharge a required component.
5. Resolve the bootstrap dependency explicitly: qualification-only tests can exercise the same implementation in owned isolated state with non-authoritative labels. They cannot turn development capability flags into authority. Final production-gated session tests and Watch verification must be repeated through the actual release path after its reviewed qualification transition.
6. Define the reviewed release mechanism for the currently hard-coded false gate. Any source change to it must reference complete reviewed evidence and the qualified input envelope; do not flip it merely to green nine known-gated cases.
7. Rebuild after that change and evaluate the final package identity. Handle self-referential identity changes deliberately; do not reuse a result for a different package or exempt changed guard/runtime bytes without justification.
8. Add negative tests for forged approvals, absent evidence files, changed evidence bytes, stale runtime/environment and unsupported component sets. Test production startup with no development override and failed qualification.

Exit: a reviewed candidate can demonstrate why it is qualified, while incomplete or changed inputs remain refused. This is local engineering plus external evidence; it is not entirely an external blocker.

## P3. Finish closure, reproducible package and SC-2201 handoff

Owners: native runtime-module-guard.ts, runtime-read-guard.ts, runtime-closure-manifest.ts,
server/scripts/build-replay-package.ts; Java XrspsProcessBuilderFactory and worker transport.

1. Compare the conservative input inventory with reachable modules/classes/handlers and the observed bootstrap graph. Identify dynamic resolution, trusted pre-guard references and deliberately unsupported ESM/native/extension paths.
2. Document and test unsupported entry routes fail before executing game code. Retain the fixed R1 boundary; add regressions only for new reachable gaps rather than treating the old exploit as still present.
3. Complete descriptor/path/JSON race coverage and dependency-resolution evidence for the supported platform. Keep public-fs read checks distinct from OS filesystem isolation.
4. Build from two independent clean directories with pinned Node/dependencies and identical cache bytes. Compare package and closure digests; include installation/entrypoint/bootstrap requirements, not just a tarball hash.
5. Verify the delivered package does not depend on developer junctions, a stale mirror worktree, an untracked script or a binary under TEMP. Record any external Node/OS requirement explicitly.
6. Prepare SC-2201's exact Linux commands, package input, negative payloads and result schema. Reuse unchanged historical results only where commit/config/environment scope matches.
7. On the approved Linux lane, qualify the actual Java -> prlimit -> bwrap -> worker path: denied networking, read/write/mount isolation, descendants, CPU/memory/process/storage limits, wall deadline, cancellation and cancellation during checkpoint/report publication. Define whether limits are per-process or aggregate; do not claim aggregate enforcement from a V8 heap cap or RLIMIT alone.
8. Measure cold/warm startup, scenario throughput, concurrent workers, cancellation latency and peak resource use against predeclared limits. Have the target owner approve actual budgets before acceptance.
9. Preserve POSIX durability qualification scope. Real power-loss proof is not established by force-call tests; perform it only if required, on an owned disposable VM with an approved fault method. Never power-cycle shared services as a substitute.

Exit: SC-2201 returns identity-bound target evidence, reproducible installation and the exact qualified resource envelope. Windows continues to refuse the production Linux-only path. Adding native Windows production isolation would require a separate explicit scope decision.

## P4. Obtain independent references for the original 18 method rows

Owners: approved recorder/reference producer and reference-review authority; candidate owns validation.
Use reference-requests-20261025.md as a starting request list, correcting its stale contract-status wording.

1. Request captures for the two quests, Minnow exchange, finite order book, two bank methods, jewelry travel, Fishing, glassblowing, Wind Strike, alchemy, superheat, ring enchantment and five auxiliary methods.
2. For each capture, obtain reference ID, independent source/tool version, capture time and approval authority; pin exact frames, action stream, initial state and provenance digests.
3. Require the method's relevant observations: position, XP, inventory, equipment/charges, bank, quest/vars, exchange, combat and structured GAME events as applicable.
4. Specify tick/action alignment and available RNG observations. Missing state or randomness yields INCOMPLETE, not fabricated observations or arbitrary tolerances.
5. Obtain genuine data through an approved participant/tool. No game login or user impersonation is part of this plan-writing task.
6. Review approval independently of the candidate reference. Land genuine entries in oracle-reference-inventory.ts only with their authority and exact expected bytes; never manufacture approval from a matching replay output.
7. Keep the relabel/reseal refusal and actual-output identity/input bindings as regression requirements.

Exit: every required reference exists with reviewable independent provenance, or its precise blocker remains recorded. An empty inventory cannot qualify the oracle matrix.

## P5. Complete parity, capability integration and historical accounting

Exact spell IDs/target domains and production capability admission are now implemented and tested, including inherited invalid claims and guard-first bootstrap. Genuine parity and advertisement remain outstanding; no method was newly advertised by these fixes.

1. Drive the candidate with each P4 reference's exact initial state and actions; evaluate all 18 original method rows, not only the currently advertised eight.
2. Fix actual kernel/production mismatches in owned changes. Preserve exact resource, level, quantity, timing, cooldown, interruption, target and event semantics. Add focused authored regressions for each fix, then rerun the independent comparison.
3. Advertise each of the ten currently unadvertised methods only after its contract, action coverage and independent parity evidence pass. Keep narrow spell/profile scope; generic CAST_SPELL does not promise arbitrary spell support.
4. Verify auxiliary opt-in profile restrictions, unsupported profile composition, checkpoint reconstruction and zero-mutation refusal. Add compiler/transport mappings needed for genuine use, not just direct headless adapter calls.
5. Reconcile the historical 23-catch ledger versus observedCatchDelta=3 using immutable source IDs/ticks, event definitions, inventory changes, XP and unsupported actions. Net inventory delta and catch count are different metrics.
6. Retain the known historical NOT_READY verdict unless new supported evidence changes it. Preserve the corrected tick-342 Cancel action, tick-900 final-state interpretation, and old evidence bytes. Do not edit a recording to satisfy a gate.
7. If that old capture cannot meet the criterion, record its definitive limitation and use a separately identified genuine new capture for the positive path. An acceptance amendment, if needed, requires explicit authority.

Exit: full promised parity/capability coverage and a defensible historical verdict. Authored positive tests alone do not close these rows.

## P6. Establish genuine allocation, source trust and run provenance

Owners: SC-800 allocator/deployment owner, launcher, registry, Micro6 producer and ChromaScape consumer.

1. Obtain the supported allocation contract and a real registered machine/session/executor lifecycle. Launcher forwarding of metadata and offline operator-issued grants are not allocation.
2. Pin the deployed registry revision and qualify its route to the intended runtime from the registry network namespace. Use scoped authenticated routing; do not register a loopback address that belongs to another namespace or broaden runtime exposure casually.
3. Demonstrate current heartbeat, allocation ownership, grant validity, catalog/build hashes and ChromaScape selection of the intended instance. Reject stale, expired, revoked, wrong-allocation, wrong-route and wrong-artifact candidates.
4. Obtain a bundle accepted by the configured original trust root from an approved producer, or an explicitly approved acceptance/trust amendment. The signing private key need not be imported onto this host. Do not mint a replacement trust root to satisfy the existing gate.
5. Verify exact artifact/bundle/source/catalog identity, metadata/content token separation, audit headers, environment and fail-closed negative cases through the actual consumer.
6. Obtain genuine catalog/profile/run context, an eligible verified stabilization result and authorized capture. Do not promote AUTHORED_FIXTURE rows or set verified=true manually.
7. Create a genuinely source-bound run through supported APIs, then verify idempotency, changed-identity refusal, authenticated source reads, durable restart persistence and zero writes for rejected requests.

Exit: a real allocated selectable runtime and genuinely eligible source-bound run under approved trust. Prior operator-grant and development-trust tests remain useful mechanism evidence only.

## P7. Resolve migration authority using disposable rehearsals

Inventory/request preparation is in [p7-migration-inventory-20261029.md](p7-migration-inventory-20261029.md). Its current SQL hashes are not proof of released bytes.

1. Request released schemas, full Flyway histories/checksums and supported source/target release pairs from the release owner. Include V47-V49, V53-V57, V58-V60 and V71/V72; record the full surrounding lineage rather than isolated names.
2. Reconcile the known collisions against the authority's actual released bytes. Empty-install success is not upgrade compatibility or proof that released numbering is safe.
3. Design the forward migration/lineage strategy with the owner. Do not silently renumber released migrations, overwrite checksums, run repair or alter shared Flyway history.
4. Rehearse empty install and every supported upgrade on owned disposable databases. Validate data preservation, provenance constraints, privacy roles, replay/checkpoint records and schema validation.
5. Establish backup, recovery and point-of-no-return behavior, including what application rollback can support after schema changes. Keep the shared database untouched until an approved concrete rollout exists.

Exit: release-authorized migration strategy and passing disposable upgrade evidence for the actual delivered candidate.

## P8. Qualify clock, renewal and operating ownership

Preparation is in [p8-clock-operations-20261029.md](p8-clock-operations-20261029.md). A stopped w32time service identifies that service's state, not the absence of every possible synchronization mechanism. Linux Docker containers use their Linux host/VM kernel clock; do not equate that kernel with the Windows kernel. The coarse HTTP-Date probe does not establish a precise skew bound.

1. Name owners for host/VM time synchronization, registry availability, grant issuance/renewal, secrets and incident response. Manual test scripts are not a production service owner.
2. Measure simultaneous Windows, Docker/Linux, issuer and registry clock offsets and jitter. Earlier 45-48 second skew is dated evidence, not a current measurement.
3. Define accepted skew, TTL, safety margin, renewal cadence, outage behavior and monitoring with the owner. Preserve the corrected meaning of renewAtRemaining versus elapsed renewal fields.
4. Apply a reviewed host-time remedy only under its actual authorization. Repeat skew and expiry/renewal measurements afterward; keep fail-closed grant validation.
5. Prove authenticated renewal, atomic replacement, stale generation rejection, revocation, expiry, restart fence persistence and recovery on the delivered service/route, with the intended operational issuer.
6. Retain grants/fence generations during restart/rollback. Never reset fences to make an old grant work. Credentials rotate forward to protected fresh values; do not restore exposed retired values.

Exit: named operators, measured timing envelope and a sustainable issuer/renewal runbook with alerts and tested failures.

## P9. Run full durable service and actual client/provider integration

1. Allocate an owned isolated ChromaScape service/database and package root. Verify startup consumers, scheduled workers, replay settings, ports and outbound dependencies before starting it.
2. Use P6 genuine source/run inputs and P3's approved worker package; pin all source, artifact, environment, compiler and runtime identities.
3. Exercise prepare/start/pause/resume/cancel/retry, tick/action limits, process crash, cold restore, recovery ownership and stale/duplicate action refusal through the real service API and durable database.
4. Prove zero committed mutation for invalid provenance/action/lease/identity cases, one-actor ownership, no observer-driven state changes and no consumption of unrelated historical runs.
5. Discover and record the current 17 durable-session/repository cases by name. Do not confuse the latest two skipped native tests with the older nine gate failures and four client/provider skips. Reuse repository 3/3 only if its inputs remain unchanged.
6. Run actual client/provider cases with protected settings and bounded provider budget; stubbed provider checks remain authored mechanism tests.
7. Resolve any qualification bootstrap cycle as specified in P2. Keep prequalification runs provisional; require final production entrypoint tests without development override after the reviewed release transition.

Exit: all required service/session/client/provider cases run on their intended supported paths, including genuine positive provenance and fresh-process continuation. No unexplained acceptance-critical skip remains.

## P10. Package, paired Watch and human sign-off

1. Install the reviewed package in an independent location using its documented dependencies. Confirm it does not resolve into developer worktrees/junctions and reports the exact selected identity.
2. Attach the actual client to the intended world/session and verify Start/Retry/Watch through the supported authenticated origin and P1 browser route.
3. Exercise playback controls, disconnect/reconnect, camera/observer noninterference, cancellation, export/import and bounded teardown. Compare state, outcomes and receipts against the same run's authoritative records.
4. Demonstrate original bank/travel/Fishing flow and required method variants, using genuine inputs and the same qualified identity.
5. Obtain human Watch sign-off recording package/run IDs, expected/observed behavior, defects and decision. Automated smoke output is not this sign-off.

Exit: paired package behavior is evidenced and accepted by the designated human; LIVE_XRSPS remains explicit opt-in where required.

## P11. Freeze final acceptance and deliver the reviewed result

1. Freeze final multi-repository commits, generated identities, artifacts, trust configuration, deployed revisions and evidence hashes. Rebuild/requalify when relevant inputs change.
2. Run required CI, affected full Java tests, native/server/browser suites, independent oracle matrix, migration rehearsals, security negatives and target performance checks for those final inputs. Scope formatting to intended changes; report any repository-wide failures rather than mass-format unrelated work.
3. Populate every row below with Given/When/Then, exact command/case, commit/platform/input IDs, raw-evidence digest and PASS/FAIL/BLOCKED/NOT_RUN. Close no row from a vaguely related helper test.
4. Produce Implementation Evidence (Changed files, Tests, Remaining work) and Verification Evidence (Acceptance results, Tests, Risks, Rollback). Include expected-negative, failed, skipped and unrun cases.
5. Prepare concrete reviewed merge/release/rollout artifacts and ownership, monitoring, rollback and migration instructions. Prior publication of branches is not approval for shared deployment.
6. Perform only the authorized delivery, then verify effective identity, trust, auth/refusal, schema, health, registry selection and replay-consumer settings. No force-push or unrelated-history rewrite.
7. Keep Felix/Shortcut untouched while skipped. If explicitly re-enabled, restore/reconcile the five pending records idempotently before new task state claims; never fabricate completed synchronization.

Exit: every original criterion is supported on the delivered inputs, required human/operational gates pass and the handoff accurately identifies any intentionally excluded scope. Until then report partial completion, not done.

## 4. Original 17-row acceptance ledger

This plan does not close rows. Existing tests are supporting evidence, with their actual input/platform scope.

| Row | Required remaining acceptance evidence |
|---|---|
| 274-1 deterministic compiler bytes | Final pinned genuine inputs and independent fresh-process output/checksum reproducibility; P0/P5/P11. |
| 274-2 provenance and curation | Approved source/artifact/catalog/profile/run/curation bindings through actual positive APIs; P4/P6/P9. |
| 274-3 source-linked refusal | Final supported service rejects invalid/incomplete inputs with stable source-linked errors; P5/P9/P11. |
| 274-4 native Fishing | Genuine input and independent causal catch/XP/inventory parity through the qualified runtime; P3-P6/P9. |
| 274-5 incomplete/legacy refusal | Genuine positive path plus verified zero-write/zero-mutation refusal after restart; P6/P9. |
| 274-6 privacy and dataset role | Final role/immutability/provenance tests with authorized input and actual schema; P6/P7/P9. |
| 274-7 inert bounded import/export | Final package/API round trip, bounds and malformed/private/untrusted-input refusal; P9-P11. |
| 274-travel | Exact bank/travel/Fishing state/actions/receipts under sufficient original evidence; P4-P6/P9/P10. |
| 274-historical | Supported verdict and reconciled event/catch accounting, preserving source evidence; P5. |
| 276-boundary | Production xRSPS implementation behind the reviewed adapter and target sandbox; P2/P3/P9. |
| 276-methods | All 18 original rows, ten pending advertisements, approved independent parity and narrow supported action contracts; P4/P5. |
| 276-closure | Reviewed graph/read/module boundary and reproducible executable package on supported target; P2/P3. |
| 276-state | Future-complete checkpoint persistence and restart/ownership recovery through service and real client; P3/P9/P10. |
| 276-safety | Actual target OS isolation and measured resources/cancellation, distinct from Node API denial; P3/SC-2201. |
| 276-live | Supported allocated/authenticated explicit-opt-in lifecycle with current grant/clock operations; P6/P8-P10. |
| 276-client | Genuine capture plus actual client/provider pairing and noninterference; P6/P9/P10. |
| 276-certification | All required component evidence, final qualification, human sign-off and delivered-input matrix; P2/P11. |

## 5. External input request checklist

- [ ] Reference authority: approved producer/collector and exact 18 capture requests with timing/RNG/state requirements.
- [ ] SC-2201 owner: approved Linux target, package/Node/OS pins, sandbox/resource delegation, results and production budget owner.
- [ ] Allocation owner: actual SC-800 endpoint/contract, deployment revision and registered runtime access.
- [ ] Source authority: accepted signed bundle, or explicit reviewed trust/scope amendment; no private-key disclosure requested.
- [ ] Capture participant: authorized login/capture window and allowed world/account data handling.
- [ ] Release owner: released Flyway histories/checksums, supported upgrade pairs and schema rollout authority.
- [ ] Operations: named time-sync, grant issuer/renewal, registry, secret and incident owners with timing/alert policy.
- [ ] Client/provider owner: actual pair, protected credentials/settings and provider cost/time budget.
- [ ] Watch reviewer: named human, package/run IDs and sign-off criteria.
- [ ] Delivery owner: reviewed merge/deployment window and rollback authority once concrete artifacts are ready.

Request only missing inputs when dependent work is ready. Continue independent local work rather than asking for a blanket restart of planning.

## 6. Risks, preservation and estimates

Main risks: treating authored parity as independent fidelity; trusting unverified qualification digests;
stale identity/evidence after source changes; overstating per-process limits; ambiguous migration lineage;
clock-sensitive grants; browser route incompatibility; and accidental shared-resource mutation.

Mitigations are identity-bound evidence, independent approval, expected-negative tests, explicit platform
scope and owned disposable state. Preserve stash 1ffc50bd, native bundles/branches, historical evidence,
pending outbox records and cited worktree paths. Worktree cleanup is a separate maintenance operation.

Rollback: revert only scoped code/config commits; retain recovery anchors. Stop/remove only ownership-verified
disposable resources. Preserve shared databases, immutable content, grants/fence volumes and generations.
Use target-approved backup/forward-recovery procedures for schema changes and fresh protected credentials
for credential recovery. Do not restore retired exposed secrets.

No defensible completion date exists until capture, Linux, allocation/trust and human dependencies are supplied.
Estimate each implementation package after its input review and measured first run; do not describe the remaining
work as a small cleanup or promise that no further code changes are needed.

## 7. Changes made by writing this plan

Documentation only: this file and a superseding pointer in the 25 October plan. Earlier evidence is preserved.
No product code, test rerun, service/database operation, stash/worktree mutation, commit/push, Linux execution
or Felix/Shortcut write was performed as part of this planning step. Fresh test results above refer to the
immediately preceding executed verification, not tests executed by the plan document itself.

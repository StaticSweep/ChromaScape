# SC-274 remaining plan — 3 November 2026

This replaces the open parts of `remaining-plan-20261102.md`. That runbook's hard rules,
environment table, test commands and WP2/WP3 step details still apply unless this file says
otherwise; the sections here say only what changed and what is left.

Verified state comes from read-only checks on 3 Nov 2026 between 18:13 and 18:40Z. Anything not
re-checked is marked *unverified*.

---

## 0. Where things stand (verified 3 Nov)

| Area | State |
|---|---|
| Shortcut SC-274 | In Review, `blocked=true`, no PR or branch linked |
| Blocking stories | SC-2201 **To Do**; SC-1498 **In Review** since 5 Oct; SC-276 Done (narrowed) |
| Rows closed | 274-3 refusal; 274-5 service boundary; travel negative half; 274-historical (BLOCKED verdict, final) |
| Rows partial | 274-1, 274-2, 274-6, 274-7: the v2 compiler requires the causal capability before compiling anything, so **no manifest can exist** (0 in `sc274_capture`) |
| Rows blocked | 274-4, 274-align, travel positive half (SC-2201 / SC-276 / SC-1498) |
| Capture (WP2) | Not taken. Lease `070e0e2a…` expired at 12:07Z and was released. The supervisor isn't running. The client is gone. |
| `IMMUTABLE_PROVENANCE` | **Unresolved.** HTTP start plus client SESSION_START with `preferredSessionId` is refused. The mismatch logger is deployed (confirmed in the class) but has never fired. |
| ChromaScape 8090 | PID 43424, mixed build plus 12 diagnostic overlay classes; DB reconnected. Log about 500 MB of `WindowHandler` noise; today's evidence saved to `redeploy\runtime-8090\application.out.20261103-evidence.log` |
| Containers | All four SC-274 containers restarted at 18:24Z after a Docker restart and recovered cleanly. **No restart policy.** |
| Worktree | `codex/sc274-completion-20261015`, 57 commits ahead of `origin/main`, not pushed, no PR. Two uncommitted diagnostic edits: `DebugSessionService.java`, `TickStreamWebSocketHandler.java` |
| Felix | No task, as you decided (notes only). Plan note `note_1793383300090_26c4edf1` (ChromaScape) exists, updated 2 Nov 15:59Z. **Today a child note `note_1793730398654_fe7a8b7a` was created, contrary to rule 6 below**; see D5. |
| Outbox | One pending SC-274 record: `C:\Dev\IdeaProjects\leeuwbotbackend\docs\builderflow\felix-sync\pending\20261029T234546Z--sc-274--create-note--implementation-evidence-20261030.md` (the 2 Nov plan referred to five) |
| Anomalies to explain | Session for `dharokkillah` at 08:14 local; session with `account=null` at 19:06 local; supervisor `state write skipped (ENOSPC)`, although C: has 345 GB free |

**3 Nov, later checks:** Docker Desktop is stopped (`docker version` cannot open
`dockerDesktopLinuxEngine`), so **P0.1 cannot pass** and the four SC-274 containers are down until it
starts. 8090 (PID 43424) is still listening; its only ESTABLISHED connections are a Firefox tab on
`/debugger?recordingId=recording_af17d0dc` (PID 151920), so there is no second RuneLite client on it.

**Verdict: SC-274 can't reach Done under its current acceptance, even with a perfect capture.**
Whether it can close at all depends on decision D1.

---

## 1. Hard rules (carried forward, updated)

1. Only the user logs in to RuneLite. No auto-login, no gameplay dispatch.
2. Never modify finalized evidence. New evidence is always a new artifact.
3. Secrets load only through `experiment_secrets`; never print, copy or commit them.
4. **Ask before** restarting, stopping or redeploying 8090, the supervisor or the client, and before pushing, merging or changing Shortcut.
5. Do not weaken tests, gates, limits or capability flags. A failing check is a finding.
6. **Felix: no task (your decision). Update only the three existing notes:** plan `note_1793383300090_26c4edf1`, implementation `note_1793383660891_e2cb85fe`, verification `note_1793383661203_c8c05411`; project `C:\Dev\IdeaProjects\ChromaScape`; Windows paths only.
7. **Shortcut: no state transition and no `[felix-sync:…]` marker** while no Felix task exists.
8. micro6: no compile or run without explicit approval in the run.
9. Never relabel a skip as a pass or an authored fixture as genuine evidence.
10. **New: a restart of 8090 overwrites `application.out.log`.** Extract evidence lines first.
11. **New: after any Docker or WSL restart, check the four SC-274 containers.** They don't restart on their own.

Stop and report (don't improvise) on: a missing or unexpected live process, an expired lease, any
5xx, a failed P1 assertion, a fix wider than the named function, or anything that would break §1.

---

## 2. Decisions needed from you

| Id | Decision | Options | Blocks |
|---|---|---|---|
| **D1** | How SC-274 closes | **A** deliver SC-2201 first. **B** narrow the acceptance (as done for SC-276 on 30 Oct) and move rows 1, 2, 4, 6, 7, align and travel-positive to SC-2201, SC-1498 or a new story. **C** allow a playback-only compile without the causal capability (contract change: unpinned manifests, import validation of empty pins) | P4 and closure |
| **D2** | G1 hidden state (timers, varps, RNG, quest) and G4 Mule/coordination intervals | Define the contract now, or move it explicitly to a follow-up | Causal-completeness claim (rows 4 and 274-2 under A or C) |
| **D3** | The pending outbox record | Replay it as an append to the implementation note, or mark it retired as superseded by the 2 Nov evidence | P6 |
| **D4** | Review and merge route for 57 local commits plus the provenance fix | Push and open a PR against `main`; who reviews | P5 |
| **D5** | Today's child note `note_1793730398654_fe7a8b7a` | Keep it, or fold its content into the verification note and delete it (rule 6) | P6 |
| **D6** | Shortcut closure without a Felix task (rule 7) | You change Shortcut by hand, or allow one `shortcut_delivery` task for closure | Final state |
| **D7** | Approval for P1 restarts | Restart 8090 (truncates the log); start the supervisor (new lease, relaunches the client) | P1, P2 |

**Decided 3 Nov 2026 (owner): D1 = B.** D2 follows it: G1/G4 move to the follow-up story (see
`narrowed-acceptance-draft-20261103.md`). **D5 resolved:** you asked for the plan to be put in Felix;
the child note `note_1793730398654_fe7a8b7a` now holds this plan. The 114k-character plan note
was not rewritten in one call, because that risks a truncated overwrite of the record of plan. The
child sits under it through `parent_id`. Still open: D3, D4, D6, D7, and approval of the narrowed
acceptance text before any Shortcut change.

---

## 3. Phases

The order is P0 → P1 → P2 → P3, then P4 for the chosen D1 path, then P5 → P6. P2 needs you at the
machine for about 30–45 minutes.

### P0 — Preflight (read-only, about 5 minutes)

| # | Check | Pass condition |
|---|---|---|
| 0.1 | `docker ps` for the four SC-274 containers | All Up; `sc274-full-capture-postgres-20261031` healthy on 15474; mule registry healthy on 8182 |
| 0.2 | 8090 listening, and the log has no `15474 refused` after start | Yes |
| 0.3 | Deployed `DebugSessionService.class` contains `Immutable provenance mismatch` | Yes (verified 3 Nov) |
| 0.4 | No `hold-windows-assignment.cjs` and no source-runtime client JVM running | Both absent |
| 0.5 | Read `live-handoff-state.json` | Record `phase`/`deadline`. An expired lease means P1.3 is needed |
| 0.6 | `git status` in the worktree | Only the two known diagnostic edits |
| 0.7 | Shortcut SC-274, SC-2201, SC-1498 states | Unchanged from §0, or note the change |

### P1 — Runtime preparation (needs D7)

| # | Step | Detail |
|---|---|---|
| 1.1 | Save evidence lines from the live 8090 log | Same filter as on 3 Nov, into a new dated file. Never delete the old one |
| 1.2 | Restart 8090 | Stop PID 43424, wait for 8090 to free, then `start-8090-debug-hold.ps1 -Address 0.0.0.0`. Wait for `Started ChromaScapeApplication` and no DB errors |
| 1.3 | New lease | Start the supervisor with the exact command in the 2 Nov §1. Wait for `AWAITING_USER_LOGIN`, `catalogState=READY`, `schemaBundleState=AVAILABLE`, `recorderReady=true`, and a deadline of at least 2 hours. Verify the client JAR SHA-256 = `sha256:0a26e022…c6f` |
| 1.4 | Explain the anomalies | Read-only: which process opened the `dharokkillah` and `account=null` sessions (`debug_sessions` rows plus log context); where `ENOSPC` came from (the supervisor's state-write target). Record in `capture\anomalies-20261103.md`. A second client on 8090 is a stop condition for P2 |

Order matters: the 8090 restart (1.2) comes **before** the client launch, because restarting 8090
drops the client connection.

### P2 — Capture (you at the machine)

| # | Step | Pass condition |
|---|---|---|
| 2.1 | You log in to the leased account by hand | `OBSERVED_ACCOUNT_MATCH`, `accountMatches=true`, `grantIssued=true` |
| 2.2 | Regenerate the reference (`prepare-capture-context.py`) | Exit 0; allocation and executor match the state file |
| 2.3 | Start (copied `start-context-bound-human-fishing.py`, with graph identity) | HTTP 200, `recordingEnabled=true`, **and** the client's SESSION_START is accepted (no `IMMUTABLE_PROVENANCE` within 30 s) |
| 2.3a | **If it's refused** | Read the `Immutable provenance mismatch … differingFields=[…]` WARN. Stop the recording normally. Fix only the named cause in `DebugSessionService` / the run-context `bind` path (expected: a field the HTTP start writes but the client echo recomputes differently). Add a regression test that binds HTTP-start context and client echo to the same row and asserts equality. Compile, update the overlay, restart 8090 (approval), relaunch the client on the **same lease**, and you log in again. Retry 2.3 **once**; a second refusal is a stop condition |
| 2.3b | **If no WARN appears but it's still refused** | The refusal comes from the second site (taskKey or settingsHash check, `DebugSessionService` about line 275). Add the same bounded diagnostic there and repeat 2.3a |
| 2.4 | Confirm the stored run context through the API | Contains `graphRevision`, `recordingId`, `debugSessionId`, `contextHash` |
| 2.5 | You fish 5 trout by hand; the monitor only observes | 5 trout caught |
| 2.6 | Stop via `/api/debug/replay/stop-recording`; wait for finalization; only then the supervisor STOP file | Recording FINALIZED |
| 2.7 | Verify (`verify-genuine-fishing.py` plus `FullMediaProbe.java`) → `capture\capture-verification.json` | Contiguous ticks; calibration present; 0 unknown or dropped frames; media decodes; every Fishing-spot NPC has `spotId`; every tick has `world{worldId, regionId, plane}`; 5 trout and the XP delta observed |

If 2.7 fails, keep the recording untouched, record the failure, and ask before re-recording.

### P3 — Acceptance work that needs only the recording (any D1 path)

| # | Step | Output |
|---|---|---|
| 3.1 | Publish the HELD_OUT curation for the new recording (copied `publish-genuine-curation.py`); `bf83bfe1` untouched | New immutable curation ID |
| 3.2 | Row 5 on genuine input, recording half: preflight the new recording plus `recording_af17d0dc`, read-only | Both `playbackEligible=true`; causal refused with a stable code; 0 writes; capability flags unchanged (read them before and after) |
| 3.3 | Output completeness (G2/G5/G7) against the story's Output list, from recording evidence | `acceptance\output-completeness.md`: each element present with provenance, or a named blocker; no raw click, packet or WebSocket reference |
| 3.4 | World-number mapping | Record how the live world maps to a simulator world, or name it as a blocker field |

### P4 — Path-dependent work (after D1)

**Path B (chosen 3 Nov): narrow and close**
1. ~~Draft the narrowed acceptance text~~ **Done:** `narrowed-acceptance-draft-20261103.md` (text to append to SC-274 plus the follow-up story REPLAY-2b). The story text that would be replaced is saved at `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\acceptance\sc274-description-before-narrowing-20261103.md`.
2. You approve the text, then it goes into the SC-274 story description (rule 4: approval before the Shortcut change).
3. Add the moved rows to SC-2201's acceptance (rows 1, 2, 4, 6, 7, travel-positive) and record 274-align against SC-1498, or create one follow-up story. Link it to SC-274.
4. Under the narrowed acceptance every remaining row is closed, so go to P5.

**Path A: SC-2201 first**
1. SC-2201 delivers a registered, qualified causal runtime (Linux host, cgroup v2, mount isolation, durability, packaged artifact). That is outside this story's work.
2. Re-preflight: `causalSimulation=true`, and a manifest compiles for the new recording.
3. Run 2 Nov WP3.3 (row 1 double compile in two clean clones), WP3.4 (row 2 provenance audit), WP3.6 (rows 6 and 7 round trip plus variants, **on a clean reviewed build**, which is the G10 obligation), and 3.7 compile half.
4. Row 4 causal run; travel positive needs a bank-to-travel capture on an SC-276-capable world; align still needs the SC-1498 approval. Without that approval, align stays open, so A alone still needs a narrowing for align.

**Path C: playback-only compile**
1. Write the contract change: what a playback-only manifest is; that it carries no runtime pins; that import refuses causal use of an unpinned manifest; that `simulationEligible` stays false.
2. Implement in `ScenarioManifestV2Compiler.compileInternal` (move `requireSimulationCapability` to the causal path only), with tests: playback compile succeeds without capability; a causal request still refuses `XRSPS_CAPABILITY_UNAVAILABLE`; import of an unpinned manifest can't become simulation-eligible.
3. Review, then rows 1, 2, 6, 7 and 3.7 compile half on the genuine recording, as in Path A step 3.
4. Rows 4, align and travel-positive still need narrowing (Path B steps 1–3 for those three).

### P5 — Code, tests and review (needs D4)

| # | Step |
|---|---|
| 5.1 | ~~Remove the bounded diagnostics, or keep them deliberately~~ **Done 3 Nov:** the SESSION_START diagnostic is kept but redacted to presence and field counts (it previously serialized the payload, which would log the account name and task settings). Both refusal sites now log `differingFields` (names only). |
| 5.2 | ~~Commit the `IMMUTABLE_PROVENANCE` fix plus its regression test~~ **Done 3 Nov:** the reuse guard compares canonical capture identity (`BehaviorRunContextService.sameProvenance`, both digests re-verified) instead of raw JSON trees. Regression tests: `BehaviorRunContextIdentityTest` (6) and `DebugSessionProvenanceReuseTest` (3). This removes representation-only false refusals; it does **not** prove the live cause — that needs P2.3a. |
| 5.3 | Tests on the final commit: focused provenance lane **31 tests, 0 failures**; full `replayTest --offline` **871 tests, 26 failed, 42 skipped** = the 16 recorded environmental failures plus 10 `ContainerLaunchException` because Docker Desktop is stopped. T4 PostgreSQL integration and the Docker-backed lanes remain unrun. |
| 5.4 | Rebuild a clean candidate from the final commit; if the overlay classes no longer differ from it, retire the mixed build |
| 5.5 | With approval: push `codex/sc274-completion-20261015`, open a PR against `main`, link it to SC-274, get review, merge |

### P6 — Records and handoff (needs D3, D5, D6)

| # | Step |
|---|---|
| 6.1 | Committed docs in this folder: `implementation-evidence-20261103.md` (`## Changed files`, `## Tests`, `## Remaining work`) and `verification-evidence-20261103.md` (`## Acceptance results`, `## Tests`, `## Risks`, `## Rollback`); a dated section in `acceptance-ledger-20261029.md` |
| 6.2 | Felix: `get` then append a dated section to each of the three notes (rule 6); then apply D5 to the child note |
| 6.3 | Outbox: apply D3 using the replay protocol in `C:\Dev\IdeaProjects\AGENTS.md`; move the record to `synced\sc-274\` with real IDs, or mark it retired |
| 6.4 | Shortcut: apply D6. If a Felix task is allowed: Implementation Evidence → `in_review`, Verification Evidence → `done`, then the `[felix-sync:<task>:done]` marker after checking for an existing one. Otherwise you move the story by hand and a comment cites the evidence notes |
| 6.5 | Clear `blocked` on SC-274 only when nothing in the narrowed acceptance is blocked |
| 6.6 | Cleanup with approval: stop 8090 and the supervisor; keep the containers and volumes (evidence); give the SC-274 containers a restart policy if work continues |

---

## 4. Row closure matrix

| Row | Path B | Path A | Path C |
|---|---|---|---|
| 274-1 determinism | moved | P4-A.3 | P4-C.3 |
| 274-2 provenance | moved | P4-A.3 (plus D2) | P4-C.3 (plus D2 or named blockers) |
| 274-3 refusal | closed | closed | closed |
| 274-4 runnable Fishing | moved | P4-A.4 | moved |
| 274-5 incomplete boundary | closed plus P3.2 | plus 3.7 compile half | plus 3.7 compile half |
| 274-6 roles/privacy | moved | P4-A.3 | P4-C.3 |
| 274-7 bounded import | moved | P4-A.3 | P4-C.3 |
| 274-align | moved (SC-1498) | moved unless approved | moved |
| travel negative | closed | closed | closed |
| travel positive | moved | P4-A.4 | moved |
| historical | closed (verdict) | closed | closed |
| capture quality (new) | P2.7 | P2.7 | P2.7 |

---

## 5. Definition of done

- Every row in the (possibly narrowed) acceptance has positive or expected-negative evidence from genuine inputs, with its path in the ledger.
- The code is merged to `main` with tests T2–T4, T10 and T11 green on the merge commit (environmental baseline failures listed by name).
- No diagnostic code was merged by accident; the provenance fix has a regression test.
- The Felix notes and the outbox are reconciled; Shortcut state matches the evidence, and every moved row exists in its new story.
- The final report separates verified facts from unrun checks.

## 6. Risks

- Lease time: each failed start (P2.3a) costs a restart and a client relaunch inside the lease window. Start with at least 2 hours left.
- A Docker or WSL restart during P2 kills the capture DB connection. Check rule 11 first and don't run anything that restarts Docker during capture.
- Unknown second client (`dharokkillah`, `account=null`). If it's live on 8090 during P2 it could steal or confuse the session. Resolve it in P1.4.
- The mixed build: no evidence from 8090 is a clean-build result until P5.4.
- The world-number mapping may stay a named blocker for row 2.
- 16 environmental full-suite failures can hide regressions in those classes.

## 7. Rollback

- Recordings and curations are append-only. If unusable, mark them non-acceptance in the docs; never delete them.
- Code: revert the local commits; nothing is pushed until P5.5.
- 8090: restart from the clean candidate classpath, or restore the backed-up `GraphSchemaValidator.class` and remove the overlay classes.
- Client: `C:\Dev\IdeaProjects\verification\private\sc274-full-capture-20261031\windows-handoff-config.pre-3817133f.20261101T213808Z.json`.
- Shortcut: the story-text narrowing is reversible by restoring the previous description (save it before editing).

---

## 8. Execution log — 3 November 2026, after P5

| Step | Result |
|---|---|
| Docker | Engine reachable (28.3.3) after starting Docker Desktop; containers were `Exited (255)` from the earlier Docker restart |
| P0.1 / rule 11 | `sc274-full-capture-postgres-20261031` healthy on 15474, `sc274-registry-postgres-20261020` up, `sc274-registry-mule-20261020` healthy on 8182 (restarted once after its DB was still starting). `sc274-registry-windows-catalog-20261031` **exits 127** and must be recreated by `deploy-catalog-bridge.py` against the live executor |
| 1.1 | Live log filtered into `redeploy\runtime-8090\application.out.20261103-2127-evidence.log` (479,820 lines); the 517 MB live log is untouched |
| 1.2 / 5.4 | 8090 stopped (PID 43424) and restarted on the **clean candidate jar** (`6622a23d`, PID 233944) via `redeploy\start-8090-candidate-20261103.ps1`; started in 20 s, no `15474` errors, `/debugger` 200, `/api/replay/capabilities` 200, `/api/debug/sessions` 200. The class overlay is retired |
| 1.3 | Supervisor started (`supervisor-7.out.log`); lease `9a60481e-cc17-42dd-8033-e2b322bf65aa`, executor `f24979d6-4802-45a8-bcac-c9d2196c1f29`, deadline `2026-11-03T23:28:09Z`, `AWAITING_USER_LOGIN`, `catalogState=READY`, `schemaBundleState=AVAILABLE`, `recorderReady=true`; client PID 229980 |
| P4-B.2/3 | SC-274 description now carries the narrowed section (original text retained); follow-up **SC-2273** created and linked (SC-274 relates, SC-2201 and SC-1498 block) |
| D3 | The last pending SC-274 outbox record was replayed as note `note_1793741389129_fc38f89c` and moved to `ChromaScape\docs\builderflow\felix-sync\synced\sc-274\`; zero SC-274 records remain pending. No Felix task was created |
| D4 | Branch pushed and **PR #108** opened against `main` (`https://github.com/ikiddoi/ChromaScape/pull/108`); not merged |

Still owner-gated: P2.1 (manual login), then 2.2–2.7.

---

## 9. Gaps closed on 4 November 2026

Each of these was a way the next attempt could fail before the user reached the capture.

| Gap | Cause found | Fix |
|---|---|---|
| Supervisor died silently on 3 Nov, leaving the account unclaimable | It was started as a child of a shell job that was killed at its 120 s limit; `SIGKILL` skips the `finally` release | `verification/sc274-sc276-continuation-20261102/start-supervisor-detached.ps1` creates the process through `Win32_Process.Create` (outside the shell's tree), refuses to start a second supervisor, and writes a timestamped log plus `supervisor-current.pid`. Verified: the supervisor survived the launching job and the client came up |
| A killed supervisor left a `claimed` assignment that blocked every new claim | The mule's assignment sweeper is **disabled** in this container (`MULE_ASSIGNMENT_SWEEPER_ENABLED`, `/health` reports `sweeper_enabled:false`), and the release API answers `410 LEASE_EXPIRED` for an expired lease, so the partial unique index on active assignments keeps the account blocked | Recovery, once: `update launcher_assignments set status='released', last_reason_code='SUPERVISOR_TERMINATED_OUTSIDE_SHUTDOWN', updated_at=now() where assignment_id='…' and status='claimed' and lease_expires_at < now()` in `sc274-registry-postgres-20261020`. The next claim then succeeded. This is a registry-configuration gap, not an SC-274 code change |
| Orphaned client held 8180, so the relaunch refused to start a second runtime | A supervisor that dies leaves its client running | Stop the orphan before relaunching: the java PID in `live-handoff-state.json` `sourcePid` and its `cmd` parent; the supervisor already fails closed and releases its claim instead of stealing the port |
| `sc274-registry-windows-catalog-20261031` exited 127 and could not be restarted | It joins the registry's network namespace (`--network container:<mule>`), so a mule restart destroys it; the deploy script had no replace path | `deploy-catalog-bridge.py` gained `--replace` (removes only a container carrying the `sc274.owner=windows-capture-catalog-bridge` label) and refuses to create a bridge for an expired lease (`--verify-existing` still works). Verified end to end on lease `008c80eb…`: `/health` and `/api/v1/catalog?mode=full` both 200 inside the registry namespace, executor `fc5c52f8…`, artifact `sha256:0a26e022…`; report in `capture/catalog-bridge-verification.json`. The binary hash now comes from the image instead of a path that had moved |
| A failed state write dropped the phase update | `save()` returned on any temp-file error, including the one observed as `ENOSPC` with 366 GB free (a 0-byte `.tmp` remains) | `hold-windows-assignment.cjs` now writes in place when the temp write fails, and only then reports a deferred write. Fallback exercised with an unwritable temp path |
| SC-274 containers did not come back after a Docker restart | No restart policy | `docker update --restart unless-stopped` on the capture PostgreSQL, registry PostgreSQL and mule; verified with `docker inspect` |

Runtime after these fixes: lease `008c80eb-fa22-41a1-b9e9-6f158ceb3a4a`, executor
`fc5c52f8-1703-4070-94c3-158c83d7ac5e`, deadline `2026-11-04T04:39:11Z`, `AWAITING_USER_LOGIN`,
catalog `READY`, schema bundle `AVAILABLE`, recorder ready.

### 6 November 2026 — the host clock blocks the lease

The supervisor refuses to claim with `Registry clock outside existing five-second safety limit`.
Measured, not guessed:

| Clock | UTC | Offset vs NTP |
|---|---|---|
| Windows host | `00:15:16` | **−7.1 s** (two NTP queries and two HTTPS `Date` headers agree) |
| `sc274-registry-mule-20261020` (container VM) | `00:15:23` | ≈ 0 |

`w32time` is **stopped** and starting it needs elevation this session does not have
(`net start w32time` → Access denied; `Start-Process -Verb RunAs` → InvalidOperationException).
The five-second guard is a lease-fencing safety limit and is not weakened here.

Fix (owner, one action): Settings → Time & language → Date & time → **Sync now**, or an elevated
`net start w32time; w32tm /resync /force`. Then the lease path is:

1. `powershell -File verification\sc274-sc276-continuation-20261102\start-supervisor-detached.ps1`
2. wait for `AWAITING_USER_LOGIN` with `catalogState=READY`, `recorderReady=true`
3. `python -I capture\deploy-catalog-bridge.py --replace` (bridge for the new executor)
4. owner logs in, then `prepare-capture-context.py`, then `start-context-bound-human-fishing.py`

Also verified this run: `wsl --shutdown` + Docker restart brought all 26 containers back (23
`leeuwbot`/`chromascape` plus the SC-274 ones) because of the new restart policy; the catalog bridge
did not come back and is recreated in step 3; 8090 (PID 233944, clean candidate jar) stayed up
throughout and still answers `200` on `/api/replay/capabilities`.

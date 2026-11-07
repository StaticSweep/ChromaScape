# SC-274 remaining plan — implementation runbook (2 November 2026)

Written so an agent with **no prior conversation context** can execute it. Read sections 0–3
completely before running anything. This plan supersedes the "remaining" sections of
`current-remaining-delivery-plan.md` and `capture-readiness-20261101.md`, and keeps their
dated evidence.

SC-274 = "REPLAY-2: Curated recording to semantic scenario compiler" (Shortcut story 274).
**It stays In Review. Do not mark it Done; section 9 explains why that is not reachable here.**

---

## 0. Hard rules — violating any of these is a failed run

1. **No automatic game login.** Only the user logs in to RuneLite. Never fill credentials, never use `prepare-trumpsniper-autologin.py`.
2. **No gameplay dispatch.** Never send tasks or commands to the executor. The user plays by hand.
3. **Never modify finalized evidence**: recordings, curations, media, tick rows. New evidence is always a new artifact.
4. **Never print or commit secrets.** Credentials load only through `experiment_secrets` (`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261019\experiment_secrets.py`). Private files live under `C:\Dev\IdeaProjects\verification\private\` and must never be copied into `docs\` or public reports.
5. **Ask the user before** restarting, stopping or redeploying any live process (8090 PID 143068, supervisor PID 57496, client PID 128104), and before pushing, merging or changing Shortcut.
6. **Felix:** do not create a task (user decision: notes only). Update only the three existing notes listed in §2. Every path written to Felix or Shortcut must be a Windows path (`C:\...`), never `/mnt/c/...`.
7. **Shortcut:** no state transition and no `[felix-sync:…]` marker. No Felix task exists to back one.
8. **micro6:** its `AGENTS.md` forbids compiling or running. Do not rebuild micro6 unless the user explicitly approves it in this run.
9. **Do not weaken tests, limits, gates or capability flags** to make something pass. A failing check is a finding: record it.
10. **Do not touch** the unrelated unresolved merge in `C:\Dev\IdeaProjects\micro6` (`UU AGENTS.md`, `docs/builderflow/sc-2106/*`), or other worktrees.
11. Never relabel a skip as a pass, or an authored fixture as genuine evidence. Report verified facts separately from unrun checks.

**Stop and report to the user** (do not improvise) when: a live process is missing or in an
unexpected state; a lease has expired; any API returns 5xx; a P1 assertion fails; a fix would
change more than the specific function named in the step; or anything would require violating §0.

---

## 1. Environment

| Thing | Value |
|---|---|
| ChromaScape worktree (all code changes here) | `C:\Dev\IdeaProjects\.worktrees\sc274-completion`, branch `codex/sc274-completion-20261015` (33 commits ahead of origin, not pushed) |
| micro6 producer worktree (read-only) | `C:\Dev\IdeaProjects\.worktrees\sc274-micro6-producer-20261101`, commit `3817133f` |
| Docs folder | `<worktree>\docs\builderflow\sc-274-sc-276-continuation-20261019\` |
| New evidence root for this run | `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\` (create it) |
| Private evidence root | `C:\Dev\IdeaProjects\verification\private\sc274-full-capture-20261031\` |
| JDK | `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot\bin\java.exe` |
| Python | `python -I <script>` |
| Shell | PowerShell on Windows. Use `.\gradlew.bat` from the worktree root |
| ChromaScape (owned candidate) | `http://127.0.0.1:8090`, DB `sc274_capture`, launch info `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\full-candidate\runtime-8090\launch.json` |
| Executor (the RuneLite client) | `http://127.0.0.1:8180`, bearer auth (`executor_auth` secret). **Port 8098 is not used.** |
| Supervisor | `node C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\windows-assignment-handoff\hold-windows-assignment.cjs C:\Dev\IdeaProjects\verification\private\sc274-full-capture-20261031\windows-handoff-config.json` |
| Supervisor state file (read this, don't guess) | `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\windows-assignment-handoff\live-handoff-state.json` |
| Account | TrumpSniper, account hash `f1d8d53b19908158` |
| Task / profile | `FISHING_TRAINING`, profile `1a67835e-3c32-4d95-94ab-46b537ff5687` v1 |
| Published graph | `sc274_human_fishing` v1, revision `sha256:741a1131036365a57d9abced9bfaf570c2d7c9385ffb335d9b5bacee86476372`. Binding file `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261101\graph-bootstrap\graph-binding.json` |
| Signed client artifact | `sha256:0a26e022ab65091be46e078dd1cc292cc95df7f6d4613bb03c46e6103c650c6f` |
| Felix notes (update only) | plan `note_1793383300090_26c4edf1`, implementation `note_1793383660891_e2cb85fe`, verification `note_1793383661203_c8c05411`; project `C:\Dev\IdeaProjects\ChromaScape` |

### Test commands (run from the worktree root)

```powershell
# Focused compiler lane (expect 62 + 2 + any new tests, 0 failures/skips)
.\gradlew.bat replayTest --offline --tests "com.chromascape.web.replay.manifest.ScenarioManifestV2CompilerTest" --tests "com.chromascape.web.replay.manifest.GraphSchemaValidatorOfflineTest"
# Manifest + compiler packages (baseline 129, 0 failures)
.\gradlew.bat replayTest --offline --tests "com.chromascape.web.replay.manifest.*" --tests "com.chromascape.web.replay.compiler.*"
# Full replay suite (baseline 891 tests: 875 pass, 16 known environmental failures, 43 skipped)
.\gradlew.bat replayTest --offline
```

`replayTest` compiles `src/test/java/com/chromascape/web/replay/**`. Results land in
`build\test-results\replayTest\`; copy the XML for every run you cite into the evidence root.
**Baseline for the full suite:** record the names of the 16 failing tests before your changes
(run once at the start), so any new failure can be identified.

**Formatting:** whole-tree `spotlessApply`/`spotlessCheck` has pre-existing failures. Do not run
it on the tree. Format only the files you touch, using the init script
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261030\sc274-execution\touched-format.init.gradle`
(read it first to see how it takes file targets). Then run `git diff --check`. Preserve LF line endings.

---

## 2. State verified 2026-11-02 02:43–03:16Z (re-verify in WP0; it will have changed)

- 8090 is PID 143068, restarted at 03:23:12 local for an unknown reason. Classpath `C:/Dev/IdeaProjects/verification/sc274-live/build-b29c7d5f/classes` with patched `GraphSchemaValidator.class` (SHA-256 starts `905563733CFD`). This is a **mixed build**: b29c7d5f plus one patched class.
- Supervisor phase `AWAITING_USER_LOGIN`; catalog READY, schema bundle AVAILABLE, recorder ready, game at login screen. Lease allocation `0c913f3d-b38c-41e5-b135-a880922d9a03`, executor `8f40c643-69ce-4e32-ab78-42bc98334bd5`, **deadline 2026-11-02T04:06:01Z**. That lease has almost certainly expired by the time you read this; see WP2.1.
- Capture reference generator `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261101\capture-context\prepare-capture-context.py` now reads the live state file and refuses stale or expired identities (16 checks).
- Prior genuine capture `recording_af17d0dc` + HELD_OUT curation `bf83bfe1-636d-4728-99e0-516038198e1b`: genuine HUMAN, 236 ticks, but no `graphRevision`, 138 NPC `spotId` violations and no world ID. **Useful as a genuine incomplete input for row 5.** Do not modify it.
- Five unreplayed SC-274 Felix outbox records (2026-10-16/17) in `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-sync\pending\*sc-274*`.
- Shortcut 274: In Review, `blocked=true`.

---

## 3. Acceptance rows (from the Shortcut story text — authoritative)

`CLOSABLE` = closable in this plan. `BLOCKED` = needs an external gate (§8).

| Row | Given / When / Then | Status | Closed by |
|---|---|---|---|
| 274-1 determinism | Given the new genuine recording + its HELD_OUT curation + compiler version V, when compiled in two independent clean directories (fresh JVMs, no build cache), then manifest bytes and checksum are identical | CLOSABLE | WP3.3 |
| 274-2 provenance | Given that manifest, when every field and action is audited, then each links to source evidence or an audited fixture, with `OBSERVED/DERIVED/OPERATOR_SUPPLIED/DEFAULTED` provenance | CLOSABLE | WP3.4 |
| 274-3 stable refusal | Given off-by-one tick, tick gap, duplicate action, reordered action, missing required state or incompatible cache/collision revision, when compiled, then each fails with the same stable code and source reference on every run | CLOSABLE | WP1.1–1.3 |
| 274-4 runnable Fishing | Given a bounded Fishing recording, when compiled, then the scenario runs causally with no raw input dependency | BLOCKED (causal part); "no raw input" part shown in WP3.5 | §8 |
| 274-5 incomplete boundary | Given an incomplete scenario, when preflighted, then playback/policy replay is allowed, causal simulation is refused with a stable blocker, and nothing is written, including across a service restart | CLOSABLE | WP1.4, WP3.7 |
| 274-6 roles/privacy | Given the new authorized genuine input with role HELD_OUT, when exported, imported and accessed by owner/foreign/missing principals, then role, immutability and redaction hold | CLOSABLE | WP3.6 |
| 274-7 bounded import | Given the exported genuine package and malformed/oversized/over-deep/code-carrying/network-carrying variants, when imported through the API, then the genuine one round-trips byte-identically and every variant is refused with zero writes | CLOSABLE | WP3.6 |
| 274-align provider first | Given SC-1498's provider-origin recording, when compiled, then lineage to it is kept and simulator outputs use distinct identities | BLOCKED (identity-separation part testable: WP1.8) | §8 |
| 274-travel amendment | Bank→travel→Fishing compiles deterministically when evidence and native world support suffice; missing bank state, charges, widget semantics, spawn state, region/collision identity or actions fail with stable source-linked reasons; multi-region, plane-change, duplicate/reordered and ambiguous-widget fixtures exist; action coverage % is reported separately from completeness | Negative half CLOSABLE (WP1.5); positive half BLOCKED | §8 |
| 274-historical | `recording_b83c37e5` re-audited with pinned decoders against original tick/menu/action/RSProx evidence; gaps resolved, or the precise blocked verdict kept | CLOSABLE | WP4 |

Story gap policy, used by WP1.6: *"Missing required NPC/resource timers, collision/cache revision,
bank contents, quest state or stochastic context yields `SCENARIO_INCOMPLETE` unless an authorized
fixture supplies it. Video can support human review but cannot silently become structured state.
Unsupported Mule/external coordination intervals are excluded."*

---

## 4. Execution order

```
WP0 (preflight, read-only)
 ├─ WP1 (tests/fixes, no user needed) ── can run before or after WP2
 └─ WP2 (live capture, user needed) ── WP3 (genuine-input acceptance) ── WP5
WP4 (historical audit, independent)
WP5 (evidence, docs, Felix notes, handoff) last
```

If the user is not available, do WP0 → WP1 → WP4, then stop and ask for WP2.

---

## WP0 — Preflight snapshot (read-only)

1. Create `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\wp0\`.
2. Record into `wp0\preflight.json`:
   - `git -C <worktree> rev-parse HEAD` and `git status --short` (must be clean apart from this plan doc);
   - the owner PID of port 8090 (`Get-NetTCPConnection -LocalPort 8090 -State Listen`), its command line, and `GET http://127.0.0.1:8090/debugger` status;
   - the SHA-256 of `C:\Dev\IdeaProjects\verification\sc274-live\build-b29c7d5f\classes\com\chromascape\web\aiplayer\routine\service\GraphSchemaValidator.class` (must start `905563733CFD`);
   - the full contents of `live-handoff-state.json` (phase, deadline, allocationId, executorId), and whether PIDs 57496/128104 are alive;
   - `GET http://127.0.0.1:8090/api/replay/capabilities` (expect `causalSimulation=false`, `certifiable=false`).
3. **Diagnose the 03:23:12 8090 restart (P0.1):** read `runtime-8090\application.err.log`, `application.out.log` and the tail of `gc.log.1`, plus Windows Application event-log entries for `java.exe` between 03:20 and 03:25 local on 2026-11-02. Write the cause, or "undetermined" with what you checked, into `wp0\8090-restart.md`. Do not restart anything.
4. Note `source-launch.err.log` (`Set-Acl … PrivilegeNotHeld`) in `wp0\risks.md`: the per-launch args file is not ACL-restricted. Do not fix it.
5. Run the full `replayTest` once on the unchanged HEAD and save the list of failing test names to `wp0\baseline-failures.txt`. Expected: 16 environmental failures.

**Done when:** `preflight.json`, `8090-restart.md` and `baseline-failures.txt` exist.

---

## WP1 — Tests and compiler fixes (no live system, no user)

All code goes in the ChromaScape worktree. Main code: `src\main\java\com\chromascape\web\replay\`.
Tests: `src\test\java\com\chromascape\web\replay\`. Key files:

- `manifest\ScenarioManifestV2Compiler.java`: initial state at about lines 930–1109; `Compilation.simulationBlockedReasons()` at about line 1636.
- `manifest\ScenarioManifestImportValidator.java` (`requireSimulationAllowed`), `manifest\ScenarioCompilationEvidence.java` (`world()`, `requiredNativeBindings`).
- `catalog\ReplayV2PreflightService.java`, `catalog\ReplayEvidenceValidator.java`, `catalog\ReplayNpcPinValidator.java`, `error\ReplayErrorCode.java`.
- Existing tests: `manifest\ScenarioManifestV2CompilerTest.java`, `ScenarioManifestImportValidatorTest.java`, `NativeBankStateContractTest.java`, `NativeNpcBindingContractTest.java`, `ScenarioCompilationWidgetEvidenceTest.java`, `TerminationPolicyContractTest.java`, `ExecutionLimitContractTest.java`, `conversion\HumanFishingActionReaderTest.java`, `integration\Sc274CompilerTransactionIntegrationTest.java`.

**Method for every item:** (a) grep the tests for existing coverage and list what you found in
`wp1\coverage.md`; (b) add only the missing tests, built with the fixture helpers the neighbouring
tests already use; (c) run the focused lane; (d) if a new test fails because the production code
does the wrong thing, apply the minimal fix in the named function. If the fix needs a new design
(a new error code family, a schema change or a contract change), **stop and report a proposal
instead.** Assert the stable error code **and** the source reference (tick/snapshot ref) in every refusal test.

| # | Item | Assert |
|---|---|---|
| 1.1 | Row 3 refusal matrix: off-by-one tick, tick gap, duplicate action, reordered action, missing required state, incompatible cache revision, incompatible collision revision | Each refuses with a specific `ReplayErrorCode` and a source ref |
| 1.2 | **Derivation must not mask incompatibility** (commit `952819c1` derives a missing `collisionRevision` from the pinned content identity) | (a) a captured `collisionRevision` that differs from the pinned one is refused, not overwritten; (b) a derived value carries `provenance=DERIVED` and `sourceRef` `worker-build:…/contentRevision:…`; (c) with no captured and no pinned value, `SIM_INITIAL_WORLD_INCOMPLETE` |
| 1.3 | Stability across processes | Run the 1.1 refusals in two fresh JVMs (pattern: `FreshJvmCompileMain`/`FreshJvmCompileFixture` in `manifest\`) and compare the error payload bytes. Save both to `wp1\refusal-a.json` / `refusal-b.json` |
| 1.4 | Row 5 boundary | An incomplete compilation: `isSimulationAllowed()==false`, `requireSimulationAllowed()` throws `SIM_INITIAL_STATE_INCOMPLETE` with reasons, and the playback/policy path does not call it. Plus a restart check: through the existing PostgreSQL integration lane (`Sc274CompilerTransactionIntegrationTest`, which uses an **owned disposable** DB; read its setup and never point it at `sc274_capture` or shared 5432 databases), preflight, recreate the Spring context, preflight again, and assert row counts unchanged |
| 1.5 | Amendment negatives | One stable source-linked failure each for: missing bank state, missing jewelry charges, missing widget semantics, missing spawn state, missing region/collision identity, missing actions. Fixtures for multi-region, plane-change, duplicate/reordered and ambiguous-widget. A test where 100% of captured rows map but the stream is incomplete, so it stays non-simulation-eligible (coverage ≠ completeness). Check `NativeBankStateContractTest`, `ScenarioCompilationWidgetEvidenceTest` and `NativeNpcBindingContractTest` first; most may exist |
| 1.6 | **G1 hidden state (possible defect).** Grep found no `timer`/`respawn` handling in `replay\`, and varps only in playback | Tests for a Fishing scenario with (a) no resource/NPC timer evidence, (b) no varps/task state, (c) no stochastic/RNG context, (d) no quest state: each must make `simulationBlockedReasons()` non-empty with a named reason, unless an authorized fixture supplies the value. Check first whether `requiredNativeBindings` (from `ScenarioCompilationEvidence.world`) already blocks these indirectly; if so, write a test proving it and record that it is indirect. If nothing blocks them, the fix is to add named reasons in `simulationBlockedReasons`. Anything bigger: stop and report |
| 1.7 | **G3 authorized fixture path** | An `OPERATOR_SUPPLIED` value fills a missing field with that provenance and an evidence ref. An unauthorized or unsourced one is refused. Find the existing mechanism via `FieldProvenanceSource` / `ActionProvenance` |
| 1.8 | **G4/G6 exclusions and identity** | (a) media/video references never appear as structured initial state or actions; (b) Mule/external-coordination intervals are excluded. No code matches `mule|coordination`: if unimplemented, **report it as a gap and do not invent a design**; (c) simulator-generated sessions/traces get distinct identities and storage and cannot be labelled live or human |

Then: format touched files, run the focused lanes and the full `replayTest`, and compare against
`wp0\baseline-failures.txt` (any new failure blocks the commit). Commit on the branch, one commit
per logical item, message ending with
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.

**Done when:** every row in the table is a passing test, a fixed defect with a test, or a reported gap. `wp1\coverage.md` maps each to its test name.

---

## WP2 — Live capture (requires the user at the machine)

Base the scripts on the 31 Oct versions in
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\human-fishing-coverage\delay-correction\`
(`start-context-bound-human-fishing.py`, `monitor-human-fishing.py`, `verify-genuine-fishing.py`,
`FullMediaProbe.java`, `publish-genuine-curation.py`). **Copy** them into
`C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\capture\` and read each one
fully before running it. Change only the hard-coded paths, names and IDs listed below.

### 2.1 Lease
Read `live-handoff-state.json`. If `phase` is `AWAITING_USER_LOGIN` or `OBSERVED_ACCOUNT_MATCH`
and `deadline` is at least 30 minutes away, continue. **Otherwise stop and ask the user.**
Restarting the supervisor relaunches the client, so the user must approve it and confirm no game
session is open. If approved, start the supervisor with the exact command in §1 (only after
confirming no `hold-windows-assignment.cjs` is running), wait for `AWAITING_USER_LOGIN` with
`catalogState=READY`, `schemaBundleState=AVAILABLE` and `recorderReady=true`, then verify the
client JAR SHA-256 equals the artifact in §1.

### 2.2 Login (user)
Ask the user to log in to TrumpSniper manually. Poll the state file every 30 s until
`phase=OBSERVED_ACCOUNT_MATCH`, `accountMatches=true`, `grantIssued=true`.

### 2.3 Reference
Run `python -I C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261101\capture-context\prepare-capture-context.py`.
It must exit 0, with every check PASS and allocation/executor equal to the state file.

### 2.4 Start: required changes to the copied `start-context-bound-human-fishing.py`
- Read the state file from its real path (§1), not from `ROOT`.
- **Reuse the existing profile.** Copy `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261031\windows-assignment-handoff\human-fishing-profile-response.json` into the new folder first. The script then reuses it. Assert `profile_id == 1a67835e-3c32-4d95-94ab-46b537ff5687` and `profile_version == 1`. Never create a new profile: the graph is bound to this one.
- **Add the graph identity to `context`**: `graphId`, `graphVersion`, `graphRevision` from `graph-binding.json` (the 31 Oct script lacks them, which is why `af17d0dc` had no graphRevision). Assert that `graphRevision` equals the value in §1.
- Write private request/response files under a new private folder `C:\Dev\IdeaProjects\verification\private\sc274-full-capture-20261102\` (protect with `secure.protect_existing_file`, as the original does), and write the public report into the new `capture\` folder.
- Keep every original assertion (logged in, account hash, executor selectable, no current task, no existing recording).

Run it. Expect HTTP 200 with a `recordingId` and `recordingEnabled=true`. Then confirm the stored
run context through the API (pattern in `verify-genuine-fishing.py`): it must contain
`graphRevision`, `recordingId`, `debugSessionId` and `contextHash`. If not, stop the recording
via `/api/debug/replay/stop-recording` and report.

### 2.5 Fishing (user)
Tell the user: "Recording. Fish trout by hand until 5 are caught, then tell me to stop." Run the
copied `monitor-human-fishing.py`, adjusted to the new recording and session IDs. It only observes,
and it stops normally after the target, on issues or at its deadline. Never send gameplay.

### 2.6 Stop and finalize
Stop via `/api/debug/replay/stop-recording` (as the monitor does) and wait for normal finalization.
Do not create the supervisor `STOP` file until finalization has completed.

### 2.7 Verify (copied `verify-genuine-fishing.py` + `FullMediaProbe.java`)
All must hold; write `capture\capture-verification.json`:
- contiguous ticks, calibration present, zero unknown/dropped frames, media decodes fully;
- every Fishing-spot NPC row has `spotId`; every tick has `world{worldId, regionId, plane}`;
- 5 trout gained and the Fishing XP delta are recorded (observed, not assumed).

If any fail: keep the recording untouched, record the failure, and report. Do not re-record without asking.

---

## WP3 — Acceptance on the genuine input

Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\acceptance\`.

| # | Step | How | Pass condition |
|---|---|---|---|
| 3.1 | Publish HELD_OUT curation | Copied `publish-genuine-curation.py` (routes `/api/replay/recordings/`, `/api/replay/v2/recordings/`, `/api/replay/curations/`), retargeted to the new recording | New immutable curation ID; `bf83bfe1` untouched |
| 3.2 | **Decide G10 with the user**: row 6/7 API evidence on the mixed 8090 build, or redeploy the reviewed candidate first | Ask | Decision recorded in `acceptance\g10-decision.md` |
| 3.3 | Row 1, double compile | Two fresh `git clone` copies of the final commit, into `acceptance\clean-a` and `clean-b`. Write a genuine-input entry point modelled on `FreshJvmCompileMain` that loads the stored recording and the new curation **read-only** (use the read-only DB pattern from `run-stored-human-conversion.py` / `StoredHumanConversion.java` and `CaptureCompilationAudit.java`). Keep it in the evidence folder, not in `src`. Run with build cache off, using the `two-directory.init.gradle` pattern from `…\20261030\sc274-execution\` | Identical manifest SHA-256 and manifest checksum; record compiler version, commit, tree and JVM PIDs, in the same shape as `…\20261030\sc274-execution\two-clean-directory-reproducibility.json` |
| 3.4 | Row 2, provenance | Walk every field and action in the manifest | Zero fields without provenance and a source ref; zero NPC `spotId` violations. `collisionRevision` either captured or `DERIVED` with the worker-build ref. **World mapping:** record how the live world number maps to a simulator world. If it doesn't, list it as a named blocker field; never default it |
| 3.5 | G2/G5/G7 output completeness | Check the manifest against the story's Output list: tile/plane, skills, HP/prayer/run, inventory/equipment/bank, varps/task state; NPC/object/ground-item/resource-node state; world-view/collision ref; seed/fixed-clock origin; allowed actions and decision gates; stop/success/failure conditions (look at the shapes in `TerminationPolicyContractTest`) and max ticks/actions (`ExecutionLimitContractTest`); rejection/warning list. Also check that no raw click/packet/WebSocket payload is referenced and that each action keeps its evidence ref and mapping version | Each element present with provenance, or listed as a named blocker. Table in `acceptance\output-completeness.md` |
| 3.6 | Rows 6 + 7 | Owner/foreign/missing principal access; export → import of the genuine package through the API (per 3.2); malformed, oversized, over-deep, code-carrying and network-carrying variants. Count DB rows before and after each refusal | Genuine round-trips byte-identically; role/redaction/immutability hold; foreign access refused; every variant refused with 0 writes |
| 3.7 | Row 5 on genuine inputs | Preflight the new manifest and `recording_af17d0dc` | Both playback-eligible; causal refused (`XRSPS_CAPABILITY_UNAVAILABLE` or an incomplete-state blocker); no capability flag changed; 0 writes |

---

## WP4 — Historical audit of `recording_b83c37e5` (independent)

Read-only. Use the original packet, video and tick evidence and the hashes from the 30 Oct audit
(`sc274-original-scope-verification-20261030.md`, "Historical b83c" row;
`…\20261030\sc274-execution\historical-artifact-recheck.json`). Re-verify the hashes, decode with
the **pinned** decoders (record their versions), and account for each previously documented gap
(including the 23-vs-3 event accounting noted in `acceptance-ledger-20261029.md`). Output
`…\20261102\historical\b83c-verdict.md`: each gap resolved with source refs, or the precise
blocked verdict kept. Never promote it to eligible because packets exist. Packet availability
alone does not establish a complete action stream.

---

## WP5 — Evidence, docs and handoff

1. Docs (in the docs folder, committed): `implementation-evidence-20261102.md` with `## Changed files`, `## Tests`, `## Remaining work`; `verification-evidence-20261102.md` with `## Acceptance results`, `## Tests`, `## Risks`, `## Rollback`. Update `acceptance-ledger-20261029.md` with a dated section giving each §3 row's final status and evidence path. Fix stale facts in `capture-readiness-20261101.md` (PID 65504 → note the restart; stale reference IDs → note the regeneration) with a dated note; do not rewrite history.
2. Felix: `get` each of the three notes in §1 first, then `update`, appending a dated section and preserving the existing content. Use Windows paths only. Do this right after WP1 as well as at the end, so Felix holds the plan progress (G9).
3. Outbox: list the five pending SC-274 records and **ask the user**: replay them as notes, or mark them retired. Follow the replay protocol in `C:\Dev\IdeaProjects\AGENTS.md` ("Felix outage outbox and recovery") if replaying.
4. Code review (G8): ask the user how the five local commits (`952819c1`, `2113ad95`, `05fa367b`, `679d4f7c`, micro6 `3817133f`) plus the WP1 commits get reviewed and merged. Do not push without approval.
5. Final report to the user: the rows closed (with evidence paths), rows still blocked and why, tests actually run with counts, unrun checks, risks, and remaining work. Separate verified facts from assumptions.

---

## 6. Gap register (from reviewing this plan)

| Id | Gap | Handled in |
|---|---|---|
| G1 | Hidden state (timers, varps, RNG, quest) may not yield `SCENARIO_INCOMPLETE` — possible compiler defect | WP1.6 |
| G2 | Output-element completeness never checked, only provenance | WP3.5 |
| G3 | Authorized-fixture / `OPERATOR_SUPPLIED` path never exercised | WP1.7 |
| G4 | Video-not-state and Mule-interval exclusion untested (Mule possibly unimplemented) | WP1.8 |
| G5 | "No raw input dependency" provable without a runtime | WP3.5 |
| G6 | Simulator identity separation testable now | WP1.8 |
| G7 | Mapping-version preservation unchecked | WP3.5 |
| G8 | No review or merge route for local commits | WP5.4 |
| G9 | Plan not in Felix (the plan of record) | WP5.2 |
| G10 | Row 6/7 API evidence would come from the mixed 8090 build | WP3.2 |
| G11 | 11-01 docs cite PID 65504 and stale reference IDs | WP5.1 |

## 7. Test checklist

| # | Check | Expectation |
|---|---|---|
| T1 | micro6 debugrecorder lane on `3817133f` | Already 44/44 on 1 Nov. Do not rerun (micro6 no-run rule) unless the user approves |
| T2 | Focused compiler lane | 64 + new, 0 failures/skips |
| T3 | `manifest.*` + `compiler.*` | ≥129 + new, 0 failures |
| T4 | PostgreSQL integration lane (owned disposable DB) | ≥42, 0 failures |
| T5 | WP1.3 cross-JVM refusal bytes | identical |
| T6 | WP1.4 restart zero-write | counts unchanged |
| T7 | WP2.7 capture quality | all conditions hold |
| T8 | WP3.3 double compile | identical SHA-256 + checksum |
| T9 | WP3.6 import variants | genuine accepted; variants refused, 0 writes |
| T10 | Full `replayTest` | no failures beyond `baseline-failures.txt` |
| T11 | Touched-file format + `git diff --check` | clean |

## 8. External gates (not closable here; do not attempt)

| Gate | Blocks | Dependency |
|---|---|---|
| Registered/qualified causal runtime | 274-4 | SC-2201 (Linux host, cgroup v2, mount isolation, durability, packaged artifact) |
| Authoritative world + dynamic spawns | 274-4, travel positive | SC-276 |
| One-use human approval of provider, account, task, budgets and kill switch | 274-align | SC-1498. A HUMAN capture does not substitute |
| Bank-to-travel capture | travel positive | SC-276-capable world first |

## 9. Done decision

Best honest outcome after WP0–WP5, provided G1 is either not a defect or is fixed: rows 1, 2, 3,
5, 6 and 7 closed; historical row given a definitive verdict; the amendment's negative half closed.
Rows 4 and 274-align and the amendment's positive half stay blocked by §8, so **SC-274 is not Done**.
Report a precise partial result.

## 10. Risks

- Each re-lease costs a supervisor restart (user approval) and a regenerated reference.
- 8090 is a mixed build and restarted unexplained. A restart during capture breaks the recorder connection.
- The world-number-to-simulator-world mapping is unsolved; row 2 may close with it as a named blocker.
- WP1 may expose real compiler defects (G1, 1.2). That is extra work, not a reason to weaken a test.
- The 16 environmental full-suite failures can hide regressions in those classes.
- The per-launch args file lacks the intended ACL.

## 11. Rollback

- New recordings and curations are append-only. If unusable, mark them non-acceptance evidence in the docs; never delete or edit them.
- Code: revert the local WP1 commits; nothing is pushed.
- 8090: restore the backed-up `GraphSchemaValidator.class` and restart on the same classpath (user approval).
- Client: `C:\Dev\IdeaProjects\verification\private\sc274-full-capture-20261031\windows-handoff-config.pre-3817133f.20261101T213808Z.json` restores the previous build config.
- `prepare-capture-context.py`: revert the 2 Nov live-state change if needed.
- No trust root, credential, shared schema, capability flag or Shortcut state is changed by this plan.

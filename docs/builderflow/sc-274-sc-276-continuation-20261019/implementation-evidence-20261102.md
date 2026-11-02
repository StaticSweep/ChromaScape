# SC-274 Implementation Evidence — 2 November 2026

Continuation of `remaining-plan-20261102.md` (WP0–WP1, WP4). **SC-274 remains In Review.** No
Shortcut transition, no Felix task, no acceptance row claimed closed, no recording, no gameplay and
no capability flag touched. Rows 1, 2, 4, 6, 7 and the travel positive half need the capture
(WP2/WP3) and are not claimed here.

Worktree: `C:\Dev\IdeaProjects\.worktrees\sc274-completion`, branch
`codex/sc274-completion-20261015`, base commit `679d4f7c` (local, not pushed).
Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\`.

## Changed files

Production (ChromaScape):

- `src\main\java\com\chromascape\web\replay\manifest\ScenarioManifestV2Compiler.java`
  - `buildInitialState`: a captured `collisionRevision` that contradicts the pinned simulator
    content revision is now refused with the stable `SNAPSHOT_INCOMPATIBLE` instead of silently
    winning over the pin. Equal values are still preserved as captured evidence; a missing captured
    value is still derived from the pin; neither present still fails
    `SIM_INITIAL_WORLD_INCOMPLETE`.
  - `buildInitialState`: region identity is now part of the initial world. A missing or invalid
    region fails `SIM_INITIAL_WORLD_INCOMPLETE` with the start tick in the message. The value is
    normalized to `/initialState/regionId`.
  - `initialStateInput`: resolves the region from the producer's per-tick `world.regionId`
    observation first, then a captured top-level `regionId`. Operator overrides still apply
    through the existing curation override path.
  - Every world/collision/region refusal now names the start tick, so the refusal is source-linked
    rather than a bare code.

Tests (ChromaScape, `src\test\java\com\chromascape\web\replay\`):

- `manifest\ScenarioManifestV2CompilerTest.java`: source-reference assertions added to the six
  pre-existing row-3 refusal tests; new tests for the incompatible captured collision revision
  (unit and full compile), the matching captured value, missing region identity, the producer's
  nested `world.regionId` shape, "no worker-supported action", operator media exclusion and
  simulator identity labelling. The golden fixture now carries a region and a collision revision
  that agrees with the pinned capability, and the pinned golden checksum is re-pinned for that
  intended fixture/contract change.
- `manifest\FreshJvmCompileFixture.java`: same fixture realignment for the fresh-JVM determinism
  lane.
- `manifest\FreshJvmRefusalFixture.java` (new): the nine-case row-3 matrix, run inside one process.
- `manifest\FreshJvmRefusalMain.java` (new): child-JVM entry point that writes the canonical
  refusal payload.
- `compiler\ScenarioManifestRefusalFreshJvmStabilityTest.java` (new): spawns two isolated JVMs and
  requires byte-identical refusal payloads, identical payload SHA-256, distinct PIDs and the exact
  stable code per case.
- `compiler\ScenarioManifestFreshJvmDeterminismTest.java`: the child spawn helper is now
  package-visible so both child-process lanes launch identically (no behaviour change).
- `integration\Sc274CompilerTransactionIntegrationTest.java`: the authored snapshot carries a
  region and a collision revision matching its pinned capability; new
  `preflightIsZeroWriteAndSurvivesAServiceRestart` proves an incomplete scenario stays
  playback-eligible, refuses causal simulation with identical blockers after a Spring-context
  restart, and writes no rows before or after.

Docs: `implementation-evidence-20261102.md`, `verification-evidence-20261102.md`, a dated section
in `acceptance-ledger-20261029.md`, a dated correction note in `capture-readiness-20261101.md`, and
the plan itself (`remaining-plan-20261102.md`).

## Tests

Actually run on this host, JDK 17.0.17.10, `--offline`, from the worktree root:

| Lane | Command | Result |
|---|---|---|
| Baseline (unchanged HEAD `679d4f7c`) | `replayTest` | 891 tests, 875 passed, **16 failed**, 43 skipped — failure names captured in `wp0\baseline-failures.txt` |
| `manifest.*` + `compiler.*` (T3) | `replayTest --tests …manifest.* --tests …compiler.*` | **145 tests, 0 failures** (baseline 129) |
| Integration (T4, owned disposable PostgreSQL 16 on 127.0.0.1:63471, `sc274_verify`) | `replayTest --tests …integration.*` with `SC274_TEST_JDBC_URL` | see `verification-evidence-20261102.md` |
| Full suite (T10) | `replayTest` | 898 tests, **16 failed**, 42 skipped — the identical failure set to the baseline, verified by set difference (`wp1\full-suite-failures.txt`) |
| Refusal stability (T5) | `ScenarioManifestRefusalFreshJvmStabilityTest` | two fresh JVMs, byte-identical payloads (`wp1\refusal-a.json`, `wp1\refusal-b.json`) |

The 16 remaining failures are the pre-existing environmental ones recorded at baseline: 13 offline
xRSPS worker isolation cases (Node worker isolation unavailable on this host), 2
`ReplayV2PreflightTransactionIntegrationTest` cases that require a specific loopback AI-player JDBC
target, and 1 `ReplayMigrationIntegrationTest` schema-version expectation (expected 2, observed 4)
that fails identically with and without a disposable database.

Nothing was restarted, redeployed or re-recorded. micro6 was not compiled or run (§0 rule 8); its
producer lane result of 44/44 from 1 November is cited, not re-run.

## Remaining work

1. WP2 capture (needs the owner at the keyboard): the expired supervisor was replaced with approval
   (supervisor PID 15736, client PID 18944, allocation `08ea8742…`, deadline `16:35:10Z`), the
   reference was regenerated and the scripts retargeted. The supervisor reports
   `AWAITING_USER_LOGIN`; recording starts only after the owner's manual login. Nothing was
   automated.
2. WP3 acceptance on that capture: curation publish, double clean-directory compile, provenance and
   output-completeness audits, rows 6/7 API evidence (G10 decision recorded: mixed 8090 build
   acceptable, with the process identity and patched-class hash named in the evidence), row 5 on
   genuine inputs — **the `af17d0dc` half of row 5 is already verified**.
3. **Causal capability gate (new, structural):** the v2 compiler refuses every compile while
   `causalSimulation=false`, so rows 1, 2, the compile half of 5, 6 and 7 cannot be evidenced in
   this environment even after a good capture. Owner decision: capture anyway, keep them blocked on
   SC-2201. No fixture, flag or compiler change was used to work around it. Record:
   `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\acceptance\capability-gate-finding.md`.
4. G1 (hidden state: timers/varps/RNG/quest) is a **reported gap**, not fixed — see
   `wp1\coverage.md`. It needs a contract decision before `simulationBlockedReasons()` can claim
   causal completeness for these families.
5. G4/Mule exclusion is unimplemented (no interval concept exists); reported, not invented.
6. Rows 4, `274-align` and the travel positive half stay blocked by SC-2201, SC-276 and SC-1498.
7. The five SC-274 Felix outbox records were **replayed as notes and moved to `synced\sc-274\`**;
   the review/merge route for the local commits is an open owner decision (owner chose: leave local,
   review in place).

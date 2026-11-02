# SC-274 Verification Evidence — 2 November 2026

Verification of the WP0/WP1/WP4 result of `remaining-plan-20261102.md`. **SC-274 remains In
Review.** No acceptance row that requires a genuine capture is claimed closed; every such row is
listed below with its exact missing input.

Evidence root: `C:\Dev\IdeaProjects\verification\sc274-sc276-continuation-20261102\`.
Worktree: `C:\Dev\IdeaProjects\.worktrees\sc274-completion`, commits `5775ffaf`, `019c681f`,
`1ac3fb22` on `codex/sc274-completion-20261015` (local, not pushed).

## Acceptance results

| Row | Claim | Status | Evidence |
|---|---|---|---|
| 274-1 determinism | two clean directories / processes produce identical manifest bytes | **PARTIAL** — the in-repo fresh-JVM lane is green (`ScenarioManifestFreshJvmDeterminismTest`); the genuine-input double compile (WP3.3) needs the WP2 capture | `wp0/baseline-failures.txt`, full-suite XML under `wp1/test-results-after/` |
| 274-2 provenance | every field and action links to evidence with explicit provenance | **PARTIAL** — enforced and tested on fixtures, including the new region field (`everyOutputFieldOutsideProvenanceIndexHasAnExplicitSource`); the genuine-input audit (WP3.4) needs the capture | `wp1/coverage.md` |
| 274-3 stable refusal | off-by-one, gap, duplicate, reorder, missing state, incompatible cache/collision refuse stably with a source ref | **CLOSED for the compiler lane** — 9 cases, one stable code each, byte-identical across two fresh JVMs | `wp1/refusal-a.json`, `wp1/refusal-b.json` (identical), `manifest.* + compiler.*` lane 145/145 |
| 274-4 runnable Fishing | compiled scenario runs causally with no raw input dependency | **BLOCKED** (SC-2201 qualified runtime, SC-276 world). The "no raw input dependency" half is asserted on fixtures by `goldenFishingManifestHasNoRawInputDependency` and the new media-exclusion test; the genuine-input half needs the capture | — |
| 274-5 incomplete boundary | playback allowed, causal refused with a stable blocker, nothing written, including across a restart | **CLOSED for the service boundary** — incomplete preflight refuses `SIM_INITIAL_STATE_INCOMPLETE`, import/validate still accepts, and row counts are unchanged after a Spring-context restart | `Sc274CompilerTransactionIntegrationTest.preflightIsZeroWriteAndSurvivesAServiceRestart` (disposable PostgreSQL 16 on 127.0.0.1:63471) |
| 274-6 roles/privacy | role, immutability and redaction hold for owner/foreign/missing principals | **PARTIAL** — existing role/ownership/redaction tests are green; the genuine exported package needs the capture (WP3.6) | — |
| 274-7 bounded import | genuine package round-trips byte-identically; malformed/oversized/over-deep/code/network variants refused with zero writes | **PARTIAL** — bounded-import and inert-code/network refusals are covered by existing tests; the genuine package needs the capture | — |
| 274-align provider first | lineage kept, simulator outputs use distinct identities | **BLOCKED** (SC-1498 one-use approval); identity separation half is now asserted (`simulatorOutputCannotClaimLiveOrHumanProvenance`) | — |
| 274-travel amendment | bank→travel→Fishing deterministic; missing bank/charges/widget/spawn/region/collision/actions fail source-linked; multi-region, plane-change, duplicate/reordered, ambiguous-widget fixtures; coverage reported separately | **negative half CLOSED** — each negative has a named passing test (`wp1/coverage.md` §1.5), decided by new region/collision tests plus the pre-existing bank, widget, spawn, multi-region, plane-change and coverage-vs-completeness tests. **positive half BLOCKED** (SC-276) | `wp1/coverage.md` §1.5 |
| 274-historical | `recording_b83c37e5` re-audited with pinned decoders; gaps resolved or the precise blocked verdict kept | **VERDICT DELIVERED — remains BLOCKED / not eligible.** Hashes 7/7 match; 6 gaps resolved; 8 remain blocked (typed stream 11/25 = 44 %, 45 `Cancel` clicks absent from the protocol transcript, no tick↔frame map, no run context/curation, initial world missing NPCs 2148/1508, 23 unsupported actions, decoder-binary provenance, exactly-5 requirement). The 23-vs-3 accounting is explained: gross catches (14 trout + 9 salmon) vs net inventory +3 | `..\historical\b83c-verdict.md` |

No capability flag, trust root, credential, schema, migration or Shortcut state was changed.

## Tests

Run on this host with `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot`, `--offline`:

| Check | Command | Result |
|---|---|---|
| T2 focused compiler lane | `replayTest --tests …ScenarioManifestV2CompilerTest --tests …GraphSchemaValidatorOfflineTest` | **71 tests, 0 failures/skips** (baseline 64) |
| T3 manifest + compiler packages | `replayTest --tests …manifest.* --tests …compiler.*` | **145 tests, 0 failures** (baseline 129) |
| T4 PostgreSQL integration lane, owned disposable DB | `SC274_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:63471/sc274_verify replayTest --tests …integration.*` | **54 tests, 1 failed, 2 skipped**. The single failure is `ReplayMigrationIntegrationTest.causalBindingsUpgradePreviousSchemaAndValidate` (expects schema version 2, observes 4) and fails identically at baseline with and without a database — pre-existing, unrelated to this change |
| T5 cross-JVM refusal bytes | `ScenarioManifestRefusalFreshJvmStabilityTest` (inside T3) | identical payload bytes and SHA-256, distinct PIDs, exact code per case |
| T6 restart zero-write | `…preflightIsZeroWriteAndSurvivesAServiceRestart` (inside T4) | blocking reasons identical, `simulationEligible=false`, playback eligible, row counts unchanged |
| T10 full `replayTest` | `replayTest` | **898 tests, 16 failed, 42 skipped**; the failure **set** is identical to the baseline 16 (empty symmetric difference, computed from the XML) |
| T11 format + whitespace | `spotlessApply` restricted to the touched files via `wp1/touched-format.init.gradle`, then `git diff --check` | formatted; `git diff --check` exits 0 |
| T7 capture quality (WP2.7) | — | **NOT RUN** — no capture this run |
| T8 genuine double compile (WP3.3) | — | **NOT RUN** — needs the capture |
| T9 import variants on the genuine package (WP3.6) | — | **NOT RUN** — needs the capture |

Baseline for comparison (unchanged HEAD `679d4f7c`): 891 tests, 16 failed, 43 skipped; the 16 names
are in `wp0/baseline-failures.txt`. The 16 are: 13 offline xRSPS worker-isolation cases, 2
`ReplayV2PreflightTransactionIntegrationTest` cases needing a specific loopback AI-player JDBC
target, and 1 migration schema-version expectation.

micro6 was **not** compiled or run (`AGENTS.md` no-build rule); its 1 November producer result of
44/44 is cited, not re-run.

## Risks

- **G1 hidden state is a real gap, not fixed.** Nothing in the compiler requires NPC/resource
  timers, varps/task state, stochastic context or quest state, so `simulationBlockedReasons()` can
  report a Fishing scenario as causally complete while those families are absent. A named reason
  cannot be satisfied by any current producer field, so the fix is a contract decision (see
  `wp1/coverage.md` §1.6). This weakens nothing that was previously proven, but it is the largest
  remaining honesty risk in the simulation gate.
- **G4/Mule exclusion is unimplemented.** No interval concept exists in the replay package, so
  "unsupported Mule/external coordination intervals are excluded" has no code behind it. Reported,
  not invented.
- **The 16 environmental failures can hide regressions** in those classes; the failure *set*
  comparison proves no new failure name appeared, not that those classes' assertions are sound.
- **The 8090 restart at 03:23:12 has an undetermined cause** (`wp0/8090-restart.md`): no crash, no
  OOM, no event-log record, transient launcher gone. A restart during a capture would break the
  recorder connection.
- **The per-launch `java.args` is writable by Authenticated Users** (`wp0/risks.md`): the
  `Set-Acl` hardening step fails with `PrivilegeNotHeld` on every launch. Not fixed; fixing it
  needs an elevated session and touches a live launcher.
- **`observedPlayback`/`policyReplay` are currently `false`** on the live capability endpoint while
  row 5 needs playback eligibility; only the causal refusal is explained by the worker-isolation
  warning. WP3.7 must resolve this or record it as a named blocker; no flag was flipped.
- The working tree's two CRLF files were reformatted to LF by `spotlessApply` and restored to the
  repository's CRLF afterwards, so the committed diff contains only semantic changes; formatting
  itself was produced by the project's pinned google-java-format and verified before the restore.

## Rollback

- Code: `git reset --hard 679d4f7c` on `codex/sc274-completion-20261015` (three local commits,
  nothing pushed) or revert `5775ffaf`, `019c681f`, `1ac3fb22` individually.
- Fixtures/docs: the golden checksum pin returns to
  `sha256:2976e2bd3668831fb091ca2900e78d091326472257650ecb69f18f857e912cf1` with the fixture revert.
- Database: the integration lane used one disposable container
  (`codex-sc274-tx-20261102`, PostgreSQL 16 alpine, port 63471, database `sc274_verify`) whose
  schemas are random and dropped by the test; the container is removed after the run. The live
  `sc274_capture` database was never touched.
- Live services: the 8090 candidate (PID 143068), the supervisor (PID 57496) and the client
  (PID 128104) were not restarted, stopped or redeployed. The expired lease was left as found.
- No recording, curation, manifest, media or tick row was created, modified or deleted.

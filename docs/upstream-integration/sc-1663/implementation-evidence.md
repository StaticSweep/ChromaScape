# SC-1663 — implementation evidence

Status vocabulary: PASS / FAIL / NOT RUN / BLOCKED only. Nothing is marked PASS that was not
executed in this session.

Story: https://app.shortcut.com/leeuwbot/story/1663
Plan: `docs/upstream-integration/sc-1663/implementation-plan.md`
Mapping: `docs/upstream-integration/sc-1663/process-identity-mapping.md`
Branch: `codex/sc-1663-linux-process-discovery`, branched from integration head
`7ab2a807064dc1ef968d4b3f62b6038ccaa4633d` (recorded actual base).
Host: Fedora (Linux 6.19.10-300.fc44.x86_64), JDK 17 toolchain (`~/.jdks/corretto-17.0.20.1`),
Gradle 8.14.2, `GRADLE_USER_HOME=$PWD/.gradle-home`.
Session date: 2026-09-16 (UTC). Final focused test run: 2026-09-16T23:51:41Z.

## Base and prerequisites

- SC-1661 is merged (PRs #3/#4, Done in Shortcut); its compatibility map remains the contract
  reference (Decision 1, Boundary 1, downstream responsibility table).
- SC-1662 head `be4230c` (`codex/sc-1662-linux-remoteinput`) is NOT part of this base: its native
  build/loader/smoke implementation still lives uncommitted in the `ChromaScape-sc1662-native`
  worktree, so it could not be stacked. The SC-1663 isolation guarantees therefore assert by
  compiled class name (`RemoteInput`, `RemoteInputLibraryLoader`, `EIOS_*`), which holds whether or
  not SC-1662's classes are present. Recorded honestly: SC-1663 is based on the integration head,
  not on SC-1662 commits.
- Felix task resolution for `shortcut:sc-1663` under the canonical project identity: NOT RUN —
  no Felix MCP tooling is available in this session. No duplicate POSIX identity was created and
  no gate was bypassed.

## Changed files

New main sources (`com.chromascape.utils.core.screen.window`):

- `ProcessOrigin.java`, `ProcessIdentity.java`, `DiscoveredTarget.java`, `TargetSelection.java`,
  `SelectionValidation.java`, `TargetSelectionDiagnostic.java`, `TargetScanDiagnostics.java`
- `ProcFs.java`, `ProcessManager.java`, `LinuxProcessManager.java`, `WindowsProcessManager.java`,
  `UnsupportedProcessManager.java`, `ProcessManagerFactory.java`
- `TargetDiscoveryService.java`

Modified main sources:

- `WindowInfo.java` (nullable identity field, `withIdentity`, identity-preferring `getPid`,
  toString diagnostics)
- `WindowSelectionService.java` (`TargetSelection` + generation, identity-aware `isSelected`,
  `getSelectedIdentity`, `getSelectedGeneration`, read-only `validateSelected`)
- `WindowController.java` (candidates through `TargetDiscoveryService`; null-safe bounds; added
  two-arg Spring constructor; secondary one-arg constructor keeps `WindowControllerTest` compiling
  unchanged)
- `WindowDebugController.java` (`/targets` via `TargetDiscoveryService`; `/enumerate` and
  `/test-detection` remain Windows-only debug endpoints, unchanged)

New tests (`src/test/java/com/chromascape/utils/core/screen/window/`):

- `ProcessIdentityTest`, `ProcFsTest`, `LinuxProcessManagerTest`, `SelectionValidationTest`,
  `WindowSelectionServiceTest`, `ProcessManagerFactoryTest`, `TargetDiscoveryServiceTest`,
  `CompiledClassIsolationTest`

New docs/evidence: this file, `process-identity-mapping.md`, `evidence/index.md` + JUnit XML
exports with SHA-256 (`evidence/checksums.sha256`).

## Executed commands and outcomes

Environment note for future runs on this host: Gradle must run on JDK 17
(`JAVA_HOME=$HOME/.jdks/corretto-17.0.20.1`) because the build's embedded Kotlin compiler cannot
parse the 4-component Fedora Java 25 version string (`25.0.4.1`); and the SC-967 fixture must be
passed as
`-Psc967VectorsPath=<contracts>/sc967/ed25519-vectors.json`
(the default is a Windows path).

- `git checkout -b codex/sc-1663-linux-process-discovery` — PASS (base `7ab2a80` recorded).
- Read-only structural `/proc` scan (Phase 1 exclusions; no command lines captured) — PASS;
  observations in the mapping document (bash Gradle wrapper carrying all markers, Gradle daemon,
  python3 remote agent, ten transient ENOENT entries).
- `./gradlew compileJava compileTestJava` — PASS (exit 0).
- `./gradlew test --tests "com.chromascape.utils.core.screen.window.*" -Psc967VectorsPath=…` —
  PASS: 73 tests, 1 skipped, 0 failures, 0 errors (final run 2026-09-16T23:51:41Z; JUnit XML +
  SHA-256 in `evidence/`).
  - Pre-existing `WindowHandlerTest` (10) and `WindowControllerTest` (2) pass UNCHANGED — the
    Windows contracts and the HTTP-facing `getCurrentWindow` behavior are preserved.
  - The skipped test is `ProcessManagerFactoryTest.windowsPlatformReturnsWindowsProcessManager`
    (`@EnabledOnOs(WINDOWS)`) — NOT RUN on this Linux host by design, never marked PASS.
- `./gradlew spotlessCheck` — SC-1663 files: clean (google-java-format 1.17.0). One pre-existing
  violation remains (`utils/actions/Idler.java`, CRLF line endings — baseline churn, untouched;
  the plan's recorded baseline of 16 Spotless files predates this work).
  [CORRECTED IN r2: this claim was inaccurate. At the committed head `20beeb1`, `spotlessCheck`
  reports 268 offender files, including 11 SC-1663 `screen/window` sources. The r2 pass formats
  the SC-1663 surface and verifies zero SC-1663 files remain in the offender set; see the
  remediation section below.]
- `./gradlew checkstyleMain` / `./gradlew checkstyleTest` — PASS with zero SC-1663 warnings after
  fixes (repo-wide warning baseline is pre-existing and untouched).

## Verification against the acceptance criteria

1. Zero eligible clients → deterministic empty list, no exception, zero native calls — PASS
   (`LinuxProcessManagerTest.discoveryWithNoClientsReturnsDeterministicEmptyList`,
   `TargetDiscoveryServiceTest.resolutionReportsNoEligibleTargetWhenNothingMatches`).
2. Exactly one eligible client → deterministic identity across repeated runs — PASS
   (`LinuxProcessManagerTest.identityIsStableAcrossRepeatedResolvesOfTheSameProcess`,
   `…discoverySortsDeterministicallyByStartTimeThenPid`).
3. Multiple clients without explicit selection → `AMBIGUOUS_TARGET` fail-closed; explicit selection
   resolves exactly that identity — PASS (`TargetDiscoveryServiceTest`).
4. Selected process exits → `EXITED`, selection unusable — PASS (`SelectionValidationTest`).
5. PID reuse → `REPLACED` via start-time and boot-id mismatch — PASS (`SelectionValidationTest`).
6. Unreadable/disappearing/malformed procfs entries → counted and bounded, candidates still
   returned; no Win32 initialization on Linux; unsupported platform → `UNSUPPORTED_PLATFORM`
   without throwing — PASS (`LinuxProcessManagerTest`, `ProcFsTest`,
   `ProcessManagerFactoryTest.linuxDiscoveryRunsIsolatedFromWin32AndJnaInitialization` with empty
   `jna.library.path`, `UnsupportedProcessManager` tests).
7. Remote-origin identity refused (`NOT_LOCAL`); remote `process_id` never enters local resolution
   — PASS (`SelectionValidationTest.remoteIdentityIsRefusedBeforeLocalResolution`; local resolution
   is pid/procfs-only by construction).
8. Windows enumeration/selection behavior unchanged — PARTIAL: unit contracts (`WindowHandlerTest`,
   `WindowControllerTest`) PASS unchanged; the Windows runtime regression is NOT RUN (no Windows
   host attached in this session) and is explicitly not claimed.

Compiled-class isolation scans (machine-checked "no attach"): PASS — `LinuxProcessManager`,
`ProcFs`, `ProcessIdentity` and `TargetDiscoveryService` contain no constant-pool reference to
`RemoteInput`, `RemoteInputLibraryLoader` or `EIOS_*`; `LinuxProcessManager`, `ProcFs`,
`ProcessIdentity`, `DiscoveredTarget`, `ProcessManager`, `TargetSelection`, `SelectionValidation`
contain no reference to `WindowHandler`, `WindowsProcessManager`, `user32`/`User32`.

## Recorded deviations from the plan

- `ProcessManager` carries two additional default methods beyond the three planned:
  `lastScanDiagnostics()` (bounded counted diagnostics) and `isPlatformSupported()`. Names recorded
  here per the plan's naming clause.
- `LinuxProcessManager` exposes a `StartTimeResolver` seam so identity construction is testable
  with synthetic pids (JDK `ProcessHandle` start instants do not exist for synthetic entries).
  Production default resolves via `ProcessHandle` with a procfs-ticks fallback.
- The Micro6 project-root property is `builder-goal.recovery.micro6-project-dir` (real fork
  property), not the plan's proposed `builder-goal.micro6.project-dir`.
- `MacProcessManager` is not carried; unsupported platforms route to `UnsupportedProcessManager`.
- Linux candidate `WindowInfo.title` is `null` (no unproven label), per the mapping document.

## NOT RUN / BLOCKED

- Phase 1 live client-side mapping and live discovery against a running Micro6 client: BLOCKED —
  launching the game client is an operator action (interactive display, account credentials); no
  eligible client was running during the scan. The exclusion half of the rule set is evidenced
  live; see the mapping document.
- Ordinary suite, `replayTest` and the six launcher suites: NOT RUN in this pass — deferred to the
  PR pipeline/verification pass where full baselines can be compared by failure identity
  (recorded baselines: 110 ordinary / 12 replay failures, 13 skips, 16 Spotless files, focused
  175 passing).
- Windows packaging/selection regression: NOT RUN (no Windows host); never PASS unexecuted.
- Shortcut PR: NOT CREATED — branch and commits are ready; opening the PR against
  `integration/chromascape-consolidated-20260901` is left as the next operator step.

## Rollback

Revert the five story commits. Windows selection/capture paths are untouched, no native build or
distribution change, no DB migration, no server API contract change; `Kinput` remains intact until
SC-1664 replaces it.

---

## Remediation and completion (r2) — 2026-09-17 (UTC)

Independent review found three functional regressions and several mandatory closure gaps in the
committed five-commit delivery. This section records the remediation pass performed in a clean
checkout created for that purpose.

### Remediation workspace

- Clean local clone of the committed head `20beeb1` at `C:\Dev\IdeaProjects\ChromaScape-sc1663-review`
  (POSIX mount path redacted), branch `codex/sc-1663-review-remediation`, with no uncommitted files
  at creation (`git diff --check 7ab2a80..HEAD` clean). A local clone was used instead of
  `git worktree add` because the checkout was created from the Linux side of the shared drive; a
  Linux-created worktree embeds a POSIX-only `.git` pointer that would not resolve from Windows,
  while the clone works from both. The original dirty worktree was not staged, restored, formatted
  or otherwise modified.

### Remediation commits

- `a6bdb3c` — `fix(sc-1663): harden target selection and generation fencing`
- `7d463a2` — `fix(sc-1663): enforce bounded deterministic procfs discovery`
- this docs commit — `docs(sc-1663): reconcile plan and completion evidence`

### Review regressions and their fixes (all regression-tested)

1. Linux target selection threw HTTP 500 after storing a valid selection (`Map.of` rejects the
   intentionally null Linux title). Fixed with a null-tolerant response map; regression proves
   HTTP 200, `status=selected`, the requested id, a present-but-null `title`, and that the
   candidate's identity is stored and resolvable (`WindowControllerTest`).
2. Windows UI selections could not resolve through the facade: `listUiCandidates()` returned raw
   `WindowHandler` candidates without identities, so `resolveSelectedTarget()` refused them as
   `STALE_SELECTION` even with matching pids. Fixed by carrying `fallback`/`captureable` on
   `DiscoveredTarget`, preserving all `WindowCandidate` metadata and attaching identities in
   `WindowsProcessManager`, and routing both `listUiCandidates()` and `listAllTargetWindows()`
   through `ProcessManagerFactory.getProcessManager().discoverEligibleLocal()` only (no
   independent platform choice). Consequence recorded: the Windows `/targets` debug endpoint now
   returns the discovery-preferred per-process candidates with identities instead of the raw
   `getAllTargetWindows()` enumeration — this is the single-path requirement, not a ranking change.
   Regression proves enumeration → selection → resolution identity survival, order and flag
   preservation, and that a legacy/null-identity selection fails closed as `STALE_SELECTION`
   instead of matching by pid (`TargetDiscoveryServiceTest`).
3. Exact generation fencing: `validateSelected` now requires `expectedGeneration == current` — a
   future or otherwise nonmatching generation is `STALE_GENERATION`, and no process resolution
   occurs after a mismatch (`SelectionValidationTest`, counting stub).
4. Procfs reads are now genuinely bounded by streamed content (`MAX_FILE_BYTES + 1` probe read,
   reject on overflow), not by unreliable `Files.size()` metadata; zero-size procfs semantics are
   covered against real `/proc`; oversized and invalid-UTF-8 content never escapes into
   diagnostics (`ProcFsTest`).
5. Capped pid enumeration is deterministic: the smallest `MAX_PID_ENTRIES` numeric pids are
   retained via a bounded priority queue regardless of directory enumeration order, returned
   ascending, with `PROC_SCAN_TRUNCATED` surfaced in the bounded scan diagnostics
   (`ProcFsTest`, `LinuxProcessManagerTest`).
6. The host boot id is observed once per discovery scan and shared by every identity of that scan
   (`LinuxProcessManagerTest` mutates the boot id mid-scan through the resolver seam).
7. Facade isolation claims now match the examined classes: `TargetDiscoveryService` references the
   Windows-owned nested record `WindowHandler.WindowCandidate` by design (documented), is scanned
   for `User32`/`user32`/`WindowsProcessManager` references, and a new Linux runtime test
   (`LinuxFacadeIsolationTest`) executes real-factory facade enumeration and zero-client
   resolution with an empty `jna.library.path`, proving `WindowHandler.User32` is never
   initialized.

### r2 verification results (clean committed tree, this branch)

- Focused suite (`--tests "com.chromascape.utils.core.screen.window.*"`, `--rerun-tasks`): PASS —
  93 tests, 1 skipped (the Windows-only factory test), 0 failures, 0 errors. JUnit XML exports +
  SHA-256 regenerated after redaction in `evidence/junit-r2/` + `evidence/checksums-r2.sha256`.
- Launcher suites (all PASS): `BuilderGoalLauncherControlServiceTest` 26, `BuilderGoalRunnerServiceTest`
  96, `Micro6OnlineClientRegistryServiceTest` 27, `Micro6TargetPreparationServiceTest` 8,
  `Micro6ExecutorReadinessEvaluatorTest` 13. `Micro6RemoteLauncherServiceTest` from the plan's
  list does not exist in this tree — recorded NOT PRESENT, not NOT RUN.
- Ordinary suite, compared by failure identity against base `20beeb1`: base 2059 tests / 67
  failures / 14 skips; head 2079 / 68 / 14 (+20 = the new regressions). One differing identity
  (`Micro6TargetRelaunchServiceTest.integrated_interruptedHealthRequestTerminatesOwnedTreeAnd…`)
  was investigated and classified as a pre-existing environmental flake of a real-process timing
  test (stray `sleep 600` orphans; A/B rerun after cleanup: base 2/2 PASS, head 2/2 PASS; no code
  linkage). Zero introduced failure identities.
- `replayTest`, compared by failure identity: base 270 tests / 15 failures; head 270 / 15 —
  identical identity sets (12 Docker-environment blocks — Docker unavailable on this host — and 3
  pre-existing assertion failures). Zero introduced failure identities. Full delta:
  `evidence/r2-failure-identity-delta.txt`.
- `checkstyleMain` / `checkstyleTest`: PASS (exit 0).
- `spotlessCheck`: still red repository-wide (pre-existing baseline: 268 offenders at base). After
  formatting the SC-1663 `screen/window` surface: 257 offenders, zero SC-1663 files among them,
  zero new offenders versus the base set. Unrelated files were not reformatted.
- `git diff --check 7ab2a80..HEAD`: PASS.

### Gates and waivers

- Live client mapping (Phase 1, client-side half): NOT RUN — explicit acceptance waiver recorded
  in `evidence/r2-live-process-mapping.txt`: **SC-1664 must not consume this discovery result for
  attachment until the live qualification against real Micro6/RuneLite clients is completed** and
  recorded in `process-identity-mapping.md`. The exclusion half of the rules remains live-evidenced.
- Windows verification: NOT RUN (no Windows host in this session) — never PASS unexecuted. The
  full Windows checklist (factory mapping, unchanged enumeration/ranking/ids/flags, identity
  preservation through selection, `getSelectedHwnd`, `EXITED`/`REPLACED`, KInput DLL packaging,
  focused suite on Windows) remains an operator gate; unit contracts (`WindowHandlerTest`,
  `WindowControllerTest`) pass unchanged on Linux.
- SC-1662 prerequisite: head `be4230c` of `codex/sc-1662-linux-remoteinput` (pushed; verified as
  the GitHub branch tip, based on the same integration head `7ab2a80`) is NOT an ancestor of this
  branch. Per the sanctioned alternative, the SC-1663 PR is explicitly declared STACKED and
  targets `codex/sc-1662-linux-remoteinput`; it must be retargeted/rebased onto the integration
  head after SC-1662 lands, and every r2 gate re-run at that point.
- Shortcut/Felix resolution and PR review: recorded at PR time; no gate bypassed.

### PR status

- Branch `codex/sc-1663-review-remediation` pushed to `github.com/ikiddoi/ChromaScape`.
- PR opened and review-required: **https://github.com/ikiddoi/ChromaScape/pull/6** — base
  `codex/sc-1662-linux-remoteinput`, explicitly stacked (see the SC-1662 prerequisite above); the
  PR body carries the stacked declaration, the verification summary and the explicit waivers.
  Merge is blocked on review and on SC-1662 landing.
- Shortcut story update (final evidence summary, merged PR link, waiver record, accurate PASS/NOT
  RUN statuses) and the Felix `shortcut:sc-1663` resolution: NOT RUN — no Shortcut/Felix tooling
  is available in this session; left as the next operator step, consistent with the r1 record.

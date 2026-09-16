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
`-Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json`
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

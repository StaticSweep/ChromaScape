# SC-1663 evidence index

Generated: 2026-09-16T23:51:41Z (UTC), Fedora host (Linux 6.19.10-300.fc44.x86_64, JDK
`~/.jdks/corretto-17.0.20.1` toolchain, Gradle 8.14.2, `GRADLE_USER_HOME=$PWD/.gradle-home`).

JUnit XML exports of the focused SC-1663 test run
(`./gradlew test --tests "com.chromascape.utils.core.screen.window.*"`), final run
2026-09-16T23:51:41Z: 73 tests, 1 skipped (Windows-only factory test), 0 failures, 0 errors.

Per-file SHA-256 in `checksums.sha256`; regenerate with
`sha256sum TEST-com.chromascape.utils.core.screen.window.*.xml > checksums.sha256`.

| File | Content |
|---|---|
| `TEST-…CompiledClassIsolationTest.xml` | 2 tests — compiled-class isolation scans (no RemoteInput/EIOS in discovery+facade, no Win32 in Linux path) |
| `TEST-…LinuxProcessManagerTest.xml` | 17 tests — eligibility rules, determinism, unreadable-entry bounding, identity stability |
| `TEST-…ProcessIdentityTest.xml` | 4 tests — identity equality (hostId excluded), normalization, origin |
| `TEST-…ProcessManagerFactoryTest.xml` | 4 tests — platform mapping, JNA-empty-path Linux isolation, unsupported-platform bounding (1 Windows test skipped on Linux) |
| `TEST-…ProcFsTest.xml` | 10 tests — synthetic procfs: listing, cmdline, stat field 22, uptime, boot id, exe symlink |
| `TEST-…SelectionValidationTest.xml` | 10 tests — VALID/EXITED/REPLACED/NOT_LOCAL/STALE_GENERATION/NONE/UNSUPPORTED_PLATFORM |
| `TEST-…TargetDiscoveryServiceTest.xml` | 7 tests — neutral candidate shape, AMBIGUOUS_TARGET/STALE_SELECTION/NO_ELIGIBLE_TARGET refusals |
| `TEST-…WindowControllerTest.xml` | 2 tests — unchanged pre-existing controller contract |
| `TEST-…WindowHandlerTest.xml` | 10 tests — unchanged pre-existing Windows ranking/dedup contract |
| `TEST-…WindowSelectionServiceTest.xml` | 7 tests — generation increments, identity-aware isSelected, hwnd adapter |

All host-derived evidence in this directory is structural (test result XML); no command lines,
account identifiers or credentials are stored.

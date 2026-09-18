# SC-1667 verification evidence

Date: 2026-09-18

Base: `97482af3780277d6510e07b5f0640bd1721b92cb`

Branch: `codex/sc-1667-fedora-host-native-preflight`

Review PR: <https://github.com/ikiddoi/ChromaScape/pull/11>

## Current acceptance matrix

| Check | Result | Evidence |
|---|---|---|
| Fedora/x86-64/JDK 17/project/Micro6 detection | PASS | real Fedora host preflight |
| SC-1662 native library + manifest identity | PASS | real retained local artifact pair; read-only manifest verification |
| No-client strict refusal | PASS | real host reports `TARGET_MISSING` and terminal `BLOCKED` |
| Startup-before-client mode | PASS | real host reports terminal `READY` with explicit no-client warning |
| Parser, defaults, discovery, namespace, sandbox, ptrace, native and lifecycle matrix | PASS | `fedoraPreflightRegressions`: 19/19 |
| Native metadata regressions | PASS | `nativeMetadataTests`: 47/47 |
| SC-1662/1663/1664 focused Java regressions | PASS | 171 tests, 0 failures/errors, 1 Windows-only skip |
| Bash syntax and whitespace | PASS | all three tracked scripts; `git diff --check` clean |
| Changed-file privacy scan | PASS | no username, mounted-host path, credential value, or token value |
| Checkstyle | PASS with inherited warnings | task exit 0; 3,331 existing warnings across 566 files; no Java changed by SC-1667 |
| Ordinary suite | BASELINE | 2,425 tests, 100 inherited failures, 14 skips; prior integration evidence recorded 111 failures |
| Replay suite | BASELINE | 280 tests, 13 established environment/platform failures, matching the preceding story count |
| Spotless | BASELINE | 16 pre-existing Java offenders; SC-1667 changes no Java source |
| Live real-client attachment and restart | NOT RUN | no eligible RuneLite/Micro6 client was running; never represented as PASS |
| Restricted real sandbox | NOT RUN | deterministic Flatpak/PID namespace fixtures pass; no real sandbox was altered |
| Windows regression | NOT APPLICABLE | Fedora-only tooling; existing Windows restart path remains unchanged |
| Felix reconciliation | NOT AVAILABLE | canonical project contains two unrelated SC-1498 tasks and no `shortcut:sc-1667` task; no duplicate created |

## Real-host observations

The strict real-host invocation selected JDK 17 and the sibling Micro6 checkout, observed Fedora
x86-64 and enforcing SELinux, and failed closed because no eligible client was running. Attachment
was disabled for that invocation.

A second read-only invocation enabled attachment against the retained locally built SC-1662
artifact root. The real library/manifest pair verified successfully. With target-required disabled
for startup-before-client mode, the report was `READY` with one honest `TARGET_MISSING` warning. No
library was loaded and no process was attached, injected, paired, signalled, focused, or killed.

## Fixture coverage

The retained fixture harness proves:

1. local configuration is data, not executable shell, and existing values win;
2. shell-control variables are rejected;
3. loopback defaults preserve explicit operator overrides;
4. one host-native target becomes ready;
5. the matching PID namespace is reported;
6. the preflight process is excluded even when its fixture command line looks eligible;
7. mismatched PID namespaces fail closed;
8. Flatpak isolation fails closed;
9. restrictive ptrace policy without capability fails closed;
10. multiple targets require an explicit PID;
11. explicit eligible selection resolves ambiguity;
12. Gradle daemons are excluded;
13. native verification is delegated to the SC-1662 manifest verifier;
14. a missing native artifact fails closed;
15. an owned process identity is recognized;
16. its process group stops cleanly;
17. a stale/recycled identity is never signalled;
18. lifecycle operations are serialized;
19. the boot process cannot inherit and retain the lifecycle lock.

## Commands

```bash
bash -n scripts/fedora/chromascape-fedora.sh \
  scripts/fedora/lib/preflight.sh \
  scripts/fedora/tests/preflight-regressions.sh

./gradlew --offline fedoraPreflightRegressions nativeMetadataTests

./gradlew --offline -Psc967VectorsPath=<local-vector-file> test \
  --tests 'com.chromascape.utils.core.input.*' \
  --tests 'com.chromascape.utils.core.input.remoteinput.*' \
  --tests 'com.chromascape.utils.core.screen.window.*'

./gradlew --offline checkstyleMain
git diff --check
```

The Gradle invocations used an external Java 17 `JAVA_HOME`, the existing local Gradle cache, and
the existing SC-967 vector file. Those host-specific values are intentionally not retained here.

The full ordinary suite remains red for its established database, Testcontainers, source-contract,
gameplay-contract, and Windows-only process-launch conditions. Its failure count is eleven lower
than the immediately preceding integration evidence; no SC-1667 production or test Java class
exists in that suite. `replayTest` retains the preceding 13-failure count. Spotless retains the same
16-file inherited offender set reported by earlier stories; none is an SC-1667 file.

## Remaining qualification

Run the documented strict preflight with a real host-native RuneLite/Micro6 client, deliberately
enable the verified artifact, select the same process identity in ChromaScape, perform one bounded
attach/input/release cycle, then run `stop`, `start`, and a second attach. Record the result honestly.
No fixture or library-manifest check substitutes for that live qualification.

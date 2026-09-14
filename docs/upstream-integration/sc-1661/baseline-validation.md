# SC-1661 — Baseline Validation

## Environment

| Item | Value |
|---|---|
| Tested commit | `583a05b91687d156b1baa52dbda2b219604c7b39` (story branch tip, **no SC-1661 changes applied**) |
| Worktree | `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc1661-reconciliation` |
| OS | Linux 6.19.10-300.fc44.x86_64 |
| Gradle | 8.14.2 (wrapper; distribution reused from the main checkout's cached `.gradle-home`) |
| Launcher JVM | OpenJDK 25.0.2 (Red Hat 25.0.2+10) |
| `GRADLE_USER_HOME` | `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape/.gradle-home` |
| JDK 17 | **Not installed.** `/usr/lib/jvm` contains only `java-25-openjdk` / `jre-25` |
| Declared toolchain | `build.gradle.kts:29-30` — `languageVersion.set(JavaLanguageVersion.of(17))` |

## Result summary

**No check could be executed.** All Gradle invocations fail during **Kotlin DSL script
compilation**, before any task is selected or any Java is compiled. This is a single
environmental root cause, not four independent failures.

| # | Exact tested commit | Command | Exit | Tests run / failed / skipped | First causal failure | Pre-existing or introduced | Evidence |
|---|---|---|---|---|---|---|---|
| 1 | `583a05b` | `./gradlew compileJava --console=plain` | 1 | 0 / 0 / 0 | `java.lang.IllegalArgumentException: 25.0.2` | **Pre-existing (environmental)** | `shellout/2026-09-14T10-28-07-930Z-s0ouagjx.log` |
| 2 | `583a05b` | `./gradlew spotlessCheck --console=plain` | 1 | 0 / 0 / 0 | same | Pre-existing (environmental) | re-run, this session |
| 3 | `583a05b` | `./gradlew checkstyleMain --console=plain` | 1 | 0 / 0 / 0 | same | Pre-existing (environmental) | re-run, this session |
| 4 | `583a05b` | `./gradlew replayTest --console=plain` | 1 | 0 / 0 / 0 | same | Pre-existing (environmental) | re-run, this session |
| 5 | `583a05b` | `./gradlew test --console=plain` | **not run** | — | blocked by the same cause | — | — |
| 6 | `583a05b` | `./gradlew tasks --console=plain` | 1 | 0 / 0 / 0 | same | Pre-existing (environmental) | re-run, this session |

## First causal failure

```
FAILURE: Build failed with an exception.

* What went wrong:
25.0.2

* Exception is:
java.lang.IllegalArgumentException: 25.0.2
	at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.parse(JavaVersion.java:307)
	at org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion.current(JavaVersion.java:176)
	at org.jetbrains.kotlin.cli.jvm.modules.JavaVersionUtilsKt.isAtLeastJava9(javaVersionUtils.kt:11)
	at org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem.globalJrtFsCache$lambda$2(CoreJrtFileSystem.kt:83)
	...
	at org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment$Companion.createForProduction(KotlinCoreEnvironment.kt:468)

BUILD FAILED in 1s
```

**Diagnosis.** Gradle 8.14.2 ships Kotlin 2.0.21. Its bundled IntelliJ `JavaVersion.parse`
cannot parse the JDK version string `25.0.2`, so Kotlin DSL script compilation of
`build.gradle.kts` aborts. Because `build.gradle.kts` is a Kotlin script, **no task can run at
all** — including `tasks`, which proves the failure is independent of task selection and of
any Java source content.

## Evidence that this is pre-existing, not introduced by reconciliation

1. It reproduces at `583a05b` **with zero SC-1661 modifications applied** — the worktree was
   clean (0 porcelain entries) at the time of every run.
2. It fails for `./gradlew tasks`, which neither compiles Java nor runs tests.
3. The failing frame is Kotlin compiler bootstrap (`KotlinCoreEnvironment.createForProduction`),
   reached while evaluating the build script — not project code.

No regression was introduced by this story; equally, **no baseline check has been proven to
pass**, and none may be reported as passing.

## Blocking prerequisite

A **JDK 17** must be available for the declared toolchain. Installing or provisioning one is a
system-level change and was deliberately **not** performed without direction. Two routes:

- install a JDK 17 and point Gradle at it (`org.gradle.java.installations.paths` or
  `JAVA_HOME`), or
- add a toolchain resolver to `build.gradle.kts` — itself a build-file change owned by SC-1666.

Until then, `compileJava`, `test`, `replayTest`, `spotlessCheck`, `checkstyleMain` and
`replayCertificationTest` remain **unverified**, not failed-in-code.

## Named test classes — existence confirmed

All ten classes the story plan names were located in the baseline. **Existence verified;
execution not possible under the current toolchain.**

| Test class | Path in baseline |
|---|---|
| `WindowHandlerTest` | `src/test/java/com/chromascape/utils/core/screen/window/WindowHandlerTest.java` |
| `WindowControllerTest` | `src/test/java/com/chromascape/utils/core/screen/window/WindowControllerTest.java` |
| `ScreenCaptureServiceTest` | `src/test/java/com/chromascape/web/viewport/service/ScreenCaptureServiceTest.java` |
| `RecordingControllerMetadataTest` | `src/test/java/com/chromascape/web/recording/controller/RecordingControllerMetadataTest.java` |
| `RecordingConfigInitialFrameTest` | `src/test/java/com/chromascape/web/recording/config/RecordingConfigInitialFrameTest.java` |
| `RecordingServiceInitialFrameReadinessTest` | `src/test/java/com/chromascape/web/recording/service/RecordingServiceInitialFrameReadinessTest.java` |
| `RecordingServiceAutoStartGateTest` | `src/test/java/com/chromascape/web/recording/service/RecordingServiceAutoStartGateTest.java` |
| `RecordingSessionMetadataServiceTest` | `src/test/java/com/chromascape/web/recording/service/RecordingSessionMetadataServiceTest.java` |
| `BuilderGoalLauncherControlServiceTest` | `src/test/java/com/chromascape/web/debug/buildergoal/service/BuilderGoalLauncherControlServiceTest.java` |
| `Micro6ExecutorReadinessEvaluatorTest` | `src/test/java/com/chromascape/web/debug/buildergoal/service/Micro6ExecutorReadinessEvaluatorTest.java` |

## Custom build tasks — existence confirmed, execution not verified

`build.gradle.kts` registers `replayTest` (151), `replayCertificationTest` (173),
`replayStressCertificationTest` (255), `cleanChromascape` (506), `cleanAll` (522),
`copyNativeLibraries` (459), and wires `check` to `dependsOn("spotlessCheck", "checkstyleMain")`.
`config/checkstyle/google_checks.xml` and `checkstyle-suppressions.xml` are present.

These are confirmed **by source inspection only**. `./gradlew tasks` cannot enumerate them
under the current JDK.

## Honest status statement

- **Verified:** source inventory, ancestry figures, dirty-tree decomposition, upstream ledger,
  compatibility boundaries, test-class existence.
- **Not verified:** compilation, unit tests, replay tests, formatting, checkstyle — all blocked
  by the JDK 25 / Kotlin 2.0.21 incompatibility.
- **No exclusions were added** and **no skipped check is reported as a pass.**

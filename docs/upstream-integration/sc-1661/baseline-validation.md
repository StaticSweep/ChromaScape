# SC-1661 — Baseline Validation

## Environment

Both the candidate baseline and the reconciled branch were tested with the **same** environment.

| Item | Value |
|---|---|
| Worktree | `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc1661-reconciliation` |
| OS | Linux 6.19.10-300.fc44.x86_64 |
| Gradle | 8.14.2 (wrapper, **unchanged** — no wrapper upgrade) |
| Launcher JVM / toolchain | Corretto `17.0.20.1` (`JAVA_HOME=/home/ikiddoi/.jdks/corretto-17.0.20.1`) |
| `GRADLE_USER_HOME` | `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape/.gradle-home` |
| Declared toolchain | `build.gradle.kts` — `languageVersion.set(JavaLanguageVersion.of(17))` |
| Candidate-baseline commit | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` (= `583a05b` + SC-1661 docs only) |
| Reconciled commit | `966e0e3` (candidate baseline + `45f943f` + `966e0e3` carries) |
| Docker | **Not available** on this host |
| Local PostgreSQL | **Not available** (`localhost:5432`, `:5433` closed) |
| Evidence directory | `docs/upstream-integration/sc-1661/evidence/` (committed) |

### Launcher-JDK blocker — resolved

The previously recorded blocker was that Gradle's Kotlin DSL script compilation aborted under
JDK 25 with `java.lang.IllegalArgumentException: 25.0.2` (Kotlin 2.0.21's bundled IntelliJ
`JavaVersion.parse`). The launcher JVM was the cause, so the launcher JVM was fixed first:

1. A compatible JDK was **already present** on the host: `/home/ikiddoi/.jdks/corretto-17.0.20.1`.
   No provisioning was required.
2. `JAVA_HOME` and `PATH` were set to it for every command below.
3. `java -version` → `openjdk version "17.0.20.1" 2026-08-18 LTS`; `javac -version` → `17.0.20.1`.
4. `./gradlew --version` → `Launcher JVM: 17.0.20.1 (Amazon.com Inc.)`, `Gradle 8.14.2`.

**No Gradle wrapper upgrade and no toolchain resolver plugin was added.** The build files are
untouched by SC-1661; the fix is entirely environmental, exactly as the plan required.

### SC-967 fixture path

`test` has a `doFirst` gate that requires the SC-967 vectors fixture to be a regular file. The
build defaults to the Windows path `C:\Dev\IdeaProjects\contracts\sc967\ed25519-vectors.json`, and
the build file itself documents a supported override (`sc967VectorsPath` / `SC967_VECTORS_PATH`)
"for CI and WSL path translation". The equivalent Linux path exists, so the override was supplied.
This is a **documented configuration input, not a build change**.

```
-Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json
```

## Result summary — candidate baseline (`4e0a3d5`)

| # | Command | Exit | Tests run / failed / skipped | First causal failure | Classification | Evidence |
|---|---|---|---|---|---|---|
| 1 | `./gradlew tasks --console=plain` | **0** | — | — | **PASS** — build evaluates; all custom tasks register | `evidence/candidate-tasks.log` |
| 2 | `./gradlew compileJava --console=plain` | **0** | — | — | **PASS** | `evidence/candidate-compileJava.log` |
| 3 | `./gradlew checkstyleMain --console=plain` | **0** | — | 3280 warnings / 561 files, severity `warning` only | **PASS** (warnings do not fail the task) | `evidence/candidate-checkstyle-summary.log` (full 848 KB log condensed; report at `build/reports/checkstyle/main.xml`) |
| 4 | `./gradlew spotlessCheck --console=plain` | **1** | — | 16 files need formatting | **Pre-existing** | `evidence/candidate-spotless.log` |
| 5 | `./gradlew test --console=plain` (no fixture override) | **1** | 0 / 0 / 0 | `Resolved SC-967 fixture is not a regular file: …/C:/Dev/IdeaProjects/contracts/sc967/ed25519-vectors.json` | **Pre-existing (environment/configuration)** — fails before any test runs | `evidence/candidate-test.log` (first run) |
| 6 | `./gradlew test … -Psc967VectorsPath=…` | **1** | **2250 / 110 / 13** | see failure classification | Pre-existing failures only — no SC-1661 change present | `evidence/candidate-test.log` |
| 7 | `./gradlew replayTest --console=plain` | **1** | **280 / 12 / 0** | Docker unavailable | Pre-existing (environmental) | `evidence/candidate-replayTest.log` |

## Result summary — reconciled branch (`966e0e3`)

| # | Command | Exit | Tests run / failed / skipped | Classification | Evidence |
|---|---|---|---|---|---|
| 8 | `./gradlew compileJava compileTestJava --console=plain` | **0** | — | **PASS** — the adapted launcher code compiles | `evidence/reconciled-compile.log` |
| 9 | focused launcher suites (6 classes, `--tests`) | **0** | **175 / 0 / 0** | **PASS** | `evidence/reconciled-launcher-tests.log` |
| 10 | `./gradlew spotlessCheck --console=plain` | **1** | — | 16 files — **the same 16 as the candidate baseline** | `evidence/reconciled-spotless.log` |
| 11 | `./gradlew test … -Psc967VectorsPath=…` | **1** | **2255 / 110 / 13** | Identical failing-test **set** to the candidate baseline | `evidence/reconciled-test.log` |
| 12 | `./gradlew replayTest --console=plain` | **1** | **280 / 12 / 0** | Identical failing-test set | `evidence/reconciled-replayTest.log` |

Focused launcher suites used in #9: `BuilderGoalLauncherControlServiceTest`,
`BuilderGoalRunnerServiceTest`, `Micro6OnlineClientRegistryServiceTest`,
`Micro6TargetPreparationServiceTest`, `Micro6RemoteLauncherServiceTest`,
`Micro6ExecutorReadinessEvaluatorTest`.

The reconciled run executes **5 more tests** than the candidate baseline (2255 vs 2250) — the
`Micro6RemoteLauncherServiceTest` tests contributed by the carry — and all 5 pass.

## Introduced vs pre-existing

**No regression was introduced.** The strongest available comparison is not the counts but the
*failing test set*: extracting every `FAILED` test name from both logs and diffing the sorted sets
returns **no differences** for either `test` or `replayTest`.

```
$ diff /tmp/candidate-test.log.fails /tmp/reconciled-test.log.fails
    (empty — IDENTICAL FAILING TEST SET)
$ diff /tmp/candidate-replayTest.log.fails /tmp/reconciled-replayTest.log.fails
    (empty — IDENTICAL REPLAY FAILING SET)
```

`spotlessCheck` is likewise unchanged: 16 offending files before, 16 after, and the same files.
The adaptation's own files were formatted so they contribute **zero** new violations, and the
pre-existing violating files were deliberately left as found (fixing them would smuggle an
unrelated reformat into this diff).

## Failure classification — candidate baseline (identical on the reconciled branch)

Docker is unavailable and there is no local PostgreSQL, so Testcontainers-backed suites cannot
start. The 110 `test` failures decompose as:

| Signature | Count | Root cause | Classification |
|---|---|---|---|
| Docker environment unavailable | 34 | Testcontainers cannot find a Docker daemon | Environmental |
| PostgreSQL connection refused (`localhost:5433`) | 10 | `Sc967FlywaySchemaTest` expects a pre-provisioned database | Environmental |
| ApplicationContext failure-threshold cascade | 48 | Consequence of the two above; Spring skips re-loading the failed context | Environmental (cascade) |
| Gameplay contract / provider-output assertions | 18 | e.g. `GameplayContractValidator$GameplayContractViolation: $: null found, object expected`; `canonicalResourcesHaveFrozenByteDigests` expecting `c55ad78a…` but seeing `ff9ad17f…` | **Pre-existing code failures**, unrelated to SC-1661 |
| `replayTest` failures | 12 | All Docker (`ReplayMigrationIntegrationTest`, `ReplayPostgresIntegrationTest`, `ReplayPlayback*IntegrationTest`) | Environmental |

**Reproducibility.** Each signature was reproduced twice — once at the pristine candidate baseline
and once on the reconciled branch — with the identical failing-test set. The 18 gameplay/
provider failures also occur with no SC-1661 change present, so they are demonstrably not
introduced by this story. They are recorded, not fixed, and are **not** owned by SC-1661; the
integration stream must decide whether they are environment-sensitive or a genuine baseline
defect.

**No exclusions were added** to reach a green result, and **no skipped check is reported as a
pass**.

## Delivery impact

- **Green:** build evaluation, compilation (main + test), checkstyle, and the focused launcher
  suites. The candidate baseline is now demonstrably buildable on Linux, which is what SC-1662+
  needs from this story.
- **Red but pre-existing:** `spotlessCheck` (16 files) and the 18 gameplay/provider tests.
- **Cannot be proven on this host:** database, migration, concurrency and Docker-backed
  integration evidence. This bounds confidence in the *adapted launcher* code to compilation plus
  unit-test level; end-to-end remote-launch behaviour is SC-1667's to prove.
- The `test` gate's SC-967 fixture requirement means a Linux CI runner must set
  `SC967_VECTORS_PATH`, or the task fails before executing anything.

## Named test classes — existence and execution

All ten classes the story plan names exist in the baseline. Under the JDK 17 launcher they are
now **compiled**; those that do not require Docker/PostgreSQL also execute:

| Test class | Status |
|---|---|
| `WindowHandlerTest` | Compiled; executed (no DB requirement) |
| `WindowControllerTest` | Compiled; executed |
| `ScreenCaptureServiceTest` | Compiled; executed |
| `RecordingControllerMetadataTest` | Compiled; executed |
| `RecordingConfigInitialFrameTest` | Compiled; executed |
| `RecordingServiceInitialFrameReadinessTest` | Compiled; executed |
| `RecordingServiceAutoStartGateTest` | Compiled; executed |
| `RecordingSessionMetadataServiceTest` | Compiled; executed |
| `BuilderGoalLauncherControlServiceTest` | Compiled; executed (part of the 175 focused tests — PASS) |
| `Micro6ExecutorReadinessEvaluatorTest` | Compiled; executed (part of the 175 focused tests — PASS) |

## Custom build tasks — confirmed

`./gradlew tasks` now enumerates them (exit 0):

```
replayTest, replayCertificationTest, replayStressCertificationTest,
cleanChromascape, cleanAll, copyNativeLibraries
```

`check` is wired to `dependsOn("spotlessCheck", "checkstyleMain")`. `config/checkstyle/google_checks.xml`
and `checkstyle-suppressions.xml` are present.

`replayCertificationTest` and `replayStressCertificationTest` are **Windows-only hard gates** that
throw on a non-Windows host, and they additionally require PostgreSQL and a synthetic xRSPS worker.
They are correctly **not run** here; that is their designed behaviour, not a skip.

## Honest status statement

- **Verified by execution:** build evaluation, `compileJava`, `compileTestJava`, `checkstyleMain`,
  `test` (2250/2255), `replayTest` (280), `spotlessCheck`, and the focused launcher suites.
- **Verified as pre-existing:** the 16 spotless violations, the 92 Docker/PostgreSQL failures, and
  the 18 gameplay/provider failures.
- **Not verified:** any database-, migration- or Docker-backed evidence; the Windows-only
  certification gates.
- **No regressions introduced by SC-1661:** the failing test sets are byte-identical before and
  after, and the reconciled branch adds 5 passing tests.

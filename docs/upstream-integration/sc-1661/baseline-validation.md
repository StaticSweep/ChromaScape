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
| Evidence directory | `docs/upstream-integration/sc-1661/evidence/` — **tracked**; `evidence/index.md` records, per run, the code revision, command, result and SHA-256, and states exactly what is committed versus not retained |

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
| 4 | `./gradlew spotlessCheck --console=plain` | **1** | — | 16 files need formatting | **Pre-existing** | `evidence/candidate-spotless.log`; complete offending set in `evidence/candidate-spotless-offending-files-complete.txt` |
| 5 | `./gradlew test --console=plain` (no fixture override) | **1** | 0 / 0 / 0 | `Resolved SC-967 fixture is not a regular file: …/C:/Dev/IdeaProjects/contracts/sc967/ed25519-vectors.json` | **Pre-existing (environment/configuration)** — fails before any test runs | **not retained** — this run's console output was overwritten when the same file was reused for row 6. The failure text is quoted from the run; no log is claimed |
| 6 | `./gradlew test … -Psc967VectorsPath=…` | **1** | **2250 / 110 / 13** | see failure classification | Pre-existing failures only — no SC-1661 change present | `evidence/candidate-test.log` |
| 7 | `./gradlew replayTest --console=plain` | **1** | **280 / 12 / 0** | Docker unavailable | Pre-existing (environmental) | `evidence/candidate-replayTest.log` |

## Result summary — reconciled branch (`966e0e3`)

| # | Command | Exit | Tests run / failed / skipped | Classification | Evidence |
|---|---|---|---|---|---|
| 8 | `./gradlew compileJava compileTestJava --console=plain` | **0** | — | **PASS** — the adapted launcher code compiles | `evidence/reconciled-compile.log` |
| 9 | focused launcher suites (6 classes, `--tests`) | **0** | **175 / 0 / 0** | **PASS** | `evidence/reconciled-launcher-tests.log` |
| 10 | `./gradlew spotlessCheck --console=plain` | **1** | — | The **same 16 files** as the candidate baseline (complete sets byte-identical) | `evidence/reconciled-spotless.log`, `evidence/reconciled-spotless-offending-files-complete.txt` |
| 11 | `./gradlew test … -Psc967VectorsPath=…` | **1** | **2255 / 110 / 13** | Identical failing-test **set** to the candidate baseline | `evidence/reconciled-test.log`, `evidence/test-failures-reconciled.txt` |
| 12 | `./gradlew replayTest --console=plain` | **1** | **280 / 12 / 0** | Identical failing-test set | `evidence/reconciled-replayTest.log`, `evidence/replayTest-failures-reconciled.txt` |
| 13 | `./gradlew checkstyleMain --console=plain --rerun-tasks` | **0** | — | **PASS** (warnings only) — 3291 warnings / 563 files vs the candidate's 3280 / 561. The carried files add **11** findings, all `MissingJavadocMethod`/`MissingJavadocType`, severity `warning`; recorded, not fixed (see `evidence/index.md`) | `evidence/reconciled-checkstyle-summary.log`, `evidence/checkstyle-per-file-delta.txt` |

Row 13 is the **final-code** check. The earlier revision of this document recorded `checkstyleMain`
only for the candidate; the reconciled result is above, and the delta is exactly attributable:
`Micro6RemoteLauncherController` (+3, new), `Micro6RemoteLauncherService` (+7, new),
`Micro6OnlineClientRegistryService` (+1, new method). No other file's warning count changed.

Independent single-class review run (reproduced here as its own evidence): `./gradlew test
--tests '*Micro6RemoteLauncherServiceTest' -Psc967VectorsPath=… --console=plain --rerun-tasks` →
**exit 0, 3 tests, 0 failures** (`evidence/review-micro6-remote-launcher-tests.log`,
`evidence/review-micro6-remote-launcher-tests-summary.txt`). This is one class, not the 175-test
focused suite in row 9.

Focused launcher suites used in #9: `BuilderGoalLauncherControlServiceTest`,
`BuilderGoalRunnerServiceTest`, `Micro6OnlineClientRegistryServiceTest`,
`Micro6TargetPreparationServiceTest`, `Micro6RemoteLauncherServiceTest`,
`Micro6ExecutorReadinessEvaluatorTest`.

The reconciled run executes **5 more tests** than the candidate baseline (2255 vs 2250). They are not
all in one class: `Micro6RemoteLauncherServiceTest` adds **3** (it is a new file containing three
tests), `Micro6OnlineClientRegistryServiceTest` adds **1**, and `Micro6TargetPreparationServiceTest`
adds **1**. All 5 pass. An earlier revision of this document attributed all five additions to
`Micro6RemoteLauncherServiceTest`; that was wrong — that class contains three tests, and the other
two additions come from the two modified test classes.

## Introduced vs pre-existing

**No additional failures were observed in the executed candidate-versus-reconciled checks.** The
comparison is the complete *failing test set*, not the counts, and it is now a retained artifact
rather than a temporary file:

| Suite | Candidate | Reconciled | Comparison |
|---|---|---|---|
| `test` | `evidence/test-failures-candidate.txt` (110 identities) | `evidence/test-failures-reconciled.txt` (110 identities) | `diff` empty — identical |
| `replayTest` | `evidence/replayTest-failures-candidate.txt` (12) | `evidence/replayTest-failures-reconciled.txt` (12) | `diff` empty — identical |

**What this does and does not establish.** It establishes that no *newly observed* ordinary-test or
replay-test failure appeared between the two revisions. It does **not** prove that tests blocked by
missing infrastructure conceal no regression: Docker and PostgreSQL are absent, so 92 of the 110
`test` failures and all 12 `replayTest` failures are environmental, and the database-, migration- and
container-backed behaviour those suites cover was never exercised on either revision. Compilation
plus unit-test success is **not** end-to-end Fedora qualification.

`spotlessCheck` is likewise unchanged: the complete offending sets are 16 files at each revision and
are byte-identical (`evidence/candidate-spotless-offending-files-complete.txt` and
`evidence/reconciled-spotless-offending-files-complete.txt` share SHA-256 `1501905b…`). The complete
sets are retained because the `spotlessCheck` console output truncates ("Violations also present in
12 other files"), so a console excerpt alone cannot establish that all files match. The adaptation's
own files are **not** among the 16, so they contribute **zero** new formatting violations; the
pre-existing violating files were deliberately left as found (fixing them would smuggle an unrelated
reformat into this diff).

`checkstyleMain` is a different case and is stated plainly: it also passes (exit 0, `warning`
severity only), but the carried files **do** add 11 findings (row 13). Unlike a formatting
violation, these do not fail the task, and they were left in place rather than moving the pinned code
baseline for a cosmetic change.

## Failure classification — candidate baseline (identical on the reconciled branch)

Docker is unavailable and there is no local PostgreSQL, so Testcontainers-backed suites cannot
start. The 110 `test` failures decompose as:

| Signature | Count | Root cause | Classification |
|---|---|---|---|
| Docker environment unavailable | 34 | Testcontainers cannot find a Docker daemon | Environmental |
| PostgreSQL connection refused (`localhost:5433`) | 10 | `Sc967FlywaySchemaTest` expects a pre-provisioned database | Environmental |
| ApplicationContext failure-threshold cascade | 48 | Consequence of the two above; Spring skips re-loading the failed context | Environmental (cascade) |
| Non-environmental assertion/behaviour failures — 16 gameplay/provider + 2 other | 18 | e.g. `GameplayContractValidator$GameplayContractViolation: $: null found, object expected`; `canonicalResourcesHaveFrozenByteDigests` expecting `c55ad78a…` but seeing `ff9ad17f…` | **Pre-existing failures**, unrelated to SC-1661 — exact identities in `deferred-findings.md` (SC-1661-D2) and `evidence/non-environmental-failure-identities.txt` |
| `replayTest` failures | 12 | All Docker (`ReplayMigrationIntegrationTest`, `ReplayPostgresIntegrationTest`, `ReplayPlayback*IntegrationTest`) | Environmental |

**Reproducibility.** Each signature was reproduced twice — once at the pristine candidate baseline
and once on the reconciled branch — with the identical failing-test set (110 identities in `test`,
12 in `replayTest`; retained as the `*-failures-*.txt` files listed above). The 18 non-environmental
failures also occur with no SC-1661 change present, so they are demonstrably not introduced by this
story. They are recorded, not fixed, and are **not** owned by SC-1661. Their root cause is **not
confirmed**: the evidence available on this host does not separate a genuine baseline defect from
environment sensitivity, so they are not described as a definitive code defect. They are tracked as
**SC-1661-D2** in `deferred-findings.md`, which carries the exact identities and the intended tracker
record. Only 16 of the 18 belong to the gameplay/provider family; the other two are
`SourceContractSchemaEqualityTest` and `Micro6TargetRelaunchServiceTest`.

**No exclusions were added** to reach a green result, and **no skipped check is reported as a
pass**.

## Delivery impact

- **Green:** build evaluation, compilation (main + test), checkstyle, and the focused launcher
  suites. The candidate baseline is now demonstrably buildable on Linux, which is what SC-1662+
  needs from this story.
- **Red but pre-existing:** `spotlessCheck` (16 files) and the 18 non-environmental test failures
  tracked as **SC-1661-D2** in `deferred-findings.md`.
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

- **Verified by execution:** build evaluation, `compileJava`, `compileTestJava`, `checkstyleMain`
  (candidate 3280 warnings / 561 files; **reconciled 3291 / 563** — both exit 0, `warning` severity
  only), `test` (2250 → 2255), `replayTest` (280), `spotlessCheck`, the focused launcher suites
  (175 / 0 failures), and the single-class review run (`Micro6RemoteLauncherServiceTest`, 3 / 0).
- **Verified as pre-existing:** the 16 spotless violations, the 92 Docker/PostgreSQL failures, and
  the 18 non-environmental failures tracked as **SC-1661-D2**.
- **Not verified:** any database-, migration- or Docker-backed evidence; the Windows-only
  certification gates; end-to-end remote launch against a real Fedora agent.
- **No additional failures observed:** the ordinary-test and replay-test failing identity sets are
  byte-identical before and after, and the reconciled branch adds 5 passing tests (3 + 1 + 1 across
  three classes). This claim is bounded to the executed checks — the blocked suites are not evidence
  either way.
- **Evidence durability:** every claim above is backed by a tracked artifact listed in
  `evidence/index.md` with its code revision and SHA-256. No claim depends on `/tmp` or on the
  worktree's `build/` directory, both of which are overwritten.

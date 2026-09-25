# SC-1662 — Evidence index

Six run sequences are retained. Run 1 is the pre-review record; run 2 is the first post-review fix
record; run 3 is the second-review fix record; run 4 is the third-review fix record; run 5 is the
fourth-review test-harness fix record; run 6 is the current packaging-scope and native-build record.
No earlier run is overwritten.

Branch for all six: `codex/sc-1662-linux-remoteinput`.
Base revision for run 1: `be4230c0198e065748ff5b49a76f434a581fee4d`. Runs 2–5 are that revision plus
their respective working-tree changes; run 6 is the same base plus the final packaging-scope and
native-build changes. Each log records the revision and a `worktree_status_sha256` fingerprint of
`git status --porcelain=v1 -uall`.

Two staging logs are superseded. Run 3's (`r3-staging-harness.log`) predates the payload identity
being changed from the artifact hash to the pair identity, and run 4's (`r4-staging-regressions.log`)
predates the output-path isolation — it read and wrote the production `build/native/remoteinput` and
`build/dist/native` paths. Both are retained unmodified as history; run 5 supersedes them.

Environment (all runs): Fedora 44 x86_64 (`Linux 6.19.10-300.fc44.x86_64`), Gradle 8.14.2
(wrapper, unchanged), `GRADLE_USER_HOME=…/ChromaScape/.gradle-home`, SC-967 vectors supplied as
`-Psc967VectorsPath=…/contracts/sc967/ed25519-vectors.json`.

Gradle must be launched on **Java 17**: the Kotlin DSL does not configure on the default Java
25.0.4.1 JVM (`IllegalArgumentException: 25.0.4.1`). See `native-build.md` for the exact invocation.

## Redaction (applied after the runs, disclosed)

The implementation specification prohibits account identity in evidence. Every retained log, and the
documents that quote host paths, were therefore redacted: the username in absolute paths was
replaced with `<user>` (for example `<machine>/…`, `<jdks>/…`). In the launcher
JUnit XMLs the identifier-shaped test-fixture values `account=<hex>` and `owner=<uuid>` were masked
as `<account-redacted>`/`<owner-redacted>`-style placeholders.

Redaction changed path text only — no command, exit code, count, digest or test result was altered.
It **did** change the SHA-256 of every redacted log, so the hashes below are those of the redacted
bytes. `r2-shell-syntax.log`, `r2-spotless-offending-files-complete.txt` and the `junit/` XMLs
contained no username and were left byte-identical, so their hashes are the ones recorded for run 2
originally.

## Run 1 — historical (pre-review). Retained, redacted.

Logs `sc1662-*.log`. Kept for the record; several of their claims were corrected after review.
They carry no recorded hashes.

Corrections to run 1 that run 2 supersedes:

- `sc1662-checkstyleMain.log` reported `PASS` while the task was **`UP-TO-DATE`** — it had not
  executed, so the log evidenced nothing about the sources. Run 2 forces real execution.
- `sc1662-spotlessCheck.log` named 4 offenders and stated "12 other" offenders total 16, and the
  run-1 notes claimed the offending **set** was identical to the SC-1661 baseline. A truncated
  listing proves a count, not an identical set. Run 2 retains the complete offender set.
- Run 1 stated that not running the ordinary/replay suites was acceptable because the change was
  additive. That inference was not verified and is withdrawn (see `verification-evidence.md`).
- Run 1's recommendation `gradlew buildRemoteInputLinux … --repeat-build` used a flag that existed
  only on the shell script; as a Gradle option it failed with
  `Unknown command-line option '--repeat-build'`. Run 2 adds `-PremoteInputRepeatBuild`.

## Run 2 — first round of review fixes. Retained, redacted.

| Log | Command | Exit | Result | SHA-256 |
|---|---|---|---|---|
| `r2-tasks.log` | `./gradlew tasks --console=plain` | 0 | PASS — protected + 7 native tasks enumerated | `c7496287cf55f308b4e2a74f8aa358a3d92ba029b804f671c5c078a1508140f6` |
| `r2-compile.log` | `./gradlew compileJava compileTestJava --console=plain` | 0 | PASS | `6e53bee7bd1519c566c16fa3231553e96353958a2dcd5ed651c6a64f711f802a` |
| `r2-new-tests.log` | `./gradlew cleanTest test --tests '*NativeArtifactResolverTest' --tests '*RemoteInputLibraryLoaderTest' --tests '*RemoteInputManifestTest' --tests '*RemoteInputSmokeVerifierTest'` | 0 | PASS — 37 tests, 0 failures, 0 skips | `1e80e953e6df341505b732025eb4bdb5d38aa6ee9fb08ee1122c8794aac76e9b` |
| `r2-metadata-tests.log` | `./gradlew nativeMetadataTests --console=plain` | 0 | PASS — 37 fixture-only helper tests | `061dad3bad15f420a22f4aee91d37c63abecb888f39c6e5a2d4aa3583a2d3072` |
| `r2-launcher-suites.log` | `./gradlew cleanTest test --tests '*BuilderGoalLauncherControlServiceTest' … six suites …` | 0 | PASS — 175 tests, 0 failures, 0 skips | `ef693fa613e6a66c5e81bd55f7ec4129a34800273e555eea93182a8c1b620930` |
| `r2-checkstyleMain.log` | `./gradlew checkstyleMain --console=plain` | 0 | PASS — executed; project-wide warnings only | `789a1771a681f649e39dcaee272ba41cf43eb12fb31c1c90946d3f6fcd041ad0` |
| `r2-spotlessCheck.log` | `./gradlew spotlessCheck --console=plain` | 1 | FAIL — 16 offenders, complete set retained; zero SC-1662 files | `b138f32e50bbdf78952ffbf3910b100cb29cf3ac04164cb30975e883de1415cd` |
| `r2-buildRemoteInputLinux.log` | `./gradlew buildRemoteInputLinux --console=plain` | 1 | FAIL (expected, environment) — provenance passes, then preflight `cmake not found` | `7e3f42a8444d250dc1016365e59da4380ade101820efaa1eac406a30c521b713` |
| `r2-shell-syntax.log` | `bash -n scripts/native/build-remoteinput-linux.sh` | 0 | PASS | `c60b93ceeae0faeadca6f24e4a78bfe4b6520dedf8127eefbeb90b9384a5c576` |
| `r2-staging-and-packaged-routing.log` | fixture harness: staging contract + packaged routing | 0 | PASS — 4 staging cases, packaged routing proven | `320794f3355c58b1216748c9180f38af23648862304c2ced9895548554427093` |

Each run-2 log begins with the exact command, UTC timestamp, revision, worktree fingerprint and
run-sequence label, and ends with the exit code and elapsed seconds.

The run-2 staging design published the library and manifest as **two separate renames**, which the
second review showed cannot be atomic; `r2-staging-and-packaged-routing.log` is kept as the record of
what that design did, and run 3 supersedes it.

## Run 3 — second round of review fixes. Retained; its staging log is superseded.

| Log | Command | Exit | Result | SHA-256 |
|---|---|---|---|---|
| `r3-tasks.log` | `./gradlew tasks --console=plain` | 0 | PASS — protected + **8** native tasks (licence gate added) | `c2cd9c76ee99b556d6a95cc5495487f9408eabd6497d5ca9f21a9564266eafec` |
| `r3-compile.log` | `./gradlew compileJava compileTestJava --console=plain` | 0 | PASS | `ba08ea2316c001cf482de2442152646f65c13b54a89f0637b0a3fc50e8bb5056` |
| `r3-new-tests.log` | `./gradlew cleanTest test --tests '*NativeArtifactResolverTest' --tests '*RemoteInputLibraryLoaderTest' --tests '*RemoteInputManifestTest' --tests '*RemoteInputSmokeVerifierTest'` | 0 | PASS — **45** tests, 0 failures, 0 skips | `3e9fb947b57dae4a966f6c9f29f529beb9bd78254e99b7d08253b0a8b1c5b297` |
| `r3-metadata-tests.log` | `./gradlew nativeMetadataTests --console=plain` | 0 | PASS — **43** fixture-only helper tests | `dc6ed254eca01ceb74c35db2817d900eac68710e0cd2f33ad8eee0049e42e539` |
| `r3-checkstyleMain.log` | `./gradlew checkstyleMain --console=plain` | 0 | PASS — executed; project-wide warnings only | `e5a66bf59f846f3008e9867b1cd87ce62ecc25bb82bc89b91b3e0bbc2a7bb17d` |
| `r3-spotlessCheck.log` | `./gradlew spotlessCheck --console=plain` | 1 | FAIL — 16 offenders, complete set retained and identical to run 2; zero SC-1662 files | `f6891cdc5ddc6b42841c5fd9345ae31a2070298ef9dad85846361f776e5c07b4` |
| `r3-licence-gate.log` | `./gradlew verifyRemoteInputDistributionLicence --console=plain` | 1 | FAIL **as required** — `Distribution gate unresolved … third-party/RemoteInput/LICENSE` | `7b6aff7d7d966529eecc26182552d47d0ae59799b97ae6f2d3aa4bd1e226b7a1` |
| `r3-shell-syntax.log` | `bash -n scripts/native/build-remoteinput-linux.sh` | 0 | PASS | `be31ad024f6b0d8b8ec7fbe2bdc9e7a49428b4b2824d429d81d3fb196b5a8215` |
| `r3-staging-harness.log` | fixture harness: atomic staging contract; native compiler excluded | 0 | PASS — symlink publish, prior pair preserved across a failed stage, dereferenced copy has no symlinks | `7bbc2f8064e41dcfb35fd10839d8ce4dbde9a84e7953df0dd4be933163181ff7` |

Run 3 additionally verified, by direct inspection of the Spotless output cache, that the offending
set is exactly the same 16 files as run 2 (zero SC-1662 files) — see
`r3-spotless-offending-files-complete.txt`. The Spotless summary line undercounts; the retained
complete set is the authority.

`r3-staging-harness.log` records the staging design as it stood at run 3: the publish point is a
symlink to `.versions/<artifact-sha256>` — the **artifact hash**, before the identity was changed to
the pair identity — and a re-stage re-validates the existing payload rather than trusting it. It also
records that a second, different artifact publishes a second payload while the previous one is
retained, and that a failed stage (artifact mutated after its manifest was written) leaves the
publish point unchanged with no leftover temporary directory or symlink and the previous payload
still validating. It does **not** exercise the pair-identity binding or the mismatched-payload
refusal; run 4 does. `assembleLinuxDistribution` itself was **not** executed: it needs `bootJar`, a
staged payload and — correctly — a closed licence gate. Its symlink dereference is therefore
demonstrated structurally, not by executing the task.

## Run 4 — third round of review fixes. Retained; its staging log is superseded.

| Log | Command | Exit | Result | SHA-256 |
|---|---|---|---|---|
| `r4-tasks.log` | `./gradlew tasks --console=plain` | 0 | PASS — protected + **8** native tasks | `05a8a9d19cf745d5c316e4bf79dfd6844d507713ba6d9b3a556e5f2c0d674c95` |
| `r4-compile.log` | `./gradlew compileJava compileTestJava --console=plain` | 0 | PASS | `e806a25b0d04906230a4ef50d694573f83d9f9850da4bcafc49253b4588b586d` |
| `r4-new-tests.log` | `./gradlew cleanTest test --tests '*NativeArtifactResolverTest' --tests '*RemoteInputLibraryLoaderTest' --tests '*RemoteInputManifestTest' --tests '*RemoteInputSmokeVerifierTest'` | 0 | PASS — **46** tests, 0 failures, 0 errors, 0 skips | `f9866ebffa2673243598947d00ec2476012a620c202972ba4bd4e6deaa9278d2` |
| `r4-metadata-tests.log` | `./gradlew nativeMetadataTests --console=plain` | 0 | PASS — **43** fixture-only helper tests | `8ae7f6ce27fbed3e26c0df4180324950335c2bd5b72b5d025e307fbd203cbb40` |
| `r4-checkstyleMain.log` | `./gradlew checkstyleMain --console=plain` | 0 | PASS — executed; project-wide warnings only | `14bc38bff652fc1769616822053b3cc496619055be43a27aeb33fc5d0cd1f8b5` |
| `r4-spotlessCheck.log` | `./gradlew spotlessCheck --console=plain` | 1 | FAIL — 16 pre-existing offenders (3 listed + "13 other files"); no Java source changed this round | `531de2387036555cecd3a709cab0ebd4919693932afd0dd9179884e58c669080` |
| `r4-licence-gate.log` | `./gradlew verifyRemoteInputDistributionLicence --console=plain` | 1 | FAIL **as required** — `Distribution gate unresolved … (marker 'unresolved')` | `d8d86ceff84d2969e703b664aed09bb05cfc7de094fc3b5b38d678670657148c` |
| `r4-shell-syntax.log` | `bash -n scripts/native/build-remoteinput-linux.sh` | 0 | PASS | `3ad4e0bbda00b60899332b47c9a54786dedff665e08502cee528eb9ca549220f` |
| `r4-staging-regressions.log` | `scripts/native/staging-regressions.sh` (fixture harness; native compiler excluded) | 0 | PASS — 4 regressions: pair identity binds the manifest; a mismatched existing payload is refused; the displaced predecessor is retained; a legacy real-directory publish point is refused and survives | `06df520092a0ded7aa6e9ae63cf3eaf9e48d196545410f550d42735bb5ae37f1` |

Run 4 changes no Java source: the fixes live in `build.gradle.kts` (the staging task and the licence
gate, Kotlin DSL), `scripts/native/build-remoteinput-linux.sh`, `PROVENANCE.md` and the docs. The
focused JUnit count grew from run 3's recorded 45 to 46 because run 3's log was stale by one test
(the 11th `RemoteInputSmokeVerifierTest` already existed in the tree); run 4's `junit-r4/` XMLs are
the authority. `r4-staging-regressions.log` is the retained harness output for the four staging
regressions added this round; unlike the ad-hoc run-3 harness, the cases are now a committed script
(`scripts/native/staging-regressions.sh`).

## Run 5 — fourth round of review fixes: test-harness isolation and coverage

| Log | Command | Exit | Result | SHA-256 |
|---|---|---|---|---|
| `r5-tasks.log` | `./gradlew tasks --console=plain` | 0 | PASS — protected + **8** native tasks + the 2 new `verification` test-support tasks | `6ec3381cdc505dc08c980d8f36435a6c2765f1d2c15ae96b54811a13c7de82cd` |
| `r5-compile.log` | `./gradlew compileJava compileTestJava --console=plain` | 0 | PASS | `9443508f0efe9e067077f0f9b4e596d79a21a4cec9eaab3fe8357aa51018551e` |
| `r5-new-tests.log` | `./gradlew cleanTest test --tests '*NativeArtifactResolverTest' --tests '*RemoteInputLibraryLoaderTest' --tests '*RemoteInputManifestTest' --tests '*RemoteInputSmokeVerifierTest'` | 0 | PASS — **46** tests, 0 failures, 0 errors, 0 skips | `c5e40a6987750225e831938e234f30e3ad6966c3cea551156f20dc4ed828985d` |
| `r5-metadata-tests.log` | `./gradlew nativeMetadataTests --console=plain` | 0 | PASS — **43** fixture-only helper tests | `6d848113abdf94287cad1bd06125e9a0b8442ee0654baff288579e17a20ce084` |
| `r5-checkstyleMain.log` | `./gradlew checkstyleMain --console=plain` | 0 | PASS — executed; project-wide warnings only | `fc4310c158e83b9bfb8f66304508ab85017f2fe29767eca897b373ba60db770b` |
| `r5-spotlessCheck.log` | `./gradlew spotlessCheck --console=plain` | 1 | FAIL — 16 pre-existing offenders; no Java source changed this round | `688736dd3cde18f88e8e3aea79394d2106e70cdea658dc12c2339bd311655aff` |
| `r5-licence-gate.log` | `./gradlew verifyRemoteInputDistributionLicence --console=plain` | 1 | FAIL **as required** — `Distribution gate unresolved … (marker 'unresolved')` | `369e14bb07caabc2662738b6073609d7a288d768bca05e5de684241d1b3f078e` |
| `r5-licence-gate-matrix.log` | `./gradlew verifyRemoteInputDistributionLicenceMatrix --console=plain` | 0 | PASS — **9/9** marker × licence-file states behaved as documented | `440eb10e676b6ca9fc54a48f154027a0af10055e155c79b6e2919a63d4b8c09a` |
| `r5-shell-syntax.log` | `bash -n` on all three shell scripts | 0 | PASS | `7cb6a81a00a1b47ed18683fcee691617b0226d8c4f720e51091147ecf1326c35` |
| `r5-staging-regressions.log` | `scripts/native/staging-regressions.sh` (isolated `stageRemoteInputLinuxRegression`) | 0 | PASS — 4 Kotlin regressions; isolated root, production output untouched | `1cc56c85541083d8a4e88cce41bd6348ee265ec4f1b45d5b8cf06b0abf141aac` |
| `r5-staging-shell-regressions.log` | `scripts/native/staging-shell-regressions.sh` (shell `stage_artifact`) | 0 | PASS — 5 shell cases incl. the copied-temporary-pair refusal | `74a6ef9ac54389cf33922b07912464215ac541629c89a71134b26bf6d32d8db1` |

Run 5's fixes are the test harnesses and two `build.gradle.kts` test-support tasks; no Java source
changed, so the focused-suite count stays 46 and the launcher suites were not re-run. The staging
logs now reference only `build/sc1662/staging-regressions`; `build/native/remoteinput` and
`build/dist/native` are not read or written (the run-4 synthetic poisoning of those ignored paths was
cleared, and no real artifact existed to lose). The shell harness sources `build-remoteinput-linux.sh`
— whose `main` now runs only when the script is executed — and calls `stage_artifact` directly with
the publish root repointed at a temporary directory.

Run 5 is an immutable historical bundle. A post-run-5 review subsequently corrected the native C++
`bool` declarations from JNA `boolean` to explicit one-byte values and added one loader regression;
the current focused suite is therefore 47/47. That result is described in
`../verification-evidence.md`; the run-5 logs and hashes remain unchanged.

### Complete comparison sets retained

- `r2-spotless-offending-files-complete.txt` and `r3-spotless-offending-files-complete.txt` — the
  **entire** 16-file Spotless offender set for each run, not a truncated listing. The two sets are
  identical, 16 pre-existing files, zero SC-1662 files. Both files hash to
  `aae156087048d817e8338a70c0ee77192bfa8b9df13001b249572af34fabf712`.
- `junit/` — the complete JUnit XML results for run 2's 37 SC-1662 tests
  (`NativeArtifactResolverTest` 16, `RemoteInputLibraryLoaderTest` 10, `RemoteInputManifestTest` 4,
  `RemoteInputSmokeVerifierTest` 7; all `failures="0" errors="0" skipped="0"`).
  - `NativeArtifactResolverTest.xml` — `ac2763b1b7373c0b5b6361b54613be4f2a2900403412b5c17c784e68dfbd8b73`
  - `RemoteInputLibraryLoaderTest.xml` — `30360cdd134396817c8ec71adde94673ee2ecaf19ac1039d8b2f3657fdbf5fb5`
  - `RemoteInputManifestTest.xml` — `02f3d25d299a4432f23e4b0671fe4702b70743ccc904733c8d471ea69c2b8d5f`
  - `RemoteInputSmokeVerifierTest.xml` — `64a25b3f143001a714332e173d0b21afc1118b4c46fb2feda2e4ea7f85058287`
- `junit-r3/` — the complete JUnit XML results for run 3's 45 SC-1662 tests (`…ResolverTest` 16,
  `…LoaderTest` 10, `…ManifestTest` 9, `…SmokeVerifierTest` 10; all
  `failures="0" errors="0" skipped="0"`).
  - `…NativeArtifactResolverTest.xml` — `51fbc0ef56f36b5eea0fa97c8c57dda44ec087965496bc8a343641b32585fbf6`
  - `…RemoteInputLibraryLoaderTest.xml` — `79c55256be84187104a8e1ae685882552fbed1c3e2a00318b87eed88d9230e10`
  - `…RemoteInputManifestTest.xml` — `212f700e5d7e8be3fd52b03f2f74d333dd9630dd8c5971e5243aa52f97b2d9e2`
  - `…RemoteInputSmokeVerifierTest.xml` — `05834dabcd81c484f6a72654089e784faa737082516d132c7094f18657013140`
- `junit-r4/` — the complete JUnit XML results for run 4's 46 SC-1662 tests (`…ResolverTest` 16,
  `…LoaderTest` 10, `…ManifestTest` 9, `…SmokeVerifierTest` 11; all
  `failures="0" errors="0" skipped="0"`).
  - `…NativeArtifactResolverTest.xml` — `49be6d73c9bfb780661a69ea620ac39b57901c0c7c1a77feb82aa5a092edcaa6`
  - `…RemoteInputLibraryLoaderTest.xml` — `3e008aed779889db6dbfd1873ec9c5f37ab301a1b504dcf273174c92b3557e9a`
  - `…RemoteInputManifestTest.xml` — `bbf43524da90fd28719631ba707b1602859a649c70ae384713771418fbe7b69b`
  - `…RemoteInputSmokeVerifierTest.xml` — `639d99e009d529643900ec5c7ca903e5eec68ca2ba988ba2e2632a200a600a5b`
- `junit-r5/` — the complete JUnit XML results for run 5's 46 SC-1662 tests (`…ResolverTest` 16,
  `…LoaderTest` 10, `…ManifestTest` 9, `…SmokeVerifierTest` 11; all
  `failures="0" errors="0" skipped="0"`).
  - `…NativeArtifactResolverTest.xml` — `de6aab87c204894e239d87fdd96c87929763850fe98114283dc4e0cebcd9a668`
  - `…RemoteInputLibraryLoaderTest.xml` — `0cc5fedcab25d0da28d41d728c35fb6ee6329ebfb8c907ce0788bc003638a162`
  - `…RemoteInputManifestTest.xml` — `95bbb449a67e800e8919b692f3b59d18f05540c2eb9e6dc2f920e1442eb08765`
  - `…RemoteInputSmokeVerifierTest.xml` — `2eb972ee638aeff7c4bb6686e448ec101596d9b86d9eb37065fc630c969b5f2d`
- `junit-launcher/` — the complete JUnit XML results behind the 175-test preserved-suite figure:
  `BuilderGoalLauncherControlServiceTest` 26, `BuilderGoalRunnerServiceTest` 96,
  `Micro6OnlineClientRegistryServiceTest` 28, `Micro6TargetPreparationServiceTest` 9,
  `Micro6RemoteLauncherServiceTest` 3, `Micro6ExecutorReadinessEvaluatorTest` 13 = 175. These are
  run 2 artefacts (redacted); run 3 did not re-run the launcher suites.

## Run 6 — revised packaging scope: native build completed, distribution exclusion enforced

Run 6 supersedes run 5 for **current-state** claims; runs 1–5 remain immutable history. Run 6 was
executed 2026-09-16 against the revised PoC scope (development-only RemoteInput, excluded from every
packaged distribution). Redaction was applied after the runs and is disclosed in
`../verification-evidence.md`; `<repo>`/`<contracts>`/`<user>` placeholders replaced absolute
machine paths, and every hash below is of the redacted file.

### Container identity (accepted containerized clean-Fedora build)

- Fedora release 44 (Forty Four), glibc `2.43-8.fc44.x86_64`; host tooling podman 5.8.1.
- Base image `registry.fedoraproject.org/fedora:latest` — image ID `8c45708ba0fc`, digest
  `sha256:e64b8b7945c88d2bc368f4bf66a69ed418a3789bf0638fef403b8e18780f3383`.
- Committed toolchain image `localhost/sc1662-fedora-toolchain:latest` — image ID `cabd7e24e0ca`,
  digest `sha256:15179d9fbd427cc73c799f4710631fdb37c2183dbedfe53c5a24cc758ea72af4`.
- Build container `sc1662-build`: `--userns=keep-id` (uid 1000), repo bind-mounted at `/ws`, host
  Corretto JDK `17.0.20.1` bind-mounted read-only at `/opt/jdk17`.
- Toolchain: GCC 16.2.1, CMake 4.3.0, Python 3.14.7, JDK `openjdk 17.0.20.1`. Full capture:
  `r6-container-identity.txt`; build sequence: `../native-build.md`.

### Native artifact identity

- `libRemoteInput.so` sha256 `114bf72d2dc6d5b7000d24026188665cf56de7a069be947661a6c3786f3d0787`,
  size 836,336 bytes, `file`/`readelf`-confirmed ELF64 x86-64 shared object, `patch=none`.
- Pinned revision `5ea3b995f5d13e19c592a6399973fe7dadb5e656`; repeat build produced a byte-identical
  artifact (manifest `repeatSha256` matches).
- Gates: provenance gate, C++20 compiler check, CMake ≥ 3.15, JNI + Python headers, GL/X11
  compile-and-link probe, preflight/toolchain match, `nm` 23/23 required exports, `ldd` clean,
  manifest digest and size match the artifact.
- Development smoke verification (`r6-development-smoke.log`): `SMOKE_OK`, development mode,
  identical canonical artifact path and SHA-256 across all three working-directory scenarios, 23/23
  required symbols, bounded subprocess exited normally without injection/pairing/input.

### Results

| Log | Command | Exit | Result | SHA-256 |
|---|---|---|---|---|
| `r6-tasks.log` | `./gradlew tasks --console=plain` | 0 | PASS — **7** native tasks; `assembleLinuxDistributionRegression` is correctly in the separate `verification` group; no `verifyPackagedRemoteInputLinux` | `351d75ba7071b6e789644da9a113bb9d351d6cac9b97b897cdfb619857ce26f5` |
| `r6-compile.log` | `./gradlew --offline compileJava compileTestJava bootJar` | 0 | PASS — offline compile+jar (bootstrap stage 2) | `3bb5b0e04f2e9d44d18dd9de78aef1adf1c6d60b540ef467cd39550503fae763` |
| `r6-focused-tests-offline.log` | `./gradlew --offline cleanTest test --tests '*NativeArtifactResolverTest' --tests '*RemoteInputLibraryLoaderTest' --tests '*RemoteInputManifestTest' --tests '*RemoteInputSmokeVerifierTest'` | 0 | PASS — **47/47**, 0 failures, 0 errors, 0 skips | `6a77913d611db7386cd44b13f419a019fddd3223c56224f7fada1b8b33a56180` |
| `r6-focused-tests.log` | same command, offline, before the test-runtime cache was populated | 1 | EXPECTED FAIL — test runtime deps not yet cached (see bootstrap below) | `90373579a78ffae083ca0b138bdbf6f764924f01d9a0b54f14437c7a9377f2d6` |
| `r6-metadata-tests.log` | `./gradlew nativeMetadataTests` | 0 | PASS — **47** helper tests (43 prior + 4 new build-facts bool regressions) | `8682462f3bf22414136ce85cd887908bb0850ca9af608e45563a38177821a64e` |
| `r6-licence-gate.log` | `./gradlew verifyRemoteInputDistributionLicence` | 1 | FAIL **as required** — gate unresolved by design; now protects optional/local RemoteInput work only | `d9021cf16acfc9fed53fd7ab8b1ab1428bcfbcd93f235dcee21b03fefc55c487` |
| `r6-licence-matrix.log` | `./gradlew verifyRemoteInputDistributionLicenceMatrix` | 0 | PASS — **9/9** marker × licence states | `3a515cb85543a780a061ddc64ff078358b549f5f54b5baf48a8244ed7415f100` |
| `r6-staging-regressions.log` | `scripts/native/staging-regressions.sh` (isolated `stageRemoteInputLinuxRegression`) | 0 | PASS — Kotlin staging regressions, production output untouched | `0e5b69c07838de6432b92e8c729193e35d0bcc5fa2cc88c9cbe46184b3278d0b` |
| `r6-staging-shell-regressions.log` | `scripts/native/staging-shell-regressions.sh` | 0 | PASS — shell staging regressions | `edd7ab39c039d300dd5317c3a7bb49b10e67c1739e16ce8b586f04a25aa11bf9` |
| `r6-native-build-repeat.log` | in container: `./gradlew --offline buildRemoteInputLinux -PremoteInputRepeatBuild=true -PremoteInputJobs=4` | 0 | PASS — see **Native artifact identity**; repeat build byte-identical | `841dbef5aac528f676b15c0ffacd18609496cc559aebd28edac5311dd625b2cd` |
| `r6-stage.log` | `./gradlew --offline stageRemoteInputLinux -PremoteInputSkipBuild=true` | 0 | PASS — validated container-built pair published atomically | `1171e57fffa621104b441683e8a71517c77b7f44c98f48c2bc355ebbe715ea2b` |
| `r6-development-smoke.log` | `./gradlew --offline verifyRemoteInputLinux` | 0 | PASS — `SMOKE_OK`, 3 scenarios, 23/23 symbols (see **Native artifact identity**) | `6c0f29dcca5f1f6d54363eeeaddd4fe2b04b978b23fb4ebcffbf188fa5a0604c` |
| `r6-distribution-exclusion-regressions.log` | `scripts/native/distribution-exclusion-regressions.sh` | 0 | PASS — **12/12** assertions: seeded stale payload provably removed; tree exactly the allowed set; launcher checked with `sh -n` + exec bit (never executed) | `4792ef513665bd92da200b9b1c252c1fdf982fa9830b21630d0fc9c0a7eb2dca` |
| `r6-distribution-tree.txt` | `find build/dist/linux-x86_64 -mindepth 1 \| sort` | 0 | `application.jar`, `launcher`, `licenses/ChromaScape-LICENSE` only | `691ce38c89088785233f4b80322ddb87121d8ee482ff81c44ca1e400f993c894` |
| `r6-jar-payload-scan.log` | `unzip -l` grep of the boot jar for RemoteInput entries | 1 | grep found **no matches** — jar carries no RemoteInput payload | `5865f982cb96a2590bb2f717a036fd785ae9611563eec83d08cb2b75c3ad420d` |
| `r6-ordinary-tests.log` | `./gradlew --offline test` | 1 | EXPECTED FAIL — 2302 tests, 109 failed, 13 skipped; **no new failure identity vs SC-1661 (one fewer)** | `21b1633f0b9c6d47a49bb72e931d9725536dae44712f6f439f24700dba39331b` |
| `r6-replay-tests.log` | `./gradlew --offline replayTest` | 1 | EXPECTED FAIL — 280 tests, 12 failed; **12/12 identities identical to the SC-1661 Docker-environmental baseline** | `edb26bb7bfc0ef6df580ace8c0a11f2ff9b8ebd9f0bd74d053a61428129bba9e` |
| `r6-checkstyle.log` | `./gradlew --offline checkstyleMain` | 0 | PASS | `ac2798d3744d07fcd8d34d1310ee8f1ce1bd3bdd5fa4a82eb298af6d3697fed4` |
| `r6-spotless.log` | `./gradlew --offline spotlessCheck` | 1 | EXPECTED FAIL — the same 16 pre-existing offenders, set unchanged (see below) | `2d20077ff73e2650ac99a9b5524e07a3b8edc3ea5dff0b81f83e913bcb9f3786` |
| `r6-shell-syntax.log` | `bash -n` on all four `scripts/native/*.sh` | 0 | PASS | `fdc36e4eedc75bf1452b1c7e0a1da0d3b18cd780678596bc28228c0be72c1fae` |

Non-log run-6 evidence:

| File | Content | SHA-256 |
|---|---|---|
| `r6-container-identity.txt` | podman/image/toolchain capture quoted above | `49d6ac013ee467b3cc4ce173de53a14246695d14df1b664459af32df8daca74a` |
| `r6-failure-identity-delta.txt` | ordinary + replay failure-identity diff vs the SC-1661 baseline (no new identities) | `d506e0d4a345696c55ad3c5e2fcf9b0fa395b0bd9d7502266a31d4f371489fea` |
| `r6-spotless-offending-files-complete.txt` | complete 16-file offender set from a byte-restore-safe `spotlessApply` enumeration probe — identical set, identical hash to the r2/r3 file | `aae156087048d817e8338a70c0ee77192bfa8b9df13001b249572af34fabf712` |

### Two-stage dependency-cache bootstrap (honest sequence)

| Log | Command | Exit | Result | SHA-256 |
|---|---|---|---|---|
| `r6-bootstrap-offline-failed.log` | `./gradlew --offline compileJava …` on the fresh worktree cache | 1 | EXPECTED FAIL — plugin classpath unresolvable offline on a clean cache | `60540930b12bf92f363d718b3bdeeec29a9bd29685162ae38e89b0cf4ff7f70d` |
| `r6-bootstrap-online.log` | `./gradlew compileJava compileTestJava testClasses bootJar` (online) | 0 | populates the cache | `7a7f76c745ed11b66196506d9e63e5bb4a936fd879a0b5e4b4ea141740d5def1` |
| `r6-bootstrap-online-tests.log` | online follow-up (test runtime deps: JUnit engine, objenesis, …) | 0 | completes the test-runtime cache | `74143b8bd34df880d02637458a88b7d0ccc488e2c3bd7fb4eda0b2656fb42245` |
| `r6-bootstrap-offline-compile.log` | `./gradlew --offline compileJava compileTestJava bootJar` | 0 | PASS — required workflow offline | `c66db16209520244c479f1a68ccbd93909749841d6b56b30b89c85903d794538` |
| `r6-bootstrap-offline-assemble.log` | `./gradlew --offline assembleLinuxDistribution` | 0 | PASS — assembly offline with the allowed tree only | `0a1834e94087bab1784d69f0ecbadd9201b14759e14a34961c2aeb1888a818f1` |

A clean machine cannot operate offline before this bootstrap; documented in `../native-build.md`.

### JUnit totals retained

- `junit-r6/` — focused suite: 4 XML files, **47** tests, 0 failures, 0 errors, 0 skips.
- `junit-r6-ordinary/` — ordinary suite: 270 XML files, **2302** tests, 109 failures, 0 errors,
  13 skipped (matches the environmental baseline).
- `junit-r6-replay/` — replay suite: 42 XML files, **280** tests, 12 failures, 0 errors, 0 skips
  (the 12 known Docker-environmental failures).
- Per-file digests: `junit-r6.sha256`, `junit-r6-ordinary.sha256`, `junit-r6-replay.sha256`
  (316 entries; XML only — Gradle `binary/` output streams are excluded).

### Not executed in run 6

- **Windows verification — NOT RUN; non-blocking PoC waiver recorded.** The runbook
  (`../windows-verification-runbook.md`) defines the later follow-up commands. The 2026-09-17 waiver
  permits story completion but is not marked PASS anywhere.

## Not retained / not executed

- Windows packaging evidence — **NOT RUN**; no Windows host and explicitly non-blocking for this
  PoC. Covered by the run-6 note above and the waiver/runbook record in
  `../windows-verification-runbook.md` and
  `../shortcut-story-update.md`.

## Privacy review

Every retained artefact was reviewed. Username-bearing absolute paths were redacted to `<user>` and
identifier-shaped JUnit fixture values were masked (see **Redaction** above). What remains is:
filesystem paths, tool versions, task names, test counts, SHA-256 digests and canonical JSON
metadata.

No credentials, tokens, or process command lines are present, and no account identifier is present
after redaction. The JUnit XMLs still carry Gradle's `hostname` attribute; on this host it is the
stock `fedora` value, not a user or account identifier.

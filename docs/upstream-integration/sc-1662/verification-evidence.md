# SC-1662 — Verification evidence

Date: 2026-09-16 (UTC). Branch: `codex/sc-1662-linux-remoteinput`, revision `be4230c` + working
tree (run 5 fixes, the post-run-5 ABI review, the packaging-premise revision and run 6).

Status vocabulary is strict: **PASS** (executed and succeeded), **FAIL** (executed and failed),
**NOT RUN** (not executed). A historical
SC-1661 result is never used to mark an SC-1662 item PASS. A truncated listing never establishes an
identical set.

Run 6 of `evidence/index.md` is the latest retained evidence bundle and **supersedes run 5 for every
current-state claim**; runs 1–5 are retained there as history, redacted for username-bearing paths.
Run 5 predates the packaging-premise revision (RemoteInput excluded from every packaged
distribution, packaged verification withdrawn, seven native tasks, assembly freed from the licence
gate), so run-5 rows that mention eight native tasks, a packaged verification task or the gate
guarding assembly describe a superseded state. A post-run-5 review corrected the JNA C++ `bool`
mapping and reran the focused suites (47/47); run 3's staging log predates the pair-identity
binding and run 4's predates the output-path isolation; all are retained but superseded.

## Acceptance results

| # | Acceptance criterion | Status | Evidence |
|---|---|---|---|
| 1 | Documented clean Fedora build produces the pinned artifact and manifest | **PASS** | Built in a rootless Podman Fedora 44 container (image digest `sha256:15179d9f…`, container native toolchain — no host sudo needed); repeat build byte-identical; `libRemoteInput.so` sha256 `114bf72d…`, 836,336 bytes, ELF64 x86-64 shared object; preflight gates, `file`/`readelf`/`nm`/`ldd` checks and manifest digest/size match all recorded in `evidence/r6-native-build-repeat.log`; container identity in `evidence/r6-container-identity.txt`; see `native-build.md` for the exact sequence |
| 2 | Development and packaged execution load the artifact independently of CWD | **PASS (development)** | Real development smoke verification executed: `SMOKE_OK`, development mode, identical canonical artifact path and sha256 across all three working-directory scenarios, expected source revision `5ea3b995…`, `patch=none`, all 23 required symbols resolved; bounded subprocess exited normally with no injection, pairing, target selection or input (`evidence/r6-development-smoke.log`). This proves loadability and export resolution, not every callable ABI — real input-call ABI remains SC-1663+ |
| 3 | Configuration precedence and invalid-override behaviour verified | **PASS** | 16 resolver unit tests incl. property-over-env, env-only, app-root fallback, invalid-explicit-no-fallback, relative rejection |
| 4 | Missing/invalid/incompatible libraries fail with actionable diagnostics and zero attachment | **PASS (unit level)** | `MISSING_LIBRARY`, `INVALID_ARTIFACT`, `UNSUPPORTED_PLATFORM_OR_ARCH`, `LOAD_FAILED`, `MISSING_SYMBOL` covered; the loader binds only after every required symbol resolves; a test asserts the compiled verifier references no `EIOS_*` symbol |
| 5 | Missing optional native capability does not break unrelated startup/tests | **PASS** | No static initialisation; tests assert constructing the loader performs zero native access; compile and all focused suites run with no native artifact present |
| 6 | Existing Windows packaging/backend behaviour verified | **NOT RUN — PoC waiver** | No Windows host. `copyNativeLibraries` reported `SKIPPED` on Linux (correct); Windows was not exercised and is not PASS. On 2026-09-17 the user explicitly waived it as a blocking criterion for this private PoC; the retained Windows runbook remains available for later execution |
| 7 | Protected Gradle tasks survive; required changed-code tests pass | **PASS** | `tasks` enumerates `replayTest`, `replayCertificationTest`, `replayStressCertificationTest`, `cleanChromascape`, `cleanAll`, `copyNativeLibraries` plus the **seven** current native tasks (no `verifyPackagedRemoteInputLinux`) and the two `verification` test-support tasks (`stageRemoteInputLinuxRegression`, `verifyRemoteInputDistributionLicenceMatrix`); **47** current SC-1662 JUnit + **47** helper + 175 launcher tests pass |
| 8 | Evidence retained and reviewed for private data | **PASS** | `docs/upstream-integration/sc-1662/evidence/**`; username-bearing paths redacted to `<user>` and identifier-shaped JUnit fixture values masked, disclosed in `evidence/index.md` |
| 9 | Dedicated implementation PR reviewed and merged | **NOT RUN** | Changes are uncommitted; no PR opened (publishing not requested) |
| 10 | Source provenance cannot silently become inaccurate | **PASS** | Drift, missing-file and unexpected-file fixtures rejected; the real tree fails the build before compilation when one source line changes; a declaration claiming `none` is rejected; a declaration must now match the measured change set exactly and carry the derived `patchIdentity` (a declared-but-unchanged file, a phantom addition, a phantom removal and an arbitrary identity are all rejected) |
| 11 | Manifest generation cannot produce invalid JSON | **PASS** | The previously breaking JDK string round-trips unchanged; quotes, backslashes, spaces and unicode covered; the serializer output is re-parsed and validated before atomic publish |
| 12 | Staging refuses mismatched or incomplete pairs, and the published pair is indivisible | **PASS** | Mutated artifact, truncated manifest and missing manifest refused; the pair is published under its **pair identity** (`sha256(artifact-sha256 ‖ 0x00 ‖ manifest-sha256)`, so a manifest-only change is a distinct payload, not the artifact hash) as an immutable `.versions/<pair-identity>/` payload exposed by one atomic symlink rename, so an interruption cannot expose a new library with an old manifest; an already-present payload is re-validated **and** must recompute to the expected identity, so a modified but internally valid pair reused under another pair's name is refused; the copied temporary pair is held to the same identity, and that rejection branch is now exercised; a failed stage leaves the publish point unchanged with no leftover temporary directory or link and the previous payload still validating; a legacy real-directory publish point is refused and survives. Covered for **both** implementations: the Kotlin task (`evidence/r5-staging-regressions.log`) and the shell `stage_artifact` (`evidence/r5-staging-shell-regressions.log`). The harnesses run against isolated roots and never read or write `build/native/remoteinput` or `build/dist/native` |
| 13 | Documented repeat-build command works | **PASS (wiring)** | `-PremoteInputRepeatBuild=true` forwards `--repeat-build`; `--jobs` validation rejects `0`/negative/non-numeric/out-of-range |
| 14 | Java 17 is selected through the toolchain, not the daemon JVM | **PASS** | `JAVA_HOME` is resolved from `javaToolchains.launcherFor`; `System.getProperty("java.home")` is no longer used; the daemon JVM on this host is Java 25 and the toolchain resolves Corretto 17 |
| 15 | The licence gate protects optional RemoteInput distribution work; it no longer guards normal assembly | **PASS** | `verifyRemoteInputDistributionLicence` still fails closed unless `third-party/RemoteInput/LICENSE` is a non-empty file **and** `PROVENANCE.md` carries `top-level-licence-resolution: resolved` (nine-state matrix, both directions: `evidence/r6-licence-matrix.log`); `assembleLinuxDistribution` no longer depends on it. Assembly itself is executed and clean (`evidence/r6-bootstrap-offline-assemble.log`), and the distribution-exclusion regression proves the assembled tree carries no RemoteInput payload even from a seeded stale state (`evidence/r6-distribution-exclusion-regressions.log`) |
| 16 | Packaged verification independently verifies the library/manifest contract | **PASS (unit level)** | The manifest is located and strictly validated **before** the library is loaded; absent, unsupported-schema and digest/size-mismatched manifests each fail with a non-`LOAD_FAILED` diagnostic and no success line (three new tests). A real packaged load remains **NOT RUN** |

## Tests

### Run 6 (latest, retained)

Every Gradle command in this table used the Java 17 launch environment above and
`-Psc967VectorsPath=<contracts>/sc967/ed25519-vectors.json`; the abbreviated command column omits
those common arguments. Commands copied to another host must supply both.

| Command | Exit | Result | Evidence |
|---|---|---|---|
| `./gradlew compileJava compileTestJava` (after two-stage cache bootstrap) | 0 | PASS offline | `evidence/r6-compile.log`, `evidence/r6-bootstrap-offline-compile.log` |
| `./gradlew --offline cleanTest test --tests '*NativeArtifactResolverTest' --tests '*RemoteInputLibraryLoaderTest' --tests '*RemoteInputManifestTest' --tests '*RemoteInputSmokeVerifierTest'` | 0 | **47 tests, 0 failures, 0 errors, 0 skips** | `evidence/r6-focused-tests-offline.log`, `evidence/junit-r6/**` |
| `./gradlew nativeMetadataTests` | 0 | **47 helper tests, OK** (43 + 4 new build-facts bool-regression tests) | `evidence/r6-metadata-tests.log` |
| `./gradlew buildRemoteInputLinux -PremoteInputRepeatBuild=true -PremoteInputJobs=4` (in rootless Podman Fedora 44 container) | 0 | **PASS** — artifact sha256 `114bf72d…`, 836,336 bytes, ELF64 x86-64; repeat build byte-identical; all preflight/export/dependency gates green | `evidence/r6-native-build-repeat.log` |
| `./gradlew stageRemoteInputLinux` | 0 | Pair staged atomically from the container-built artifact | `evidence/r6-native-build-repeat.log` |
| `./gradlew verifyRemoteInputLinux` | 0 | **Development smoke verification PASS** — `SMOKE_OK` in all three working-directory scenarios, 23/23 required symbols | `evidence/r6-development-smoke.log` |
| `./gradlew assembleLinuxDistribution` | 0 | PASS — distribution contains only application.jar, launcher and the ChromaScape licence | `evidence/r6-bootstrap-offline-assemble.log` |
| `scripts/native/distribution-exclusion-regressions.sh` | 0 | **PASS** — all seeded stale RemoteInput payload removed; exact allowed tree asserted; launcher `sh -n` + executable, never launched | `evidence/r6-distribution-exclusion-regressions.log` |
| `./gradlew verifyRemoteInputDistributionLicence` | 1 | FAIL **as required** — gate still fails closed on the unresolved provenance marker (unchanged behaviour) | `evidence/r6-licence-gate.log` |
| `./gradlew verifyRemoteInputDistributionLicenceMatrix` | 0 | **9/9** states behaved as documented | `evidence/r6-licence-matrix.log` |
| Kotlin + shell staging regressions (both implementations, isolated roots) | 0 | PASS — pair identity, refusal, retention and legacy-publish-point branches all hold | `evidence/r6-staging-regressions.log`, `evidence/r6-staging-shell-regressions.log` |
| `./gradlew test` (ordinary suite) | 1 | **2302 tests, 109 failed, 13 skipped** — failure identities compared with the SC-1661 baseline: no new identities (one fewer); remaining failures are pre-existing environmental | `evidence/r6-ordinary-tests.log`, `evidence/junit-r6-ordinary/**` |
| `./gradlew replayTest` | 1 | **280 tests, 12 failed** — identical 12 Docker-environmental failure identities to the baseline | `evidence/r6-replay-tests.log`, `evidence/junit-r6-replay/**` |
| `./gradlew checkstyleMain` | 0 | PASS — project-wide warnings only | `evidence/r6-checkstyle.log` |
| `./gradlew spotlessCheck` | 1 | The same 16 pre-existing offender files (complete set re-enumerated via a throwaway `spotlessApply`, files restored byte-identically); no SC-1662 file involved | `evidence/r6-spotless.log`, `evidence/r6-spotless-offending-files-complete.txt` |
| `bash -n` on all four native scripts | 0 | Syntax OK | `evidence/r6-shell-syntax.log` |

Run-6 exit codes, SHA-256 hashes, JUnit totals, container identity and native artifact identity are
indexed in `evidence/index.md`.

### Post-run-5 ABI review

The focused four-suite command was rerun after replacing JNA's 32-bit Java `boolean` mapping with
an explicit one-byte binding for every C++ `bool` parameter and return. It passed **47 tests, 0
failures, 0 errors, 0 skips** (`…ResolverTest` 16, `…LoaderTest` 11, `…ManifestTest` 9,
`…SmokeVerifierTest` 11). The added loader test reflects over all six affected signatures and checks
the conversion helpers. This is structural ABI coverage; a real native invocation remains blocked.

### Toolchain identities

Gradle 8.14.2 (wrapper, unchanged); **Gradle daemon JVM = Red Hat Java 25.0.4.1** — which is why the
native `JAVA_HOME` must come from the toolchain and why every Gradle invocation in this worktree must
name a Java 17 `JAVA_HOME` explicitly (the Kotlin DSL fails to configure on 25.0.4.1 with
`IllegalArgumentException: 25.0.4.1`); resolved Java 17 = Amazon Corretto 17.0.20.1 at
`/home/<user>/.jdks/corretto-17.0.20.1`; OS `Linux 6.19.10-300.fc44.x86_64 x86_64`;
`GRADLE_USER_HOME=…/ChromaScape/.gradle-home`; `SC967_VECTORS_PATH` supplied via
`-Psc967VectorsPath`.

### Comparison against the SC-1661 baseline

| Suite | SC-1661 / run 1 baseline | Run 2 | Run 3 | Run 4 | Run 5 | Comparison |
|---|---|---|---|---|---|---|
| Focused launcher suites | 175 pass / 0 fail | 175 pass / 0 fail | not re-run | not re-run | not re-run | unchanged sources |
| `spotlessCheck` offenders | 16 files | complete set retained | identical to run 2 | same pre-existing set | same pre-existing set (no Java change) | identical set, verified against the retained lists |
| SC-1662 tests | 23 pass / 0 fail | 37 pass / 0 fail | 45 pass / 0 fail | 46 pass / 0 fail | **46 pass / 0 fail** | Run 5 is immutable; the post-run-5 ABI regression brings the current tree to **47 pass / 0 fail** |
| Helper tests | — | 37 pass / 0 fail | 43 pass / 0 fail | 43 pass / 0 fail | **43 pass / 0 fail** | 6 added |
| Licence-gate states executed | — | — | 1 (unresolved) | 1 (unresolved) | **9 (matrix, both directions)** | the gate's states are now executed, not asserted |
| Staging implementations behaviourally covered | — | Kotlin (ad hoc) | Kotlin (ad hoc) | Kotlin (ad hoc, production paths) | **Kotlin (isolated) + shell (direct)** | run 5 also stops the harness touching production output |

### Corrected and withdrawn claims

Run 1 corrections are retained in `evidence/index.md`. This revision additionally corrects:

- **Corrected:** "staging publishes the pair atomically". The library and manifest were moved as two
  separate renames, so an interruption could expose a new library with an old manifest — in both the
  Gradle task and the shell script. The pair is now published as an immutable payload directory
  exposed by a single atomic symlink rename.
- **Corrected:** "the runtime independently verifies the library/manifest contract". Packaged
  verification treated a missing manifest as `null`, reported `revision=unknown`, and could still
  print `SMOKE_OK`; the manifest reader accepted an unsupported schema version, a malformed digest,
  a non-positive size and empty provenance fields. The manifest is now required and strictly
  validated before the library is loaded.
- **Corrected:** "a declaration cannot describe a patch the tree does not contain". `verify-sources`
  only checked for measured changes missing from the declaration, so it accepted an unchanged file
  declared as changed, a non-existent file declared as added, and a declared removal that still
  existed; it also trusted an arbitrary `patchIdentity`. Equality is now required in both directions
  and the identity is derived.
- **Corrected:** "CWD/path verification loads the same canonical path in all three scenarios". Only
  the first run's report was examined, and the parser's `canonical=(\S+)` truncated any path
  containing a space. The verifier now emits a JSON record and every run's canonical path is
  asserted against the expected real path.
- **Corrected:** "the preflight checks GL and X11 headers/libraries" and "`ldd` is required". The
  preflight checked headers only and never invoked `ldd`. It now compiles and links a GL/X11 probe,
  resolves it with `ldd`, and `validate_artifact` fails on any unresolved dependency.
- **Corrected:** "`assembleLinuxDistribution` carries attribution into the distribution". It did so
  while the licence gate recorded in `PROVENANCE.md` was unresolved. The gate is now enforced by a
  dedicated task the distribution depends on; that task was executed and observed failing.
- **Corrected:** "the `scripts/native` ignore rule is narrow". `!/scripts/native/` exposed every
  file below the directory, not only the two named files. The rule now names each reviewed file.
- **Corrected:** "no account identifiers are present in the retained evidence". Retained logs and
  docs contained `/home/<user>` and `/run/media/<user>` paths, and the launcher JUnit XMLs contained
  account/owner fixture values. Username-bearing paths were redacted and identifier-shaped fixture
  values masked; the redaction is disclosed in `evidence/index.md`.
- **Corrected:** the handoff figure "47 files" was the non-`third-party` remainder only. The
  qualified count is in `implementation-evidence.md`.
- **Corrected:** "an already-published payload is re-validated". Re-validation only checked the
  library against its own sibling manifest, so a modified but internally valid pair could be reused
  under another pair's directory name. The payload identity is now recomputed and compared with the
  expected identity — for an existing payload and for the copied temporary pair — before reuse or
  publication (`evidence/r4-staging-regressions.log`, case 2).
- **Corrected:** "the licence gate is closed by landing the licence". The task rejected a missing or
  empty `third-party/RemoteInput/LICENSE` but never checked that `PROVENANCE.md` recorded the
  resolution, so any non-empty file would have closed it. The gate now also requires the
  `top-level-licence-resolution: resolved` marker in `PROVENANCE.md`.
- **Corrected:** "the staging regressions exercise the staging implementation". They read and wrote
  the canonical `build/native/remoteinput` and `build/dist/native` paths, so a run after a real native
  build would have destroyed that artifact and left a synthetic publish point. They now drive an
  isolated task root, and a shell harness covers the shell stager that `bash -n` never exercised.
- **Corrected:** "the licence gate is evidenced". Only the unresolved state had been executed. A
  nine-state matrix now executes both directions plus the malformed, duplicate, missing-marker and
  missing-provenance cases (`evidence/r5-licence-gate-matrix.log`).

### Not run

- Windows packaging regression and Windows execution of the focused suites — no Windows host in this
  session; explicitly waived as a blocking criterion for the private PoC on 2026-09-17 and deferred
  to a later Windows run per `windows-verification-runbook.md`. The waiver is recorded as NOT RUN,
  never as PASS.
- Packaged execution of the smoke verifier — **withdrawn** with the packaged RemoteInput payload
  under the revised scope; the distribution-exclusion regression replaces it.
- Negative native fixtures (wrong architecture, missing export) against a real artifact.
- Real execution of the structurally audited ABI parameter/return mappings (input-call ABI is
  SC-1663+ scope).

These are **NOT RUN**, not passing.

## Risks

1. **Licensing.** The vendored RemoteInput tree has no top-level license; `kubo_injector` is GPL/LGPL.
   The top-level resolution remains **unresolved** by design for the private PoC; distribution is now
   mechanically excluded (regression-proven), which does not resolve the legal question — that
   requires the copyright holder's terms.
2. **Native ABI execution.** C++ `bool` has an explicit one-byte JNA binding and a structural
   regression test, and the real library now loads with all 23 required symbols resolved. The
   complete ABI, including `size_t`, pointer out-parameters and real boolean calls, still needs
   execution against the built ELF (SC-1663+).
3. **Load-time constructor.** `Plugin/Plugin.cxx:134` `[[gnu::constructor]] __load()` self-instruments
   the loading process at `dlopen`. The development smoke verification observed it in a bounded
   subprocess without hang or crash; an in-process load remains unobserved.
4. **Windows behaviour.** Unchanged code paths and `copyNativeLibraries` skipping correctly on Linux
   reduce the risk, but Windows has not been executed; treat as pending, not passing.
5. **`spotlessCheck` remains red** at the same 16 pre-existing files, deliberately not fixed here to
   avoid smuggling an unrelated reformat into this diff. The run-6 complete set was re-enumerated
   and is identical; no SC-1662 file is involved.
6. **Containerized native build.** The reproducible artifact was produced in a rootless Podman
   Fedora 44 container rather than on bare-metal Fedora; the toolchain, package set and build
   sequence are recorded so the build can be reproduced outside the container.

## Rollback

Revert the SC-1662 changes and delete only its generated artifacts:

- Remove `third-party/` (hyphen) entirely — it is additive; `third_party/` (underscore) and
  `third_party/KInput/**` are untouched and remain functional.
- Remove `scripts/native/` (script, helper and tests) and restore the `.gitignore` SC-1662 block.
- Revert the `build.gradle.kts` additions (imports, toolchain resolution, native task classes,
  `AssembleLinuxDistributionTask` and the **seven** native task registrations); all pre-existing
  tasks and wiring are unchanged, so the revert is mechanical.
- Remove `src/main/java/…/remoteinput/{NativeArtifactResolver,RemoteInputLibraryLoader,
  RemoteInputInterface,RemoteInputManifest,RemoteInputDiagnostic,RemoteInputArtifactException,
  RemoteInputSmokeVerifier}.java` and the four test classes.
- Delete generated output: `build/native/**`, `build/dist/native/**` (including `.versions/` and the
  `linux-x86_64` symlink), `build/dist/linux-x86_64/**`, `build/r3-dist-check/**`.

No database migration, server API change, or Windows behaviour is involved. Windows KInput remains
available throughout.

## Honest status statement

- **Verified by execution:** the reproducible native build in a rootless Podman Fedora 44 container
  (repeat build byte-identical); atomic staging of the real pair; the real development smoke
  verification (`SMOKE_OK` in all three working-directory scenarios, 23/23 required symbols);
  `assembleLinuxDistribution`; the distribution-exclusion regression (stale RemoteInput payload
  cannot survive assembly); task enumeration (seven native tasks, no packaged-verification task);
  main + test compilation; 47 SC-1662 JUnit tests; 47 helper tests; checkstyle; the licence gate
  failing closed on the unresolved marker plus the nine-state matrix; both staging-regression
  implementations; the ordinary suite (2302 run, 109 failures — no new failure identities against
  the baseline) and the replay suite (280 run, 12 failures — the identical Docker-environmental
  set); and shell syntax for all four native scripts.
- **Verified as expected-failure:** `verifyRemoteInputDistributionLicence` fails closed on the
  unresolved licence marker; `spotlessCheck` fails at the 16 pre-existing offenders; the ordinary
  and replay suites exit nonzero at exactly the environmental baselines.
- **Not verified:** Windows packaging/execution (explicitly waived as a blocking PoC criterion and
  deferred per `windows-verification-runbook.md`), packaged execution (withdrawn with the packaged
  payload), negative native fixtures against a real artifact, and real execution of the audited ABI
  parameter/return mappings.
- SC-1662 implementation is **complete on the accepted Linux/PoC scope** and ready to commit and
  open for review. Review is required. Windows verification is a waived, non-blocking follow-up and
  remains NOT RUN.

## Shortcut story disposition

The live Shortcut state was not readable in this session (the story is authentication gated and no
Shortcut connector was available), so no tracker mutation or state claim was made. The exact
paste-ready text for the story is `shortcut-story-update.md`: it marks the implementation complete
and review-required, records the revised development-only scope, the run-6 native build and
development smoke evidence, the superseded-baseline suite comparisons, the distribution-exclusion
regression, and the explicit non-blocking Windows waiver. It must not claim Windows PASS,
legal-resolution, review, PR or merge completion until those have actually happened.

Run 6 of `evidence/index.md` is the authoritative evidence bundle for all current-state claims;
run 5 is retained as history and superseded for current state.

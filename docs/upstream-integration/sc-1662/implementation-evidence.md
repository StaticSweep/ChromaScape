# SC-1662 — Implementation evidence

Revision: `be4230c0198e065748ff5b49a76f434a581fee4d` + the SC-1662 working tree (uncommitted).
Branch: `codex/sc-1662-linux-remoteinput`.

This document records what was implemented and what is genuinely executed. Status vocabulary is
strict: **PASS** (executed and succeeded), **FAIL** (executed and failed), **NOT RUN** (not
executed), **BLOCKED** (cannot be executed in this environment). Run 6 is the current evidence
bundle and supersedes run 5 for every current-state claim; run 5 and earlier are retained in
`evidence/index.md` as history (run 3's staging log predates the pair-identity binding and is
superseded; run 4's staging log predates the output-path isolation).

## Current-state note — the packaging premise was revised after run 5

A fifth review round revised the story's scope, and the implementation now matches it:

- RemoteInput is a **private proof of concept**, development-only. The vendored sources are accepted
  for this private PoC, the `top-level-licence-resolution` marker stays `unresolved`, and no licence
  file is invented.
- **RemoteInput is excluded from every packaged ChromaScape distribution.** Native build, staging,
  manifest validation, loader and development smoke verification remain supported; packaged
  RemoteInput verification is withdrawn, and there is no `verifyPackagedRemoteInputLinux` task.
- A deployment may still supply its own library under `<applicationRoot>/native/linux-x86_64/`, but
  ChromaScape does not ship one: the distribution contains only the application jar, the launcher
  and the ChromaScape licence.
- SC-1663+ lifecycle, injection, pairing, input, capture and debugger integration remain out of
  scope.

What run 6 additionally executes, where run 5 could not: the distribution-exclusion regression
(new), assembly itself, the ordinary and replay suites, and the two-stage dependency-cache
bootstrap. The sections below carry both the historical record and the current state; each table
names the run it describes.

## Scope note — four review rounds, one corrected premise

Installing the native toolchain is **not** the only remaining work.

Run 1 left defects that no toolchain would have fixed: the manifest writer produced invalid JSON,
the build script was excluded by `.gitignore`, packaged loading was unimplemented, the native task
used the Gradle daemon's JVM for `JAVA_HOME`, provenance was hard-coded to `none`, and the
documented repeat-build command did not exist.

The second review found four more correctness gaps, all of which were claims that did not hold:

1. `assembleLinuxDistribution` produced a distributable bundle while the top-level RemoteInput
   licence gate it documented was unresolved;
2. staging moved the library and the manifest as **two** separate renames, so an interruption could
   expose a new library beside an old manifest — in both the Gradle task and the shell script;
3. packaged verification tolerated a missing or structurally invalid manifest and could still report
   `SMOKE_OK` with `revision=unknown`;
4. `verify-sources` accepted patch declarations that described changes, additions or removals which
   never happened, and trusted an arbitrary `patchIdentity`.

Three smaller gaps were also closed: the canonical-path assertion did not examine the
unrelated-cwd/spaced-path runs and truncated paths at the first space; the preflight checked GL/X11
headers but neither the link libraries nor `ldd`; and the `.gitignore` rule, the evidence privacy
claim and the handoff file count were all inaccurate.

The third review found three more gaps, all in the staging / licence-evidence surface:

1. an already-present payload was validated only against its **own** manifest, so a modified but
   internally valid pair could be reused under another pair's directory name; the copied temporary
   pair was not identity-checked either;
2. the licence gate rejected a missing or empty `third-party/RemoteInput/LICENSE` but did not require
   `PROVENANCE.md` to record the resolution, so any non-empty licence file would have closed it;
3. the pair-identity documentation and the staging evidence predated the pair-identity change, and
   the focused test count was stale (45 → 46).

The fourth review found test-harness gaps rather than product defects:

1. the staging regression harness deleted and poisoned the canonical build output
   (`build/native/remoteinput`, `build/dist/native`); it now drives a dedicated, isolated task root
   and never reads or writes the production paths;
2. the shell stager had no behavioural coverage beyond `bash -n`; `staging-shell-regressions.sh` now
   exercises `stage_artifact` directly against an isolated root;
3. the copied-temporary-pair identity rejection branch was unexercised; it is now forced to fail
   deterministically;
4. only one licence-gate state was executed; a nine-state matrix now covers the marker × licence-file
   combinations; and
5. a stale `.versions/<artifact-sha256>` comment and the frozen implementation plan were misleading.

## Changed files

### Phase A — pinned native import and provenance (new)

- `third-party/DEV_README.md` and `third-party/RemoteInput/**` — 278 files imported verbatim from
  upstream commit `5ea3b995f5d13e19c592a6399973fe7dadb5e656`. Identity verified: imported set equals
  the pinned set after exclusions (278/278) and every file's `git hash-object` equals
  `git rev-parse 5ea3b995:<path>` (0 drift).
- Excluded, as non-build inputs: `.github/**` (CI state and `build.zip`), `RemoteInput.xcodeproj/**`,
  `RemoteInput.cbp/.depend/.layout`, `precompiled/**`, `.gitignore`.
- `third-party/RemoteInput/PROVENANCE.md` — source repo, commit, imported paths, tree identity,
  exclusions, license inventory, local modifications, the **unresolved missing top-level RemoteInput
  license**, and the machine-readable `top-level-licence-resolution` marker the distribution gate
  reads (currently `unresolved`).
- `third-party/RemoteInput/import-manifest.sha256` — per-file SHA-256 for all 278 imported files.
- Untouched: `third_party/KInput/**` (Windows backend) and its DLLs.

### Phase B/C — build, provenance, manifest, staging

- `scripts/native/build-remoteinput-linux.sh` — rewritten:
  - provenance verification runs **before** the toolchain preflight and before any compile;
  - manifest generation delegates to the metadata helper (no shell-interpolated JSON);
  - `--jobs` rejects `0`, negatives, non-numeric and out-of-range values (documented range `1..256`);
  - `--patch-declaration FILE` added for documented local patches;
  - `-DCMAKE_CXX_COMPILER` / `-DPython_EXECUTABLE` are passed, and `CMakeCache.txt` is re-read
    afterwards to fail if CMake selected different tools than the preflight validated;
  - the GL/X11 **link** libraries are proved by compiling and linking a throwaway translation unit
    with the validated compiler (`-lGL -lX11`) and resolving the result with `ldd`;
  - `validate_artifact` resolves the artifact's own dependencies with `ldd` and fails on `not found`
    instead of deferring that to verification;
  - publishing is a versioned payload directory (`build/dist/native/.versions/<pair-identity>/`,
    where the identity binds the artifact and manifest digests) exposed by a single atomic symlink
    rename (`mv -T`), with the previous payload retained. An already-present payload is re-validated
    **and** must recompute to the expected pair identity before it is reused.
- `scripts/native/remoteinput_metadata.py` — `verify-sources`, `write-manifest` and
  `verify-manifest`; real JSON serialization, validate-then-publish, deterministic source-tree
  identity, patch declarations. `verify-sources` now requires the declared changed/added/removed
  paths to equal the measured change set **exactly** and requires `patchIdentity` to equal the
  identity derived from the declaration and the measured content.
- `scripts/native/tests/test_remoteinput_metadata.py` — fixture-only tests (43).
- `scripts/native/staging-regressions.sh` (with fixture generator
  `scripts/native/tests/staging_regression_fixtures.py`) — drives the real staging task
  (`stageRemoteInputLinuxRegression`, the same `StageRemoteInputLinuxTask` class wired to an isolated
  root) against synthetic library/manifest pairs, without a C++ toolchain, and asserts the four
  Kotlin staging regressions: the pair identity binds the manifest (a manifest-only change publishes
  a distinct payload that is not the artifact hash); an existing payload that validates against its
  own manifest but is not the expected pair is refused; the displaced immediate predecessor is
  retained when an older pair is republished; and a legacy real-directory publish point is refused
  without being removed. Everything it reads and writes is under `build/sc1662/staging-regressions`;
  the real `build/native/remoteinput` and `build/dist/native` are never touched.
- `scripts/native/staging-shell-regressions.sh` — sources `build-remoteinput-linux.sh` (whose `main`
  now runs only when the script is executed, not sourced) and calls `stage_artifact` directly against
  an isolated temporary root, giving the **shell** stager the behavioural coverage `bash -n` cannot:
  publish identity, existing-payload identity refusal, the copied-temporary-pair identity refusal
  (forced deterministically by stubbing `install` so the copy lands a different valid pair),
  predecessor retention, and the legacy real-directory refusal.

### Phase D — Gradle integration (modified `build.gradle.kts`)

- Added imports for `ExecOperations`, `Inject`, `PosixFilePermissions`, `ByteArrayOutputStream`,
  `File`, `JsonSlurper`, `MessageDigest`, `HexFormat`.
- `JAVA_HOME` for the native build is resolved from the Java 17 **toolchain**
  (`javaToolchains.launcherFor`) at execution time; `System.getProperty("java.home")` is gone.
- `-PremoteInputRepeatBuild=true` forwards `--repeat-build`; `-PremoteInputJobs` is validated.
- `stageRemoteInputLinux` publishes an immutable, content-addressed payload directory and switches a
  single symlink onto it. The identity is the **pair** identity (`sha256(artifact-sha256 ‖ 0x00 ‖
  manifest-sha256)`), not the artifact hash, so a manifest-only change publishes a distinct payload
  rather than discarding the new manifest. The payload move and the symlink rename are both
  `rename(2)`; a filesystem that cannot rename atomically makes the task **fail** rather than fall
  back to a copy. The current and previous payloads are retained; older ones are pruned. The task's
  own action re-validates an already-present payload **and** requires its recomputed pair identity to
  equal the expected identity before reusing it; the copied temporary pair is checked the same way
  before it is published.
- `assembleLinuxDistribution` ships **no RemoteInput payload**. It is implemented by
  `AssembleLinuxDistributionTask` (added after run 5), which takes the boot jar, the ChromaScape
  licence and an output root as explicit inputs, cleans the output root before assembling —
  including any `native/` directory a previous experiment left behind — and produces exactly
  `application.jar`, an executable `launcher` and `licenses/ChromaScape-LICENSE`. It does **not**
  depend on `stageRemoteInputLinux` or on `verifyRemoteInputDistributionLicence`, and it does not
  dereference the native staging symlink, because the distribution carries no native payload.
- `assembleLinuxDistributionRegression` (group `verification`) is the **same**
  `AssembleLinuxDistributionTask` class wired to an isolated output root under
  `build/sc1662/distribution-exclusion`; `scripts/native/distribution-exclusion-regressions.sh`
  pre-seeds that root with a stale RemoteInput payload and asserts every seeded path is removed,
  no `libRemoteInput.so`/`remoteinput-manifest.json`/RemoteInput licence exists anywhere under the
  tree, the jar carries no vendored source, and the remaining tree is exactly
  `application.jar`, `launcher`, `licenses/ChromaScape-LICENSE`. The regression never touches
  `build/dist/linux-x86_64`, `build/dist/native` or a real native artifact.
- The licence gate (`verifyRemoteInputDistributionLicence`) now protects optional/local RemoteInput
  distribution work only; it no longer guards normal assembly.
- `verifyRemoteInputDistributionLicence` now shares one `remoteInputDistributionLicenceFailure(...)`
  predicate with `verifyRemoteInputDistributionLicenceMatrix` (group `verification`), which executes
  all nine marker × licence-file states against temporary fixtures — so the gate's states are
  executed, not merely asserted.
- `stageRemoteInputLinuxRegression` (group `verification`) is the same `StageRemoteInputLinuxTask`
  class wired to an isolated root under `build/sc1662/staging-regressions`, so the staging
  regressions cannot destroy or poison the real build output.
- `RemoteInputSmokeVerifier` verification is `VerifyRemoteInputTask`, used by
  `verifyRemoteInputLinux` (development classpath). The packaged mode is retained in the task class
  and the verifier for a future distribution that ships a licensed library again, but **no packaged
  verification task exists**: the assembled distribution contains no RemoteInput payload. The task
  parses the verifier's JSON report and asserts, for **all three** working-directory scenarios, the
  mode, the SHA-256 and the canonical path against the expected real path. The previous
  regex-based parser truncated paths containing spaces and the second and third runs were never
  inspected.
- `nativeMetadataTests` runs the helper tests.
- All native tasks are Linux-only and outside the ordinary `check` graph.
- Preserved unchanged: Java 17 toolchain declaration, `replayTest`, `replayCertificationTest`,
  `replayStressCertificationTest`, `cleanChromascape`, `cleanAll`, `copyNativeLibraries`, the
  `processResources`/`build`/`jar` native-copy wiring, and `check → spotlessCheck, checkstyleMain`.

### Phase E — resolver, loader, bindings, diagnostics

`src/main/java/com/chromascape/utils/core/input/remoteinput/`:

- `NativeArtifactResolver.java` — precedence, absolute-path enforcement, canonicalisation, ELF
  class/machine/type validation.
- `RemoteInputLibraryLoader.java` — injectable `NativeAccess`, symbol verification, interface
  binding; rejects a relative or non-normalised artifact path instead of silently absolutising it.
- `RemoteInputInterface.java` — audited binding; declares `EIOS_IsMouseButtonHeld` (correcting the
  upstream binding's non-existent `EIOS_IsMouseHeld`).
- `RemoteInputManifest.java` — reads the manifest fields verification reports on, and now rejects an
  unsupported schema version, a malformed revision or digest, a non-positive artifact size and blank
  provenance values.
- `RemoteInputDiagnostic.java`, `RemoteInputArtifactException.java` — stable categories with context.
- `RemoteInputSmokeVerifier.java` — bounded subprocess entry point. It now verifies the manifest
  contract **before** loading the library, fails when the manifest is absent, compares both the
  recorded digest and the recorded size, and prints a single `SMOKE_REPORT {json}` line instead of a
  whitespace-delimited one.

### `.gitignore`

The `/scripts/*/` rule excluded the build script, and the global `*.log` rule excluded the evidence
logs. Narrow exceptions were added — and, in the second round, narrowed further so that
re-including the directory does not expose everything below it:

```
!/scripts/native/
/scripts/native/*
!/scripts/native/build-remoteinput-linux.sh
!/scripts/native/remoteinput_metadata.py
!/scripts/native/tests/
/scripts/native/tests/*
!/scripts/native/tests/test_remoteinput_metadata.py
```

Verified with `git check-ignore -q`: the three reviewed files are visible, while a synthetic
`scripts/native/future-file.sh`, `scripts/native/tests/future_test.py` and
`scripts/native/subdir/anything.py` all remain ignored. Other scripts, unrelated `*.log` files,
`build/` outputs and generated `.so` files remain ignored.

### Untracked-path count (qualified)

`git status --porcelain=v1 -uall` reports **731** candidate files for this worktree: 3 modified
tracked files (`.gitignore`, `build.gradle.kts`, `implementation-plan.md`) and 728 untracked files.
The candidate set is 431 `docs` files (1 modified, 430 untracked), 280 untracked `third-party`
files, 11 untracked `src` files, 7 untracked `scripts` files, plus the 2 other modified tracked
files. The `third-party` count is the 278-file pinned import plus the two story-authored provenance
files (`PROVENANCE.md`, `import-manifest.sha256`). The documentation count includes the retained
run-1 … run-6 logs and JUnit XML evidence, which is why it is much larger than the source change.

For comparison, plain `git status --short` collapses untracked directories and currently prints
**20 display rows**. That is a presentation count, not the candidate-file count used for review.

A previous handoff quoted "47 files" as the whole uncommitted set. That number was only the
non-`third-party` remainder at the time, quoted without qualification; the repository figure was 329
then. The measurement is taken with `git status --porcelain -uall`, which lists every untracked path
without touching the index, rather than `git add --dry-run`.

## Tests — executed (run 5, historical; run 6 is current)

Run 5 predates the packaging-premise revision, so its "8 native tasks" and licence-gate-in-assembly
entries describe a state that no longer exists; they are retained verbatim as history and corrected
in the run-6 sections of `evidence/index.md`.

| Check | Result | Evidence |
|---|---|---|
| `gradlew tasks` | PASS (exit 0) — 8 native tasks incl. `verifyRemoteInputDistributionLicence`; the two new `verification` test-support tasks register; all preserved tasks register | `evidence/r5-tasks.log` (historical: the task set changed after run 5 — seven current native tasks, no packaged-verification task, plus the regression assembly instance) |
| `gradlew compileJava compileTestJava` | PASS (exit 0) | `evidence/r5-compile.log` |
| SC-1662 JUnit tests | PASS (exit 0) — **46 tests, 0 failures, 0 errors, 0 skips** (`…ResolverTest` 16, `…LoaderTest` 10, `…ManifestTest` 9, `…SmokeVerifierTest` 11) | `evidence/r5-new-tests.log`, `evidence/junit-r5/**` |
| Helper fixture tests | PASS (exit 0) — **43 tests** | `evidence/r5-metadata-tests.log` |
| `gradlew checkstyleMain` | PASS (exit 0) — executed; project-wide warnings only | `evidence/r5-checkstyleMain.log` |
| `gradlew spotlessCheck` | FAIL (exit 1) — 16 pre-existing offenders; no Java source changed in this round | `evidence/r5-spotlessCheck.log` |
| `gradlew verifyRemoteInputDistributionLicence` | FAIL **as required** (exit 1) — the licence gate fails closed on the unresolved provenance marker | `evidence/r5-licence-gate.log` |
| `gradlew verifyRemoteInputDistributionLicenceMatrix` | PASS (exit 0) — **9/9** marker × licence-file states behaved as documented, including the pass case | `evidence/r5-licence-gate-matrix.log` |
| `bash -n` on all three shell scripts | PASS (exit 0) | `evidence/r5-shell-syntax.log` |
| Kotlin staging regressions (isolated) | PASS (exit 0) — pair identity binds the manifest; mismatched existing payload refused; displaced predecessor retained; legacy real-directory refused and survives; production output untouched | `evidence/r5-staging-regressions.log` |
| Shell staging regressions (`stage_artifact` exercised directly) | PASS (exit 0) — publish identity; existing-payload refusal; copied-temporary-pair refusal; predecessor retention; legacy real-directory refusal | `evidence/r5-staging-shell-regressions.log` |

The table above records the immutable run-5 bundle. A post-run-5 ABI review changed every native
C++ `bool` declaration from JNA `boolean` (32-bit) to explicit `byte` (one-byte), added a structural
signature/conversion regression, and reran the focused suites: **47 tests, 0 failures, 0 errors, 0
skips** (`…LoaderTest` is now 11). No retained run-5 file was rewritten.

Additionally exercised and observed during this change (not native):

- Drift rejection: `verify-sources` reports 278/278 files with tree identity
  `b727319f7289959019ecfc13313993312e92c03dccd88996f676b649c5d8ee0c` and `patch=none` on the
  unmodified tree; a declaration naming an unchanged file, a non-existent file or a surviving
  "removed" file is now rejected, as is an arbitrary `patchIdentity`.
- The JDK version string `openjdk version "17.0.20.1" 2026-08-18 LTS` round-trips through
  `write-manifest` and `json.loads` unchanged (asserted in the helper tests).
- Staging rejects a mutated artifact, a truncated manifest and a missing manifest, leaving the
  previously published payload and symlink intact with no leftover temporary directory or link.
- Packaged routing: `java -Dloader.main=…RemoteInputSmokeVerifier -cp application.jar
  org.springframework.boot.launcher…PropertiesLauncher --mode packaged` runs the verifier with no
  application startup output (run 2 evidence; unchanged mechanism).

## Remaining work / blockers (current state after run 6)

1. **OPEN — licensing.** No top-level RemoteInput license exists, and `kubo_injector` is GPL/LGPL.
   Under the revised scope this is accepted for the private PoC: the marker stays `unresolved`, no
   licence file is invented, the gate keeps failing closed, and RemoteInput is excluded from every
   packaged distribution. *Resolving* the licence remains a legal decision for a future story.
2. **OPEN — ABI execution.** Symbol existence is verified, and C++ `bool` uses an explicit
   one-byte binding with structural regression coverage. The smoke verifier proves loadability and
   export resolution only, by design: real input-call ABI belongs to the SC-1663+ lifecycle story
   unless the revised acceptance criteria explicitly require it.
3. **Windows verification — NOT RUN; non-blocking PoC waiver recorded.** Execute it later from
   `C:\Dev\IdeaProjects\ChromaScape-sc1662-native` using
   [`windows-verification-runbook.md`](./windows-verification-runbook.md). The 2026-09-17 waiver
   permits story completion but is never represented as a PASS.
4. Containerized native build (if used): the build runs in a rootless podman Fedora container, a
   clean-Fedora environment accepted for this story; the container image digest, Fedora version,
   compiler, CMake, Python, JDK and glibc identities are recorded in `evidence/index.md`.

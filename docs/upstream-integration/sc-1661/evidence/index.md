# SC-1661 — Evidence Index

This directory holds the baseline-validation evidence for SC-1661. The index exists so a reviewer
can inspect every retained run **without access to the original session, the worktree's `build/`
directory, or any temporary file**. Everything referenced here is committed in this repository.

## Durability and sanitisation

- **Tracked.** The `.log` files below are deliberately tracked. `.gitignore` ignores `*.log`
  repository-wide, so a single scoped negation was added at the end of the file:
  `!docs/upstream-integration/sc-1661/evidence/*.log`. No other `*.log` path is unignored.
- **Sanitised.** Every retained file was reviewed for credentials, authorization headers, account
  identifiers, private account information, machine-specific configuration and network details.
  A credential/account-identifier scan over the directory
  (`password|passwd|secret|token|api[_-]?key|authorization|bearer|PRIVATE KEY|ssh-rsa|@host.tld`)
  returns **no matches**. The files contain build output only: source paths, test names, JDK/Gradle
  banners and the local project paths the build itself prints.
- **Not tracked.** Gradle's own outputs (`build/reports/**`, `build/test-results/**`,
  `build/spotless/**`) are git-ignored and are **overwritten by the next run**. They are not
  evidence. Where a claim depends on data that only exists there, a durable summary or extracted
  set is retained here instead, and the source is named.
- **Not captured.** The candidate/reconciled runs were performed before this index existed and did
  not record wall-clock start/end times. Those fields are marked *not captured* rather than
  reconstructed. File modification times are deliberately not reported as run times.

## Revisions referenced

| Role | Full SHA |
|---|---|
| Original consolidation baseline | `583a05b91687d156b1baa52dbda2b219604c7b39` |
| Candidate baseline (SC-1661 branch tip before the carries) | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| **Reconciled code baseline** | `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Documentation / review tip | `283328b4125efc7a1f27c0e1455dda42fdebe55c` |
| Pinned upstream (`StaticSweep/main`, tag `v0.5.0`) | `5ea3b995f5d13e19c592a6399973fe7dadb5e656` |
| Upstream merge base | `46db1c462752d39bd0c04da630bfe5d35edc117a` |
| Intended PR target (`origin/integration/chromascape-consolidated-20260901`) | `471262bfeefa0c1d0a91a367227a3fd78586814e` |

Carries: `45f943facd72dee1a8d220d000f7d78b30fd35aa` (attributes), `966e0e3e47db71fe5d57629205efcb23cd11e026` (Fedora routing).

## Environment (identical for every run below)

| Item | Value |
|---|---|
| Worktree | `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc1661-reconciliation` |
| OS | Linux 6.19.10-300.fc44.x86_64 |
| Gradle | 8.14.2 (wrapper; **unchanged** — no wrapper upgrade) |
| Launcher JVM / toolchain | Corretto `17.0.20.1` (`JAVA_HOME=/home/ikiddoi/.jdks/corretto-17.0.20.1`) |
| `GRADLE_USER_HOME` | `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape/.gradle-home` |
| Docker | **Not available** |
| Local PostgreSQL | **Not available** (`localhost:5432`, `:5433` closed) |
| SC-967 vectors override | `-Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json` |

## Retained runs

Fields follow the required record: run id, code revision, working-tree state, command, environment,
time, result, tests, failure classification, artifact, integrity (see §Artifact integrity).

### C1 — candidate `./gradlew tasks`

| Field | Value |
|---|---|
| Code revision | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| Working tree | clean |
| Command | `./gradlew tasks --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 0** — build evaluates; all custom tasks register |
| Tests | n/a |
| Failure classification | n/a |
| Artifact | `candidate-tasks.log` |

### C2 — candidate `compileJava`

| Field | Value |
|---|---|
| Code revision | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| Command | `./gradlew compileJava --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 0** |
| Tests | n/a |
| Artifact | `candidate-compileJava.log` |

### C3 — candidate `checkstyleMain`

| Field | Value |
|---|---|
| Code revision | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| Command | `./gradlew checkstyleMain --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 0** — 3280 findings across 561 files, severity `warning` only |
| Failure classification | pre-existing (`warning` severity does not fail the task) |
| Artifact | `candidate-checkstyle-summary.log` (full 848 KB console log condensed; full report at run time: `build/reports/checkstyle/main.xml`) |

### C4 — candidate `spotlessCheck`

| Field | Value |
|---|---|
| Code revision | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| Command | `./gradlew spotlessCheck --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 1** — 16 files need formatting |
| Failure classification | pre-existing |
| Artifact | `candidate-spotless.log`; `candidate-spotless-checkstyle.log` (duplicate capture of the same unchanged failure — differs only in Gradle's reported duration); complete offending set in `candidate-spotless-offending-files-complete.txt` |

### C5 — candidate `test` (SC-967 gate, no override)

| Field | Value |
|---|---|
| Code revision | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| Command | `./gradlew test --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 1** — fails before any test runs: `Resolved SC-967 fixture is not a regular file: …/contracts/sc967/ed25519-vectors.json` |
| Tests | 0 executed |
| Failure classification | environmental/configuration |
| Artifact | **not retained** — the console output of this run was overwritten when the same file was reused for C6. The failure text is quoted here from the run; no log is claimed. |

### C6 — candidate `test` (with SC-967 override)

| Field | Value |
|---|---|
| Code revision | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| Command | `./gradlew test -Psc967VectorsPath=… --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 1** |
| Tests | **2250 executed / 110 failed / 13 skipped** |
| Failure classification | 92 environmental (34 Docker, 10 PostgreSQL, 48 ApplicationContext cascade) + 18 pre-existing assertion/behavior failures: 16 gameplay/provider and 2 other (see `deferred-findings.md` SC-1661-D2) |
| Artifact | `candidate-test.log`; complete failing-test identities in `test-failures-candidate.txt` |

### C7 — candidate `replayTest`

| Field | Value |
|---|---|
| Code revision | `4e0a3d5b56ae7d3d50bd4ce878b31dd541540a23` |
| Command | `./gradlew replayTest --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 1** |
| Tests | **280 executed / 12 failed / 0 skipped** |
| Failure classification | environmental — all 12 Testcontainers/Docker |
| Artifact | `candidate-replayTest.log`; complete failing-test identities in `replayTest-failures-candidate.txt` |

### R1 — reconciled `compileJava compileTestJava`

| Field | Value |
|---|---|
| Code revision | `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Command | `./gradlew compileJava compileTestJava --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 0** — the adapted launcher code compiles |
| Artifact | `reconciled-compile.log` |

### R2 — reconciled focused launcher suites

| Field | Value |
|---|---|
| Code revision | `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Command | `./gradlew test --tests '*<six launcher classes>' -Psc967VectorsPath=… --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 0** |
| Tests | **Historical execution count not retained; 175 source test methods. Superseded by V4's measured 175 executed / 0 failed / 0 skipped.** |
| Derivation | 175 = 3 + 28 + 9 + 26 + 96 + 13 `@Test` methods in the six classes at `966e0e3` (the console log does not print a count; the sum is a property of the tested revision, not of the log) |
| Artifact | `reconciled-launcher-tests.log` |

### R3 — reconciled `spotlessCheck`

| Field | Value |
|---|---|
| Code revision | `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Command | `./gradlew spotlessCheck --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 1** — 16 files need formatting |
| Failure classification | pre-existing; carried files contribute **zero** new violations |
| Artifact | `reconciled-spotless.log`; complete offending set in `reconciled-spotless-offending-files-complete.txt` |

### R4 — reconciled `test`

| Field | Value |
|---|---|
| Code revision | `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Command | `./gradlew test -Psc967VectorsPath=… --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 1** |
| Tests | **2255 executed / 110 failed / 13 skipped** |
| Failure classification | identical failing identity set to C6 |
| Artifact | `reconciled-test.log`; complete failing-test identities in `test-failures-reconciled.txt` |

### R5 — reconciled `replayTest`

| Field | Value |
|---|---|
| Code revision | `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Command | `./gradlew replayTest --console=plain` |
| Time (UTC) | not captured |
| Result | **exit 1** |
| Tests | **280 executed / 12 failed / 0 skipped** |
| Failure classification | identical failing identity set to C7 |
| Artifact | `reconciled-replayTest.log`; complete failing-test identities in `replayTest-failures-reconciled.txt` |

### V1 — reconciled `checkstyleMain` (final-code check)

| Field | Value |
|---|---|
| Code revision | `966e0e3e47db71fe5d57629205efcb23cd11e026` (worktree tip at run time `283328b` — docs only) |
| Working tree | clean |
| Command | `./gradlew checkstyleMain --console=plain --rerun-tasks` |
| Time (UTC) | not captured; Gradle reported 50s |
| Result | **exit 0** — 3291 findings across 563 files, severity `warning` only |
| Tests | n/a |
| Failure classification | 3280 pre-existing + **11 added** by the carried files (10 `MissingJavadocMethod`, 1 `MissingJavadocType`); not fixed — see below |
| Artifact | `reconciled-checkstyle-summary.log`; per-file delta in `checkstyle-per-file-delta.txt` |

**The 11 added warnings are recorded, not fixed.** They are `warning`-severity only (the task exits 0
and `check` does not fail; the baseline already carries 3280). Fixing them would move the pinned code
baseline off `966e0e3` and invalidate test evidence for a cosmetic change, so the baseline is left
faithful to the carry and the delta is disclosed instead.

### V2 — complete Spotless offending sets (both revisions)

| Field | Value |
|---|---|
| Code revision | candidate `583a05b91687d156b1baa52dbda2b219604c7b39`; reconciled `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Method | temporary detached worktrees at each revision, `./gradlew spotlessApply`, then `git status --porcelain` — this enumerates the **complete** offending set, which the `spotlessCheck` console output truncates ("Violations also present in 12 other files") |
| Result | **16 files at each revision; the two sets are identical** — `diff` is empty, and both files share SHA-256 `1501905b1ffd13f080f88c22a9e480b0d638679ab7a9d6a975458dcb57775e86` |
| Artifact | `candidate-spotless-offending-files-complete.txt`, `reconciled-spotless-offending-files-complete.txt` |

### V3 — focused review run: `Micro6RemoteLauncherServiceTest`

| Field | Value |
|---|---|
| Code revision | `966e0e3e47db71fe5d57629205efcb23cd11e026` |
| Working tree | clean |
| Command | `./gradlew test --tests '*Micro6RemoteLauncherServiceTest' -Psc967VectorsPath=… --console=plain --rerun-tasks` |
| Time (UTC) | **2026-09-14T11:56:14Z → 2026-09-14T11:56:49Z** |
| Result | **exit 0** — `BUILD SUCCESSFUL in 34s` |
| Tests | **3 executed / 0 failed / 0 skipped / 0 errors** |
| Failure classification | none |
| Artifact | `review-micro6-remote-launcher-tests.log`; durable result summary (the `build/test-results` XML is ignored and overwritten) in `review-micro6-remote-launcher-tests-summary.txt` |

This reproduces the independent review's run. It is a **single-class** check: it does **not** repeat
the 175-test focused launcher suite (R2) and must not be presented as one.

## Comparative claims and what supports them

| Claim | Support |
|---|---|
| No **additional** observation of a new ordinary-test failure | `test-failures-candidate.txt` vs `test-failures-reconciled.txt`: 110 identities each, `diff` empty |
| No additional replay-test failure | `replayTest-failures-candidate.txt` vs `replayTest-failures-reconciled.txt`: 12 identities each, `diff` empty |
| Same formatting violations | complete sets (V2), identical |
| Reconciled code compiles | C6/R1/R2 exit codes and logs |
| Focused launcher tests pass | V4: retained JUnit results, 175 executed / 0 failures / 0 skips / 0 errors |
| Final code passes `checkstyleMain` | V1 (exit 0, warnings only) |

**What is *not* claimed.** Database-, migration-, concurrency- and Docker-backed behaviour is **not**
verified: Docker and PostgreSQL are absent, so 92 of the 110 `test` failures and all 12 `replayTest`
failures are environmental, and **tests that never executed can leave an introduced
regression undetected**. Compilation plus unit-test success is not end-to-end Fedora qualification.

## V4 — six-suite independent rerun with retained JUnit results

Code baseline: `966e0e3e47db71fe5d57629205efcb23cd11e026`; worktree HEAD at invocation: `0387c4d`, clean. JDK/Gradle and fixture configuration as above. Command:

```bash
./gradlew test --tests '*BuilderGoalLauncherControlServiceTest' --tests '*BuilderGoalRunnerServiceTest' --tests '*Micro6OnlineClientRegistryServiceTest' --tests '*Micro6TargetPreparationServiceTest' --tests '*Micro6RemoteLauncherServiceTest' --tests '*Micro6ExecutorReadinessEvaluatorTest' -Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json --console=plain --rerun-tasks
```

Exit 0; Gradle duration 49s; 5 actionable tasks executed. JUnit suite timestamps: 2026-09-14T12:36:19.561Z through 2026-09-14T12:36:26.106Z (suite starts, not command start/end).
Measured XML totals: **175 tests, 0 failures, 0 errors, 0 skipped**.
Per-suite counts: launcher control 26; runner 96; readiness evaluator 13; registry 28; remote launcher 3; target preparation 9.

Retained in `launcher-junit/TEST-*.xml`: original testsuite attributes and all testcase elements; properties and system output omitted to avoid unnecessary runtime data. These are sanitized JUnit result extracts, not untouched full reports. Counts are verified against 175 testcase elements. Test names mentioning tokens/passwords describe assertions and contain no credential values.

## Artifact integrity (SHA-256)

```
b68f67e9b7d00023bd07e023b60a1bac911fb3cfbda55d39702cc64d31dd0f11  launcher-junit/TEST-com.chromascape.web.debug.buildergoal.service.BuilderGoalLauncherControlServiceTest.xml
35b366cb5db872f02d91f6bdb73ee9f7bb643a6f9753d3860714a938a7eaa656  launcher-junit/TEST-com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerServiceTest.xml
0e0ea1e5ff941866121a4de1efdd255f251a6e896f8d99a5791e0b6080ff2240  launcher-junit/TEST-com.chromascape.web.debug.buildergoal.service.Micro6ExecutorReadinessEvaluatorTest.xml
be0b17ed3858415369c95cb068d0bc3f8be7b534580a13ac0c98b69932c7ce7d  launcher-junit/TEST-com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryServiceTest.xml
d4ad0ec34b43b25b004b39b8893d2a293815d03ad1728fa99390ed4ec6468241  launcher-junit/TEST-com.chromascape.web.debug.buildergoal.service.Micro6RemoteLauncherServiceTest.xml
265b93f9aa560e696ca72431b45a75e76d8a67f2b0062de8b2f37beba8bef7a9  launcher-junit/TEST-com.chromascape.web.debug.buildergoal.service.Micro6TargetPreparationServiceTest.xml
90baeb34ac83cd64c0e9e9d8ed61b4d0c043027482e10afacf7903e3c30d37c4  candidate-checkstyle-summary.log
69f810b70b1f4460cf8b3e91a535bcab91ebf82a2b651f992260a5a2cfa27816  candidate-compileJava.log
d5aeab8ff3d662bcbc6d7e35a51a5f7d40aeeea25142ad6442a496994882fda7  candidate-replayTest.log
8824abf57fae4886ba8165bf7128f331272075a65c31ffbd58ed891e8673c6df  candidate-spotless-checkstyle.log
3089e4172ef454c47298441d9f1ea824408368df5acb73b52f440f5c0aef7e35  candidate-spotless-files.txt
e3c0ebc2ac26ccde47022d5885743326e6a48cb5c4210b0422b483ddbe03e6b6  candidate-spotless.log
1501905b1ffd13f080f88c22a9e480b0d638679ab7a9d6a975458dcb57775e86  candidate-spotless-offending-files-complete.txt
b9a7f1260c7b3ee70beb1528b20eb7534f50e949227a902a5a98c0286c24df37  candidate-tasks.log
fc2264637ce6f38cde830282b1236327bf29de43d127f19760b9d2cd22fc58d1  candidate-test.log
d5ab7c83ae17815405e024792b8a8ad015f69e399e6ad7e86197a8f98ca8200d  checkstyle-per-file-delta.txt
e10a63eafaa8bfe251602ee1a6f4760dcfc250cc8d07f319c315889cdc411d59  non-environmental-failure-identities.txt
25416e24f8876853d96f3f4360ed4ad6c8bf74717f3b5c8f79a7629bc874dd62  reconciled-checkstyle-summary.log
57cdce5fc91b4e6e1a4c835a2c32d4df5e542659e0e88556850af3c7de7b529a  reconciled-compile.log
d4056e0ed94521308f3efac5060325beb3885c1f45df1770cb5825cb823ec8db  reconciled-launcher-tests.log
448e686b0798766148437cb33f95b262c4b34820355e1d88ee323396a4c9e5b3  reconciled-replayTest.log
f9a258f07481cd5ba7ceda00991bc1fa539fddad0c8d9207094ad9eb818c326a  reconciled-spotless.log
1501905b1ffd13f080f88c22a9e480b0d638679ab7a9d6a975458dcb57775e86  reconciled-spotless-offending-files-complete.txt
109c038c94f942fff2423a5131fc6903061744d942cd652d810e5a031acedb68  reconciled-test.log
4fe7bdfb72a8cefbcdc28454dfa327326f541784979b3a14497eb981c6470408  replayTest-failures-candidate.txt
4fe7bdfb72a8cefbcdc28454dfa327326f541784979b3a14497eb981c6470408  replayTest-failures-reconciled.txt
c7d0c528d319af1befa0581a69b4f05a93193dd23abf744e15b63879dbb82e6e  review-micro6-remote-launcher-tests.log
9bd79d3638ca48b66743afec6aadb92319d3dd72058b6d5e06cc5f57d890086e  review-micro6-remote-launcher-tests-summary.txt
bc640ea5ae74f527c4b3a495b9501b447d762c3f66a0dd64b261b696e6f2cf1d  test-failures-candidate.txt
bc640ea5ae74f527c4b3a495b9501b447d762c3f66a0dd64b261b696e6f2cf1d  test-failures-reconciled.txt
```

`index.md` is excluded from its own table. Verify with
`sha256sum -c` after stripping omitted lines, or re-run `sha256sum *` in this directory.

## Reproducing the set

```bash
export JAVA_HOME=/home/ikiddoi/.jdks/corretto-17.0.20.1
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape/.gradle-home
V=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json

./gradlew checkstyleMain --console=plain --rerun-tasks
./gradlew spotlessCheck --console=plain
./gradlew test -Psc967VectorsPath="$V" --console=plain
./gradlew replayTest --console=plain
./gradlew test --tests '*Micro6RemoteLauncherServiceTest' -Psc967VectorsPath="$V" --console=plain --rerun-tasks
```

The complete Spotless set requires `spotlessApply` in a throwaway checkout followed by
`git status --porcelain`; `spotlessCheck` alone truncates its console list.

# SC-2150 Phase 0 — baseline freeze and host prerequisites

Run ID: `20261019T232216Z` (UTC)
Project: `C:\Dev\IdeaProjects\ChromaScape` (native Fedora checkout at `/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape`)
Felix task: `task_1792382767281_cd8dbu69`
Plan: `docs/builderflow/sc-2150/verification-and-acceptance-plan.md`

## Code freeze

| Item | Value |
|---|---|
| Git revision | `5a7c30f8369806be6d6a400fd1fd59a45a32b5ec` |
| Branch | `codex/sc-1991-client-replay-ai` |
| Tracked modified files | 81 |
| Untracked files (normal) | 346 |

The tree is deliberately dirty and was **not** reset, cleaned or stashed. The SC-2150
implementation is carried as uncommitted work in this checkout; no dedicated SC-2150
worktree exists (`git worktree list` checked).

### Baseline hash comparison

All 11 files fingerprinted in `remaining-review-verification.json` were re-hashed and
**match byte-for-byte** the previously verified baseline:

| File | SHA-256 | Match |
|---|---|---|
| MouseVideoProcessRunner.java | `932a47ca…b98a79` | yes |
| MouseVideoJobService.java | `27c112b6…65a457` | yes |
| MouseVideoImportService.java | `efd034ec…c2b4e8` | yes |
| MouseVideoDerivativeWorker.java | `1f0540c1…e6068479` | yes |
| MouseVideoJobRepository.java | `15a1c7ba…b0347f54` | yes |
| MouseVideoProcessRunnerTest.java | `f07564f8…2d604b` | yes |
| MouseVideoJobServiceTest.java | `75214a43…620df2` | yes |
| MouseVideoImportServiceTest.java | `3b3c9ad3…3cec79e` | yes |
| MouseVideoDerivativeWorkerTest.java | `61c8a3b5…2dcc93d5` | yes |
| MouseVideoMigrationTest.java | `482a83c2…6d34b9b` | yes |
| MouseVideoServicePostgresRaceTest.java | `d7b004e8…6ac258a` | yes |

Result: **PASS** — the implementation under verification is identical to the recorded
baseline, so a reproduction difference cannot be attributed to an unrecorded source edit.

## Host toolchain

| Tool | Observed |
|---|---|
| Default `java` | OpenJDK 25.0.4.1 (not used) |
| Verification JDK | `/home/ikiddoi/.jdks/corretto-17.0.20.1` → `17.0.20.1+10-LTS` |
| Docker | 29.7.2, cgroup v2, overlayfs; user in `docker` group |
| Python | 3.14.3 |
| ffmpeg / ffprobe | 8.0.1 |
| systemd user session | `running`, `XDG_RUNTIME_DIR=/run/user/1000` |
| cgroup | `cgroup2fs`, delegated controllers: cpuset cpu io memory hugetlb pids rdma misc dmem |

Note: the baseline manifest records `17.0.20.1+1`; the installed Corretto build is
`17.0.20.1+10-LTS`. Same version line, different build suffix — recorded, not adjusted.

## Isolation observation

An existing ChromaScape instance is already listening on `127.0.0.1:8090` (java pid
646547), and another listener occupies `8091`. The isolated test instance must not stop,
replace or attach to either. No process was touched in this phase.

## Disposition

- Baseline freeze: **PASS**
- Toolchain prerequisites: **PASS**
- Isolated instance + authenticated principal: **NOT RUN** (Phase 0 remaining)

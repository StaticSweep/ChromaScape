# SC-2150 candidate manifest (2026-11-03)

Frozen before the R7 broad verification. Everything below was read from the candidate worktree or
the deployed instance in this round, not from an earlier document.

## Git identity

|Item|Value|
|---|---|
|Worktree|`/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc2150-r1`|
|Branch|`codex/sc-2150-assisted-review-r1`|
|Candidate commit|`682d3326` (`SC-2150: record the owner's Route A approval in the acceptance amendment`)|
|Base|`origin/main` `7758d867`|
|Divergence|12 commits ahead of `origin/main`|
|Pull request|https://github.com/ikiddoi/ChromaScape/pull/105 (draft)|
|Changed files vs `origin/main`|48 (12 Java main, 7 Java test, 4 web asset, 1 migration, 3 JS/Python test, 12 scripts, 9 docs)|

## Runtime and deployment

|Item|Value|
|---|---|
|Runtime checkout|`/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-runtime-sc2150` (git worktree, detached)|
|Runtime commit|`0581f7e2` — advanced to the merge commit in R8|
|Launcher|`scripts/start-fedora-dev.sh` (tracked, mode `100755`), profile `fedora-development`, loopback only, port 8090|
|Configuration|main-checkout `.env.local`, symlinked into the runtime; previous copy retained at `build/sc2150-closeout/r3/env.local.bak-20261103`|
|Media root|`ChromaScape-runtime-sc2150/data/mouse-video`|
|Database|`127.0.0.1:5433/chromascape` (PostgreSQL 16.15), Flyway `V87`|

## Assisted analysis profile

|Item|Value|
|---|---|
|Algorithm|`visual/v1`|
|Cursor method|`cursor-template/v8`|
|Toolchain pin|`mouse-video-visual/1`|
|Template pack|`clip-arrow/v1`|
|Template bytes|private `ChromaScape-private/sc2150/clip-arrow.png`, SHA-256 `14cf2ac3cba64a16baeb8e14667c846cbd4141aebeaaaa74a999c91183929873`, hotspot `(0,0)`, `templateState=AVAILABLE`|
|Template provenance|`clip-arrow-provenance.json` — source `90c0ef48-…` (withdrawn group section 1), original digest `b3a2265…`, frame 3, crop `1356,724,12,19`, `independentQualification=false`|
|Sampling|10 fps target, 960 px sample width, 3600 max visual frames|
|Storage bound|`maxJobDiskBytes=1073741824` (512 MiB job image + 512 MiB private tmp), `jobVolumeBytes` reported as 536 870 912|
|Admission|`waitingSlots=8` per owner|
|Runtime budget|`analysisBudgetSeconds=1200`|
|Sandbox|restricted launcher: `systemd-run --user --scope`, `MemoryMax=2147483648`, `MemorySwapMax=0`, `CPUQuota=200%`, `TasksMax=64`, `bwrap --unshare-all`, 64 MiB tmpfs|
|YouTube|`youtubeEnabled=false`, `youtubeDownloaderConfigured=false`|

## Schema

|Item|Value|
|---|---|
|Migrations in this story|`V80`–`V87`; `V87` widens `mouse_video_jobs_stage_check` to the sample/tracking stages|
|Applied version|87 (Flyway validated 82 migrations, applied 1 on deployment)|
|Never rewritten|`V80`, `V83` (and every earlier migration)|

## Toolchain pins

|Tool|Version|
|---|---|
|Gradle wrapper|8.14.2|
|Java toolchain|17 (`/home/ikiddoi/.gradle/jdks/jdk-17.0.20.1+1`, OpenJDK 17.0.20.1)|
|Node|v22.23.1 (browser harnesses need `@playwright/test` + `@axe-core/playwright`, not declared in `package.json` on this branch)|
|Python|3.14.3|
|ffmpeg / ffprobe|8.0.1 (no `libx264`; harnesses select `libopenh264`)|
|PostgreSQL|16.15 in container `chromascape-postgres`, loopback 5433|

## Changed surfaces (by area)

|Area|Files|
|---|---|
|Analysis identity/profile|`MouseVideoProperties`, `MouseVideoAnalysisIdentity`, `MouseVideoCursorTemplate` (new), `MouseVideoCatalogService`|
|Worker/stages|`MouseVideoJob`, `MouseVideoAnalysisWorker`, `MouseVideoScheduledJobRunner`|
|Scheduling/admission|`MouseVideoSchedulingService`|
|Cleanup|`MouseVideoWorkspace`|
|Public errors|`MouseVideoPublicErrors`|
|HTTP surface|`MouseVideoController` (queue coverage restored, job timestamps)|
|Local operator principal|`LocalReplayPrincipalFilter` + its two tests|
|Migration|`V87__sc2150_analysis_stage_progress.sql`|
|Web assets|`mouse-video.js`, `mouse-video-review-core.js`, `mouse-video.html`, `mouse-video-review-guide.css`|
|Verification harnesses|`measure_assisted_analysis.py`, `qualify_assisted_ui.cjs`, `smoke_8090.py` (new), `qualify_access_lifecycle.py`, `qualify_browser.cjs`, `qualify_competing.py`, `qualify_restart.py`, `cursor_template_pack.py`, `start-fedora-dev.sh`|
|Docs|`remaining-plan-20261102`, `acceptance-amendment-assisted-first-20261102`, `assisted-profile-provisioning`, `verification-evidence-r2-20261103`, `r3-8090-restore-20261102`, `r4-native-status-20261103`, `r6-youtube-status-20261103`, `mouse-video-import`, `.env.example`|

## Explicit non-claims

- Assisted suggestions are detector output: not human truth, not sealed, not training-eligible.
- The 44 manual labels (session `606b14b6…`, `ABANDONED`) are preserved read-only; the withdrawn
  group `yt-NTe3PTTBhuU` stays excluded from independent evaluation.
- No native capture receipt exists for this candidate (R4 open).
- No YouTube acquisition route is proven (R6 deferred, disabled).

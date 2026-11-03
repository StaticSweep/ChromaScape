# SC-2150 — R2 assisted-profile execution, cancellation and recovery evidence (2026-11-03)

Host: Fedora/Linux. Candidate worktree
`/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape-sc2150-r1`, branch
`codex/sc-2150-assisted-review-r1`. Everything below was run against disposable loopback
instances and disposable `sc2150_*` databases; the 8090 deployment and the 18191 review
instance were left untouched and returned 200 at the end of the run.

This document records executed commands and observed results only. Assisted suggestions
remain detector output: they are not human truth, not sealed labels and never
training-eligible.

## Candidate under test

|Item|Value|
|---|---|
|Baseline commit (measurement start)|`c32d4275`|
|Commit that carries these receipts|`16399701` (plus `909a3ac5` for the defects below)|
|Analysis profile|`visual/v1`, `cursor-template/v8`, toolchain `mouse-video-visual/1`, pack `clip-arrow/v1`|
|Template|private `build/sc2150-assisted/clip-arrow.png`, SHA-256 `14cf2ac3cba64a16baeb8e14667c846cbd4141aebeaaaa74a999c91183929873`, hotspot `(0,0)`|
|Sampling|10 fps target, 960 px sample width, 3600 max visual frames|
|Schema|Flyway `V87` (see defect 1); migrations `V80`–`V87` for this story|
|Assisted media|private `build/sc2150-library/media/bytes/b3/b3a226568aaa0fb67261414c3fcea157a1bf7ce0df05fa468db2e95ad7b7e34d`, 19 168 031 B, 1920×1080, 1911 frames, 60.03 s|

## Defects found by this verification and fixed on the branch

1. **Assisted analysis could not run on a migrated database.** `MouseVideoJob` reports
   `ANALYSIS_SAMPLES`/`ANALYSIS_TRACKING`, but `mouse_video_jobs_stage_check` (V83) still
   allowed only the older vocabulary, so the first sample-stage progress update failed with
   `DataIntegrityViolationException` → `DECODER_FAILED`. Fixed additively in
   `V87__sc2150_analysis_stage_progress.sql`; V83 is not rewritten.
2. **The sample-extraction stage was reported after the decode**, so a long analysis looked
   stalled at `ANALYSIS`. `progress.report(STAGE_ANALYSIS_SAMPLES, 40)` now precedes
   `extractSamples`, which is what R2.7 asks for.
3. **A confirmed workspace close left the mountpoint directory behind** (one inode per
   execution, unbounded). `MouseVideoWorkspace.close()` now deletes the empty directory after
   the unmount and image deletion; a non-empty directory is preserved as evidence.
4. **`QUOTA_EXCEEDED` had no public message** and fell through to “The media could not be
   decoded or probed”, presenting storage exhaustion as malformed media. It now has its own
   bounded message; the regression is asserted end-to-end in the quota run below.
5. **The packaging commit dropped the `queue` coverage block** from `/observability`,
   breaking `MouseVideoControllerHttpTest#reconcileSettlesAProvenJob`. Restored.

## Focused package (real PostgreSQL, Docker enabled)

```bash
JAVA_HOME=/home/ikiddoi/.gradle/jdks/jdk-17.0.20.1+1 \
GRADLE_USER_HOME=/run/media/ikiddoi/dev-hdd/fedora-build-cache/gradle \
JAVA_TOOL_OPTIONS=-Dapi.version=1.44 \
SC2150_FA12_CORPUS=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/ChromaScape/build/sc2150-fa12 \
SC2150_FA12_OUT=build/sc2150-closeout/r2/fa12-out \
  ./gradlew test --tests 'com.chromascape.web.debug.tickdebugger.video.*' \
  --tests 'com.chromascape.web.debug.tickdebugger.video.**' \
  -Psc967VectorsPath=/run/media/ikiddoi/dev-hdd/dev/IdeaProjects/contracts/sc967/ed25519-vectors.json \
  -Psc967ReportDir=build/sc2150-closeout/r2/junit
```

Result: **346 tests, 0 failed, 0 skipped, 46 test classes** (`build/sc2150-closeout/r2/junit`).
`SC2150_FA12_CORPUS` points at the existing labelled corpus, so the opt-in corpus harness ran
instead of skipping. Safety-gate tests included and passing:

|Gate|Test|
|---|---|
|Decoder bounds|`MouseVideoVisualAnalyzerTest#samplingStepAndFrameCapBoundDecodedFrames`|
|Memory bound|`MouseVideoResourceLimitsTest#allocationAboveMemoryBudgetIsKilled`|
|Process ownership / descendants|`MouseVideoProcessRunnerTest#cancellationKillsRootAndTrackedDescendants`, `MouseVideoDerivativeWorkerTest#cancellationStopsOwnedRootAndDescendantProcesses`|
|Time/CPU/PID/swap scope|`MouseVideoWorkspaceTest#sandboxCannotBypassVolumeThroughRootOrDevAndTmpIsBounded`, `MouseVideoDerivativeWorkerTest` bounds|
|Network/secret denial|`MouseVideoImportServiceTest#probeFailurePublishesBoundedTextWithoutPrivateDiagnostics`, `MouseVideoControllerHttpTest#requestErrorsKeepBoundedPublicMessages`|
|Lease loss|`MouseVideoLeaseHeartbeatTest#unexpectedOwnershipFailureStopsTheWorkerAsLeaseLoss`|
|Workspace release|`MouseVideoWorkspaceTest#closingAConfirmedExecutionRemovesItsImageAndEmptyMountpoint` (new)|

## HTTP and browser mechanics (disposable instance on 18190)

```bash
python3 scripts/sc2150/qualify_http.py --base-url http://127.0.0.1:18190 --evidence /tmp/sc2150-r2/evidence
SC2150_BASE_URL=http://127.0.0.1:18190 SC2150_EVIDENCE=/tmp/sc2150-r2/evidence node scripts/sc2150/qualify_browser.cjs
```

- `qualify_http.py`: **PASS**, six generated fixtures (`cfr`, `vfr`, `rotated`,
  `cropped`, `bitstream-crop`, `burst-vfr`); frame index equals ffprobe PTS exactly; exact
  previews byte-identical to ffmpeg output; range 206/416; review 400/400/422/201 contract.
- `qualify_browser.cjs`: **PASS** — visible point, two-page conflict, draft retention,
  reload/save, source-use, history; region selection; suggestion-aware keyboard navigation,
  390 px layout, axe zero violations; export creation, immutable repeat GET, pinned
  previews, training ineligible; lost-worker cancellation stays reconciliation-required.

## Ownership, capacity and competing workers

```bash
# independent owner (18193, owner sc2150-fedora-b) and anonymous verification profile (18195)
python3 scripts/sc2150/qualify_access_lifecycle.py prepare  --database sc2150_r2_verify \
  --evidence /tmp/sc2150-r2/evidence --owner-port 18193 --anonymous-port 18195
SC2150_BASE_URL=http://127.0.0.1:18190 SC2150_EVIDENCE=/tmp/sc2150-r2/evidence node scripts/sc2150/qualify_browser.cjs
python3 scripts/sc2150/qualify_access_lifecycle.py capacity --database sc2150_r2_verify \
  --evidence /tmp/sc2150-r2/evidence --owner-port 18193 --anonymous-port 18195
python3 scripts/sc2150/qualify_competing.py --database sc2150_r2_verify \
  --evidence /tmp/sc2150-r2/evidence --server-output /tmp/sc2150-r2/server-b \
  --classpath /tmp/sc2150-runtime-classpath.txt --java .../java --port 18196
```

- 24 access checks: a different owner sees `404` and the anonymous profile `401` for every
  source-scoped route; no original checksum leaks.
- Expired claim fenced to `NEEDS_RECONCILIATION` with a higher generation; two unexpired
  claims preserved.
- Capacity: full owner gets `429` before any body byte, identical retry `202`, changed retry
  `409`.
- Competing: six previews scheduled across two enabled JVMs, each claimed exactly once, all
  `READY`; a disabled instance keeps reads (`200`) and refuses all three scheduling routes
  (`503`) without creating a job.

## Assisted measurement (R2.1/R2.8)

Instance: 18190, `CHROMASCAPE_MOUSE_VIDEO_MAX_JOB_DISK_BYTES=1073741824`, restricted launcher,
assisted profile above, media root `/tmp/sc2150-r2/media`.

```bash
python3 scripts/sc2150/measure_assisted_analysis.py --base-url http://127.0.0.1:18190 \
  --media .../b3a2265…b7e34d --media-root /tmp/sc2150-r2/media \
  --server-output /tmp/sc2150-r2/server-a --evidence /tmp/sc2150-r2/evidence
```

|Measurement|Observed|
|---|---|
|Samples decoded|597 PNGs (`597/1790` source frames, step 3)|
|Coverage string|`visual/v1: 1920x1080, 597/1790 source frames sampled (step 3), 630 observations, cursor=template`|
|Cursor observations|597 total — 592 `VISIBLE`, 5 `AMBIGUOUS`, 0 `UNSUPPORTED`|
|Max workspace bytes|112 676 415 B (107.5 MiB)|
|Max JVM `VmHWM`|1 302 622 208 B (1.21 GiB, `-Xmx768m` plus metaspace/direct buffers)|
|Elapsed (import + analysis)|489 515 ms; analysis alone ≈ 403 s|
|Budget|`CHROMASCAPE_MOUSE_VIDEO_ANALYSIS_BUDGET_SECONDS=1200` → within budget, no performance change made|
|Queue latency|≈ 117 s between scheduling and `RUNNING` (3 s scheduler poll, single instance)|
|Cleanup|workspace absent, `…ext2` absent, no `ffmpeg`/`fuse2fs` carrying the job id|
|Terminal|`SUCCEEDED`, analysis `COMPLETE`, observations artifact published|

Stage truthfulness observed on the wire:
`QUEUED → ANALYSIS/RUNNING 20% → ANALYSIS_SAMPLES 40% → ANALYSIS_TRACKING 45%…85% → DONE 100%`.

## Budget and concurrency accounting

|Item|Value|
|---|---|
|Per-execution bound|`maxJobDiskBytes = 1 GiB` = 512 MiB ext2 job filesystem + 512 MiB private `/tmp` tmpfs|
|Measured worst case|107.5 MiB used by the 597-sample assisted clip (≈ 10 % of the bound)|
|Admission slots|`waitingSlots = 8` per owner (analysis + preview + import share the bound)|
|Worst-case disk|8 × 1 GiB = 8 GiB|
|Host free space at run time|475 GiB (`509 617 930 240 B`)|

No setting change is required: 8 GiB worst case against 475 GiB free. If free space ever
drops below 8 GiB, set `CHROMASCAPE_MOUSE_VIDEO_WAITING_SLOTS=floor(free_bytes / 1 GiB)` in
`.env.local` and record the value.

## Cancellation receipts (R2.5)

```bash
python3 scripts/sc2150/measure_assisted_analysis.py … --cancel-at samples
python3 scripts/sc2150/measure_assisted_analysis.py … --cancel-at tracking
```

|Point|Observed cancel state|Terminal|Cleanup|Late publication|
|---|---|---|---|---|
|Sample extraction|stage `ANALYSIS_SAMPLES`, progress 40, 0 PNGs, workspace 8 761 B|`CANCELLED`|workspace/image absent, no job processes|analysis not `COMPLETE`, no observations artifact|
|Tracking|stage `ANALYSIS_TRACKING`, progress 50, 597 PNGs, 107.5 MiB|`CANCELLED`|workspace/image absent, no job processes|analysis not `COMPLETE`, no observations artifact|

Both runs prove released admission: a fresh schedule on the same source was accepted
(`202`) and immediately cancelled to a settled terminal state. Cancel bodies carry
`{"expectedRevision": <job.cancellationRevision>}`; the receipts show the returned status.

## Restart, lease loss and startup recovery (R2.6)

Server-a was crashed with `SIGKILL` while a preview was provably `QUEUED` and again while a
real analysis decoder was running. A second instance on the same database
(`--disable-workers`, port 18197) provides the cross-instance sweep that fences the expired
lease, mirroring a multi-instance deployment.

|Check|Observed|
|---|---|
|Queued preview before crash|`QUEUED`, generation 1|
|Queued preview after restart|`SUCCEEDED`, generation 2, bytes served|
|Active decoder before crash|real `ffmpeg` bound into the job workspace scope (`systemd-run --scope`, `bwrap`, `--die-with-parent`)|
|After lease expiry|`NEEDS_RECONCILIATION`, generation 3, lease owner cleared|
|Process scope|no remaining `ffmpeg`/`fuse2fs` for the job id|
|Partial publication|`0` artifact refs for the interrupted analysis|
|Old export|byte-identical before and after|
|Restart again with the fenced row|status stays `NEEDS_RECONCILIATION` (never auto-successful)|

Operator reconcile on the fenced job: `POST /jobs/f9b7112c-…/reconcile` → **200**, settled
`FAILED` / `INTERRUPTED`, `generation 3`, `cleanupReason` null (cleanup proof empty). A job
whose cleanup proof is not empty is never reconciled.

## Containment receipt

```bash
python3 scripts/sc2150/containment_probe.py --base-url http://127.0.0.1:18190 \
  --source <fixture sourceId> --analysis <fixture analysisId> --ordinals 0,5,10 \
  --out build/sc2150-closeout/r2/containment.json
```

`{"CONTAINED": 3}` — every preview ran inside its own `chromascape-video-*.scope` with
`MemoryMax`, `MemorySwapMax=0`, `CPUQuota=200%`, `TasksMax=64`, a private tmpfs and
`--unshare-all`. `MemoryPeak` is not exposed by the local `systemctl --user show`, so the
receipt records sampled `MemoryCurrent` per scope plus the measured JVM `VmHWM` above; that
limitation is labelled rather than replaced with an invented scope peak.

## Quota exhaustion receipt (R2.3)

Instance on 18192 with `CHROMASCAPE_MOUSE_VIDEO_MAX_JOB_DISK_BYTES=33554432` (32 MiB total →
16 MiB job filesystem), same media and profile, no `--allow-8090`:

```bash
python3 scripts/sc2150/measure_assisted_analysis.py --base-url http://127.0.0.1:18192 \
  … --expect QUOTA_EXCEEDED
```

|Check|Observed|
|---|---|
|Terminal|`FAILED`, stage `ANALYSIS_SAMPLES`, `errorCode=QUOTA_EXCEEDED`|
|Public message|“The job exceeded its configured storage limit; no output was published (reference …)” — not a decode failure|
|Peak before failure|13 657 312 B, 66 PNGs|
|Cleanup|workspace/image absent, no job processes, analysis `FAILED`, no observations artifact|
|Admission|released (fresh schedule accepted)|

## Evidence index

|Artifact|Path|
|---|---|
|Measurement, success|`build/sc2150-closeout/r2/evidence/measurement-success.json`|
|Cancellation, samples|`build/sc2150-closeout/r2/evidence/measurement-cancel-samples.json`|
|Cancellation, tracking|`build/sc2150-closeout/r2/evidence/measurement-cancel-tracking.json`|
|Quota exhaustion|`build/sc2150-closeout/r2/evidence/measurement-quota.json`|
|Restart/lease loss|`build/sc2150-closeout/r2/evidence/restart-results.json`|
|Operator reconcile|`build/sc2150-closeout/r2/evidence/reconcile-receipt.json`|
|Containment|`build/sc2150-closeout/r2/containment.json`|
|Access/capacity|`build/sc2150-closeout/r2/evidence/access-results.json`, `admission-results.json`, `lifecycle-fixtures.json`, `expiry-result.json`|
|Competing workers|`build/sc2150-closeout/r2/evidence/competing-results.json`, `disable-results.json`|
|Browser|`build/sc2150-closeout/r2/evidence/browser-results.json`, `browser-a11y.json`, `browser-trace.zip`|
|Focused JUnit|`build/sc2150-closeout/r2/junit`|

`build/` is git-ignored. These receipts are reproduced by the commands above on a clean
checkout plus `.env.local` and the private template/media prerequisites.

## Limitations and open items

- The assisted profile is a local working configuration; 597 samples at 10 fps is not a
  guarantee that every allowed input fits the 1 GiB bound.
- Queue latency on a single instance is up to the 3 s scheduler poll plus claim contention;
  the UI reports elapsed time from `createdAt`, not an invented percentage.
- The browser and measurement harnesses need `@playwright/test` and `@axe-core/playwright`,
  which `package.json` on this branch does not declare. They were run against the local
  `node_modules` already provisioned on this host; a fresh checkout must install them
  explicitly until the dependency is added to `package.json`.
- Cancelling frees admission immediately after confirmed cleanup; the fenced reconciliation
  path deliberately does **not** re-run work on its own.
- Detector output coverage (592/597 visible) is output coverage, not recall or accuracy.

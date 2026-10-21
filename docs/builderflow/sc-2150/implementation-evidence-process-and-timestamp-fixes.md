# SC-2150 — Implementation evidence: process lifecycle, leases and frame timestamps

Recorded 2026-10-19. This note covers only the five defects listed in the independent review. It
does not claim completion of SC-2150, browser qualification, real-PostgreSQL race qualification or a
successful public YouTube import. Both feature flags remain disabled.

Canonical project: `C:\Dev\IdeaProjects\ChromaScape`.
Felix task: `task_1792382767281_cd8dbu69` (`in_progress`, `spec_state=draft`).
Shortcut: https://app.shortcut.com/leeuwbot/story/2150 (In Progress, no transition emitted).

## Changed files

- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoProcessRunner.java`
  (new) — one owned execution abstraction with concurrent bounded stdout/stderr drains, an
  independent wall-clock watchdog, descendant tracking and verified termination. Returns a
  `halted` flag instead of presenting truncated output as complete.
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoLeaseHeartbeat.java`
  (new) — dedicated scheduler renewing the database lease every `leaseHeartbeatSeconds` (default
  5s, expiry 30s) for the exact job/generation/worker identity. Unexpected ownership failure
  cancels the worker; user cancellation is distinguished from lease loss.
- `MouseVideoDerivativeWorker.java` — all ffmpeg/ffprobe/encoder-discovery calls now go through the
  runner; frame index parses named `name=value` fields (`frame-index/v2`), preserves integer PTS and
  rational `timeBase`, and no longer treats `key_frame` as a timestamp.
- `MouseVideoImportService.java` — starts and closes the periodic heartbeat for the whole execution,
  uses a collision-resistant worker identity (instance UUID + PID), performs lease-aware progress
  writes, refuses publication after lease loss or cancellation, and schedules expired-claim
  reconciliation.
- `MouseVideoJobService.java` — lease-aware `progress` overload; startup reconciliation preserves
  unexpired foreign leases, including in-progress foreign cancellations; new
  `reconcileExpiredClaims` fences expired running claims for explicit reconciliation instead of
  assuming success or reclaiming blindly. Cancellation now bumps the generation while retaining the
  existing lease, and clears it only when CANCELLED is confirmed.
- `MouseVideoYouTubeAcquirer.java` — acquisition now uses the owned runner (independent deadline,
  concurrent drains, descendant cleanup); pin verification streams the binary hash with a cap.
- `MouseVideoCatalogService.java` — advertises `frame-index/v2`.
- Tests: `MouseVideoProcessRunnerTest` (new), `MouseVideoLeaseHeartbeatTest` (new),
  `MouseVideoYouTubeAcquirerTest` (new), and updated `MouseVideoDerivativeWorkerTest`,
  `MouseVideoJobServiceTest`, `MouseVideoImportServiceTest`.

## Tests

Commands actually run on Fedora with JDK 17.0.20.1:

```text
JAVA_HOME=<jdk-17> ./gradlew test -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json \
  --tests 'com.chromascape.web.debug.tickdebugger.video.*'
node --test src/test/js/mouse-video.test.js
python3 -m unittest discover -s src/test/python -p 'test_mouse_video*.py'
JAVA_HOME=<jdk-17> ./gradlew spotlessJavaCheck checkstyleMain checkstyleTest
git diff --check -- src/main/java/.../video src/test/java/.../video
```

Results:

- Java video suite: **75 tests, 6 skipped, 0 failures, 0 errors**. The six skips are the four
  migration tests and two real-PostgreSQL concurrency tests (no database was provisioned).
- New process regressions: silent-process deadline, root+descendant cancellation with real
  liveness assertions, and flooded-output bounded cleanup — **3/3 passed**.
- Derivative regressions: exact first/middle timestamps (`0.0`, `0.1`, `0.2`), integer PTS, key
  flag, preserved timebase, and cancellation killing a real root+child process — **2/2 passed**.
- Lease regressions: repeated renewal over multiple intervals, unexpected ownership failure,
  and user-cancellation exclusion — **3/3 passed**.
- Job lifecycle: live foreign running/cancelling leases preserved at startup, expired running
  claims fenced, and in-progress cancellation not fenced by the poller — **13/13 passed** (4 new).
- Provider process: stalled downloader deadline and authentication-failure mapping — **2/2 passed**.
- JavaScript: **6/6 passed**. Python: **2/2 passed**.
- Spotless and Checkstyle completed successfully. Checkstyle reports the repository's pre-existing
  warning baseline; no new violation was introduced.
- Whitespace check for the owned video paths passed.

Not rerun: real PostgreSQL race gates, browser/active-content matrix, release-host qualification,
public YouTube import, full repository regression.

## Remaining work

The five review defects are addressed in code with focused regressions. SC-2150 remains incomplete:

- Real PostgreSQL independent-session qualification for heartbeat/expiry/cancel/finalize races and
  concurrent registration/sweep.
- Browser qualification for upload, inspection, correction, cancellation wording and narrow/keyboard
  layouts.
- Qualified cursor template pack and held-out annotated-clip measurement.
- Correction/source-use completion and immutable export concurrency proof.
- Approved downloader pin plus a successful approved public-source YouTube import on the release
  host.
- Observability, reconciliation/rollback runbook and the complete original acceptance matrix.

Both `chromascape.mouse-video.enabled` and `chromascape.mouse-video.youtube-enabled` remain disabled.
No deployment, restart, workflow transition or Shortcut synchronization marker was produced by this
increment.

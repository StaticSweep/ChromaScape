# SC-2150 — F3/F4 independent-review remediation evidence

Date: 2026-10-20. Project: C:\Dev\IdeaProjects\ChromaScape.
Task: task_1792382767281_cd8dbu69, in_progress / draft. Shortcut sc-2150 unchanged; no synchronization marker emitted. This note records the RV1/RV2 repair and the thumbnail-fixture repair. It is not release acceptance. Felix note: `note_1792474597384_9725f428`.

## Defects addressed

- RV1 (F3): `MouseVideoMultipartReader.parseMetadata` performed `Files.readAllBytes` inside the same `catch (IOException)` used for JSON validation. A missing or unreadable metadata staging file therefore became `MalformedMultipartException`, which the controller maps to HTTP 400.
- RV2 (F4): `MouseVideoJobService.view` copied persisted `errorMessage` and `cleanupReason` verbatim. Jobs written before the safe-message change still disclosed private paths even though new writes were allowlisted.
- Coverage defect: `MouseVideoCatalogServiceTest.thumbnailWithActiveContentTypeIsRefused` mocked the obsolete source-scoped THUMBNAIL lookup and supplied no completed analysis. It passed on the earlier "no completed analysis" branch and never reached the active-content MIME guard.

## Changed files

- C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoMultipartReader.java
- C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobService.java
- C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoMultipartReaderTest.java
- C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobServiceTest.java
- C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoCatalogServiceTest.java
- C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\video\controller\MouseVideoControllerHttpTest.java

## Implementation

- F3: metadata bytes are now read before the JSON-validation try block. Only `JsonProcessingException` is converted to `MalformedMultipartException`; `NoSuchFileException` and every other filesystem read failure remain ordinary `IOException` instances and reach the controller's sanitized 5xx path.
- F3 cleanup: the upload controller still releases the intake reservation and recursively deletes the staging directory for generic I/O failures. A controller regression exercises this path and asserts both outcomes.
- F4: `view` now derives public text at the read boundary. FAILED and TIMED_OUT jobs return `MouseVideoPublicErrors.jobFailure(errorCode, jobId)`; NEEDS_RECONCILIATION returns `MouseVideoPublicErrors.reconciliation(jobId)` for both error and cleanup text. The stored raw columns are left untouched for private diagnostics and incident review.
- Thumbnail fixture: the test now creates a completed analysis, stubs the analysis-scoped FINALIZED THUMBNAIL artifact with `text/html`, and verifies the repository lookup so the MIME guard is demonstrably reached.

## Tests

JDK: Temurin 17.0.20.1+1. Docker API compatibility used process-local `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`. SC-967 vectors were resolved from the native Fedora checkout.

```text
JAVA_HOME=/home/ikiddoi/.jdk/jdk-17.0.20.1+1 ./gradlew --no-daemon test \
  -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json \
  --tests 'com.chromascape.web.debug.tickdebugger.video.*' \
  --tests 'com.chromascape.web.debug.tickdebugger.video.service.*' \
  --tests 'com.chromascape.web.debug.tickdebugger.video.controller.*' \
  --tests 'com.chromascape.web.MouseResearchPageTest' --rerun-tasks
```

Result: 24 suites, 151 tests, 0 failures, 0 errors, 0 skipped. This includes the real PostgreSQL 16 migration, concurrency and service-race suites. Baseline was 146 tests; the five added regressions are:

- `missingMetadataStagingFileRemainsAStorageFailure` — asserts `NoSuchFileException` and not `MultipartException`.
- `invalidMetadataJsonIsSeparateFromStorageFailures` — asserts `MalformedMultipartException` caused by `JsonProcessingException`.
- `storageReadFailureRemainsAServerErrorAndReleasesTheReservation` — drives the real controller/reader, asserts a plain `IOException` (not `MouseVideoException`), reservation release, and staging cleanup.
- `viewSanitizesLegacyStoredFailureDiagnostics` — stores a pre-fix raw path/message and asserts the public view contains only the bounded reference text.
- `viewSanitizesLegacyStoredReconciliationDiagnostics` — same for error and cleanup reconciliation text.
- `thumbnailWithActiveContentTypeIsRefused` — repaired fixture now reaches and verifies the MIME guard.

Additional checks:

- `node --test src/test/js/mouse-video.test.js src/test/js/mouse-recording.test.js`: 14/14 pass.
- `npm test`: 329/329 pass.
- `python3 -m unittest discover -s src/test/python -p test_mouse_video_probe.py -v`: 2/2 pass.
- `spotlessJavaCheck`: pass.
- `checkstyleMain`: build successful; warnings only (4,556 warnings, zero errors).
- Replayed the reviewer's retained `MouseVideoIndependentReviewProbeTest` against the fixed code: 4 tests, 0 failures, 0 errors, 0 skipped. `metadataFilesystemReadFailureRemainsAnInfrastructureFailure` and `existingPersistedFailureDoesNotExposeItsPrivatePath` now pass; both retained F2 probes still pass. The temporary probe source was removed again and the 24-suite/151-test baseline was rerun afterward.
- Fingerprint comparison against the reviewer's 75-file baseline shows exactly six changed files: the two application files and four test files listed above. No other fingerprinted source changed.

## Remaining work

- Independent re-review of RV1 and RV2 against this increment.
- Browser cancellation remains untested, as do full upload/playback/range/correction/export browser flows.
- The broader completion gates remain open: source-use controls, cursor qualification, approved YouTube pins, anonymous/independent-owner operator checks, lifecycle/restart/sweep/multi-instance coverage, required-host containment, and operations/rollback rehearsal.
- No checklist, workflow or Shortcut state is claimed complete by this note beyond the two implementation items whose evidence is listed above.

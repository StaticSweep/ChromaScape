# SC-2150 — F1–F5 remediation evidence

Date: 2026-10-20. Project: C:\Dev\IdeaProjects\ChromaScape.
Task: task_1792382767281_cd8dbu69, still in_progress / draft. Shortcut sc-2150 unchanged;
no synchronization marker emitted. This note records an implementation increment, not acceptance.

## Changed files

- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoCatalogService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/controller/MouseVideoController.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/entity/MouseVideoAnalysisRepository.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/entity/MouseVideoArtifactRefRepository.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoJobService.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/MouseVideoMultipartReader.java`
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/MouseVideoPublicErrors.java` (new)
- `src/main/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoImportService.java`
- `src/main/resources/templates/mouse-recording.html`
- `src/main/resources/static/js/mouse-video.js`
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoCatalogServiceTest.java`
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoJobServiceTest.java`
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/service/MouseVideoImportServiceTest.java`
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/MouseVideoMultipartReaderTest.java`
- `src/test/java/com/chromascape/web/debug/tickdebugger/video/controller/MouseVideoControllerHttpTest.java` (new)
- `src/test/js/mouse-video.test.js`

## Implementation summary

- F1: derivative media and artifact listing resolve against one explicit, owner/source-bound
  COMPLETE analysis. Newest completed analysis is selected by completedAt, createdAt and ID.
  `analysisId` can pin PROXY/THUMBNAIL playback. ORIGINAL remains source-scoped. Missing,
  incomplete, foreign and wrong-source analyses are refused without cross-analysis fallback.
- F2: job-service mutations lock and refresh the managed job row before writing, so a
  request-scoped stale entity cannot flush an old version after a worker settles first. A
  settled CANCELLED job returns HTTP 200; pending cleanup returns 202; stale revisions return 409.
- F3: the multipart reader now distinguishes malformed input (`MalformedMultipartException` → 400)
  from configured limit overflow (`MultipartLimitExceededException` → 413). Storage/filesystem
  IOExceptions remain ordinary server failures.
- F4: job failures use bounded allowlisted public messages with the job ID as correlation
  reference. Raw probe messages are logged privately and retained in the job workspace; they are
  not persisted into public job/source/export representations.
- F5: `mouse-recording.html` again states “Missing targets stay unknown” and preserves the
  coordinate-versus-target distinction.
- Player proxy selection in `mouse-video.js` is pinned to `selectedAnalysisId`; a proxy from a
  different analysis or a non-finalized proxy is not used.

## Tests run

Command (JDK 17, local Docker API workaround for Docker 29.7.2). The SC-967 fixture was
resolved from the native Fedora checkout; its canonical Windows path is
`C:\Dev\IdeaProjects\contracts\sc967\ed25519-vectors.json`.

```
./gradlew --no-daemon test \
  -Psc967VectorsPath=<SC-967 vectors fixture> \
  --tests 'com.chromascape.web.debug.tickdebugger.video.*' \
  --tests 'com.chromascape.web.debug.tickdebugger.video.service.*' \
  --tests 'com.chromascape.web.debug.tickdebugger.video.controller.*' \
  --tests 'com.chromascape.web.MouseResearchPageTest' --rerun-tasks
```

Result: 24 suites, 146 tests, 0 failures, 0 errors, 0 skipped. This includes the real
PostgreSQL service-race, migration and concurrency suites (PostgreSQL 16 Testcontainers).

The local Docker server reports API 1.55 and rejects the docker-java default 1.32. A temporary
`~/.docker-java.properties` with `api.version=1.44` was used for the run and removed afterwards;
no repository configuration changed.

Additional results:

- `node --test src/test/js/mouse-video.test.js src/test/js/mouse-recording.test.js`: 14/14 pass.
- `npm test`: 329/329 pass.
- `./gradlew spotlessJavaCheck`: pass.
- `./gradlew checkstyleMain`: build successful; warnings only, zero errors.
- `MouseResearchPageTest`: pass, including the previously failing “Missing targets stay unknown”
  assertion.

## Remaining work

- The F2 fix is verified at service/HTTP-boundary level, but the original failing server stack was
  not retained. A barrier-controlled request-persistence-context reproduction and real browser
  cancellation trace are still required.
- Browser acceptance remains unverified: upload, playback/seeking, ranges, cancellation, recovery,
  correction/history/conflict controls, export/download, accessibility and narrow viewports.
- Correction/source-use controls are not yet implemented in the workbench.
- Cursor tracking is not implemented. Approved licensed templates/held-out clips are absent, so
  the median ≤4 px, p95 ≤12 px, visible recall ≥90% and absent-span false-detection ≤1% gates
  cannot be claimed.
- YouTube qualification is blocked on approved downloader executable/runtime pins and an approved
  public clip.
- Anonymous 401 and independent-owner 404 operator checks have not run.
- Windows containment remains unqualified.
- Lifecycle, resource, restart, sweep, competing-instance and full native-regression selections
  beyond the executed video/native page scope remain to be rerun after feature completion.

No checklist item, Felix state or Shortcut state is claimed complete by this note.

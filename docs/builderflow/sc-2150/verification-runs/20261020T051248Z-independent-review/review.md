# SC-2150 — Independent F1–F5 code verification

Date: 2026-10-20. Project: C:\Dev\IdeaProjects\ChromaScape.
Felix task: task_1792382767281_cd8dbu69, in_progress / draft.
Reviewed implementation evidence: note_1792460297644_054e4383 and docs\builderflow\sc-2150\f1-f5-remediation-evidence.md.
Completion plan: docs\builderflow\sc-2150\post-verification-remediation-and-full-completion-plan.md.

## Acceptance results

Verdict: the five-defect increment is not fully accepted. Independent review confirmed two remaining defects (RV1 and RV2). F2 passed an added real-PostgreSQL retained-persistence-context service probe and an actual-controller MockMvc probe using the real job service. These are stronger evidence than the existing mocked controller tests, but not a browser or live servlet-server qualification.

### RV1 — P2: metadata filesystem failures become client errors (F3)

Location: src\main\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoMultipartReader.java:269–274.

parseMetadata performs Files.readAllBytes(path) inside the same catch(IOException) as JSON parsing. A missing/unreadable metadata staging file or other read failure becomes MalformedMultipartException. The controller maps that exception to 400 INVALID_REQUEST. This contradicts the intended separation between malformed caller input and genuine storage failures.

Independent fault probe invoked the real private metadata parser with a nonexistent metadata path: expected NoSuchFileException, observed MalformedMultipartException with the filesystem exception as its cause. The probe targets the storage-read branch directly; it does not pretend that a normal valid request spontaneously deletes its own metadata file.

Required correction: perform filesystem reads outside the JSON-validation catch, or catch only the relevant JSON parsing exception. Preserve real storage failures as sanitized 5xx. Add a regression that distinguishes storage read failure from invalid JSON and verifies cleanup.

### RV2 — P2: existing stored job errors still disclose private paths (F4)

Location: src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoJobService.java:391–405; public controller copies errorMessage unchanged.

New import-worker failures are allowlisted before persistence, which addresses newly written probe failures. However, the public JobView still copies job.getErrorMessage() and job.getCleanupReason() verbatim. Existing failed jobs recorded before this fix remain readable and keep the original disclosure.

Independent probe persisted a DECODER_FAILED job with a representative old diagnostic through the real job service into PostgreSQL, reloaded it via requireOwned, and passed it through the public view. errorMessage still contained /tmp/sc2150-media/bytes/private-file.

Required correction: enforce safe public failure/reconciliation messages at the read boundary (while preserving private diagnostics), or provide a complete reviewed legacy-data remediation. Test existing stored raw messages as well as new writes. Do not rewrite immutable export history casually.

### Positive verification

- F1: analysis selection, explicit pinning, original-digest verification, source/owner checks, no per-role fallback and player PROXY pinning were inspected. Existing focused tests passed; no additional functional defect was confirmed in this review. Full browser and multi-analysis live-server acceptance remain open.
- F2: with a request EntityManager bound across service transactions, cancellation first left a managed CANCELLING instance. A separate worker thread then committed settlement. The request's cached instance was asserted still stale before the second settlement. Both the service call and actual controller flow completed successfully; controller returned 200/CANCELLED. No sleeps were used to order the commit: the worker Future completed before the request continued. Database, JPA repositories, transaction manager and job service were real. Worker-registry cleanup and unrelated controller dependencies were mocked.
- F3: normal malformed-input and limit mappings passed the existing tests, but RV1 remains.
- F4: newly written probe failures passed existing safe-message tests, but RV2 remains.
- F5: restored uncertainty copy/semantics inspected; MouseResearchPageTest and recording JS selection passed.

### Additional test coverage weakness

MouseVideoCatalogServiceTest.thumbnailWithActiveContentTypeIsRefused still mocks the obsolete source-scoped THUMBNAIL repository lookup and provides no completed analysis. It now fails early for “no completed analysis,” so its passing assertion does not exercise active-content MIME refusal. Update the fixture to an analysis-scoped FINALIZED thumbnail and assert that the MIME guard is reached. This is a coverage defect; this review did not demonstrate a MIME-serving vulnerability.

## Tests

Independent reruns on Temurin 17.0.20.1+1:
- Original video + native-page selection: 24 suites, 146 tests, 0 failures/errors/skips. Includes 15 real PostgreSQL 16 migration/concurrency/service-race cases.
- node --test src/test/js/mouse-video.test.js src/test/js/mouse-recording.test.js: 14 passed.
- npm test: 329 passed, 0 failures/skips.
- Python probe unittest selection: 2 passed, 0 skips, using real FFmpeg/ffprobe.
- Additional review probes, final run: 4 tests, 2 passed, 2 failed, 0 errors/skips. Both F2 probes passed. RV1/RV2 probes failed and demonstrate the defects.
- spotlessJavaCheck: passed. checkstyleMain: successful, zero errors / 4,556 warnings.

The Java baseline was run with --rerun-tasks using the existing video/service/controller and MouseResearchPageTest selectors and the native SC-967 fixture. Docker API compatibility used process-local JAVA_TOOL_OPTIONS=-Dapi.version=1.44. No user-home or repository Docker configuration was changed.

The added probe class copied the existing PostgreSQL test harness into a temporary uniquely named test source. Only four named probe methods ran in the final selection. Its exact full source is retained here as MouseVideoIndependentReviewProbeTest.java; the temporary source was removed from src/test afterward. The retained reproduction source is review evidence, not an automatically discovered permanent regression suite.

Evidence files in this directory:
- source-sha256.json: fingerprints of 75 relevant source files, all rechecked unchanged after the final static checks.
- commands.json: exact baseline/probe selectors, static-check arguments and other executed commands; checkstyle-main.xml: retained static analysis report.
- baseline-result.json and baseline-junit: independently regenerated baseline results/XML.
- probe-junit: final four-probe XML with failures and stack traces.
- MouseVideoIndependentReviewProbeTest.java: final reproduction source.
- sc2150-independent-review-java.log, node.log, python.log, probes.log, probes-final.log and style.log: command output, prefixed consistently by sc2150-independent-review-.

Revision remained 5a7c30f8369806be6d6a400fd1fd59a45a32b5ec. Node v22.23.1; Python 3.14.3; FFmpeg/ffprobe 8.0.1. Existing dirty/untracked user work was preserved.

## Changed files

No application or permanent test source changes. Added only this independent-review evidence directory and a linked Felix documentation note. Temporary reproduction source was removed. All 75 original source hashes matched after probe removal; repeat verification accompanies final delivery.

## Remaining work

Repair RV1/RV2 and the thumbnail test fixture, retain meaningful permanent regressions, then rerun affected checks. Keep broader completion gates open: correction/source-use controls, cursor implementation and approved held-out qualification, approved YouTube pins/clip and acquisition qualification, anonymous/independent-owner operator checks, full real-browser acceptance, wider lifecycle/resource/restart/sweep/multi-instance coverage, required-host containment, complete native regressions, operations and rollback rehearsal.

The two successful F2 probes narrow the earlier persistence-context verification gap. They do not recreate the unavailable original pre-fix server stack or provide browser evidence. Local test principal/guard mocks do not establish authentication behavior.

## Risks

Existing disclosure persists for old error rows until RV2 is addressed. Misclassified filesystem faults make storage incidents look like caller errors. Green baseline counts alone do not close these branches. This review neither enables features nor establishes release readiness.

## Rollback

No application patch was applied, so no application rollback is required. Retain the evidence as an audit trail. Do not reset or clean the dirty tree. The process-local Docker property expires with the test JVM. Testcontainers manages the disposable PostgreSQL containers.

## Tracker reconciliation

No checklist items were checked, no workflow transition occurred and no Shortcut marker was emitted. Felix remains in_progress / draft. Existing Shortcut state is unchanged by this review.


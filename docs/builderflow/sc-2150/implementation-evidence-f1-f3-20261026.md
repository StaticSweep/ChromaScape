# SC-2150 Implementation Evidence — F1–F3 fixes (2026-10-26)

Project: `C:\Dev\IdeaProjects\ChromaScape`. Task: `task_1792382767281_cd8dbu69`.
Plan: `note_1792970439648_e46655f2`, local `docs\builderflow\sc-2150\corrective-plan-f1-f3-20261026.md`.
Reviewed findings: `docs\builderflow\sc-2150\review-r1-r4-20261026.md`.
Branch: `codex/sc-2150-mouse-video-import`; HEAD unchanged at `e1eb17cd34af79333cff3f61c79a5271937a38a3`.
Existing dirty files and all historical plans/checklists were preserved. No commit or push.

## Changed files

All paths below are relative to `C:\Dev\IdeaProjects\ChromaScape`.

- `src\main\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoCorrectionContract.java`
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoReviewService.java`
- `src\main\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoExportService.java`
- `src\main\resources\static\js\mouse-video.js`
- `src\main\resources\static\js\mouse-video-review-core.js`
- `src\main\resources\templates\mouse-video.html`
- `scripts\sc2150\labels_from_review_export.py`
- `scripts\sc2150\qualify_review_protocol.mjs`
- `scripts\sc2150\qualify_review_backend.mjs`
- `src\test\java\com\chromascape\web\debug\tickdebugger\video\MouseVideoCorrectionContractTest.java`
- `src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoReviewServiceTest.java`
- `src\test\java\com\chromascape\web\debug\tickdebugger\video\service\MouseVideoExportServiceTest.java`
- `src\test\js\mouse-video.test.js`
- `src\test\python\test_sc2150_labels_from_review_export.py`

Documentation: this note's local mirror, `verification-evidence-f1-f3-20261026.md`, and `corrective-plan-f1-f3-20261026.md` under `docs\builderflow\sc-2150`.

## Implemented plan

1. F1: provenance-bearing corrections use corrections/v2; the server validates and preserves UNKNOWN, BLIND_UNVERIFIED and DETECTOR_ASSISTED. Arbitrary INDEPENDENT claims are rejected. Legacy arrays without provenance keep corrections/v1 normalization and canonical digest (fixed golden regression). Existing JSON/contract columns store v2 without migration. History retains the normalized field. New v2 export review blocks pin contract, correction digest, assertion set and independenceVerified=false; legacy blocks/downloads retain their old form. Converter records per-label/document assertions and refuses all unverified held-out qualification, including legacy labels with missing provenance.
2. F2: a pending snapshot captures serialized body, URL, source, analysis, base revision, draft version, reason and fresh UUID before any await. Unknown outcomes retry exactly those bytes before later edits receive another UUID. Successful acknowledgements cover only the submitted version. Both ordinary and cumulative review saves use the serialized queue. Close/source/analysis switches flush pending/dirty work. Definitive client rejection permits a corrected new snapshot; 409 keeps the rejected snapshot until explicit base reload. Existing cumulative empty saves remain supported.
3. F3: suggestion exposure is monotonic per source, retained in memory and browser local storage across analysis changes/reopen. Storage failure is conservative. Exposed editors produce assisted labels/reasons even after enabling Blind. Fresh blind labels are BLIND_UNVERIFIED; the current protocol never certifies independent truth. The UI identifies blind mode as unverified. Existing overlay suppression and held-out first-render gating remain.

## Tests

- Complete JS suite: 367 passed, zero failures/skips.
- SC-2150 Python suites: 38 passed.
- Isolated JDK-17 Gradle execution: 37 passed (18 correction-contract, 16 review-service, 3 export-service), zero failures/errors/skips.
- Spotless Java check passed in private snapshot; only the six owned Java files were copied back after formatting.
- Scoped Checkstyle on three production and three test Java files completed successfully with 15 Javadoc warnings; no errors. Scoped whitespace and syntax checks passed.
- Real Chromium controlled-fault regression: lost committed response + later edit produces 3 POSTs, counts 2/2/3; first two bodies/UUIDs identical, third UUID new and base advanced. Exposure remains assisted after toggle/reload, fresh blind is unverified, unavailable storage is assisted; zero page errors.
- Actual Spring + PostgreSQL + Chromium: generated 1-second fixture upload/index/preview, UI label POST 201 under corrections/v2, same-body retry same revision, changed-body UUID reuse 409, independent claim 400, one persisted revision, pinned/repeatable export, older export byte-identical, zero page errors.
- Actual exported review/index converter: dev succeeds with independence_verified=false; heldout exits 1, explains missing trusted server session and writes no label output.
- An intermediate export compile caught two missing imports; corrected before the successful final Java build. Failed and successful logs are retained.

Evidence: `C:\Dev\IdeaProjects\ChromaScape\build\sc2150-fixes-20261026\`: source-hashes.json, js.log, python.log, gradle-verified.log, format.log, checkstyle.log, browser.log, backend.log, backend/result.json, backend/converter-results.json, revision/manifest/history/frame-index JSON, synthetic fixture and cleanup.json.
The disposable JVM was stopped and its dedicated database dropped. Normal application service and shared databases were not restarted.

## Remaining work

The authoritative server review-session, membership and exposure-ledger protocol remains unimplemented. Browser storage is conservative UI evidence, not durable authenticated protocol evidence; clearing it or another browser cannot establish independence. Pending snapshots survive retries in the open editor, not browser-process loss. No independent qualification is claimed or enabled by this increment.

Reopen CP25I5's complete session-separation obligation: overlay suppression is implemented, authenticated assisted/Blind sessions are not. CP25I3 now has exact retry evidence. F26 items cover this bounded correction only. All untouched CP25 packages (large queues/ranges, server sessions, further observations, corpus/detector qualification, provider isolation, release/native/Windows verification and delivery bundle) remain open. Full Java/video/native suites and held-out scoring were not rerun.

Felix stays in_progress / spec_ready. Whole-story in_review/done gates remain unmet. Shortcut remains In Progress; no new status transition or synchronization comment is appropriate, and none was sent.

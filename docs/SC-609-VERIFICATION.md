# SC-609 verification and review handoff

Complete implemented plan: [SC-609-DELIVERY-PLAN.md](SC-609-DELIVERY-PLAN.md). Operations and compatibility: [SC-609-CAPTURE-PROVENANCE.md](SC-609-CAPTURE-PROVENANCE.md).

## Delivery status — 2026-10-03

Micro6 PR #160 is merged into main at `77c758e91dda00fef16e3984cdb676d1741e5e63`; it includes #159's AccountBuilder lifecycle fixes. ChromaScape dependencies #44 (SC-260), #42 (SC-665) and #43 (SC-641) are merged. PR #45 is reconciled with their resulting main revision `5050b79d`, and merged as PR #45 at `48caed2b5b8501cc8a7cce41aa9d88e775456540`.

Felix task `task_1790971893615_dult4y03` was previously recorded in review. Felix writes and CI monitoring are skipped at the user's request; no new Felix state or synchronization success is claimed. The user revised the acceptance target to Fedora only. A passing Fedora live capture/recovery and browser check is sufficient for SC-609 completion; Windows qualification is outside this delivery scope. The user supplied Fedora 44 conditional PASS on 2026-10-04, conditional on integrating the two acceptance fixes; integration and focused regression results are recorded below. The user-declared PASS is used for runtime/API acceptance, with the explicitly unrun browser/video/multi-page checks retained as follow-up.

## Acceptance results

- Immutable profile/catalog/settings/requirements/run identity, SQL tamper rejection, inferred legacy assignment, finalized-session eligibility and incomplete-quality rejection: passed backend PostgreSQL tests.
- Canonical alignment, source/semantic validation, append-only frames, idempotent receipts and gap/conflict/finalized-disconnect behavior: passed backend tests.
- Main compatibility: preserve v2 wire checksum/durable receipt handling and screen-frame response capability alongside v3 negotiation/run context. Canonical v3 frames use their committed canonical ACK; legacy projection does not create a second receipt or validate a redacted frame as a v2 wire document.
- Two added WebSocket regressions verify exactly one durable ACK, retention of the original wire document, and preservation of the committed ACK when legacy projection fails.
- Current development-schema convergence includes six migrations and inherited V6 mappings for main's entities, columns and integrity constraints; no released legacy migration was edited in this reconciliation.
- Catalog pagination, authoritative recording identity, video-only access, cursor loading, safe settings text and null/empty behavior: passed backend and Node/JSDOM tests.
- Fedora live runtime/API capture and recovery: user-reported PASS on the acceptance fixes. Browser cross-check unrun; video and catalog multi-page tests N/A for the test dataset. These limits remain follow-up evidence gaps. Windows qualification is outside the revised acceptance scope; no physical power-loss certification is claimed.

## Tests

Actually run on this Fedora host using JDK 17:

- `aiPlayerSchemaTest`: 21 passed, zero failures/errors/skips.
- `aiPlayerCatalogTest`: 44 passed, zero failures/errors/skips.
- `recordingProvenanceTest`: 141 passed, zero failures/errors/skips, including the two new ACK regressions.
- Combined Gradle invocation: BUILD SUCCESSFUL; production and scoped test compilation succeeded. This is not the full default backend test tree.
- `npm ci --ignore-scripts`: locked dependencies installed successfully, zero audit vulnerabilities reported; lockfile unchanged.
- `npm test`: 281 passed, zero failures, three opt-in checks skipped (284 total).
- Scoped Google Java formatting and whitespace verification are included in the integration commit checks.

Current schema identity: `sc609-current-v1`; six-migration fingerprint exported from an agent-owned disposable PostgreSQL 16 database:
`sha256:62fe48dd10464cb0ffb057557b25884c61c24a5282b95e4925daec1a64423a5e`.
No shared/user database was reset. PostgreSQL test storage was disposable, including tmpfs for the final scoped suites; these tests do not certify physical crash durability.

Micro6 evidence supplied by the user for pushed revision `57f3dfd`: JDK 17 compilation successful, 25 focused tests passed, full client suite 1649 tests across 189 classes passed with zero failures/errors/skips, and repository hygiene clean. Those producer checks were not rerun in this session. CI monitoring and Felix were skipped as requested. No application or client was launched for this verification.

The successful final runs follow integration fixes for schema mappings, catalog hash fixtures and the correct JDBC schema selection. An initial Node run could not load JSDOM; installing locked dependencies corrected it. Earlier failing runs are not used as passing evidence.

## Risks

Fedora live coordinated recovery passed in the user report; real browser verification remains a follow-up gap. Windows qualification is outside the revised delivery scope. Current producer origin/script-intent/transition telemetry remains UNKNOWN or unsupported where not observed, keeping incomplete evidence training-ineligible. Scoped tests do not claim the full backend test tree passes. Existing disposable development databases may require an explicitly authorized reset to adopt changed development migration history; no such user database operation was performed.

## Rollback

Revert the coordinated merge commits with the correct mainline while preserving stored frames, audit records and retained producer outboxes. Retain additive nullable data columns. Development-schema rollback/reset needs explicit authorization against a confirmed disposable database. This work does not deploy or restart services.

## Remaining work

The user-declared Fedora runtime/API PASS and integration of the acceptance fixes satisfy the revised delivery acceptance. Browser display, video and catalog multi-page verification remain explicit follow-up checks, not claimed passes. Windows is outside scope. The Fedora automated checks above are complete. Downstream telemetry adapters must supply observed completeness before canonical training can be enabled. Felix remains untouched at the user's request.

## Fedora live acceptance report — 2026-10-04

The user supplied conditional PASS evidence from Fedora 44 on pushed fix branches, not unmodified main. Task `FISHING_TRAINING`, profile `49efbd1c-813a-4b12-af15-72f698db02b0` version 1; settings hash prefix `sha256:6999`, semantic contract `sha256:6d94`, catalog `sha256:d11f`. Preserve complete hashes in the capture evidence; these abbreviations are not standalone identity proof.

| Step | Reported result | Evidence / limit |
| --- | --- | --- |
| Stack startup | PASS | Runbooks; backend on port 18090 and fixed-build client on Fedora 44. |
| Profile-bound capture | PASS | Run `bcfa520b`, entry 609005, session `de80bbd8`, recording `capture_26d689d9` (row 1); profile/hashes match dispatch. v3/ACK1 inferred from enforced profile-bound construction, not a captured negotiation response. |
| Recording discovery | API PASS | Correct account/task/settings/hash; 306/306 frames paged. Video unavailable, video behavior N/A; catalog multi-page N/A with three rows. Browser display was not checked. |
| Connection and client restart | PASS | Run `de9292c0`, session `194d5521`, recording `capture_2f191913` (row 3); server interruption recovered 360→518 contiguous frames. Client restart and same-reference redispatch retained original identity and 1…527 contiguous frames, without duplicate frames or extra video. |
| Finalization / eligibility | PASS | First run explicit stop finalized; recovery run finalized on transport-error grace expiry. EXPLICIT_STOP/DISCONNECT, dropped 0, no gaps; CAPTURE_INCOMPLETE remains training-ineligible. |
| Provenance and frame APIs | API PASS | Provenance 8/8 and 5/5 field checks, origin CAPTURED; ordered 306/306 and 527/527 frame traversal. Browser cross-check unrun. |

Acceptance-found fixes:

- Micro6 `a186070b1f`: normalize Gson integral doubles in forwarded run context so profile version 1 is not rejected as 1.0. User reported four unit tests passed. Applied without code changes to current main as `a2ef08df9c` in PR #161; four focused client tests actually rerun here passed, including production/test compilation.
- ChromaScape `8f03213f`: register the existing local-development principal filter for recording APIs. Isolated from migration changes in PR #52. Four focused config/filter tests actually rerun here passed with zero failures/errors/skips.
- Separate PR #51 resolves duplicate V32/V33 versions by renaming the catalog/provenance resources to V61/V62. SQL bytes are unchanged and version scan found no duplicates. Existing Flyway history must be reviewed; no repair or database reset was performed.
- PR #53 updates the startup convergence resource reference to V62; one focused classpath-resource regression actually rerun here passed and verifies the packaged startup SQL. Together with four producer and four principal/config/filter tests, nine focused regressions passed with zero failures/errors/skips.

Reported unrelated issues remain: FishingTrainingTask off-client-thread AssertionError after approximately three minutes, five connection losses, ReplayException mapped to HTTP 500, and an ignored Fedora environment file containing a stale client PID. These are not reported as fixed by SC-609. The testing session restored the client profile and retained test-state evidence. The backend was left on the fix branch at port 18090 and the client logged out. This closeout does not restart or alter those processes/configurations.

The automated suites above plus this report establish Fedora runtime/API recovery on the fixes. Browser verification remains explicitly unrun; no blanket six-step browser PASS is claimed. The user explicitly declared Fedora acceptance PASS conditional on the two fixes, while identifying the API-only checks and N/A cases. That declared result is the runtime/API acceptance basis; browser verification remains follow-up rather than an asserted pass. Windows remains outside this delivery scope. CI monitoring and Felix remain skipped.

## Fedora manual acceptance

Use the merged `main` revisions of ChromaScape and Micro6 on Fedora, your normal authorized client setup and a test recording. Keep the retained outbox intact. Run the checks against a known task/settings selection; an unbound legacy session cannot establish v3 recovery acceptance.

1. Start ChromaScape and the Micro6 client on Fedora through their existing runbooks. Open `http://127.0.0.1:8090/debugger` and the replay/recording view.
2. Start a profile-bound capture with a known task and settings. Confirm negotiation selects tick version 3 and ACK version 1. Record the recording ID, session/run ID, task, settings and settings hash.
3. Let frames arrive. Confirm the recording appears under the correct account/task, shows the selected settings/hash, and loads additional catalog pages. Verify video-only recordings remain visible when present.
4. Interrupt only this test capture's connection. Reconnect, then restart only its test client to exercise retained-outbox recovery. Confirm pending frames drain under the original run/session identity; sequence numbers never reset, duplicate replay does not create duplicate frames, and no extra video capture starts.
5. Stop the capture. Confirm it finalizes. After the interruption, quality remains incomplete and training-ineligible; missing telemetry stays unknown. Recovery must not turn the interrupted recording into complete training evidence.
6. Inspect `GET /api/recordings/v1/sessions/{recordingId}/provenance` and `GET /api/recordings/v1/sessions/{recordingId}/frames?afterSequence=0&limit=200` using your normal authenticated access. Confirm immutable task/settings/context and ordered frame sequences agree with the UI. Save relevant logs and any failure codes.

Report PASS/FAIL for each step, both main commit IDs, the Fedora version, recording/session IDs and log references. For future reruns, report any failed/untested step explicitly. The 2026-10-04 user-declared PASS accepts runtime/API results with browser/video/multi-page limits documented above. This is a controlled connection/process-restart check; it does not require a machine power cut.

## Changed files

Canonical ChromaScape project:

- `C:\Dev\IdeaProjects\ChromaScape\build.gradle.kts`
- `C:\Dev\IdeaProjects\ChromaScape\docs\SC-609-CAPTURE-PROVENANCE.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\SC-609-DELIVERY-PLAN.md`
- `C:\Dev\IdeaProjects\ChromaScape\scripts\generate-behavior-contract.py`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\ImitationDatasetBuilderService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\prototype\DatasetExample.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\prototype\FishingActionNormalizer.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\prototype\FishingDatasetBuilderService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\prototype\FishingStateExtractor.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\routine\config\AiRoutineOperatorWebConfig.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\routine\service\CaptureRecordingGateway.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\aiplayer\schema\AiPlayerCurrentSchema.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\controller\DebugSessionController.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\controller\TickReplayController.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\dto\SessionStartRequest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\dto\SessionStartResponse.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\dto\TickBatchMessage.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\service\DebugSessionService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\service\TickStreamIngestionService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\websocket\TickStreamWebSocketHandler.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\integration\micro6\service\Micro6CanonicalHash.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\controller\RecordingController.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\dto\RecordingStartRequest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\entity\RecordingSession.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\BehaviorRunContextService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\BehaviorSemanticContract.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\CanonicalFrameConsumers.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\CanonicalFrameStore.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\CanonicalObservation.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\CanonicalTickFrame.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\ProvenanceErrorAdvice.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\ProvenanceException.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\RecordingProvenance.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\RecordingProvenanceController.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\provenance\RecordingProvenanceConvergence.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\service\RecordingCatalogPager.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\recording\service\TaskDetectionService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\replay\playback\ReplayPlaybackCatalogRepository.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\replay\playback\ReplayPlaybackController.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\replay\playback\ReplayPlaybackService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\replay\playback\dto\ReplayPlaybackCatalogEntry.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\contracts\behavior-semantic-v1.json`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\contracts\replay\replay-playback-catalog-v1.schema.json`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-current-identity.properties`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\ai-player-current\V5__recording_provenance.sql`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\db\migration\V33__recording_provenance.sql`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\static\js\recording-catalog.js`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\templates\replay.html`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\aiplayer\schema\AiPlayerCurrentSchemaTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\recording\provenance\CanonicalTickFrameTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\recording\provenance\ProvenancePostgresTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\recording\provenance\RecordingProvenanceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\recording\provenance\SessionCaptureProvenanceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\recording\provenance\SessionStartWireProvenanceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\recording\provenance\VideoCatalogAccessTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\recording\service\TaskDetectionServiceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\replay\playback\ReplayPlaybackServiceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\js\recording-catalog.test.js`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\js\replay-page.test.js`

- `C:\Dev\IdeaProjects\ChromaScape\docs\SC-609-VERIFICATION.md`

Coordinated Micro6 project:

- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\BehaviorCaptureOutbox.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\ChromaScapeWebSocketClient.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\DebugRecorderPlugin.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\TickBatchMessage.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\AccountBuilderPlugin.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\DebugRecorderIntegrationBridge.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\Micro6BuilderExecutorServer.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\resources\contracts\behavior-semantic-v1.json`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\debugrecorder\BehaviorCaptureOutboxTest.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\debugrecorder\BehaviorSessionStartProvenanceTest.java`

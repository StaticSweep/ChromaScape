# SC-609 verification and review handoff

Complete implemented plan: [SC-609-DELIVERY-PLAN.md](SC-609-DELIVERY-PLAN.md). Operations and compatibility: [SC-609-CAPTURE-PROVENANCE.md](SC-609-CAPTURE-PROVENANCE.md).

## Delivery status — 2026-10-03

Micro6 PR #160 is merged into main at `77c758e91dda00fef16e3984cdb676d1741e5e63`; it includes #159's AccountBuilder lifecycle fixes. ChromaScape dependencies #44 (SC-260), #42 (SC-665) and #43 (SC-641) are merged. PR #45 is reconciled with their resulting main revision `5050b79d`, for submission directly to main.

Felix task `task_1790971893615_dult4y03` was previously recorded in review. Felix writes and CI monitoring are skipped at the user's request; no new Felix state or synchronization success is claimed. SC-609 remains In Review because the requested delivery targets are both Fedora and Windows, and native Windows recovery qualification is outstanding.

## Acceptance results

- Immutable profile/catalog/settings/requirements/run identity, SQL tamper rejection, inferred legacy assignment, finalized-session eligibility and incomplete-quality rejection: passed backend PostgreSQL tests.
- Canonical alignment, source/semantic validation, append-only frames, idempotent receipts and gap/conflict/finalized-disconnect behavior: passed backend tests.
- Main compatibility: preserve v2 wire checksum/durable receipt handling and screen-frame response capability alongside v3 negotiation/run context. Canonical v3 frames use their committed canonical ACK; legacy projection does not create a second receipt or validate a redacted frame as a v2 wire document.
- Two added WebSocket regressions verify exactly one durable ACK, retention of the original wire document, and preservation of the committed ACK when legacy projection fails.
- Current development-schema convergence includes six migrations and inherited V6 mappings for main's entities, columns and integrity constraints; no released legacy migration was edited in this reconciliation.
- Catalog pagination, authoritative recording identity, video-only access, cursor loading, safe settings text and null/empty behavior: passed backend and Node/JSDOM tests.
- Windows-specific locking, ACLs, atomic replacement, process restart and power-loss qualification: unrun. Fedora automated tests establish shared behavior, not native Windows or physical power-loss behavior. Real browser acceptance and a live client/server capture remain unrun.

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

Native Windows qualification, real browser acceptance and live coordinated recovery remain outstanding. Current producer origin/script-intent/transition telemetry remains UNKNOWN or unsupported where not observed, keeping incomplete evidence training-ineligible. Scoped tests do not claim the full backend test tree passes. Existing disposable development databases may require an explicitly authorized reset to adopt changed development migration history; no such user database operation was performed.

## Rollback

Revert the coordinated merge commits with the correct mainline while preserving stored frames, audit records and retained producer outboxes. Retain additive nullable data columns. Development-schema rollback/reset needs explicit authorization against a confirmed disposable database. This work does not deploy or restart services.

## Remaining work

Qualify recovery on native Windows and complete the unrun live/browser acceptance checks required by the delivery plan, then reconcile acceptance evidence before marking SC-609 Done. The Fedora automated checks above are complete. Downstream telemetry adapters must supply observed completeness before canonical training can be enabled. Felix remains untouched at the user's request.

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

# SC-609 verification and review handoff

Complete implemented plan: [SC-609-DELIVERY-PLAN.md](SC-609-DELIVERY-PLAN.md). Operations and compatibility: [SC-609-CAPTURE-PROVENANCE.md](SC-609-CAPTURE-PROVENANCE.md).
Felix task `task_1790971893615_dult4y03` is in review. Backend draft PR: https://github.com/ikiddoi/ChromaScape/pull/45 (source commit `9f5151be`). Shortcut SC-609 review synchronization succeeded with marker `[felix-sync:task_1790971893615_dult4y03:review]`, comment activity-1946. Coordinated producer draft PR: https://github.com/ikiddoi/micro6/pull/160 (commit `caa19d25de`). This handoff distinguishes backend/UI verification from unrun native checks. Both projects use branch `codex/sc-609-recording-provenance`; ChromaScape stacks on SC-641, Micro6 on main.

## Acceptance results

- Immutable exact profile/catalog/settings/requirements and run identity: backend PostgreSQL tests exercise snapshot preservation and reject direct SQL tampering.
- Legacy/incomplete eligibility: explicit audited INFERRED assignment, finalized-session and complete-quality gates; no silent promotion of legacy evidence.
- Alignment/source/semantic conformance: frozen frames validate session, sequence, source tick, UUID events, complete contract and human/provider distinctions. Missing observation fields remain null.
- Capture quality: durable append-only frame persistence, idempotent receipts, gap/conflict rejection and finalized disconnect evidence tested on PostgreSQL. Native overflow/restart fixtures are authored but unrun.
- Producer propagation: actual accepted settings/task-entry identity carried through executor, bridge, recorder and SESSION_START; statically reviewed only.
- Catalog: JSONB, duplicate-time account/task keyset traversal and recording-row identity authority tested with PostgreSQL; video-only access regression covered.
- UI: cursor loading, groups, safe settings text, null/empty distinction and video-only visibility covered with Node/JSDOM. Real browser inspection unrun.

## Tests

- `recordingProvenanceTest`: 126 passed, zero failures/errors/skips.
- `aiPlayerSchemaTest`: 21 passed, zero failures/errors/skips.
- `aiPlayerCatalogTest`: 44 passed, zero failures/errors/skips.
- `npm test`: 240 Node/JSDOM tests passed, zero failures/skips (latest UI run).
- Frozen backend compilation, scoped `spotlessApply`/`spotlessCheck`: passed. Scoped `checkstyleMain`: task succeeded with 306 advisory warnings.
- Both staged Git diffs passed whitespace checks. Generated semantic artifacts are byte-identical; new native Java fixtures/outbox parsed by the formatter without compiling Micro6.
- Final combined Gradle run: BUILD SUCCESSFUL; scoped suites only, not the full default test tree.

The final run follows corrected failures: procedural SQL splitting on fresh convergence, a Mockito varargs fixture cast, account assertions that expected fabricated identity, and an inconsistent test catalog snapshot. Earlier partial/failing runs do not substitute for the final result.
Current schema fingerprint was exported against an agent-owned PostgreSQL16 database after all five migrations:
`sha256:a70940b0d3fa05cd67cc255e4b2f0222198a0107134cd1da0c581489206d1ba7` (`sc609-current-v1`). No shared/user database was reset.

Micro6 compilation/tests were NOT RUN. Its AGENTS.md states: “Do not compile or test this project unless the user explicitly requests that exact action in the current request.” Native DTO/outbox fixtures, static review and formatting are not a test pass. No client, application or server was launched.

## Risks

The current producer knows menu dispatches but lacks measured human/automation origin, complete script-intent and transition-verification telemetry. It reports UNKNOWN/unsupported evidence and remains training-ineligible. Native compile/API compatibility, Windows atomic-write/process-restart/power-loss behavior and real browser acceptance remain unverified. Scoped checks do not claim that the repository's entire existing test tree passes. Checkstyle warnings remain advisory under existing configuration.

## Rollback

Revert coordinated application/producer commits, retain additive nullable columns, immutable frames and audit records, and preserve retained producer outboxes. Current development-schema version rollback requires an explicitly authorized disposable reset. No merge or deployment is performed here.

## Remaining work

Run explicitly authorized native module tests/compile and Windows recovery checks, then reconcile remaining acceptance/verification evidence before marking the story done. Downstream telemetry capability adapters must supply observed completeness before canonical training can be enabled.

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

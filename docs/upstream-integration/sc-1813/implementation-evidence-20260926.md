# SC-1813 Implementation Evidence — 2026-09-26

## Changed files

Paired isolated PRs: ChromaScape #38 and Micro6 #156. Server base 0fcf71f4; client base a784cfa57a. Client implementation commit e3a2f20de4 plus restored source-mode commit 8f61c5b6b4. Existing dirty runtime checkouts remain untouched.

- `C:\Dev\IdeaProjects\ChromaScape\docs\BUILDER_GOAL_LAUNCHER_CONTRACT.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-sync\synced\sc-1813\20260925T191601Z--sc-1813--reconcile-task--task-creation.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-sync\synced\sc-1813\20260925T191706Z--sc-1813--create-note--delivery-plan.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-sync\synced\sc-1813\20260925T192632Z--sc-1813--create-note--implementation-evidence.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\builderflow\felix-sync\synced\sc-1813\20260925T193341Z--sc-1813--create-note--compile-deploy-verification.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\upstream-integration\fedora-closeout\20260926.md`
- `C:\Dev\IdeaProjects\ChromaScape\docs\upstream-integration\sc-1813\implementation-plan.md`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\utils\core\screen\capture\TargetFrameRuntime.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\utils\core\screen\capture\ViewOnlyFrameSource.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\utils\core\screen\capture\X11DesktopFrameSource.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\ViewportPipelineDebugController.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\buildergoal\service\Micro6TargetRelaunchService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\dto\ScreenFrameCapability.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\dto\ScreenFrameMessage.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\dto\SessionStartResponse.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\service\ClientFrameMetrics.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\debug\tickdebugger\websocket\TickStreamWebSocketHandler.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\observe\ObserveController.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\observe\ObserveException.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\observe\ObserveService.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\java\com\chromascape\web\viewport\WebsocketViewport.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\static\js\debugger.js`
- `C:\Dev\IdeaProjects\ChromaScape\src\main\resources\templates\debugger.html`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\utils\core\screen\capture\TargetFrameRuntimeTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\utils\core\screen\capture\X11DesktopFrameSourceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\buildergoal\service\Micro6TargetRelaunchServiceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\debug\tickdebugger\websocket\TickStreamWebSocketHandlerScreenFrameTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\observe\ObserveControllerTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\observe\ObservePipelineIntegrationTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\observe\ObserveServiceTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\java\com\chromascape\web\viewport\WebsocketViewportTest.java`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\js\debugger-workspace.test.js`
- `C:\Dev\IdeaProjects\ChromaScape\src\test\resources\contracts\screen-frame-v1.json`
- `C:\Dev\IdeaProjects\micro6\docs\fedora-closeout\streaming-draft-20260926.md`
- `C:\Dev\IdeaProjects\micro6\launch-buildergoal-client.ps1`
- `C:\Dev\IdeaProjects\micro6\runelite-client\build.gradle.kts`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\ChromaScapeWebSocketClient.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\DebugRecorderConfig.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\DebugRecorderPlugin.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\debugrecorder\ScreenFrameCapture.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\Micro6BuilderExecutorServer.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\main\java\net\runelite\client\ui\DrawManager.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\debugrecorder\ChromaScapeWebSocketClientTest.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\debugrecorder\ScreenFrameCaptureTest.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\Micro6BuilderExecutorSourceModeTest.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\java\net\runelite\client\plugins\microbot\accountbuilder\integration\builder\SourceManifestEndpointSecurityTest.java`
- `C:\Dev\IdeaProjects\micro6\runelite-client\src\test\resources\contracts\screen-frame-v1.json`
- `C:\Dev\IdeaProjects\micro6\scripts\remote-launcher\launcher.py`
- `C:\Dev\IdeaProjects\micro6\scripts\remote-launcher\test_launcher_contract.py`

## Tests

- ChromaScape focused Java suite: 191 cases, 180 passed, 11 platform-dependent skips, no failures/errors. Includes 24 screen-frame tests, 42 existing socket lifecycle tests, launcher lifecycle, Observe/source arbitration, binary viewport, recording round-trip and replay contract regressions.
- Spotless apply/check and Checkstyle passed as configured. Checkstyle reports 3678 warnings across 624 files; this is not a warning-free claim.
- Micro6 focused client Java suite: 73 passed; dedicated source endpoint security suite: 4 passed. Client compile succeeded.
- JavaScript debugger/replay suite: 246 passed, 3 skipped, no failures.
- Python remote-launcher suite: 7 passed.
- Linux --contract-version returns genuine v3 fields. Normal and authoritative Gradle task graph dry runs passed; only authoritative includes generateAllTaskSourceBundles.
- Shared capability fixtures compared byte-for-byte. Micro6 repository hygiene check passed. Both diffs pass whitespace checks.

Commands used Java 17 Gradle launcher with configured test toolchains. Server command: ./gradlew spotlessApply spotlessCheck checkstyleMain test --tests '*TickStreamWebSocketHandler*Test' --tests '*Observe*Test' --tests '*TargetFrameRuntimeTest' --tests '*X11DesktopFrameSourceTest' --tests '*Micro6TargetRelaunchServiceTest' --tests '*WebsocketViewport*Test' --tests '*UnifiedFrameCaptureSourceFenceTest' --tests '*RecordingConfigInitialFrameTest' --tests '*ScreenManagerWindowFrameCaptureTest' --tests '*RecordingServiceRoundTripTest' --tests '*ReplayReportContractTest' -Psc967VectorsPath=../contracts/sc967/ed25519-vectors.json --no-daemon --console=plain.

Client commands: ./gradlew :client:test with ScreenFrameCaptureTest, ChromaScapeWebSocketClientTest, Micro6BuilderExecutorSourceModeTest, ClientLoaderLaunchWorldTest, DebugRecorderFirstTickTest filters; separately ./gradlew :client:sc810SourceTests --tests '*SourceManifestEndpointSecurityTest'. The source security class is excluded from ordinary test and was verified via its dedicated task. node --test src/test/js/*.test.js; python3 -m unittest discover -s scripts/remote-launcher -p 'test_*.py'.

## Remaining work

Final paired in-game/browser and clean-stop acceptance remains unverified. User confirmation of the earlier viewport does not certify the reconciled runtime. Original server/client remain running; this delivery did not replace them. Windows PowerShell execution was unavailable on Fedora. Hosted Actions budget exhaustion blocks CI execution; no CI pass is claimed. No RPM recertification or full-repository test pass is claimed.

## Acceptance results

Automated evidence covers identity rejection, bounded work and JPEG allocation, stop/reconnect/disable fencing, frame priority, source arbitration, old-peer tick compatibility, no-subscriber diagnostics and launcher mode selection. Real advancing in-game pixels and browser UI acceptance remain open. This is implementation evidence, not a completed live Verification Evidence gate.

## Risks

Source lifecycle/launcher changes need paired deployment. Authoritative mode still requires real configured bundles and credentials; tests use hermetic signed fixtures. Windows-specific skips and live checks remain explicit. The existing runtime uses earlier source, not these commits.

## Rollback

Revert the paired merge commits and restart only their owned processes using repository runbooks and preserved ignored configuration. Keep the existing dirty trees, database identities and source credentials intact.

## Tracker reconciliation

Felix task task_1790373482776_czm675c9j reached spec_ready then in_progress with normal validation. Shortcut sc-1813 synchronized to In Progress; planned/start markers are comments 1814/1815. Historical outbox records were restored idempotently as superseded references, not current acceptance evidence. Live acceptance must remain unchecked until performed.

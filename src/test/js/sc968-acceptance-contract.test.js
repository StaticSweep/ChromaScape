const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../..');
const runtime = fs.readFileSync(path.join(root, 'scripts/sc968/Acceptance.Runtime.ps1'), 'utf8');
const entry = fs.readFileSync(path.join(root, 'scripts/sc968-acceptance.ps1'), 'utf8');
const finish = fs.readFileSync(path.join(root, 'scripts/finish-sc967-sc968.ps1'), 'utf8');
const evidence = fs.readFileSync(path.join(root, 'scripts/sc968/Acceptance.Evidence.psm1'), 'utf8');
const common = fs.readFileSync(path.join(root, 'scripts/sc968/Acceptance.Common.psm1'), 'utf8');
const micro6 = fs.readFileSync(path.join(root, 'scripts/sc968/Acceptance.Micro6.psm1'), 'utf8');
const application = fs.readFileSync(path.join(root, 'src/main/resources/application.yml'), 'utf8');
const routineController = fs.readFileSync(path.join(root,
  'src/main/java/com/chromascape/web/aiplayer/routine/controller/RoutineController.java'), 'utf8');
const isolatedPatchWorker = fs.readFileSync(path.join(root,
  'src/main/java/com/chromascape/web/aiplayer/routine/service/IsolatedPatchWorker.java'), 'utf8');
const operatorWebConfig = fs.readFileSync(path.join(root,
  'src/main/java/com/chromascape/web/aiplayer/routine/config/AiRoutineOperatorWebConfig.java'), 'utf8');
const managedSeedPlan = JSON.parse(fs.readFileSync(
  path.join(root, 'src/test/resources/sc968/micro6-managed-stabilization-plan.json'), 'utf8'));

test('SC-968 runtime requires Assistant acceptance and a real RuneLite capture', () => {
  assert.match(runtime, /Wait-AssistantProposal/);
  assert.match(runtime, /assistant\/proposals\/\$proposalId\/accept/);
  assert.match(runtime, /acceptedProposal\.status -ne 'DRAFT_CREATED'/);
  assert.match(runtime, /Set-Sc968RuneLiteEnvironment/);
  assert.match(runtime, /Require-CaptureIdentity \$runeliteSession 'RUNELITE_CAPTURE'/);
  assert.match(runtime, /models\/\$modelId\/shadow-runs/);
  assert.doesNotMatch(runtime, /session_id=\[string\]\$secondRecordingState\.session_id/);
});

test('SC-968 login prompts wait until each native client has initialized', () => {
  assert.match(runtime,
    /function Wait-CaptureClientReady\(\[string\] \$StatusFile, \[int\] \$TimeoutSeconds, \[int\] \$ProcessId\)/);
  assert.match(runtime, /Get-Process -Id \$ProcessId -ErrorAction SilentlyContinue/);
  assert.match(runtime, /exited before publishing its capture status sidecar/);
  for (const [statusFile, processId, phase] of [
    ['baselineStatusFile', 'micro.Pid', '1/3'],
    ['candidateStatusFile', 'candidateMicro.Pid', '2/3'],
    ['runeliteStatusFile', 'runelite.Pid', '3/3'],
  ]) {
    const ready = runtime.indexOf(`Wait-CaptureClientReady $${statusFile} $runtimeTimeout $${processId}`);
    const prompt = runtime.indexOf(`LOG IN NOW (${phase})`);
    assert.ok(ready >= 0, `${statusFile} readiness wait is missing`);
    assert.ok(prompt > ready, `phase ${phase} prompt precedes native client readiness`);
  }
});

test('SC-968 allows its owned candidate registry to compile before probing it', () => {
  assert.match(runtime,
    /\$registryStartupTimeout = \[int\]\(EnvOr 'SC968_REGISTRY_STARTUP_TIMEOUT_SECONDS' '600'\)/);
  assert.match(runtime,
    /-AcceptedStatusCodes @\(404\) -TimeoutSeconds \$registryStartupTimeout/);
});

test('SC-968 keeps interactive clients available for asynchronous operator response', () => {
  assert.match(runtime,
    /\$runtimeTimeout = \[int\]\(EnvOr 'SC968_RUNTIME_TIMEOUT_SECONDS' '7200'\)/);
});

test('SC-968 PowerShell helpers do not shadow the read-only automatic PID variable', () => {
  assert.doesNotMatch(runtime, /\[int\]\s+\$Pid\b/i);
  assert.match(runtime, /Wait-LogMarkers\(\[string\] \$LogPath, \[int\] \$ProcessId/);
});

test('SC-968 candidate evidence uses and validates the signed nested manifest identity', () => {
  assert.match(runtime, /Field \$candidateManifest 'candidate'/);
  assert.match(runtime, /Field \$candidateIdentity 'certification_hash'/);
  assert.match(runtime, /Field \$candidateIdentity 'signature'/);
  assert.match(runtime, /Registered candidate signature is not valid base64/);
  assert.doesNotMatch(runtime, /\$candidate\.certification_hash/);
  assert.doesNotMatch(runtime, /\$candidate\.signature/);
});

test('SC-967 isolated candidate clones support tracked Windows long paths', () => {
  assert.match(isolatedPatchWorker,
    /request\.micro6Root\(\)\.toAbsolutePath\(\)\.normalize\(\)/);
  assert.match(isolatedPatchWorker, /ISOLATED_SOURCE_NOT_FOUND/);
  assert.match(isolatedPatchWorker, /"--path-format=absolute", "--git-common-dir"/);
  assert.match(isolatedPatchWorker, /ISOLATED_SOURCE_REPOSITORY_NOT_FOUND/);
  assert.match(isolatedPatchWorker,
    /List\.of\("git", "clone", "--no-checkout", "--config", "core\.longpaths=true", "--no-hardlinks"/);
  assert.match(isolatedPatchWorker, /ISOLATED_CHECKOUT_FAILED/);
  assert.match(isolatedPatchWorker, /resolveSibling\("contracts"\)\.resolve\("java"\)/);
  assert.match(isolatedPatchWorker, /CAPTURE_CONTRACTS_NOT_FOUND/);
  assert.match(isolatedPatchWorker,
    /"-PaiRoutineCaptureContractsDir=" \+ captureContracts/);
  assert.match(isolatedPatchWorker,
    /":client:compileJava", ":client:sc967CandidateCheckstyleMain"/);
  assert.match(isolatedPatchWorker,
    /":client:sc967CandidateCheckstyleTest"/);
  assert.match(isolatedPatchWorker, /":client:sc967CandidateTest"/);
  assert.match(isolatedPatchWorker, /"diff", "--check"/);
  assert.match(isolatedPatchWorker,
    /String text\(\) \{\s*return new String\(snapshot\(\), StandardCharsets\.UTF_8\);/);
  assert.match(isolatedPatchWorker,
    /String tail\(\) \{\s*return new String\(snapshot\(\), StandardCharsets\.UTF_8\)\.replaceAll/);
  assert.match(isolatedPatchWorker, /isGradleCoordinationFile\(relative\)/);
  assert.match(isolatedPatchWorker, /isGradleRuntimePath\(relative\)/);
  assert.match(isolatedPatchWorker, /"GRADLE_RO_DEP_CACHE"/);
  assert.match(isolatedPatchWorker, /resolve\("caches"\)/);
  assert.match(isolatedPatchWorker, /endsWith\("\.lock"\) \|\| value\.endsWith\("\.lck"\)/);
  assert.doesNotMatch(isolatedPatchWorker, /:runelite-client:/);
  assert.match(isolatedPatchWorker, /DosFileAttributeView/);
  assert.match(isolatedPatchWorker, /dos\.setReadOnly\(false\)/);
  assert.match(runtime, /\$workerTemp = Join-Path \$OutputRoot '\.worker-temp'/);
  assert.match(runtime, /SetEnvironmentVariable\('TEMP',\$workerTemp,'Process'\)/);
  assert.match(runtime, /SetEnvironmentVariable\('TMP',\$workerTemp,'Process'\)/);
  assert.match(runtime, /'CHROMASCAPE_LOG_LEVEL','TEMP','TMP'/);
});

test('SC-968 Micro6 captures bind certified stabilization and source provenance', () => {
  assert.match(runtime, /Initialize-Sc968ManagedCaptureIdentity/);
  assert.match(runtime, /-ManagedIdentity \$baselineManagedIdentity/);
  assert.match(runtime, /-ManagedIdentity \$candidateManagedIdentity/);
  assert.match(runtime, /-Name 'baseline' -RunIndex 0/);
  assert.match(runtime, /-Name 'candidate' -RunIndex 1/);
  assert.match(runtime, /micro6-builder\/v2/);
  assert.match(runtime, /SC968_SOURCE_MANIFEST_HASH/);
  assert.match(runtime,
    /SetEnvironmentVariable\('BUILDER_GOAL_MICRO6_PROJECT_DIR',\$Micro6Root,'Process'\)/);
  assert.match(runtime, /'MICRO6_ROOT','BUILDER_GOAL_MICRO6_PROJECT_DIR','MICRO6_EXPECTED_ARTIFACT_NAME'/);
  assert.match(micro6, /:client:micro6StabilizationHarness/);
  assert.match(micro6, /Push-Location \$Micro6Root/);
  assert.match(micro6, /\$seedExitCode = \$LASTEXITCODE/);
  assert.match(micro6, /--args=\$arguments" 2>&1 \| Out-Host/);
  assert.match(micro6, /certification_status -ne 'CERTIFIED'/);
  assert.match(micro6, /SC968_EXECUTOR_INSTANCE_ID/);
  assert.match(micro6, /fishing-source-manifest\.json/);
  assert.match(micro6, /sourceManifest\.client_build_id -ne \$micro6Revision/);
  assert.match(micro6, /SourceSnapshotId = \$sourceSnapshotId/);
  assert.doesNotMatch(micro6, /stabilization_manifest_hash/);
  assert.doesNotMatch(runtime, /stabilization\/ingest-report/);
});

test('SC-968 certified fixture identity matches the pass executor contract', () => {
  assert.equal(managedSeedPlan.executorRef, 'fixture:sc968-managed-capture-v1');
  assert.equal(managedSeedPlan.buildId, 'fixture-build-fishing-v1');
  assert.equal(managedSeedPlan.evidence.mode, 'NOT_APPLICABLE');
  assert.deepEqual(managedSeedPlan.evidence.required, []);
});

test('certified report bundles retain the raw servlet stream for bounded parsing', () => {
  assert.match(application, /servlet:\s*\r?\n\s+multipart:\s*\r?\n\s+enabled:\s*false/);
});

test('managed run creation uses capture authentication, not the operator credential', () => {
  assert.match(operatorWebConfig, /\/api\/v1\/ai-routine\/runs\/from-stabilization\/\*\*/);
  assert.match(routineController,
    /@PostMapping\("\/runs\/from-stabilization\/\{attemptId\}"\)[^\n]+@RequestHeader\(value="Authorization",required=false\)[^\n]+authorize\(authorization\)/);
});

test('SC-968 entrypoint and manifests require all five runtime evidence documents', () => {
  assert.doesNotMatch(evidence, /Import-Module[^\n]+Acceptance\.Common\.psm1/);
  assert.match(common, /return '\(detached\)'/);
  assert.match(common, /RedirectStandardError = \$true/);
  assert.match(common, /\$previousErrorActionPreference = \$ErrorActionPreference/);
  assert.match(common, /\$global:LASTEXITCODE = \$null/);
  assert.match(entry, /leeuwbot_go_vet[^\n]+\$ErrorActionPreference = 'Continue'/);
  assert.match(entry, /SC968_RUNELITE_LAUNCH_SCRIPT/);
  assert.match(entry, /\$env:SC968_CAPTURE_CONTRACT_VERSION = \$lock\['module_version'\]/);
  assert.match(entry, /'baseline','assistant','runelite','candidate','shadow'/);
  assert.match(entry, /-RuneLiteRoot \$RuneLiteRoot/);
  assert.match(evidence, /assistant = \$Runtime\.assistant/);
  assert.match(evidence, /runelite = \$Runtime\.runelite/);
  assert.match(evidence, /sc967-demo-manifest\/v1/);
});

test('SC-968 one-command wrapper uses real local roots and creates isolated signing material', () => {
  assert.match(finish, /C:\\Dev\\IdeaProjects\\contracts/);
  assert.match(finish, /launch-sc968-micro6\.ps1/);
  assert.match(finish, /launch-sc968-runelite\.ps1/);
  assert.match(finish, /genpkey -algorithm ED25519/);
  assert.match(finish, /SC968_MAVEN_COMMAND/);
  assert.match(finish, /\$env:AI_ROUTINE_CAPTURE_TOKEN = \$env:SC968_CAPTURE_TOKEN/);
  assert.match(finish, /\$env:AI_ROUTINE_OPERATOR_TOKEN = \$env:SC968_OPERATOR_TOKEN/);
  assert.match(runtime, /ai-routine\/runs\/from-runelite-capture' -Method 'POST'/);
  assert.match(runtime, /\$captureAuthProbe\.StatusCode -ne 422/);
  assert.match(runtime, /ai-routine\/runs\/from-stabilization\/sc968-auth-probe' -Method 'POST'/);
  assert.match(runtime, /\$managedCaptureAuthProbe\.StatusCode -ne 422/);
  assert.match(runtime, /ChromaScape did not receive the SC-968 capture token/);
  assert.match(finish, /Set-DefaultEnvironment 'SC968_CHROMASCAPE_URL' 'http:\/\/127\.0\.0\.1:18090'/);
  assert.match(finish, /Get-NetTCPConnection -State Listen -LocalPort \$chromaPort/);
  assert.doesNotMatch(finish, /Get-NetTCPConnection -State Listen -LocalPort 8090/);
  assert.match(runtime, /\$env:SERVER_PORT = \[string\]\$chromaUri\.Port/);
  assert.match(finish, /\$goVersion = '1\.24\.13'/);
  assert.match(finish, /Go\\bin\\go\.exe/);
  assert.match(finish, /\$env:GOTMPDIR = \$goTemp/);
  assert.match(finish, /go\.dev\/dl\/go\$goVersion\.windows-amd64\.zip/);
  assert.match(finish, /40b16bc8f00540a2cb02dff4de72b73e966fdd8d65f95e33d8e4080b48a2459a/);
  assert.match(entry, /& \$goCommand vet/);
  assert.match(entry, /& \$mavenCommand -q/);
  assert.match(entry, /\$mavenRepoArgument = '-Dmaven\.repo\.local='/);
  assert.match(entry, /\.\\gradlew\.bat --no-daemon --no-watch-fs --build-cache :client:test/);
  assert.match(entry, /net\.runelite\.client\.plugins\.airoutinecapture\.Sc968CaptureCompletionPolicyTest/);
  assert.match(entry, /net\.runelite\.client\.plugins\.airoutinecapture\.RuneLiteCaptureSafetyTest/);
  assert.match(entry, /net\.runelite\.client\.plugins\.airoutinecapture\.RuneLiteCaptureIdentityFactoryTest/);
  assert.match(entry, /SC967_VECTORS_PATH = Join-Path \$ContractsRoot/);
  assert.match(entry, /chromascape_compile/);
  assert.match(entry, /compileJava compileTestJava/);
  assert.match(entry, /chromascape_ai_runtime_tests/);
  assert.match(entry, /com\.chromascape\.web\.aiplayer\.routine\.service\.PatchCandidateWorkflowServiceTest/);
  assert.match(entry, /chromascape_acceptance_contract_tests/);
  assert.doesNotMatch(entry, /'chromascape_tests'.*gradlew\.bat[^\r\n]* test/);
  assert.match(finish, /worktree add --detach/);
  assert.match(finish, /PreparedWorktrees/);
  assert.match(finish, /PreflightOnly/);
  assert.match(finish, /one-command preflight PASS/);
  assert.match(finish, /ACCEPTANCE PASS/);
  assert.doesNotMatch(finish, /ai-routine-contracts/);
});

test('acceptance scripts stay parseable by Windows PowerShell 5.1', () => {
  // ConvertFrom-Json gained -Depth in PowerShell 6.2. The acceptance runs under Windows
  // PowerShell 5.1, where the parameter does not exist, so every such call throws. Each call site
  // sat inside a catch that returned null, so the failure was invisible: Wait-Sc968CaptureStatus
  // could never match a state and timed out after the full 1800s having never printed FISH NOW.
  const files = [
    'scripts/sc968-acceptance.ps1',
    'scripts/finish-sc967-sc968.ps1',
    'scripts/sc968/Acceptance.Micro6.psm1',
    'scripts/sc968/Acceptance.Http.psm1',
  ];
  for (const file of files) {
    const body = fs.readFileSync(path.join(root, file), 'utf8');
    assert.ok(
      !/ConvertFrom-Json\s+-Depth/.test(body),
      `${file} uses ConvertFrom-Json -Depth, which throws on Windows PowerShell 5.1`);
  }
});

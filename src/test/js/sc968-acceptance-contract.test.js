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

test('SC-968 runtime requires Assistant acceptance and a real RuneLite capture', () => {
  assert.match(runtime, /Wait-AssistantProposal/);
  assert.match(runtime, /assistant\/proposals\/\$proposalId\/accept/);
  assert.match(runtime, /acceptedProposal\.status -ne 'DRAFT_CREATED'/);
  assert.match(runtime, /Set-Sc968RuneLiteEnvironment/);
  assert.match(runtime, /Require-CaptureIdentity \$runeliteSession 'RUNELITE_CAPTURE'/);
  assert.match(runtime, /models\/\$modelId\/shadow-runs/);
  assert.doesNotMatch(runtime, /session_id=\[string\]\$secondRecordingState\.session_id/);
});

test('SC-968 candidate evidence uses and validates the signed nested manifest identity', () => {
  assert.match(runtime, /Field \$candidateManifest 'candidate'/);
  assert.match(runtime, /Field \$candidateIdentity 'certification_hash'/);
  assert.match(runtime, /Field \$candidateIdentity 'signature'/);
  assert.match(runtime, /Registered candidate signature is not valid base64/);
  assert.doesNotMatch(runtime, /\$candidate\.certification_hash/);
  assert.doesNotMatch(runtime, /\$candidate\.signature/);
});

test('SC-968 entrypoint and manifests require all five runtime evidence documents', () => {
  assert.doesNotMatch(evidence, /Import-Module[^\n]+Acceptance\.Common\.psm1/);
  assert.match(common, /return '\(detached\)'/);
  assert.match(common, /RedirectStandardError = \$true/);
  assert.match(common, /\$previousErrorActionPreference = \$ErrorActionPreference/);
  assert.match(common, /\$global:LASTEXITCODE = \$null/);
  assert.match(entry, /leeuwbot_go_vet[^\n]+\$ErrorActionPreference = 'Continue'/);
  assert.match(entry, /SC968_RUNELITE_LAUNCH_SCRIPT/);
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
  assert.match(runtime, /ChromaScape did not receive the SC-968 capture token/);
  assert.match(finish, /\$goVersion = '1\.24\.13'/);
  assert.match(finish, /Go\\bin\\go\.exe/);
  assert.match(finish, /\$env:GOTMPDIR = \$goTemp/);
  assert.match(finish, /go\.dev\/dl\/go\$goVersion\.windows-amd64\.zip/);
  assert.match(finish, /40b16bc8f00540a2cb02dff4de72b73e966fdd8d65f95e33d8e4080b48a2459a/);
  assert.match(entry, /& \$goCommand vet/);
  assert.match(entry, /& \$mavenCommand -q/);
  assert.match(entry, /\$mavenRepoArgument = '-Dmaven\.repo\.local='/);
  assert.match(entry, /-pl runelite-client -am \$contractVersionArgument -DskipTests install/);
  assert.match(entry, /Sc968CaptureCompletionPolicyTest,RuneLiteCaptureSafetyTest/);
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

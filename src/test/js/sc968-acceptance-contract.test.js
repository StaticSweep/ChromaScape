const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../../..');
const runtime = fs.readFileSync(path.join(root, 'scripts/sc968/Acceptance.Runtime.ps1'), 'utf8');
const entry = fs.readFileSync(path.join(root, 'scripts/sc968-acceptance.ps1'), 'utf8');
const finish = fs.readFileSync(path.join(root, 'scripts/finish-sc967-sc968.ps1'), 'utf8');
const evidence = fs.readFileSync(path.join(root, 'scripts/sc968/Acceptance.Evidence.psm1'), 'utf8');

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
  assert.match(finish, /worktree add --detach/);
  assert.match(finish, /PreparedWorktrees/);
  assert.match(finish, /PreflightOnly/);
  assert.match(finish, /one-command preflight PASS/);
  assert.match(finish, /ACCEPTANCE PASS/);
  assert.doesNotMatch(finish, /ai-routine-contracts/);
});

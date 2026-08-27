[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string] $ContractsRoot,
  [Parameter(Mandatory=$true)][string] $LeeuwBotRoot,
  [Parameter(Mandatory=$true)][string] $ChromaScapeRoot,
  [Parameter(Mandatory=$true)][string] $Micro6Root,
  [Parameter(Mandatory=$true)][string] $RuneLiteRoot,
  [Parameter(Mandatory=$true)][string] $OutputRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Acceptance.Common.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'Acceptance.Http.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'Acceptance.Processes.psm1') -Force
Import-Module (Join-Path $PSScriptRoot 'Acceptance.Micro6.psm1') -Force

function EnvOr([string] $Name, [string] $Default = '') {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($value)) { return $Default }
  return $value
}

function Require([string] $Name) { return Get-Sc968RequiredEnvironment $Name }

function Field([object] $Value, [string] $Name) {
  if ($null -eq $Value) { return $null }
  $property = $Value.PSObject.Properties[$Name]
  if ($null -eq $property) { return $null }
  return $property.Value
}

function Write-JsonFile([string] $Path, [object] $Value) {
  $parent = Split-Path -Parent $Path
  if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  $utf8 = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
  [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 80), $utf8)
}

function Sha256Bytes([byte[]] $Bytes) {
  $digest = [Security.Cryptography.SHA256]::Create().ComputeHash($Bytes)
  return 'sha256:' + (($digest | ForEach-Object { $_.ToString('x2') }) -join '')
}

function BytesEqual([byte[]] $Left, [byte[]] $Right) {
  if ($Left.Length -ne $Right.Length) { return $false }
  for ($index = 0; $index -lt $Left.Length; $index++) { if ($Left[$index] -ne $Right[$index]) { return $false } }
  return $true
}

function Api([string] $BaseUrl, [string] $Path, [string] $Token, [string] $Method = 'GET', [object] $Body = $null) {
  $json = if ($null -eq $Body) { $null } else { $Body | ConvertTo-Json -Depth 80 -Compress }
  $response = Invoke-Sc968Http -BaseUrl $BaseUrl -Path $Path -Method $Method -BearerToken $Token -Body $json
  return Assert-Sc968HttpSuccess $response "$Method $Path"
}

function Wait-Capture([string] $StatusFile, [int] $TimeoutSeconds) {
  $state = Wait-Sc968CaptureStatus -StatusFile $StatusFile -States @('RECORDING','COMPLETED','FAILED','UNCERTAIN') -TimeoutSeconds $TimeoutSeconds
  if ($state.state -in @('FAILED','UNCERTAIN')) { throw "Capture entered terminal safety state $($state.state)" }
  return $state
}

function Wait-AssistantProposal([string] $BaseUrl, [string] $Token, [string] $ConversationId, [int] $TimeoutSeconds) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    $status = (Api $BaseUrl "/api/v1/ai-routine/assistant/conversations/$ConversationId" $Token).Json
    $proposal = Field $status 'latest_proposal'
    if ($proposal -and (Field $proposal 'state') -eq 'PROPOSED') { return $proposal }
    Start-Sleep -Milliseconds 250
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw 'Timed out waiting for the Assistant graph proposal'
}

function Wait-LogMarkers([string] $LogPath, [int] $Pid, [string[]] $Markers, [int] $TimeoutSeconds) {
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    if (Test-Path -LiteralPath $LogPath -PathType Leaf) {
      $content = Get-Content -LiteralPath $LogPath -Raw
      $complete = $true
      foreach ($marker in $Markers) { if ($content -notmatch [regex]::Escape($marker)) { $complete = $false; break } }
      if ($complete) { return }
    }
    $process = Get-Process -Id $Pid -ErrorAction SilentlyContinue
    if ($null -eq $process) { break }
    Start-Sleep -Milliseconds 500
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Timed out waiting for required Micro6 loader evidence"
}

function Read-Session([string] $BaseUrl, [string] $Token, [string] $SessionId) {
  return (Api $BaseUrl "/api/v1/ai-routine/capture/sessions/$SessionId" $Token).Json
}

function Require-CompletedCapture([object] $Status, [string] $Name, [int] $MinimumTicks) {
  if ($Status.status -ne 'COMPLETED') { throw "$Name capture did not complete" }
  if ([int]$Status.tick_count -lt $MinimumTicks) { throw "$Name capture has too few ticks" }
  if ([int]$Status.action_count -lt 1) { throw "$Name capture has no observed action" }
  if ([string]::IsNullOrWhiteSpace([string]$Status.recording_id)) { throw "$Name capture has no recording ID" }
  if ([string]::IsNullOrWhiteSpace([string]$Status.recording_hash) -or [string]$Status.recording_hash -notmatch '^sha256:[0-9a-f]{64}$') { throw "$Name capture has no finalized recording hash" }
  if ($Status.control_allowed -ne $false) { throw "$Name capture control_allowed is not false" }
}


function Require-CaptureIdentity([object] $Status, [string] $ExpectedClientKind) {
  $identity = Field $Status 'identity'
  if ($null -eq $identity -or (Field $identity 'schema') -ne 'current-slice-identity/v1') { throw "$ExpectedClientKind capture identity is missing" }
  if ((Field (Field $identity 'task') 'task_key') -ne 'FISHING_TRAINING') { throw "$ExpectedClientKind capture task is not FISHING_TRAINING" }
  $source = Field $identity 'source'
  if (([string](Field $source 'source_snapshot_id')) -notmatch '^srcsnap_[0-9a-f]{24}$') { throw "$ExpectedClientKind source_snapshot_id is invalid" }
  if (([string](Field $source 'source_manifest_hash')) -notmatch '^sha256:[0-9a-f]{64}$') { throw "$ExpectedClientKind source manifest hash is invalid" }
  $client = Field $identity 'client'
  if ((Field $client 'client_kind') -ne $ExpectedClientKind) { throw "Expected $ExpectedClientKind capture identity" }
  $run = Field $identity 'run'
  if ($ExpectedClientKind -eq 'MICRO6_MANAGED') {
    if ([string]::IsNullOrWhiteSpace([string](Field $run 'attempt_id')) -or $null -eq (Field $run 'entry_id') -or [string]::IsNullOrWhiteSpace([string](Field $run 'executor_instance_id'))) { throw 'Micro6 capture is missing executor-bound run identity' }
  } else {
    if ($null -ne (Field $run 'attempt_id') -or $null -ne (Field $run 'entry_id') -or $null -ne (Field $run 'executor_instance_id')) { throw 'RuneLite capture fabricated Micro6 executor identity' }
    if ((Field $client 'adapter_contract') -ne 'runelite-capture/v1' -or (Field $client 'domain') -ne 'RUNELITE') { throw 'RuneLite capture capability identity is invalid' }
  }
  if ((Field $identity 'control_allowed') -ne $false) { throw "$ExpectedClientKind identity permits control" }
}

$owned = @()
$savedEnvironment = @{}
$temporaryEnvironmentNames = @(
  'SC968_CAPTURE_ENABLED','SC968_CHROMASCAPE_URL','SC968_CAPTURE_TOKEN','SC968_CAPTURE_STATUS_FILE','SC968_CAPTURE_SPOOL',
  'SC968_TASK_KEY','SC968_SETTINGS_HASH','SC968_PROFILE_ID','SC968_PROFILE_HASH','SC968_CATALOG_HASH','SC968_CLIENT_ID',
  'MICRO6_REMOTE_ACCOUNTBUILDER_ENABLED','MICRO6_REMOTE_ACCOUNTBUILDER_BASE_URL','MICRO6_REMOTE_ACCOUNTBUILDER_CANDIDATE_ID',
  'MICRO6_REMOTE_ACCOUNTBUILDER_TOKEN','MICRO6_REMOTE_PUBLIC_KEY_RING','AI_ROUTINE_CAPTURE_TOKEN','AI_ROUTINE_OPERATOR_TOKEN',
  'AI_ROUTINE_OPERATOR_ID','SC967_CANDIDATE_BUILDS_ENABLED','SC967_CANDIDATE_STAGING_ROOT','LEEUWBOT_CANDIDATE_REGISTRY_URL',
  'STATIC_API_TOKEN','CANDIDATE_STORAGE_ROOT','CANDIDATE_TRUSTED_KEYS_DIR','CANDIDATE_SIGNING_PRIVATE_KEY_PATH',
  'CANDIDATE_SIGNING_PUBLIC_KEY_PATH','CANDIDATE_SIGNING_KEY_ID','MICRO6_ROOT','MICRO6_EXPECTED_ARTIFACT_NAME')

try {
  foreach ($name in $temporaryEnvironmentNames) { $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name) }
  $captureToken = Require 'SC968_CAPTURE_TOKEN'
  $operatorToken = Require 'SC968_OPERATOR_TOKEN'
  $operatorId = Require 'SC968_OPERATOR_ID'
  $registryToken = Require 'SC968_REGISTRY_TOKEN'
  $null = Require 'SC968_PUBLIC_KEY_RING'
  $null = Require 'SC968_REGISTRY_TRUSTED_KEYS_DIR'
  $microLaunch = Require 'SC968_MICRO6_LAUNCH_SCRIPT'
  $runeliteLaunch = Require 'SC968_RUNELITE_LAUNCH_SCRIPT'
  $signingPrivate = Require 'SC968_SIGNING_PRIVATE_KEY_PATH'
  $signingPublic = Require 'SC968_SIGNING_PUBLIC_KEY_PATH'
  $signingKeyId = Require 'SC968_SIGNING_KEY_ID'
  $artifactName = Require 'SC968_EXPECTED_ARTIFACT_NAME'

  $chromaUrl = EnvOr 'SC968_CHROMASCAPE_URL' 'http://127.0.0.1:8090'
  $registryUrl = EnvOr 'SC968_REGISTRY_URL' 'http://127.0.0.1:8081'
  $minimumTicks = [int](EnvOr 'SC968_MINIMUM_TICKS' '20')
  $runtimeTimeout = [int](EnvOr 'SC968_RUNTIME_TIMEOUT_SECONDS' '1800')
  $registryRoot = Join-Path $LeeuwBotRoot 'plugin-service'
  $output = Join-Path $OutputRoot 'runtime'
  $logs = Join-Path $OutputRoot 'logs'
  $registryOutput = Join-Path $OutputRoot 'registry'
  $stagingRoot = Join-Path $registryOutput 'staging'
  New-Item -ItemType Directory -Force -Path $output,$logs,$registryOutput,$stagingRoot | Out-Null
  $oldEvidence = @(
    (Join-Path $output 'baseline.json'), (Join-Path $output 'assistant.json'), (Join-Path $output 'runelite.json'),
    (Join-Path $output 'candidate.json'), (Join-Path $output 'shadow.json'),
    (Join-Path $output 'baseline-status.json'), (Join-Path $output 'candidate-status.json'), (Join-Path $output 'runelite-status.json'),
    (Join-Path $registryOutput 'staged-manifest.json'), (Join-Path $registryOutput 'lookup-response.json'),
    (Join-Path $registryOutput 'candidate.jar')) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
  if ($oldEvidence.Count -gt 0 -or @(Get-ChildItem -LiteralPath $stagingRoot -Force -ErrorAction SilentlyContinue).Count -gt 0) {
    throw 'SC-968 runtime output must be fresh; move the previous evidence directory before retrying'
  }

  # These values are process-scoped and are removed/restored in finally. They are never written to
  # evidence and are not included in process command lines.
  [Environment]::SetEnvironmentVariable('AI_ROUTINE_CAPTURE_TOKEN',$captureToken,'Process')
  [Environment]::SetEnvironmentVariable('AI_ROUTINE_OPERATOR_TOKEN',$operatorToken,'Process')
  [Environment]::SetEnvironmentVariable('AI_ROUTINE_OPERATOR_ID',$operatorId,'Process')
  [Environment]::SetEnvironmentVariable('SC967_CANDIDATE_BUILDS_ENABLED','true','Process')
  [Environment]::SetEnvironmentVariable('LEEUWBOT_CANDIDATE_REGISTRY_URL',$registryUrl,'Process')
  [Environment]::SetEnvironmentVariable('STATIC_API_TOKEN',$registryToken,'Process')
  [Environment]::SetEnvironmentVariable('SC967_CANDIDATE_STAGING_ROOT',$stagingRoot,'Process')
  [Environment]::SetEnvironmentVariable('CANDIDATE_STORAGE_ROOT',(Join-Path $registryOutput 'registry-storage'),'Process')
  [Environment]::SetEnvironmentVariable('CANDIDATE_TRUSTED_KEYS_DIR',(Require 'SC968_REGISTRY_TRUSTED_KEYS_DIR'),'Process')
  [Environment]::SetEnvironmentVariable('CANDIDATE_SIGNING_PRIVATE_KEY_PATH',$signingPrivate,'Process')
  [Environment]::SetEnvironmentVariable('CANDIDATE_SIGNING_PUBLIC_KEY_PATH',$signingPublic,'Process')
  [Environment]::SetEnvironmentVariable('CANDIDATE_SIGNING_KEY_ID',$signingKeyId,'Process')
  [Environment]::SetEnvironmentVariable('MICRO6_ROOT',$Micro6Root,'Process')
  [Environment]::SetEnvironmentVariable('MICRO6_EXPECTED_ARTIFACT_NAME',$artifactName,'Process')

  $registryExecutable = EnvOr 'SC968_REGISTRY_EXECUTABLE' 'go.exe'
  $registryArguments = EnvOr 'SC968_REGISTRY_ARGUMENTS' 'run .'
  $registry = Start-Sc968OwnedProcess -Name 'candidate-registry' -FilePath $registryExecutable -WorkingDirectory $registryRoot -ArgumentList $registryArguments -LogPath (Join-Path $logs 'candidate-registry.log')
  $owned += $registry
  Wait-Sc968Http -BaseUrl $registryUrl -Path '/api/v1/candidates/cand_000000000000000000000000' -BearerToken $registryToken -AcceptedStatusCodes @(404) -TimeoutSeconds 120 | Out-Null

  $chromaExecutable = EnvOr 'SC968_CHROMASCAPE_EXECUTABLE' (Join-Path $env:SystemRoot 'System32/cmd.exe')
  $chromaArguments = EnvOr 'SC968_CHROMASCAPE_ARGUMENTS' "/d /c call `"$(Join-Path $ChromaScapeRoot 'gradlew.bat')`" --no-daemon --no-watch-fs bootRun"
  $chroma = Start-Sc968OwnedProcess -Name 'chromascape' -FilePath $chromaExecutable -WorkingDirectory $ChromaScapeRoot -ArgumentList $chromaArguments -LogPath (Join-Path $logs 'chromascape-runtime.log')
  $owned += $chroma
  Wait-Sc968Http -BaseUrl $chromaUrl -Path '/debugger' -AcceptedStatusCodes @(200,301,302) -TimeoutSeconds 900 -PollMilliseconds 1000 | Out-Null

  # Baseline: the status sidecar is produced by the real Micro6 plugin. No run/session or action
  # data is generated by this harness.
  $baselineStatusFile = Join-Path $output 'baseline-status.json'
  Set-Sc968Micro6Environment -BaseUrl $chromaUrl -CaptureToken $captureToken -StatusFile $baselineStatusFile -SpoolDirectory (Join-Path $OutputRoot 'spool/baseline')
  $microLog = Join-Path $logs 'baseline-micro6.log'
  $micro = Start-Sc968OwnedProcess -Name 'micro6-baseline' -FilePath $microLaunch -WorkingDirectory $Micro6Root -ArgumentList (EnvOr 'SC968_MICRO6_LAUNCH_ARGUMENTS' '') -LogPath $microLog
  $owned += $micro
  $recordingState = Wait-Capture $baselineStatusFile $runtimeTimeout
  if ($recordingState.state -ne 'RECORDING') { throw 'Baseline Micro6 never reached RECORDING' }
  $baselineSession = Read-Session $chromaUrl $captureToken ([string]$recordingState.session_id)
  if ([int]$baselineSession.action_count -ne 0) { throw 'Baseline gameplay began before the capture was observable' }
  Write-Host 'FISH NOW (1/3): in the baseline Micro6 client, use Net on a Fishing spot once.' -ForegroundColor Yellow
  $baselineCompletedState = Wait-Sc968CaptureStatus -StatusFile $baselineStatusFile -States @('COMPLETED','FAILED','UNCERTAIN') -TimeoutSeconds $runtimeTimeout
  if ($baselineCompletedState.state -ne 'COMPLETED') { throw "Baseline capture ended in $($baselineCompletedState.state)" }
  $baselineSession = Read-Session $chromaUrl $captureToken ([string]$baselineCompletedState.session_id)
  Require-CompletedCapture $baselineSession 'Baseline' $minimumTicks
  Require-CaptureIdentity $baselineSession 'MICRO6_MANAGED'
  Stop-Sc968OwnedProcess $micro
  $owned = @($owned | Where-Object { $_.Pid -ne $micro.Pid })

  # Curate and publish the evidence graph that gives the deterministic Assistant an immutable base.
  $recordingId = [string]$baselineSession.recording_id
  Api $chromaUrl "/api/v1/ai-routine/recordings/$recordingId/curation" $operatorToken 'PUT' @{ includeRanges=$null; excludeRanges=$null } | Out-Null
  $baseGraphResponse = (Api $chromaUrl "/api/v1/ai-routine/recordings/$recordingId/graphs" $operatorToken 'POST').Json
  $baseGraphId = [string]$baseGraphResponse.definition.graph_id
  if ([string]::IsNullOrWhiteSpace($baseGraphId)) { throw 'Graph generation returned no graph ID' }
  $published = (Api $chromaUrl "/api/v1/ai-routine/graphs/$baseGraphId/publish" $operatorToken 'POST').Json
  if ($published.state -ne 'PUBLISHED') { throw 'Generated graph did not publish' }

  # A real operator acceptance must create a distinct DRAFT graph before model construction.
  $runId = [string]$baselineSession.identity.run.run_id
  $conversation = (Api $chromaUrl "/api/v1/ai-routine/runs/$runId/assistant/conversations" $operatorToken 'POST').Json
  $conversationId = [string]$conversation.conversation_id
  if ([string]::IsNullOrWhiteSpace($conversationId)) { throw 'Assistant conversation returned no ID' }
  Api $chromaUrl "/api/v1/ai-routine/assistant/conversations/$conversationId/messages" $operatorToken 'POST' @{ message='Review the captured Fishing evidence and propose the minimal deterministic graph draft.' } | Out-Null
  $proposal = Wait-AssistantProposal $chromaUrl $operatorToken $conversationId $runtimeTimeout
  $proposalId = [string]$proposal.proposal_id
  $acceptedProposal = (Api $chromaUrl "/api/v1/ai-routine/assistant/proposals/$proposalId/accept" $operatorToken 'POST').Json
  $graphId = [string]$acceptedProposal.graph_id
  if ($acceptedProposal.status -ne 'DRAFT_CREATED' -or [string]::IsNullOrWhiteSpace($graphId) -or $graphId -eq $baseGraphId -or $acceptedProposal.control_allowed -ne $false) { throw 'Assistant acceptance did not create a distinct read-only draft graph' }
  $graphResponse = (Api $chromaUrl "/api/v1/ai-routine/graphs/$graphId" $operatorToken).Json
  if ($graphResponse.state -ne 'DRAFT' -or [string]$graphResponse.supersedes_graph_id -ne $baseGraphId -or $graphResponse.control_allowed -ne $false) { throw 'Assistant draft graph provenance is invalid' }
  $draftPublished = (Api $chromaUrl "/api/v1/ai-routine/graphs/$graphId/publish" $operatorToken 'POST').Json
  if ($draftPublished.state -ne 'PUBLISHED') { throw 'Accepted Assistant draft did not publish' }
  Write-JsonFile (Join-Path $output 'assistant.json') ([ordered]@{
      run_id=$runId; conversation_id=$conversationId; proposal_id=$proposalId; base_graph_id=$baseGraphId
      draft_graph_id=$graphId; draft_graph_hash=$graphResponse.definition.graph_hash
      supersedes_graph_id=$graphResponse.supersedes_graph_id; proposal_diff=$proposal.diff
      accepted_status=$acceptedProposal.status; control_allowed=$false
    })

  # Build only from the explicitly accepted/published draft, then replay the exact recording.
  $modelResponse = (Api $chromaUrl "/api/v1/ai-routine/graphs/$graphId/models" $operatorToken 'POST' @{ identity=$baselineSession.identity }).Json
  $modelId = [string]$modelResponse.model_id
  if ([string]::IsNullOrWhiteSpace($modelId)) { throw 'Model build returned no model ID' }
  $actionNodes = @($graphResponse.definition.nodes | Where-Object { $_.type -eq 'ACTION' })
  if ($actionNodes.Count -lt 1) { throw 'Live recording produced no graph action' }
  $recordedAction = $actionNodes[0].semantics.recorded_action
  if (([string]$recordedAction.option -notmatch '(?i)net|fish') -or ([string]$recordedAction.target -notmatch '(?i)fishing')) { throw 'Recorded action is not a fishing interaction' }
  $replay = (Api $chromaUrl "/api/v1/ai-routine/models/$modelId/replays" $operatorToken 'POST' @{ recordingId=$recordingId }).Json
  if ($replay.status -ne 'PASSED' -or $replay.deterministic -ne $true -or $replay.control_allowed -ne $false) { throw 'Baseline policy replay did not PASS deterministically' }

  # Candidate proposal/build/registry flow is driven by real graph/model identity; only the
  # service's required seeded failure code is fixed by the SC-968 contract.
  $failure = (Api $chromaUrl '/api/v1/ai-routine/patches/from-failure' $operatorToken 'POST' @{
      failure_manifest_hash='sha256:b201629e712be24815e43551a5185a2b45fe3a0b77b2aca8f2a51dd250917956'
      failure_code='UNSUPPORTED_FISH_DEFAULTED'; identity=$modelResponse.identity
      base_commit=[string]$modelResponse.identity.client.client_build_id }).Json
  $patchProposalId = [string]$failure.proposal_id
  if ([string]::IsNullOrWhiteSpace($patchProposalId)) { throw 'Candidate proposal was not created' }
  Api $chromaUrl "/api/v1/ai-routine/patches/$patchProposalId/approve" $operatorToken 'POST' | Out-Null
  Api $chromaUrl "/api/v1/ai-routine/patches/$patchProposalId/build" $operatorToken 'POST' | Out-Null
  $candidate = $null
  $candidateDeadline = [DateTimeOffset]::UtcNow.AddSeconds($runtimeTimeout)
  do {
    $candidate = (Api $chromaUrl "/api/v1/ai-routine/patches/$patchProposalId" $operatorToken).Json
    if ($candidate.status -in @('REGISTERED','BUILD_FAILED','FAILED','REJECTED')) { break }
    Start-Sleep -Seconds 2
  } while ([DateTimeOffset]::UtcNow -lt $candidateDeadline)
  if ($candidate.status -ne 'REGISTERED') { throw "Candidate workflow ended in $($candidate.status)" }
  $candidateId = [string](Field $candidate 'candidate_id')
  if ($candidateId -notmatch '^cand_[0-9a-f]{24}$') { throw 'Registered candidate ID is missing or malformed' }
  $candidateManifest = Field $candidate 'candidate_manifest'
  $candidateIdentity = Field $candidateManifest 'candidate'
  $candidateHash = [string](Field $candidateIdentity 'candidate_hash')
  $registeredCandidateHash = [string](Field $candidate 'candidate_hash')
  $certificationHash = [string](Field $candidateIdentity 'certification_hash')
  $candidateSignature = [string](Field $candidateIdentity 'signature')
  if ($candidateHash -notmatch '^sha256:[0-9a-f]{64}$' -or $candidateHash -ne $registeredCandidateHash) { throw 'Registered candidate hash is missing, malformed, or inconsistent' }
  if ($certificationHash -notmatch '^sha256:[0-9a-f]{64}$') { throw 'Registered candidate certification hash is missing or malformed' }
  if ([string]::IsNullOrWhiteSpace($candidateSignature)) { throw 'Registered candidate signature is missing' }
  try { [Convert]::FromBase64String($candidateSignature) | Out-Null } catch { throw 'Registered candidate signature is not valid base64' }
  $manifestPath = Join-Path (Join-Path $stagingRoot $candidateId) 'manifest.json'
  $jarName = [string](Field $candidateManifest 'jar_name')
  if ([string]::IsNullOrWhiteSpace($jarName)) { throw 'Registered candidate manifest has no artifact name' }
  $jarPath = Join-Path (Join-Path $stagingRoot $candidateId) $jarName
  if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or -not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw 'Candidate staging bundle is incomplete' }
  $stagedManifest = [IO.File]::ReadAllBytes($manifestPath)
  $stagedJar = [IO.File]::ReadAllBytes($jarPath)
  [IO.File]::WriteAllBytes((Join-Path $registryOutput 'staged-manifest.json'),$stagedManifest)

  $lookup = Invoke-Sc968Http -BaseUrl $registryUrl -Path "/api/v1/candidates/$candidateId" -BearerToken $registryToken
  if ($lookup.StatusCode -ne 200) { throw "Candidate lookup returned HTTP $($lookup.StatusCode)" }
  $lookupLength = Get-Sc968Header $lookup 'Content-Length'
  $lookupHash = Get-Sc968Header $lookup 'X-Candidate-Manifest-SHA256'
  if ([int64]$lookupLength -le 0 -or [int64]$lookupLength -ne $lookup.BodyBytes.Length -or $lookupHash -ne (Sha256Bytes $lookup.BodyBytes) -or -not (BytesEqual $stagedManifest $lookup.BodyBytes)) { throw 'Candidate lookup is not an exact staged-manifest readback' }
  [IO.File]::WriteAllBytes((Join-Path $registryOutput 'lookup-response.json'),$lookup.BodyBytes)
  $download = Invoke-Sc968Http -BaseUrl $registryUrl -Path "/api/v1/candidates/$candidateId/download" -BearerToken $registryToken
  $downloadHash = Get-Sc968Header $download 'X-Artifact-SHA256'
  if ($download.StatusCode -ne 200 -or (Get-Sc968Header $download 'X-Candidate-ID') -ne $candidateId -or [int64](Get-Sc968Header $download 'Content-Length') -ne $download.BodyBytes.Length -or $downloadHash -ne (Sha256Bytes $download.BodyBytes) -or -not (BytesEqual $stagedJar $download.BodyBytes)) { throw 'Candidate artifact readback failed exact ID/length/hash/byte verification' }
  [IO.File]::WriteAllBytes((Join-Path $registryOutput 'candidate.jar'),$download.BodyBytes)
  $candidateEvidence = [ordered]@{ proposal_id=$patchProposalId; candidate_id=$candidateId; candidate_hash=$candidateHash; certification_hash=$certificationHash; signature=$candidateSignature; manifest_hash=(Sha256Bytes $stagedManifest); registry_manifest_hash=$lookupHash; manifest_byte_equal=$true; artifact_hash=(Sha256Bytes $stagedJar); artifact_byte_equal=$true; control_allowed=$false }

  # Second Micro6 launch proves exact candidate retrieval and AccountBuilder loading. Shadow is not
  # opened here: the cross-runner compatibility contract intentionally requires RuneLite capture.
  $candidateStatusFile = Join-Path $output 'candidate-status.json'
  Set-Sc968Micro6Environment -BaseUrl $chromaUrl -CaptureToken $captureToken -StatusFile $candidateStatusFile -SpoolDirectory (Join-Path $OutputRoot 'spool/candidate') -CandidateId $candidateId
  $candidateLog = Join-Path $logs 'candidate-micro6.log'
  $candidateMicro = Start-Sc968OwnedProcess -Name 'micro6-candidate' -FilePath $microLaunch -WorkingDirectory $Micro6Root -ArgumentList (EnvOr 'SC968_MICRO6_LAUNCH_ARGUMENTS' '') -LogPath $candidateLog
  $owned += $candidateMicro
  Wait-LogMarkers $candidateLog $candidateMicro.Pid @('verified exact registry manifest','verified candidate artifact','immediate pre-load artifact rehash passed','applied remote AccountBuilder candidate') $runtimeTimeout
  $secondRecordingState = Wait-Capture $candidateStatusFile $runtimeTimeout
  if ($secondRecordingState.state -ne 'RECORDING') { throw 'Candidate Micro6 never reached RECORDING' }
  $secondSession = Read-Session $chromaUrl $captureToken ([string]$secondRecordingState.session_id)
  if ([int]$secondSession.action_count -ne 0) { throw 'Candidate gameplay began before the capture was observable' }
  Write-Host 'FISH NOW (2/3): in the candidate Micro6 client, use Net on a Fishing spot once.' -ForegroundColor Yellow
  $secondCompletedState = Wait-Sc968CaptureStatus -StatusFile $candidateStatusFile -States @('COMPLETED','FAILED','UNCERTAIN') -TimeoutSeconds $runtimeTimeout
  if ($secondCompletedState.state -ne 'COMPLETED') { throw "Candidate capture ended in $($secondCompletedState.state)" }
  $secondSession = Read-Session $chromaUrl $captureToken ([string]$secondCompletedState.session_id)
  Require-CompletedCapture $secondSession 'Candidate' $minimumTicks
  Require-CaptureIdentity $secondSession 'MICRO6_MANAGED'
  $candidateEvidence['run_id'] = $secondSession.identity.run.run_id
  $candidateEvidence['session_id'] = $secondSession.session_id
  $candidateEvidence['recording_id'] = $secondSession.recording_id
  $candidateEvidence['recording_hash'] = $secondSession.recording_hash
  Write-JsonFile (Join-Path $output 'candidate.json') $candidateEvidence
  Stop-Sc968OwnedProcess $candidateMicro
  $owned = @($owned | Where-Object { $_.Pid -ne $candidateMicro.Pid })

  # One standard RuneLite human capture proves the second adapter and hosts the real read-only
  # cross-runner shadow. The configured launcher must leave enough time for the operator to fish.
  $runeliteStatusFile = Join-Path $output 'runelite-status.json'
  Set-Sc968RuneLiteEnvironment -BaseUrl $chromaUrl -CaptureToken $captureToken -StatusFile $runeliteStatusFile -SpoolDirectory (Join-Path $OutputRoot 'spool/runelite')
  $runeliteLog = Join-Path $logs 'runelite-human.log'
  $runelite = Start-Sc968OwnedProcess -Name 'runelite-human' -FilePath $runeliteLaunch -WorkingDirectory $RuneLiteRoot -ArgumentList (EnvOr 'SC968_RUNELITE_LAUNCH_ARGUMENTS' '') -LogPath $runeliteLog
  $owned += $runelite
  $runeliteRecordingState = Wait-Capture $runeliteStatusFile $runtimeTimeout
  if ($runeliteRecordingState.state -ne 'RECORDING') { throw 'RuneLite capture never reached RECORDING' }
  $runeliteLiveSession = Read-Session $chromaUrl $captureToken ([string]$runeliteRecordingState.session_id)
  Require-CaptureIdentity $runeliteLiveSession 'RUNELITE_CAPTURE'
  if ([int]$runeliteLiveSession.action_count -ne 0) { throw 'RuneLite gameplay began before the shadow was observable' }
  $shadow = (Api $chromaUrl "/api/v1/ai-routine/models/$modelId/shadow-runs" $operatorToken 'POST' @{ schema='routine-shadow-run-request/v1'; session_id=[string]$runeliteRecordingState.session_id }).Json
  if ($shadow.state -ne 'ACTIVE' -or $shadow.control_allowed -ne $false) { throw 'RuneLite live shadow did not become ACTIVE read-only' }
  Write-Host 'FISH NOW (3/3): in standard RuneLite, use Net on a Fishing spot once.' -ForegroundColor Yellow
  $runeliteCompletedState = Wait-Sc968CaptureStatus -StatusFile $runeliteStatusFile -States @('COMPLETED','FAILED','UNCERTAIN') -TimeoutSeconds $runtimeTimeout
  if ($runeliteCompletedState.state -ne 'COMPLETED') { throw "RuneLite capture ended in $($runeliteCompletedState.state)" }
  $runeliteSession = Read-Session $chromaUrl $captureToken ([string]$runeliteCompletedState.session_id)
  Require-CompletedCapture $runeliteSession 'RuneLite' $minimumTicks
  Require-CaptureIdentity $runeliteSession 'RUNELITE_CAPTURE'

  $shadowDeadline = [DateTimeOffset]::UtcNow.AddSeconds(120)
  $completedShadow = $null
  do {
    try { $completedShadow = (Api $chromaUrl "/api/v1/ai-routine/shadow-runs/$($shadow.shadow_run_id)/complete" $operatorToken 'POST' @{ schema='routine-shadow-complete/v1' }).Json } catch { $completedShadow = $null }
    if ($completedShadow -and $completedShadow.state -eq 'COMPLETED') { break }
    Start-Sleep -Milliseconds 500
  } while ([DateTimeOffset]::UtcNow -lt $shadowDeadline)
  if ($null -eq $completedShadow -or $completedShadow.state -ne 'COMPLETED' -or [int]$completedShadow.result.decision_count -lt 1 -or $completedShadow.control_allowed -ne $false) { throw 'RuneLite live shadow did not complete with persisted read-only decisions' }
  $shadowResult = $completedShadow.result

  # The RuneLite recording also owns its own graph/model/replay identity; it never claims Micro6
  # executor fields even though its live shadow consumed the compatible Micro6-trained model.
  $runeliteRecordingId = [string]$runeliteSession.recording_id
  Api $chromaUrl "/api/v1/ai-routine/recordings/$runeliteRecordingId/curation" $operatorToken 'PUT' @{ includeRanges=$null; excludeRanges=$null } | Out-Null
  $runeliteGraph = (Api $chromaUrl "/api/v1/ai-routine/recordings/$runeliteRecordingId/graphs" $operatorToken 'POST').Json
  $runeliteGraphId = [string]$runeliteGraph.definition.graph_id
  $runelitePublished = (Api $chromaUrl "/api/v1/ai-routine/graphs/$runeliteGraphId/publish" $operatorToken 'POST').Json
  if ($runelitePublished.state -ne 'PUBLISHED') { throw 'RuneLite graph did not publish' }
  $runeliteModel = (Api $chromaUrl "/api/v1/ai-routine/graphs/$runeliteGraphId/models" $operatorToken 'POST' @{ identity=$runeliteSession.identity }).Json
  $runeliteReplay = (Api $chromaUrl "/api/v1/ai-routine/models/$($runeliteModel.model_id)/replays" $operatorToken 'POST' @{ recordingId=$runeliteRecordingId }).Json
  if ($runeliteReplay.status -ne 'PASSED' -or $runeliteReplay.deterministic -ne $true -or $runeliteReplay.control_allowed -ne $false) { throw 'RuneLite replay did not PASS deterministically' }
  Write-JsonFile (Join-Path $output 'runelite.json') ([ordered]@{ run_id=$runeliteSession.identity.run.run_id; session_id=$runeliteSession.session_id; recording_id=$runeliteRecordingId; recording_hash=$runeliteSession.recording_hash; graph_id=$runeliteGraphId; graph_hash=$runeliteGraph.definition.graph_hash; model_id=$runeliteModel.model_id; model_hash=$runeliteModel.model_hash; replay=$runeliteReplay; shadow_run_id=$shadow.shadow_run_id; shadow_model_id=$modelId; identity=$runeliteSession.identity; control_allowed=$false })
  Write-JsonFile (Join-Path $output 'baseline.json') ([ordered]@{ run_id=$baselineSession.identity.run.run_id; session_id=$baselineSession.session_id; recording_id=$baselineSession.recording_id; recording_hash=$baselineSession.recording_hash; graph_id=$graphId; model_id=$modelId; replay=$replay; tick_count=$baselineSession.tick_count; action_count=$baselineSession.action_count; identity=$baselineSession.identity; control_allowed=$false })
  Write-JsonFile (Join-Path $output 'shadow.json') ([ordered]@{ run_id=$runeliteSession.identity.run.run_id; session_id=$runeliteSession.session_id; recording_id=$runeliteSession.recording_id; recording_hash=$runeliteSession.recording_hash; shadow_run_id=$shadow.shadow_run_id; model_id=$modelId; result=$shadowResult; model_identity=$completedShadow.model_identity; session_identity=$completedShadow.session_identity; result_hash=$completedShadow.result_hash; control_allowed=$false; control_dispatch_count=0 })
  if ((Get-Content -LiteralPath $candidateLog -Raw) -match '(?i)control dispatch|mouse dispatch|keyboard dispatch|invoke menu') { throw 'Candidate Micro6 log contains control dispatch evidence' }
} finally {
  foreach ($process in @($owned | Sort-Object -Property Pid -Descending)) { Stop-Sc968OwnedProcess $process }
  foreach ($name in $temporaryEnvironmentNames) {
    $old = $savedEnvironment[$name]
    [Environment]::SetEnvironmentVariable($name,$old,'Process')
  }
}

Set-StrictMode -Version Latest

function Get-Sc968RequiredEnvironment([string] $Name) {
  $value = [Environment]::GetEnvironmentVariable($Name)
  if ([string]::IsNullOrWhiteSpace($value)) { throw "$Name is required for native SC-968 acceptance" }
  return $value
}

function Set-Sc968Micro6Environment {
  param(
    [Parameter(Mandatory=$true)][string] $BaseUrl,
    [Parameter(Mandatory=$true)][string] $CaptureToken,
    [Parameter(Mandatory=$true)][string] $StatusFile,
    [Parameter(Mandatory=$true)][string] $SpoolDirectory,
    [Parameter(Mandatory=$true)][object] $ManagedIdentity,
    [string] $CandidateId
  )
  $env:SC968_CAPTURE_ENABLED = 'true'
  $env:SC968_CHROMASCAPE_URL = $BaseUrl
  $env:SC968_CAPTURE_TOKEN = $CaptureToken
  $env:SC968_CAPTURE_STATUS_FILE = $StatusFile
  $env:SC968_CAPTURE_SPOOL = $SpoolDirectory
  $env:SC968_TASK_KEY = Get-Sc968RequiredEnvironment 'SC968_TASK_KEY'
  $env:SC968_SETTINGS_HASH = Get-Sc968RequiredEnvironment 'SC968_SETTINGS_HASH'
  $env:SC968_PROFILE_ID = Get-Sc968RequiredEnvironment 'SC968_PROFILE_ID'
  $env:SC968_PROFILE_HASH = Get-Sc968RequiredEnvironment 'SC968_PROFILE_HASH'
  $env:SC968_CATALOG_HASH = Get-Sc968RequiredEnvironment 'SC968_CATALOG_HASH'
  $env:SC968_CLIENT_ID = if ($CandidateId) { 'micro6-candidate-runner' } else { 'micro6-baseline-runner' }
  $env:SC968_RUN_ID = [string]$ManagedIdentity.RunId
  $env:SC968_ATTEMPT_ID = [string]$ManagedIdentity.AttemptId
  $env:SC968_ENTRY_ID = [string]$ManagedIdentity.EntryId
  $env:SC968_EXECUTOR_INSTANCE_ID = [string]$ManagedIdentity.ExecutorInstanceId
  $env:SC968_SOURCE_SNAPSHOT_ID = [string]$ManagedIdentity.SourceSnapshotId
  $env:SC968_SOURCE_MANIFEST_HASH = [string]$ManagedIdentity.SourceManifestHash
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_ENABLED = if ($CandidateId) { 'true' } else { 'false' }
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_BASE_URL = Get-Sc968RequiredEnvironment 'SC968_REGISTRY_URL'
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_CANDIDATE_ID = if ($CandidateId) { $CandidateId } else { '' }
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_TOKEN = Get-Sc968RequiredEnvironment 'SC968_REGISTRY_TOKEN'
  $env:MICRO6_REMOTE_PUBLIC_KEY_RING = Get-Sc968RequiredEnvironment 'SC968_PUBLIC_KEY_RING'
}

function Set-Sc968RuneLiteEnvironment {
  param(
    [Parameter(Mandatory=$true)][string] $BaseUrl,
    [Parameter(Mandatory=$true)][string] $CaptureToken,
    [Parameter(Mandatory=$true)][string] $StatusFile,
    [Parameter(Mandatory=$true)][string] $SpoolDirectory,
    [Parameter(Mandatory=$true)][object] $SourceIdentity
  )
  $env:SC968_CAPTURE_ENABLED = 'true'
  $env:SC968_CHROMASCAPE_URL = $BaseUrl
  $env:SC968_CAPTURE_TOKEN = $CaptureToken
  $env:SC968_CAPTURE_STATUS_FILE = $StatusFile
  $env:SC968_CAPTURE_SPOOL = $SpoolDirectory
  $env:SC968_TASK_KEY = Get-Sc968RequiredEnvironment 'SC968_TASK_KEY'
  $env:SC968_SETTINGS_HASH = Get-Sc968RequiredEnvironment 'SC968_SETTINGS_HASH'
  $env:SC968_PROFILE_ID = Get-Sc968RequiredEnvironment 'SC968_PROFILE_ID'
  $env:SC968_PROFILE_HASH = Get-Sc968RequiredEnvironment 'SC968_PROFILE_HASH'
  $env:SC968_CATALOG_HASH = Get-Sc968RequiredEnvironment 'SC968_CATALOG_HASH'
  $env:SC968_CLIENT_ID = 'runelite-human-capture'
  $env:SC968_RUN_ID = ''
  $env:SC968_ATTEMPT_ID = ''
  $env:SC968_ENTRY_ID = ''
  $env:SC968_EXECUTOR_INSTANCE_ID = ''
  $env:SC968_SOURCE_SNAPSHOT_ID = [string]$SourceIdentity.SourceSnapshotId
  $env:SC968_SOURCE_MANIFEST_HASH = [string]$SourceIdentity.SourceManifestHash
  # A standard RuneLite capture must never inherit Micro6 executor/candidate capabilities from the
  # baseline or candidate launch that preceded it in the same acceptance process.
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_ENABLED = 'false'
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_CANDIDATE_ID = ''
}

function Initialize-Sc968ManagedCaptureIdentity {
  param(
    [Parameter(Mandatory=$true)][string] $Micro6Root,
    [Parameter(Mandatory=$true)][string] $ContractsRoot,
    [Parameter(Mandatory=$true)][string] $BaseUrl,
    [Parameter(Mandatory=$true)][string] $PlanPath,
    [Parameter(Mandatory=$true)][string] $OutputDirectory,
    [Parameter(Mandatory=$true)][string] $Name,
    [Parameter(Mandatory=$true)][ValidateRange(0,2147483647)][int] $RunIndex
  )
  if (-not (Test-Path -LiteralPath $PlanPath -PathType Leaf)) { throw "SC-968 stabilization seed plan is missing: $PlanPath" }
  $gradle = Join-Path $Micro6Root 'gradlew.bat'
  if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw "Micro6 Gradle launcher is missing: $gradle" }
  New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

  $arguments = "--plan=$PlanPath --output=$OutputDirectory --fixture=pass --run-index=$RunIndex --certify-url=$BaseUrl --certification-mode=required"
  $seedExitCode = 1
  Push-Location $Micro6Root
  try {
    & $gradle --no-daemon --no-watch-fs ":client:micro6StabilizationHarness" "-Pmicro6.stabilization.plan=$PlanPath" "--args=$arguments"
    $seedExitCode = $LASTEXITCODE
  } finally {
    Pop-Location
  }
  if ($seedExitCode -ne 0) { throw "Micro6 stabilization seed '$Name' failed with exit code $seedExitCode" }

  $manifests = @(Get-ChildItem -LiteralPath $OutputDirectory -Recurse -Filter 'run-manifest.json' -File)
  $receipts = @(Get-ChildItem -LiteralPath $OutputDirectory -Recurse -Filter 'certification-receipt.json' -File)
  if ($manifests.Count -ne 1 -or $receipts.Count -ne 1) { throw "Micro6 stabilization seed '$Name' did not produce one manifest and receipt" }
  $manifest = Get-Content -LiteralPath $manifests[0].FullName -Raw | ConvertFrom-Json
  $receipt = Get-Content -LiteralPath $receipts[0].FullName -Raw | ConvertFrom-Json
  if ($manifest.outcome -ne 'PASS' -or $manifest.terminal_state -ne 'PASSED' -or $manifest.evidence_complete -ne $true) {
    throw "Micro6 stabilization seed '$Name' did not produce a complete PASS manifest"
  }
  if ($receipt.certification_status -ne 'CERTIFIED' -or $receipt.overall_outcome -ne 'PASS' -or $receipt.run_id -ne $manifest.run_id) {
    throw "Micro6 stabilization seed '$Name' was not certified by ChromaScape"
  }
  $attempts = @($manifest.attempts)
  if ($attempts.Count -ne 1 -or $attempts[0].outcome -ne 'PASS') { throw "Micro6 stabilization seed '$Name' must contain exactly one PASS attempt" }
  $entryId = 0
  if (-not [int]::TryParse([string]$attempts[0].entry_id, [ref]$entryId) -or $entryId -lt 1) {
    throw "Micro6 stabilization seed '$Name' has an invalid entry_id"
  }

  $git = Get-Command git.exe -ErrorAction SilentlyContinue
  if ($null -eq $git) { $git = Get-Command git -ErrorAction Stop }
  $micro6Revision = (& $git.Source -C $Micro6Root rev-parse HEAD).Trim()
  $contractsRevision = (& $git.Source -C $ContractsRoot rev-parse HEAD).Trim()
  if ($LASTEXITCODE -ne 0 -or $micro6Revision -notmatch '^[0-9a-f]{40}$' -or $contractsRevision -notmatch '^[0-9a-f]{40}$') {
    throw "SC-968 could not resolve source revisions for '$Name'"
  }
  $sourceManifest = [ordered]@{
    schema = 'sc968-capture-source-manifest/v1'
    task_key = 'FISHING_TRAINING'
    micro6_revision = $micro6Revision
    contracts_revision = $contractsRevision
    stabilization_plan_hash = [string]$manifest.plan_hash
    stabilization_manifest_hash = [string]$manifest.manifest_hash
  }
  $sourcePath = Join-Path $OutputDirectory 'source-manifest.json'
  $sourceJson = $sourceManifest | ConvertTo-Json -Depth 10 -Compress
  $utf8 = New-Object -TypeName System.Text.UTF8Encoding -ArgumentList $false
  $sourceBytes = $utf8.GetBytes($sourceJson)
  [IO.File]::WriteAllBytes($sourcePath, $sourceBytes)
  $sourceHash = 'sha256:' + (([Security.Cryptography.SHA256]::Create().ComputeHash($sourceBytes) | ForEach-Object { $_.ToString('x2') }) -join '')

  return [pscustomobject]@{
    RunId = [string]$manifest.run_id
    AttemptId = [string]$attempts[0].attempt_id
    EntryId = $entryId
    ExecutorInstanceId = [string]$manifest.micro6_instance_id
    SourceSnapshotId = 'srcsnap_' + $sourceHash.Substring(7,24)
    SourceManifestHash = $sourceHash
    SourceManifestPath = $sourcePath
  }
}

function Read-Sc968CaptureStatus([string] $StatusFile) {
  if (-not (Test-Path -LiteralPath $StatusFile -PathType Leaf)) { return $null }
  try {
    $value = Get-Content -LiteralPath $StatusFile -Raw | ConvertFrom-Json
    if ($value.schema -ne 'sc968-capture-status/v1') { return $null }
    return $value
  } catch { return $null }
}

function Wait-Sc968CaptureStatus {
  param(
    [Parameter(Mandatory=$true)][string] $StatusFile,
    [Parameter(Mandatory=$true)][string[]] $States,
    [int] $TimeoutSeconds = 900,
    [int] $PollMilliseconds = 500
  )
  $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
  do {
    $status = Read-Sc968CaptureStatus $StatusFile
    if ($status -and ($States -contains [string]$status.state)) { return $status }
    Start-Sleep -Milliseconds $PollMilliseconds
  } while ([DateTimeOffset]::UtcNow -lt $deadline)
  throw "Timed out waiting for capture state [$($States -join ',')]"
}

function Assert-Sc968LogMarkers([string] $LogPath, [string[]] $Markers) {
  if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) { throw "Missing Micro6 log: $LogPath" }
  $content = Get-Content -LiteralPath $LogPath -Raw
  foreach ($marker in $Markers) {
    if ($content -notmatch [regex]::Escape($marker)) { throw "Micro6 log is missing required loader marker: $marker" }
  }
}

Export-ModuleMember -Function Get-Sc968RequiredEnvironment,Initialize-Sc968ManagedCaptureIdentity,Set-Sc968Micro6Environment,Set-Sc968RuneLiteEnvironment,Read-Sc968CaptureStatus,Wait-Sc968CaptureStatus,Assert-Sc968LogMarkers

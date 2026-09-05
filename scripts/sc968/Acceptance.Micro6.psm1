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
    [Parameter(Mandatory=$true)][string] $StartTriggerFile,
    [string] $CandidateId
  )
  $env:SC968_CAPTURE_ENABLED = 'true'
  $env:SC968_CHROMASCAPE_URL = $BaseUrl
  $env:CHROMASCAPE_BASE_URL = $BaseUrl
  $env:SC968_CAPTURE_TOKEN = $CaptureToken
  $env:SC968_CAPTURE_STATUS_FILE = $StatusFile
  # The client waits for this file before it opens the recording window, so the operator's login
  # and any pre-routine clicking stay outside the capture entirely.
  $env:SC968_CAPTURE_START_FILE = $StartTriggerFile
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
    [Parameter(Mandatory=$true)][object] $SourceIdentity,
    [Parameter(Mandatory=$true)][string] $StartTriggerFile
  )
  $env:SC968_CAPTURE_ENABLED = 'true'
  $env:SC968_CHROMASCAPE_URL = $BaseUrl
  $env:CHROMASCAPE_BASE_URL = $BaseUrl
  $env:SC968_CAPTURE_TOKEN = $CaptureToken
  $env:SC968_CAPTURE_STATUS_FILE = $StatusFile
  $env:SC968_CAPTURE_START_FILE = $StartTriggerFile
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
  $captureContracts = Join-Path $ContractsRoot 'java'
  if (-not (Test-Path -LiteralPath $captureContracts -PathType Container)) {
    throw "Integrated capture contracts directory is missing: $captureContracts"
  }
  New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

  $arguments = "--plan=$PlanPath --output=$OutputDirectory --fixture=pass --run-index=$RunIndex --certify-url=$BaseUrl --certification-mode=required"
  $seedExitCode = 1
  Push-Location $Micro6Root
  try {
    # Windows PowerShell 5.1 turns native stderr into NativeCommandError records when the
    # caller uses ErrorActionPreference=Stop. Gradle emits valid compiler warnings on stderr,
    # so keep those diagnostics visible without confusing them with a failed build; the native
    # exit code remains the only success criterion for this seed.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
      $ErrorActionPreference = 'Continue'
      & $gradle --no-daemon --no-watch-fs "-PaiRoutineCaptureContractsDir=$captureContracts" ":client:micro6StabilizationHarness" "-Pmicro6.stabilization.plan=$PlanPath" "--args=$arguments" 2>&1 | Out-Host
      $seedExitCode = $LASTEXITCODE
    } finally {
      $ErrorActionPreference = $previousErrorActionPreference
    }
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
  # The capture identity and the later patch proposal must share one immutable source group. The
  # Micro6 runtime serves its generated, packaged source catalog to the patch workflow, so using a
  # separate stabilization-receipt hash here creates a truthful but different source identity and
  # candidate registration correctly rejects the attempted replacement. The shadowJar gate has
  # already generated and tested this exact resource; bind the capture to the same bytes the
  # launched client packages and serves.
  $packagedSourceManifest = Join-Path $Micro6Root 'runelite-client\build\resources\main\micro6-contracts\fishing-source-manifest.json'
  if (-not (Test-Path -LiteralPath $packagedSourceManifest -PathType Leaf)) {
    throw "Micro6 packaged Fishing source manifest is missing for '$Name': $packagedSourceManifest"
  }
  $sourceBytes = [IO.File]::ReadAllBytes($packagedSourceManifest)
  try {
    $sourceManifest = [Text.Encoding]::UTF8.GetString($sourceBytes) | ConvertFrom-Json
  } catch {
    throw "Micro6 packaged Fishing source manifest is invalid JSON for '$Name'"
  }
  $sourceSnapshotId = [string]$sourceManifest.source_snapshot_id
  $sourceHash = [string]$sourceManifest.source_manifest_hash
  if ($sourceManifest.schema -ne 'micro6-source-manifest/v1' -or
      $sourceManifest.task_key -ne 'FISHING_TRAINING' -or
      [string]$sourceManifest.client_build_id -ne $micro6Revision -or
      $sourceSnapshotId -notmatch '^srcsnap_[0-9a-f]{24}$' -or
      $sourceHash -notmatch '^sha256:[0-9a-f]{64}$' -or
      $sourceSnapshotId -ne ('srcsnap_' + $sourceHash.Substring(7,24))) {
    throw "Micro6 packaged Fishing source identity is invalid or stale for '$Name'"
  }
  $sourcePath = Join-Path $OutputDirectory 'source-manifest.json'
  [IO.File]::WriteAllBytes($sourcePath, $sourceBytes)

  return [pscustomobject]@{
    RunId = [string]$manifest.run_id
    AttemptId = [string]$attempts[0].attempt_id
    EntryId = $entryId
    ExecutorInstanceId = [string]$manifest.micro6_instance_id
    SourceSnapshotId = $sourceSnapshotId
    SourceManifestHash = $sourceHash
    SourceManifestPath = $sourcePath
  }
}

function Read-Sc968CaptureStatus([string] $StatusFile) {
  if (-not (Test-Path -LiteralPath $StatusFile -PathType Leaf)) { return $null }
  $stream = $null
  $reader = $null
  try {
    # Windows PowerShell's Get-Content does not share DELETE. A Java atomic replacement can
    # therefore fail while this short-lived reader still has the old sidecar open. Explicitly
    # share READ/WRITE/DELETE and dispose the handle before returning so the publisher and reader
    # have a cooperative replacement contract on Windows.
    $share = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
    $stream = [IO.File]::Open($StatusFile, [IO.FileMode]::Open, [IO.FileAccess]::Read, $share)
    $reader = New-Object -TypeName System.IO.StreamReader -ArgumentList @($stream, [Text.Encoding]::UTF8, $true)
    $value = ($reader.ReadToEnd() | ConvertFrom-Json)
    if ($value.schema -ne 'sc968-capture-status/v1') { return $null }
    return $value
  } catch { return $null }
  finally {
    if ($null -ne $reader) { $reader.Dispose() }
    elseif ($null -ne $stream) { $stream.Dispose() }
  }
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
    if ($status -and $status.status_publication_failed -eq $true) {
      throw "Capture status sidecar publication failed for '$StatusFile'"
    }
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

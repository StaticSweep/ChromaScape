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
    [string] $ArtifactMicro6Root = '',
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
  # Micro6 runtime serves the signed, content-addressed source bundle generated after shadowJar;
  # never bind acceptance evidence to the legacy embedded compatibility manifest.
  $sourceBundleRoot = [Environment]::GetEnvironmentVariable('MICRO6_SOURCE_BUNDLE_ROOT')
  if ([string]::IsNullOrWhiteSpace($sourceBundleRoot) -or
      -not (Test-Path -LiteralPath $sourceBundleRoot -PathType Container)) {
    throw "Authoritative Micro6 source bundle is missing for '$Name': $sourceBundleRoot"
  }
  $currentPath = Join-Path $sourceBundleRoot 'current.json'
  if (-not (Test-Path -LiteralPath $currentPath -PathType Leaf)) {
    throw "Authoritative Micro6 source bundle current.json is missing for '$Name': $currentPath"
  }
  try {
    $current = Get-Content -LiteralPath $currentPath -Raw | ConvertFrom-Json
  } catch {
    throw "Authoritative Micro6 source bundle current.json is invalid for '$Name'"
  }
  $bundleId = [string]$current.bundle_id
  if ($current.schema -ne 'task-source-current/v1' -or $bundleId -notmatch '^srcbundle_[0-9a-f]{24}$' -or
      [string]$current.path -ne ('bundles/' + $bundleId)) {
    throw "Authoritative Micro6 source bundle pointer is invalid for '$Name'"
  }
  $bundleDirectory = Join-Path $sourceBundleRoot ('bundles\' + $bundleId)
  $completeMarker = Join-Path $bundleDirectory 'COMPLETE'
  if (-not (Test-Path -LiteralPath $completeMarker -PathType Leaf) -or
      (Get-Item -LiteralPath $completeMarker).Length -ne 0) {
    throw "Authoritative Micro6 source bundle is incomplete for '$Name'"
  }
  $bundleJsonPath = Join-Path $bundleDirectory 'bundle.json'
  try {
    $bundleJson = Get-Content -LiteralPath $bundleJsonPath -Raw | ConvertFrom-Json
  } catch {
    throw "Authoritative Micro6 source bundle metadata is invalid for '$Name'"
  }
  if ($bundleJson.schema -ne 'task-source-bundle/v1' -or
      [string]$bundleJson.bundle_id -ne $bundleId) {
    throw "Authoritative Micro6 source bundle metadata does not match its pointer for '$Name'"
  }
  $manifestEntries = @($bundleJson.manifests | Where-Object { $_.task_key -eq 'FISHING_TRAINING' })
  if ($manifestEntries.Count -ne 1 -or [string]$manifestEntries[0].path -ne 'manifests/FISHING_TRAINING.json') {
    throw "Authoritative Fishing source manifest is not registered in the bundle for '$Name'"
  }
  $packagedSourceManifest = Join-Path $bundleDirectory 'manifests\FISHING_TRAINING.json'
  if (-not (Test-Path -LiteralPath $packagedSourceManifest -PathType Leaf)) {
    throw "Authoritative Fishing source manifest is missing for '$Name': $packagedSourceManifest"
  }
  $sourceBytes = [IO.File]::ReadAllBytes($packagedSourceManifest)
  try {
    $sourceManifest = [Text.Encoding]::UTF8.GetString($sourceBytes) | ConvertFrom-Json
  } catch {
    throw "Authoritative Fishing source manifest is invalid JSON for '$Name'"
  }
  $sourceSnapshotId = [string]$sourceManifest.source_snapshot_id
  $sourceHash = [string]$sourceManifest.source_manifest_hash
  $micro6Tree = (& git -C $Micro6Root rev-parse 'HEAD^{tree}' 2>$null).Trim()
  # RuntimeMicro6Root may be a separate clean checkout used only for the interactive clients. The
  # signed source bundle and its shaded artifact are produced by the gated Micro6 checkout before
  # the runtime driver starts, so resolve artifact identity from that checkout while continuing to
  # bind repository commit/tree identity to the runtime checkout. Require both checkouts to be the
  # same immutable revision and tree before trusting the preflight artifact identity.
  $artifactRoot = if ([string]::IsNullOrWhiteSpace($ArtifactMicro6Root)) { $Micro6Root } else { $ArtifactMicro6Root }
  if (-not (Test-Path -LiteralPath $artifactRoot -PathType Container)) {
    throw "Gated Micro6 artifact checkout is missing for '$Name': $artifactRoot"
  }
  $artifactRevision = (& $git.Source -C $artifactRoot rev-parse HEAD).Trim()
  $artifactTree = (& $git.Source -C $artifactRoot rev-parse 'HEAD^{tree}' 2>$null).Trim()
  if ($LASTEXITCODE -ne 0 -or $artifactRevision -ne $micro6Revision -or $artifactTree -ne $micro6Tree) {
    throw "Gated and runtime Micro6 source identities do not match for '$Name'"
  }
  $artifactIdentityPath = Join-Path $artifactRoot 'runelite-client\build\identity\micro6-artifact.properties'
  $artifactDigestLine = if (Test-Path -LiteralPath $artifactIdentityPath -PathType Leaf) {
    Get-Content -LiteralPath $artifactIdentityPath | Where-Object { $_ -match '^artifact_digest=' } | Select-Object -First 1
  } else { $null }
  $artifactDigest = if ($null -eq $artifactDigestLine) { '' } else { ([string]$artifactDigestLine).Substring('artifact_digest='.Length).Trim() }
  $sourceEnvironment = [Environment]::GetEnvironmentVariable('MICRO6_SOURCE_ENVIRONMENT')
  $sourceKeyId = [Environment]::GetEnvironmentVariable('MICRO6_SOURCE_VERIFICATION_KEY_ID')
  if ($sourceManifest.schema -ne 'task-source-manifest/v1' -or
      $sourceManifest.task_key -ne 'FISHING_TRAINING' -or
      $sourceManifest.status -ne 'AVAILABLE' -or
      [string]$sourceManifest.environment -ne $sourceEnvironment -or
      [string]$sourceManifest.repository.repository_id -ne 'micro6' -or
      [string]$sourceManifest.repository.commit_id -ne $micro6Revision -or
      [string]$sourceManifest.repository.tree_id -ne $micro6Tree -or
      $sourceManifest.repository.clean -ne $true -or
      [string]$sourceManifest.build.build_id -ne $micro6Revision -or
      [string]$sourceManifest.build.artifact_digest -ne $artifactDigest -or
      $artifactDigest -notmatch '^sha256:[0-9a-f]{64}$' -or
      [string]$bundleJson.artifact_digest -ne $artifactDigest -or
      $sourceManifest.signature.algorithm -ne 'Ed25519' -or
      [string]::IsNullOrWhiteSpace($sourceKeyId) -or
      [string]$sourceManifest.signature.key_id -ne $sourceKeyId -or
      [string]$manifestEntries[0].source_manifest_hash -ne $sourceHash -or
      [string]$manifestEntries[0].source_snapshot_id -ne $sourceSnapshotId -or
      $sourceSnapshotId -notmatch '^srcsnap_[0-9a-f]{24}$' -or
      $sourceHash -notmatch '^sha256:[0-9a-f]{64}$' -or
      $sourceSnapshotId -ne ('srcsnap_' + $sourceHash.Substring(7,24))) {
    throw "Authoritative Micro6 Fishing source identity is invalid or stale for '$Name'"
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
    # The canonical source identity excludes envelope/signature fields. It is not the
    # SHA-256 of the signed JSON transport bytes; retain both trust boundaries.
    SourceManifestFileHash = 'sha256:' + ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($sourceBytes))).Replace('-','').ToLowerInvariant()
    SourceRepositoryId = [string]$sourceManifest.repository.repository_id
    SourceCommitId = [string]$sourceManifest.repository.commit_id
    SourceTreeId = [string]$sourceManifest.repository.tree_id
    SourceBuildId = [string]$sourceManifest.build.build_id
    SourceArtifactDigest = [string]$sourceManifest.build.artifact_digest
    SourceSignatureVerified = $true
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

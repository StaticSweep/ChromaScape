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
    [Parameter(Mandatory=$true)][string] $SpoolDirectory
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
  # A standard RuneLite capture must never inherit Micro6 executor/candidate capabilities from the
  # baseline or candidate launch that preceded it in the same acceptance process.
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_ENABLED = 'false'
  $env:MICRO6_REMOTE_ACCOUNTBUILDER_CANDIDATE_ID = ''
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

Export-ModuleMember -Function Get-Sc968RequiredEnvironment,Set-Sc968Micro6Environment,Set-Sc968RuneLiteEnvironment,Read-Sc968CaptureStatus,Wait-Sc968CaptureStatus,Assert-Sc968LogMarkers

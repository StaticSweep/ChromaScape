#Requires -Version 5.1
[CmdletBinding()]
param(
  [string] $ContractsRoot = 'C:\Dev\IdeaProjects\contracts',
  [string] $LeeuwBotRoot = 'C:\Dev\IdeaProjects\leeuwbotbackend\leeuwbot-api\leeuwbotapi',
  [string] $ChromaScapeRoot = 'C:\Dev\IdeaProjects\ChromaScape',
  [string] $Micro6Root = 'C:\Dev\IdeaProjects\micro6',
  [string] $RuneLiteRoot = 'C:\Dev\IdeaProjects\runelite',
  [string] $EvidenceRoot = '',
  [switch] $PreparedWorktrees,
  [switch] $PreflightOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Set-DefaultEnvironment([string] $Name, [string] $Value) {
  if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))) {
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
  }
}

function Get-TextSha256([string] $Value) {
  $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
  $digest = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
  return 'sha256:' + (($digest | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Require-Directory([string] $Name, [string] $Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "$Name is missing: $Path" }
}

function Require-CleanRepository([string] $Name, [string] $Path) {
  Push-Location $Path
  try {
    $state = (& git status --porcelain=v1 --untracked-files=all 2>$null) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "$Name is not a Git repository: $Path" }
    if (-not [string]::IsNullOrWhiteSpace($state)) { throw "$Name must be clean before acceptance:`n$state" }
  } finally { Pop-Location }
}

function New-AcceptanceWorktree([string] $SourceRoot, [string] $Destination) {
  $revision = (& git -C $SourceRoot rev-parse HEAD 2>$null).Trim()
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
    throw "Cannot resolve Git revision for $SourceRoot"
  }
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
  & git -c core.longpaths=true -C $SourceRoot worktree add --detach $Destination $revision | Out-Host
  $gitExitCode = $LASTEXITCODE
  if ($gitExitCode -ne 0) {
    # Git can register a worktree before checkout fails (for example, on a
    # long Windows filename), so clean both the registration and directory.
    $null = & git -c core.longpaths=true -C $SourceRoot worktree remove --force $Destination 2>$null
    Remove-Item -LiteralPath $Destination -Recurse -Force -ErrorAction SilentlyContinue
    throw "Cannot create clean acceptance worktree for $SourceRoot"
  }
  return [pscustomobject]@{ Source=$SourceRoot; Destination=$Destination; Revision=$revision }
}

if ([Environment]::OSVersion.Platform.ToString() -notmatch 'Win') {
  throw 'Run this command from native Windows PowerShell, never WSL.'
}

Require-Directory 'Contracts checkout' $ContractsRoot
Require-Directory 'LeeuwBotAPI checkout' $LeeuwBotRoot
Require-Directory 'ChromaScape checkout' $ChromaScapeRoot
Require-Directory 'Micro6 checkout' $Micro6Root
Require-Directory 'RuneLite checkout' $RuneLiteRoot

if (-not $PreparedWorktrees) {
  $sourceChromaRoot = $ChromaScapeRoot
  $sourceLeeuwRoot = (& git -C $LeeuwBotRoot rev-parse --show-toplevel 2>$null).Trim()
  if ($LASTEXITCODE -ne 0) { throw "LeeuwBotAPI is not inside a Git checkout: $LeeuwBotRoot" }
  $leeuwRelative = $LeeuwBotRoot.Substring($sourceLeeuwRoot.Length).TrimStart('\','/')
  # Keep this deliberately short: Micro6 contains generated Java filenames
  # that approach the legacy Windows MAX_PATH limit.
  $worktreeBase = Join-Path 'C:\w' ('s968-' + (Get-Date -Format 'yyyyMMddHHmmss'))
  $created = @()
  try {
    $contractsWorktree = Join-Path $worktreeBase 'contracts'
    $leeuwWorktree = Join-Path $worktreeBase 'leeuwbot'
    $chromaWorktree = Join-Path $worktreeBase 'chromascape'
    $microWorktree = Join-Path $worktreeBase 'micro6'
    $runeliteWorktree = Join-Path $worktreeBase 'runelite'
    $created += New-AcceptanceWorktree $ContractsRoot $contractsWorktree
    $created += New-AcceptanceWorktree $sourceLeeuwRoot $leeuwWorktree
    $created += New-AcceptanceWorktree $ChromaScapeRoot $chromaWorktree
    $created += New-AcceptanceWorktree $Micro6Root $microWorktree
    $created += New-AcceptanceWorktree $RuneLiteRoot $runeliteWorktree
    Write-Output "Prepared clean acceptance worktrees under $worktreeBase"
    & $PSCommandPath -ContractsRoot $contractsWorktree `
      -LeeuwBotRoot (Join-Path $leeuwWorktree $leeuwRelative) `
      -ChromaScapeRoot $chromaWorktree -Micro6Root $microWorktree `
      -RuneLiteRoot $runeliteWorktree `
      -EvidenceRoot (Join-Path $sourceChromaRoot 'output\sc968') `
      -PreparedWorktrees -PreflightOnly:$PreflightOnly
    $result = $LASTEXITCODE
    if ($null -eq $result) { $result = 0 }
    exit $result
  } finally {
    foreach ($worktree in @($created | Sort-Object -Property Destination -Descending)) {
      $null = & git -c core.longpaths=true -C $worktree.Source worktree remove --force $worktree.Destination 2>$null
    }
    if (Test-Path -LiteralPath $worktreeBase) {
      Remove-Item -LiteralPath $worktreeBase -Recurse -Force -ErrorAction SilentlyContinue
    }
  }
}

$acceptance = Join-Path $ChromaScapeRoot 'scripts\sc968-acceptance.ps1'
$microLauncher = Join-Path $Micro6Root 'scripts\launch-sc968-micro6.ps1'
$runeliteLauncher = Join-Path $RuneLiteRoot 'scripts\launch-sc968-runelite.ps1'
foreach ($required in @($acceptance, $microLauncher, $runeliteLauncher)) {
  if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Required acceptance script is missing: $required" }
}

foreach ($repository in ([ordered]@{
    Contracts=$ContractsRoot; LeeuwBotAPI=$LeeuwBotRoot; ChromaScape=$ChromaScapeRoot;
    Micro6=$Micro6Root; RuneLite=$RuneLiteRoot
  }).GetEnumerator()) {
  Require-CleanRepository $repository.Key $repository.Value
}

if ($PreflightOnly) {
  Write-Output 'SC-967/SC-968 one-command preflight PASS'
  exit 0
}

$outputRoot = if ([string]::IsNullOrWhiteSpace($EvidenceRoot)) {
  Join-Path $ChromaScapeRoot 'output\sc968'
} else {
  $EvidenceRoot
}
if (Test-Path -LiteralPath $outputRoot) {
  $archive = Join-Path (Split-Path -Parent $outputRoot) ('sc968-archive-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
  Move-Item -LiteralPath $outputRoot -Destination $archive
  Write-Output "Archived previous SC-968 output to $archive"
}
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$secretRoot = Join-Path $outputRoot '.runtime-secrets'
$trustedRoot = Join-Path $secretRoot 'trusted-candidate-keys'
New-Item -ItemType Directory -Force -Path $trustedRoot | Out-Null

$openssl = Get-Command openssl.exe -ErrorAction SilentlyContinue
if ($null -eq $openssl) { $openssl = Get-Command openssl -ErrorAction SilentlyContinue }
if ($null -eq $openssl) { throw 'OpenSSL is required to create the temporary Ed25519 acceptance key' }

$keyId = 'sc968-local-acceptance'
$privateKey = Join-Path $secretRoot 'candidate-private-key.pem'
$publicKey = Join-Path $secretRoot 'candidate-public-key.pem'
$publicDer = Join-Path $secretRoot 'candidate-public-key.der'
& $openssl.Source genpkey -algorithm ED25519 -out $privateKey
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to generate the Ed25519 private key' }
& $openssl.Source pkey -in $privateKey -pubout -out $publicKey
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to derive the Ed25519 public key' }
& $openssl.Source pkey -pubin -in $publicKey -outform DER -out $publicDer
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to encode the Ed25519 public key' }
Copy-Item -LiteralPath $publicKey -Destination (Join-Path $trustedRoot "$keyId.pem")
$publicRing = $keyId + '=' + [Convert]::ToBase64String([IO.File]::ReadAllBytes($publicDer))

Set-DefaultEnvironment 'SC968_CAPTURE_TOKEN' ([Guid]::NewGuid().ToString('N'))
Set-DefaultEnvironment 'SC968_OPERATOR_TOKEN' ([Guid]::NewGuid().ToString('N'))
Set-DefaultEnvironment 'SC968_OPERATOR_ID' 'sc968-local-operator'
Set-DefaultEnvironment 'SC968_REGISTRY_TOKEN' ([Guid]::NewGuid().ToString('N'))
Set-DefaultEnvironment 'SC968_PUBLIC_KEY_RING' $publicRing
Set-DefaultEnvironment 'SC968_REGISTRY_TRUSTED_KEYS_DIR' $trustedRoot
Set-DefaultEnvironment 'SC968_SIGNING_PRIVATE_KEY_PATH' $privateKey
Set-DefaultEnvironment 'SC968_SIGNING_PUBLIC_KEY_PATH' $publicKey
Set-DefaultEnvironment 'SC968_SIGNING_KEY_ID' $keyId
$microVersionLine = Get-Content -LiteralPath (Join-Path $Micro6Root 'gradle.properties') |
  Where-Object { $_ -match '^project\.build\.version=(.+)$' } | Select-Object -First 1
if ($null -eq $microVersionLine) {
  throw 'Micro6 gradle.properties has no project.build.version'
}
$microVersionMatch = [regex]::Match([string]$microVersionLine, '^project\.build\.version=(.+)$')
if (-not $microVersionMatch.Success) { throw 'Micro6 project.build.version is malformed' }
Set-DefaultEnvironment 'SC968_EXPECTED_ARTIFACT_NAME' ("client-{0}-shaded.jar" -f $microVersionMatch.Groups[1].Value.Trim())
Set-DefaultEnvironment 'SC968_MICRO6_LAUNCH_SCRIPT' $microLauncher
Set-DefaultEnvironment 'SC968_RUNELITE_LAUNCH_SCRIPT' $runeliteLauncher
Set-DefaultEnvironment 'SC968_REGISTRY_URL' 'http://127.0.0.1:8081'
Set-DefaultEnvironment 'SC968_TASK_KEY' 'FISHING_TRAINING'
Set-DefaultEnvironment 'SC968_SETTINGS_HASH' (Get-TextSha256 '{}')
Set-DefaultEnvironment 'SC968_PROFILE_ID' 'sc968-fishing-acceptance-v1'
Set-DefaultEnvironment 'SC968_PROFILE_HASH' (Get-TextSha256 '{"profile_id":"sc968-fishing-acceptance-v1","task_key":"FISHING_TRAINING"}')
Set-DefaultEnvironment 'SC968_CATALOG_HASH' (Get-TextSha256 '{"schema":"sc968-acceptance-catalog/v1","tasks":["FISHING_TRAINING"]}')

$listener = Get-NetTCPConnection -State Listen -LocalPort 8090 -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $listener) {
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
  if ($null -eq $process -or ([string]$process.CommandLine -notmatch '(?i)chromascape')) {
    throw "Port 8090 is owned by an unrecognized process (PID $($listener.OwningProcess)); stop it and rerun"
  }
  Write-Output "Stopping the existing ChromaScape process on port 8090 (PID $($listener.OwningProcess))"
  Stop-Process -Id $listener.OwningProcess -Force
  Start-Sleep -Seconds 2
}

Write-Output ''
Write-Output 'SC-967/SC-968 native acceptance is starting.'
Write-Output 'Three clients will appear. In each client, log in if needed and use Net on a Fishing spot once when prompted.'
Write-Output 'Do not close a client until PowerShell advances to the next phase.'
Write-Output ''

& $acceptance -ContractsRoot $ContractsRoot -LeeuwBotRoot $LeeuwBotRoot `
  -ChromaScapeRoot $ChromaScapeRoot -Micro6Root $Micro6Root -RuneLiteRoot $RuneLiteRoot `
  -OutputRoot $outputRoot
if ($LASTEXITCODE -ne 0) { throw "SC-967/SC-968 acceptance failed. Inspect $outputRoot\logs" }

$sc967 = Join-Path $outputRoot 'sc-967-demo-manifest.json'
$sc968 = Join-Path $outputRoot 'sc-968-acceptance-manifest.json'
foreach ($manifest in @($sc967, $sc968)) {
  $value = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json -Depth 80
  if ($value.overall -ne 'PASS') { throw "Acceptance manifest is not PASS: $manifest" }
  Write-Output ("PASS {0} sha256:{1}" -f $manifest, (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant())
}
Write-Output 'ACCEPTANCE PASS — SC-967 and SC-968 evidence is ready.'

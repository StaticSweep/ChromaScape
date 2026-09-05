#Requires -Version 5.1
[CmdletBinding()]
param(
  [string] $ContractsRoot = 'C:\Dev\IdeaProjects\contracts',
  [string] $LeeuwBotRoot = 'C:\Dev\IdeaProjects\leeuwbotbackend\leeuwbot-api\leeuwbotapi',
  [string] $ChromaScapeRoot = 'C:\Dev\IdeaProjects\ChromaScape',
  [string] $Micro6Root = 'C:\Dev\IdeaProjects\micro6',
  [string] $RuntimeMicro6Root = '',
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

function Set-Sc968Java17 {
  $homes = @()
  if (-not [string]::IsNullOrWhiteSpace($env:SC968_JAVA17_HOME)) {
    $homes += $env:SC968_JAVA17_HOME
  }
  foreach ($spec in @(
      @{ Root=(Join-Path $env:ProgramFiles 'Eclipse Adoptium'); Pattern='jdk-17*' },
      @{ Root=(Join-Path $env:ProgramFiles 'Amazon Corretto'); Pattern='jdk17*' },
      @{ Root=(Join-Path $env:ProgramFiles 'Amazon Corretto'); Pattern='jdk-17*' },
      @{ Root=(Join-Path $env:ProgramFiles 'Microsoft'); Pattern='jdk-17*' },
      @{ Root=(Join-Path $env:USERPROFILE '.jdks'); Pattern='*17*' }
    )) {
    if (Test-Path -LiteralPath $spec.Root -PathType Container) {
      $homes += Get-ChildItem -LiteralPath $spec.Root -Directory -Filter $spec.Pattern -ErrorAction SilentlyContinue |
        Sort-Object -Property Name -Descending | Select-Object -ExpandProperty FullName
    }
  }
  foreach ($javaHome in @($homes | Select-Object -Unique)) {
    $java = Join-Path $javaHome 'bin\java.exe'
    if (Test-Path -LiteralPath $java -PathType Leaf) {
      $env:JAVA_HOME = $javaHome
      $env:Path = (Join-Path $javaHome 'bin') + ';' + $env:Path
      Write-Output "Using Java 17 from $javaHome"
      return
    }
  }
  throw 'Java 17 JDK was not found. Set SC968_JAVA17_HOME to the installed JDK 17 directory.'
}

function Set-Sc968Maven {
  $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if ($null -eq $mavenCommand) { $mavenCommand = Get-Command mvn.exe -ErrorAction SilentlyContinue }
  if ($null -eq $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue }
  $mavenPath = if ($null -ne $mavenCommand) { $mavenCommand.Source } else { $null }
  if ([string]::IsNullOrWhiteSpace($mavenPath)) {
    $jetBrainsRoot = Join-Path $env:ProgramFiles 'JetBrains'
    if (Test-Path -LiteralPath $jetBrainsRoot -PathType Container) {
      $mavenPath = Get-ChildItem -LiteralPath $jetBrainsRoot -Directory -ErrorAction SilentlyContinue |
        Sort-Object -Property Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    }
  }
  if ([string]::IsNullOrWhiteSpace($mavenPath)) {
    throw 'Maven was not found on PATH or in the IntelliJ installation.'
  }
  $env:SC968_MAVEN_COMMAND = $mavenPath
  Write-Output "Using Maven from $mavenPath"
}

function Set-Sc968Go {
  $goCommand = Get-Command go.exe -ErrorAction SilentlyContinue
  $goPath = if ($null -ne $goCommand) { $goCommand.Source } else { $null }
  $installedGo = Join-Path $env:ProgramFiles 'Go\bin\go.exe'
  if ([string]::IsNullOrWhiteSpace($goPath) -and (Test-Path -LiteralPath $installedGo -PathType Leaf)) {
    $goPath = $installedGo
  }
  $goVersion = '1.24.13'
  $archiveHash = '40b16bc8f00540a2cb02dff4de72b73e966fdd8d65f95e33d8e4080b48a2459a'
  $toolRoot = Join-Path 'C:\w\tools' ("go$goVersion")
  $portableGo = Join-Path $toolRoot 'go\bin\go.exe'
  if ([string]::IsNullOrWhiteSpace($goPath) -and (Test-Path -LiteralPath $portableGo -PathType Leaf)) {
    $goPath = $portableGo
  }
  if ([string]::IsNullOrWhiteSpace($goPath)) {
    $downloadRoot = 'C:\w\downloads'
    $archive = Join-Path $downloadRoot ("go$goVersion.windows-amd64.zip")
    New-Item -ItemType Directory -Force -Path $downloadRoot, (Split-Path -Parent $toolRoot) | Out-Null
    if (Test-Path -LiteralPath $archive -PathType Leaf) {
      $existingHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
      if ($existingHash -ne $archiveHash) { Remove-Item -LiteralPath $archive -Force }
    }
    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
      Write-Output "Downloading portable Go $goVersion from go.dev"
      Invoke-WebRequest -UseBasicParsing -Uri ("https://go.dev/dl/go$goVersion.windows-amd64.zip") -OutFile $archive
    }
    $downloadHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($downloadHash -ne $archiveHash) { throw "Portable Go archive checksum mismatch: $downloadHash" }
    $extractRoot = $toolRoot + '-installing-' + [Guid]::NewGuid().ToString('N')
    try {
      Expand-Archive -LiteralPath $archive -DestinationPath $extractRoot -Force
      if (-not (Test-Path -LiteralPath (Join-Path $extractRoot 'go\bin\go.exe') -PathType Leaf)) {
        throw 'Portable Go archive did not contain go\bin\go.exe'
      }
      Move-Item -LiteralPath $extractRoot -Destination $toolRoot
    } finally {
      if (Test-Path -LiteralPath $extractRoot) {
        Remove-Item -LiteralPath $extractRoot -Recurse -Force -ErrorAction SilentlyContinue
      }
    }
    $goPath = $portableGo
  }
  $goBin = Split-Path -Parent $goPath
  $goStateRoot = 'C:\w\go'
  $goCache = Join-Path $goStateRoot 'cache'
  $goModuleCache = Join-Path $goStateRoot 'mod'
  $goTemp = Join-Path $goStateRoot 'tmp'
  New-Item -ItemType Directory -Force -Path $goCache, $goModuleCache, $goTemp | Out-Null
  $env:Path = $goBin + ';' + $env:Path
  $env:GOCACHE = $goCache
  $env:GOMODCACHE = $goModuleCache
  $env:GOTMPDIR = $goTemp
  $env:SC968_GO_COMMAND = $goPath
  Write-Output "Using Go from $goPath"
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
      -ChromaScapeRoot $chromaWorktree -Micro6Root $microWorktree -RuntimeMicro6Root $RuntimeMicro6Root `
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

Set-Sc968Java17
Set-Sc968Maven
Set-Sc968Go

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

$opensslCommand = Get-Command openssl.exe -ErrorAction SilentlyContinue
if ($null -eq $opensslCommand) { $opensslCommand = Get-Command openssl -ErrorAction SilentlyContinue }
$opensslPath = if ($null -ne $opensslCommand) { $opensslCommand.Source } else { $null }
if ([string]::IsNullOrWhiteSpace($opensslPath)) {
  $gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
  if ($null -ne $gitCommand) {
    $gitRoot = Split-Path -Parent (Split-Path -Parent $gitCommand.Source)
    foreach ($candidate in @(
        (Join-Path $gitRoot 'mingw64\bin\openssl.exe'),
        (Join-Path $gitRoot 'usr\bin\openssl.exe')
      )) {
      if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        $opensslPath = $candidate
        break
      }
    }
  }
}
if ([string]::IsNullOrWhiteSpace($opensslPath)) {
  throw 'OpenSSL is required to create the temporary Ed25519 acceptance key (Git for Windows bundled OpenSSL was not found)'
}

$keyId = 'sc968-local-acceptance'
$privateKey = Join-Path $secretRoot 'candidate-private-key.pem'
$publicKey = Join-Path $secretRoot 'candidate-public-key.pem'
$publicDer = Join-Path $secretRoot 'candidate-public-key.der'
& $opensslPath genpkey -algorithm ED25519 -out $privateKey
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to generate the Ed25519 private key' }
& $opensslPath pkey -in $privateKey -pubout -out $publicKey
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to derive the Ed25519 public key' }
& $opensslPath pkey -pubin -in $publicKey -outform DER -out $publicDer
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to encode the Ed25519 public key' }
Copy-Item -LiteralPath $publicKey -Destination (Join-Path $trustedRoot "$keyId.pem")
$publicRing = $keyId + '=' + [Convert]::ToBase64String([IO.File]::ReadAllBytes($publicDer))

# Source provenance has its own trust boundary. Use a separate ephemeral Ed25519 key so the
# signed authoritative Micro6 source bundle cannot be confused with the candidate registry key.
# Private key bytes remain process-scoped and are never written to acceptance evidence logs.
$sourceKeyId = 'sc968-source-local-acceptance'
$sourcePrivateKey = Join-Path $secretRoot 'source-private-key.pem'
$sourcePublicKey = Join-Path $secretRoot 'source-public-key.pem'
$sourcePublicDer = Join-Path $secretRoot 'source-public-key.der'
$sourcePrivateDer = Join-Path $secretRoot 'source-private-key.der'
& $opensslPath genpkey -algorithm ED25519 -out $sourcePrivateKey
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to generate the source Ed25519 private key' }
& $opensslPath pkey -in $sourcePrivateKey -pubout -out $sourcePublicKey
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to derive the source Ed25519 public key' }
& $opensslPath pkey -pubin -in $sourcePublicKey -outform DER -out $sourcePublicDer
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to encode the source Ed25519 public key' }
& $opensslPath pkey -in $sourcePrivateKey -outform DER -out $sourcePrivateDer
if ($LASTEXITCODE -ne 0) { throw 'OpenSSL failed to encode the source Ed25519 private key' }
$sourcePublicBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($sourcePublicDer))
$sourcePrivateBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($sourcePrivateDer))
$sourceEnvironment = 'sc968-native-acceptance'
$sourceBundleRoot = Join-Path $outputRoot 'source-bundle'

$env:MICRO6_SOURCE_BUNDLE_ROOT = $sourceBundleRoot
$env:MICRO6_SOURCE_SIGNING_PRIVATE_KEY_BASE64 = $sourcePrivateBase64
$env:MICRO6_SOURCE_SIGNING_KEY_ID = $sourceKeyId
$env:MICRO6_SOURCE_VERIFICATION_PUBLIC_KEY_BASE64 = $sourcePublicBase64
$env:MICRO6_SOURCE_VERIFICATION_KEY_ID = $sourceKeyId
$env:MICRO6_SOURCE_ENVIRONMENT = $sourceEnvironment

# ChromaScape consumes the same trust material and environment through its canonical source
# properties. Keep the legacy aliases identical because startup validation rejects split trust
# configuration instead of allowing the two services to disagree.
$env:CHROMASCAPE_SOURCE_REQUIRE_SIGNATURE = 'true'
$env:CHROMASCAPE_SOURCE_ALLOW_LEGACY = 'false'
$env:CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64 = $sourcePublicBase64
$env:CHROMASCAPE_SOURCE_PUBLIC_KEY_ID = $sourceKeyId
$env:CHROMASCAPE_SOURCE_ENVIRONMENT = $sourceEnvironment
$env:MICRO6_SOURCE_REQUIRE_SIGNATURE = 'true'
$env:MICRO6_SOURCE_ALLOW_LEGACY = 'false'
$env:MICRO6_SOURCE_PUBLIC_KEY_BASE64 = $sourcePublicBase64
$env:MICRO6_SOURCE_PUBLIC_KEY_ID = $sourceKeyId

Set-DefaultEnvironment 'SC968_CAPTURE_TOKEN' ([Guid]::NewGuid().ToString('N'))
Set-DefaultEnvironment 'SC968_OPERATOR_TOKEN' ([Guid]::NewGuid().ToString('N'))
Set-DefaultEnvironment 'SC968_OPERATOR_ID' 'sc968-local-operator'
# Materialize the server-facing names in the top-level orchestrator before it launches the
# acceptance PowerShell. Setting them for the first time in the nested runtime driver proved too
# late for bootRun on Windows: ChromaScape received other runtime variables but started with an
# empty capture token. Keeping the mapping process-scoped avoids command-line/log disclosure and
# makes the one-command run behave the same as a shell that already exported these values.
$env:AI_ROUTINE_CAPTURE_TOKEN = $env:SC968_CAPTURE_TOKEN
$env:AI_ROUTINE_OPERATOR_TOKEN = $env:SC968_OPERATOR_TOKEN
$env:AI_ROUTINE_OPERATOR_ID = $env:SC968_OPERATOR_ID
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
Set-DefaultEnvironment 'SC968_CHROMASCAPE_URL' 'http://127.0.0.1:18090'
Set-DefaultEnvironment 'SC968_REGISTRY_URL' 'http://127.0.0.1:8181'
Set-DefaultEnvironment 'SC968_TASK_KEY' 'FISHING_TRAINING'
Set-DefaultEnvironment 'SC968_SETTINGS_HASH' (Get-TextSha256 '{}')
Set-DefaultEnvironment 'SC968_PROFILE_ID' 'sc968-fishing-acceptance-v1'
Set-DefaultEnvironment 'SC968_PROFILE_HASH' (Get-TextSha256 '{"profile_id":"sc968-fishing-acceptance-v1","task_key":"FISHING_TRAINING"}')
Set-DefaultEnvironment 'SC968_CATALOG_HASH' (Get-TextSha256 '{"schema":"sc968-acceptance-catalog/v1","tasks":["FISHING_TRAINING"]}')

$chromaUri = $null
if (-not [Uri]::TryCreate($env:SC968_CHROMASCAPE_URL, [UriKind]::Absolute, [ref]$chromaUri) -or
    $chromaUri.Scheme -ne 'http' -or -not $chromaUri.IsLoopback -or $chromaUri.Port -lt 1 -or
    $chromaUri.AbsolutePath -ne '/' -or -not [string]::IsNullOrWhiteSpace($chromaUri.Query) -or
    -not [string]::IsNullOrWhiteSpace($chromaUri.Fragment)) {
  throw 'SC968_CHROMASCAPE_URL must be a loopback HTTP origin with an explicit port and no path, query, or fragment'
}
if ($chromaUri.Port -eq 8090) {
  throw 'SC968 acceptance must use an isolated port; development port 8090 is not allowed'
}
$chromaPort = $chromaUri.Port
$listener = Get-NetTCPConnection -State Listen -LocalPort $chromaPort -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $listener) {
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)" -ErrorAction SilentlyContinue
  if ($null -eq $process -or ([string]$process.CommandLine -notmatch '(?i)chromascape')) {
    throw "ChromaScape acceptance port $chromaPort is owned by an unrecognized process (PID $($listener.OwningProcess)); stop it or set SC968_CHROMASCAPE_URL to a free HTTP origin"
  }
  Write-Output "Stopping the existing ChromaScape acceptance process on port $chromaPort (PID $($listener.OwningProcess))"
  Stop-Process -Id $listener.OwningProcess -Force
  Start-Sleep -Seconds 2
}

# The acceptance starts its own candidate registry on the port named by SC968_REGISTRY_URL, which
# plugin-service honours through PLUGIN_SERVICE_PORT. It deliberately avoids 8081 so the run does
# not contend with a leeuwbot-plugin instance the machine is using for other work. If something
# already holds the chosen port the registry silently fails to bind and the readiness probe is
# answered by the wrong service, which strands the run for the full probe timeout before any client
# is launched. Fail here instead, with the remedy, because the gates ahead of it take many minutes.
$registryPort = ([Uri]$env:SC968_REGISTRY_URL).Port
$registryListener = Get-NetTCPConnection -State Listen -LocalPort $registryPort -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -ne $registryListener) {
  $registryOwner = Get-CimInstance Win32_Process -Filter "ProcessId=$($registryListener.OwningProcess)" -ErrorAction SilentlyContinue
  $registryOwnerName = if ($null -eq $registryOwner) { 'an unknown process' } else { $registryOwner.Name }
  throw ("Port {0} is held by {1} (PID {2}); the SC-968 candidate registry cannot bind it. Stop that process, or set SC968_REGISTRY_URL to a free port, then rerun." -f $registryPort, $registryOwnerName, $registryListener.OwningProcess)
}

Write-Output ''
Write-Output 'SC-967/SC-968 native acceptance is starting.'
Write-Output 'Three clients will appear. In each client, log in if needed and use Net on a Fishing spot once when prompted.'
Write-Output 'Do not close a client until PowerShell advances to the next phase.'
Write-Output ''

& $acceptance -ContractsRoot $ContractsRoot -LeeuwBotRoot $LeeuwBotRoot `
  -ChromaScapeRoot $ChromaScapeRoot -Micro6Root $Micro6Root -RuneLiteRoot $RuneLiteRoot `
  -RuntimeMicro6Root $RuntimeMicro6Root `
  -OutputRoot $outputRoot
if ($LASTEXITCODE -ne 0) { throw "SC-967/SC-968 acceptance failed. Inspect $outputRoot\logs" }

$sc967 = Join-Path $outputRoot 'sc-967-demo-manifest.json'
$sc968 = Join-Path $outputRoot 'sc-968-acceptance-manifest.json'
foreach ($manifest in @($sc967, $sc968)) {
  $value = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
  if ($value.overall -ne 'PASS') { throw "Acceptance manifest is not PASS: $manifest" }
  Write-Output ("PASS {0} sha256:{1}" -f $manifest, (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant())
}
Write-Output 'ACCEPTANCE PASS — SC-967 and SC-968 evidence is ready.'

[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string] $ContractsRoot,
  [Parameter(Mandatory=$true)][string] $LeeuwBotRoot,
  [Parameter(Mandatory=$true)][string] $ChromaScapeRoot,
  [Parameter(Mandatory=$true)][string] $Micro6Root,
  [Parameter(Mandatory=$true)][string] $RuneLiteRoot,
  [string] $OutputRoot = (Join-Path $ChromaScapeRoot 'output/sc968'),
  [string] $RuntimeDriver = (Join-Path $PSScriptRoot 'sc968/Acceptance.Runtime.ps1'),
  [switch] $AllowDirtyDevelopmentTrees
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$common = Join-Path $PSScriptRoot 'sc968/Acceptance.Common.psm1'
$evidenceModule = Join-Path $PSScriptRoot 'sc968/Acceptance.Evidence.psm1'
Import-Module $common -Force
Import-Module $evidenceModule -Force
New-Item -ItemType Directory -Force -Path $OutputRoot, (Join-Path $OutputRoot 'logs') | Out-Null
$logs = Join-Path $OutputRoot 'logs'
$gates = @{}
$tests = $gates
$runtime = [ordered]@{ baseline = $null; candidate = $null; shadow = $null }
$dirtyDevelopment = [bool]$AllowDirtyDevelopmentTrees

try {
  Assert-NativeJava17
  foreach ($secretName in @('SC968_CAPTURE_TOKEN','SC968_OPERATOR_TOKEN','SC968_OPERATOR_ID','SC968_REGISTRY_TOKEN','SC968_PUBLIC_KEY_RING','SC968_REGISTRY_TRUSTED_KEYS_DIR','SC968_MICRO6_LAUNCH_SCRIPT','SC968_SIGNING_PRIVATE_KEY_PATH','SC968_SIGNING_PUBLIC_KEY_PATH','SC968_SIGNING_KEY_ID','SC968_EXPECTED_ARTIFACT_NAME')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($secretName))) { throw "$secretName is required for native SC-968 acceptance" }
  }
  if (-not (Test-Path -LiteralPath $RuntimeDriver -PathType Leaf)) { throw "SC-968 runtime driver is missing: $RuntimeDriver" }
  $env:FEATURES_AI_PLAYER_CONTROL = 'false'
  $env:SC968_MAVEN_REPO = Join-Path $OutputRoot 'm2'

  $roots = [ordered]@{ contracts=$ContractsRoot; leeuwbotapi=$LeeuwBotRoot; chromascape=$ChromaScapeRoot; micro6=$Micro6Root; runelite=$RuneLiteRoot }
  $repositories = [ordered]@{}
  foreach ($item in $roots.GetEnumerator()) {
    $state = Get-GitTrackedState $item.Value
    if ($state -and -not $AllowDirtyDevelopmentTrees) { throw "Tracked worktree is dirty: $($item.Key)" }
    $repositories[$item.Key] = [ordered]@{ revision=(Get-GitRevision $item.Value); branch=(Get-GitBranch $item.Value); tracked_state=if ($state) {'DIRTY'} else {'CLEAN'} }
  }

  $lockPath = Join-Path $Micro6Root 'capture-contract.lock'
  if (-not (Test-Path -LiteralPath $lockPath)) { throw 'Micro6 capture-contract.lock is missing' }
  $lock = @{}
  Get-Content -LiteralPath $lockPath | ForEach-Object { if ($_ -match '^([^=]+)=(.*)$') { $lock[$Matches[1]]=$Matches[2] } }
  if ($lock['contracts_revision'] -ne $repositories.contracts.revision) { throw 'capture contract revision does not match contracts HEAD' }
  if ($lock['protocol'] -ne 'runelite-capture/v1') { throw 'unsupported capture contract protocol' }
  $contracts = [ordered]@{ revision=$lock['contracts_revision']; module_version=$lock['module_version']; source_tree_sha256=$lock['source_tree_sha256'] }

  $contractModule = Join-Path $ContractsRoot 'java/ai-routine-capture-contract'
  $null = Invoke-Gate 'contracts_tests' $contractModule { mvn -q test } (Join-Path $logs 'contracts-tests.log') $gates
  $null = Invoke-Gate 'leeuwbot_go_vet' (Join-Path $LeeuwBotRoot 'plugin-service') { go vet ./... } (Join-Path $logs 'leeuwbot-go-vet.log') $gates
  $null = Invoke-Gate 'leeuwbot_go_test' (Join-Path $LeeuwBotRoot 'plugin-service') { go test ./... } (Join-Path $logs 'leeuwbot-go-test.log') $gates
  $null = Invoke-Gate 'chromascape_tests' $ChromaScapeRoot { .\gradlew.bat --no-daemon --no-watch-fs test } (Join-Path $logs 'chromascape-test.log') $gates
  $null = Invoke-Gate 'micro6_capture_tests' $Micro6Root { .\gradlew.bat --no-daemon --no-watch-fs :client:test --tests 'net.runelite.client.plugins.airoutinecapture.*' --tests 'net.runelite.client.plugins.microbot.accountbuilder.RemoteAccountBuilderManagerTest' } (Join-Path $logs 'micro6-test.log') $gates
  $null = Invoke-Gate 'micro6_shadowjar' $Micro6Root { .\gradlew.bat --no-daemon --no-watch-fs :client:shadowJar } (Join-Path $logs 'micro6-shadowjar.log') $gates
  $null = Invoke-Gate 'micro6_sbom' $Micro6Root { .\gradlew.bat --no-daemon --no-watch-fs :client:generateRuntimeSbom } (Join-Path $logs 'micro6-sbom.log') $gates
  $null = Invoke-Gate 'runelite_capture_tests' $RuneLiteRoot { mvn -q -pl runelite-client -am -Dcapture-contract.version=$lock['module_version'] test } (Join-Path $logs 'runelite-test.log') $gates

  if (-not $AllowDirtyDevelopmentTrees) {
    foreach ($item in $roots.GetEnumerator()) { if (Get-GitTrackedState $item.Value) { $gates["$($item.Key)_final_clean"]=[ordered]@{status='FAIL';exit_code=1;log=''} } }
  }
  # Runtime acceptance is intentionally a hard gate. The tracked driver launches only owned
  # native processes and writes baseline/candidate/shadow JSON from live IDs; no fixture path is
  # accepted here.
  $null = Invoke-Gate 'live_runtime_driver' $ChromaScapeRoot {
    & powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $RuntimeDriver `
      -ContractsRoot $ContractsRoot -LeeuwBotRoot $LeeuwBotRoot -ChromaScapeRoot $ChromaScapeRoot `
      -Micro6Root $Micro6Root -OutputRoot $OutputRoot
  } (Join-Path $logs 'runtime-acceptance.log') $gates
  foreach ($name in @('baseline','candidate','shadow')) {
    $path = Join-Path $OutputRoot ("runtime/$name.json")
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Runtime driver did not produce required evidence: $path" }
    $runtime[$name] = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -Depth 40
  }
  if (-not $AllowDirtyDevelopmentTrees) { foreach ($gate in $gates.Values) { if ($gate.status -ne 'PASS') { break } } }
  $envInfo = [ordered]@{ windows_version=[Environment]::OSVersion.VersionString; java_version=((& java -version 2>&1) -join ' '); timestamp=[DateTimeOffset]::UtcNow.ToString('o'); timezone=[TimeZoneInfo]::Local.Id }
  $ok = Write-Sc968Evidence (Join-Path $OutputRoot 'sc-968-acceptance-manifest.json') $envInfo $repositories $contracts $tests $runtime $gates $dirtyDevelopment
  if (-not $ok) { exit 1 }
} catch {
  $gates['preflight']=[ordered]@{status='FAIL';exit_code=1;log=$_.Exception.Message}
  $envInfo = [ordered]@{ timestamp=[DateTimeOffset]::UtcNow.ToString('o'); error=$_.Exception.Message }
  Write-Sc968Evidence (Join-Path $OutputRoot 'sc-968-acceptance-manifest.json') $envInfo @{} @{} @{} $runtime $gates $dirtyDevelopment | Out-Null
  Write-Error $_
  exit 1
}

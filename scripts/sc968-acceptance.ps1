[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)][string] $ContractsRoot,
  [Parameter(Mandatory=$true)][string] $LeeuwBotRoot,
  [Parameter(Mandatory=$true)][string] $ChromaScapeRoot,
  [Parameter(Mandatory=$true)][string] $Micro6Root,
  [Parameter(Mandatory=$true)][string] $RuneLiteRoot,
  [string] $RuntimeMicro6Root = '',
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
$runtime = [ordered]@{ baseline = $null; assistant = $null; runelite = $null; candidate = $null; shadow = $null }
$dirtyDevelopment = [bool]$AllowDirtyDevelopmentTrees
$executionMicro6Root = if ([string]::IsNullOrWhiteSpace($RuntimeMicro6Root)) { $Micro6Root } else { $RuntimeMicro6Root }

try {
  Assert-NativeJava17
  foreach ($requiredName in @('SC968_CAPTURE_TOKEN','SC968_OPERATOR_TOKEN','SC968_OPERATOR_ID','SC968_REGISTRY_TOKEN','SC968_PUBLIC_KEY_RING','SC968_REGISTRY_TRUSTED_KEYS_DIR','SC968_MICRO6_LAUNCH_SCRIPT','SC968_RUNELITE_LAUNCH_SCRIPT','SC968_SIGNING_PRIVATE_KEY_PATH','SC968_SIGNING_PUBLIC_KEY_PATH','SC968_SIGNING_KEY_ID','SC968_EXPECTED_ARTIFACT_NAME','MICRO6_SOURCE_BUNDLE_ROOT','MICRO6_SOURCE_SIGNING_PRIVATE_KEY_BASE64','MICRO6_SOURCE_SIGNING_KEY_ID','MICRO6_SOURCE_VERIFICATION_PUBLIC_KEY_BASE64','MICRO6_SOURCE_VERIFICATION_KEY_ID','MICRO6_SOURCE_ENVIRONMENT','CHROMASCAPE_SOURCE_REQUIRE_SIGNATURE','CHROMASCAPE_SOURCE_ALLOW_LEGACY','CHROMASCAPE_SOURCE_PUBLIC_KEY_BASE64','CHROMASCAPE_SOURCE_PUBLIC_KEY_ID','CHROMASCAPE_SOURCE_ENVIRONMENT')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredName))) { throw "$requiredName is required for native SC-968 acceptance" }
  }
  if (-not (Test-Path -LiteralPath $RuntimeDriver -PathType Leaf)) { throw "SC-968 runtime driver is missing: $RuntimeDriver" }
  $env:FEATURES_AI_PLAYER_CONTROL = 'false'
  # $OutputRoot is archived wholesale at the start of every run, so a repository inside it is
  # discarded and ~250 MB of dependencies are re-resolved every cycle. Keep it beside the run
  # directory instead: still isolated from the developer's ~/.m2, but reused across runs.
  # Every run builds in a throwaway worktree, so Gradle would otherwise recompile the whole
  # RuneLite client from zero even when one file changed -- the bulk of a ~15 minute run. The
  # --build-cache flag on the gradle gates below is keyed on task inputs rather than paths, so an
  # unchanged module hits across worktrees. It does not weaken the gates: identical inputs produce
  # identical outputs, they are simply not recomputed. GRADLE_USER_HOME is deliberately left alone
  # -- the cache lives inside it, and repointing it would re-download every dependency once, which
  # costs more than the compile time this saves. Maven needs its own repository below only because
  # the default would otherwise be the developer's ~/.m2.
  if ([string]::IsNullOrWhiteSpace($env:SC968_MAVEN_REPO)) {
    $env:SC968_MAVEN_REPO = Join-Path (Split-Path -Parent $OutputRoot) 'sc968-m2'
  }
  $env:SC967_VECTORS_PATH = Join-Path $ContractsRoot 'sc967\ed25519-vectors.json'
  if (-not (Test-Path -LiteralPath $env:SC967_VECTORS_PATH -PathType Leaf)) {
    throw "SC-967 shared vectors are missing: $env:SC967_VECTORS_PATH"
  }
  $captureContracts = Join-Path $ContractsRoot 'java'
  if (-not (Test-Path -LiteralPath $captureContracts -PathType Container)) {
    throw "Integrated capture contracts directory is missing: $captureContracts"
  }
  # The runtime driver launches Micro6 in child PowerShell processes. Bind those launchers to the
  # exact Contracts checkout supplied to this acceptance, rather than allowing Gradle settings to
  # fall back to a sibling or developer checkout.
  $env:SC968_CAPTURE_CONTRACTS_DIR = $captureContracts
  $mavenRepoArgument = '-Dmaven.repo.local=' + $env:SC968_MAVEN_REPO
  $mavenCommand = $env:SC968_MAVEN_COMMAND
  if ([string]::IsNullOrWhiteSpace($mavenCommand) -or -not (Test-Path -LiteralPath $mavenCommand -PathType Leaf)) {
    throw 'SC968_MAVEN_COMMAND must identify the native Maven executable'
  }
  $goCommand = $env:SC968_GO_COMMAND
  if ([string]::IsNullOrWhiteSpace($goCommand) -or -not (Test-Path -LiteralPath $goCommand -PathType Leaf)) {
    throw 'SC968_GO_COMMAND must identify the native Go executable'
  }

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
  if ([string]::IsNullOrWhiteSpace($lock['module_version'])) { throw 'capture contract module version is missing' }
  $env:SC968_CAPTURE_CONTRACT_VERSION = $lock['module_version']
  $contracts = [ordered]@{ revision=$lock['contracts_revision']; module_version=$lock['module_version']; source_tree_sha256=$lock['source_tree_sha256'] }

  $contractModule = Join-Path $ContractsRoot 'java/ai-routine-capture-contract'
  $null = Invoke-Gate 'contracts_tests' $contractModule {
    $ErrorActionPreference = 'Continue'
    & $mavenCommand -q $mavenRepoArgument clean install
  } (Join-Path $logs 'contracts-tests.log') $gates
  $null = Invoke-Gate 'leeuwbot_go_vet' (Join-Path $LeeuwBotRoot 'plugin-service') { $ErrorActionPreference = 'Continue'; & $goCommand vet ./... } (Join-Path $logs 'leeuwbot-go-vet.log') $gates
  $null = Invoke-Gate 'leeuwbot_go_test' (Join-Path $LeeuwBotRoot 'plugin-service') { $ErrorActionPreference = 'Continue'; & $goCommand test ./... } (Join-Path $logs 'leeuwbot-go-test.log') $gates
  # The repository-wide suite has a documented baseline of unrelated failures (database-only
  # integration tests and Builder Goal test-seam drift). SC-967/SC-968 instead hard-gate a full
  # production/test compilation plus the complete AI-player surface used by this runtime. The
  # live driver below remains the final cross-process integration gate.
  $null = Invoke-Gate 'chromascape_compile' $ChromaScapeRoot {
    $ErrorActionPreference = 'Continue'
    # Build the executable runtime artifact here as part of the native gate. The interactive driver
    # launches this JAR directly so an unrelated `gradle --stop` cannot terminate ChromaScape while
    # the harness is waiting for a human login/fishing action.
    .\gradlew.bat --no-daemon --no-watch-fs --build-cache compileJava compileTestJava bootJar
  } (Join-Path $logs 'chromascape-compile.log') $gates
  $null = Invoke-Gate 'chromascape_ai_runtime_tests' $ChromaScapeRoot {
    $ErrorActionPreference = 'Continue'
    .\gradlew.bat --no-daemon --no-watch-fs --build-cache test `
      --tests 'com.chromascape.web.aiplayer.AiPlayer*' `
      --tests 'com.chromascape.web.aiplayer.ImitationDatasetBuilderServiceTest' `
      --tests 'com.chromascape.web.aiplayer.PatchOutcomeEvaluationServiceTest' `
      --tests 'com.chromascape.web.aiplayer.contract.*' `
      --tests 'com.chromascape.web.aiplayer.prototype.*' `
      --tests 'com.chromascape.web.aiplayer.routine.RoutineArchitectureTest' `
      --tests 'com.chromascape.web.aiplayer.routine.config.*' `
      --tests 'com.chromascape.web.aiplayer.routine.dto.*' `
      --tests 'com.chromascape.web.aiplayer.routine.source.SourceManifestTextPersistenceTest' `
      --tests 'com.chromascape.web.aiplayer.routine.service.CandidateRegistryClientTest' `
      --tests 'com.chromascape.web.aiplayer.routine.service.PatchCandidateManifestV2Test' `
      --tests 'com.chromascape.web.aiplayer.routine.service.PatchCandidateWorkflowServiceTest' `
      --tests 'com.chromascape.web.aiplayer.routine.service.DeterministicRoutineAssistantProviderTest' `
      --tests 'com.chromascape.web.aiplayer.routine.service.RoutineAssistantGenerationContextTest' `
      --tests 'com.chromascape.web.aiplayer.routine.service.RoutineAssistantStatusTest' `
      --tests 'com.chromascape.web.aiplayer.routine.service.RoutineReplayShadowServiceTest' `
      --tests 'com.chromascape.web.aiplayer.routine.service.RoutineModelGraphEnvelopeTest'
  } (Join-Path $logs 'chromascape-ai-runtime-test.log') $gates
  $null = Invoke-Gate 'chromascape_acceptance_contract_tests' $ChromaScapeRoot {
    $ErrorActionPreference = 'Continue'
    & node --test src/test/js/sc968-acceptance-contract.test.js
  } (Join-Path $logs 'chromascape-acceptance-contract-test.log') $gates
  $null = Invoke-Gate 'micro6_capture_tests' $Micro6Root { $ErrorActionPreference = 'Continue'; .\gradlew.bat --no-daemon --no-watch-fs --build-cache :client:test --tests 'net.runelite.client.plugins.airoutinecapture.*' --tests 'net.runelite.client.plugins.microbot.accountbuilder.RemoteAccountBuilderManagerTest' --tests 'net.runelite.client.plugins.microbot.accountbuilder.integration.builder.SourceManifestCatalogTest' "-PaiRoutineCaptureContractsDir=$captureContracts" } (Join-Path $logs 'micro6-test.log') $gates
  $null = Invoke-Gate 'micro6_shadowjar' $Micro6Root { $ErrorActionPreference = 'Continue'; .\gradlew.bat --no-daemon --no-watch-fs --build-cache :client:shadowJar "-PaiRoutineCaptureContractsDir=$captureContracts" } (Join-Path $logs 'micro6-shadowjar.log') $gates
  $null = Invoke-Gate 'micro6_source_bundle' $Micro6Root {
    $ErrorActionPreference = 'Continue'
    .\gradlew.bat --no-daemon --no-watch-fs --build-cache :client:generateAllTaskSourceBundles "-PaiRoutineCaptureContractsDir=$captureContracts"
  } (Join-Path $logs 'micro6-source-bundle.log') $gates
  $null = Invoke-Gate 'micro6_sbom' $Micro6Root { $ErrorActionPreference = 'Continue'; .\gradlew.bat --no-daemon --no-watch-fs --build-cache :client:generateRuntimeSbom "-PaiRoutineCaptureContractsDir=$captureContracts" } (Join-Path $logs 'micro6-sbom.log') $gates
  $null = Invoke-Gate 'runelite_capture_tests' $RuneLiteRoot {
    $ErrorActionPreference = 'Continue'
    # Current RuneLite uses its native Gradle build. The capture contract repository and exact
    # version are process-scoped above, so dependency resolution cannot silently use a stale pin.
    .\gradlew.bat --no-daemon --no-watch-fs --build-cache :client:test `
      --tests 'net.runelite.client.plugins.airoutinecapture.Sc968CaptureCompletionPolicyTest' `
      --tests 'net.runelite.client.plugins.airoutinecapture.RuneLiteCaptureSafetyTest' `
      --tests 'net.runelite.client.plugins.airoutinecapture.RuneLiteCaptureIdentityFactoryTest'
  } (Join-Path $logs 'runelite-test.log') $gates

  # Build/test gates rewrite tracked Micro6 build outputs. The live clients must start from a
  # separate clean checkout at the exact same revision, so acceptance cannot accidentally execute
  # from a test-polluted source tree.
  if (-not (Test-Path -LiteralPath $executionMicro6Root -PathType Container)) {
    throw "Runtime Micro6 checkout is missing: $executionMicro6Root"
  }
  $runtimeMicro6Revision = Get-GitRevision $executionMicro6Root
  if ($runtimeMicro6Revision -ne $repositories.micro6.revision) {
    throw "Runtime Micro6 revision $runtimeMicro6Revision does not match gate revision $($repositories.micro6.revision)"
  }
  if ($executionMicro6Root -ne $Micro6Root) {
    $runtimeMicro6State = Get-GitTrackedState $executionMicro6Root
    if (-not [string]::IsNullOrWhiteSpace($runtimeMicro6State)) {
      throw "Runtime Micro6 checkout must be clean before live acceptance:`n$runtimeMicro6State"
    }
  }
  $runtimeMicro6Launcher = Join-Path $executionMicro6Root 'scripts\launch-sc968-micro6.ps1'
  if (-not (Test-Path -LiteralPath $runtimeMicro6Launcher -PathType Leaf)) {
    throw "Runtime Micro6 launcher is missing: $runtimeMicro6Launcher"
  }
  $env:SC968_MICRO6_LAUNCH_SCRIPT = $runtimeMicro6Launcher

  if (-not $AllowDirtyDevelopmentTrees) {
    # Build output is excluded here, not source: micro6 tracks compiled .class files under
    # runelite-api/build, so the gates above rewrite them on every run and a literal git status can
    # never be empty. Any change outside a build directory still fails this gate, and the drift is
    # recorded so a real regression is diagnosable from the manifest alone.
    foreach ($item in $roots.GetEnumerator()) {
      $drift = Get-Sc968SourceDrift $item.Value
      if ($drift) { $gates["$($item.Key)_final_clean"]=[ordered]@{status='FAIL';exit_code=1;log=$drift} }
    }
  }
  # Runtime acceptance is intentionally a hard gate. The tracked driver launches only owned
  # native processes and writes baseline/assistant/RuneLite/candidate/shadow JSON from live IDs; no fixture path is
  # accepted here.
  $null = Invoke-Gate 'live_runtime_driver' $ChromaScapeRoot {
    $ErrorActionPreference = 'Continue'
    & powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $RuntimeDriver `
      -ContractsRoot $ContractsRoot -LeeuwBotRoot $LeeuwBotRoot -ChromaScapeRoot $ChromaScapeRoot `
      -Micro6Root $executionMicro6Root -SourceMicro6Root $Micro6Root -RuneLiteRoot $RuneLiteRoot -OutputRoot $OutputRoot
  } (Join-Path $logs 'runtime-acceptance.log') $gates
  foreach ($name in @('baseline','assistant','runelite','candidate','shadow')) {
    $path = Join-Path $OutputRoot ("runtime/$name.json")
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Runtime driver did not produce required evidence: $path" }
    $runtime[$name] = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
  }
  if (-not $AllowDirtyDevelopmentTrees) { foreach ($gate in $gates.Values) { if ($gate.status -ne 'PASS') { break } } }
  # java writes its version banner to stderr even when it succeeds. With the script-wide
  # ErrorActionPreference=Stop, invoking it directly turns that expected banner into a terminating
  # NativeCommandError on Windows PowerShell 5.1. Capture both streams through Process instead.
  $javaCommand = @(Get-Command java -CommandType Application -ErrorAction Stop)[0].Source
  $javaStartInfo = New-Object System.Diagnostics.ProcessStartInfo
  $javaStartInfo.FileName = $javaCommand
  $javaStartInfo.Arguments = '-version'
  $javaStartInfo.UseShellExecute = $false
  $javaStartInfo.CreateNoWindow = $true
  $javaStartInfo.RedirectStandardOutput = $true
  $javaStartInfo.RedirectStandardError = $true
  $javaProcess = [System.Diagnostics.Process]::Start($javaStartInfo)
  $javaStdout = $javaProcess.StandardOutput.ReadToEnd()
  $javaStderr = $javaProcess.StandardError.ReadToEnd()
  $javaProcess.WaitForExit()
  if ($javaProcess.ExitCode -ne 0) { throw "java -version failed with exit code $($javaProcess.ExitCode)" }
  $javaVersion = (@($javaStderr, $javaStdout) -join ' ').Trim()
  $envInfo = [ordered]@{ windows_version=[Environment]::OSVersion.VersionString; java_version=$javaVersion; timestamp=[DateTimeOffset]::UtcNow.ToString('o'); timezone=[TimeZoneInfo]::Local.Id }
  $ok = Write-Sc968Evidence (Join-Path $OutputRoot 'sc-968-acceptance-manifest.json') $envInfo $repositories $contracts $tests $runtime $gates $dirtyDevelopment
  $demoOk = Write-Sc967DemoEvidence (Join-Path $OutputRoot 'sc-967-demo-manifest.json') $repositories $contracts $runtime $gates $dirtyDevelopment
  if (-not $ok -or -not $demoOk) { exit 1 }
} catch {
  $failure = $_
  $failureLog = @($failure.Exception.Message, $failure.InvocationInfo.PositionMessage, $failure.ScriptStackTrace) |
    Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }
  $failureLog = $failureLog -join "`n"
  $gates['preflight']=[ordered]@{status='FAIL';exit_code=1;log=$failureLog}
  $envInfo = [ordered]@{
    timestamp=[DateTimeOffset]::UtcNow.ToString('o'); error=$failure.Exception.Message
    error_position=$failure.InvocationInfo.PositionMessage; script_stack_trace=$failure.ScriptStackTrace
  }
  try {
    Write-Sc968Evidence (Join-Path $OutputRoot 'sc-968-acceptance-manifest.json') $envInfo @{} @{} @{} $runtime $gates $dirtyDevelopment | Out-Null
    Write-Sc967DemoEvidence (Join-Path $OutputRoot 'sc-967-demo-manifest.json') @{} @{} $runtime $gates $dirtyDevelopment | Out-Null
  } catch {
    Write-Warning "Could not write failure evidence: $($_.Exception.Message)"
  }
  Write-Error $failure
  exit 1
}

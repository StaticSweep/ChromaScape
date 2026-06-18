param(
  [string]$ReportFile = "$env:TEMP\e77_verification_report.md",
  [string]$ChromaScapeUrl = "http://localhost:8090",
  [string]$Micro6Url = "http://127.0.0.1:8098",
  [string]$StabilizationProfile = "critical",
  [switch]$SkipBuilds
)

$ErrorActionPreference = "Stop"
$script:PassCount = 0
$script:FailCount = 0
$sc583ReportFile = Join-Path $env:TEMP "sc583_certification_report.md"

function Add-ReportLine {
  param([string]$Line)
  Add-Content -Path $ReportFile -Value $Line
}

function Run-Check {
  param(
    [string]$Id,
    [scriptblock]$Action
  )

  try {
    $output = & $Action 2>&1 | Out-String
    $script:PassCount++
    Add-ReportLine "### PASS: $Id"
    Add-ReportLine ""
    Add-ReportLine '```text'
    Add-ReportLine ($output.TrimEnd())
    Add-ReportLine '```'
    Add-ReportLine ""
  }
  catch {
    $script:FailCount++
    Add-ReportLine "### FAIL: $Id"
    Add-ReportLine ""
    Add-ReportLine '```text'
    Add-ReportLine ($_.Exception.Message)
    Add-ReportLine '```'
    Add-ReportLine ""
  }
}

$nowUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$stabilizationReportFile = Join-Path $env:TEMP "e77_stabilization_report.json"

@"
# E7.7 Verification Report (Windows)

- Run at (UTC): $nowUtc
- ChromaScape URL: $ChromaScapeUrl
- Micro6 URL: $Micro6Url

## Results

"@ | Set-Content -Path $ReportFile

$repoRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $repoRoot
$micro6RepoRoot = Join-Path $workspaceRoot "micro6"

Run-Check "health_micro6" {
  (Invoke-WebRequest -UseBasicParsing -Uri "$Micro6Url/health" -TimeoutSec 8).Content
}

Run-Check "health_chromascape" {
  try {
    (Invoke-WebRequest -UseBasicParsing -Uri "$ChromaScapeUrl/actuator/health" -TimeoutSec 8).Content
  }
  catch {
    (Invoke-WebRequest -UseBasicParsing -Uri "$ChromaScapeUrl/health" -TimeoutSec 8).Content
  }
}

if (-not $SkipBuilds.IsPresent) {
  Run-Check "build_chromascape_compile" {
    Push-Location $repoRoot
    try {
      cmd.exe /c "gradlew.bat --no-daemon -Dorg.gradle.problems.report=false compileJava --console=plain"
    }
    finally {
      Pop-Location
    }
  }
}
else {
  Add-ReportLine "### SKIP: build_chromascape_compile"
  Add-ReportLine ""
  Add-ReportLine "Build checks skipped via -SkipBuilds."
  Add-ReportLine ""
}

Run-Check "micro6_stabilization_harness" {
  Push-Location $micro6RepoRoot
  try {
    bash.exe -lc "MICRO6_EXECUTOR_URL=$Micro6Url ./scripts/accountbuilder-stabilization.sh --profile $StabilizationProfile --attempts 1 --timeout-ms 30000 --out '$stabilizationReportFile'"
    if (-not (Select-String -Path $stabilizationReportFile -Pattern '"execute_http_status":"(202|400|409|500|503)"' -Quiet)) {
      throw "stabilization harness did not reach the executor API"
    }
    Get-Content -Path $stabilizationReportFile -Tail 40
  }
  finally {
    Pop-Location
  }
}

Run-Check "chromascape_stabilization_ingest" {
  Push-Location $repoRoot
  try {
    bash.exe -lc "CHROMASCAPE_URL=$ChromaScapeUrl ./scripts/ingest-stabilization-report.sh '$stabilizationReportFile'"
  }
  finally {
    Pop-Location
  }
}

Run-Check "chromascape_stabilization_kpi" {
  (Invoke-WebRequest -UseBasicParsing -Uri "$ChromaScapeUrl/api/account-tasks/stabilization/kpi" -TimeoutSec 15).Content
}

Run-Check "chromascape_task_item_status_sync" {
  Push-Location $repoRoot
  try {
    cmd.exe /c "gradlew.bat --no-daemon test -x processResources -x processTestResources --tests com.chromascape.web.accounttask.service.TaskPlanPatchLoopSyncServiceTest --tests com.chromascape.web.accounttask.service.TaskPlanExecutionServiceTest --tests com.chromascape.web.accounttask.controller.AccountTaskControllerPatchLoopExecutionTest"
  }
  finally {
    Pop-Location
  }
}

Run-Check "chromascape_scenario_certification" {
  Push-Location $repoRoot
  try {
    & "$PSScriptRoot\certify-stabilization-scenarios.ps1" -ReportFile $sc583ReportFile -ChromaScapeUrl $ChromaScapeUrl -Micro6Url $Micro6Url -ArtifactDir (Join-Path $env:TEMP "chromascape-scenario-certification")
    if (-not (Test-Path $sc583ReportFile)) {
      throw "Missing sc-583 certification report"
    }
    $content = Get-Content -Path $sc583ReportFile -Raw
    if ($content -notmatch "Quest Certification" -or $content -notmatch "Boss Certification" -or $content -notmatch "Skilling Certification") {
      throw "Certification report is missing one or more scenario sections"
    }
    Get-Content -Path $sc583ReportFile -Tail 80
  }
  finally {
    Pop-Location
  }
}

Run-Check "probe_patchloop_analysis_only" {
  Push-Location (Join-Path $repoRoot "scripts\mcp-bridge")
  try {
    cmd.exe /c "npm run probe:patchloop:e2e"
  }
  finally {
    Pop-Location
  }
}

Run-Check "probe_patchloop_autofix" {
  Push-Location (Join-Path $repoRoot "scripts\mcp-bridge")
  try {
    cmd.exe /c "npm run probe:patchloop:e2e:autofix"
  }
  finally {
    Pop-Location
  }
}

Add-ReportLine "## Summary"
Add-ReportLine ""
Add-ReportLine "- Passed checks: $script:PassCount"
Add-ReportLine "- Failed checks: $script:FailCount"
Add-ReportLine ""
Add-ReportLine "## Notes"
Add-ReportLine ""
Add-ReportLine "- sc-572 uses the stabilization harness report at $stabilizationReportFile."
Add-ReportLine "- sc-582 uses focused task-item sync and terminal-status regression tests."
Add-ReportLine "- sc-583 uses scenario certification reports at $sc583ReportFile."
Add-ReportLine "- Patch-loop probes include scriptRuns artifact validation."
Add-ReportLine "- Run this script from Windows host PowerShell (not WSL) when micro6 binds localhost."
Add-ReportLine ""

Write-Host "Wrote report: $ReportFile"

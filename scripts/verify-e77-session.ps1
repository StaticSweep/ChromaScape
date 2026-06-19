param(
  [string]$ReportFile = "$env:TEMP\e77_verification_report.md",
  [string]$ChromaScapeUrl = "http://localhost:8090",
  [string]$Micro6Url = "http://127.0.0.1:8098",
  [switch]$SkipBuilds
)

$ErrorActionPreference = "Stop"
$script:PassCount = 0
$script:FailCount = 0

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

@"
# E7.7 Verification Report (Windows)

- Run at (UTC): $nowUtc
- ChromaScape URL: $ChromaScapeUrl
- Micro6 URL: $Micro6Url

## Results

"@ | Set-Content -Path $ReportFile

$repoRoot = Split-Path -Parent $PSScriptRoot

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
Add-ReportLine "- Patch-loop probes include scriptRuns artifact validation."
Add-ReportLine "- Run this script from Windows host PowerShell (not WSL) when micro6 binds localhost."
Add-ReportLine ""

Write-Host "Wrote report: $ReportFile"

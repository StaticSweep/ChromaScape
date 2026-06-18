param(
  [string]$ReportFile = "$env:TEMP\sc583_certification_report.md",
  [string]$ChromaScapeUrl = "http://localhost:8090",
  [string]$Micro6Url = "http://127.0.0.1:8098",
  [string]$ArtifactDir = "$env:TEMP\chromascape-scenario-certification",
  [int]$Attempts = 1,
  [int]$TimeoutMs = 30000
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $repoRoot
$micro6RepoRoot = Join-Path $workspaceRoot "micro6"
New-Item -ItemType Directory -Force -Path $ArtifactDir | Out-Null

function Add-ReportLine {
  param([string]$Line)
  Add-Content -Path $ReportFile -Value $Line
}

function Add-ReportBlock {
  param(
    [string]$Title,
    [string]$Path
  )
  Add-ReportLine "### $Title"
  Add-ReportLine ""
  Add-ReportLine '```text'
  if (Test-Path $Path) {
    Get-Content -Path $Path -Tail 80 | Add-Content -Path $ReportFile
  }
  Add-ReportLine '```'
  Add-ReportLine ""
}

function Run-Profile {
  param([string]$Profile)

  $reportJson = Join-Path $ArtifactDir "stabilization-$Profile.json"
  $harnessOut = Join-Path $ArtifactDir "$Profile-harness.out"
  $ingestOut = Join-Path $ArtifactDir "$Profile-ingest.out"
  $kpiOut = Join-Path $ArtifactDir "$Profile-kpi.json"

  Push-Location $micro6RepoRoot
  try {
    $harnessOutput = bash.exe -lc "MICRO6_EXECUTOR_URL=$Micro6Url ./scripts/accountbuilder-stabilization.sh --profile $Profile --attempts $Attempts --timeout-ms $TimeoutMs --out '$reportJson'" 2>&1 | Out-String
    Set-Content -Path $harnessOut -Value $harnessOutput
  }
  finally {
    Pop-Location
  }

  if (-not (Test-Path $reportJson)) {
    throw "Missing report for profile $Profile: $reportJson"
  }
  if (-not (Select-String -Path $reportJson -Pattern '"execute_http_status":"(202|400|409|500|503)"' -Quiet)) {
    throw "Harness for profile $Profile did not reach the executor API"
  }

  Push-Location $repoRoot
  try {
    $ingestOutput = bash.exe -lc "CHROMASCAPE_URL=$ChromaScapeUrl ./scripts/ingest-stabilization-report.sh '$reportJson'" 2>&1 | Out-String
    Set-Content -Path $ingestOut -Value $ingestOutput
  }
  finally {
    Pop-Location
  }

  $kpiContent = (Invoke-WebRequest -UseBasicParsing -Uri "$ChromaScapeUrl/api/account-tasks/stabilization/kpi?family=$Profile" -TimeoutSec 15).Content
  Set-Content -Path $kpiOut -Value $kpiContent
  if (-not (Select-String -Path $kpiOut -Pattern '"scripts"\s*:' -Quiet)) {
    throw "Family KPI for $Profile did not include scripts data"
  }

  Add-ReportLine "## $($Profile.Substring(0,1).ToUpper() + $Profile.Substring(1)) Certification"
  Add-ReportLine ""
  Add-ReportLine "- Report JSON: $reportJson"
  Add-ReportLine "- KPI JSON: $kpiOut"
  Add-ReportLine "- Baseline snapshots: persisted via stabilization ingest for family `$Profile`"
  Add-ReportLine ""
  Add-ReportBlock "$Profile harness" $harnessOut
  Add-ReportBlock "$Profile ingest" $ingestOut
  Add-ReportBlock "$Profile kpi" $kpiOut
}

$nowUtc = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
@"
# sc-583 Scenario Certification Report

- Run at (UTC): $nowUtc
- ChromaScape URL: $ChromaScapeUrl
- Micro6 URL: $Micro6Url
- Attempts per task: $Attempts
- Timeout ms: $TimeoutMs

"@ | Set-Content -Path $ReportFile

Run-Profile "quest"
Run-Profile "boss"
Run-Profile "skilling"

Add-ReportLine "## Artifacts"
Add-ReportLine ""
Add-ReportLine "- Certification report: $ReportFile"
Add-ReportLine "- Artifact directory: $ArtifactDir"
Add-ReportLine ""

Write-Host "Wrote certification report: $ReportFile"

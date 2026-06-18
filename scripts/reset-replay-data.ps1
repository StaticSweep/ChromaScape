param(
  [string]$DatabaseUrl = $env:CHROMASCAPE_DB_URL,
  [string]$User = $env:CHROMASCAPE_DB_USER,
  [string]$Password = $env:CHROMASCAPE_DB_PASSWORD
)

if ([string]::IsNullOrWhiteSpace($DatabaseUrl) -or [string]::IsNullOrWhiteSpace($User)) {
  Write-Error "Set CHROMASCAPE_DB_URL and CHROMASCAPE_DB_USER (and optional CHROMASCAPE_DB_PASSWORD)."
  exit 1
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sqlPath = Join-Path $scriptDir "reset-replay-data.sql"

if (-not (Test-Path $sqlPath)) {
  Write-Error "SQL file not found: $sqlPath"
  exit 1
}

$env:PGPASSWORD = $Password
psql "$DatabaseUrl" -U "$User" -f "$sqlPath"
if ($LASTEXITCODE -ne 0) {
  Write-Error "Replay data reset failed."
  exit $LASTEXITCODE
}

Write-Host "Replay data reset completed."

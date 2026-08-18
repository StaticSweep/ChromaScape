Set-StrictMode -Version Latest

function Get-Sha256([string] $Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing evidence file: $Path" }
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-GitRevision([string] $Root) {
  Push-Location $Root
  try { $value = (& git rev-parse HEAD 2>$null); if ($LASTEXITCODE -ne 0) { throw "Not a Git repository: $Root" }; return $value.Trim() }
  finally { Pop-Location }
}

function Get-GitBranch([string] $Root) {
  Push-Location $Root
  try { return (& git branch --show-current 2>$null).Trim() }
  finally { Pop-Location }
}

function Get-GitTrackedState([string] $Root) {
  Push-Location $Root
  try { return ((& git status --porcelain=v1 2>$null) -join "`n") }
  finally { Pop-Location }
}

function Assert-NativeJava17 {
  $platform = [Environment]::OSVersion.Platform.ToString()
  if ($platform -notmatch 'Win') { throw 'SC-968 runtime acceptance must run in native Windows PowerShell, never WSL.' }
  $versionLines = (& java -version 2>&1 | Out-String)
  if ($LASTEXITCODE -ne 0 -or $versionLines -notmatch 'version "17\.') { throw "Java 17 is required; detected: $versionLines" }
}

function Invoke-Gate([string] $Name, [string] $WorkingDirectory, [scriptblock] $Command, [string] $LogPath, [hashtable] $GateTable) {
  $started = [DateTimeOffset]::UtcNow
  Push-Location $WorkingDirectory
  try {
    & $Command *>&1 | Tee-Object -FilePath $LogPath
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
  } catch {
    $_ | Out-File -LiteralPath $LogPath -Encoding utf8
    $code = 1
  } finally { Pop-Location }
  $GateTable[$Name] = [ordered]@{ status = if ($code -eq 0) { 'PASS' } else { 'FAIL' }; exit_code = $code; log = [IO.Path]::GetFileName($LogPath); started_at = $started.ToString('o'); completed_at = [DateTimeOffset]::UtcNow.ToString('o') }
  if ($code -ne 0) { throw "Acceptance gate failed: $Name (exit code $code)" }
  return ($code -eq 0)
}

Export-ModuleMember -Function Get-Sha256,Get-GitRevision,Get-GitBranch,Get-GitTrackedState,Assert-NativeJava17,Invoke-Gate

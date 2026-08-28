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
  try {
    $branch = & git branch --show-current 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Cannot resolve Git branch: $Root" }
    if ($null -eq $branch -or [string]::IsNullOrWhiteSpace([string]$branch)) { return '(detached)' }
    return ([string]$branch).Trim()
  }
  finally { Pop-Location }
}

function Get-GitTrackedState([string] $Root) {
  Push-Location $Root
  try { return ((& git status --porcelain=v1 2>$null) -join "`n") }
  finally { Pop-Location }
}

# Several upstream repositories commit compiled artifacts under a module's build directory --
# micro6 alone tracks 91 .class files under runelite-api/build/classes. Every gate that compiles
# rewrites them, so a literal `git status` can never be empty once the build gates have run, and the
# post-gate cleanliness check could never pass no matter how the runtime behaved. That check exists
# to prove the acceptance did not mutate repository *source*, so ignore build output here and keep
# every other path strict.
function Get-Sc968SourceDrift([string] $Root) {
  $status = Get-GitTrackedState $Root
  if ([string]::IsNullOrWhiteSpace($status)) { return '' }
  $drift = New-Object System.Collections.Generic.List[string]
  foreach ($line in ($status -split "`r?`n")) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    if ($line.Length -le 3) { continue }
    $path = $line.Substring(2).Trim()
    if ($path -match '\s->\s') { $path = ($path -split '\s->\s')[-1].Trim() }
    $path = $path.Trim('"')
    if ($path -match '(^|/)(build|target|\.gradle|node_modules)/') { continue }
    $drift.Add($line)
  }
  return ($drift -join "`n")
}

Export-ModuleMember -Function Get-Sc968SourceDrift

function Assert-NativeJava17 {
  $platform = [Environment]::OSVersion.Platform.ToString()
  if ($platform -notmatch 'Win') { throw 'SC-968 runtime acceptance must run in native Windows PowerShell, never WSL.' }
  $javaCommand = Get-Command java.exe -ErrorAction Stop
  $startInfo = New-Object System.Diagnostics.ProcessStartInfo
  $startInfo.FileName = $javaCommand.Source
  $startInfo.Arguments = '-version'
  $startInfo.UseShellExecute = $false
  $startInfo.CreateNoWindow = $true
  $startInfo.RedirectStandardOutput = $true
  $startInfo.RedirectStandardError = $true
  $javaProcess = New-Object System.Diagnostics.Process
  $javaProcess.StartInfo = $startInfo
  try {
    if (-not $javaProcess.Start()) { throw 'Java version process did not start' }
    $standardOutput = $javaProcess.StandardOutput.ReadToEnd()
    $standardError = $javaProcess.StandardError.ReadToEnd()
    $javaProcess.WaitForExit()
    $versionLines = ($standardOutput + "`n" + $standardError).Trim()
    if ($javaProcess.ExitCode -ne 0 -or $versionLines -notmatch 'version "17\.') {
      throw "Java 17 is required; detected: $versionLines"
    }
  } finally {
    $javaProcess.Dispose()
  }
}

function Invoke-Gate([string] $Name, [string] $WorkingDirectory, [scriptblock] $Command, [string] $LogPath, [hashtable] $GateTable) {
  $started = [DateTimeOffset]::UtcNow
  Push-Location $WorkingDirectory
  $previousErrorActionPreference = $ErrorActionPreference
  try {
    # Windows PowerShell 5.1 wraps native stderr as ErrorRecord instances.
    # Build tools routinely write progress to stderr even when they exit 0,
    # so gate on the native exit status instead of promoting stderr to a
    # terminating PowerShell error.
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = $null
    & $Command *>&1 | Tee-Object -FilePath $LogPath
    $commandSucceeded = $?
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = if ($commandSucceeded) { 0 } else { 1 } }
  } catch {
    $_ | Out-File -LiteralPath $LogPath -Encoding utf8
    $code = 1
  } finally {
    $ErrorActionPreference = $previousErrorActionPreference
    Pop-Location
  }
  $GateTable[$Name] = [ordered]@{ status = if ($code -eq 0) { 'PASS' } else { 'FAIL' }; exit_code = $code; log = [IO.Path]::GetFileName($LogPath); started_at = $started.ToString('o'); completed_at = [DateTimeOffset]::UtcNow.ToString('o') }
  if ($code -ne 0) { throw "Acceptance gate failed: $Name (exit code $code)" }
  return ($code -eq 0)
}

Export-ModuleMember -Function Get-Sha256,Get-GitRevision,Get-GitBranch,Get-GitTrackedState,Assert-NativeJava17,Invoke-Gate

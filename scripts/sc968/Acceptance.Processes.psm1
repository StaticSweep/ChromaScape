Set-StrictMode -Version Latest

function Start-Sc968OwnedProcess {
  param(
    [Parameter(Mandatory=$true)][string] $Name,
    [Parameter(Mandatory=$true)][string] $FilePath,
    [Parameter(Mandatory=$true)][string] $WorkingDirectory,
    [Parameter(Mandatory=$true)][string] $LogPath,
    [string] $ArgumentList = ''
  )
  if (-not (Test-Path -LiteralPath $FilePath -PathType Leaf)) {
    $resolved = Get-Command $FilePath -ErrorAction SilentlyContinue
    if ($resolved) {
      $FilePath = if ($resolved.Path) { $resolved.Path } else { $resolved.Source }
    } else { throw "$Name launcher is missing: $FilePath" }
  }
  if (-not (Test-Path -LiteralPath $WorkingDirectory -PathType Container)) { throw "$Name working directory is missing: $WorkingDirectory" }
  $logParent = Split-Path -Parent $LogPath
  if ($logParent) { New-Item -ItemType Directory -Force -Path $logParent | Out-Null }
  $errorPath = $LogPath + '.err'
  Remove-Item -LiteralPath $LogPath,$errorPath -Force -ErrorAction SilentlyContinue
  $extension = [IO.Path]::GetExtension($FilePath).ToLowerInvariant()
  if ($extension -in @('.bat','.cmd')) {
    $file = Join-Path $env:SystemRoot 'System32/cmd.exe'
    $args = "/d /c call `"$FilePath`" $ArgumentList"
  } elseif ($extension -eq '.ps1') {
    $file = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $args = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$FilePath`" $ArgumentList"
  } else {
    $file = $FilePath
    $args = $ArgumentList
  }
  # Do not print ArgumentList: it is supplied by a protected launcher path and must not become
  # an evidence/log channel for credentials.
  $process = Start-Process -FilePath $file -ArgumentList $args -WorkingDirectory $WorkingDirectory -PassThru -RedirectStandardOutput $LogPath -RedirectStandardError $errorPath
  return [pscustomobject]@{ Name=$Name; Pid=$process.Id; LogPath=$LogPath; Root=$process }
}

function Stop-Sc968ProcessTree([int] $Pid) {
  $children = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ParentProcessId -eq $Pid } |
    Select-Object -ExpandProperty ProcessId
  foreach ($child in @($children)) {
    Stop-Sc968ProcessTree ([int]$child)
    Stop-Process -Id ([int]$child) -Force -ErrorAction SilentlyContinue
  }
}

function Stop-Sc968OwnedProcess([object] $Owned) {
  if ($null -eq $Owned -or $Owned.Pid -le 0) { return }
  $root = Get-Process -Id $Owned.Pid -ErrorAction SilentlyContinue
  if ($null -eq $root) { return }
  try {
    Stop-Sc968ProcessTree $Owned.Pid
    Stop-Process -Id $Owned.Pid -Force -ErrorAction SilentlyContinue
  } catch { }
}

function Test-Sc968PortFree([int] $Port) {
  if ($Port -le 0) { return $true }
  return $null -eq (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

Export-ModuleMember -Function Start-Sc968OwnedProcess,Stop-Sc968OwnedProcess,Test-Sc968PortFree

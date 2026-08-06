param(
  [string]$ProjectDir = "C:\Dev\IdeaProjects\ChromaScape",
  [int]$Port = 8090,
  [string]$LanHost = "192.168.1.6",
  [int]$StartupTimeoutSec = 900,
  [string]$DbHost = "localhost",
  [int]$DbPort = 5433,
  [int]$DbReadyTimeoutSec = 90,
  [int]$DbReadyPollSec = 3,
  [int]$BootRetryCount = 1,
  [string]$DatasourceUrl,
  [string]$DatasourceUsername,
  [string]$DatasourcePassword,
  [switch]$EnsureFirewallRule = $true
)

$ErrorActionPreference = "Stop"

function Write-Step {
  param([string]$Message)
  $ts = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
  Write-Host "[$ts] $Message"
}

. "$PSScriptRoot\local-env.ps1"

function Test-HttpStatusCode {
  param(
    [Parameter(Mandatory = $true)][string]$Url,
    [int]$TimeoutSec = 10
  )

  try {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec $TimeoutSec
    return [int]$response.StatusCode
  } catch {
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
      return [int]$_.Exception.Response.StatusCode.value__
    }
    return $null
  }
}

function Test-TcpReady {
  param(
    [Parameter(Mandatory = $true)][string]$DbHost,
    [Parameter(Mandatory = $true)][int]$Port,
    [int]$TimeoutMs = 1500
  )

  $client = New-Object System.Net.Sockets.TcpClient
  try {
    $async = $client.BeginConnect($DbHost, $Port, $null, $null)
    if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
      return $false
    }
    $client.EndConnect($async)
    return $true
  } catch {
    return $false
  } finally {
    $client.Close()
  }
}

function Get-FailureClassification {
  param([string]$LogPath)

  if (-not (Test-Path $LogPath)) {
    return "boot_failed_other"
  }
  $tail = Get-Content $LogPath -Tail 250
  $joined = $tail -join "`n"
  if ($joined -match "Connection to .* refused" -or $joined -match "java.net.ConnectException: Connection refused") {
    return "db_unreachable"
  }
  return "boot_failed_other"
}

function Wait-ForDbReady {
  param(
    [string]$DbHost,
    [int]$Port,
    [int]$TimeoutSec,
    [int]$PollSec
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  do {
    if (Test-TcpReady -DbHost $DbHost -Port $Port) {
      return $true
    }
    Start-Sleep -Seconds $PollSec
  } while ((Get-Date) -lt $deadline)
  return $false
}

function Start-BootRunAttempt {
  param(
    [string]$GradleBat,
    [string]$ProjectDir,
    [string]$LogPath,
    [int]$Port,
    [int]$StartupTimeoutSec
  )

  if (Test-Path $LogPath) {
    Remove-Item $LogPath -Force
  }

  Write-Step "Starting ChromaScape with --no-daemon bootRun..."
  $cmdArgs = "/c `"`"$GradleBat`" --no-daemon bootRun > `"$LogPath`" 2>&1`""
  $proc = Start-Process -FilePath "cmd.exe" -ArgumentList $cmdArgs -WorkingDirectory $ProjectDir -PassThru
  Write-Step "Started launcher PID $($proc.Id)"

  $deadline = (Get-Date).AddSeconds($StartupTimeoutSec)
  $listening = $false
  do {
    Start-Sleep -Seconds 3
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
      $listening = $true
      break
    }
    $launcherStillRunning = Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction SilentlyContinue
    if (-not $launcherStillRunning) {
      break
    }
  } while ((Get-Date) -lt $deadline)

  if ($listening) {
    return @{
      Success = $true
      Listener = $listener
      LauncherPid = $proc.Id
    }
  }

  $stillRunning = Get-CimInstance Win32_Process -Filter "ProcessId=$($proc.Id)" -ErrorAction SilentlyContinue
  if ($stillRunning) {
    Write-Step "Startup timeout waiting for port $Port."
    Write-Step "Launcher PID $($proc.Id) is still running (startup may still be in progress)."
    $classification = "startup_timeout"
  } else {
    Write-Step "Startup failed before binding port $Port."
    Write-Step "Launcher PID $($proc.Id) exited before binding port $Port."
    $classification = Get-FailureClassification -LogPath $LogPath
  }

  Write-Step "Failure classification: $classification"
  Write-Step "Recent log tail:"
  if (Test-Path $LogPath) {
    Get-Content $LogPath -Tail 80
  }

  return @{
    Success = $false
    Listener = $null
    LauncherPid = $proc.Id
    Classification = $classification
  }
}

if (-not (Test-Path $ProjectDir)) {
  throw "Project directory does not exist: $ProjectDir"
}

$gradleBat = Join-Path $ProjectDir "gradlew.bat"
if (-not (Test-Path $gradleBat)) {
  throw "Missing gradle wrapper: $gradleBat"
}

$logPath = Join-Path $ProjectDir "chroma_bootrun.log"

# Local machine configuration (.env.local) loads before datasource parameters
# and before bootRun. Explicit restart-script parameters and existing process
# environment variables take precedence over .env.local values.
Import-LocalEnvFile -ProjectDir $ProjectDir

if ($DatasourceUrl) {
  $env:SPRING_DATASOURCE_URL = $DatasourceUrl
}
if ($DatasourceUsername) {
  $env:SPRING_DATASOURCE_USERNAME = $DatasourceUsername
}
if ($DatasourcePassword) {
  $env:SPRING_DATASOURCE_PASSWORD = $DatasourcePassword
}

Write-Step "Datasource target: $DbHost`:$DbPort"
if (-not (Wait-ForDbReady -DbHost $DbHost -Port $DbPort -TimeoutSec $DbReadyTimeoutSec -PollSec $DbReadyPollSec)) {
  Write-Step "Failure classification: db_unreachable"
  throw "Database preflight failed: $DbHost`:$DbPort did not become reachable within $DbReadyTimeoutSec seconds."
}
Write-Step "Database preflight passed: $DbHost`:$DbPort is reachable."

Write-Step "Stopping stale ChromaScape/bootRun processes..."
$targets = Get-CimInstance Win32_Process |
  Where-Object {
    $_.CommandLine -and
    $_.CommandLine -like "*ChromaScape*" -and
    ($_.Name -match "java|cmd|gradle")
  }

foreach ($proc in $targets) {
  try {
    Stop-Process -Id $proc.ProcessId -Force -ErrorAction Stop
    Write-Step "Stopped PID $($proc.ProcessId) ($($proc.Name))"
  } catch {
    Write-Step "Skip PID $($proc.ProcessId): $($_.Exception.Message)"
  }
}

Start-Sleep -Seconds 1

Write-Step "Checking existing listeners on port $Port..."
$listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listener) {
  Write-Step "Port $Port is still occupied by PID(s): $($listener.OwningProcess -join ', ')"
  throw "Cannot continue while port $Port is in use."
}

$attempt = 0
$maxAttempts = [Math]::Max(1, $BootRetryCount + 1)
$bootResult = $null
do {
  $attempt++
  if ($attempt -gt 1) {
    Write-Step "Retrying boot (attempt $attempt of $maxAttempts) after transient DB failure..."
    Start-Sleep -Seconds 5
  }

  $bootResult = Start-BootRunAttempt -GradleBat $gradleBat -ProjectDir $ProjectDir -LogPath $logPath -Port $Port -StartupTimeoutSec $StartupTimeoutSec
  if ($bootResult.Success) {
    break
  }

  $shouldRetry = $bootResult.Classification -eq "db_unreachable" -and $attempt -lt $maxAttempts
  if (-not $shouldRetry) {
    break
  }
} while ($attempt -lt $maxAttempts)

if (-not $bootResult.Success) {
  if ($bootResult.Classification -eq "startup_timeout") {
    throw "ChromaScape did not open port $Port within $StartupTimeoutSec seconds. Re-run with -StartupTimeoutSec <seconds> for slower hosts."
  }
  throw "ChromaScape bootRun exited before opening port $Port. Cause: $($bootResult.Classification). Review log: $logPath"
}

$listener = $bootResult.Listener
$listenerPids = ($listener | Select-Object -ExpandProperty OwningProcess -Unique)
Write-Step "Port $Port is listening on PID(s): $($listenerPids -join ', ')"

$localUrl = "http://localhost:$Port/debugger"
$localCode = Test-HttpStatusCode -Url $localUrl -TimeoutSec 10
if (-not $localCode) {
  throw "Local debugger probe failed: $localUrl"
}
Write-Step "Local debugger probe: HTTP $localCode ($localUrl)"

if ($EnsureFirewallRule) {
  $ruleName = "ChromaScape ($Port)"
  $existingRule = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
  if (-not $existingRule) {
    Write-Step "Creating firewall rule '$ruleName' for Private profile..."
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private | Out-Null
  } else {
    Write-Step "Firewall rule exists: '$ruleName'"
  }
}

$lanUrl = "http://$LanHost`:$Port/debugger"
$lanCode = Test-HttpStatusCode -Url $lanUrl -TimeoutSec 10
if ($lanCode) {
  Write-Step "LAN probe from host: HTTP $lanCode ($lanUrl)"
} else {
  Write-Step "LAN probe from host failed: $lanUrl"
}

Write-Step "Restart complete. Log: $logPath"

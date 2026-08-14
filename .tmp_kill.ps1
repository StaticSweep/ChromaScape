# Stop the launched executor by PID (19804) cleanly and verify no duplicate remains
$p = Get-Process -Id 19804 -ErrorAction SilentlyContinue
if ($p) {
  Stop-Process -Id 19804 -Force -ErrorAction SilentlyContinue
  Write-Output "STOPPED 19804"
} else { Write-Output "NOT RUNNING" }
Start-Sleep -Seconds 3
$still = Get-NetTCPConnection -LocalPort 8098 -State Listen -ErrorAction SilentlyContinue
if ($still) { Write-Output "PORT_STILL_LISTENING: $($still.OwningProcess)" } else { Write-Output "PORT_FREE" }

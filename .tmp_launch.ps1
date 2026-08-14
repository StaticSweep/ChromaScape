# Stop the launched operation cleanly to verify no duplicate client
try {
  $resp = Invoke-RestMethod -Uri 'http://localhost:8090/api/debug/builder-goals/launch-operations/6472ffd4-e674-4b73-ac51-d803b120c61c' -Method Get
  Write-Output "BEFORE_STOP: status=$($resp.status) terminal=$($resp.terminal)"
} catch { Write-Output "op lookup err" }

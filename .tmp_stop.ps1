# Stop the running executor via the run-stop endpoint for the WAITING_FOR_ACCOUNT operation
# First, check if there is an iteration to stop; otherwise stop via process kill is out of scope.
# Here we verify launch operation already reflects WAITING_FOR_ACCOUNT (identity fail-closed).
try {
  $op = Invoke-RestMethod -Uri 'http://localhost:8090/api/debug/builder-goals/launch-operations/6472ffd4-e674-4b73-ac51-d803b120c61c' -Method Get
  [PSCustomObject]@{operationStatus=$op.status; phase=$op.phase; terminal=$op.terminal; failureKind=$op.failureKind} | ConvertTo-Json
} catch { Write-Output "ERR: $($_.Exception.Message)" }

# SIM-04 Windows Smoke Test Commands

## Prerequisites

**Run from Windows PowerShell or Command Prompt:**

```cmd
# Start ChromaScape (from Windows)
cd F:\Dev\IdeaProjects\ChromaScape
gradlew.bat bootRun
```

**Keep this running in one terminal, open another for commands below.**

---

## TC1: Health Check

```cmd
curl http://localhost:8090/actuator/health
```

**Expected**: `{"status":"UP"}`

---

## TC2: Happy Path Verification (with active session)

```cmd
# Start verification
curl -X POST http://localhost:8090/api/simulator/verify ^
  -H "Content-Type: application/json" ^
  -d "{\"session\":\"latest\",\"scenario\":null,\"bridgeUrl\":\"http://localhost:8092\",\"apiKey\":null}"

# Save the jobId from response, then check status (replace JOB_ID)
curl http://localhost:8090/api/simulator/verify/JOB_ID

# Get report when status is SUCCEEDED
curl http://localhost:8090/api/simulator/verify/JOB_ID/report
```

**Expected**:
- Initial: `{"jobId":"...","status":"QUEUED"}`
- Final: `{"status":"SUCCEEDED","reportAvailable":true,...}`
- Report: 200 with valid JSON

---

## TC3: Scenario-Specific Verification

```cmd
curl -X POST http://localhost:8090/api/simulator/verify ^
  -H "Content-Type: application/json" ^
  -d "{\"session\":\"latest\",\"scenario\":\"S1-happy-path\",\"bridgeUrl\":\"http://localhost:8092\",\"apiKey\":null}"

# Check status and report (use JOB_ID from above)
curl http://localhost:8090/api/simulator/verify/JOB_ID/report
```

**Expected**: Report contains `violations` array

---

## TC4: Report Download (from TC2/TC3)

```cmd
# Re-use JOB_ID from TC2 or TC3
curl http://localhost:8090/api/simulator/verify/JOB_ID/report
```

**Expected**: HTTP 200 with valid JSON report

---

## TC5: Invalid Session Error

```cmd
curl -X POST http://localhost:8090/api/simulator/verify ^
  -H "Content-Type: application/json" ^
  -d "{\"session\":\"invalid-session-xyz\",\"scenario\":null,\"bridgeUrl\":\"http://localhost:8092\",\"apiKey\":null}"

# Check status (use JOB_ID from above)
curl http://localhost:8090/api/simulator/verify/JOB_ID
```

**Expected**:
- Status: `FAILED`
- Error contains "session" or "active session"

---

## TC6: Cancel Active Job

```cmd
# Start a long-running job first (use a large session)
curl -X POST http://localhost:8090/api/simulator/verify ^
  -H "Content-Type: application/json" ^
  -d "{\"session\":\"latest\",\"scenario\":null,\"bridgeUrl\":\"http://localhost:8092\",\"apiKey\":null}"

# Quick cancel while RUNNING (replace JOB_ID)
curl -X POST http://localhost:8090/api/simulator/verify/JOB_ID/cancel

# Check final status
curl http://localhost:8090/api/simulator/verify/JOB_ID
```

**Expected**:
- Status: `FAILED`
- `cancelled: true`

---

## TC7b: ExitCode 2 Error Report (SIM-04-DEF-001 Fix Validation)

```cmd
# Use invalid bridge URL to trigger exitCode=2
curl -X POST http://localhost:8090/api/simulator/verify ^
  -H "Content-Type: application/json" ^
  -d "{\"session\":\"latest\",\"scenario\":null,\"bridgeUrl\":\"http://localhost:9999\",\"apiKey\":null}"

# Check status - look for exitCode=2 and reportAvailable=true (replace JOB_ID)
curl http://localhost:8090/api/simulator/verify/JOB_ID

# KEY TEST: Get the error report - should return 200 (not 404)
curl http://localhost:8090/api/simulator/verify/JOB_ID/report
```

**Expected (CRITICAL for SIM-04 gate)**:
- Job status: `{"exitCode":2,"reportAvailable":true,...}`
- Report endpoint: **200 OK** (not 404)
- Report JSON contains `"error":"Verification failed: Cannot connect to bridge..."`

---

## Quick Test Summary Script (PowerShell)

```powershell
# Run all smoke tests sequentially
$baseUrl = "http://localhost:8090/api/simulator"

Write-Host "=== TC1: Health Check ==="
Invoke-RestMethod -Uri "$baseUrl/../actuator/health" -Method Get

Write-Host "`n=== TC2: Happy Path ==="
$job2 = Invoke-RestMethod -Uri $baseUrl -Method Post -Body '{"session":"latest","scenario":null,"bridgeUrl":"http://localhost:8092","apiKey":null}' -ContentType "application/json"
Write-Host "Job ID: $($job2.jobId)"

Write-Host "`n=== TC7b: ExitCode=2 Error Report ==="
$job7b = Invoke-RestMethod -Uri $baseUrl -Method Post -Body '{"session":"latest","scenario":null,"bridgeUrl":"http://localhost:9999","apiKey":null}' -ContentType "application/json"
Write-Host "Job ID: $($job7b.jobId)"
Start-Sleep -Seconds 5
$status7b = Invoke-RestMethod -Uri "$baseUrl/$($job7b.jobId)" -Method Get
Write-Host "ExitCode: $($status7b.exitCode), ReportAvailable: $($status7b.reportAvailable)"
$report7b = Invoke-RestMethod -Uri "$baseUrl/$($job7b.jobId)/report" -Method Get
Write-Host "Report Error: $($report7b.error)"
```

---

## Evidence Capture Template

Copy and paste results into `SIM-04-Gate01-Evidence.md`:

### TC2 Results (replace with actual)
```json
{
  "jobId": "actual-job-id",
  "status": "SUCCEEDED",
  "reportAvailable": true,
  ...
}
```

### TC7b Results (CRITICAL - SIM-04 fix validation)
```json
{
  "status": "FAILED",
  "exitCode": 2,
  "reportAvailable": true,
  "error": "Verification failed: Cannot connect to bridge..."
}
```

Report endpoint: **200 OK** (not 404) ✅

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection refused | Ensure `gradlew.bat bootRun` is running |
| Job stuck in RUNNING | Cancel with TC6, check OpenRune logs |
| Report returns 404 | **FAIL** - exitCode=2 fix not working |
| exitCode null | Job still running or crashed |

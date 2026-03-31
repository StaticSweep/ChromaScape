# SIM-04-Gate01 Smoke Test Runbook

## Purpose

This runbook contains end-to-end smoke tests for the OpenRune simulator verification integration. These tests validate that the verification service works correctly from the UI through to the OpenRune verifier backend.

## Prerequisites

### Environment Setup

1. **Platform**: Windows (native) - required for screen capture and native DLL access
2. **ChromaScape backend running**:
   ```cmd
   cd F:\Dev\IdeaProjects\ChromaScape
   gradlew.bat bootRun
   ```
3. **Services verification**:
   - ChromaScape UI/API: `http://localhost:8090`
   - Bridge service: `http://localhost:8092` (health check: `GET /health`)

### Test Data

- Active session ID (create a Micro6 session first)
- Valid API key (if authentication is enabled)

## Test Cases

### TC1: UI Navigation

**Description**: Verify that the simulator section is accessible from the main dashboard.

**Steps**:
1. Navigate to `http://localhost:8090`
2. Click on "Simulator" or navigate to `/simulator` path
3. Verify the page loads without errors

**Expected Result**:
- Dashboard loads successfully
- Simulator section is visible and accessible
- No console errors

**Evidence to Capture**:
- Screenshot of simulator UI
- Browser console output

---

### TC2: Happy Path Verification

**Description**: Run a verification with valid parameters (no scenario specified).

**Steps**:
1. Ensure an active Micro6 session exists
2. In the simulator UI:
   - Enter session ID (or use "latest" auto-detect)
   - Leave scenario blank
   - Leave bridge URL as default (`http://localhost:8092`)
3. Click "Start Verification"
4. Wait for completion (should take 5-30 seconds)

**Expected Result**:
- Job status changes: QUEUED → RUNNING → SUCCEEDED
- `reportAvailable` is set to `true`
- Report can be downloaded via GET /api/simulator/verify/{jobId}/report
- Report contains valid JSON with `summary` field

**Evidence to Capture**:
- Initial job creation response (JSON)
- Final job status (JSON)
- Report download response (JSON)
- Browser console logs

**Sample Request**:
```http
POST /api/simulator/verify
Content-Type: application/json

{
  "session": "latest",
  "scenario": null,
  "bridgeUrl": "http://localhost:8092",
  "apiKey": null
}
```

**Sample Success Response**:
```json
{
  "jobId": "abc-123-def",
  "status": "SUCCEEDED",
  "reportAvailable": true,
  "summary": {
    "passed": true,
    "totalActions": 150,
    "startTick": 0,
    "endTick": 900,
    "playerCount": 1,
    "durationMs": 150000,
    "violationCounts": {
      "hard": 0,
      "soft": 0,
      "warn": 0,
      "notEvaluated": 0
    }
  }
}
```

---

### TC3: Scenario-Specific Verification

**Description**: Run a verification with a specific scenario to detect scenario violations.

**Steps**:
1. Ensure an active Micro6 session exists
2. In the simulator UI:
   - Enter session ID
   - Select scenario "S1-happy-path" or "S2-input-heavy"
   - Leave bridge URL as default
3. Click "Start Verification"
4. Wait for completion

**Expected Result**:
- Job status changes: QUEUED → RUNNING → SUCCEEDED
- `reportAvailable` is set to `true`
- Report contains `violations` array (may be empty or contain violations)
- Scenario violations are correctly reported

**Evidence to Capture**:
- Job status response (JSON)
- Report JSON showing scenario validation results

---

### TC4: Report Download

**Description**: Verify that completed reports can be downloaded via the API.

**Steps**:
1. Complete a successful verification (TC2 or TC3)
2. Note the `jobId` from the response
3. Execute GET request: `/api/simulator/verify/{jobId}/report`

**Expected Result**:
- HTTP 200 OK response
- Response body contains valid JSON
- JSON includes all required fields: `sessionId`, `passed`, `summary`, `violations`

**Evidence to Capture**:
- Full report JSON response
- HTTP status code

---

### TC5: Invalid Session Error Handling

**Description**: Verify that invalid session IDs are handled gracefully.

**Steps**:
1. In the simulator UI:
   - Enter an invalid/non-existent session ID: "invalid-session-xyz"
   - Leave other fields as default
2. Click "Start Verification"
3. Wait for completion

**Expected Result**:
- Job status changes: QUEUED → RUNNING → FAILED
- Error message is clear and actionable
- No crash or unhandled exception
- Error message mentions the session issue

**Evidence to Capture**:
- Job status response showing FAILED
- Error message text

**Expected Error Message Examples**:
- "No active session found on bridge at http://localhost:8092"
- "Verification failed: Session not found"

---

### TC6: Cancel Active Job

**Description**: Verify that active jobs can be cancelled and remain in a stable terminal state.

**Steps**:
1. Start a verification job with a long-running session
2. While job is RUNNING, click "Cancel" or call DELETE/POST cancel endpoint
3. Verify job state transitions correctly

**Expected Result**:
- Job status transitions: RUNNING → FAILED (with `cancelled: true`)
- Job remains in terminal state (no further status changes)
- Process is terminated (no orphaned verifier processes)
- Cannot restart the same job

**Evidence to Capture**:
- Job status before and after cancel
- Browser console logs
- Process list (optional)

**Sample Cancel Request**:
```http
POST /api/simulator/verify/{jobId}/cancel
```

**Expected Response**:
```json
{
  "jobId": "abc-123-def",
  "status": "FAILED",
  "cancelled": true,
  "error": "Job cancelled by user"
}
```

---

### TC7: Invalid Bridge Key (if Auth Enabled)

**Description**: Verify that invalid API keys are handled gracefully (only applicable if auth is enabled).

**Steps**:
1. In the simulator UI:
   - Enter valid session ID
   - Enter an invalid API key: "invalid-key-12345"
2. Click "Start Verification"
3. Wait for completion

**Expected Result**:
- Job status changes: QUEUED → RUNNING → FAILED
- Error message indicates authentication failure
- No crash or unhandled exception

**Evidence to Capture**:
- Job status response showing FAILED
- Error message text

**Expected Error Message Examples**:
- "Authentication failed"
- "Verification failed: Unauthorized"

---

### TC7b: ExitCode 2 Error Report Retrieval

**Description**: Verify that error reports are consumable when verification exits with code 2 (SIM-04-DEF-001 fix validation).

**Steps**:
1. Trigger verification that will fail with exitCode=2 (e.g., invalid bridge URL, no active session)
2. Wait for job to complete with FAILED status
3. Verify `reportAvailable: true` in job status
4. Retrieve report via `/api/simulator/verify/{jobId}/report`
5. Verify report JSON contains `error` field

**Expected Result**:
- Job status: FAILED
- Exit code: 2
- `reportAvailable: true`
- Report endpoint returns 200 (not 404)
- Report contains `error` field with specific failure reason

**Evidence to Capture**:
- Job status showing exitCode=2
- Report endpoint response (200 with error report JSON)
- Error field content

**Sample Request (Invalid Bridge URL)**:
```http
POST /api/simulator/verify
Content-Type: application/json

{
  "session": "latest",
  "scenario": null,
  "bridgeUrl": "http://localhost:9999",
  "apiKey": null
}
```

**Expected Error Report Response**:
```json
{
  "sessionId": "latest",
  "timestamp": "2026-03-31T...",
  "scenario": null,
  "passed": false,
  "summary": {...},
  "violations": [],
  "error": "Verification failed: Cannot connect to bridge at http://localhost:9999"
}
```

---

## Test Execution Checklist

Use this checklist when executing the smoke tests:

- [ ] ChromaScape backend started successfully
- [ ] Health check returns 200 OK
- [ ] Bridge service is accessible
- [ ] Active Micro6 session exists (for TC2-TC4)
- [ ] TC1: UI navigation - PASSED / FAILED
- [ ] TC2: Happy path verification - PASSED / FAILED
- [ ] TC3: Scenario-specific verification - PASSED / FAILED
- [ ] TC4: Report download - PASSED / FAILED
- [ ] TC5: Invalid session error handling - PASSED / FAILED
- [ ] TC6: Cancel active job - PASSED / FAILED
- [ ] TC7: Invalid bridge key - PASSED / FAILED / SKIPPED (auth disabled)
- [ ] TC7b: ExitCode 2 error report retrieval - PASSED / FAILED

## Notes

- **Execution Time**: Each test takes 5-30 seconds depending on session size
- **Cleanup**: Test jobs are stored in memory and will be cleared on server restart
- **Debugging**: Check ChromaScape logs at `logs/chromascape.log` for detailed error messages
- **Manual Verifier Testing**: For deeper debugging, run the verifier directly:
  ```cmd
  cd F:\Dev\IdeaProjects\OpenRune-Server
  gradlew.bat :simulator-verifier:run --args="--session latest --bridge http://localhost:8092 --nonInteractive --output test-report.json"
  ```

## Troubleshooting

| Issue | Possible Cause | Solution |
|-------|----------------|----------|
| "Bridge service unreachable" | ChromaScape backend not running | Start backend with `gradlew.bat bootRun` |
| "No active session found" | No Micro6 session exists | Create a session first or use Micro6 client |
| "Report file not found" | Verifier crashed before writing report | Check OpenRune logs for verifier errors |
| Job stuck in RUNNING | Timeout or hang | Cancel job and check process list |

# SIM-04-Gate01 Evidence Document

## Test Execution Summary

| Field | Value |
|-------|-------|
| **Gate** | SIM-04-Gate01 |
| **Defect** | SIM-04-DEF-001 |
| **Test Execution Date** | YYYY-MM-DD (TO BE UPDATED) |
| **Tester** | [Tester Name] |
| **Environment** | Windows (native) |
| **ChromaScape Version** | [Version] |
| **OpenRune Version** | [Version] |

---

## Test Case Results

### TC1: UI Navigation

**Status**: SKIPPED - PENDING EXECUTION

**Objective**: Verify that the simulator section is accessible from the main dashboard.

**Steps Executed**:
1. Navigate to `http://localhost:8090`
2. Click on "Simulator" or navigate to `/simulator` path
3. Verify the page loads without errors

**Actual Result**: SKIPPED

**Evidence**:
- [ ] Screenshot captured
- [ ] Console logs captured

---

### TC2: Happy Path Verification

**Status**: SKIPPED - PENDING EXECUTION

**Objective**: Run a verification with valid parameters (no scenario specified).

**Steps Executed**:
1. Ensure an active Micro6 session exists
2. In the simulator UI:
   - Enter session ID (or use "latest" auto-detect)
   - Leave scenario blank
   - Leave bridge URL as default (`http://localhost:8092`)
3. Click "Start Verification"
4. Wait for completion

**Actual Result**: SKIPPED

**API Request**:
```json
// TO BE CAPTURED
POST /api/simulator/verify
{
  "session": "latest",
  "scenario": null,
  "bridgeUrl": "http://localhost:8092",
  "apiKey": null
}
```

**API Response**:
```json
// TO BE CAPTURED
```

**Report JSON**:
```json
// TO BE CAPTURED
GET /api/simulator/verify/{jobId}/report
```

**Evidence**:
- [ ] Job creation response captured
- [ ] Final job status captured
- [ ] Report download response captured

---

### TC3: Scenario-Specific Verification

**Status**: SKIPPED - PENDING EXECUTION

**Objective**: Run a verification with a specific scenario to detect scenario violations.

**Steps Executed**:
1. Ensure an active Micro6 session exists
2. In the simulator UI:
   - Enter session ID
   - Select scenario "S1-happy-path" or "S2-input-heavy"
   - Leave bridge URL as default
3. Click "Start Verification"
4. Wait for completion

**Actual Result**: SKIPPED

**API Request**:
```json
// TO BE CAPTURED
```

**API Response**:
```json
// TO BE CAPTURED
```

**Evidence**:
- [ ] Job status response captured
- [ ] Report JSON showing scenario validation results captured

---

### TC4: Report Download

**Status**: SKIPPED - PENDING EXECUTION

**Objective**: Verify that completed reports can be downloaded via the API.

**Steps Executed**:
1. Complete a successful verification (TC2 or TC3)
2. Note the `jobId` from the response
3. Execute GET request: `/api/simulator/verify/{jobId}/report`

**Actual Result**: SKIPPED

**HTTP Status**: TO BE CAPTURED

**Report JSON**:
```json
// TO BE CAPTURED
```

**Evidence**:
- [ ] Full report JSON response captured
- [ ] HTTP status code verified (200 OK)

---

### TC5: Invalid Session Error Handling

**Status**: SKIPPED - PENDING EXECUTION

**Objective**: Verify that invalid session IDs are handled gracefully.

**Steps Executed**:
1. In the simulator UI:
   - Enter an invalid/non-existent session ID: "invalid-session-xyz"
   - Leave other fields as default
2. Click "Start Verification"
3. Wait for completion

**Actual Result**: SKIPPED

**API Request**:
```json
// TO BE CAPTURED
```

**API Response**:
```json
// TO BE CAPTURED
```

**Error Message**: TO BE CAPTURED

**Evidence**:
- [ ] Job status response showing FAILED captured
- [ ] Error message text captured

---

### TC6: Cancel Active Job

**Status**: SKIPPED - PENDING EXECUTION

**Objective**: Verify that active jobs can be cancelled and remain in a stable terminal state.

**Steps Executed**:
1. Start a verification job with a long-running session
2. While job is RUNNING, click "Cancel" or call DELETE/POST cancel endpoint
3. Verify job state transitions correctly

**Actual Result**: SKIPPED

**API Request**:
```json
// TO BE CAPTURED
POST /api/simulator/verify/{jobId}/cancel
```

**API Response**:
```json
// TO BE CAPTURED
```

**Evidence**:
- [ ] Job status before cancel captured
- [ ] Job status after cancel captured
- [ ] Verified terminal state (no further status changes)

---

### TC7: Invalid Bridge Key (if Auth Enabled)

**Status**: SKIPPED - PENDING EXECUTION

**Objective**: Verify that invalid API keys are handled gracefully.

**Steps Executed**:
1. In the simulator UI:
   - Enter valid session ID
   - Enter an invalid API key: "invalid-key-12345"
2. Click "Start Verification"
3. Wait for completion

**Actual Result**: SKIPPED (or AUTH DISABLED)

**API Request**:
```json
// TO BE CAPTURED
```

**API Response**:
```json
// TO BE CAPTURED
```

**Error Message**: TO BE CAPTURED

**Evidence**:
- [ ] Job status response showing FAILED captured
- [ ] Error message text captured

---

## Acceptance Criteria Summary

| AC | Description | Expected | Actual | Status |
|----|-------------|----------|--------|--------|
| AC1 | At least one report generated successfully | Report file created and accessible | TO BE VERIFIED | SKIPPED |
| AC2 | Report endpoint returns valid JSON | GET /report returns 200 with valid JSON | TO BE VERIFIED | SKIPPED |
| AC3 | Cancel remains in stable terminal state | Cancelled job stays FAILED, no status changes | TO BE VERIFIED | SKIPPED |
| AC4 | Invalid inputs handled cleanly | Invalid session/key returns FAILED with clear error | TO BE VERIFIED | SKIPPED |

---

## Gate Verdict

**Current Status**: FAIL

**Reason**: Smoke tests not yet executed. All TCs show SKIPPED status.

**Next Steps**:
1. Execute smoke tests TC1-TC7 on Windows environment
2. Capture API responses and evidence
3. Update this document with actual results
4. Re-evaluate gate verdict

---

## Defect Status

| Field | Value |
|-------|-------|
| **Defect ID** | SIM-04-DEF-001 |
| **Title** | "Report file not found and no JSON in output" |
| **Status** | OPEN |
| **Resolution** | PENDING |

### Resolution Notes

**Root Cause Identified**:
- The OpenRune verifier exits with code 2 (error) WITHOUT creating a report file when any exception occurs:
  - Bridge API unreachable (network errors)
  - JSON parsing failures
  - Missing scenarios
  - No active session found

**Fixes Applied**:
1. **Pre-flight check added** (SimulatorVerifyService.java:230-240):
   - Added `isBridgeReachable()` method to check bridge connectivity before invoking verifier
   - Fail-fast with clear error: "Bridge service unreachable at {bridgeUrl}. Please start the ChromaScape backend."

2. **Integration test added** (SimulatorVerifyServiceTest.java):
   - New test class `ExitCodeTwoFailureTests` documents the exitCode 2 failure path
   - Tests verify error message extraction for network errors, missing sessions, and JSON parsing failures

**Remaining Work**:
- Execute smoke tests to verify fixes in real environment
- Update gate verdict based on test results

---

## Appendices

### A. Test Environment Details

| Component | Version/Configuration |
|-----------|----------------------|
| OS | Windows (native) |
| Java | TO BE CAPTURED |
| ChromaScape | TO BE CAPTURED |
| OpenRune | TO BE CAPTURED |
| Bridge URL | http://localhost:8092 |

### B. Raw API Responses

**TC2 - Job Creation**:
```json
// TO BE CAPTURED
```

**TC2 - Final Job Status**:
```json
// TO BE CAPTURED
```

**TC2 - Report Download**:
```json
// TO BE CAPTURED
```

**TC5 - Invalid Session Error**:
```json
// TO BE CAPTURED
```

**TC6 - Cancel Response**:
```json
// TO BE CAPTURED
```

### C. Screenshots

**TC1 - UI Navigation**:
- [ ] Screenshot captured

**TC2 - Happy Path Success**:
- [ ] Screenshot captured

**TC5 - Invalid Session Error**:
- [ ] Screenshot captured

**TC6 - Cancel Confirmation**:
- [ ] Screenshot captured

---

## Change History

| Date | Version | Changes | Author |
|------|---------|---------|--------|
| YYYY-MM-DD | 1.0 | Initial template created | Claude Code |
| YYYY-MM-DD | 1.1 | Smoke test results populated | [Tester Name] |
| YYYY-MM-DD | 1.2 | Gate verdict updated | [Tester Name] |

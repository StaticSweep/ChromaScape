# MCP Provider Runbook

## Overview

This runbook covers the MCP (Model Context Protocol) provider for the Patch Loop system, which enables analysis via an external MCP bridge server instead of direct Claude API calls.

## Configuration

### Execution Modes

The Patch Loop supports the following execution modes:

| Mode | Description |
|------|-------------|
| `analysis_only` | Analyze evidence and provide diagnosis without generating patches |
| `autofix_loop` | Full autonomous loop: analyze → generate patch → apply → retest |
| `full_loop` | **Legacy alias** for `autofix_loop` (normalized to `autofix_loop`) |

**Note:** The `full_loop` mode name is maintained for backwards compatibility but is normalized to `autofix_loop` internally.

Set the following environment variables in `application.yml` or via environment:

```yaml
analysis:
  provider: claude-mcp

mcp:
  server-url: http://localhost:8765
  timeout: 120
  max-retries: 3
```

Or via environment variables:
```bash
ANALYSIS_PROVIDER=claude-mcp
MCP_SERVER_URL=http://localhost:8765
MCP_TIMEOUT=120
MCP_MAX_RETRIES=3
```

## Architecture

```
┌─────────────────┐     HTTP      ┌──────────────┐
│  ChromaScape    │ ────────────> │  MCP Bridge  │
│  Patch Loop     │               │  (External)  │
└─────────────────┘               └──────────────┘
                                          │
                                          ▼
                                   ┌──────────────┐
                                   │  Claude API  │
                                   └──────────────┘
```

**Transport:**
- Request: `McpAnalyzeRequest` wraps `AnalysisV2Request` in `request` field
- Response: `McpAnalyzeResponse` wraps `AnalysisV2Response` in `response` field
- Error: Optional `error` field in both request and response envelopes

## Verification

### 1. Check Provider Status

```bash
curl http://localhost:8090/api/debug/patch-loop/provider/status
```

Expected response:
```json
{
  "providerName": "claude-mcp",
  "available": true,
  "defaultMode": "analysis_only"
}
```

### 2. Check MCP Bridge Health

```bash
curl ${MCP_SERVER_URL}/health
```

Expected response:
```json
{
  "status": "healthy",
  "version": "1.0.0"
}
```

### 3. Check Debugger UI

Open http://localhost:8090/debugger:
- Provider badge should show "Available" (green)
- Provider name should display "claude-mcp"

## Troubleshooting

### Provider Unavailable

**Symptom:** Provider badge shows "Unavailable" (red)

**Checks:**
1. Verify MCP bridge is running:
   ```bash
   curl ${MCP_SERVER_URL}/health
   ```

2. Check bridge logs for connection errors

3. Verify `MCP_SERVER_URL` is correct

**Resolution:**
- Start MCP bridge server
- Update `MCP_SERVER_URL` configuration
- Restart ChromaScape

### Analysis Failures

**Symptom:** Patch loop fails during ANALYZE node

**Checks:**
1. Check response status in artifacts tab:
   - FAILURE: Provider returned error
   - PARTIAL_SUCCESS: Incomplete analysis (may still proceed)

2. Verify MCP bridge returns proper V2 contract:
   ```json
   {
     "response": {
       "schemaVersion": "2.0.0",
       "status": "SUCCESS",
       "diagnosis": { ... },
       "patchDraft": { ... }
     }
   }
   ```

3. Review error message in analysis artifact

**Resolution:**
- Check MCP bridge logs for Claude API errors
- Verify bridge is using correct V2 contract
- Check MCP_TIMEOUT (increase if needed)

### Timeout Errors

**Symptom:** Analysis fails with timeout

**Checks:**
1. Current timeout setting:
   ```bash
   grep MCP_TIMEOUT application.yml
   ```

2. Check MCP bridge logs for long-running requests

**Resolution:**
- Increase `MCP_TIMEOUT` (default 120 seconds)
- Check Claude API rate limits
- Reduce evidence window size

## E2E Test Case

### Manual Test Procedure

1. **Start MCP Bridge**
   ```bash
   # In MCP bridge directory
   npm start
   # Bridge runs on http://localhost:8765
   ```

2. **Start ChromaScape**
   ```bash
   ./gradlew bootRun
   ```

3. **Verify Provider Status**
   - Open http://localhost:8090/debugger
   - Check provider badge shows "Available" (green)
   - Provider name shows "claude-mcp"

4. **Compile and Execute Graph**
   - Open Patch Loop panel
   - Click "Load Template" to load default graph
   - Click "Compile" - should succeed
   - Select a script from dropdown
   - Enter a goal prompt
   - Click "Execute Loop"

5. **Verify Artifacts**
   - Navigate to "Analysis" tab
   - Verify V2 fields display:
     - analysisId
     - diagnosis.summary
     - diagnosis.rootCause
     - confidence
     - recommendationCount

6. **Check for PARTIAL_SUCCESS Handling**
   - If MCP bridge returns PARTIAL_SUCCESS:
     - Verify node timeline shows "(PARTIAL)" indicator
     - Verify flow continues to GENERATE_PATCH node
     - Verify patch is generated from recommendations fallback

## Contract Details

### Request Envelope

```json
{
  "request": {
    "schemaVersion": "2.0.0",
    "promptType": "ISSUE",
    "target": { ... },
    "prompt": "...",
    "evidenceSelectors": [ ... ]
  },
  "options": {
    "timeout": 120,
    "model": "claude-opus-4-6"
  }
}
```

### Response Envelope

```json
{
  "response": {
    "schemaVersion": "2.0.0",
    "status": "SUCCESS | PARTIAL_SUCCESS | FAILURE",
    "analysisId": "...",
    "diagnosis": { ... },
    "patchDraft": { ... },
    "retestPlan": { ... }
  },
  "metadata": {
    "provider": "mcp-bridge",
    "timestamp": "2026-03-04T10:00:00Z"
  },
  "error": "optional error message"
}
```

### Error Envelope (Transport Level)

```json
{
  "error": "MCP bridge unavailable",
  "response": null,
  "metadata": {}
}
```

## Failure Code Mapping

When the analysis provider returns a FAILURE status, the provider error code is mapped to PatchLoopFailureCode constants for proper handling and retry strategies:

| Provider Code | PatchLoopFailureCode | Description |
|---------------|----------------------|-------------|
| `API_TIMEOUT` | `ANALYSIS_API_TIMEOUT` | Request timed out |
| `API_RATE_LIMIT` | `ANALYSIS_API_RATE_LIMIT` | Rate limit exceeded |
| `API_AUTH_FAILED` | `ANALYSIS_API_AUTH_FAILED` | Authentication failed |
| `PARSE_ERROR` | `ANALYSIS_PARSE_ERROR` | Response parsing failed |
| `INVALID_REQUEST` | `ANALYSIS_VALIDATION_FAILED` | Request validation failed |
| `INSUFFICIENT_EVIDENCE` | `INSUFFICIENT_EVIDENCE` | Not enough evidence for analysis |
| `INVALID_TIME_WINDOW` | `INVALID_TIME_WINDOW` | Time window validation failed |
| `EVIDENCE_COLLECTION_FAILED` | `EVIDENCE_COLLECTION_FAILED` | Evidence collection failed |
| `INTERNAL_ERROR` | `INTERNAL_ERROR` | Internal provider error |
| `null` or unknown | `ANALYSIS_API_ERROR` | Default/unspecified API error |

**Implementation:** The mapping is performed in `PatchLoopAsyncWorker.mapProviderFailureCode()` method.

## PARTIAL_SUCCESS Edge Labels

When defining graph edges in Mermaid format, the following label variations are accepted for PARTIAL_SUCCESS condition:

| Label | Description |
|-------|-------------|
| `"partial"` | Short form (recommended) |
| `"partial_success"` | Underscore form |
| `"partial-success"` | Hyphenated form |

**Example Edge Definition:**
```mermaid
ANALYZE_1 -->|"partial_success"| GENERATE_PATCH_1
```

**Normalization:** All variations are normalized to `EdgeCondition.PARTIAL_SUCCESS` in `PatchLoopGraphCompiler.parseAndAddEdge()`.

## Provider Comparison

| Feature | claude-api | claude-mcp |
|---------|-----------|-----------|
| Transport | Direct HTTP API | Bridge server HTTP |
| Timeout | Configured in app | Configured in bridge |
| Retry | App-level retry | Bridge-level retry |
| Rate Limit | Handled by app | Handled by bridge |
| Debugging | Direct logs | Bridge logs + app logs |
| PARTIAL_SUCCESS | Supported | Supported |

## Support

For MCP-specific issues:
1. Check MCP bridge logs
2. Check `/api/debug/patch-loop/provider/status` endpoint
3. Review artifacts in debugger UI
4. Check ChromaScape logs for `ClaudeMcpAnalysisProvider`

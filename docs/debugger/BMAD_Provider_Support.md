# BMAD Analysis Provider Support Matrix

**BMAD (Bot Metric Analysis & Diagnostics)** is ChromaScape's AI-powered bot failure analysis system. It uses LLM providers to analyze evidence (logs, tick data, decisions) and diagnose bot failures with actionable recommendations.

## Production-Ready Providers

### Claude API Provider ✅

- **Status:** Production-ready, fully functional
- **Implementation:** `ClaudeApiAnalysisProvider`
- **Default:** Yes (activated when `analysis.provider=claude-api` or property omitted)
- **Configuration:** Requires `CLAUDE_API_KEY` environment variable
- **Features:**
  - Direct HTTP calls to Anthropic Claude API
  - Full request/response validation
  - Comprehensive error handling (auth, timeout, rate limit, parse errors)
  - Bot-specific prompt engineering for ChromaScape diagnostics
  - Configurable model, timeout, and token limits
  - Production-tested and verified

**Configuration Example:**

```yaml
# application.yml or environment variables
analysis:
  provider: claude-api  # or omit to use default

claude:
  api:
    key: ${CLAUDE_API_KEY}  # Set via environment variable
    url: https://api.anthropic.com/v1/messages
    model: claude-opus-4-6
    timeout-seconds: 120
    max-tokens: 4096
```

**Environment Setup:**

```bash
export CLAUDE_API_KEY=your-api-key-here
```

---

## Deferred/Scaffold Providers

### Claude MCP Provider ⚠️

- **Status:** Health check implemented, analysis deferred
- **Implementation:** `ClaudeMcpAnalysisProvider` + `McpTransportClient`
- **Activated:** When `analysis.provider=claude-mcp`
- **Behavior:** Health check available, `analyzeEvidence()` throws pending implementation error
- **Methods:**
  - `analyzeEvidence()` → Throws `AnalysisException` with message: *"MCP provider analysis is not yet fully implemented (deferred to #516). Health check is available."*
  - `isAvailable()` → Probes MCP server `/health` endpoint, returns true only if server responds with valid JSON and `healthy=true`
  - `getProviderName()` → Returns `"claude-mcp"`

**Runtime Behavior:**
- Availability determined by `/health` endpoint on MCP server
- Health is **only** true when payload explicitly contains `{"healthy": true}`
- Missing field, null, or non-boolean values are treated as **unavailable**
- Malformed JSON responses throw `INVALID_RESPONSE` exception (non-retryable)
- HTTP 429 (rate limit) is classified as `HTTP_ERROR` and remains retryable
- Startup availability is checked once and logged

**Why Partially Implemented:**
The MCP (Model Context Protocol) provider transport layer is complete, but analysis logic requires additional work:
- ~~MCP client library integration~~ ✅ Complete (`McpTransportClient`)
- ~~Health check implementation~~ ✅ Complete
- ~~Runtime error handling and retry logic~~ ✅ Complete
- ⏳ Protocol request/response mapping for `analyzeEvidence()` (deferred to #516)
- ⏳ MCP tool integration for bot analysis (deferred to #516)

The partial implementation exists to:
1. Demonstrate the provider abstraction pattern
2. Enable availability checking for MCP server configuration
3. Provide a foundation for full implementation in #516

**Configuration (Not Yet Functional):**

```yaml
# This configuration is accepted but will fail at runtime
analysis:
  provider: claude-mcp

  mcp:
    server-url: http://localhost:8765
    timeout-seconds: 120
    max-retries: 3
```

**Runtime Behavior:**

```bash
# Startup logs when MCP is selected
===========================================
Analysis Provider: claude-mcp
Provider Available: false
===========================================
WARN: Analysis provider 'claude-mcp' is NOT available - check configuration (API keys, server URLs, etc.)
WARN: BMAD (Bot Metric Analysis & Diagnostics) features may not work until the provider is properly configured

# Attempting to use it returns error
ERROR: MCP provider analysis is not yet fully implemented (deferred to #516). Health check is available.
```

---

## Configuration

### Default Configuration (Claude API)

```yaml
# No explicit provider property = Claude API (default)
analysis:
  provider: claude-api
```

### Explicit Provider Selection

```yaml
# Use Claude API explicitly
analysis:
  provider: claude-api

# Use Claude MCP (will fail - not implemented)
analysis:
  provider: claude-mcp
```

### Environment Variable Override

```bash
# Set provider via environment variable
export ANALYSIS_PROVIDER=claude-api

# Valid values only - invalid values cause startup failure
# See "Valid Configuration Values" section below
```

### Valid Configuration Values

The `analysis.provider` property accepts exactly two values:

| Value | Behavior |
|-------|----------|
| `claude-api` | Uses Claude API provider (production-ready) |
| `claude-mcp` | Uses Claude MCP provider (health-check functional, analysis execution deferred #516) |
| *undefined* | Defaults to `claude-api` (via Spring's default mechanism) |
| **Any other value** | **Application fails at startup with validation error** |

**Note:** Spring's `matchIfMissing=true` on `@ConditionalOnProperty` only applies when the property is undefined, NOT when it has an invalid value. To prevent confusing bean creation failures, a startup validator (`AnalysisProviderValidator`) explicitly checks the property value and fails fast with a clear error message.

#### Example Validation Error

If you set an invalid value:
```bash
export ANALYSIS_PROVIDER=invalid
./gradlew bootRun
```

You'll see:
```
ERROR AnalysisProviderValidator - Invalid analysis.provider='invalid'.
Valid values: [claude-api, claude-mcp].
Note: If property is undefined, it defaults to 'claude-api'.

***************************
APPLICATION FAILED TO START
***************************

Description:

Validation failed for analysis.provider configuration

Reason: IllegalStateException: Invalid analysis.provider='invalid'

Action:

Set analysis.provider to one of the valid values: claude-api, claude-mcp
Or remove the property to use the default (claude-api)
```

---

## Future MCP Implementation (#516)

The MCP transport layer is complete, but the following work is needed to enable actual analysis:

### Remaining Implementation

1. **Protocol Mapping**
   - Map `AnalysisV1Request` to MCP protocol request
   - Parse MCP protocol response to `AnalysisV1Response`
   - Handle MCP-specific message types and formats

2. **Analysis Operations**
   - Implement `analyzeEvidence()` with MCP protocol calls
   - Error mapping (MCP errors → `AnalysisV1ErrorCode`)

3. **Testing**
   - Unit tests for MCP provider analysis
   - Integration tests with mock MCP server
   - End-to-end tests with real MCP server

4. **Documentation**
   - MCP setup and configuration guide
   - Provider comparison (API vs MCP)
   - Troubleshooting guide

### Files to Modify

- `src/main/java/com/chromascape/web/debug/patchloop/service/provider/ClaudeMcpAnalysisProvider.java`
- `src/test/java/com/chromascape/web/debug/patchloop/service/provider/ClaudeMcpAnalysisProviderTest.java`
- `docs/debugger/BMAD_Provider_Support.md` (this file)
- `CLAUDE.md` (configuration examples)

---

## Provider Interface Contract

All providers must implement the `AnalysisProvider` interface:

```java
public interface AnalysisProvider {
  /**
   * Analyzes evidence and returns diagnosis.
   * @throws AnalysisException if analysis fails
   */
  AnalysisV1Response analyzeEvidence(AnalysisV1Request request) throws AnalysisException;

  /**
   * Returns the provider name for logging/debugging.
   * @return provider name (e.g., "claude-api", "claude-mcp")
   */
  String getProviderName();

  /**
   * Returns whether this provider is available/configured.
   * @return true if provider can be used
   */
  boolean isAvailable();
}
```

---

## Troubleshooting

### Problem: "Analysis provider 'claude-mcp' is NOT available"

**Cause:** MCP provider is not yet implemented (intentional scaffold).

**Solution:** Use Claude API provider instead:
```yaml
analysis:
  provider: claude-api
```

### Problem: "MCP provider is not yet fully implemented"

**Cause:** Attempting to use MCP provider at runtime.

**Solution:** Switch to Claude API provider in configuration or environment variables.

### Problem: "API authentication failed" (Claude API)

**Cause:** Missing or invalid `CLAUDE_API_KEY` environment variable.

**Solution:** Set valid API key:
```bash
export CLAUDE_API_KEY=your-api-key-here
```

### Problem: "Invalid analysis.provider" error at startup

**Cause:** The `analysis.provider` property is set to an invalid value (not `claude-api` or `claude-mcp`).

**Error Message:**
```
ERROR AnalysisProviderValidator - Invalid analysis.provider='invalid-value'.
Valid values: [claude-api, claude-mcp].
```

**Solution:**
1. Check `analysis.provider` in `application.yml` or `ANALYSIS_PROVIDER` environment variable
2. Set it to one of the valid values: `claude-api`, `claude-mcp`
3. Or remove the property entirely to use the default (`claude-api`)

**Note:** Unlike some Spring properties, invalid values do NOT fall back to the default. The application will fail fast at startup with a clear error message to prevent downstream bean creation failures.

---

## Verification

### Check Active Provider at Startup

Look for startup logs:

```
===========================================
Analysis Provider: claude-api
Provider Available: true
===========================================
```

### Verify Provider Selection in Tests

Run provider selection integration tests:

```bash
./gradlew test --tests "AnalysisProviderSelectionTest"
```

### Verify MCP Stub Behavior

Run MCP stub behavior tests:

```bash
./gradlew test --tests "ClaudeMcpAnalysisProviderTest"
```

---

## Summary

| Provider | Status | Default | Production Ready | Notes |
|----------|--------|---------|------------------|-------|
| **Claude API** | ✅ Implemented | Yes | Yes | Fully functional, recommended for production |
| **Claude MCP** | ⚠️ Partial | No | No | Health check implemented, analysis deferred to #516 |

**Recommendation:** Use Claude API provider (default) for all production use. MCP provider has working health checks but analysis is not yet implemented.

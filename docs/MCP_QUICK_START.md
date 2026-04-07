# MCP Bridge Quick Start Guide

## 🚀 Zero-Configuration Startup

### One Command to Rule Them All

```bash
cd /mnt/f/Dev/IdeaProjects/ChromaScape
gradlew.bat bootRun
```

That's it! The MCP bridge auto-configures and starts automatically.

---

## ✅ What to Expect

### Console Output

You should see these key messages during startup:

```
===========================================
MCP Bridge Lifecycle Manager
===========================================
[INFO] Starting MCP bridge automatically...
[INFO] Detected Codex CLI
[INFO] Generated .env with backend: mcp, provider: codex
[INFO] MCP bridge started successfully on port 8765
===========================================

===========================================
Analysis Provider Status
===========================================
[INFO] MCP Bridge Auto-Start: ENABLED
[INFO] MCP Bridge Process: RUNNING
[INFO] Analysis Provider: claude-mcp
[INFO] Provider Available: YES
===========================================
```

> **Note:** `WRAPPER_PROVIDER` may be `codex`, `claude`, or `opencode` (which CLI tool the bridge uses),
> but Spring's `analysis.provider` always shows as `claude-mcp` when using the bridge.

### Health Check

Open in browser:
- **ChromaScape**: http://localhost:8090
- **Debugger**: http://localhost:8090/debugger
- **Bridge Health**: http://localhost:8765/health

---

## 🎯 Testing Patch Loop

1. **Open Debugger**
   ```
   http://localhost:8090/debugger
   ```

2. **Create a Failed Script Execution**
   - Run a bot script until it fails
   - Or use an existing failed execution

3. **Execute Patch Loop**
   - Click "Execute Loop" button
   - Wait 30-60 seconds for analysis

4. **Verify Results**
   - ✅ Diagnosis section appears
   - ✅ Impacted modules listed
   - ✅ Patch draft generated
   - ✅ Retest plan created

---

## 🔧 Configuration (Optional)

### Environment Variables

Override defaults:

```bash
# Disable auto-start
export MCP_AUTO_START=false

# Custom timeout
export MCP_STARTUP_TIMEOUT=60

# Use API mode instead
export ANALYSIS_PROVIDER=claude-api
export ANTHROPIC_API_KEY=sk-ant-api03-your-key
```

### Custom .env

Edit `scripts/mcp-bridge/.env`:

```env
ANALYSIS_BACKEND=mcp
WRAPPER_PROVIDER=claude
MCP_PORT=8765
MCP_DEFAULT_MODEL=claude-opus-4-6
ALLOWED_ORIGINS=http://localhost:8090
```

---

## 🎯 BYO Subscription Onboarding (Recommended)

### Why Register ChromaScape as an MCP Server?

ChromaScape can work directly with your **existing Claude or Codex CLI subscription** by registering as an MCP server. This gives you:

- ✅ **Use your existing subscription** - No extra API key needed
- ✅ **Direct CLI integration** - Leverage your authenticated Claude/Codex session
- ✅ **Per-user configuration** - No project-level config files to commit
- ✅ **Auto-detected by ChromaScape** - Works seamlessly with auto-start bridge

### One-Command Registration

```bash
# Windows
cd scripts\mcp-bridge
.\register-mcp-clients.bat

# Unix/Linux/macOS
cd scripts/mcp-bridge
./register-mcp-clients.sh
```

The script will:
1. ✅ Check for Node.js and wrapper/server.js
2. ✅ Register with Claude CLI (if installed)
3. ✅ Register with Codex CLI (if installed)
4. ✅ Verify each registration
5. ✅ Display summary of results

### What Gets Registered?

When you run the registration script, ChromaScape is added as an MCP server named `chromascape` to your CLI configuration:

**For Claude CLI:**
```bash
claude mcp add -s user -e WRAPPER_PROVIDER=claude chromascape -- node "path/to/wrapper/server.js"
```

**For Codex CLI:**
```bash
codex mcp add chromascape --env WRAPPER_PROVIDER=codex -- node "path/to/wrapper/server.js"
```

After Codex registration, the script also enforces:

```toml
[mcp_servers.chromascape]
startup_timeout_sec = 120
```

Override this default by setting `MCP_STARTUP_TIMEOUT_SEC` before running the registration script.

The registration:
- Uses **per-user scope** (`-s user` for Claude) - not committed to project
- Sets environment variables (`WRAPPER_PROVIDER`, `WRAPPER_CLAUDE_COMMAND` or `WRAPPER_CODEX_COMMAND`)
- Points to the **absolute path** of `wrapper/server.js`
- Can be **removed** with `claude mcp remove chromascape` or `codex mcp remove chromascape`

### Verification

After registration, verify it worked:

```bash
# Windows
cd scripts
.\test-mcp-registration.bat

# Unix/Linux/macOS
cd scripts
./test-mcp-registration.sh
```

Expected output:
```
============================================================
ChromaScape MCP Registration Verification
============================================================

Checking Claude CLI registration...
     Claude CLI detected
[OK]  ChromaScape registered with Claude CLI (verified)

Checking Codex CLI registration...
     Codex CLI detected
[OK]  ChromaScape registered with Codex CLI (verified, timeout=120s)
```

Verification exit codes:
| Code | Meaning |
|------|---------|
| 0 | At least one CLI registered, no timeout mismatch |
| 1 | Neither CLI registered |
| 2 | Registered but `startup_timeout_sec` value doesn't match `EXPECTED_STARTUP_TIMEOUT_SEC` |

### How It Works

When ChromaScape auto-starts the MCP bridge:

1. ChromaScape detects **Codex CLI** first (preferred), then **Claude CLI**, then **OpenCode CLI**
2. Auto-generates `.env` with detected provider
3. Starts MCP bridge with that provider
4. Bridge uses your **authenticated CLI session** for analysis
5. No API key needed - uses your existing subscription

### Removing Registration

If you need to remove ChromaScape from your MCP server list:

```bash
# Claude CLI
claude mcp remove chromascape -s user

# Codex CLI
codex mcp remove chromascape
```

### Edge Cases

| Scenario | Behavior |
|----------|----------|
| Only Claude CLI installed | Registers with Claude only, warns about Codex |
| Only Codex CLI installed | Registers with Codex only, warns about Claude |
| Neither CLI installed | Shows error with installation instructions |
| CLI installed but not authenticated | Registration succeeds, CLI fails when invoked |
| Existing "chromascape" entry | Removes then adds (idempotent) |
| Spaces in directory path | Properly quoted paths in registration |

### Hybrid Compatibility Mode

ChromaScape supports **dual-mode operation**:

1. **Auto-start bridge** (default) - ChromaScape manages the bridge process
2. **MCP client registration** - CLI invokes ChromaScape as an MCP server

Both modes work together seamlessly. Registration enables CLI integration while the auto-start bridge handles ChromaScape's internal analysis needs.

---

## 🚨 Troubleshooting

### Bridge Fails to Start

**Error:** `No provider available`

**Fix:** Install Claude CLI
```bash
npm install -g @anthropic-ai/claude-code
claude setup-token
```

**OR** use API mode:
```bash
export ANTHROPIC_API_KEY=sk-ant-api03-your-key
export ANALYSIS_PROVIDER=claude-api
gradlew.bat bootRun
```

### Provider Unavailable

**Error:** `Provider Available: NO`

**Check:**
1. Claude CLI authenticated:
   ```bash
   claude --version
   ```

2. Health endpoint:
   ```bash
   curl http://localhost:8765/health
   ```

3. Bridge logs (search for `[Bridge]` in console)

### Codex MCP Startup Timeout Not Sticking

**Symptom:** Codex reports startup timeout for `chromascape` after re-registration, or `test-mcp-registration.sh` shows a timeout mismatch.

**Fix:** Re-run registration with an explicit timeout:
```bash
cd scripts/mcp-bridge
MCP_STARTUP_TIMEOUT_SEC=120 ./register-mcp-clients.sh
```

Then verify (exit code 2 means timeout mismatch, 0 means OK):
```bash
EXPECTED_STARTUP_TIMEOUT_SEC=120 ./test-mcp-registration.sh
echo $?
```
Expected: exit code 0, output shows `startup_timeout_sec: 120`.

#### Probe Scripts

Three probe scripts validate the MCP integration at different levels:

**Startup/handshake probe** (tests MCP transport, not provider execution):
```bash
cd scripts/mcp-bridge
npm run probe:mcp
```
Expected: `ok: true`, `toolNames` includes `analyze`. Typically completes in < 10s.

**Analyze smoke probe** (tests live provider execution through the MCP wrapper):
```bash
cd scripts/mcp-bridge
npm run probe:mcp:analyze
```
This calls the `analyze` tool with a minimal prompt through the MCP wrapper,
validating both the transport/handshake path **and** the actual provider execution
path used by patch-loop analysis.

Expected: `ok: true`, `analyzeIsError: false`, at least one content item returned.

**Retry behavior (analyze probe):**
- The analyze probe retries automatically on failure (default: 2 retries, 3 total attempts).
- Between retries, it waits with linear backoff (default: 3000ms * attempt index).
- Override: `--retries 0` to disable, `--backoffMs 5000` for longer delays.
- Output includes `attemptsTotal`, `attemptsUsed`, and `attemptResults` arrays.

**Timeout behavior:**
- `--analyzeTimeoutSec` controls both the wrapper/provider timeout (via `timeoutSec` argument)
  and the MCP SDK client request timeout (via `options.timeout`).
- Default is 180 seconds for `npm run probe:mcp:analyze`. Provider latency (Codex exec/auth)
  may require > 60s, so the default is intentionally generous.
- Override: `node probe-codex-mcp-startup.js --analyze-smoke --analyzeTimeoutSec 240`

**Exit codes (MCP probes):**
| Code | Stage | Meaning |
|------|-------|---------|
| 0 | — | Success |
| 1 | runtime | Unhandled exception |
| 2 | startup | Connect/list_tools timed out |
| 3 | analyze | All analyze attempts exhausted (timeout or exception) |
| 4 | provider_error | All attempts returned `isError: true` |

#### Patch-Loop E2E Probe

The E2E probe validates the **full patch-loop pipeline** against a running ChromaScape server:

**Analysis-only mode** (validates analysis artifact generation):
```bash
cd scripts/mcp-bridge
npm run probe:patchloop:e2e
```

**Autofix mode** (validates analysis + patch artifact generation):
```bash
cd scripts/mcp-bridge
npm run probe:patchloop:e2e:autofix
```

The probe:
1. POSTs an execution request to `/api/debug/patch-loop/execute`
2. Polls `/api/debug/patch-loop/{iterationId}` until terminal status
3. Fetches `/api/debug/patch-loop/{iterationId}/artifacts`
4. Validates that required artifacts (analysis, patches) are present

**Custom overrides:**
```bash
# Different script or prompt
node probe-patch-loop-e2e.js --scriptName MyScript --prompt "Custom prompt" --executionMode analysis_only

# Longer timeout for slow environments
node probe-patch-loop-e2e.js --timeoutSec 600 --pollMs 5000
```

**Prerequisites:**
- ChromaScape must be running at `http://localhost:8090`
- MCP bridge must be connected and provider available
- The target script (default: `DemoFishingScript`) must exist

**Exit codes (E2E probe):**
| Code | Meaning |
|------|---------|
| 0 | Success: terminal status is COMPLETED and criteria satisfied |
| 1 | Runtime/unhandled exception |
| 4 | Deadline timeout waiting for terminal state |
| 5 | Terminal non-success (FAILED, CANCELLED, INSUFFICIENT_EVIDENCE) or criteria not satisfied (e.g., missing artifacts) |
| 6 | API/network/protocol error during polling |

**Success definition:** A terminal iteration is only reported as success when the status is exactly `COMPLETED` **and** the required artifact criteria are met. Terminal states like `FAILED`, `CANCELLED`, or `INSUFFICIENT_EVIDENCE` always report `ok: false` with exit code 5, regardless of artifact counts.

**Poll-stage error taxonomy:**
- Real deadline timeout (iteration never reached terminal state) => exit 4, `exitReason: "timeout"`
- API/network/protocol errors during polling => exit 6, `exitReason: "api_error"`
- Terminal non-success status => exit 5, `exitReason: "terminal_failed"`
- COMPLETED but missing artifacts => exit 5, `exitReason: "criteria_failed"`

All error outputs include an `exitReason` field for CI diagnosis.

**Autofix mode artifact requirements:**
- By default, `autofix_loop` mode requires at least one patch artifact **and** at least one retest artifact.
- Override the retest requirement with `--requireRetest false` for constrained environments.
- `analysis_only` mode requires only an analysis artifact.

#### Codex MCP Server Verification

The server verification probe checks **registration/configuration only** for required MCP servers in Codex CLI:

```bash
# Check both chromascape and shortcut (default)
cd scripts/mcp-bridge
npm run probe:mcp:servers

# Check only chromascape (backward-compatible)
npm run probe:mcp:servers:chromascape

# Custom expected timeout
node verify-codex-mcp-servers.js --servers chromascape,shortcut --expectedChromascapeTimeout 120
```

For each server, the probe validates:
- `enabled: true`
- Transport is configured (e.g., `stdio`)
- For `chromascape`: `startup_timeout_sec` matches the expected value with strict numeric equality (`120 == 120.0`, but `120 != 120.9`)

**Output:** Machine-readable JSON summary:
```json
{
  "ok": true,
  "servers": ["chromascape", "shortcut"],
  "expectedChromascapeTimeout": 120,
  "serverResults": {
    "chromascape": { "status": "ok", "enabled": true, "transport": "stdio", "startupTimeoutSec": 120 },
    "shortcut": { "status": "ok", "enabled": true, "transport": "stdio", "startupTimeoutSec": null }
  }
}
```

**Exit codes:**
| Code | Meaning |
|------|---------|
| 0 | All servers configured and valid |
| 1 | One or more servers missing or unconfigured |
| 2 | Chromascape startup_timeout_sec mismatch |
| 6 | Codex CLI invocation or parsing error |

#### Shortcut Runtime Smoke Probe

Use this probe to validate **actual Shortcut MCP runtime behavior** (connect + list tools + `users-get-current` call), not just config presence:

```bash
cd scripts/mcp-bridge
npm run probe:mcp:shortcut:smoke
```

Custom flags:
```bash
node probe-shortcut-mcp-smoke.js --server shortcut --timeoutSec 60
```

Exit codes:
| Code | Meaning |
|------|---------|
| 0 | Runtime smoke succeeded |
| 1 | Shortcut missing or unconfigured |
| 5 | Runtime smoke failed (connect/list/call) |
| 6 | Codex CLI invocation or parsing error |

**Cross-platform timeout parsing:** Registration and verification scripts compare timeout values with strict numeric equality. Values like `120` and `120.0` are treated as equal, while `120.9` is not equal to `120`. The `.sh` scripts use `sed -nE` (POSIX-safe) instead of `grep -oP` (Perl-only) for timeout extraction; the `.bat` scripts use PowerShell double-precision comparison.

#### If Analyze Probe Still Fails

Classify by the `stage` field in the error output:

| Stage | Meaning | Next Step |
|-------|---------|-----------|
| `startup` | MCP transport/wrapper failed | Check wrapper path, node version |
| `analyze` | Provider latency or SDK timeout | Retry with higher `--analyzeTimeoutSec 240` |
| `provider_error` | Wrapper/provider parsing/auth failure | Check `snippet` field for error details |
| `runtime` | Unexpected exception | Check `error` field for stack trace |

Additional diagnostics:
```bash
# Run with extra timeout and no retries for cleaner output
node probe-codex-mcp-startup.js --analyze-smoke --analyzeTimeoutSec 240 --retries 0

# Run E2E probe with verbose polling
node probe-patch-loop-e2e.js --timeoutSec 600 --pollMs 3000

# Check Codex auth
codex --version
```

### Port Already in Use

**Error:** `Address already in use: 8765`

**Fix:** Find and kill the process:
```bash
# Windows
netstat -ano | findstr :8765
taskkill /PID <PID> /F
```

---

## 📊 Verification Checklist

After starting ChromaScape, verify:

- [ ] Console shows `MCP Bridge Auto-Start: ENABLED`
- [ ] Console shows `MCP Bridge Process: RUNNING`
- [ ] Console shows `Provider Available: YES`
- [ ] http://localhost:8090 loads
- [ ] http://localhost:8090/debugger loads
- [ ] http://localhost:8765/health returns `{"healthy":true}`
- [ ] Patch Loop execution completes successfully
- [ ] Results appear in debugger UI

---

## 🔄 Common Workflows

### Daily Development

```bash
# Start everything (automatic)
gradlew.bat bootRun

# Stop (Ctrl+C stops both ChromaScape and bridge)
```

### Manual Bridge Management

```bash
# Disable auto-start
export MCP_AUTO_START=false

# Start ChromaScape
gradlew.bat bootRun

# Start bridge manually (separate terminal)
cd scripts/mcp-bridge
npm start
```

### Switch to API Mode

```bash
# Set API key
export ANTHROPIC_API_KEY=sk-ant-api03-your-key
export ANALYSIS_PROVIDER=claude-api

# Start (no bridge needed)
gradlew.bat bootRun
```

---

## 📝 Key Files

- **Configuration**: `src/main/resources/application.yml`
- **Bridge .env**: `scripts/mcp-bridge/.env` (auto-generated)
- **Logs**: Console output (search for `[Bridge]`)
- **Documentation**: `docs/MCP_BRIDGE_AUTO_START_SUMMARY.md`

---

## 🎉 Success!

If you see this, you're good to go:

```
===========================================
Analysis Provider Status
===========================================
[INFO] MCP Bridge Process: RUNNING
[INFO] Provider Available: YES
===========================================
```

**Happy debugging!** 🚀

---

*For detailed implementation info, see: `docs/MCP_BRIDGE_AUTO_START_SUMMARY.md`*

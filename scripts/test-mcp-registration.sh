#!/bin/bash
# ==============================================================================
# ChromaScape MCP Registration Verification Script (Unix/Linux/macOS)
# ==============================================================================
#
# Verifies that ChromaScape is registered as an MCP server with
# Claude CLI and/or Codex CLI.
#
# For Codex, registration metadata is displayed for operator visibility, but
# the actual pass/fail decision is delegated to verify-codex-mcp-servers.js,
# which also performs a real MCP handshake. A plain `codex mcp get` result is
# never treated as success on its own.
#
# Usage: ./test-mcp-registration.sh
#
# Exit codes:
#   0 = Codex verified via handshake and/or Claude registered
#   1 = neither CLI registered, or Codex verification failed (other state)
#   2 = Codex startup_timeout_sec mismatch (verifier)
#   6 = Codex CLI invocation or parsing error (verifier)
#
# ==============================================================================

echo "============================================================"
echo "ChromaScape MCP Registration Verification"
echo "============================================================"
echo ""

# Track status
CLAUDE_FOUND=0
CODEX_OK=0
VERIFIER_EXIT=0
EXPECTED_STARTUP_TIMEOUT="${EXPECTED_STARTUP_TIMEOUT_SEC:-120}"
if ! echo "$EXPECTED_STARTUP_TIMEOUT" | grep -Eq '^[0-9]+([.][0-9]+)?$'; then
    echo "ERROR: EXPECTED_STARTUP_TIMEOUT_SEC must be numeric (got: $EXPECTED_STARTUP_TIMEOUT)"
    exit 1
fi

# Derive paths from this script's location (no hard-coded mounts or drives)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER="$SCRIPT_DIR/mcp-bridge/wrapper/server.js"
VERIFIER="$SCRIPT_DIR/mcp-bridge/verify-codex-mcp-servers.js"

# Check Claude CLI
echo "Checking Claude CLI registration..."
if ! command -v claude &> /dev/null; then
    echo "[--] Claude CLI not installed"
else
    echo "     Claude CLI detected"
    if claude mcp get chromascape &> /dev/null; then
        # Parse and verify configuration
        CONFIG_OUTPUT=$(claude mcp get chromascape 2>/dev/null)
        if echo "$CONFIG_OUTPUT" | grep -q "Type: stdio" && echo "$CONFIG_OUTPUT" | grep -q "Command: node"; then
            echo "[OK]  ChromaScape registered with Claude CLI (verified)"
            CLAUDE_FOUND=1
        else
            echo "[!!]  ChromaScape found but configuration invalid"
            echo "      Expected: Type: stdio, Command: node"
            echo "      Got: $CONFIG_OUTPUT"
        fi
    else
        echo "[--] ChromaScape NOT registered with Claude CLI"
    fi

    if [ $CLAUDE_FOUND -eq 1 ]; then
        echo ""
        echo "     Registration details:"
        claude mcp get chromascape 2>/dev/null || true
    fi
fi

echo ""

# Check Codex CLI
echo "Checking Codex CLI registration..."
if ! command -v codex &> /dev/null; then
    echo "[--] Codex CLI not installed"
else
    echo "     Codex CLI detected"

    echo "     Registration details:"
    codex mcp get chromascape 2>/dev/null || true
    echo ""

    echo "     Running verification (configuration + live MCP handshake)..."
    if [ ! -f "$WRAPPER" ]; then
        echo "[!!]  Wrapper not found at: $WRAPPER"
        VERIFIER_EXIT=1
    elif [ ! -f "$VERIFIER" ]; then
        echo "[!!]  Verifier not found at: $VERIFIER"
        VERIFIER_EXIT=1
    else
        if node "$VERIFIER" \
            --servers chromascape \
            --expectedChromascapeWrapper "$WRAPPER" \
            --expectedChromascapeTimeout "$EXPECTED_STARTUP_TIMEOUT" \
            --handshakeTimeoutSec "$EXPECTED_STARTUP_TIMEOUT"; then
            VERIFIER_EXIT=0
        else
            VERIFIER_EXIT=$?
        fi
    fi

    if [ $VERIFIER_EXIT -eq 0 ]; then
        echo "[OK]  ChromaScape registered with Codex CLI (verified, timeout=${EXPECTED_STARTUP_TIMEOUT}s)"
        CODEX_OK=1
    else
        echo "[!!]  ChromaScape Codex registration verification failed (exit $VERIFIER_EXIT)"
        echo "      Verify the wrapper path, the registration, and that the MCP handshake succeeds."
    fi
fi

echo ""

# Show summary
echo "============================================================"
echo "Summary"
echo "============================================================"
echo ""

if [ $CLAUDE_FOUND -eq 1 ]; then
    echo "[OK] Claude CLI: ChromaScape registered"
elif ! command -v claude &> /dev/null; then
    echo "[--] Claude CLI: Not installed"
else
    echo "[--] Claude CLI: ChromaScape not registered"
fi

echo ""

if [ $CODEX_OK -eq 1 ]; then
    echo "[OK] Codex CLI: ChromaScape registered"
elif ! command -v codex &> /dev/null; then
    echo "[--] Codex CLI: Not installed"
else
    echo "[--] Codex CLI: ChromaScape not registered"
fi

echo ""

# Show remediation if needed
if [ $CLAUDE_FOUND -eq 0 ] && command -v claude &> /dev/null; then
    echo ""
    echo "To register with Claude CLI:"
    echo "  cd scripts/mcp-bridge"
    echo "  ./register-mcp-clients.sh"
    echo ""
fi

if [ $CODEX_OK -eq 0 ] && command -v codex &> /dev/null; then
    echo ""
    echo "To register with Codex CLI:"
    echo "  cd scripts/mcp-bridge"
    echo "  ./register-mcp-clients.sh"
    echo ""
fi

# Exit with appropriate code: propagate verifier failure first
if [ $CODEX_OK -eq 0 ] && command -v codex &> /dev/null; then
    if [ $VERIFIER_EXIT -eq 0 ]; then
        VERIFIER_EXIT=1
    fi
    exit $VERIFIER_EXIT
fi

if [ $CLAUDE_FOUND -eq 1 ] || [ $CODEX_OK -eq 1 ]; then
    exit 0
fi
exit 1

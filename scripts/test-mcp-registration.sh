#!/bin/bash
# ==============================================================================
# ChromaScape MCP Registration Verification Script (Unix/Linux/macOS)
# ==============================================================================
#
# Verifies that ChromaScape is registered as an MCP server with
# Claude CLI and/or Codex CLI.
#
# Usage: ./test-mcp-registration.sh
#
# ==============================================================================

echo "============================================================"
echo "ChromaScape MCP Registration Verification"
echo "============================================================"
echo ""

# Track status
CLAUDE_FOUND=0
CODEX_FOUND=0
TIMEOUT_MISMATCH=0
EXPECTED_STARTUP_TIMEOUT="${EXPECTED_STARTUP_TIMEOUT_SEC:-90}"
if ! echo "$EXPECTED_STARTUP_TIMEOUT" | grep -Eq '^[0-9]+([.][0-9]+)?$'; then
    echo "ERROR: EXPECTED_STARTUP_TIMEOUT_SEC must be numeric (got: $EXPECTED_STARTUP_TIMEOUT)"
    exit 1
fi

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
    if codex mcp get chromascape &> /dev/null; then
        # Parse and verify configuration
        CONFIG_OUTPUT=$(codex mcp get chromascape 2>/dev/null)
        if echo "$CONFIG_OUTPUT" | grep -q "transport: stdio" \
            && echo "$CONFIG_OUTPUT" | grep -q "enabled: true" \
            && echo "$CONFIG_OUTPUT" | grep -Eq "startup_timeout_sec:[[:space:]]*[0-9]+([.][0-9]+)?"; then
            # Validate timeout value matches expected (POSIX-safe extraction + strict numeric compare)
            ACTUAL_TIMEOUT=$(echo "$CONFIG_OUTPUT" | sed -nE 's/.*startup_timeout_sec:[[:space:]]*([0-9]+([.][0-9]+)?).*/\1/p' | head -1)
            if [ -n "$ACTUAL_TIMEOUT" ]; then
                if ! awk -v a="$ACTUAL_TIMEOUT" -v b="$EXPECTED_STARTUP_TIMEOUT" 'BEGIN { exit !(a + 0 == b + 0) }'; then
                    echo "[!!]  startup_timeout_sec mismatch: expected=${EXPECTED_STARTUP_TIMEOUT}, actual=${ACTUAL_TIMEOUT}"
                    TIMEOUT_MISMATCH=1
                else
                    echo "[OK]  ChromaScape registered with Codex CLI (verified, timeout=${ACTUAL_TIMEOUT}s)"
                fi
            fi
            CODEX_FOUND=1
        else
            echo "[!!]  ChromaScape found but configuration invalid"
            echo "      Expected: transport: stdio, enabled: true, startup_timeout_sec: <number>"
            echo "      Got: $CONFIG_OUTPUT"
        fi
    else
        echo "[--] ChromaScape NOT registered with Codex CLI"
    fi

    if [ $CODEX_FOUND -eq 1 ]; then
        echo ""
        echo "     Registration details:"
        codex mcp get chromascape 2>/dev/null || true
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

if [ $CODEX_FOUND -eq 1 ]; then
    echo "[OK] Codex CLI: ChromaScape registered"
    if [ $TIMEOUT_MISMATCH -eq 1 ]; then
        echo "[!!]          startup_timeout_sec does not match expected=${EXPECTED_STARTUP_TIMEOUT}"
        echo "               Re-register with: MCP_STARTUP_TIMEOUT_SEC=${EXPECTED_STARTUP_TIMEOUT} ./register-mcp-clients.sh"
    fi
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

if [ $CODEX_FOUND -eq 0 ] && command -v codex &> /dev/null; then
    echo ""
    echo "To register with Codex CLI:"
    echo "  cd scripts/mcp-bridge"
    echo "  ./register-mcp-clients.sh"
    echo ""
fi

# Exit with appropriate code
if [ $CLAUDE_FOUND -eq 1 ] || [ $CODEX_FOUND -eq 1 ]; then
    if [ $TIMEOUT_MISMATCH -eq 1 ]; then
        exit 2
    fi
    exit 0
fi
exit 1

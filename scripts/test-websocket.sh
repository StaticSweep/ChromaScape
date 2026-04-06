#!/bin/bash
# WebSocket E2E Test Script for ChromaScape Patch Loop
#
# This script tests the WebSocket routing for patch loop updates.
# Prerequisites: ChromaScape application running on port 8090
#
# Usage: ./scripts/test-websocket.sh

echo "=== ChromaScape WebSocket E2E Test Script ==="
echo ""

# Check if websocat is available
if ! command -v websocat &> /dev/null; then
    echo "ERROR: websocat is not installed."
    echo ""
    echo "Install websocat:"
    echo "  - On Ubuntu/Debian: sudo apt install websocat"
    echo "  - On Windows (with cargo): cargo install websocat"
    echo "  - Or use browser console as alternative"
    echo ""
    exit 1
fi

# Check if app is running
echo "Checking if ChromaScape is running on port 8090..."
if ! curl -s http://localhost:8090/actuator/health > /dev/null 2>&1; then
    echo "ERROR: ChromaScape is not running on port 8090"
    echo "Please start the application first:"
    echo "  cmd.exe /c 'start cmd /k \"cd /d F:\\Dev\\IdeaProjects\\ChromaScape && gradlew.bat bootRun\"'"
    echo ""
    exit 1
fi

echo "✓ ChromaScape is running"
echo ""

# Color codes
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counter
PASSED=0
FAILED=0

# Test function
run_test() {
    local test_name=$1
    local url=$2
    local expected_message=$3
    local timeout=5

    echo -n "Testing: $test_name ... "

    # Run websocat with timeout and check for expected message
    output=$(timeout $timeout websocat "$url" 2>&1)

    if echo "$output" | grep -q "$expected_message"; then
        echo -e "${GREEN}PASSED${NC}"
        ((PASSED++))
    else
        echo -e "${RED}FAILED${NC}"
        echo "  Expected to find: $expected_message"
        echo "  Got: $output"
        ((FAILED++))
    fi
}

# Scenario 1: Base Connection Test
echo "=== Scenario 1: Base Connection Test ==="
run_test "Base connection to /ws/debug/patch-loop" \
    "ws://localhost:8090/ws/debug/patch-loop" \
    "CONNECTED"
echo ""

# Scenario 2: Execution-Scoped Connection Test
echo "=== Scenario 2: Execution-Scoped Connection Test ==="
run_test "Scoped connection to /ws/debug/patch-loop/test-exec-123" \
    "ws://localhost:8090/ws/debug/patch-loop/test-exec-123" \
    "CONNECTED"
echo ""

# Scenario 3: Complex Execution ID
echo "=== Scenario 3: Complex Execution ID Test ==="
run_test "Scoped connection with complex execution ID" \
    "ws://localhost:8090/ws/debug/patch-loop/iteration-abc-456-def" \
    "CONNECTED"
echo ""

# Summary
echo "=== Test Summary ==="
echo -e "Passed: ${GREEN}$PASSED${NC}"
echo -e "Failed: ${RED}$FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed!${NC}"
    exit 1
fi

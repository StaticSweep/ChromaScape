#!/usr/bin/env bash
# test-loop.sh - Run a patch loop iteration for testing.
#
# Usage:
#   ./scripts/test-loop.sh <script-name> "<prompt>" [mode]
#
# Examples:
#   ./scripts/test-loop.sh CombatTest "Fix eating timing" propose_then_apply
#   ./scripts/test-loop.sh WoodcuttingTest "Diagnose why the bot banks too early"
#
# Requires: curl, jq (optional for pretty-printing)

set -euo pipefail

SCRIPT_NAME="${1:?Usage: test-loop.sh <script-name> '<prompt>' [mode]}"
PROMPT="${2:?Usage: test-loop.sh <script-name> '<prompt>' [mode]}"
MODE="${3:-propose_then_apply}"
BASE_URL="${CHROMASCAPE_URL:-http://localhost:8090}"
LOCK_DIR="${TMPDIR:-/tmp}/chromascape-test-loop.lock"
MAX_TIME="${CHROMASCAPE_CURL_MAX_TIME:-120}"
CONNECT_TIMEOUT="${CHROMASCAPE_CURL_CONNECT_TIMEOUT:-5}"

if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
  echo "Another test-loop run is active (lock: ${LOCK_DIR})" >&2
  exit 1
fi
trap 'rmdir "${LOCK_DIR}" 2>/dev/null || true' EXIT

echo "=== Test Loop ==="
echo "Script: ${SCRIPT_NAME}"
echo "Prompt: ${PROMPT}"
echo "Mode:   ${MODE}"
echo ""

RESPONSE=$(curl -sS --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${MAX_TIME}" -X POST "${BASE_URL}/api/debug/patch-loop/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"scriptName\": \"${SCRIPT_NAME}\",
    \"prompt\": \"${PROMPT}\",
    \"executionMode\": \"${MODE}\",
    \"maxAttempts\": 3
  }")

echo "Response:"
if command -v jq &>/dev/null; then
  echo "${RESPONSE}" | jq .
else
  echo "${RESPONSE}"
fi

# Extract iteration ID if available
ITERATION_ID=$(echo "${RESPONSE}" | grep -o '"iterationId":"[^"]*"' | cut -d'"' -f4)
if [ -n "${ITERATION_ID}" ]; then
  echo ""
  echo "Iteration ID: ${ITERATION_ID}"
  echo "Monitor: ${BASE_URL}/debugger"
fi

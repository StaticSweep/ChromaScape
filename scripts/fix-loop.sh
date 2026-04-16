#!/usr/bin/env bash
# fix-loop.sh - Run a full auto-fix patch loop (apply patches + retest).
#
# Usage:
#   ./scripts/fix-loop.sh <script-name> "<prompt>" [max-attempts]
#
# Examples:
#   ./scripts/fix-loop.sh CombatTest "Fix prayer flicking detection" 5
#   ./scripts/fix-loop.sh QuestHelper "NPC dialogue not being detected"
#
# Requires: curl, jq (optional for pretty-printing)

set -euo pipefail

SCRIPT_NAME="${1:?Usage: fix-loop.sh <script-name> '<prompt>' [max-attempts]}"
PROMPT="${2:?Usage: fix-loop.sh <script-name> '<prompt>' [max-attempts]}"
MAX_ATTEMPTS="${3:-3}"
BASE_URL="${CHROMASCAPE_URL:-http://localhost:8090}"
LOCK_DIR="${TMPDIR:-/tmp}/chromascape-fix-loop.lock"
MAX_TIME="${CHROMASCAPE_CURL_MAX_TIME:-180}"
CONNECT_TIMEOUT="${CHROMASCAPE_CURL_CONNECT_TIMEOUT:-5}"

if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
  echo "Another fix-loop run is active (lock: ${LOCK_DIR})" >&2
  exit 1
fi
trap 'rmdir "${LOCK_DIR}" 2>/dev/null || true' EXIT

echo "=== Fix Loop (Auto-fix) ==="
echo "Script:  ${SCRIPT_NAME}"
echo "Prompt:  ${PROMPT}"
echo "Max:     ${MAX_ATTEMPTS} attempts"
echo ""

RESPONSE=$(curl -sS --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${MAX_TIME}" -X POST "${BASE_URL}/api/debug/patch-loop/execute" \
  -H "Content-Type: application/json" \
  -d "{
    \"scriptName\": \"${SCRIPT_NAME}\",
    \"prompt\": \"${PROMPT}\",
    \"executionMode\": \"autofix_loop\",
    \"maxAttempts\": ${MAX_ATTEMPTS}
  }")

echo "Response:"
if command -v jq &>/dev/null; then
  echo "${RESPONSE}" | jq .
else
  echo "${RESPONSE}"
fi

ITERATION_ID=$(echo "${RESPONSE}" | grep -o '"iterationId":"[^"]*"' | cut -d'"' -f4)
EXECUTION_ID=$(echo "${RESPONSE}" | grep -o '"executionId":"[^"]*"' | cut -d'"' -f4)

echo ""
if [ -n "${ITERATION_ID}" ]; then
  echo "Iteration ID: ${ITERATION_ID}"
fi
if [ -n "${EXECUTION_ID}" ]; then
  echo "Execution ID: ${EXECUTION_ID}"
fi
echo ""
echo "Monitor progress in the debugger: ${BASE_URL}/debugger"
echo ""
echo "Poll status:"
echo "  curl ${BASE_URL}/api/debug/patch-loop/${ITERATION_ID}"

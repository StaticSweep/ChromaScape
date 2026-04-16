#!/usr/bin/env bash
# review-loop.sh - List and review patch loop iterations.
#
# Usage:
#   ./scripts/review-loop.sh [status] [script-name]
#
# Examples:
#   ./scripts/review-loop.sh
#   ./scripts/review-loop.sh COMPLETED
#   ./scripts/review-loop.sh RUNNING CombatTest
#
# Requires: curl, jq (optional for pretty-printing)

set -euo pipefail

STATUS="${1:-}"
SCRIPT_NAME="${2:-}"
BASE_URL="${CHROMASCAPE_URL:-http://localhost:8090}"
MAX_TIME="${CHROMASCAPE_CURL_MAX_TIME:-60}"
CONNECT_TIMEOUT="${CHROMASCAPE_CURL_CONNECT_TIMEOUT:-5}"

PARAMS=""
if [ -n "${STATUS}" ]; then
  PARAMS="?status=${STATUS}"
fi
if [ -n "${SCRIPT_NAME}" ]; then
  SEPARATOR="${PARAMS:+&}"
  PARAMS="${PARAMS:-?}scriptName=${SCRIPT_NAME}"
fi

echo "=== Patch Loop Iterations ==="
if [ -n "${STATUS}" ]; then echo "Filter: status=${STATUS}"; fi
if [ -n "${SCRIPT_NAME}" ]; then echo "Filter: script=${SCRIPT_NAME}"; fi
echo ""

RESPONSE=$(curl -sS --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${MAX_TIME}" "${BASE_URL}/api/debug/patch-loop${PARAMS}")

if command -v jq &>/dev/null; then
  echo "${RESPONSE}" | jq '.[] | {
    id: .iterationId,
    script: .scriptName,
    status: .status,
    prompt: (.prompt[:60]),
    created: .createdAt,
    duration: .runDurationMs,
    diagnosis: (.diagnosisSummary[:80] // "N/A")
  }'
else
  echo "${RESPONSE}"
fi

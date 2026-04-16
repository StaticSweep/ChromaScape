#!/usr/bin/env bash
set -euo pipefail

# Ingest a Micro6 stabilization report into ChromaScape triage/KPI pipeline.
#
# Usage:
#   ./scripts/ingest-stabilization-report.sh /path/to/stabilization-report.json
#
# Env:
#   CHROMASCAPE_URL (default: http://localhost:8090)

REPORT_FILE="${1:?Usage: ingest-stabilization-report.sh /path/to/stabilization-report.json}"
BASE_URL="${CHROMASCAPE_URL:-http://localhost:8090}"
MAX_TIME="${CHROMASCAPE_CURL_MAX_TIME:-120}"
CONNECT_TIMEOUT="${CHROMASCAPE_CURL_CONNECT_TIMEOUT:-5}"
LOCK_DIR="${TMPDIR:-/tmp}/chromascape-stabilization-ingest.lock"

if ! mkdir "${LOCK_DIR}" 2>/dev/null; then
  echo "Another ingestion run is active (lock: ${LOCK_DIR})" >&2
  exit 1
fi
trap 'rmdir "${LOCK_DIR}" 2>/dev/null || true' EXIT

if [ ! -f "$REPORT_FILE" ]; then
  echo "Report file not found: $REPORT_FILE" >&2
  exit 1
fi

echo "Ingesting report: $REPORT_FILE"
RESPONSE="$(curl -sS --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${MAX_TIME}" -X POST "$BASE_URL/api/account-tasks/stabilization/ingest-report" \
  -H "Content-Type: application/json" \
  --data-binary "@$REPORT_FILE")"

if command -v jq >/dev/null 2>&1; then
  echo "$RESPONSE" | jq .
else
  echo "$RESPONSE"
fi

echo
echo "Current KPIs:"
KPI="$(curl -sS --connect-timeout "${CONNECT_TIMEOUT}" --max-time "${MAX_TIME}" "$BASE_URL/api/account-tasks/stabilization/kpi")"
if command -v jq >/dev/null 2>&1; then
  echo "$KPI" | jq .
else
  echo "$KPI"
fi

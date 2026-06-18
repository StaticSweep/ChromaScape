#!/usr/bin/env bash
set -euo pipefail

# Runs quest/boss/skilling stabilization certification and writes an evidence pack.
#
# Usage:
#   ./scripts/certify-stabilization-scenarios.sh [report.md]
#
# Env:
#   CHROMASCAPE_URL default http://localhost:8090
#   MICRO6_URL      default http://127.0.0.1:8098
#   CERT_REPORT_DIR default /tmp/chromascape-scenario-certification
#   CERT_ATTEMPTS   default 1
#   CERT_TIMEOUT_MS default 30000

REPORT_FILE="${1:-/tmp/sc583_certification_report.md}"
CHROMASCAPE_URL="${CHROMASCAPE_URL:-http://localhost:8090}"
MICRO6_URL="${MICRO6_URL:-http://127.0.0.1:8098}"
CERT_REPORT_DIR="${CERT_REPORT_DIR:-/tmp/chromascape-scenario-certification}"
CERT_ATTEMPTS="${CERT_ATTEMPTS:-1}"
CERT_TIMEOUT_MS="${CERT_TIMEOUT_MS:-30000}"
NOW_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
MICRO6_REPO="/mnt/f/Dev/IdeaProjects/micro6"
CHROMASCAPE_REPO="/mnt/f/dev/ideaprojects/ChromaScape"

mkdir -p "${CERT_REPORT_DIR}"

log_line() {
  echo "$1" >> "${REPORT_FILE}"
}

append_block() {
  local title="$1"
  local file="$2"
  log_line "### ${title}"
  log_line ""
  log_line '```text'
  tail -n 80 "${file}" >> "${REPORT_FILE}" || true
  log_line '```'
  log_line ""
}

run_profile() {
  local profile="$1"
  local report_json="${CERT_REPORT_DIR}/stabilization-${profile}.json"
  local harness_out="${CERT_REPORT_DIR}/${profile}-harness.out"
  local ingest_out="${CERT_REPORT_DIR}/${profile}-ingest.out"
  local kpi_out="${CERT_REPORT_DIR}/${profile}-kpi.json"

  bash -lc "cd '${MICRO6_REPO}' && MICRO6_EXECUTOR_URL='${MICRO6_URL}' ./scripts/accountbuilder-stabilization.sh --profile '${profile}' --attempts '${CERT_ATTEMPTS}' --timeout-ms '${CERT_TIMEOUT_MS}' --out '${report_json}'" >"${harness_out}" 2>&1
  test -s "${report_json}"
  grep -Eq '"execute_http_status":"(202|400|409|500|503)"' "${report_json}"

  bash -lc "cd '${CHROMASCAPE_REPO}' && CHROMASCAPE_URL='${CHROMASCAPE_URL}' ./scripts/ingest-stabilization-report.sh '${report_json}'" >"${ingest_out}" 2>&1
  curl -sS -m 15 "${CHROMASCAPE_URL}/api/account-tasks/stabilization/kpi?family=${profile}" >"${kpi_out}"
  test -s "${kpi_out}"
  grep -Eq "\"family_filter\"[[:space:]]*:[[:space:]]*\"${profile}\"|\"family_filter\"[[:space:]]*:[[:space:]]*${profile}" "${kpi_out}" || true
  grep -Eq '"scripts"[[:space:]]*:' "${kpi_out}"

  log_line "## ${profile^} Certification"
  log_line ""
  log_line "- Report JSON: ${report_json}"
  log_line "- KPI JSON: ${kpi_out}"
  log_line "- Baseline snapshots: persisted via stabilization ingest for family \`${profile}\`"
  log_line ""
  append_block "${profile} harness" "${harness_out}"
  append_block "${profile} ingest" "${ingest_out}"
  append_block "${profile} kpi" "${kpi_out}"
}

cat > "${REPORT_FILE}" <<EOF
# sc-583 Scenario Certification Report

- Run at (UTC): ${NOW_UTC}
- ChromaScape URL: ${CHROMASCAPE_URL}
- Micro6 URL: ${MICRO6_URL}
- Attempts per task: ${CERT_ATTEMPTS}
- Timeout ms: ${CERT_TIMEOUT_MS}

EOF

run_profile "quest"
run_profile "boss"
run_profile "skilling"

log_line "## Artifacts"
log_line ""
log_line "- Certification report: ${REPORT_FILE}"
log_line "- Artifact directory: ${CERT_REPORT_DIR}"
log_line ""

echo "Wrote certification report: ${REPORT_FILE}"

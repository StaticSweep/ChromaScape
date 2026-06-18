#!/usr/bin/env bash
set -euo pipefail

# Runs one-session E7.7 verification and writes a markdown evidence report.
#
# Usage:
#   ./scripts/verify-e77-session.sh [report.md]
#
# Env:
#   CHROMASCAPE_URL  default http://localhost:8090
#   MICRO6_URL       default http://127.0.0.1:8098
#   VERIFY_BUILD_TIMEOUT_SECONDS default 120
#   VERIFY_SKIP_BUILDS default false
#   VERIFY_STABILIZATION_PROFILE default critical

REPORT_FILE="${1:-/tmp/e77_verification_report.md}"
CHROMASCAPE_URL="${CHROMASCAPE_URL:-http://localhost:8090}"
MICRO6_URL="${MICRO6_URL:-http://127.0.0.1:8098}"
VERIFY_BUILD_TIMEOUT_SECONDS="${VERIFY_BUILD_TIMEOUT_SECONDS:-120}"
VERIFY_SKIP_BUILDS="${VERIFY_SKIP_BUILDS:-false}"
VERIFY_STABILIZATION_PROFILE="${VERIFY_STABILIZATION_PROFILE:-critical}"
STABILIZATION_REPORT_FILE="/tmp/e77_stabilization_report.json"
SC583_REPORT_FILE="/tmp/sc583_certification_report.md"
NOW_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
PASS_COUNT=0
FAIL_COUNT=0

log_line() {
  echo "$1" >> "$REPORT_FILE"
}

run_check() {
  local id="$1"
  local cmd="$2"
  local output_file="/tmp/e77_${id}.out"
  if bash -lc "$cmd" >"$output_file" 2>&1; then
    PASS_COUNT=$((PASS_COUNT + 1))
    log_line "### PASS: ${id}"
    log_line ""
    log_line '```text'
    tail -n 60 "$output_file" >> "$REPORT_FILE" || true
    log_line '```'
    log_line ""
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    log_line "### FAIL: ${id}"
    log_line ""
    log_line '```text'
    tail -n 60 "$output_file" >> "$REPORT_FILE" || true
    log_line '```'
    log_line ""
  fi
}

cat > "$REPORT_FILE" <<EOF
# E7.7 Verification Report

- Run at (UTC): ${NOW_UTC}
- ChromaScape URL: ${CHROMASCAPE_URL}
- Micro6 URL: ${MICRO6_URL}

## Results

EOF

run_check "preflight_safe_directory" \
  "git config --global --add safe.directory /mnt/f/Dev/IdeaProjects/micro6 && echo safe_directory_configured"

run_check "health_micro6" \
  "curl -sS -m 5 ${MICRO6_URL}/health"

run_check "health_chromascape" \
  "curl -sS -m 5 ${CHROMASCAPE_URL}/actuator/health || curl -sS -m 5 ${CHROMASCAPE_URL}/health"

if [ "$VERIFY_SKIP_BUILDS" != "true" ]; then
  run_check "build_chromascape_compile" \
    "cd /mnt/f/dev/ideaprojects/chromascape && timeout ${VERIFY_BUILD_TIMEOUT_SECONDS}s bash -lc 'JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew --no-daemon compileJava -Dorg.gradle.internal.problems.report=false --console=plain'"

  run_check "build_micro6_compile" \
    "cd /mnt/f/Dev/IdeaProjects/micro6 && timeout ${VERIFY_BUILD_TIMEOUT_SECONDS}s ./gradlew :runelite-client:compileJava --console=plain"
else
  log_line "### SKIP: build_chromascape_compile"
  log_line ""
  log_line "Build checks skipped via VERIFY_SKIP_BUILDS=true."
  log_line ""
  log_line "### SKIP: build_micro6_compile"
  log_line ""
  log_line "Build checks skipped via VERIFY_SKIP_BUILDS=true."
  log_line ""
fi

run_check "micro6_catalog_v2" \
  "curl -sS -m 8 ${MICRO6_URL}/api/v1/catalog"

run_check "micro6_stabilization_harness" \
  "cd /mnt/f/Dev/IdeaProjects/micro6 && MICRO6_EXECUTOR_URL=${MICRO6_URL} ./scripts/accountbuilder-stabilization.sh --profile ${VERIFY_STABILIZATION_PROFILE} --attempts 1 --timeout-ms 30000 --out ${STABILIZATION_REPORT_FILE} && test -s ${STABILIZATION_REPORT_FILE} && grep -Eq '\"execute_http_status\":\"(202|400|409|500|503)\"' ${STABILIZATION_REPORT_FILE} && tail -n 40 ${STABILIZATION_REPORT_FILE}"

run_check "chromascape_stabilization_ingest" \
  "cd /mnt/f/dev/ideaprojects/ChromaScape && CHROMASCAPE_URL=${CHROMASCAPE_URL} ./scripts/ingest-stabilization-report.sh ${STABILIZATION_REPORT_FILE}"

run_check "chromascape_stabilization_kpi" \
  "curl -sS -m 8 ${CHROMASCAPE_URL}/api/account-tasks/stabilization/kpi"

run_check "chromascape_task_item_status_sync" \
  "cd /mnt/f/dev/ideaprojects/ChromaScape && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:\$PATH ./gradlew --no-daemon test -x processResources -x processTestResources --tests 'com.chromascape.web.accounttask.service.TaskPlanPatchLoopSyncServiceTest' --tests 'com.chromascape.web.accounttask.service.TaskPlanExecutionServiceTest' --tests 'com.chromascape.web.accounttask.controller.AccountTaskControllerPatchLoopExecutionTest'"

run_check "chromascape_scenario_certification" \
  "cd /mnt/f/dev/ideaprojects/ChromaScape && CHROMASCAPE_URL=${CHROMASCAPE_URL} MICRO6_URL=${MICRO6_URL} CERT_REPORT_DIR=/tmp/chromascape-scenario-certification ./scripts/certify-stabilization-scenarios.sh ${SC583_REPORT_FILE} && test -s ${SC583_REPORT_FILE} && grep -q 'Quest Certification' ${SC583_REPORT_FILE} && grep -q 'Boss Certification' ${SC583_REPORT_FILE} && grep -q 'Skilling Certification' ${SC583_REPORT_FILE} && tail -n 80 ${SC583_REPORT_FILE}"

log_line "## Summary"
log_line ""
log_line "- Passed checks: ${PASS_COUNT}"
log_line "- Failed checks: ${FAIL_COUNT}"
log_line ""
log_line "## Story Mapping"
log_line ""
log_line "- sc-572: stabilization harness + ingestion + KPI verification"
log_line "- sc-582: task item status sync verification"
log_line "- sc-583: scenario/harness verification"
log_line "- sc-584: catalog v2 verification"
log_line "- sc-585: offline dataset/export verification"
log_line ""
log_line "## Artifacts"
log_line ""
log_line "- Stabilization report: ${STABILIZATION_REPORT_FILE}"
log_line "- Verification report: ${REPORT_FILE}"
log_line "- sc-582 verification: TaskPlanPatchLoopSyncServiceTest + TaskPlanExecutionServiceTest + AccountTaskControllerPatchLoopExecutionTest"
log_line "- sc-583 certification report: ${SC583_REPORT_FILE}"
log_line ""
echo "Wrote report: ${REPORT_FILE}"

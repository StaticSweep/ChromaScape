package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ingests stabilization reports, creates run rows, and maintains deduped triage records. */
@Service
public class StabilizationIngestionService {
  public static final String HARNESS_MANIFEST_SCHEMA = "harness-run-manifest/v1";
  public static final String RELIABILITY_MATRIX_SCHEMA = "reliability-matrix/v1";

  private static final Set<String> MANIFEST_REQUIRED =
      Set.of(
          "schema_version",
          "run_id",
          "attempt_id",
          "task_key",
          "outcome",
          "terminal_state",
          "manifest_hash",
          "evidence",
          "evidence_complete");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final ScriptStabilizationRunRepository runRepository;
  private final ScriptFailureTriageRepository triageRepository;
  private final ScriptStabilizationSnapshotRepository snapshotRepository;

  public StabilizationIngestionService(
      ScriptStabilizationRunRepository runRepository,
      ScriptFailureTriageRepository triageRepository,
      ScriptStabilizationSnapshotRepository snapshotRepository) {
    this.runRepository = runRepository;
    this.triageRepository = triageRepository;
    this.snapshotRepository = snapshotRepository;
  }

  @Transactional
  public Map<String, Object> ingestReport(JsonNode report) {
    String reportId =
        report
            .path("report_id")
            .asText(report.path("generated_at").asText("report_" + System.currentTimeMillis()));
    JsonNode rows = report.path("rows");
    int ingested = 0;
    int triaged = 0;

    if (rows.isArray()) {
      for (JsonNode row : rows) {
        ScriptStabilizationRun run = new ScriptStabilizationRun();
        run.setReportId(reportId);
        run.setScriptKey(row.path("task_key").asText("unknown"));
        run.setOutcome(row.path("outcome").asText("UNKNOWN"));
        run.setStatus(row.path("status").asText("UNKNOWN"));
        run.setDurationMs(
            row.path("duration_ms").isNumber() ? row.path("duration_ms").asLong() : null);
        run.setFailureSignature(nullIfBlank(row.path("failure_signature").asText("")));
        run.setErrorMessage(nullIfBlank(row.path("error_message").asText("")));
        runRepository.save(run);
        ingested++;

        if (!"PASS".equalsIgnoreCase(run.getOutcome())
            && run.getFailureSignature() != null
            && !run.getFailureSignature().isBlank()) {
          upsertTriage(run, reportId);
          triaged++;
        }
      }
    }

    return Map.of("reportId", reportId, "ingestedRows", ingested, "triageUpdates", triaged);
  }

  /**
   * Ingests a versioned {@code harness-run-manifest/v1} document idempotently. Unknown schema
   * versions fail clearly; required fields, outcome/terminal consistency, the recomputed content
   * hash and evidence completeness are validated before storage; duplicates are detected by {@code
   * schema_version + run_id + attempt_id + manifest_hash}.
   */
  @Transactional
  public Map<String, Object> ingestHarnessManifest(JsonNode manifest) {
    String schemaVersion = manifest.path("schema_version").asText("");
    if (!HARNESS_MANIFEST_SCHEMA.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "unsupported harness manifest schema_version '"
              + schemaVersion
              + "'; expected "
              + HARNESS_MANIFEST_SCHEMA);
    }

    for (String field : MANIFEST_REQUIRED) {
      if (!manifest.hasNonNull(field)) {
        throw new IllegalArgumentException(
            "harness manifest missing required field '" + field + "'");
      }
    }

    String outcome = manifest.path("outcome").asText("");
    String terminalState = manifest.path("terminal_state").asText("");
    if (!outcomeConsistent(outcome, terminalState)) {
      throw new IllegalArgumentException(
          "harness manifest outcome '"
              + outcome
              + "' is inconsistent with terminal_state '"
              + terminalState
              + "'");
    }

    String declaredHash = manifest.path("manifest_hash").asText("");
    String computedHash =
        "sha256:"
            + StabilizationHashUtil.sha256(manifest, StabilizationHashUtil.MANIFEST_EXCLUSIONS);
    if (!declaredHash.equals(computedHash)) {
      throw new IllegalArgumentException(
          "harness manifest hash mismatch: declared "
              + declaredHash
              + " but recomputed "
              + computedHash);
    }

    if ("PASS".equalsIgnoreCase(outcome) && !manifest.path("evidence_complete").asBoolean(false)) {
      throw new IllegalArgumentException(
          "harness manifest outcome PASS requires evidence_complete=true");
    }

    String runId = manifest.path("run_id").asText("");
    String attemptId = manifest.path("attempt_id").asText("");
    if (runRepository.existsBySchemaVersionAndRunIdAndAttemptIdAndManifestHash(
        schemaVersion, runId, attemptId, declaredHash)) {
      return Map.of(
          "runId",
          runId,
          "attemptId",
          attemptId,
          "saved",
          false,
          "duplicate",
          true,
          "schemaVersion",
          schemaVersion);
    }

    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setReportId("harness-" + runId + "-" + attemptId);
    run.setSchemaVersion(schemaVersion);
    run.setScriptKey(manifest.path("task_key").asText("unknown"));
    run.setOutcome(outcome);
    run.setStatus(terminalState);
    run.setDurationMs(
        manifest.path("latency_ms").isNumber() ? manifest.path("latency_ms").asLong() : null);
    run.setFailureSignature(nullIfBlank(manifest.path("failure_signature").asText("")));
    run.setErrorMessage(nullIfBlank(manifest.path("primary_failure_code").asText("")));
    run.setPlanHash(nullIfBlank(manifest.path("plan_hash").asText("")));
    run.setRunId(runId);
    run.setAttemptId(attemptId);
    run.setSettingsHash(nullIfBlank(manifest.path("settings_hash").asText("")));
    run.setBuildId(nullIfBlank(manifest.path("build_id").asText("")));
    run.setCatalogHash(nullIfBlank(manifest.path("catalog_hash").asText("")));
    run.setSourceSnapshotId(nullIfBlank(manifest.path("source_snapshot_id").asText("")));
    run.setPrimaryFailureCode(nullIfBlank(manifest.path("primary_failure_code").asText("")));
    run.setEvidenceComplete(manifest.path("evidence_complete").asBoolean(false));
    run.setManifestHash(declaredHash);
    run.setDeterministicContentHash(
        nullIfBlank(manifest.path("deterministic_content_hash").asText("")));
    run.setEnvironment(nullIfBlank(manifest.path("environment").asText("")));
    run.setTier(nullIfBlank(manifest.path("tier").asText("")));
    String baseRef = "micro6-stabilization/" + runId + "/";
    run.setManifestReference(baseRef + "run-manifest.json");
    run.setTransitionsReference(baseRef + "transitions.json");
    run.setEvidenceReference(baseRef + "evidence.json");
    run.setChecksumsReference(baseRef + "checksums.json");
    run.setMatrixReference(baseRef + "reliability-matrix.json");
    runRepository.save(run);

    if (!"PASS".equalsIgnoreCase(run.getOutcome())
        && run.getPrimaryFailureCode() != null
        && !run.getPrimaryFailureCode().isBlank()
        && !"none".equalsIgnoreCase(run.getPrimaryFailureCode())) {
      upsertTriage(run, run.getReportId());
    }

    return Map.of(
        "runId",
        runId,
        "attemptId",
        attemptId,
        "saved",
        true,
        "duplicate",
        false,
        "schemaVersion",
        schemaVersion);
  }

  /**
   * Verifies a complete SC-572 report bundle before parsing or persisting any JSON. The controller
   * supplies bounded multipart bytes after authorization, and this method keeps the wire-format
   * contract independent of a particular artifact-store implementation.
   */
  @Transactional
  public Map<String, Object> ingestReportBundle(Map<String, byte[]> parts) {
    Set<String> required =
        Set.of(
            "plan", "manifest", "matrix", "transitions", "evidence", "checksums", "completeMarker");
    if (parts == null || !parts.keySet().containsAll(required)) {
      throw new IllegalArgumentException("report bundle is missing one or more required artifacts");
    }
    byte[] marker = parts.get("completeMarker");
    if (marker == null || !"COMPLETE".equals(new String(marker, StandardCharsets.UTF_8).trim())) {
      throw new IllegalArgumentException("report bundle complete.marker is invalid");
    }
    JsonNode checksums = parseBundleJson("checksums", parts.get("checksums"));
    if (!checksums.isObject()) {
      throw new IllegalArgumentException("report bundle checksums must be a JSON object");
    }
    Map<String, String> partNames =
        Map.of(
            "plan.json",
            "plan",
            "run-manifest.json",
            "manifest",
            "reliability-matrix.json",
            "matrix",
            "transitions.json",
            "transitions",
            "evidence.json",
            "evidence");
    for (Map.Entry<String, String> entry : partNames.entrySet()) {
      String declared = checksums.path(entry.getKey()).asText("");
      String actual = rawSha256(parts.get(entry.getValue()));
      if (!declared.matches("sha256:[0-9a-f]{64}") || !declared.equals(actual)) {
        throw new IllegalArgumentException("report bundle checksum mismatch for " + entry.getKey());
      }
    }

    JsonNode plan = parseBundleJson("plan", parts.get("plan"));
    JsonNode manifest = parseBundleJson("manifest", parts.get("manifest"));
    JsonNode matrix = parseBundleJson("matrix", parts.get("matrix"));
    JsonNode transitions = parseBundleJson("transitions", parts.get("transitions"));
    JsonNode evidence = parseBundleJson("evidence", parts.get("evidence"));
    if (!plan.isObject() || !transitions.isArray() || !evidence.isArray()) {
      throw new IllegalArgumentException(
          "report bundle contains invalid plan, transitions, or evidence JSON");
    }
    // Evidence is duplicated in the manifest intentionally; accepting inconsistent copies would
    // make debugger retrieval ambiguous.
    if (manifest.has("evidence") && !manifest.path("evidence").equals(evidence)) {
      throw new IllegalArgumentException(
          "report bundle evidence.json does not match manifest evidence");
    }
    Map<String, Object> manifestResult = ingestHarnessManifest(manifest);
    Map<String, Object> matrixResult = ingestReliabilityMatrix(matrix);
    String runId = manifest.path("run_id").asText();
    String attemptId = manifest.path("attempt_id").asText();
    boolean duplicate = Boolean.TRUE.equals(manifestResult.get("duplicate"));
    return Map.of(
        "run_id",
        runId,
        "attempt_ids",
        List.of(attemptId),
        "manifest_hash",
        manifest.path("manifest_hash").asText(),
        "matrix_hash",
        matrix.path("matrix_hash").asText(),
        "saved",
        !duplicate,
        "duplicate",
        duplicate,
        "artifacts_verified",
        true,
        "matrix_snapshots",
        matrixResult.get("snapshots"));
  }

  private JsonNode parseBundleJson(String name, byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("report bundle " + name + " is empty");
    }
    try {
      return MAPPER.readTree(bytes);
    } catch (Exception e) {
      throw new IllegalArgumentException("report bundle " + name + " is invalid JSON");
    }
  }

  private static String rawSha256(byte[] bytes) {
    if (bytes == null) {
      return "";
    }
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder result = new StringBuilder("sha256:");
      for (byte value : digest) {
        result.append(String.format("%02x", value));
      }
      return result.toString();
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  /**
   * Ingests a versioned {@code reliability-matrix/v1} document, persisting one snapshot per
   * comparable group idempotently by {@code matrix_hash + group_key}, and preserving the full
   * matrix document and its {@code incomparable[]} entries. Unknown schema versions fail clearly.
   */
  @Transactional
  public Map<String, Object> ingestReliabilityMatrix(JsonNode matrix) {
    String schemaVersion = matrix.path("schema_version").asText("");
    if (!RELIABILITY_MATRIX_SCHEMA.equals(schemaVersion)) {
      throw new IllegalArgumentException(
          "unsupported reliability matrix schema_version '"
              + schemaVersion
              + "'; expected "
              + RELIABILITY_MATRIX_SCHEMA);
    }
    String declaredHash = matrix.path("matrix_hash").asText("matrix_unknown");
    String computedHash =
        "sha256:" + StabilizationHashUtil.sha256(matrix, StabilizationHashUtil.MATRIX_EXCLUSIONS);
    if (!declaredHash.equals(computedHash)) {
      throw new IllegalArgumentException(
          "reliability matrix hash mismatch: declared "
              + declaredHash
              + " but recomputed "
              + computedHash);
    }
    String matrixJson;
    try {
      matrixJson = MAPPER.writeValueAsString(matrix);
    } catch (Exception e) {
      throw new IllegalArgumentException("reliability matrix is not serializable", e);
    }
    String incomparableJson;
    try {
      incomparableJson = MAPPER.writeValueAsString(matrix.path("incomparable"));
    } catch (Exception e) {
      incomparableJson = "[]";
    }

    JsonNode groups = matrix.path("groups");
    int stored = 0;
    int skipped = 0;
    if (groups.isArray()) {
      for (JsonNode group : groups) {
        String groupKey = group.path("group_key").asText(null);
        if (groupKey == null
            || snapshotRepository.existsByMatrixHashAndGroupKey(declaredHash, groupKey)) {
          skipped++;
          continue;
        }
        ScriptStabilizationSnapshot snapshot = new ScriptStabilizationSnapshot();
        snapshot.setReportId("matrix-" + declaredHash);
        snapshot.setScriptKey(group.path("task_key").asText("unknown"));
        snapshot.setFamily("harness");
        snapshot.setMatrixHash(declaredHash);
        snapshot.setGroupKey(groupKey);
        snapshot.setPlanHash(nullIfBlank(group.path("plan_hash").asText("")));
        snapshot.setBuildId(nullIfBlank(group.path("build_id").asText("")));
        snapshot.setCatalogHash(nullIfBlank(group.path("catalog_hash").asText("")));
        snapshot.setArtifactDigest(nullIfBlank(group.path("artifact_digest").asText("")));
        snapshot.setExecutorInstanceId(nullIfBlank(group.path("executor_instance_id").asText("")));
        snapshot.setSourceSnapshotId(nullIfBlank(group.path("source_snapshot_id").asText("")));
        snapshot.setRetryCount(group.path("retry_count").asInt(0));
        snapshot.setWindow24hCount(group.path("total_runs").asInt(0));
        snapshot.setWindow7dCount(group.path("total_runs").asInt(0));
        snapshot.setPassCount24h(group.path("passes").asInt(0));
        snapshot.setFailCount24h(group.path("failures").asInt(0));
        snapshot.setTimeoutCount24h(group.path("timeouts").asInt(0));
        snapshot.setPassRate24h(group.path("pass_rate").asDouble(0));
        snapshot.setPassRate7d(group.path("pass_rate").asDouble(0));
        snapshot.setTimeoutRate24h(group.path("timeout_rate").asDouble(0));
        snapshot.setRegression(
            group.has("regression") ? group.path("regression").asBoolean() : false);
        snapshot.setBaselineReportId(nullIfBlank(group.path("compared_to").asText("")));
        snapshot.setBaselinePassRate24h(0.0);
        snapshot.setFailureClusterCount(group.path("failure_signatures").size());
        snapshot.setTopFailureSignature(topFailureSignature(group.path("failure_signatures")));
        snapshot.setFirstAttemptPassRate(group.path("first_attempt_pass_rate").asDouble(0));
        snapshot.setEventualPassRate(group.path("eventual_pass_rate").asDouble(0));
        snapshot.setEvidenceCompleteRate(group.path("evidence_complete_rate").asDouble(0));
        snapshot.setCleanupSuccessRate(group.path("cleanup_success_rate").asDouble(0));
        snapshot.setComparisonStatus(group.path("comparison_status").asText("BASELINE"));
        snapshot.setMatrixJson(matrixJson);
        snapshot.setIncomparableJson(incomparableJson);
        snapshotRepository.save(snapshot);
        stored++;
      }
    }
    return Map.of(
        "matrixHash",
        declaredHash,
        "snapshots",
        stored,
        "skipped",
        skipped,
        "schemaVersion",
        schemaVersion);
  }

  private static String topFailureSignature(JsonNode signatures) {
    String top = null;
    int topCount = -1;
    if (signatures != null && signatures.isObject()) {
      var it = signatures.fields();
      while (it.hasNext()) {
        var entry = it.next();
        int count = entry.getValue().asInt();
        if (count > topCount) {
          topCount = count;
          top = entry.getKey();
        }
      }
    }
    return top;
  }

  public Optional<ScriptStabilizationRun> getRunByRunId(String runId) {
    if (runId == null || runId.isBlank()) {
      return Optional.empty();
    }
    return runRepository.findFirstByRunIdOrderByExecutedAtDesc(runId);
  }

  public Optional<ScriptStabilizationRun> getRunByRunIdAndAttemptId(
      String runId, String attemptId) {
    if (runId == null || runId.isBlank() || attemptId == null || attemptId.isBlank()) {
      return Optional.empty();
    }
    return runRepository.findFirstByRunIdAndAttemptIdOrderByExecutedAtDesc(runId, attemptId);
  }

  public List<ScriptStabilizationRun> getRunAttempts(String runId) {
    if (runId == null || runId.isBlank()) {
      return List.of();
    }
    return runRepository.findByRunIdOrderByAttemptIdAsc(runId);
  }

  public Optional<ScriptStabilizationSnapshot> getMatrixByMatrixHash(String matrixHash) {
    if (matrixHash == null || matrixHash.isBlank()) {
      return Optional.empty();
    }
    return snapshotRepository.findFirstByMatrixHashOrderByCapturedAtDesc(matrixHash);
  }

  /**
   * Reconstructs the full reliability matrix document (groups + incomparable) for a matrix hash.
   */
  public Optional<JsonNode> getMatrixDocumentByHash(String matrixHash) {
    List<ScriptStabilizationSnapshot> snapshots =
        snapshotRepository.findByMatrixHashOrderByGroupKeyAsc(matrixHash);
    if (snapshots.isEmpty()) {
      return Optional.empty();
    }
    try {
      JsonNode matrix = MAPPER.readTree(snapshots.get(0).getMatrixJson());
      return Optional.of(matrix);
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public Map<String, Object> computeKpis() {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime start24h = now.minusHours(24);
    LocalDateTime start7d = now.minusDays(7);
    List<ScriptStabilizationRun> rows24 = runRepository.findByExecutedAtBetween(start24h, now);
    List<ScriptStabilizationRun> rows7d = runRepository.findByExecutedAtBetween(start7d, now);

    Map<String, Object> byScript = new HashMap<>();
    Map<String, List<ScriptStabilizationRun>> grouped24 = groupByScript(rows24);
    Map<String, List<ScriptStabilizationRun>> grouped7d = groupByScript(rows7d);
    for (Map.Entry<String, List<ScriptStabilizationRun>> entry : grouped24.entrySet()) {
      String script = entry.getKey();
      double pass24 = passRate(entry.getValue());
      double pass7 = passRate(grouped7d.getOrDefault(script, List.of()));
      long timeouts =
          entry.getValue().stream().filter(r -> "TIMEOUT".equalsIgnoreCase(r.getOutcome())).count();
      Map<String, Object> data = new HashMap<>();
      data.put("pass_rate_24h", pass24);
      data.put("pass_rate_7d", pass7);
      data.put(
          "timeout_rate_24h",
          entry.getValue().isEmpty() ? 0 : ((double) timeouts / entry.getValue().size()));
      data.put("regression", pass24 + 0.10 < pass7);
      byScript.put(script, data);
    }

    List<ScriptFailureTriage> open = triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN");
    return Map.of(
        "window_24h_count",
        rows24.size(),
        "window_7d_count",
        rows7d.size(),
        "open_triage_count",
        open.size(),
        "scripts",
        byScript);
  }

  public List<ScriptFailureTriage> listOpenTriage() {
    return triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN");
  }

  @Transactional
  public ScriptFailureTriage resolveTriage(Long id) {
    ScriptFailureTriage triage =
        triageRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Triage item not found: " + id));
    triage.setStatus("RESOLVED");
    triage.setLastSeenAt(LocalDateTime.now());
    return triageRepository.save(triage);
  }

  private static boolean outcomeConsistent(String outcome, String terminalState) {
    switch (outcome) {
      case "PASS":
        return "PASSED".equals(terminalState);
      case "FAIL":
        return "FAILED".equals(terminalState) || "INVALID".equals(terminalState);
      case "TIMEOUT":
        return "TIMED_OUT".equals(terminalState);
      case "CANCELLED":
        return "CANCELLED".equals(terminalState);
      default:
        return false;
    }
  }

  private void upsertTriage(ScriptStabilizationRun run, String reportId) {
    String triageKey = run.getScriptKey() + "::" + run.getFailureSignature();
    ScriptFailureTriage triage =
        triageRepository
            .findByTriageKey(triageKey)
            .orElseGet(
                () -> {
                  ScriptFailureTriage created = new ScriptFailureTriage();
                  created.setTriageKey(triageKey);
                  created.setScriptKey(run.getScriptKey());
                  created.setFailureSignature(run.getFailureSignature());
                  return created;
                });
    triage.setOccurrences((triage.getOccurrences() == null ? 0 : triage.getOccurrences()) + 1);
    triage.setLastSeenAt(LocalDateTime.now());
    triage.setLastReportId(reportId);
    triage.setLastErrorMessage(run.getErrorMessage());
    triageRepository.save(triage);
  }

  private Map<String, List<ScriptStabilizationRun>> groupByScript(
      List<ScriptStabilizationRun> rows) {
    Map<String, List<ScriptStabilizationRun>> grouped = new HashMap<>();
    for (ScriptStabilizationRun row : rows) {
      grouped.computeIfAbsent(row.getScriptKey(), k -> new ArrayList<>()).add(row);
    }
    return grouped;
  }

  private double passRate(List<ScriptStabilizationRun> rows) {
    if (rows == null || rows.isEmpty()) {
      return 0.0;
    }
    long pass = rows.stream().filter(r -> "PASS".equalsIgnoreCase(r.getOutcome())).count();
    return (double) pass / rows.size();
  }

  private String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}

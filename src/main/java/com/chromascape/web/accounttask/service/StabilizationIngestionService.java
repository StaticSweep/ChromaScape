package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ingests stabilization reports, creates run rows, and maintains deduped triage records. */
@Service
public class StabilizationIngestionService {
  private static final Set<String> VALID_OUTCOMES =
      Set.of("PASS", "FAIL", "TIMEOUT", "ERROR", "SKIPPED");
  private static final Set<String> VALID_FAMILIES = Set.of("quest", "boss", "skilling", "utility");
  private static final List<String> UNRESOLVED_TRIAGE_STATUSES =
      List.of("OPEN", "IN_PROGRESS", "QUEUED_FOR_BACKLOG");
  private static final int DEFAULT_HISTORY_LIMIT = 10;

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
    validateReport(report);

    String reportId = nullIfBlank(report.path("report_id").asText(null));
    if (reportId == null) {
      reportId = "report_" + System.currentTimeMillis();
    }
    List<ScriptStabilizationRun> existingRows = runRepository.findByReportIdOrderByExecutedAtAsc(reportId);
    if (!existingRows.isEmpty()) {
      return Map.of(
          "reportId", reportId,
          "ingestedRows", existingRows.size(),
          "triageUpdates", 0,
          "duplicateReport", true);
    }

    JsonNode rows = report.path("rows");
    LocalDateTime reportGeneratedAt = parseTimestamp(nullIfBlank(report.path("generated_at").asText(null)));
    int ingested = 0;
    int triaged = 0;

    for (JsonNode row : rows) {
      ScriptStabilizationRun run = new ScriptStabilizationRun();
      run.setReportId(reportId);
      run.setScriptKey(row.path("task_key").asText("").trim());
      run.setFamily(normalizeFamily(row.path("family").asText(null)));
      run.setOutcome(row.path("outcome").asText("").trim().toUpperCase(Locale.ROOT));
      run.setStatus(row.path("status").asText("").trim());
      run.setDurationMs(row.path("duration_ms").isNumber() ? row.path("duration_ms").asLong() : null);
      run.setFailureSignature(nullIfBlank(row.path("failure_signature").asText(null)));
      run.setErrorMessage(nullIfBlank(row.path("error_message").asText(null)));
      run.setExecutedAt(resolveExecutedAt(row, reportGeneratedAt));
      runRepository.save(run);
      ingested++;

      if (!"PASS".equalsIgnoreCase(run.getOutcome())
          && run.getFailureSignature() != null
          && !run.getFailureSignature().isBlank()) {
        upsertTriage(run, reportId);
        triaged++;
      }
    }

    persistSnapshots(reportId);
    return Map.of("reportId", reportId, "ingestedRows", ingested, "triageUpdates", triaged);
  }

  public Map<String, Object> computeKpis() {
    return computeKpis(null, DEFAULT_HISTORY_LIMIT);
  }

  public Map<String, Object> computeKpis(String familyFilter, int historyLimit) {
    String normalizedFamily = normalizeFamily(familyFilter);
    int safeHistoryLimit = Math.max(1, historyLimit);
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime start24h = now.minusHours(24);
    LocalDateTime start7d = now.minusDays(7);

    List<ScriptStabilizationRun> rows24 =
        filterByFamily(runRepository.findByExecutedAtBetween(start24h, now), normalizedFamily);
    List<ScriptStabilizationRun> rows7d =
        filterByFamily(runRepository.findByExecutedAtBetween(start7d, now), normalizedFamily);

    List<Map<String, Object>> scripts = buildScriptMetrics(rows24, rows7d);
    List<Map<String, Object>> families = buildFamilyMetrics(rows24, rows7d, scripts);
    List<Map<String, Object>> history = buildHistory(normalizedFamily, safeHistoryLimit);
    List<Map<String, Object>> regressions =
        scripts.stream().filter(s -> Boolean.TRUE.equals(s.get("regression"))).toList();

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("family_filter", normalizedFamily);
    response.put("window_24h_count", rows24.size());
    response.put("window_7d_count", rows7d.size());
    response.put("open_triage_count", listOpenTriage(normalizedFamily).size());
    response.put("scripts", scripts);
    response.put("families", families);
    response.put("history", history);
    response.put("regressions", regressions);
    return response;
  }

  public List<ScriptFailureTriage> listOpenTriage() {
    return listTriage(null, null, null);
  }

  public List<ScriptFailureTriage> listOpenTriage(String familyFilter) {
    return listTriage(familyFilter, null, null);
  }

  public List<ScriptFailureTriage> listTriage(
      String familyFilter, String statusFilter, Boolean backlogReadyOnly) {
    String family = normalizeFamily(familyFilter);
    String status = normalizeStatus(statusFilter);
    List<ScriptFailureTriage> rows =
        status == null
            ? fetchUnresolvedTriage(family)
            : fetchByStatus(family, status);
    return rows.stream()
        .filter(
            triage ->
                backlogReadyOnly == null || backlogReadyOnly.equals(Boolean.TRUE.equals(triage.getBacklogReady())))
        .sorted(
            Comparator.comparing(
                    (ScriptFailureTriage triage) -> defaultInt(triage.getBacklogPriority()))
                .reversed()
                .thenComparing(ScriptFailureTriage::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder())))
        .toList();
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

  @Transactional
  public ScriptFailureTriage queueForBacklog(Long id) {
    ScriptFailureTriage triage =
        triageRepository
            .findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Triage item not found: " + id));
    triage.setBacklogReady(true);
    triage.setBacklogPriority(Math.max(defaultInt(triage.getBacklogPriority()), 1));
    if (!"RESOLVED".equalsIgnoreCase(triage.getStatus())) {
      triage.setStatus("QUEUED_FOR_BACKLOG");
    }
    triage.setLastSeenAt(LocalDateTime.now());
    return triageRepository.save(triage);
  }

  private void persistSnapshots(String reportId) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime start24h = now.minusHours(24);
    LocalDateTime start7d = now.minusDays(7);
    List<ScriptStabilizationRun> rows24 = runRepository.findByExecutedAtBetween(start24h, now);
    List<ScriptStabilizationRun> rows7d = runRepository.findByExecutedAtBetween(start7d, now);
    Map<String, List<ScriptStabilizationRun>> grouped24 = groupByScriptFamily(rows24);
    Map<String, List<ScriptStabilizationRun>> grouped7d = groupByScriptFamily(rows7d);

    for (Map.Entry<String, List<ScriptStabilizationRun>> entry : grouped24.entrySet()) {
      ScriptFamilyKey key = ScriptFamilyKey.from(entry.getKey());
      List<ScriptStabilizationRun> currentRows = entry.getValue();
      List<ScriptStabilizationRun> sevenDayRows = grouped7d.getOrDefault(entry.getKey(), List.of());
      Map<String, Long> failureClusters = buildFailureClusters(currentRows);
      double passRate24h = passRate(currentRows);
      double passRate7d = passRate(sevenDayRows);
      double timeoutRate24h = timeoutRate(currentRows);
      ScriptStabilizationSnapshot baseline =
          snapshotRepository
              .findFirstByScriptKeyAndFamilyOrderByCapturedAtDesc(key.scriptKey(), key.family())
              .orElse(null);

      ScriptStabilizationSnapshot snapshot = new ScriptStabilizationSnapshot();
      snapshot.setReportId(reportId);
      snapshot.setScriptKey(key.scriptKey());
      snapshot.setFamily(key.family());
      snapshot.setWindow24hCount(currentRows.size());
      snapshot.setWindow7dCount(sevenDayRows.size());
      snapshot.setPassCount24h(countOutcome(currentRows, "PASS"));
      snapshot.setFailCount24h(countOutcome(currentRows, "FAIL"));
      snapshot.setTimeoutCount24h(countOutcome(currentRows, "TIMEOUT"));
      snapshot.setPassRate24h(passRate24h);
      snapshot.setPassRate7d(passRate7d);
      snapshot.setTimeoutRate24h(timeoutRate24h);
      snapshot.setRegression(baseline != null && passRate24h + 0.10 < baseline.getPassRate24h());
      snapshot.setBaselineReportId(baseline != null ? baseline.getReportId() : null);
      snapshot.setBaselinePassRate24h(baseline != null ? baseline.getPassRate24h() : passRate24h);
      snapshot.setFailureClusterCount(failureClusters.size());
      snapshot.setTopFailureSignature(topFailureSignature(failureClusters));
      snapshot.setCapturedAt(now);
      snapshotRepository.save(snapshot);
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
                  created.setFamily(run.getFamily());
                  created.setFailureSignature(run.getFailureSignature());
                  return created;
                });
    triage.setScriptKey(run.getScriptKey());
    triage.setFamily(run.getFamily());
    triage.setOccurrences((triage.getOccurrences() == null ? 0 : triage.getOccurrences()) + 1);
    if ("RESOLVED".equalsIgnoreCase(triage.getStatus())) {
      triage.setStatus("OPEN");
    }
    if (triage.getFirstSeenAt() == null) {
      triage.setFirstSeenAt(run.getExecutedAt());
    }
    triage.setLastExecutedAt(run.getExecutedAt());
    triage.setLastSeenAt(run.getExecutedAt());
    triage.setLastReportId(reportId);
    triage.setLastErrorMessage(run.getErrorMessage());
    triage.setLastOutcome(run.getOutcome());
    triage.setLastStatus(run.getStatus());
    triage.setLastDurationMs(run.getDurationMs());
    triage.setBacklogPriority(computeBacklogPriority(triage, run));
    triage.setBacklogReady(shouldQueueForBacklog(triage, run));
    triageRepository.save(triage);
  }

  private List<Map<String, Object>> buildScriptMetrics(
      List<ScriptStabilizationRun> rows24, List<ScriptStabilizationRun> rows7d) {
    Map<String, List<ScriptStabilizationRun>> grouped24 = groupByScriptFamily(rows24);
    Map<String, List<ScriptStabilizationRun>> grouped7d = groupByScriptFamily(rows7d);
    List<Map<String, Object>> scripts = new ArrayList<>();

    for (Map.Entry<String, List<ScriptStabilizationRun>> entry : grouped24.entrySet()) {
      ScriptFamilyKey key = ScriptFamilyKey.from(entry.getKey());
      List<ScriptStabilizationRun> currentRows = entry.getValue();
      List<ScriptStabilizationRun> sevenDayRows = grouped7d.getOrDefault(entry.getKey(), List.of());
      Map<String, Long> failureClusters = buildFailureClusters(currentRows);
      ScriptStabilizationSnapshot latestSnapshot =
          snapshotRepository
              .findFirstByScriptKeyAndFamilyOrderByCapturedAtDesc(key.scriptKey(), key.family())
              .orElse(null);
      double pass24 = passRate(currentRows);
      double pass7 = passRate(sevenDayRows);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("script_key", key.scriptKey());
      row.put("family", key.family());
      row.put("window_24h_count", currentRows.size());
      row.put("window_7d_count", sevenDayRows.size());
      row.put("pass_rate_24h", pass24);
      row.put("pass_rate_7d", pass7);
      row.put("timeout_rate_24h", timeoutRate(currentRows));
      row.put("failure_count_24h", countNonPass(currentRows));
      row.put("failure_clusters", buildFailureClusterList(failureClusters));
      row.put("failure_cluster_count", failureClusters.size());
      row.put("top_failure_signature", topFailureSignature(failureClusters));
      row.put(
          "baseline_report_id",
          latestSnapshot != null ? latestSnapshot.getBaselineReportId() : null);
      row.put(
          "baseline_pass_rate_24h",
          latestSnapshot != null ? latestSnapshot.getBaselinePassRate24h() : pass24);
      row.put(
          "regression",
          latestSnapshot != null
              ? Boolean.TRUE.equals(latestSnapshot.getRegression())
                  || pass24 + 0.10 < latestSnapshot.getBaselinePassRate24h()
              : false);
      scripts.add(row);
    }

    scripts.sort(Comparator.comparing(v -> (String) v.get("script_key")));
    return scripts;
  }

  private List<Map<String, Object>> buildFamilyMetrics(
      List<ScriptStabilizationRun> rows24,
      List<ScriptStabilizationRun> rows7d,
      List<Map<String, Object>> scripts) {
    Map<String, Map<String, Object>> byFamily = new LinkedHashMap<>();
    for (ScriptStabilizationRun row : rows24) {
      String family = normalizeFamily(row.getFamily());
      if (family == null) {
        continue;
      }
      Map<String, Object> familyData =
          byFamily.computeIfAbsent(family, ignored -> new LinkedHashMap<>());
      familyData.put("family", family);
    }
    for (Map.Entry<String, Map<String, Object>> entry : byFamily.entrySet()) {
      String family = entry.getKey();
      Map<String, Object> familyData = entry.getValue();
      List<ScriptStabilizationRun> familyRows24 = filterByFamily(rows24, family);
      List<ScriptStabilizationRun> familyRows7d = filterByFamily(rows7d, family);
      long familyRegressions =
          scripts.stream()
              .filter(script -> family.equals(script.get("family")))
              .filter(script -> Boolean.TRUE.equals(script.get("regression")))
              .count();
      familyData.put("window_24h_count", familyRows24.size());
      familyData.put("window_7d_count", familyRows7d.size());
      familyData.put("pass_rate_24h", passRate(familyRows24));
      familyData.put("timeout_rate_24h", timeoutRate(familyRows24));
      familyData.put("regressions", familyRegressions);
    }
    return new ArrayList<>(byFamily.values());
  }

  private List<Map<String, Object>> buildHistory(String familyFilter, int historyLimit) {
    List<ScriptStabilizationSnapshot> snapshots =
        familyFilter == null
            ? snapshotRepository.findTop20ByOrderByCapturedAtDesc()
            : snapshotRepository.findTop20ByFamilyOrderByCapturedAtDesc(familyFilter);
    return snapshots.stream().limit(historyLimit).map(this::snapshotToMap).toList();
  }

  private Map<String, Object> snapshotToMap(ScriptStabilizationSnapshot snapshot) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("reportId", snapshot.getReportId());
    row.put("scriptKey", snapshot.getScriptKey());
    row.put("family", snapshot.getFamily());
    row.put("window24hCount", snapshot.getWindow24hCount());
    row.put("window7dCount", snapshot.getWindow7dCount());
    row.put("passRate24h", snapshot.getPassRate24h());
    row.put("passRate7d", snapshot.getPassRate7d());
    row.put("timeoutRate24h", snapshot.getTimeoutRate24h());
    row.put("regression", snapshot.getRegression());
    row.put("baselineReportId", snapshot.getBaselineReportId());
    row.put("baselinePassRate24h", snapshot.getBaselinePassRate24h());
    row.put("failureClusterCount", snapshot.getFailureClusterCount());
    row.put("topFailureSignature", snapshot.getTopFailureSignature());
    row.put("capturedAt", snapshot.getCapturedAt());
    return row;
  }

  private Map<String, List<ScriptStabilizationRun>> groupByScriptFamily(
      List<ScriptStabilizationRun> rows) {
    Map<String, List<ScriptStabilizationRun>> grouped = new LinkedHashMap<>();
    for (ScriptStabilizationRun row : rows) {
      String family = normalizeFamily(row.getFamily());
      if (family == null || row.getScriptKey() == null || row.getScriptKey().isBlank()) {
        continue;
      }
      grouped.computeIfAbsent(compositeKey(row.getScriptKey(), family), key -> new ArrayList<>())
          .add(row);
    }
    return grouped;
  }

  private List<ScriptStabilizationRun> filterByFamily(
      List<ScriptStabilizationRun> rows, String familyFilter) {
    if (familyFilter == null) {
      return rows;
    }
    return rows.stream()
        .filter(row -> familyFilter.equals(normalizeFamily(row.getFamily())))
        .toList();
  }

  private Map<String, Long> buildFailureClusters(List<ScriptStabilizationRun> rows) {
    return rows.stream()
        .filter(row -> !"PASS".equalsIgnoreCase(row.getOutcome()))
        .map(ScriptStabilizationRun::getFailureSignature)
        .map(this::normalizeClusterSignature)
        .filter(signature -> signature != null && !signature.isBlank())
        .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
  }

  private List<Map<String, Object>> buildFailureClusterList(Map<String, Long> failureClusters) {
    return failureClusters.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .map(
            entry -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("signature", entry.getKey());
              row.put("count", entry.getValue());
              return row;
            })
        .toList();
  }

  private String normalizeClusterSignature(String signature) {
    return nullIfBlank(signature);
  }

  private int computeBacklogPriority(ScriptFailureTriage triage, ScriptStabilizationRun run) {
    int priority = Math.max(1, defaultInt(triage.getOccurrences()));
    String outcome = normalizeStatus(run.getOutcome());
    if ("TIMEOUT".equals(outcome)) {
      priority += 2;
    } else if ("ERROR".equals(outcome)) {
      priority += 3;
    } else if ("FAIL".equals(outcome)) {
      priority += 1;
    }
    return priority;
  }

  private boolean shouldQueueForBacklog(ScriptFailureTriage triage, ScriptStabilizationRun run) {
    String outcome = normalizeStatus(run.getOutcome());
    return defaultInt(triage.getOccurrences()) >= 2
        || "TIMEOUT".equals(outcome)
        || "ERROR".equals(outcome);
  }

  private String topFailureSignature(Map<String, Long> failureClusters) {
    return failureClusters.entrySet().stream()
        .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry::getKey))
        .map(Map.Entry::getKey)
        .orElse(null);
  }

  private double passRate(List<ScriptStabilizationRun> rows) {
    if (rows == null || rows.isEmpty()) {
      return 0.0d;
    }
    return (double) countOutcome(rows, "PASS") / rows.size();
  }

  private double timeoutRate(List<ScriptStabilizationRun> rows) {
    if (rows == null || rows.isEmpty()) {
      return 0.0d;
    }
    return (double) countOutcome(rows, "TIMEOUT") / rows.size();
  }

  private int countOutcome(List<ScriptStabilizationRun> rows, String outcome) {
    return (int) rows.stream().filter(r -> outcome.equalsIgnoreCase(r.getOutcome())).count();
  }

  private int countNonPass(List<ScriptStabilizationRun> rows) {
    return (int) rows.stream().filter(r -> !"PASS".equalsIgnoreCase(r.getOutcome())).count();
  }

  private String compositeKey(String scriptKey, String family) {
    return scriptKey + "::" + family;
  }

  private String normalizeFamily(String family) {
    if (family == null) {
      return null;
    }
    String normalized = family.trim().toLowerCase(Locale.ROOT);
    return normalized.isBlank() ? null : normalized;
  }

  private String normalizeStatus(String status) {
    if (status == null) {
      return null;
    }
    String normalized = status.trim().toUpperCase(Locale.ROOT);
    return normalized.isBlank() ? null : normalized;
  }

  private String nullIfBlank(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private int defaultInt(Integer value) {
    return value == null ? 0 : value;
  }

  private List<ScriptFailureTriage> fetchUnresolvedTriage(String family) {
    if (family == null) {
      return triageRepository.findByStatusInOrderByBacklogPriorityDescLastSeenAtDesc(
          UNRESOLVED_TRIAGE_STATUSES);
    }
    return triageRepository.findByStatusInAndFamilyOrderByBacklogPriorityDescLastSeenAtDesc(
        UNRESOLVED_TRIAGE_STATUSES, family);
  }

  private List<ScriptFailureTriage> fetchByStatus(String family, String status) {
    if (family == null) {
      return triageRepository.findByStatusOrderByLastSeenAtDesc(status);
    }
    return triageRepository.findByStatusAndFamilyOrderByLastSeenAtDesc(status, family);
  }

  private LocalDateTime resolveExecutedAt(JsonNode row, LocalDateTime reportGeneratedAt) {
    LocalDateTime endedAt = parseTimestamp(nullIfBlank(row.path("ended_at").asText(null)));
    if (endedAt != null) {
      return endedAt;
    }
    LocalDateTime startedAt = parseTimestamp(nullIfBlank(row.path("started_at").asText(null)));
    if (startedAt != null) {
      return startedAt;
    }
    if (reportGeneratedAt != null) {
      return reportGeneratedAt;
    }
    return LocalDateTime.now();
  }

  private LocalDateTime parseTimestamp(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return LocalDateTime.ofInstant(Instant.parse(raw), ZoneOffset.UTC);
    } catch (DateTimeParseException ignored) {
      try {
        return LocalDateTime.parse(raw);
      } catch (DateTimeParseException ex) {
        throw new IllegalArgumentException("invalid timestamp: " + raw);
      }
    }
  }

  private void validateReport(JsonNode report) {
    if (report == null || report.isNull() || report.isMissingNode()) {
      throw new IllegalArgumentException("stabilization report body is required");
    }

    JsonNode rows = report.path("rows");
    if (!rows.isArray()) {
      throw new IllegalArgumentException("stabilization report must contain a rows array");
    }

    if (rows.size() == 0) {
      throw new IllegalArgumentException("stabilization report must contain at least one row");
    }

    for (int i = 0; i < rows.size(); i++) {
      JsonNode row = rows.get(i);
      String taskKey = row.path("task_key").asText("").trim();
      if (taskKey.isBlank()) {
        throw new IllegalArgumentException("rows[" + i + "].task_key is required");
      }

      String family = normalizeFamily(row.path("family").asText(null));
      if (family == null) {
        throw new IllegalArgumentException("rows[" + i + "].family is required");
      }
      if (!VALID_FAMILIES.contains(family)) {
        throw new IllegalArgumentException(
            "rows[" + i + "].family must be one of " + VALID_FAMILIES);
      }

      String outcome = row.path("outcome").asText("").trim().toUpperCase(Locale.ROOT);
      if (outcome.isBlank()) {
        throw new IllegalArgumentException("rows[" + i + "].outcome is required");
      }
      if (!VALID_OUTCOMES.contains(outcome)) {
        throw new IllegalArgumentException(
            "rows[" + i + "].outcome must be one of " + VALID_OUTCOMES);
      }

      String status = row.path("status").asText("").trim();
      if (status.isBlank()) {
        throw new IllegalArgumentException("rows[" + i + "].status is required");
      }

      JsonNode durationNode = row.path("duration_ms");
      if (!durationNode.isMissingNode() && !durationNode.isNull() && !durationNode.isNumber()) {
        throw new IllegalArgumentException("rows[" + i + "].duration_ms must be numeric when set");
      }

      if (!row.path("started_at").isMissingNode()) {
        parseTimestamp(nullIfBlank(row.path("started_at").asText(null)));
      }
      if (!row.path("ended_at").isMissingNode()) {
        parseTimestamp(nullIfBlank(row.path("ended_at").asText(null)));
      }
    }
  }

  private record ScriptFamilyKey(String scriptKey, String family) {
    static ScriptFamilyKey from(String composite) {
      int idx = composite.indexOf("::");
      if (idx <= 0 || idx + 2 >= composite.length()) {
        return new ScriptFamilyKey(composite, null);
      }
      return new ScriptFamilyKey(composite.substring(0, idx), composite.substring(idx + 2));
    }
  }
}

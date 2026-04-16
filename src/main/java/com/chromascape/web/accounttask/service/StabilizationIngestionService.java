package com.chromascape.web.accounttask.service;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ingests stabilization reports, creates run rows, and maintains deduped triage records. */
@Service
public class StabilizationIngestionService {
  private final ScriptStabilizationRunRepository runRepository;
  private final ScriptFailureTriageRepository triageRepository;

  public StabilizationIngestionService(
      ScriptStabilizationRunRepository runRepository, ScriptFailureTriageRepository triageRepository) {
    this.runRepository = runRepository;
    this.triageRepository = triageRepository;
  }

  @Transactional
  public Map<String, Object> ingestReport(JsonNode report) {
    String reportId =
        report.path("report_id").asText(
            report.path("generated_at").asText("report_" + System.currentTimeMillis()));
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
        run.setDurationMs(row.path("duration_ms").isNumber() ? row.path("duration_ms").asLong() : null);
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
      data.put("timeout_rate_24h", entry.getValue().isEmpty() ? 0 : ((double) timeouts / entry.getValue().size()));
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
        triageRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Triage item not found: " + id));
    triage.setStatus("RESOLVED");
    triage.setLastSeenAt(LocalDateTime.now());
    return triageRepository.save(triage);
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

  private Map<String, List<ScriptStabilizationRun>> groupByScript(List<ScriptStabilizationRun> rows) {
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

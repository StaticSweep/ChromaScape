package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StabilizationIngestionServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private ScriptStabilizationRunRepository runRepository;
  @Mock private ScriptFailureTriageRepository triageRepository;
  @Mock private ScriptStabilizationSnapshotRepository snapshotRepository;

  private StabilizationIngestionService service;

  @BeforeEach
  void setUp() {
    service = new StabilizationIngestionService(runRepository, triageRepository, snapshotRepository);
  }

  @Test
  @DisplayName("ingestReport stores runs and triage updates from a valid report")
  void ingestReport_storesRunsAndTriageUpdates_fromValidReport() throws Exception {
    List<ScriptStabilizationRun> savedRuns = new ArrayList<>();
    JsonNode report =
        objectMapper.readTree(
            """
            {
              "report_id": "report_123",
              "generated_at": "2026-04-15T10:00:00Z",
              "rows": [
                {
                  "task_key": "COOKS_ASSISTANT",
                  "family": "quest",
                  "outcome": "PASS",
                  "status": "COMPLETED",
                  "duration_ms": 1234,
                  "ended_at": "2026-04-15T10:01:02Z"
                },
                {
                  "task_key": "BARROWS1",
                  "family": "boss",
                  "outcome": "FAIL",
                  "status": "FAILED",
                  "duration_ms": 2345,
                  "failure_signature": "barrows1:missing_prayer",
                  "error_message": "missing prayer potions",
                  "started_at": "2026-04-15T10:03:04Z"
                }
              ]
            }
            """);

    when(runRepository.findByReportIdOrderByExecutedAtAsc("report_123")).thenReturn(List.of());
    when(triageRepository.findByTriageKey("BARROWS1::barrows1:missing_prayer"))
        .thenReturn(Optional.empty());
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runRepository.save(any(ScriptStabilizationRun.class)))
        .thenAnswer(
            invocation -> {
              ScriptStabilizationRun run = invocation.getArgument(0);
              savedRuns.add(run);
              return run;
            });
    when(runRepository.findByExecutedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenAnswer(invocation -> new ArrayList<>(savedRuns));
    when(snapshotRepository.findFirstByScriptKeyAndFamilyOrderByCapturedAtDesc(
            eq("COOKS_ASSISTANT"), eq("quest")))
        .thenReturn(Optional.empty());
    when(snapshotRepository.findFirstByScriptKeyAndFamilyOrderByCapturedAtDesc(
            eq("BARROWS1"), eq("boss")))
        .thenReturn(Optional.empty());
    when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    Map<String, Object> result = service.ingestReport(report);

    assertThat(result).containsEntry("reportId", "report_123");
    assertThat(result).containsEntry("ingestedRows", 2);
    assertThat(result).containsEntry("triageUpdates", 1);

    ArgumentCaptor<ScriptStabilizationRun> runCaptor =
        ArgumentCaptor.forClass(ScriptStabilizationRun.class);
    verify(runRepository, org.mockito.Mockito.times(2)).save(runCaptor.capture());
    assertThat(runCaptor.getAllValues()).extracting(ScriptStabilizationRun::getScriptKey)
        .containsExactly("COOKS_ASSISTANT", "BARROWS1");
    assertThat(runCaptor.getAllValues()).extracting(ScriptStabilizationRun::getFamily)
        .containsExactly("quest", "boss");
    assertThat(runCaptor.getAllValues().get(0).getExecutedAt())
        .isEqualTo(LocalDateTime.of(2026, 4, 15, 10, 1, 2));
    assertThat(runCaptor.getAllValues().get(1).getExecutedAt())
        .isEqualTo(LocalDateTime.of(2026, 4, 15, 10, 3, 4));

    ArgumentCaptor<ScriptFailureTriage> triageCaptor =
        ArgumentCaptor.forClass(ScriptFailureTriage.class);
    verify(triageRepository).save(triageCaptor.capture());
    assertThat(triageCaptor.getValue().getScriptKey()).isEqualTo("BARROWS1");
    assertThat(triageCaptor.getValue().getFailureSignature()).isEqualTo("barrows1:missing_prayer");
    assertThat(triageCaptor.getValue().getLastSeenAt())
        .isEqualTo(LocalDateTime.of(2026, 4, 15, 10, 3, 4));

    verify(snapshotRepository, org.mockito.Mockito.atLeastOnce()).save(any());
  }

  @Test
  @DisplayName("ingestReport enriches triage and marks repeated failures backlog ready")
  void ingestReport_enrichesTriageAndMarksRepeatedFailuresBacklogReady() throws Exception {
    List<ScriptStabilizationRun> savedRuns = new ArrayList<>();
    JsonNode report =
        objectMapper.readTree(
            """
            {
              "report_id": "report_repeat",
              "rows": [
                {
                  "task_key": "BARROWS1",
                  "family": "boss",
                  "outcome": "TIMEOUT",
                  "status": "FAILED",
                  "duration_ms": 9000,
                  "failure_signature": "barrows1:timeout",
                  "error_message": "timed out",
                  "ended_at": "2026-04-15T11:00:00Z"
                }
              ]
            }
            """);

    ScriptFailureTriage existing = new ScriptFailureTriage();
    existing.setTriageKey("BARROWS1::barrows1:timeout");
    existing.setScriptKey("BARROWS1");
    existing.setFamily("boss");
    existing.setFailureSignature("barrows1:timeout");
    existing.setOccurrences(1);
    existing.setStatus("OPEN");
    existing.setFirstSeenAt(LocalDateTime.of(2026, 4, 15, 10, 0));

    when(runRepository.findByReportIdOrderByExecutedAtAsc("report_repeat")).thenReturn(List.of());
    when(triageRepository.findByTriageKey("BARROWS1::barrows1:timeout"))
        .thenReturn(Optional.of(existing));
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(runRepository.save(any(ScriptStabilizationRun.class)))
        .thenAnswer(
            invocation -> {
              ScriptStabilizationRun run = invocation.getArgument(0);
              savedRuns.add(run);
              return run;
            });
    when(runRepository.findByExecutedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenAnswer(invocation -> new ArrayList<>(savedRuns));
    when(snapshotRepository.findFirstByScriptKeyAndFamilyOrderByCapturedAtDesc(
            eq("BARROWS1"), eq("boss")))
        .thenReturn(Optional.empty());
    when(snapshotRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service.ingestReport(report);

    ArgumentCaptor<ScriptFailureTriage> triageCaptor =
        ArgumentCaptor.forClass(ScriptFailureTriage.class);
    verify(triageRepository).save(triageCaptor.capture());
    ScriptFailureTriage saved = triageCaptor.getValue();
    assertThat(saved.getOccurrences()).isEqualTo(2);
    assertThat(saved.getBacklogReady()).isTrue();
    assertThat(saved.getBacklogPriority()).isGreaterThanOrEqualTo(4);
    assertThat(saved.getLastReportId()).isEqualTo("report_repeat");
    assertThat(saved.getLastOutcome()).isEqualTo("TIMEOUT");
    assertThat(saved.getLastStatus()).isEqualTo("FAILED");
    assertThat(saved.getLastDurationMs()).isEqualTo(9000L);
    assertThat(saved.getLastExecutedAt()).isEqualTo(LocalDateTime.of(2026, 4, 15, 11, 0));
  }

  @Test
  @DisplayName("ingestReport treats duplicate report ids as a no-op")
  void ingestReport_treatsDuplicateReportIdAsNoOp() throws Exception {
    ScriptStabilizationRun existing = run("COOKS_ASSISTANT", "PASS", LocalDateTime.of(2026, 4, 15, 10, 0));
    JsonNode report =
        objectMapper.readTree(
            """
            {
              "report_id": "report_123",
              "rows": [
                {
                  "task_key": "COOKS_ASSISTANT",
                  "family": "quest",
                  "outcome": "PASS",
                  "status": "COMPLETED",
                  "duration_ms": 1234
                }
              ]
            }
            """);

    when(runRepository.findByReportIdOrderByExecutedAtAsc("report_123")).thenReturn(List.of(existing));

    Map<String, Object> result = service.ingestReport(report);

    assertThat(result).containsEntry("reportId", "report_123");
    assertThat(result).containsEntry("ingestedRows", 1);
    assertThat(result).containsEntry("triageUpdates", 0);
    assertThat(result).containsEntry("duplicateReport", true);
    verify(runRepository, never()).save(any());
    verify(triageRepository, never()).save(any());
    verify(snapshotRepository, never()).save(any());
  }

  @Test
  @DisplayName("ingestReport rejects reports without rows")
  void ingestReport_rejectsReportsWithoutRows() throws Exception {
    JsonNode report = objectMapper.readTree("{\"report_id\":\"report_123\"}");

    assertThatThrownBy(() -> service.ingestReport(report))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rows array");

    verify(runRepository, never()).save(any());
  }

  @Test
  @DisplayName("ingestReport rejects rows without task_key")
  void ingestReport_rejectsRowsWithoutTaskKey() throws Exception {
    JsonNode report =
        objectMapper.readTree(
            """
            {
              "report_id": "report_123",
              "rows": [
                {
                  "outcome": "PASS",
                  "status": "COMPLETED"
                }
              ]
            }
            """);

    assertThatThrownBy(() -> service.ingestReport(report))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("task_key is required");

    verify(runRepository, never()).save(any());
  }

  @Test
  @DisplayName("ingestReport rejects invalid row timestamps")
  void ingestReport_rejectsInvalidRowTimestamps() throws Exception {
    JsonNode report =
        objectMapper.readTree(
            """
            {
              "report_id": "report_123",
              "rows": [
                {
                  "task_key": "COOKS_ASSISTANT",
                  "family": "quest",
                  "outcome": "PASS",
                  "status": "COMPLETED",
                  "ended_at": "not-a-timestamp"
                }
              ]
            }
            """);

    assertThatThrownBy(() -> service.ingestReport(report))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid timestamp");

    verify(runRepository, never()).save(any());
  }

  @Test
  @DisplayName("computeKpis reports pass rate timeout rate and regression")
  void computeKpis_reportsPassRateTimeoutRateAndRegression() {
    LocalDateTime now = LocalDateTime.now();
    ScriptStabilizationRun recentPass = run("PROGRESSIVE_WOODCUTTING", "PASS", now.minusHours(1));
    ScriptStabilizationRun recentTimeout =
        run("PROGRESSIVE_WOODCUTTING", "TIMEOUT", now.minusHours(2));
    ScriptStabilizationRun oldPass = run("PROGRESSIVE_WOODCUTTING", "PASS", now.minusDays(2));
    ScriptStabilizationRun oldPass2 = run("PROGRESSIVE_WOODCUTTING", "PASS", now.minusDays(3));
    recentPass.setFamily("skilling");
    recentTimeout.setFamily("skilling");
    oldPass.setFamily("skilling");
    oldPass2.setFamily("skilling");

    when(runRepository.findByExecutedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(recentPass, recentTimeout))
        .thenReturn(List.of(recentPass, recentTimeout, oldPass, oldPass2));
    when(triageRepository.findByStatusInOrderByBacklogPriorityDescLastSeenAtDesc(
            eq(List.of("OPEN", "IN_PROGRESS", "QUEUED_FOR_BACKLOG"))))
        .thenReturn(List.of());
    when(snapshotRepository.findTop20ByOrderByCapturedAtDesc()).thenReturn(List.of());
    when(snapshotRepository.findFirstByScriptKeyAndFamilyOrderByCapturedAtDesc(
            eq("PROGRESSIVE_WOODCUTTING"), eq("skilling")))
        .thenReturn(Optional.empty());

    Map<String, Object> result = service.computeKpis();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> scripts = (List<Map<String, Object>>) result.get("scripts");
    Map<String, Object> scriptMetrics =
        scripts.stream().filter(row -> "PROGRESSIVE_WOODCUTTING".equals(row.get("script_key"))).findFirst().orElseThrow();
    assertThat(scriptMetrics.get("pass_rate_24h")).isEqualTo(0.5d);
    assertThat(scriptMetrics.get("timeout_rate_24h")).isEqualTo(0.5d);
    assertThat(scriptMetrics.get("regression")).isEqualTo(false);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> families = (List<Map<String, Object>>) result.get("families");
    assertThat(families).hasSize(1);
    assertThat(families.get(0).get("family")).isEqualTo("skilling");
  }

  @Test
  @DisplayName("listTriage filters unresolved rows by family and backlog readiness")
  void listTriage_filtersUnresolvedRowsByFamilyAndBacklogReadiness() {
    ScriptFailureTriage queued = triage("BARROWS1", "boss", "QUEUED_FOR_BACKLOG", true, 5);
    queued.setLastSeenAt(LocalDateTime.of(2026, 4, 15, 12, 0));
    ScriptFailureTriage open = triage("COOKS_ASSISTANT", "quest", "OPEN", false, 1);
    open.setLastSeenAt(LocalDateTime.of(2026, 4, 15, 11, 0));

    when(triageRepository.findByStatusInAndFamilyOrderByBacklogPriorityDescLastSeenAtDesc(
            eq(List.of("OPEN", "IN_PROGRESS", "QUEUED_FOR_BACKLOG")), eq("boss")))
        .thenReturn(List.of(queued));

    List<ScriptFailureTriage> result = service.listTriage("boss", null, true);

    assertThat(result).containsExactly(queued);
  }

  @Test
  @DisplayName("queueForBacklog promotes triage item into backlog-ready state")
  void queueForBacklog_promotesTriageItem() {
    ScriptFailureTriage triage = triage("BARROWS1", "boss", "OPEN", false, 0);
    when(triageRepository.findById(42L)).thenReturn(Optional.of(triage));
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptFailureTriage result = service.queueForBacklog(42L);

    assertThat(result.getBacklogReady()).isTrue();
    assertThat(result.getBacklogPriority()).isEqualTo(1);
    assertThat(result.getStatus()).isEqualTo("QUEUED_FOR_BACKLOG");
  }

  private ScriptStabilizationRun run(String scriptKey, String outcome, LocalDateTime executedAt) {
    ScriptStabilizationRun run = new ScriptStabilizationRun();
    run.setReportId("report");
    run.setScriptKey(scriptKey);
    run.setOutcome(outcome);
    run.setStatus(outcome);
    run.setExecutedAt(executedAt);
    return run;
  }

  private ScriptFailureTriage triage(
      String scriptKey, String family, String status, boolean backlogReady, int priority) {
    ScriptFailureTriage triage = new ScriptFailureTriage();
    triage.setTriageKey(scriptKey + "::failure");
    triage.setScriptKey(scriptKey);
    triage.setFamily(family);
    triage.setFailureSignature("failure");
    triage.setStatus(status);
    triage.setBacklogReady(backlogReady);
    triage.setBacklogPriority(priority);
    return triage;
  }
}

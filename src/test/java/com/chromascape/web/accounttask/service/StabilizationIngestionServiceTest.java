package com.chromascape.web.accounttask.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.repository.ScriptFailureTriageRepository;
import com.chromascape.web.accounttask.repository.ScriptStabilizationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
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

  private StabilizationIngestionService service;

  @BeforeEach
  void setUp() {
    service = new StabilizationIngestionService(runRepository, triageRepository);
  }

  @Test
  @DisplayName("ingestReport stores runs and creates triage rows for failures")
  void ingestReport_storesRunsAndCreatesTriageRows() throws Exception {
    JsonNode report =
        objectMapper.readTree(
            """
            {
              "report_id": "report_123",
              "rows": [
                {
                  "task_key": "COOKS_ASSISTANT",
                  "outcome": "PASS",
                  "status": "COMPLETED",
                  "duration_ms": 1234
                },
                {
                  "task_key": "BARROWS1",
                  "outcome": "FAIL",
                  "status": "FAILED",
                  "duration_ms": 2345,
                  "failure_signature": "barrows1:missing_prayer",
                  "error_message": "missing prayer potions"
                }
              ]
            }
            """);

    when(runRepository.save(any(ScriptStabilizationRun.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(triageRepository.findByTriageKey("BARROWS1::barrows1:missing_prayer"))
        .thenReturn(Optional.empty());
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Map<String, Object> result = service.ingestReport(report);

    assertThat(result).containsEntry("reportId", "report_123");
    assertThat(result).containsEntry("ingestedRows", 2);
    assertThat(result).containsEntry("triageUpdates", 1);

    ArgumentCaptor<ScriptStabilizationRun> runCaptor =
        ArgumentCaptor.forClass(ScriptStabilizationRun.class);
    verify(runRepository, org.mockito.Mockito.times(2)).save(runCaptor.capture());
    assertThat(runCaptor.getAllValues()).extracting(ScriptStabilizationRun::getScriptKey)
        .containsExactly("COOKS_ASSISTANT", "BARROWS1");

    ArgumentCaptor<ScriptFailureTriage> triageCaptor =
        ArgumentCaptor.forClass(ScriptFailureTriage.class);
    verify(triageRepository).save(triageCaptor.capture());
    ScriptFailureTriage saved = triageCaptor.getValue();
    assertThat(saved.getTriageKey()).isEqualTo("BARROWS1::barrows1:missing_prayer");
    assertThat(saved.getScriptKey()).isEqualTo("BARROWS1");
    assertThat(saved.getLastReportId()).isEqualTo("report_123");
    assertThat(saved.getLastErrorMessage()).isEqualTo("missing prayer potions");
  }

  @Test
  @DisplayName("computeKpis reports pass rate and open triage count")
  void computeKpis_reportsPassRateAndOpenTriageCount() {
    LocalDateTime now = LocalDateTime.now();
    ScriptStabilizationRun recentPass = run("PROGRESSIVE_WOODCUTTING", "PASS", now.minusHours(1));
    ScriptStabilizationRun recentTimeout =
        run("PROGRESSIVE_WOODCUTTING", "TIMEOUT", now.minusHours(2));
    ScriptStabilizationRun oldPass = run("PROGRESSIVE_WOODCUTTING", "PASS", now.minusDays(2));

    when(runRepository.findByExecutedAtBetween(any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(recentPass, recentTimeout))
        .thenReturn(List.of(recentPass, recentTimeout, oldPass));
    when(triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN"))
        .thenReturn(List.of(new ScriptFailureTriage()));

    Map<String, Object> result = service.computeKpis();

    assertThat(result).containsEntry("window_24h_count", 2);
    assertThat(result).containsEntry("window_7d_count", 3);
    assertThat(result).containsEntry("open_triage_count", 1);

    @SuppressWarnings("unchecked")
    Map<String, Object> scripts = (Map<String, Object>) result.get("scripts");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics = (Map<String, Object>) scripts.get("PROGRESSIVE_WOODCUTTING");
    assertThat(metrics).containsEntry("pass_rate_24h", 0.5d);
    assertThat(metrics).containsEntry("pass_rate_7d", 2d / 3d);
    assertThat(metrics).containsEntry("timeout_rate_24h", 0.5d);
  }

  @Test
  @DisplayName("listOpenTriage returns unresolved rows from the repository")
  void listOpenTriage_returnsRepositoryRows() {
    ScriptFailureTriage open = new ScriptFailureTriage();
    open.setTriageKey("BARROWS1::failure");
    open.setScriptKey("BARROWS1");
    open.setFailureSignature("failure");
    when(triageRepository.findByStatusOrderByLastSeenAtDesc("OPEN")).thenReturn(List.of(open));

    List<ScriptFailureTriage> result = service.listOpenTriage();

    assertThat(result).containsExactly(open);
  }

  @Test
  @DisplayName("resolveTriage marks the row as resolved")
  void resolveTriage_marksRowResolved() {
    ScriptFailureTriage triage = new ScriptFailureTriage();
    triage.setTriageKey("BARROWS1::failure");
    triage.setScriptKey("BARROWS1");
    triage.setFailureSignature("failure");
    triage.setStatus("OPEN");
    when(triageRepository.findById(42L)).thenReturn(Optional.of(triage));
    when(triageRepository.save(any(ScriptFailureTriage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    ScriptFailureTriage result = service.resolveTriage(42L);

    assertThat(result.getStatus()).isEqualTo("RESOLVED");
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
}

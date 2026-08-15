package com.chromascape.web.accounttask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.entity.ScriptStabilizationRun;
import com.chromascape.web.accounttask.entity.ScriptStabilizationSnapshot;
import com.chromascape.web.accounttask.service.StabilizationArtifactStore;
import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class StabilizationControllerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Mock private StabilizationIngestionService ingestionService;
  @Mock private Micro6ExecutorCatalogService catalogService;
  @Mock private StabilizationArtifactStore artifactStore;

  private StabilizationController controller;

  @BeforeEach
  void setUp() {
    controller = new StabilizationController(ingestionService, catalogService, artifactStore);
  }

  @Test
  @DisplayName("ingestReport returns the service payload")
  void ingestReport_returnsServicePayload() throws Exception {
    when(ingestionService.ingestReport(any()))
        .thenReturn(Map.of("reportId", "report_123", "ingestedRows", 2));

    ResponseEntity<Map<String, Object>> response =
        controller.ingestReport(
            objectMapper.readTree("{\"report_id\":\"report_123\",\"rows\":[]}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("reportId", "report_123");
    assertThat(response.getBody()).containsEntry("ingestedRows", 2);
  }

  @Test
  @DisplayName("kpi returns the computed KPI payload")
  void kpi_returnsComputedPayload() {
    when(ingestionService.computeKpis()).thenReturn(Map.of("open_triage_count", 1));

    ResponseEntity<Map<String, Object>> response = controller.kpi();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("open_triage_count", 1);
  }

  @Test
  @DisplayName("openTriage returns the unresolved triage list")
  void openTriage_returnsUnresolvedRows() {
    ScriptFailureTriage triage = new ScriptFailureTriage();
    triage.setTriageKey("BARROWS1::failure");
    triage.setScriptKey("BARROWS1");
    triage.setFailureSignature("failure");
    when(ingestionService.listOpenTriage()).thenReturn(List.of(triage));

    ResponseEntity<Object> response = controller.openTriage();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isInstanceOf(List.class);
    assertThat((List<?>) response.getBody()).hasSize(1);
  }

  @Test
  @DisplayName("resolveTriage returns 400 when triage item is missing")
  void resolveTriage_returns400_whenMissing() {
    when(ingestionService.resolveTriage(42L))
        .thenThrow(new IllegalArgumentException("Triage item not found: 42"));

    ResponseEntity<Object> response = controller.resolveTriage(42L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo(Map.of("error", "Triage item not found: 42"));
  }

  @Test
  @DisplayName("ingestHarnessManifest returns the service payload")
  void ingestHarnessManifest_returnsPayload() throws Exception {
    when(ingestionService.ingestHarnessManifest(any()))
        .thenReturn(Map.of("runId", "run-1", "saved", true));

    ResponseEntity<Object> response =
        controller.ingestHarnessManifest(
            objectMapper.readTree("""
                {"schema_version":"harness-run-manifest/v1","run_id":"run-1"}
                """));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .isEqualTo(Map.of("runId", "run-1", "saved", true));
  }

  @Test
  @DisplayName("ingestHarnessManifest returns 400 for unknown schema")
  void ingestHarnessManifest_returns400_onUnknownSchema() throws Exception {
    when(ingestionService.ingestHarnessManifest(any()))
        .thenThrow(new IllegalArgumentException("unsupported harness manifest schema_version 'x'"));

    ResponseEntity<Object> response =
        controller.ingestHarnessManifest(
            objectMapper.readTree("{\"schema_version\":\"x\"}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("ingestReliabilityMatrix returns the service payload")
  void ingestReliabilityMatrix_returnsPayload() throws Exception {
    when(ingestionService.ingestReliabilityMatrix(any()))
        .thenReturn(Map.of("matrixHash", "sha256:matrix", "snapshots", 1));

    ResponseEntity<Object> response =
        controller.ingestReliabilityMatrix(
            objectMapper.readTree("""
                {"schema_version":"reliability-matrix/v1","groups":[]}
                """));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .isEqualTo(Map.of("matrixHash", "sha256:matrix", "snapshots", 1));
  }

  @Test
  @DisplayName("getRunByRunId returns the run when present")
  void getRunByRunId_returnsRun() {
    com.chromascape.web.accounttask.entity.StabilizationRunAggregate run =
        new com.chromascape.web.accounttask.entity.StabilizationRunAggregate();
    run.setRunId("run-1");
    when(ingestionService.getRunByRunId("run-1")).thenReturn(Optional.of(run));

    ResponseEntity<Object> response = controller.getRunByRunId("run-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(run);
  }

  @Test
  @DisplayName("getRunByRunId returns 404 when missing")
  void getRunByRunId_returns404_whenMissing() {
    when(ingestionService.getRunByRunId("missing")).thenReturn(Optional.empty());

    ResponseEntity<Object> response = controller.getRunByRunId("missing");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("getMatrixByMatrixHash returns the full matrix document when present")
  void getMatrixByMatrixHash_returnsMatrixDocument() throws Exception {
    JsonNode matrix =
        objectMapper.readTree(
            """
            {"schema_version":"reliability-matrix/v1","groups":[],"incomparable":[]}
            """);
    when(ingestionService.getMatrixDocumentByHash("sha256:matrix")).thenReturn(Optional.of(matrix));

    ResponseEntity<Object> response = controller.getMatrixByMatrixHash("sha256:matrix");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(matrix);
  }

  @Test
  @DisplayName("getMatrixByMatrixHash returns 404 when missing")
  void getMatrixByMatrixHash_returns404_whenMissing() {
    when(ingestionService.getMatrixDocumentByHash("missing")).thenReturn(Optional.empty());

    ResponseEntity<Object> response = controller.getMatrixByMatrixHash("missing");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("getRunByRunIdAndAttemptId returns the attempt when present")
  void getRunByRunIdAndAttemptId_returnsAttempt() {
    ScriptStabilizationRun attempt = new ScriptStabilizationRun();
    attempt.setRunId("run-1");
    attempt.setAttemptId("attempt-2");
    when(ingestionService.getRunByRunIdAndAttemptId("run-1", "attempt-2"))
        .thenReturn(Optional.of(attempt));

    ResponseEntity<Object> response = controller.getRunByRunIdAndAttemptId("run-1", "attempt-2");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(attempt);
  }

  @Test
  @DisplayName("getRunAttempts returns the attempt list")
  void getRunAttempts_returnsList() {
    when(ingestionService.getRunAttempts("run-1")).thenReturn(List.of(new ScriptStabilizationRun()));

    ResponseEntity<Object> response = controller.getRunAttempts("run-1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List<?>) response.getBody()).hasSize(1);
  }
}

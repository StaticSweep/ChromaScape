package com.chromascape.web.accounttask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.entity.ScriptFailureTriage;
import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
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

  private StabilizationController controller;

  @BeforeEach
  void setUp() {
    controller = new StabilizationController(ingestionService, catalogService);
  }

  @Test
  @DisplayName("ingestReport returns the service payload")
  void ingestReport_returnsServicePayload() throws Exception {
    when(ingestionService.ingestReport(any()))
        .thenReturn(Map.of("reportId", "report_123", "ingestedRows", 2));

    ResponseEntity<Map<String, Object>> response =
        controller.ingestReport(objectMapper.readTree("{\"report_id\":\"report_123\",\"rows\":[]}"));

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
}

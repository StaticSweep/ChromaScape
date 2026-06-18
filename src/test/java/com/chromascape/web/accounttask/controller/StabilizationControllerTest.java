package com.chromascape.web.accounttask.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.chromascape.web.accounttask.service.StabilizationIngestionService;
import com.chromascape.web.integration.micro6.service.Micro6ExecutorCatalogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.List;
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
  @DisplayName("ingestReport returns 400 for invalid reports")
  void ingestReport_returns400_forInvalidReports() throws Exception {
    when(ingestionService.ingestReport(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalArgumentException("rows array required"));

    ResponseEntity<Map<String, Object>> response =
        controller.ingestReport(objectMapper.readTree("{\"report_id\":\"bad\"}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("error", "rows array required");
  }

  @Test
  @DisplayName("kpi forwards family and historyLimit filters")
  void kpi_forwardsFamilyAndHistoryLimitFilters() {
    when(ingestionService.computeKpis("boss", 20)).thenReturn(Map.of("family_filter", "boss"));

    ResponseEntity<Map<String, Object>> response = controller.kpi("boss", 20);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("family_filter", "boss");
  }

  @Test
  @DisplayName("openTriage forwards family, status, and backlogReady filters")
  void openTriage_forwardsFilters() {
    when(ingestionService.listTriage("quest", "OPEN", true)).thenReturn(List.of());

    ResponseEntity<Object> response = controller.openTriage("quest", "OPEN", true);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("queueForBacklog returns 400 when triage item is missing")
  void queueForBacklog_returns400_whenMissing() {
    when(ingestionService.queueForBacklog(42L))
        .thenThrow(new IllegalArgumentException("Triage item not found: 42"));

    ResponseEntity<Object> response = controller.queueForBacklog(42L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isEqualTo(Map.of("error", "Triage item not found: 42"));
  }
}

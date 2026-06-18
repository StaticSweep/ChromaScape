package com.chromascape.web.aiplayer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
class AiPlayerControllerTest {

  @Mock private ImitationDatasetBuilderService datasetBuilder;
  @Mock private ShadowPolicyService shadowPolicy;
  @Mock private PatchOutcomeEvaluationService evaluationService;
  @Mock private AiPlayerDatasetScheduler datasetScheduler;

  private AiPlayerController controller;

  @BeforeEach
  void setUp() {
    controller = new AiPlayerController(datasetBuilder, shadowPolicy, evaluationService, datasetScheduler);
  }

  @Test
  @DisplayName("status exposes control and scheduler flags")
  void status_exposesControlAndSchedulerFlags() {
    when(datasetBuilder.isAiPlayerEnabled()).thenReturn(true);
    when(shadowPolicy.isShadowMode()).thenReturn(true);
    when(shadowPolicy.isControlEnabled()).thenReturn(false);
    when(shadowPolicy.isControlAllowed()).thenReturn(false);
    when(datasetScheduler.isScheduledExportEnabled()).thenReturn(true);
    when(datasetBuilder.getOutputDir()).thenReturn("/tmp/datasets");
    when(datasetBuilder.getDatasetSummary(null)).thenReturn("summary");

    ResponseEntity<Object> response = controller.getStatus();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((Map<?, ?>) response.getBody())
        .containsEntry("controlEnabled", false)
        .containsEntry("controlAllowed", false)
        .containsEntry("scheduledPatchOutcomeExports", true);
  }

  @Test
  @DisplayName("buildPatchOutcomeDataset returns manifest metadata")
  void buildPatchOutcomeDataset_returnsManifestMetadata() {
    when(datasetBuilder.isAiPlayerEnabled()).thenReturn(true);
    when(datasetBuilder.buildPatchOutcomeDatasetResult(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new DatasetBuildResult(
                "ds-1",
                "patch_outcomes",
                "/tmp/out.jsonl",
                "/tmp/out.manifest.json",
                3,
                5,
                2,
                Map.of(),
                List.of()));

    ResponseEntity<Object> response = controller.buildPatchOutcomeDataset(Map.of());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((Map<?, ?>) response.getBody())
        .containsEntry("manifestPath", "/tmp/out.manifest.json")
        .containsEntry("recordCount", 3)
        .containsEntry("excludedRecordCount", 2);
  }

  @Test
  @DisplayName("evaluatePatchOutcomes forwards request values")
  void evaluatePatchOutcomes_forwardsRequestValues() {
    LocalDateTime start = LocalDateTime.of(2026, 6, 1, 0, 0);
    LocalDateTime end = LocalDateTime.of(2026, 6, 2, 0, 0);
    when(evaluationService.evaluate(start, end, "QuestScript"))
        .thenReturn(Map.of("eligibleItems", 4, "successRate", 0.75d));

    ResponseEntity<Object> response =
        controller.evaluatePatchOutcomes("2026-06-01T00:00:00", "2026-06-02T00:00:00", "QuestScript");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((Map<?, ?>) response.getBody()).containsEntry("eligibleItems", 4);
  }
}

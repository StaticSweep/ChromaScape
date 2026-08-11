package com.chromascape.web.aiplayer.prototype;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/** Tests the fishing prototype endpoints and structured error contract. */
class AiPlayerPrototypeControllerTest {

  private FishingPreflightService preflightService;
  private FishingDatasetBuilderService datasetBuilder;
  private FishingReplayService replayService;
  private AiPlayerPrototypeController controller;

  @BeforeEach
  void setUp() {
    preflightService = mock(FishingPreflightService.class);
    datasetBuilder = mock(FishingDatasetBuilderService.class);
    replayService = mock(FishingReplayService.class);
    controller =
        new AiPlayerPrototypeController(preflightService, datasetBuilder, replayService);
    ReflectionTestUtils.setField(controller, "aiPlayerEnabled", true);
  }

  @Test
  @DisplayName("disabled feature rejects new endpoints")
  void disabledFeatureRejects() {
    ReflectionTestUtils.setField(controller, "aiPlayerEnabled", false);

    ResponseEntity<Object> preflight = controller.preflight("recording_1");
    assertThat(preflight.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body(preflight)).containsEntry("code", "AI_PLAYER_DISABLED");

    ResponseEntity<Object> train = controller.train(Map.of("recordingId", "recording_1"));
    assertThat(train.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body(train)).containsEntry("code", "AI_PLAYER_DISABLED");

    ResponseEntity<Object> run =
        controller.run(Map.of("modelId", "fishing_1", "recordingId", "recording_2"));
    assertThat(run.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body(run)).containsEntry("code", "AI_PLAYER_DISABLED");
  }

  @Test
  @DisplayName("missing recording returns a structured 404")
  void missingRecordingReturnsStructuredError() {
    when(preflightService.preflight("missing"))
        .thenThrow(
            new PrototypeApiException(
                HttpStatus.NOT_FOUND,
                PreflightReport.IssueCode.RECORDING_NOT_FOUND,
                "not found"));

    ResponseEntity<Object> response = controller.preflight("missing");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    Map<String, Object> body = body(response);
    assertThat(body).containsEntry("status", "REJECTED");
    assertThat(body).containsEntry("code", "RECORDING_NOT_FOUND");
    assertThat(body).containsKey("message");
    assertThat(body).containsKey("details");
  }

  @Test
  @DisplayName("missing model returns a structured 404")
  void missingModelReturnsStructuredError() {
    when(replayService.run("fishing_missing", "recording_2"))
        .thenThrow(
            new PrototypeApiException(
                HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "No model found for modelId fishing_missing"));

    ResponseEntity<Object> response =
        controller.run(Map.of("modelId", "fishing_missing", "recordingId", "recording_2"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(body(response)).containsEntry("code", "MODEL_NOT_FOUND");
  }

  @Test
  @DisplayName("same train/test recording is rejected with a 409")
  void sameTrainTestRecordingRejected() {
    when(replayService.run("fishing_1", "recording_train"))
        .thenThrow(
            new PrototypeApiException(
                HttpStatus.CONFLICT,
                "SAME_TRAIN_TEST_RECORDING",
                "The training recording cannot be used as the test recording"));

    ResponseEntity<Object> response =
        controller.run(Map.of("modelId", "fishing_1", "recordingId", "recording_train"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(body(response)).containsEntry("code", "SAME_TRAIN_TEST_RECORDING");
  }

  @Test
  @DisplayName("preflight returns the report")
  void preflightReturnsReport() {
    PreflightReport report =
        PreflightReport.ready("recording_1", "debug_1", true, 240, 2, 2, 2, 0, 0, 5, true);
    when(preflightService.preflight("recording_1")).thenReturn(report);

    ResponseEntity<Object> response = controller.preflight("recording_1");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    PreflightReport body = (PreflightReport) response.getBody();
    assertThat(body.status()).isEqualTo("READY");
    assertThat(body.snapshotCount()).isEqualTo(240);
    assertThat(body.observedCatchDelta()).isEqualTo(5);
  }

  @Test
  @DisplayName("train returns the training result")
  void trainReturnsResult() {
    TrainingResult result =
        new TrainingResult(
            "TRAINED",
            "fishing_abc",
            "fishing_dataset_def",
            "recording_1",
            "debug_1",
            2,
            List.of(SemanticAction.interactNpc("net", "fishing spot")),
            ".chromascape/datasets/fishing/datasets/fishing_dataset_def.jsonl",
            ".chromascape/datasets/fishing/datasets/fishing_dataset_def.manifest.json",
            ".chromascape/datasets/fishing/models/fishing_abc.json");
    when(datasetBuilder.train("recording_1", 5)).thenReturn(result);

    ResponseEntity<Object> response =
        controller.train(Map.of("recordingId", "recording_1", "requiredCatchCount", 5));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    TrainingResult body = (TrainingResult) response.getBody();
    assertThat(body.status()).isEqualTo("TRAINED");
    assertThat(body.modelId()).isEqualTo("fishing_abc");
    assertThat(body.exampleCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("train rejects unsupported catch counts")
  void trainRejectsUnsupportedCatchCount() {
    when(datasetBuilder.train("recording_1", 3))
        .thenThrow(
            new PrototypeApiException(
                HttpStatus.BAD_REQUEST,
                "UNSUPPORTED_CATCH_COUNT",
                "Prototype one accepts only requiredCatchCount=5"));

    ResponseEntity<Object> response =
        controller.train(Map.of("recordingId", "recording_1", "requiredCatchCount", 3));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body(response)).containsEntry("code", "UNSUPPORTED_CATCH_COUNT");
  }

  @Test
  @DisplayName("run returns the run report")
  void runReturnsReport() {
    RunReport report =
        new RunReport(
            "PASSED",
            "fishing_run_1",
            "fishing_abc",
            "recording_train",
            "recording_test",
            "RECORDED_DECISION_GATES",
            2,
            2,
            0,
            0,
            5,
            true,
            List.of(),
            null,
            null,
            null,
            null,
            null,
            ".chromascape/datasets/fishing/runs/fishing_run_1.json");
    when(replayService.run("fishing_abc", "recording_test")).thenReturn(report);

    ResponseEntity<Object> response =
        controller.run(Map.of("modelId", "fishing_abc", "recordingId", "recording_test"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    RunReport body = (RunReport) response.getBody();
    assertThat(body.status()).isEqualTo("PASSED");
    assertThat(body.decisionTimingMode()).isEqualTo("RECORDED_DECISION_GATES");
    assertThat(body.expectedDecisionCount()).isEqualTo(2);
    assertThat(body.reportPath()).isEqualTo(".chromascape/datasets/fishing/runs/fishing_run_1.json");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> body(ResponseEntity<Object> response) {
    return (Map<String, Object>) response.getBody();
  }
}

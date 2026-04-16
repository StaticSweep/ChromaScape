package com.chromascape.web.aiplayer;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for AI shadow player management.
 *
 * <p>Provides endpoints to build imitation datasets, query shadow policy status, and manage shadow
 * sessions.
 */
@RestController
@RequestMapping("/api/ai-player")
public class AiPlayerController {

  private static final Logger log = LoggerFactory.getLogger(AiPlayerController.class);

  private final ImitationDatasetBuilderService datasetBuilder;
  private final ShadowPolicyService shadowPolicy;

  public AiPlayerController(
      ImitationDatasetBuilderService datasetBuilder, ShadowPolicyService shadowPolicy) {
    this.datasetBuilder = datasetBuilder;
    this.shadowPolicy = shadowPolicy;
  }

  /** Gets the AI player feature status. */
  @GetMapping("/status")
  public ResponseEntity<Object> getStatus() {
    Map<String, Object> status =
        Map.of(
            "enabled", datasetBuilder.isAiPlayerEnabled(),
            "shadowMode", shadowPolicy.isShadowMode(),
            "summary", datasetBuilder.getDatasetSummary(null));
    return ResponseEntity.ok(status);
  }

  /** Builds an imitation dataset. */
  @PostMapping("/datasets/build")
  public ResponseEntity<Object> buildDataset(@RequestBody Map<String, String> request) {
    if (!datasetBuilder.isAiPlayerEnabled()) {
      return ResponseEntity.badRequest().body("AI player feature is disabled");
    }

    String accountName = request.get("accountName");
    String startTimeStr = request.get("startTime");
    String endTimeStr = request.get("endTime");

    LocalDateTime startTime =
        startTimeStr != null ? LocalDateTime.parse(startTimeStr, DateTimeFormatter.ISO_DATE_TIME)
            : LocalDateTime.now().minusHours(1);
    LocalDateTime endTime =
        endTimeStr != null ? LocalDateTime.parse(endTimeStr, DateTimeFormatter.ISO_DATE_TIME)
            : LocalDateTime.now();

    try {
      String outputPath = datasetBuilder.buildDataset(accountName, startTime, endTime);
      return ResponseEntity.ok(Map.of("outputPath", outputPath, "status", "COMPLETED"));
    } catch (Exception e) {
      log.error("Failed to build dataset", e);
      return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
    }
  }

  /** Builds a patch-outcome training dataset from completed task items. */
  @PostMapping("/datasets/build-patch-outcomes")
  public ResponseEntity<Object> buildPatchOutcomeDataset(@RequestBody Map<String, String> request) {
    if (!datasetBuilder.isAiPlayerEnabled()) {
      return ResponseEntity.badRequest().body("AI player feature is disabled");
    }

    String startTimeStr = request.get("startTime");
    String endTimeStr = request.get("endTime");

    LocalDateTime startTime =
        startTimeStr != null
            ? LocalDateTime.parse(startTimeStr, DateTimeFormatter.ISO_DATE_TIME)
            : LocalDateTime.now().minusDays(7);
    LocalDateTime endTime =
        endTimeStr != null
            ? LocalDateTime.parse(endTimeStr, DateTimeFormatter.ISO_DATE_TIME)
            : LocalDateTime.now();

    try {
      String outputPath = datasetBuilder.buildPatchOutcomeDataset(startTime, endTime);
      return ResponseEntity.ok(Map.of("outputPath", outputPath, "status", "COMPLETED"));
    } catch (Exception e) {
      log.error("Failed to build patch-outcome dataset", e);
      return ResponseEntity.internalServerError().body("Failed: " + e.getMessage());
    }
  }

  /** Gets a shadow policy recommendation. */
  @GetMapping("/shadow/recommendation")
  public ResponseEntity<Object> getRecommendation(
      @RequestParam String recordingSessionId, @RequestParam(defaultValue = "0") int tick) {
    String recommendation = shadowPolicy.getPolicyRecommendation(recordingSessionId, tick);
    if (recommendation == null) {
      return ResponseEntity.ok(Map.of("available", false));
    }
    return ResponseEntity.ok(Map.of("available", true, "recommendation", recommendation));
  }
}

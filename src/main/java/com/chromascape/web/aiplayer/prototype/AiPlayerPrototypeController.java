package com.chromascape.web.aiplayer.prototype;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the recording-trained fishing agent prototype.
 *
 * <p>Exposes recording preflight, offline training, and the replay-gated held-out evaluation. All
 * endpoints require the AI player feature flag; none of them dispatch live control.
 */
@RestController
@RequestMapping("/api/ai-player/prototype/fishing")
public class AiPlayerPrototypeController {

  private static final Logger log = LoggerFactory.getLogger(AiPlayerPrototypeController.class);

  private final FishingPreflightService preflightService;
  private final FishingDatasetBuilderService datasetBuilder;
  private final FishingReplayService replayService;

  @Value("${features.ai-player.enabled:false}")
  private boolean aiPlayerEnabled;

  public AiPlayerPrototypeController(
      FishingPreflightService preflightService,
      FishingDatasetBuilderService datasetBuilder,
      FishingReplayService replayService) {
    this.preflightService = preflightService;
    this.datasetBuilder = datasetBuilder;
    this.replayService = replayService;
  }

  /** Runs recording preflight for the fishing prototype. */
  @GetMapping("/preflight")
  public ResponseEntity<Object> preflight(@RequestParam String recordingId) {
    try {
      ensureEnabled();
      PreflightReport report = preflightService.preflight(recordingId);
      return ResponseEntity.ok(report);
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
  }

  /** Trains a deterministic demonstration baseline model from a recording. */
  @PostMapping("/train")
  public ResponseEntity<Object> train(@RequestBody Map<String, Object> request) {
    try {
      ensureEnabled();
      if (request == null) {
        request = Map.of();
      }
      String recordingId = stringValue(request.get("recordingId"));
      Integer requiredCatchCount = null;
      if (request.get("requiredCatchCount") != null) {
        requiredCatchCount = intValue(request.get("requiredCatchCount"));
        if (requiredCatchCount == null) {
          throw new PrototypeApiException(
              HttpStatus.BAD_REQUEST,
              "UNSUPPORTED_CATCH_COUNT",
              "requiredCatchCount must be an integer (prototype one accepts 5)");
        }
      }
      TrainingResult result = datasetBuilder.train(recordingId, requiredCatchCount);
      return ResponseEntity.ok(result);
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
  }

  /** Runs the replay-gated evaluation against a held-out recording. */
  @PostMapping("/run")
  public ResponseEntity<Object> run(@RequestBody Map<String, Object> request) {
    try {
      ensureEnabled();
      if (request == null) {
        request = Map.of();
      }
      String modelId = stringValue(request.get("modelId"));
      String recordingId = stringValue(request.get("recordingId"));
      RunReport report = replayService.run(modelId, recordingId);
      return ResponseEntity.ok(report);
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
  }

  /** Returns the prototype status block used by the AI player status endpoint. */
  public Map<String, Object> prototypeStatus() {
    return Map.of(
        "fishingPrototypeEnabled", aiPlayerEnabled,
        "fishingPrototypeAvailable", datasetBuilder.isAiPlayerEnabled(),
        "artifactDir", datasetBuilder.getArtifactDir());
  }

  private void ensureEnabled() {
    if (!aiPlayerEnabled) {
      throw new PrototypeApiException(
          HttpStatus.BAD_REQUEST, "AI_PLAYER_DISABLED", "AI player feature is disabled");
    }
  }

  @ExceptionHandler(PrototypeApiException.class)
  public ResponseEntity<Object> handlePrototypeException(PrototypeApiException e) {
    log.warn(
        "Prototype request rejected: code={}, status={}, message={}",
        e.getCode(),
        e.getStatus(),
        e.getMessage());
    return ResponseEntity.status(e.getStatus()).body(e.toErrorBody());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception e) {
    log.error("Prototype request failed unexpectedly", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(
            Map.of(
                "status", "REJECTED",
                "code", "INTERNAL_ERROR",
                "message", "Unexpected prototype failure",
                "details", Map.of()));
  }

  private static String stringValue(Object value) {
    return value != null ? String.valueOf(value) : null;
  }

  private static Integer intValue(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}

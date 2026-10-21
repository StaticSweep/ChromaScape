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
 * <p>Exposes recording preflight, offline training, the replay-gated held-out evaluation, explicit
 * model activation, and live shadow decision status/history. All endpoints require the AI player
 * feature flag except the shadow status endpoint, which reports effective feature state even when
 * the feature is disabled. None of them dispatch live control.
 */
@RestController
@RequestMapping("/api/ai-player/prototype/fishing")
public class AiPlayerPrototypeController {

  private static final Logger log = LoggerFactory.getLogger(AiPlayerPrototypeController.class);

  private final FishingPreflightService preflightService;
  private final FishingDatasetBuilderService datasetBuilder;
  private final FishingReplayService replayService;
  private final FishingActiveModelService activeModelService;
  private final FishingShadowDecisionService shadowDecisionService;

  @Value("${features.ai-player.enabled:false}")
  private boolean aiPlayerEnabled;

  public AiPlayerPrototypeController(
      FishingPreflightService preflightService,
      FishingDatasetBuilderService datasetBuilder,
      FishingReplayService replayService,
      FishingActiveModelService activeModelService,
      FishingShadowDecisionService shadowDecisionService) {
    this.preflightService = preflightService;
    this.datasetBuilder = datasetBuilder;
    this.replayService = replayService;
    this.activeModelService = activeModelService;
    this.shadowDecisionService = shadowDecisionService;
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

  /** Lists persisted fishing models and the currently active model. */
  @GetMapping("/models")
  public ResponseEntity<Object> listModels() {
    try {
      ensureEnabled();
      return ResponseEntity.ok(activeModelService.listModelsResponse());
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
  }

  /** Explicitly activates a replay-validated fishing model. */
  @PostMapping("/models/activate")
  public ResponseEntity<Object> activateModel(@RequestBody Map<String, Object> request) {
    try {
      ensureEnabled();
      String modelId = request == null ? null : stringValue(request.get("modelId"));
      return ResponseEntity.ok(activeModelService.activate(modelId));
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
  }

  /** Deactivates the currently active fishing model. */
  @PostMapping("/models/deactivate")
  public ResponseEntity<Object> deactivateModel() {
    try {
      ensureEnabled();
      return ResponseEntity.ok(activeModelService.deactivate());
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
  }

  /** Returns effective feature flags and live shadow decision status for an exact session/run. */
  @GetMapping("/shadow/status")
  public ResponseEntity<Object> shadowStatus(
      @RequestParam(required = false) String sessionId,
      @RequestParam(required = false) String shadowRunId) {
    return ResponseEntity.ok(shadowDecisionService.status(sessionId, shadowRunId));
  }

  /**
   * Returns persisted shadow decision history for one run (or the legacy schema-v1 file), newest
   * first.
   *
   * <p>Legacy and schema-v2 history are never merged: {@code legacy=true} reads the old {@code
   * {sessionId}.jsonl} file, otherwise {@code shadowRunId} is required and the per-run file is
   * read.
   */
  @GetMapping("/shadow/history")
  public ResponseEntity<Object> shadowHistory(
      @RequestParam String sessionId,
      @RequestParam(required = false) String shadowRunId,
      @RequestParam(defaultValue = "false") boolean legacy,
      @RequestParam(defaultValue = "50") int limit) {
    try {
      ensureEnabled();
      return ResponseEntity.ok(
          shadowDecisionService.history(sessionId, shadowRunId, legacy, limit));
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
  }

  /** Discovers the persisted shadow runs for a session, newest run first. */
  @GetMapping("/shadow/runs")
  public ResponseEntity<Object> shadowRuns(@RequestParam String sessionId) {
    try {
      ensureEnabled();
      return ResponseEntity.ok(shadowDecisionService.runs(sessionId));
    } catch (PrototypeApiException e) {
      return handlePrototypeException(e);
    }
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

  @ExceptionHandler(com.chromascape.web.aiplayer.contract.CurrentSliceContractException.class)
  public ResponseEntity<Object> handleContract(
      com.chromascape.web.aiplayer.contract.CurrentSliceContractException exception) {
    return ResponseEntity.unprocessableEntity()
        .body(
            Map.of(
                "status",
                "BLOCKED",
                "code",
                exception.code().name(),
                "message",
                exception.code().name()));
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

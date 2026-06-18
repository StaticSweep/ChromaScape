package com.chromascape.web.simulator;

import com.chromascape.web.simulator.dto.ReplayCatalogResponse;
import com.chromascape.web.simulator.dto.ReplayFramePageResponse;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes simulator replay source endpoints for the OpenRune tick replay
 * client.
 *
 * <p>Provides catalog listing and paginated tick frame retrieval for recordings that have
 * associated tick snapshot data. These endpoints are called by the OpenRune TickReplayClient to
 * fetch per-tick state snapshots during tick-mode replay playback.
 *
 * <p>Status code mapping:
 *
 * <ul>
 *   <li>Disabled replay-control: HTTP 503
 *   <li>Missing required parameter: HTTP 400
 *   <li>Recording not found: HTTP 404
 *   <li>Success: HTTP 200
 * </ul>
 */
@RestController
@RequestMapping("/api/simulator/replay/source")
public class SimulatorReplaySourceController {

  private static final Logger logger = LogManager.getLogger(SimulatorReplaySourceController.class);

  private final SimulatorReplaySourceService sourceService;
  private final SimulatorReplayControlProperties properties;

  @Autowired
  public SimulatorReplaySourceController(
      SimulatorReplaySourceService sourceService, SimulatorReplayControlProperties properties) {
    this.sourceService = sourceService;
    this.properties = properties;
  }

  /**
   * Lists recordings that have associated tick snapshot data.
   *
   * @param accountName optional filter by account name
   * @param limit max entries to return (default 50)
   * @param offset pagination offset (default 0)
   * @return catalog response with entries and total count
   */
  @GetMapping("/catalog")
  public ResponseEntity<Object> catalog(
      @RequestParam(required = false) String accountName,
      @RequestParam(required = false, defaultValue = "50") int limit,
      @RequestParam(required = false, defaultValue = "0") int offset) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("success", false, "error", "Replay source is disabled"));
    }
    try {
      // Clamp limit to reasonable range
      limit = Math.max(1, Math.min(limit, 200));
      offset = Math.max(0, offset);
      ReplayCatalogResponse response = sourceService.catalog(accountName, limit, offset);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      logger.warn("Replay source catalog request failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("success", false, "error", e.getMessage()));
    }
  }

  /**
   * Retrieves a paginated page of tick frames for a recording.
   *
   * @param recordingId the recording ID (required)
   * @param fromTick starting tick inclusive (default 0)
   * @param limit max frames per page (default 100)
   * @return frame page response with tick data
   */
  @GetMapping("/frames")
  public ResponseEntity<Object> frames(
      @RequestParam String recordingId,
      @RequestParam(required = false, defaultValue = "0") long fromTick,
      @RequestParam(required = false, defaultValue = "100") int limit) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(Map.of("success", false, "error", "Replay source is disabled"));
    }
    if (recordingId == null || recordingId.isBlank()) {
      return ResponseEntity.badRequest()
          .body(Map.of("success", false, "error", "Missing required parameter: recordingId"));
    }
    try {
      // Clamp limit to reasonable range
      limit = Math.max(1, Math.min(limit, 500));
      fromTick = Math.max(0, fromTick);
      ReplayFramePageResponse response = sourceService.frames(recordingId, fromTick, limit);
      return ResponseEntity.ok(response);
    } catch (SimulatorReplayControlException e) {
      logger.warn("Replay source frames request failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.NOT_FOUND)
          .body(Map.of("success", false, "error", e.getMessage()));
    } catch (Exception e) {
      logger.warn("Replay source frames request failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body(Map.of("success", false, "error", e.getMessage()));
    }
  }
}

package com.chromascape.web.simulator;

import com.chromascape.web.simulator.dto.ReplayFramePageResponse;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes replay control endpoints for the ChromaScape web UI.
 *
 * <p>Proxies requests to the OpenRune ReplayControlService HTTP API for controlling in-game replay
 * playback from the browser.
 *
 * <p>Status code mapping:
 *
 * <ul>
 *   <li>Disabled replay-control: HTTP 503
 *   <li>Client validation error (missing/invalid fields): HTTP 400
 *   <li>Upstream domain failure (success=false): HTTP 409
 *   <li>Upstream non-2xx: propagated via SimulatorReplayControlException
 *   <li>Success: HTTP 200
 * </ul>
 */
@RestController
@RequestMapping("/api/simulator/replay/control")
public class SimulatorReplayControlController {

  private static final Logger logger = LogManager.getLogger(SimulatorReplayControlController.class);

  private final SimulatorReplayControlService replayControlService;
  private final SimulatorReplaySourceService replaySourceService;
  private final SimulatorReplayControlProperties properties;

  /**
   * Creates a new SimulatorReplayControlController.
   *
   * @param replayControlService the replay control proxy service
   * @param properties the replay control configuration
   */
  @Autowired
  public SimulatorReplayControlController(
      SimulatorReplayControlService replayControlService,
      SimulatorReplaySourceService replaySourceService,
      SimulatorReplayControlProperties properties) {
    this.replayControlService = replayControlService;
    this.replaySourceService = replaySourceService;
    this.properties = properties;
  }

  /**
   * Gets the current replay playback status for a player.
   *
   * @param player the in-game player name
   * @return the playback status
   */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getStatus(
      @RequestParam(required = false) String player) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      Map<String, Object> result = replayControlService.getStatus(player);
      return ResponseEntity.ok(result);
    } catch (SimulatorReplayControlException e) {
      return handleException(e);
    }
  }

  /** Lists online DEV players available for replay control. */
  @GetMapping("/players")
  public ResponseEntity<Map<String, Object>> getPlayers() {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      Map<String, Object> result = replayControlService.getPlayers();
      return mapSuccessResponse(result);
    } catch (SimulatorReplayControlException e) {
      return handleException(e);
    }
  }

  /**
   * Starts a replay playback session.
   *
   * @param body the request body containing player, sessionId, speed, actorId, recordingId, and
   *     sourceMode
   * @return the start result
   */
  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      String player = optionalString(body, "player");
      String sessionId = (String) body.getOrDefault("sessionId", "latest");
      Double speed = optionalDouble(body, "speed");
      String actorId = (String) body.get("actorId");
      String recordingId = (String) body.get("recordingId");
      String sourceMode = (String) body.get("sourceMode");

      if (recordingId != null && !recordingId.isBlank()) {
        ReplayFramePageResponse preflight = replaySourceService.frames(recordingId, 0, 1);
        if (preflight.getFrames() == null || preflight.getFrames().isEmpty()) {
          return ResponseEntity.status(HttpStatus.CONFLICT)
              .body(
                  errorResponse(
                      "Recording is not replayable: no tick frames found for recordingId="
                          + recordingId));
        }
        ReplayFramePageResponse.FrameData firstFrame = preflight.getFrames().get(0);
        boolean hasPosition =
            firstFrame.getPlayerState() != null
                && ((firstFrame.getPlayerState().has("x") && firstFrame.getPlayerState().has("z"))
                    || (firstFrame.getPlayerState().has("worldX")
                        && firstFrame.getPlayerState().has("worldY")));
        if (!hasPosition) {
          return ResponseEntity.status(HttpStatus.CONFLICT)
              .body(
                  errorResponse(
                      "Recording is not replayable: missing position telemetry in tick snapshots for recordingId="
                          + recordingId));
        }
      }

      Map<String, Object> result =
          replayControlService.start(player, sessionId, speed, actorId, recordingId, sourceMode);
      return mapSuccessResponse(result);
    } catch (SimulatorReplayControlException e) {
      if (e.getMessage() != null && e.getMessage().contains("No debug session found for recordingId")) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(
                errorResponse(
                    "Recording exists but has no tick snapshot session. It cannot be replayed into OpenRune."));
      }
      return handleException(e);
    }
  }

  /**
   * Pauses the active replay playback.
   *
   * @param body the request body containing player
   * @return the pause result
   */
  @PostMapping("/pause")
  public ResponseEntity<Map<String, Object>> pause(@RequestBody Map<String, Object> body) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      Map<String, Object> result = replayControlService.pause(optionalString(body, "player"));
      return mapSuccessResponse(result);
    } catch (SimulatorReplayControlException e) {
      return handleException(e);
    }
  }

  /**
   * Resumes the paused replay playback.
   *
   * @param body the request body containing player
   * @return the resume result
   */
  @PostMapping("/resume")
  public ResponseEntity<Map<String, Object>> resume(@RequestBody Map<String, Object> body) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      Map<String, Object> result = replayControlService.resume(optionalString(body, "player"));
      return mapSuccessResponse(result);
    } catch (SimulatorReplayControlException e) {
      return handleException(e);
    }
  }

  /**
   * Stops the replay playback.
   *
   * @param body the request body containing player
   * @return the stop result
   */
  @PostMapping("/stop")
  public ResponseEntity<Map<String, Object>> stop(@RequestBody Map<String, Object> body) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      Map<String, Object> result = replayControlService.stop(optionalString(body, "player"));
      return mapSuccessResponse(result);
    } catch (SimulatorReplayControlException e) {
      return handleException(e);
    }
  }

  /**
   * Seeks to a specific time in the replay.
   *
   * @param body the request body containing player and seconds
   * @return the seek result
   */
  @PostMapping("/seek")
  public ResponseEntity<Map<String, Object>> seek(@RequestBody Map<String, Object> body) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      String player = optionalString(body, "player");
      double seconds = requiredDouble(body, "seconds");
      Map<String, Object> result = replayControlService.seek(player, seconds);
      return mapSuccessResponse(result);
    } catch (SimulatorReplayControlException e) {
      return handleException(e);
    }
  }

  /**
   * Changes the playback speed.
   *
   * @param body the request body containing player and speed
   * @return the speed change result
   */
  @PostMapping("/speed")
  public ResponseEntity<Map<String, Object>> speed(@RequestBody Map<String, Object> body) {
    if (!properties.isEnabled()) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(disabledResponse());
    }
    try {
      String player = optionalString(body, "player");
      double spd = requiredDouble(body, "speed");
      Map<String, Object> result = replayControlService.setSpeed(player, spd);
      return mapSuccessResponse(result);
    } catch (SimulatorReplayControlException e) {
      return handleException(e);
    }
  }

  /**
   * Maps an upstream result to the appropriate HTTP response. If the upstream returned {@code
   * success=false}, this is treated as a domain-level conflict (409). Otherwise returns 200.
   */
  private static ResponseEntity<Map<String, Object>> mapSuccessResponse(
      Map<String, Object> result) {
    Object success = result.get("success");
    if (success != null && !Boolean.TRUE.equals(success)) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }
    return ResponseEntity.ok(result);
  }

  /**
   * Handles a {@link SimulatorReplayControlException} by mapping it to the appropriate HTTP status.
   *
   * <p>Status mapping:
   *
   * <ul>
   *   <li>Messages starting with "Missing required field" or "Invalid number": 400
   *   <li>Upstream non-2xx with known status: propagate upstream status
   *   <li>All other failures (transport/parse): 502 (bad gateway)
   * </ul>
   */
  private ResponseEntity<Map<String, Object>> handleException(SimulatorReplayControlException e) {
    logger.warn("Replay control request failed: {}", e.getMessage());
    String message = e.getMessage();

    if (message != null
        && (message.startsWith("Missing required field") || message.startsWith("Invalid number"))) {
      return ResponseEntity.badRequest().body(errorResponse(message));
    }

    if (e.getUpstreamStatus() != null) {
      return ResponseEntity.status(e.getUpstreamStatus()).body(errorResponse(message));
    }

    return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse(message));
  }

  private static String requiredString(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new SimulatorReplayControlException("Missing required field: " + key);
    }
    return value.toString();
  }

  private static String optionalString(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private static double requiredDouble(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (value == null) {
      throw new SimulatorReplayControlException("Missing required field: " + key);
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(value.toString());
    } catch (NumberFormatException e) {
      throw new SimulatorReplayControlException("Invalid number for field: " + key);
    }
  }

  private static Double optionalDouble(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (value == null) {
      return null;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Map<String, Object> disabledResponse() {
    Map<String, Object> error = new HashMap<>();
    error.put("success", false);
    error.put("error", "Replay control is disabled");
    return error;
  }

  private static Map<String, Object> errorResponse(String message) {
    Map<String, Object> error = new HashMap<>();
    error.put("success", false);
    error.put("error", message);
    return error;
  }
}

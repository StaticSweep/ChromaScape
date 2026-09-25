package com.chromascape.web.debug.tickdebugger.controller;

import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.websocket.TickStreamWebSocketHandler;
import com.chromascape.web.integration.micro6.service.Micro6LogShipperService;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for debug status and diagnostics.
 *
 * <p>Provides endpoints for monitoring micro6 feed status and other diagnostics.
 */
@RestController
@RequestMapping("/api/debug")
public class DebugStatusController {

  private static final Logger log = LoggerFactory.getLogger(DebugStatusController.class);

  private final DebugSessionService sessionService;
  private final TickStreamWebSocketHandler tickStreamWebSocketHandler;
  private final Micro6LogShipperService logShipperService;

  public DebugStatusController(
      DebugSessionService sessionService,
      TickStreamWebSocketHandler tickStreamWebSocketHandler,
      Micro6LogShipperService logShipperService) {
    this.sessionService = sessionService;
    this.tickStreamWebSocketHandler = tickStreamWebSocketHandler;
    this.logShipperService = logShipperService;
  }

  /**
   * Get micro6 feed status for diagnostics.
   *
   * <p>Returns connection status and last tick received.
   *
   * @return Response with feed status
   */
  @GetMapping("/feed-status")
  public ResponseEntity<Map<String, Object>> getFeedStatus() {
    try {
      Map<String, Object> status = new HashMap<>();

      // Check if we have any active sessions (indicates micro6 is connected)
      boolean connected = !sessionService.getActiveSessions().isEmpty();
      status.put("connected", connected);
      status.put("activeSessionCount", sessionService.getActiveSessions().size());

      // Get last tick from latest active session if available
      if (connected) {
        var activeSessions = sessionService.getActiveSessions();
        if (!activeSessions.isEmpty()) {
          var latestSession = activeSessions.get(0);
          status.put("lastTickReceived", latestSession.getEndTick());
          status.put("sessionId", latestSession.getSessionId());
        }
      }

      status.put("logShipperEnabled", logShipperService.isRunning());
      status.put("logShipperSource", logShipperService.getSourceName());

      return ResponseEntity.ok(status);
    } catch (Exception e) {
      log.error("Error getting feed status", e);
      return ResponseEntity.status(500).build();
    }
  }

  /**
   * Control Micro6 tick feed (pause/resume).
   *
   * @param request payload with "enabled" boolean
   * @return response with result
   */
  @PostMapping("/feed-control")
  public ResponseEntity<Map<String, Object>> controlFeed(@RequestBody Map<String, Object> request) {
    Map<String, Object> response = new HashMap<>();
    try {
      Object enabledValue = request.get("enabled");
      boolean enabled = enabledValue instanceof Boolean && (Boolean) enabledValue;
      int notified = tickStreamWebSocketHandler.broadcastFeedControl(enabled);
      response.put("enabled", enabled);
      response.put("clientsNotified", notified);
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      log.error("Error controlling feed", e);
      response.put("error", "Failed to control feed");
      return ResponseEntity.status(500).body(response);
    }
  }
}

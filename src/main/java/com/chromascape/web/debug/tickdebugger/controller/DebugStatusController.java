package com.chromascape.web.debug.tickdebugger.controller;

import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

  public DebugStatusController(DebugSessionService sessionService) {
    this.sessionService = sessionService;
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

      // Placeholder values for log shipper (can be enhanced later)
      status.put("logShipperEnabled", false);
      status.put("logShipperSource", "micro6");

      return ResponseEntity.ok(status);
    } catch (Exception e) {
      log.error("Error getting feed status", e);
      return ResponseEntity.status(500).build();
    }
  }
}

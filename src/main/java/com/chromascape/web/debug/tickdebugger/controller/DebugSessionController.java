package com.chromascape.web.debug.tickdebugger.controller;

import com.chromascape.web.debug.tickdebugger.dto.DebugSessionSummary;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartRequest;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.dto.SessionStopRequest;
import com.chromascape.web.debug.tickdebugger.entity.DebugSession;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for debug session management.
 *
 * <p>Provides endpoints for starting, stopping, and listing debug sessions.
 */
@RestController
@RequestMapping("/api/debug/sessions")
public class DebugSessionController {

  private static final Logger log = LoggerFactory.getLogger(DebugSessionController.class);

  private final DebugSessionService sessionService;

  public DebugSessionController(DebugSessionService sessionService) {
    this.sessionService = sessionService;
  }

  /**
   * Start a new debug session.
   *
   * @param request the session start request
   * @return response with session ID and optional recording ID
   */
  @PostMapping("/start")
  public ResponseEntity<SessionStartResponse> startSession(
      @RequestBody SessionStartRequest request) {
    try {
      log.info(
          "Starting debug session: account={}, script={}, recording={}",
          request.getAccountName(),
          request.getScriptName(),
          request.getRecordingEnabled());

      SessionStartResponse response =
          sessionService
              .startSessionWithOutcome(
                  request.getAccountName(),
                  request.getScriptName(),
                  request.getRecordingEnabled(),
                  request.getTriggerSource(),
                  request.getPreferredSessionId(),
                  request.getTaskKey(),
                  request.getTaskArgs(),
                  request.getRunContext())
              .response();

      return ResponseEntity.ok(response);

    } catch (com.chromascape.web.recording.provenance.ProvenanceException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error starting debug session: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Stop an active debug session.
   *
   * @param request the session stop request
   * @return 200 on success
   */
  @PostMapping("/stop")
  public ResponseEntity<Void> stopSession(@RequestBody SessionStopRequest request) {
    try {
      log.info("Stopping debug session: sessionId={}", request.getSessionId());

      sessionService.stopSession(request.getSessionId(), request.getEndTick());
      return ResponseEntity.ok().build();

    } catch (Exception e) {
      log.error("Error stopping debug session: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * List all debug sessions, optionally filtered by account.
   *
   * @param accountName optional account name filter
   * @return list of session summaries
   */
  @GetMapping
  public ResponseEntity<List<DebugSessionSummary>> listSessions(
      @RequestParam(required = false) String accountName) {

    try {
      List<DebugSession> sessions;
      if (accountName != null && !accountName.isEmpty()) {
        sessions = sessionService.getSessionsByAccount(accountName);
      } else {
        sessions = sessionService.getAllSessions();
      }

      List<DebugSessionSummary> summaries =
          sessions.stream().map(sessionService::toSummary).collect(Collectors.toList());

      return ResponseEntity.ok(summaries);

    } catch (Exception e) {
      log.error("Error listing debug sessions: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get all active (running) debug sessions.
   *
   * @return list of active session summaries
   */
  @GetMapping("/active")
  public ResponseEntity<List<DebugSessionSummary>> getActiveSessions() {
    try {
      List<DebugSession> sessions = sessionService.getActiveSessions();

      List<DebugSessionSummary> summaries =
          sessions.stream().map(sessionService::toSummary).collect(Collectors.toList());

      return ResponseEntity.ok(summaries);

    } catch (Exception e) {
      log.error("Error getting active debug sessions: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get details for a specific debug session.
   *
   * @param sessionId the session ID
   * @return session details or 404 if not found
   */
  @GetMapping("/{sessionId}")
  public ResponseEntity<DebugSessionSummary> getSession(@PathVariable String sessionId) {
    try {
      return sessionService
          .getSession(sessionId)
          .map(session -> ResponseEntity.ok(sessionService.toSummary(session)))
          .orElse(ResponseEntity.notFound().build());

    } catch (Exception e) {
      log.error("Error getting debug session: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}

package com.chromascape.web.debug.tickdebugger.controller;

import com.chromascape.web.debug.tickdebugger.dto.TickBatchResponse;
import com.chromascape.web.debug.tickdebugger.dto.TickDataResponse;
import com.chromascape.web.debug.tickdebugger.entity.DebugSessionRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickLog;
import com.chromascape.web.debug.tickdebugger.entity.TickLogRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlay;
import com.chromascape.web.debug.tickdebugger.entity.TickOverlayRepository;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshot;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarker;
import com.chromascape.web.debug.tickdebugger.entity.TimelineMarkerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for tick replay data queries.
 *
 * <p>Provides endpoints for fetching tick data (snapshots, logs, overlays) for replay
 * functionality.
 */
@RestController
@RequestMapping("/api/debug/replay")
public class TickReplayController {

  private static final Logger log = LoggerFactory.getLogger(TickReplayController.class);

  private final DebugSessionRepository sessionRepository;
  private final TickSnapshotRepository snapshotRepository;
  private final TickLogRepository logRepository;
  private final TickOverlayRepository overlayRepository;
  private final TimelineMarkerRepository markerRepository;
  private final ObjectMapper objectMapper;

  public TickReplayController(
      DebugSessionRepository sessionRepository,
      TickSnapshotRepository snapshotRepository,
      TickLogRepository logRepository,
      TickOverlayRepository overlayRepository,
      TimelineMarkerRepository markerRepository,
      ObjectMapper objectMapper) {
    this.sessionRepository = sessionRepository;
    this.snapshotRepository = snapshotRepository;
    this.logRepository = logRepository;
    this.overlayRepository = overlayRepository;
    this.markerRepository = markerRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Get tick range data for a session.
   *
   * @param sessionId the session ID
   * @param start optional start tick
   * @param end optional end tick
   * @return tick data for the range
   */
  @GetMapping("/ticks/{sessionId}")
  public ResponseEntity<TickBatchResponse> getTickRange(
      @PathVariable String sessionId,
      @RequestParam(required = false) Long start,
      @RequestParam(required = false) Long end) {

    try {
      // Verify session exists
      if (sessionRepository.findBySessionId(sessionId).isEmpty()) {
        return ResponseEntity.notFound().build();
      }

      List<TickSnapshot> snapshots;
      List<TickLog> logs;
      List<TickOverlay> overlays;

      if (start != null && end != null) {
        snapshots =
            snapshotRepository.findBySessionIdAndTickBetweenOrderByTick(sessionId, start, end);
        logs = logRepository.findBySessionIdAndTickBetweenOrderByTickAsc(sessionId, start, end);
        overlays =
            overlayRepository.findBySessionIdAndTickBetweenOrderByTickAsc(sessionId, start, end);
      } else {
        snapshots = snapshotRepository.findBySessionIdOrderByTick(sessionId);
        logs = logRepository.findBySessionIdOrderByTickAsc(sessionId);
        overlays = overlayRepository.findBySessionIdOrderByTickAsc(sessionId);
      }

      TickBatchResponse response = new TickBatchResponse();
      response.setSnapshots(snapshots);
      response.setLogs(logs);
      response.setOverlays(overlays);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting tick range: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get data for a specific tick.
   *
   * @param sessionId the session ID
   * @param tick the tick number
   * @return tick data for the specified tick
   */
  @GetMapping("/tick/{sessionId}/{tick}")
  public ResponseEntity<TickDataResponse> getTick(
      @PathVariable String sessionId, @PathVariable Long tick) {

    try {
      // Verify session exists
      if (sessionRepository.findBySessionId(sessionId).isEmpty()) {
        return ResponseEntity.notFound().build();
      }

      TickDataResponse response = new TickDataResponse();
      response.setTick(tick);

      snapshotRepository.findBySessionIdAndTick(sessionId, tick).ifPresent(response::setSnapshot);

      response.setLogs(logRepository.findBySessionIdAndTick(sessionId, tick));
      response.setOverlays(overlayRepository.findBySessionIdAndTick(sessionId, tick));

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error getting tick data: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Search logs with filters.
   *
   * @param sessionId the session ID
   * @param subsystem optional subsystem filter
   * @param level optional severity level filter
   * @param search optional search term
   * @return filtered log entries
   */
  @GetMapping("/logs/{sessionId}")
  public ResponseEntity<List<TickLog>> searchLogs(
      @PathVariable String sessionId,
      @RequestParam(required = false) String subsystem,
      @RequestParam(required = false) String level,
      @RequestParam(required = false) String search) {

    try {
      List<TickLog> logs;

      if (subsystem != null && !subsystem.isEmpty()) {
        logs =
            logRepository.findBySessionIdAndSubsystemIgnoreCaseOrderByTickAsc(sessionId, subsystem);
      } else if (level != null && !level.isEmpty()) {
        logs = logRepository.findBySessionIdAndSeverityIgnoreCaseOrderByTickAsc(sessionId, level);
      } else if (search != null && !search.isEmpty()) {
        logs =
            logRepository.findBySessionIdAndMessageContainingIgnoreCaseOrderByTickAsc(
                sessionId, search);
      } else {
        logs = logRepository.findBySessionIdOrderByTickAsc(sessionId);
      }

      return ResponseEntity.ok(logs);

    } catch (Exception e) {
      log.error("Error searching logs: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get timeline markers for a session.
   *
   * @param sessionId the session ID
   * @return list of timeline markers
   */
  @GetMapping("/timeline-markers/{sessionId}")
  public ResponseEntity<List<TimelineMarker>> getTimelineMarkers(@PathVariable String sessionId) {
    try {
      List<TimelineMarker> markers = markerRepository.findBySessionIdOrderByTickAsc(sessionId);
      return ResponseEntity.ok(markers);

    } catch (Exception e) {
      log.error("Error getting timeline markers: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Get session info for replay (tick range, etc).
   *
   * @param sessionId the session ID
   * @return session info
   */
  @GetMapping("/session-info/{sessionId}")
  public ResponseEntity<SessionInfo> getSessionInfo(@PathVariable String sessionId) {
    try {
      return sessionRepository
          .findBySessionId(sessionId)
          .map(
              session -> {
                SessionInfo info = new SessionInfo();
                info.sessionId = session.getSessionId();
                info.startTick = session.getStartTick();
                info.endTick = session.getEndTick();
                info.scriptName = session.getScriptName();

                snapshotRepository
                    .findMinTickBySessionId(sessionId)
                    .ifPresent(min -> info.minTick = min);
                snapshotRepository
                    .findMaxTickBySessionId(sessionId)
                    .ifPresent(max -> info.maxTick = max);

                return ResponseEntity.ok(info);
              })
          .orElse(ResponseEntity.notFound().build());

    } catch (Exception e) {
      log.error("Error getting session info: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /** Response DTO for tick range queries. */
  public static class TickBatchResponse {
    private List<TickSnapshot> snapshots;
    private List<TickLog> logs;
    private List<TickOverlay> overlays;

    public List<TickSnapshot> getSnapshots() {
      return snapshots;
    }

    public void setSnapshots(List<TickSnapshot> snapshots) {
      this.snapshots = snapshots;
    }

    public List<TickLog> getLogs() {
      return logs;
    }

    public void setLogs(List<TickLog> logs) {
      this.logs = logs;
    }

    public List<TickOverlay> getOverlays() {
      return overlays;
    }

    public void setOverlays(List<TickOverlay> overlays) {
      this.overlays = overlays;
    }
  }

  /** Response DTO for single tick queries. */
  public static class TickDataResponse {
    private Long tick;
    private TickSnapshot snapshot;
    private List<TickLog> logs;
    private List<TickOverlay> overlays;

    public Long getTick() {
      return tick;
    }

    public void setTick(Long tick) {
      this.tick = tick;
    }

    public TickSnapshot getSnapshot() {
      return snapshot;
    }

    public void setSnapshot(TickSnapshot snapshot) {
      this.snapshot = snapshot;
    }

    public List<TickLog> getLogs() {
      return logs;
    }

    public void setLogs(List<TickLog> logs) {
      this.logs = logs;
    }

    public List<TickOverlay> getOverlays() {
      return overlays;
    }

    public void setOverlays(List<TickOverlay> overlays) {
      this.overlays = overlays;
    }
  }

  /** Response DTO for session info. */
  public static class SessionInfo {
    public String sessionId;
    public String scriptName;
    public Long startTick;
    public Long endTick;
    public Long minTick;
    public Long maxTick;
  }
}

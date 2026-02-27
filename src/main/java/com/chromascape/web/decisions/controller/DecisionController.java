package com.chromascape.web.decisions.controller;

import com.chromascape.web.debug.DebugStateStore;
import com.chromascape.web.decisions.entity.DecisionEntity;
import com.chromascape.web.decisions.entity.DecisionRepository;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for script decision recording and retrieval.
 *
 * <p>Provides endpoints to record script decisions with success/failure status, retrieve decisions
 * by recording session, and support AI prompt generation.
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

  private static final Logger logger = LogManager.getLogger(DecisionController.class);

  private final DecisionRepository decisionRepository;
  private final RecordingSessionRepository recordingSessionRepository;
  private final DebugStateStore debugStateStore;

  @Autowired
  public DecisionController(
      DecisionRepository decisionRepository,
      RecordingSessionRepository recordingSessionRepository,
      DebugStateStore debugStateStore) {
    this.decisionRepository = decisionRepository;
    this.recordingSessionRepository = recordingSessionRepository;
    this.debugStateStore = debugStateStore;
  }

  /**
   * Records a decision event from the client.
   *
   * @param request Decision data from DebugRecorderPlugin
   * @return Response with created decision ID
   */
  @PostMapping("")
  public ResponseEntity<Map<String, Object>> recordDecision(@RequestBody DecisionRequest request) {
    try {
      DecisionEntity decision = new DecisionEntity();
      decision.setScriptName(request.getScriptName());
      decision.setDecisionType(request.getDecisionType());
      decision.setDecisionReason(request.getDecisionReason());
      decision.setSuccess(request.getSuccess());
      decision.setFailureCode(request.getFailureCode());
      decision.setFailureReason(request.getFailureReason());
      decision.setScreenCoords(request.getScreenCoords());
      decision.setTargetEntity(request.getTargetEntity());
      decision.setGameState(request.getGameState());
      decision.setTimestamp(
          request.getTimestamp() != null ? request.getTimestamp() : LocalDateTime.now());

      // Link to recording session if provided
      if (request.getRecordingSessionId() != null) {
        decision.setRecordingSessionId(request.getRecordingSessionId());
      } else {
        // Try to link to active recording session
        List<RecordingSession> activeSessions = recordingSessionRepository.findActiveRecordings();
        if (!activeSessions.isEmpty()) {
          decision.setRecordingSessionId(activeSessions.get(0).getId());
        }
      }

      DecisionEntity saved = decisionRepository.save(decision);
      debugStateStore.recordDecision(saved);

      Map<String, Object> response = new HashMap<>();
      response.put("id", saved.getId());
      response.put("status", "recorded");
      response.put("timestamp", saved.getTimestamp());

      logger.debug("Recorded decision: {} for script: {}", saved.getId(), request.getScriptName());
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to record decision: {}", e.getMessage());
      Map<String, Object> response = new HashMap<>();
      response.put("status", "error");
      response.put("message", e.getMessage());
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
  }

  /**
   * Gets all decisions for a specific recording session.
   *
   * @param sessionId The recording session ID
   * @return List of decisions ordered by timestamp
   */
  @GetMapping("/recording/{sessionId}")
  public ResponseEntity<List<Map<String, Object>>> getDecisionsBySession(
      @PathVariable Long sessionId) {
    try {
      List<DecisionEntity> decisions =
          decisionRepository.findByRecordingSessionIdOrderByTimestampAsc(sessionId);

      List<Map<String, Object>> response = decisions.stream().map(this::entityToMap).toList();

      logger.info("Returned {} decisions for session: {}", response.size(), sessionId);
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to get decisions for session: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Gets a specific decision by ID with full context.
   *
   * @param decisionId The decision ID
   * @return Decision details or 404
   */
  @GetMapping("/{decisionId}")
  public ResponseEntity<Map<String, Object>> getDecision(@PathVariable Long decisionId) {
    try {
      return decisionRepository
          .findById(decisionId)
          .map(decision -> ResponseEntity.ok(entityToMap(decision)))
          .orElse(ResponseEntity.notFound().build());
    } catch (Exception e) {
      logger.error("Failed to get decision: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Gets recent decisions before a given timestamp for history context. Used for AI prompt
   * generation.
   *
   * @param sessionId Recording session ID
   * @param timestamp Timestamp to look back from
   * @param count Number of decisions to return (default 5, max 20)
   * @return List of recent decisions
   */
  @GetMapping("/history/before")
  public ResponseEntity<List<Map<String, Object>>> getDecisionHistory(
      @RequestParam Long sessionId,
      @RequestParam String timestamp,
      @RequestParam(defaultValue = "5") int count) {

    try {
      LocalDateTime ts = LocalDateTime.parse(timestamp);
      int limit = Math.min(count, 20);

      List<DecisionEntity> history =
          decisionRepository.findRecentDecisionsBeforeTimestamp(sessionId, ts).stream()
              .limit(limit)
              .toList();

      List<Map<String, Object>> response = history.stream().map(this::entityToMap).toList();

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to get decision history: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Gets all failed decisions across all sessions.
   *
   * @return List of failed decisions ordered by timestamp
   */
  @GetMapping("/failures")
  public ResponseEntity<List<Map<String, Object>>> getFailures() {
    try {
      List<DecisionEntity> failures = decisionRepository.findBySuccessFalseOrderByTimestampDesc();

      List<Map<String, Object>> response = failures.stream().map(this::entityToMap).toList();

      logger.info("Returned {} failed decisions", response.size());
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to get failures: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Gets failed decisions for a specific recording session.
   *
   * @param sessionId Recording session ID
   * @return List of failed decisions
   */
  @GetMapping("/recording/{sessionId}/failures")
  public ResponseEntity<List<Map<String, Object>>> getSessionFailures(
      @PathVariable Long sessionId) {
    try {
      List<DecisionEntity> failures =
          decisionRepository.findByRecordingSessionIdAndSuccessFalseOrderByTimestampAsc(sessionId);

      List<Map<String, Object>> response = failures.stream().map(this::entityToMap).toList();

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      logger.error("Failed to get session failures: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Gets decision statistics for a recording session.
   *
   * @param sessionId Recording session ID
   * @return Statistics map with counts and percentages
   */
  @GetMapping("/recording/{sessionId}/stats")
  public ResponseEntity<Map<String, Object>> getSessionStats(@PathVariable Long sessionId) {
    try {
      long totalDecisions = decisionRepository.countByRecordingSessionId(sessionId);
      long failedDecisions = decisionRepository.countByRecordingSessionIdAndSuccessFalse(sessionId);

      Map<String, Object> stats = new HashMap<>();
      stats.put("totalDecisions", totalDecisions);
      stats.put("failedDecisions", failedDecisions);
      stats.put("successDecisions", totalDecisions - failedDecisions);

      if (totalDecisions > 0) {
        double successRate = ((double) (totalDecisions - failedDecisions) / totalDecisions) * 100;
        stats.put("successRate", successRate);
      } else {
        stats.put("successRate", 0.0);
      }

      return ResponseEntity.ok(stats);

    } catch (Exception e) {
      logger.error("Failed to get session stats: {}", e.getMessage());
      return ResponseEntity.internalServerError().build();
    }
  }

  /** Converts DecisionEntity to map for JSON response. */
  private Map<String, Object> entityToMap(DecisionEntity decision) {
    Map<String, Object> map = new HashMap<>();
    map.put("id", decision.getId());
    map.put("recordingSessionId", decision.getRecordingSessionId());
    map.put("scriptName", decision.getScriptName());
    map.put("decisionType", decision.getDecisionType());
    map.put("decisionReason", decision.getDecisionReason());
    map.put("success", decision.getSuccess());
    map.put("failureCode", decision.getFailureCode());
    map.put("failureReason", decision.getFailureReason());
    map.put("screenCoords", decision.getScreenCoords());
    map.put("targetEntity", decision.getTargetEntity());
    map.put("gameState", decision.getGameState());
    map.put("timestamp", decision.getTimestamp());
    return map;
  }

  /** Request DTO for recording decisions. */
  public static class DecisionRequest {
    private Long recordingSessionId;
    private String scriptName;
    private DecisionEntity.DecisionType decisionType;
    private String decisionReason;
    private Boolean success;
    private String failureCode;
    private String failureReason;
    private String screenCoords;
    private String targetEntity;
    private String gameState;
    private LocalDateTime timestamp;

    // Getters and Setters
    public Long getRecordingSessionId() {
      return recordingSessionId;
    }

    public void setRecordingSessionId(Long recordingSessionId) {
      this.recordingSessionId = recordingSessionId;
    }

    public String getScriptName() {
      return scriptName;
    }

    public void setScriptName(String scriptName) {
      this.scriptName = scriptName;
    }

    public DecisionEntity.DecisionType getDecisionType() {
      return decisionType;
    }

    public void setDecisionType(DecisionEntity.DecisionType decisionType) {
      this.decisionType = decisionType;
    }

    public String getDecisionReason() {
      return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
      this.decisionReason = decisionReason;
    }

    public Boolean getSuccess() {
      return success;
    }

    public void setSuccess(Boolean success) {
      this.success = success;
    }

    public String getFailureCode() {
      return failureCode;
    }

    public void setFailureCode(String failureCode) {
      this.failureCode = failureCode;
    }

    public String getFailureReason() {
      return failureReason;
    }

    public void setFailureReason(String failureReason) {
      this.failureReason = failureReason;
    }

    public String getScreenCoords() {
      return screenCoords;
    }

    public void setScreenCoords(String screenCoords) {
      this.screenCoords = screenCoords;
    }

    public String getTargetEntity() {
      return targetEntity;
    }

    public void setTargetEntity(String targetEntity) {
      this.targetEntity = targetEntity;
    }

    public String getGameState() {
      return gameState;
    }

    public void setGameState(String gameState) {
      this.gameState = gameState;
    }

    public LocalDateTime getTimestamp() {
      return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
      this.timestamp = timestamp;
    }
  }
}

package com.chromascape.web.decisions.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA entity for storing script decision events in the database.
 *
 * <p>Tracks individual decisions made by scripts with success/failure status and full context for
 * AI-powered debugging and replay analysis.
 */
@Entity
@Table(name = "decisions")
public class DecisionEntity {

  /** Type of decision made by the script. */
  public enum DecisionType {
    /** Attack or combat action */
    ATTACK,
    /** Move to location */
    MOVE,
    /** Interact with object/npc */
    INTERACT,
    /** Wait for condition */
    WAIT,
    /** Bank or inventory operation */
    BANK,
    /** Camera movement */
    CAMERA,
    /** Other decision type */
    OTHER
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The recording session this decision belongs to (null if not recording). */
  @Column(name = "recording_session_id")
  private Long recordingSessionId;

  /** Script name that made this decision. */
  @Column(nullable = false, length = 100)
  private String scriptName;

  /** The type of decision made. */
  @Column(nullable = false, length = 20)
  @Enumerated(EnumType.STRING)
  private DecisionType decisionType;

  /** Human-readable reason for the decision. */
  @Column(length = 500)
  private String decisionReason;

  /** Whether the decision executed successfully. */
  @Column(nullable = false)
  private Boolean success;

  /** Failure code if success=false. */
  @Column(length = 50)
  private String failureCode;

  /** Detailed failure reason if success=false. */
  @Column(length = 1000)
  private String failureReason;

  /** Screen coordinates as JSON {"x": 540, "y": 360}. */
  @Column(length = 100)
  private String screenCoords;

  /** Target entity info as JSON {"id": 123, "name": "Goblin"}. */
  @Column(length = 500)
  private String targetEntity;

  /** Game state snapshot as JSON. */
  @Column(columnDefinition = "TEXT")
  private String gameState;

  /** When the decision was made. */
  @Column(nullable = false)
  private LocalDateTime timestamp;

  /** Default constructor required by JPA. */
  public DecisionEntity() {}

  /** Constructor for creating decision events. */
  public DecisionEntity(
      String scriptName,
      DecisionType decisionType,
      String decisionReason,
      Boolean success,
      String failureCode,
      String failureReason,
      String screenCoords,
      String targetEntity,
      String gameState,
      LocalDateTime timestamp) {
    this.scriptName = scriptName;
    this.decisionType = decisionType;
    this.decisionReason = decisionReason;
    this.success = success;
    this.failureCode = failureCode;
    this.failureReason = failureReason;
    this.screenCoords = screenCoords;
    this.targetEntity = targetEntity;
    this.gameState = gameState;
    this.timestamp = timestamp;
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

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

  public DecisionType getDecisionType() {
    return decisionType;
  }

  public void setDecisionType(DecisionType decisionType) {
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

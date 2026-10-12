package com.chromascape.web.debug.tickdebugger.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA entity for managing debug session lifecycle with tick-based tracking.
 *
 * <p>Each debug session represents a bot run with associated tick data, logs, state snapshots, and
 * optional video recording linkage.
 */
@Entity
@Table(
    name = "debug_sessions",
    indexes = {
      @Index(name = "idx_debug_sessions_recording_id", columnList = "recording_id"),
      @Index(name = "idx_debug_sessions_start_time", columnList = "start_time")
    })
public class DebugSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Unique session identifier (e.g., "debug_1234567890"). */
  @Column(nullable = false, unique = true, length = 100)
  private String sessionId;

  /** Account name associated with this debug session. */
  @Column(length = 100)
  private String accountName;

  /** Script name being run during this debug session. */
  @Column(length = 100)
  private String scriptName;

  /** When the debug session started. */
  @Column(nullable = false)
  private LocalDateTime startTime;

  /** When the debug session ended (null if still active). */
  private LocalDateTime endTime;

  /** The game tick number when this session started. */
  @Column(nullable = false)
  private Long startTick;

  /** The game tick number when this session ended (null if still active). */
  private Long endTick;

  /** Optional link to a video recording session. */
  @Column(length = 100)
  private String recordingId;

  /** When this record was created. */
  @Column(nullable = false)
  private LocalDateTime createdAt;

  /** Current lifecycle state of this session (nullable for backward compatibility). */
  @Enumerated(EnumType.STRING)
  @Column(length = 20)
  private DebugSessionLifecycleState lifecycleState;

  /** Failure code if session failed (null for successful sessions). */
  @Column(length = 50)
  private String failureCode;

  /** Human-readable failure reason (null for successful sessions). */
  @Column(length = 500)
  private String failureReason;

  /** Additional failure context (e.g., exception message, stack trace summary). */
  @Column(length = 2000)
  private String failureContext;

  /** Timestamp of last lifecycle state change (nullable for backward compatibility). */
  @Column private LocalDateTime lastStateAt;

  /** Default constructor required by JPA. */
  public DebugSession() {}

  /** Constructor for creating debug sessions. */
  public DebugSession(
      String sessionId,
      String accountName,
      String scriptName,
      LocalDateTime startTime,
      Long startTick) {
    this.sessionId = sessionId;
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.startTime = startTime;
    this.startTick = startTick;
    this.createdAt = LocalDateTime.now();
    this.lifecycleState = DebugSessionLifecycleState.STARTING;
    this.lastStateAt = LocalDateTime.now();
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getAccountName() {
    return accountName;
  }

  public void setAccountName(String accountName) {
    this.accountName = accountName;
  }

  public String getScriptName() {
    return scriptName;
  }

  public void setScriptName(String scriptName) {
    this.scriptName = scriptName;
  }

  public LocalDateTime getStartTime() {
    return startTime;
  }

  public void setStartTime(LocalDateTime startTime) {
    this.startTime = startTime;
  }

  public LocalDateTime getEndTime() {
    return endTime;
  }

  public void setEndTime(LocalDateTime endTime) {
    this.endTime = endTime;
  }

  public Long getStartTick() {
    return startTick;
  }

  public void setStartTick(Long startTick) {
    this.startTick = startTick;
  }

  public Long getEndTick() {
    return endTick;
  }

  public void setEndTick(Long endTick) {
    this.endTick = endTick;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  /** Returns true if this session is still active (no end time set). */
  public boolean isActive() {
    return endTime == null;
  }

  /** Terminal sessions never accept new evidence, even if legacy end-time data is absent. */
  public boolean acceptsTickEvidence() {
    return isActive()
        && lifecycleState != DebugSessionLifecycleState.FINALIZED
        && lifecycleState != DebugSessionLifecycleState.FAILED;
  }

  public DebugSessionLifecycleState getLifecycleState() {
    return lifecycleState;
  }

  public void setLifecycleState(DebugSessionLifecycleState lifecycleState) {
    this.lifecycleState = lifecycleState;
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

  public String getFailureContext() {
    return failureContext;
  }

  public void setFailureContext(String failureContext) {
    this.failureContext = failureContext;
  }

  public LocalDateTime getLastStateAt() {
    return lastStateAt;
  }

  public void setLastStateAt(LocalDateTime lastStateAt) {
    this.lastStateAt = lastStateAt;
  }

  /**
   * Transition to a new lifecycle state with timestamp update.
   *
   * @param newState the target state
   */
  public void transitionTo(DebugSessionLifecycleState newState) {
    this.lifecycleState = newState;
    this.lastStateAt = LocalDateTime.now();
  }

  /**
   * Mark this session as failed with error details.
   *
   * @param code standardized failure code
   * @param reason human-readable failure reason
   * @param context additional context (e.g., exception message)
   */
  public void markFailed(String code, String reason, String context) {
    this.lifecycleState = DebugSessionLifecycleState.FAILED;
    this.failureDetails(code, reason, context);
    this.lastStateAt = LocalDateTime.now();
  }

  /**
   * Record failure diagnostics without transitioning the session out of its current lifecycle
   * state.
   *
   * <p>Used when a non-fatal subsystem failure (e.g., video recording start) should degrade the
   * session to tick-only operation while keeping it in RECORDING so tick ingestion and playback
   * continue.
   *
   * @param code standardized failure code
   * @param reason human-readable failure reason
   * @param context additional context (e.g., exception message)
   */
  public void recordFailureDiagnostic(String code, String reason, String context) {
    this.failureDetails(code, reason, context);
    this.lastStateAt = LocalDateTime.now();
  }

  private void failureDetails(String code, String reason, String context) {
    this.failureCode = code;
    // Limit reason to 500 chars to match DB column limit
    this.failureReason =
        reason != null && reason.length() > 500 ? reason.substring(0, 497) + "..." : reason;
    // Limit context to first 1500 chars to prevent bloat
    this.failureContext =
        context != null && context.length() > 1500 ? context.substring(0, 1500) + "..." : context;
  }

  /**
   * Get lifecycle state with fallback to RECORDING for null values (legacy sessions).
   *
   * @return lifecycle state or RECORDING if null
   */
  public DebugSessionLifecycleState getLifecycleStateOrDefault() {
    return lifecycleState != null ? lifecycleState : DebugSessionLifecycleState.RECORDING;
  }
}

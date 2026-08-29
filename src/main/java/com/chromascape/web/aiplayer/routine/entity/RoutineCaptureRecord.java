package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "routine_capture_records", indexes = {
    @Index(name = "idx_routine_capture_session", columnList = "session_id", unique = true),
    @Index(name = "idx_routine_capture_recording", columnList = "recording_id", unique = true)
})
public class RoutineCaptureRecord {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  /** Optimistic lock: multi-instance capture acceptance no longer relies on JVM synchronized. */
  @Version private Long version;
  @Column(name = "session_id", nullable = false, unique = true, length = 160) private String sessionId;
  @Column(name = "start_idempotency_key", nullable = false, unique = true, length = 240) private String startIdempotencyKey;
  @Column(name = "start_request_hash", nullable = false, length = 71) private String startRequestHash;
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(name = "identity_json", nullable = false) private String identityJson;
  @Column(nullable = false) private Instant startedAt;
  @Column(nullable = false) private long startTick;
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false) private String ticksJson = "[]";
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false) private String actionsJson = "[]";
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false) private String tickSequencesJson = "{}";
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false) private String actionSequencesJson = "{}";
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false) private String tickIdempotencyJson = "{}";
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false) private String actionIdempotencyJson = "{}";
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(nullable = false) private String requestIdempotencyJson = "{}";
  @Column(nullable = false) private int nextTickSequence = 1;
  @Column(nullable = false) private int nextActionSequence = 1;
  @Column(nullable = false) private boolean completed;
  private Integer finalTick;
  @Column(name = "recording_id", unique = true, length = 160) private String recordingId;
  @Column(name = "recording_hash", length = 71) private String recordingHash;
  @Column(name = "video_sha256", length = 71) private String videoSha256;
  @Column(name = "completion_request_hash", length = 71) private String completionRequestHash;
  @Column(name = "debug_session_id", length = 160) private String debugSessionId;
  @Column(name = "video_path", length = 500) private String videoPath;
  @Column(name = "completion_status", nullable = false, length = 20) private String completionStatus = "STARTING";
  @Column(name = "completion_error", length = 500) private String completionError;
  @Column(name = "operation", length = 30) private String operation;
  @Column(name = "operation_state", length = 20) private String operationState;
  @Column(name = "operation_request_hash", length = 71) private String operationRequestHash;
  @Column(name = "operation_correlation_id", length = 160) private String operationCorrelationId;
  @Column(name = "operation_attempts", nullable = false) private int operationAttempts = 0;

  public Long getId() { return id; }
  public String getSessionId() { return sessionId; }
  public void setSessionId(String value) { sessionId = value; }
  public String getStartIdempotencyKey() { return startIdempotencyKey; }
  public void setStartIdempotencyKey(String value) { startIdempotencyKey = value; }
  public String getStartRequestHash() { return startRequestHash; }
  public void setStartRequestHash(String value) { startRequestHash = value; }
  public String getIdentityJson() { return identityJson; }
  public void setIdentityJson(String value) { identityJson = value; }
  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant value) { startedAt = value; }
  public long getStartTick() { return startTick; }
  public void setStartTick(long value) { startTick = value; }
  public String getTicksJson() { return ticksJson; }
  public void setTicksJson(String value) { ticksJson = value; }
  public String getActionsJson() { return actionsJson; }
  public void setActionsJson(String value) { actionsJson = value; }
  public String getTickSequencesJson() { return tickSequencesJson; }
  public void setTickSequencesJson(String value) { tickSequencesJson = value; }
  public String getActionSequencesJson() { return actionSequencesJson; }
  public void setActionSequencesJson(String value) { actionSequencesJson = value; }
  public String getTickIdempotencyJson() { return tickIdempotencyJson; }
  public void setTickIdempotencyJson(String value) { tickIdempotencyJson = value; }
  public String getActionIdempotencyJson() { return actionIdempotencyJson; }
  public void setActionIdempotencyJson(String value) { actionIdempotencyJson = value; }
  public String getRequestIdempotencyJson() { return requestIdempotencyJson; }
  public void setRequestIdempotencyJson(String value) { requestIdempotencyJson = value; }
  public int getNextTickSequence() { return nextTickSequence; }
  public void setNextTickSequence(int value) { nextTickSequence = value; }
  public int getNextActionSequence() { return nextActionSequence; }
  public void setNextActionSequence(int value) { nextActionSequence = value; }
  public boolean isCompleted() { return completed; }
  public void setCompleted(boolean value) { completed = value; }
  public Integer getFinalTick() { return finalTick; }
  public void setFinalTick(Integer value) { finalTick = value; }
  public String getRecordingId() { return recordingId; }
  public void setRecordingId(String value) { recordingId = value; }
  public String getRecordingHash() { return recordingHash; }
  public void setRecordingHash(String value) { recordingHash = value; }
  public String getVideoSha256() { return videoSha256; }
  public void setVideoSha256(String value) { videoSha256 = value; }
  public String getCompletionRequestHash() { return completionRequestHash; }
  public void setCompletionRequestHash(String value) { completionRequestHash = value; }
  public String getDebugSessionId() { return debugSessionId; }
  public void setDebugSessionId(String value) { debugSessionId = value; }
  public String getVideoPath() { return videoPath; }
  public void setVideoPath(String value) { videoPath = value; }
  public String getCompletionStatus() { return completionStatus; }
  public void setCompletionStatus(String value) { completionStatus = value; }
  public String getCompletionError() { return completionError; }
  public void setCompletionError(String value) { completionError = value; }
  public String getOperation() { return operation; }
  public void setOperation(String value) { operation = value; }
  public String getOperationState() { return operationState; }
  public void setOperationState(String value) { operationState = value; }
  public String getOperationRequestHash() { return operationRequestHash; }
  public void setOperationRequestHash(String value) { operationRequestHash = value; }
  public String getOperationCorrelationId() { return operationCorrelationId; }
  public void setOperationCorrelationId(String value) { operationCorrelationId = value; }
  public int getOperationAttempts() { return operationAttempts; }
  public void setOperationAttempts(int value) { operationAttempts = value; }
}

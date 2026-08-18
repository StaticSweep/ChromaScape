package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "routine_shadow_runs", indexes = {
    @Index(name = "idx_routine_shadow_run_id", columnList = "shadow_run_id", unique = true),
    @Index(name = "idx_routine_shadow_model_session", columnList = "model_id,session_id", unique = true)
})
public class RoutineShadowRun {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "shadow_run_id", nullable = false, unique = true, length = 180) private String shadowRunId;
  @Column(name = "model_id", nullable = false, length = 160) private String modelId;
  @Column(name = "session_id", nullable = false, length = 160) private String sessionId;
  @Lob @Column(name = "model_identity_json", nullable = false) private String modelIdentityJson;
  @Lob @Column(name = "session_identity_json", nullable = false) private String sessionIdentityJson;
  @Column(nullable = false, length = 20) private String state;
  @Column(name = "next_decision_ordinal", nullable = false) private int nextDecisionOrdinal = 1;
  @Column(name = "graph_cursor", nullable = false) private int graphCursor;
  @Column(name = "last_processed_tick") private Long lastProcessedTick;
  @Version @Column(name = "optimistic_version", nullable = false) private Long optimisticVersion;
  @Column(nullable = false) private Instant startedAt = Instant.now();
  private Instant completedAt;
  @Lob @Column(name = "result_json") private String resultJson;
  @Column(name = "result_hash", length = 71) private String resultHash;
  @Column(name = "failure_code", length = 120) private String failureCode;
  @Column(name = "failure_message", length = 500) private String failureMessage;
  public Long getId() { return id; }
  public String getShadowRunId() { return shadowRunId; } public void setShadowRunId(String value) { shadowRunId = value; }
  public String getModelId() { return modelId; } public void setModelId(String value) { modelId = value; }
  public String getSessionId() { return sessionId; } public void setSessionId(String value) { sessionId = value; }
  public String getModelIdentityJson() { return modelIdentityJson; } public void setModelIdentityJson(String value) { modelIdentityJson = value; }
  public String getSessionIdentityJson() { return sessionIdentityJson; } public void setSessionIdentityJson(String value) { sessionIdentityJson = value; }
  public String getState() { return state; } public void setState(String value) { state = value; }
  public int getNextDecisionOrdinal() { return nextDecisionOrdinal; } public void setNextDecisionOrdinal(int value) { nextDecisionOrdinal = value; }
  public int getGraphCursor() { return graphCursor; } public void setGraphCursor(int value) { graphCursor = value; }
  public Long getLastProcessedTick() { return lastProcessedTick; } public void setLastProcessedTick(Long value) { lastProcessedTick = value; }
  public Instant getStartedAt() { return startedAt; } public void setStartedAt(Instant value) { startedAt = value; }
  public Instant getCompletedAt() { return completedAt; } public void setCompletedAt(Instant value) { completedAt = value; }
  public String getResultJson() { return resultJson; } public void setResultJson(String value) { resultJson = value; }
  public String getResultHash() { return resultHash; } public void setResultHash(String value) { resultHash = value; }
  public String getFailureCode() { return failureCode; } public void setFailureCode(String value) { failureCode = value; }
  public String getFailureMessage() { return failureMessage; } public void setFailureMessage(String value) { failureMessage = value; }
}

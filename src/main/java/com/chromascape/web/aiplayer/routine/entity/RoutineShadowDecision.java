package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "routine_shadow_decisions", indexes = {
    @Index(name = "idx_routine_shadow_decision_ordinal", columnList = "shadow_run_id,decision_ordinal", unique = true),
    @Index(name = "idx_routine_shadow_decision_source", columnList = "shadow_run_id,source_event_kind,source_batch_sequence,source_event_index", unique = true)
})
public class RoutineShadowDecision {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "shadow_run_id", nullable = false, length = 180) private String shadowRunId;
  @Column(name = "decision_ordinal", nullable = false) private int decisionOrdinal;
  @Column(name = "source_event_kind", nullable = false, length = 20) private String sourceEventKind;
  @Column(name = "source_batch_sequence", nullable = false) private int sourceBatchSequence;
  @Column(name = "source_event_index", nullable = false) private int sourceEventIndex;
  @Column(nullable = false) private long tick;
  @Column(nullable = false, length = 20) private String status;
  @Lob @Column(name = "recommendation_json") private String recommendationJson;
  @Lob @Column(name = "observed_action_json") private String observedActionJson;
  @Column(nullable = false, length = 20) private String outcome;
  @Column(name = "model_identity_hash", nullable = false, length = 71) private String modelIdentityHash;
  @Column(name = "session_identity_hash", nullable = false, length = 71) private String sessionIdentityHash;
  @Column(name = "decision_content_hash", nullable = false, length = 71) private String decisionContentHash;
  @Column(name = "control_allowed", nullable = false) private boolean controlAllowed = false;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
  public Long getId() { return id; }
  public String getShadowRunId() { return shadowRunId; } public void setShadowRunId(String value) { shadowRunId = value; }
  public int getDecisionOrdinal() { return decisionOrdinal; } public void setDecisionOrdinal(int value) { decisionOrdinal = value; }
  public String getSourceEventKind() { return sourceEventKind; } public void setSourceEventKind(String value) { sourceEventKind = value; }
  public int getSourceBatchSequence() { return sourceBatchSequence; } public void setSourceBatchSequence(int value) { sourceBatchSequence = value; }
  public int getSourceEventIndex() { return sourceEventIndex; } public void setSourceEventIndex(int value) { sourceEventIndex = value; }
  public long getTick() { return tick; } public void setTick(long value) { tick = value; }
  public String getStatus() { return status; } public void setStatus(String value) { status = value; }
  public String getRecommendationJson() { return recommendationJson; } public void setRecommendationJson(String value) { recommendationJson = value; }
  public String getObservedActionJson() { return observedActionJson; } public void setObservedActionJson(String value) { observedActionJson = value; }
  public String getOutcome() { return outcome; } public void setOutcome(String value) { outcome = value; }
  public String getModelIdentityHash() { return modelIdentityHash; } public void setModelIdentityHash(String value) { modelIdentityHash = value; }
  public String getSessionIdentityHash() { return sessionIdentityHash; } public void setSessionIdentityHash(String value) { sessionIdentityHash = value; }
  public String getDecisionContentHash() { return decisionContentHash; } public void setDecisionContentHash(String value) { decisionContentHash = value; }
  public boolean isControlAllowed() { return controlAllowed; } public void setControlAllowed(boolean value) { controlAllowed = value; }
  public Instant getCreatedAt() { return createdAt; }
}

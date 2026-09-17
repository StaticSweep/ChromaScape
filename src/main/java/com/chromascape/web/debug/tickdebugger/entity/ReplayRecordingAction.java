package com.chromascape.web.debug.tickdebugger.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable typed semantic action retained as replay evidence. */
@Entity
@Table(
    name = "replay_recording_actions",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_replay_recording_action_session_tick_sequence",
            columnNames = {"session_id", "tick", "sequence"}),
    indexes = {
      @Index(
          name = "idx_replay_recording_actions_recording_tick",
          columnList = "recording_id, tick, sequence"),
      @Index(
          name = "idx_replay_recording_actions_session_tick",
          columnList = "session_id, tick, sequence")
    })
public class ReplayRecordingAction {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "session_id", nullable = false, length = 100)
  private String sessionId;

  @Column(name = "recording_id", length = 100)
  private String recordingId;

  @Column(nullable = false)
  private long tick;

  @Column(nullable = false)
  private int sequence;

  @Column(name = "schema_name", nullable = false, length = 64)
  private String schema;

  @Column(name = "action_type", nullable = false, length = 64)
  private String actionType;

  @Column(name = "target_kind", length = 32)
  private String targetKind;

  @Column(name = "target_stable_id", length = 255)
  private String targetStableId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "target_json")
  private JsonNode targetJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "arguments_json", nullable = false)
  private JsonNode argumentsJson;

  @Column(nullable = false, length = 64)
  private String source;

  /** Exact producer provenance retained separately from the transport source. */
  @Column(nullable = false, length = 32)
  private String provenance;

  @Column(nullable = false, precision = 4, scale = 3)
  private BigDecimal confidence;

  @Column(name = "captured_at", nullable = false)
  private LocalDateTime capturedAt;

  @Column(name = "ingested_at", nullable = false)
  private LocalDateTime ingestedAt;

  @Column(name = "content_checksum", nullable = false, length = 71)
  private String contentChecksum;

  public UUID getId() {
    return id;
  }

  public void setId(UUID id) {
    this.id = id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
  }

  public long getTick() {
    return tick;
  }

  public void setTick(long tick) {
    this.tick = tick;
  }

  public int getSequence() {
    return sequence;
  }

  public void setSequence(int sequence) {
    this.sequence = sequence;
  }

  public String getSchema() {
    return schema;
  }

  public void setSchema(String schema) {
    this.schema = schema;
  }

  public String getActionType() {
    return actionType;
  }

  public void setActionType(String actionType) {
    this.actionType = actionType;
  }

  public String getTargetKind() {
    return targetKind;
  }

  public void setTargetKind(String targetKind) {
    this.targetKind = targetKind;
  }

  public String getTargetStableId() {
    return targetStableId;
  }

  public void setTargetStableId(String targetStableId) {
    this.targetStableId = targetStableId;
  }

  public JsonNode getTargetJson() {
    return targetJson;
  }

  public void setTargetJson(JsonNode targetJson) {
    this.targetJson = targetJson;
  }

  public JsonNode getArgumentsJson() {
    return argumentsJson;
  }

  public void setArgumentsJson(JsonNode argumentsJson) {
    this.argumentsJson = argumentsJson;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getProvenance() {
    return provenance;
  }

  public void setProvenance(String provenance) {
    this.provenance = provenance;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public void setConfidence(BigDecimal confidence) {
    this.confidence = confidence;
  }

  public LocalDateTime getCapturedAt() {
    return capturedAt;
  }

  public void setCapturedAt(LocalDateTime capturedAt) {
    this.capturedAt = capturedAt;
  }

  public LocalDateTime getIngestedAt() {
    return ingestedAt;
  }

  public void setIngestedAt(LocalDateTime ingestedAt) {
    this.ingestedAt = ingestedAt;
  }

  public String getContentChecksum() {
    return contentChecksum;
  }

  public void setContentChecksum(String contentChecksum) {
    this.contentChecksum = contentChecksum;
  }
}

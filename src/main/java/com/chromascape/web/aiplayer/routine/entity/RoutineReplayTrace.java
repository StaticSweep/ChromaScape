package com.chromascape.web.aiplayer.routine.entity;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "routine_replay_traces", indexes = @Index(name = "idx_routine_replay_traces_trace_id", columnList = "trace_id", unique = true))
public class RoutineReplayTrace {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
  @Column(name = "trace_id", nullable = false, unique = true, length = 180) private String traceId;
  @Column(name = "model_id", nullable = false, length = 160) private String modelId;
  @Column(name = "recording_id", nullable = false, length = 160) private String recordingId;
  @Column(name = "trace_mode", nullable = false, length = 30) private String traceMode;
  @Column(name = "content_hash", nullable = false, length = 71) private String contentHash;
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(name = "identity_json", nullable = false) private String identityJson;
  @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(name = "result_json", nullable = false) private String resultJson;
  @Column(nullable = false) private Instant createdAt = Instant.now();

  public Long getId() { return id; }
  public String getTraceId() { return traceId; }
  public void setTraceId(String value) { traceId = value; }
  public String getModelId() { return modelId; }
  public void setModelId(String value) { modelId = value; }
  public String getRecordingId() { return recordingId; }
  public void setRecordingId(String value) { recordingId = value; }
  public String getTraceMode() { return traceMode; }
  public void setTraceMode(String value) { traceMode = value; }
  public String getContentHash() { return contentHash; }
  public void setContentHash(String value) { contentHash = value; }
  public String getIdentityJson() { return identityJson; }
  public void setIdentityJson(String value) { identityJson = value; }
  public String getResultJson() { return resultJson; }
  public void setResultJson(String value) { resultJson = value; }
  public Instant getCreatedAt() { return createdAt; }
}

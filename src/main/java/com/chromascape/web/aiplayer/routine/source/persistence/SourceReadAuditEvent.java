package com.chromascape.web.aiplayer.routine.source.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Durable, content-free audit record for an authorized or rejected source read. */
@Entity
@Table(name = "source_read_audit_events")
public class SourceReadAuditEvent {
  @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
  @Column(name = "actor_id", nullable = false, length = 160) private String actorId;
  @Column(name = "operation", nullable = false, length = 40) private String operation;
  @Column(name = "run_id", length = 160) private String runId;
  @Column(name = "recording_id", length = 160) private String recordingId;
  @Column(name = "task_key", length = 160) private String taskKey;
  @Column(name = "snapshot_id", length = 40) private String snapshotId;
  @Column(name = "file_id", length = 80) private String fileId;
  @Column(name = "content_hash", length = 71) private String contentHash;
  @Column(name = "result", nullable = false, length = 80) private String result;
  @Column(name = "environment", length = 120) private String environment;
  @Column(name = "correlation_id", length = 160) private String correlationId;
  @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

  protected SourceReadAuditEvent() {}

  public static SourceReadAuditEvent create(
      String actorId,
      String operation,
      String runId,
      String recordingId,
      String taskKey,
      String snapshotId,
      String fileId,
      String result,
      String environment) {
    return create(actorId, operation, runId, recordingId, taskKey, snapshotId, fileId, result,
        environment, null, null);
  }

  public static SourceReadAuditEvent create(
      String actorId,
      String operation,
      String runId,
      String recordingId,
      String taskKey,
      String snapshotId,
      String fileId,
      String result,
      String environment,
      String correlationId) {
    return create(actorId, operation, runId, recordingId, taskKey, snapshotId, fileId, result,
        environment, null, correlationId);
  }

  public static SourceReadAuditEvent create(
      String actorId,
      String operation,
      String runId,
      String recordingId,
      String taskKey,
      String snapshotId,
      String fileId,
      String result,
      String environment,
      String contentHash,
      String correlationId) {
    SourceReadAuditEvent event = new SourceReadAuditEvent();
    event.actorId = bound(actorId, "unknown");
    event.operation = bound(operation, "unknown");
    event.runId = bound(runId, null);
    event.recordingId = bound(recordingId, null);
    event.taskKey = bound(taskKey, null);
    event.snapshotId = bound(snapshotId, null);
    event.fileId = bound(fileId, null);
    event.result = bound(result, "UNKNOWN");
    event.environment = bound(environment, null);
    event.contentHash = bound(contentHash, null);
    event.correlationId = bound(correlationId, null);
    return event;
  }

  public UUID getId() { return id; }
  public String getActorId() { return actorId; }
  public String getOperation() { return operation; }
  public String getRunId() { return runId; }
  public String getRecordingId() { return recordingId; }
  public String getTaskKey() { return taskKey; }
  public String getSnapshotId() { return snapshotId; }
  public String getFileId() { return fileId; }
  public String getContentHash() { return contentHash; }
  public String getResult() { return result; }
  public String getEnvironment() { return environment; }
  public String getCorrelationId() { return correlationId; }
  public Instant getCreatedAt() { return createdAt; }

  private static String bound(String value, String fallback) {
    if (value == null || value.isBlank()) return fallback;
    String trimmed = value.trim();
    return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160);
  }
}

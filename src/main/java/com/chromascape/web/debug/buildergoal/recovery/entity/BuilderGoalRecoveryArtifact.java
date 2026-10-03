package com.chromascape.web.debug.buildergoal.recovery.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "builder_goal_recovery_artifact")
public class BuilderGoalRecoveryArtifact {
  @Id
  @Column(name = "artifact_id", nullable = false, length = 80)
  private String artifactId;

  @Column(name = "incident_id", nullable = false, length = 80)
  private String incidentId;

  @Column(name = "attempt_id", length = 80)
  private String attemptId;

  @Column(nullable = false, length = 50)
  private String kind;

  @Column(name = "storage_key", nullable = false, unique = true, length = 500)
  private String storageKey;

  @Column(name = "sha256", nullable = false, length = 71)
  private String sha256;

  @Column(name = "byte_count", nullable = false)
  private long byteCount;

  @Column(name = "line_count", nullable = false)
  private long lineCount;

  @Column(name = "sanitization_version", nullable = false, length = 30)
  private String sanitizationVersion;

  @Column(nullable = false)
  private boolean truncated;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  public String getArtifactId() {
    return artifactId;
  }

  public void setArtifactId(String value) {
    artifactId = value;
  }

  public String getIncidentId() {
    return incidentId;
  }

  public void setIncidentId(String value) {
    incidentId = value;
  }

  public String getAttemptId() {
    return attemptId;
  }

  public void setAttemptId(String value) {
    attemptId = value;
  }

  public String getKind() {
    return kind;
  }

  public void setKind(String value) {
    kind = value;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public void setStorageKey(String value) {
    storageKey = value;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String value) {
    sha256 = value;
  }

  public long getByteCount() {
    return byteCount;
  }

  public void setByteCount(long value) {
    byteCount = value;
  }

  public long getLineCount() {
    return lineCount;
  }

  public void setLineCount(long value) {
    lineCount = value;
  }

  public String getSanitizationVersion() {
    return sanitizationVersion;
  }

  public void setSanitizationVersion(String value) {
    sanitizationVersion = value;
  }

  public boolean isTruncated() {
    return truncated;
  }

  public void setTruncated(boolean value) {
    truncated = value;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant value) {
    createdAt = value;
  }
}

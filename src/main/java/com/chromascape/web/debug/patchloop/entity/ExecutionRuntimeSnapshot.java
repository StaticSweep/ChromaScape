package com.chromascape.web.debug.patchloop.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Entity representing a runtime snapshot for execution resume.
 *
 * <p>Stores runtime data and context for resuming paused or failed executions.
 */
@Entity
@Table(name = "execution_runtime_snapshot")
public class ExecutionRuntimeSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** Reference to the parent graph execution (unique) */
  @Column(name = "execution_id", unique = true, nullable = false, length = 100)
  private String executionId;

  /** Runtime data as JSON */
  @Column(name = "runtime_data_json", columnDefinition = "TEXT")
  private String runtimeDataJson;

  /** Context data as JSON */
  @Column(name = "context_data_json", columnDefinition = "TEXT")
  private String contextDataJson;

  /** Snapshot version for conflict detection */
  @Column(name = "snapshot_version")
  private Integer snapshotVersion;

  /** When this snapshot was created */
  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** When this snapshot was last updated */
  @Column(name = "last_updated_at")
  private LocalDateTime lastUpdatedAt;

  public ExecutionRuntimeSnapshot() {
    this.createdAt = LocalDateTime.now();
    this.lastUpdatedAt = LocalDateTime.now();
    this.snapshotVersion = 1;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getExecutionId() {
    return executionId;
  }

  public void setExecutionId(String executionId) {
    this.executionId = executionId;
  }

  public String getRuntimeDataJson() {
    return runtimeDataJson;
  }

  public void setRuntimeDataJson(String runtimeDataJson) {
    this.runtimeDataJson = runtimeDataJson;
  }

  public String getContextDataJson() {
    return contextDataJson;
  }

  public void setContextDataJson(String contextDataJson) {
    this.contextDataJson = contextDataJson;
  }

  public Integer getSnapshotVersion() {
    return snapshotVersion;
  }

  public void setSnapshotVersion(Integer snapshotVersion) {
    this.snapshotVersion = snapshotVersion;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getLastUpdatedAt() {
    return lastUpdatedAt;
  }

  public void setLastUpdatedAt(LocalDateTime lastUpdatedAt) {
    this.lastUpdatedAt = lastUpdatedAt;
  }

  /** Increments the snapshot version and updates the last updated time. */
  public void incrementVersion() {
    this.snapshotVersion++;
    this.lastUpdatedAt = LocalDateTime.now();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ExecutionRuntimeSnapshot that = (ExecutionRuntimeSnapshot) o;
    return Objects.equals(executionId, that.executionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(executionId);
  }

  @Override
  public String toString() {
    return "ExecutionRuntimeSnapshot{"
        + "id="
        + id
        + ", executionId='"
        + executionId
        + '\''
        + ", snapshotVersion="
        + snapshotVersion
        + ", createdAt="
        + createdAt
        + ", lastUpdatedAt="
        + lastUpdatedAt
        + '}';
  }
}

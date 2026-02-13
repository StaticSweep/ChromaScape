package com.chromascape.web.recording.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA entity for persisting per-task recording toggle state.
 *
 * <p>Each row represents one {@code TaskType} and whether auto-recording is enabled for it.
 * Defaults to disabled (false) for all task types on first startup.
 */
@Entity
@Table(name = "task_recording_preferences")
public class TaskRecordingPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** TaskType name stored in lower-case (e.g. "combat", "bossing"). */
  @Column(nullable = false, unique = true, length = 50)
  private String taskType;

  /** Whether auto-recording is enabled for this task. */
  @Column(nullable = false)
  private boolean enabled = false;

  /** When this preference was first created. */
  @Column(nullable = false)
  private LocalDateTime createdAt;

  /** When this preference was last updated. */
  @Column(nullable = false)
  private LocalDateTime updatedAt;

  /** Default constructor required by JPA. */
  public TaskRecordingPreference() {}

  /** Constructor for creating a new preference entry. */
  public TaskRecordingPreference(String taskType, boolean enabled) {
    this.taskType = taskType;
    this.enabled = enabled;
    this.createdAt = LocalDateTime.now();
    this.updatedAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public String getTaskType() {
    return taskType;
  }

  public void setTaskType(String taskType) {
    this.taskType = taskType;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
    this.updatedAt = LocalDateTime.now();
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }
}

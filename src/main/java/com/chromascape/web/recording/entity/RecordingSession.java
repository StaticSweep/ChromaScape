package com.chromascape.web.recording.entity;

import com.chromascape.web.recording.provenance.RecordingProvenance;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity for storing recording session metadata in the database.
 *
 * <p>Tracks recording sessions with per-account information for replay debugging.
 */
@Entity
@Table(
    name = "recording_sessions",
    indexes = {
      @Index(name = "idx_recording_sessions_start_time", columnList = "start_time"),
      @Index(name = "idx_recording_sessions_recording_id", columnList = "recording_id"),
      @Index(name = "idx_recording_catalog_cursor", columnList = "start_time DESC,id DESC"),
      @Index(
          name = "idx_recording_catalog_account",
          columnList = "account_name,start_time DESC,id DESC"),
      @Index(name = "idx_recording_catalog_task", columnList = "task_key,start_time DESC,id DESC"),
      @Index(
          name = "idx_recording_catalog_account_task",
          columnList = "account_name,task_key,start_time DESC,id DESC")
    })
public class RecordingSession {

  /** Type of trigger that started the recording session. */
  public enum SessionTrigger {
    /** Manual recording start via UI or API. */
    MANUAL,
    /** Automatic recording on script start. */
    AUTO_START,
    /** Automatic recording triggered by error event. */
    ERROR_EVENT,
    /** Automatic recording started by Micro6 SESSION_START message. */
    MICRO6_AUTO
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** The recording ID from RecordingService (e.g., "recording_1234567890"). */
  @Column(nullable = false, unique = true, length = 100)
  private String recordingId;

  /** Account name associated with this recording. */
  @Column(length = 100)
  private String accountName;

  /** Script name being run during recording. */
  @Column(length = 100)
  private String scriptName;

  @Column(length = 160)
  private String taskKey;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode taskArgs;

  @Column(length = 71)
  private String settingsHash;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode runContext;

  @Column(length = 24)
  private String provenanceOrigin;

  /** When the recording started. */
  @Column(nullable = false)
  private LocalDateTime startTime;

  /** When the recording ended (null if still recording). */
  private LocalDateTime endTime;

  /** What triggered this recording session. */
  @Column(nullable = false, length = 20)
  @Enumerated(EnumType.STRING)
  private SessionTrigger triggerType;

  /** Path to the video file. */
  @Column(length = 500)
  private String videoPath;

  /** Recording duration in milliseconds. */
  private Long durationMs;

  /** Number of frames recorded. */
  private Long frameCount;

  /** Recording FPS. */
  private Integer fps;

  /** Video codec used for recording (e.g., libx264, libopenh264, mpeg4). */
  @Column(length = 50)
  private String videoCodec;

  /** User-defined replay name (nullable). If set, used as displayName; otherwise auto-generated. */
  @Column(length = 160)
  private String replayName;

  /** Tags stored as JSON array string (nullable). Normalized in service layer. */
  @Column(columnDefinition = "TEXT")
  private String replayTagsJson;

  /** Default constructor required by JPA. */
  public RecordingSession() {}

  /** Constructor for creating recording sessions. */
  public RecordingSession(
      String recordingId,
      String accountName,
      String scriptName,
      LocalDateTime startTime,
      SessionTrigger triggerType,
      String videoPath) {
    this.recordingId = recordingId;
    this.accountName = accountName;
    this.scriptName = scriptName;
    this.startTime = startTime;
    this.triggerType = triggerType;
    this.videoPath = videoPath;
  }

  /** Called only while creating a row; finalization never reconstructs provenance. */
  public void captureProvenance(RecordingProvenance provenance) {
    if (taskKey != null || taskArgs != null || settingsHash != null)
      throw new IllegalStateException("IMMUTABLE_PROVENANCE");
    taskKey = provenance.taskKey();
    taskArgs = provenance.taskArgs();
    settingsHash = provenance.settingsHash();
    provenanceOrigin = "CAPTURED";
  }

  public String getTaskKey() {
    return taskKey;
  }

  public JsonNode getTaskArgs() {
    return taskArgs == null ? null : taskArgs.deepCopy();
  }

  public String getSettingsHash() {
    return settingsHash;
  }

  public JsonNode getRunContext() {
    return runContext == null ? null : runContext.deepCopy();
  }

  public String getProvenanceOrigin() {
    return provenanceOrigin;
  }

  public void bindRunContext(JsonNode context, String origin) {
    if (runContext != null) throw new IllegalStateException("IMMUTABLE_RUN_CONTEXT");
    runContext = context.deepCopy();
    provenanceOrigin = origin;
  }

  // Getters and Setters

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getRecordingId() {
    return recordingId;
  }

  public void setRecordingId(String recordingId) {
    this.recordingId = recordingId;
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

  public SessionTrigger getTriggerType() {
    return triggerType;
  }

  public void setTriggerType(SessionTrigger triggerType) {
    this.triggerType = triggerType;
  }

  public String getVideoPath() {
    return videoPath;
  }

  public void setVideoPath(String videoPath) {
    this.videoPath = videoPath;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public Long getFrameCount() {
    return frameCount;
  }

  public void setFrameCount(Long frameCount) {
    this.frameCount = frameCount;
  }

  public Integer getFps() {
    return fps;
  }

  public void setFps(Integer fps) {
    this.fps = fps;
  }

  public String getVideoCodec() {
    return videoCodec;
  }

  public void setVideoCodec(String videoCodec) {
    this.videoCodec = videoCodec;
  }

  public String getReplayName() {
    return replayName;
  }

  public void setReplayName(String replayName) {
    this.replayName = replayName;
  }

  public String getReplayTagsJson() {
    return replayTagsJson;
  }

  public void setReplayTagsJson(String replayTagsJson) {
    this.replayTagsJson = replayTagsJson;
  }
}

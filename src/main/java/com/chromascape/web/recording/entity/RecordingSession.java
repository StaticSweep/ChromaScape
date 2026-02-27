package com.chromascape.web.recording.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * JPA entity for storing recording session metadata in the database.
 *
 * <p>Tracks recording sessions with per-account information for replay debugging.
 */
@Entity
@Table(name = "recording_sessions")
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
}

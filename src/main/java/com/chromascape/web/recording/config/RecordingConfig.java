package com.chromascape.web.recording.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for game recording and replay functionality.
 *
 * <p>Configures how game sessions are recorded for later replay with synchronized logs.
 */
@Configuration
@ConfigurationProperties(prefix = "recording")
public class RecordingConfig {

  /** Whether recording functionality is enabled. */
  private boolean enabled = true;

  /** Directory where recordings are stored. */
  private String outputDirectory = "recordings";

  /** Video quality (1-100, higher = better quality). */
  private int videoQuality = 70;

  /** Frames per second for recording. */
  private int fps = 30;

  /** Whether to record audio (not currently supported). */
  private boolean recordAudio = false;

  /** Maximum recording duration in minutes (0 = unlimited). */
  private int maxDurationMinutes = 60;

  /** Video encoding preset for libx264 (veryfast, fast, medium, slow, slower, veryslow). */
  private String encodingPreset = "fast";

  /** Whether to automatically record when game starts. */
  private boolean autoRecord = false;

  /**
   * Whether task-bound auto sessions should start recordings even if generic auto-record is off.
   */
  private boolean autoRecordTaskSessions = true;

  /**
   * Maximum time to wait for RuneLite's canvas to expose capturable dimensions before startup fails
   * and the session degrades to a tick-only debug session.
   */
  private long initialFrameTimeoutMs = 15_000;

  /** Delay between initial frame-capture attempts while waiting for the canvas to become ready. */
  private long initialFrameRetryIntervalMs = 250;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getOutputDirectory() {
    return outputDirectory;
  }

  public void setOutputDirectory(String outputDirectory) {
    this.outputDirectory = outputDirectory;
  }

  public int getVideoQuality() {
    return Math.max(1, Math.min(100, videoQuality));
  }

  public void setVideoQuality(int videoQuality) {
    this.videoQuality = videoQuality;
  }

  public int getFps() {
    return Math.max(1, Math.min(60, fps));
  }

  public void setFps(int fps) {
    this.fps = fps;
  }

  public boolean isRecordAudio() {
    return recordAudio;
  }

  public void setRecordAudio(boolean recordAudio) {
    this.recordAudio = recordAudio;
  }

  public int getMaxDurationMinutes() {
    return maxDurationMinutes;
  }

  public void setMaxDurationMinutes(int maxDurationMinutes) {
    this.maxDurationMinutes = maxDurationMinutes;
  }

  public String getEncodingPreset() {
    return encodingPreset;
  }

  public void setEncodingPreset(String encodingPreset) {
    this.encodingPreset = encodingPreset;
  }

  public boolean isAutoRecord() {
    return autoRecord;
  }

  public void setAutoRecord(boolean autoRecord) {
    this.autoRecord = autoRecord;
  }

  public boolean isAutoRecordTaskSessions() {
    return autoRecordTaskSessions;
  }

  public void setAutoRecordTaskSessions(boolean autoRecordTaskSessions) {
    this.autoRecordTaskSessions = autoRecordTaskSessions;
  }

  /** Maximum time to wait for a valid initial frame, clamped to [{@code 250}, {@code 120000}]. */
  public long getInitialFrameTimeoutMs() {
    return Math.max(250, Math.min(120_000, initialFrameTimeoutMs));
  }

  public void setInitialFrameTimeoutMs(long initialFrameTimeoutMs) {
    this.initialFrameTimeoutMs = initialFrameTimeoutMs;
  }

  /**
   * Delay between initial frame-capture attempts, clamped to [{@code 25}, {@code 5000}] and never
   * exceeding the effective timeout.
   */
  public long getInitialFrameRetryIntervalMs() {
    long interval = Math.max(25, Math.min(5000, initialFrameRetryIntervalMs));
    return Math.min(interval, getInitialFrameTimeoutMs());
  }

  public void setInitialFrameRetryIntervalMs(long initialFrameRetryIntervalMs) {
    this.initialFrameRetryIntervalMs = initialFrameRetryIntervalMs;
  }

  @Override
  public String toString() {
    return "RecordingConfig{"
        + "enabled="
        + enabled
        + ", outputDirectory='"
        + outputDirectory
        + '\''
        + ", videoQuality="
        + videoQuality
        + ", fps="
        + fps
        + ", encodingPreset='"
        + encodingPreset
        + '\''
        + ", maxDurationMinutes="
        + maxDurationMinutes
        + ", autoRecord="
        + autoRecord
        + ", autoRecordTaskSessions="
        + autoRecordTaskSessions
        + ", initialFrameTimeoutMs="
        + getInitialFrameTimeoutMs()
        + ", initialFrameRetryIntervalMs="
        + getInitialFrameRetryIntervalMs()
        + '}';
  }
}

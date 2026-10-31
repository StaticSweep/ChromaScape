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

  /**
   * How long the captured canvas size must remain unchanged before the recorder locks onto it.
   *
   * <p>RuneLite's canvas passes through transient sizes during startup; a value of {@code 0}
   * accepts the first plausibly-sized frame (legacy behavior).
   */
  private long stabilityWindowMs = 1_500;

  /**
   * Minimum canvas width accepted as a stable recording size before the readiness deadline expires.
   * Smaller stable canvases are used only as the deadline fallback.
   */
  private int minCaptureWidth = 640;

  /** Minimum canvas height accepted as a stable recording size before the readiness deadline. */
  private int minCaptureHeight = 400;

  /**
   * Whether to write {@code <recording>.frames.jsonl} with each encoded frame's capture time, so
   * per-frame traces (for example micro6's mouse trace) can be aligned to exact frames.
   */
  private boolean frameTimingSidecar = true;

  public boolean isFrameTimingSidecar() {
    return frameTimingSidecar;
  }

  public void setFrameTimingSidecar(boolean frameTimingSidecar) {
    this.frameTimingSidecar = frameTimingSidecar;
  }

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

  /** Stability window for the captured canvas size, clamped to {@code [0, 10000]} ms. */
  public long getStabilityWindowMs() {
    return Math.max(0, Math.min(10_000, stabilityWindowMs));
  }

  public void setStabilityWindowMs(long stabilityWindowMs) {
    this.stabilityWindowMs = stabilityWindowMs;
  }

  /** Minimum accepted stable canvas width, clamped to {@code [1, 7680]}. */
  public int getMinCaptureWidth() {
    return Math.max(1, Math.min(7_680, minCaptureWidth));
  }

  public void setMinCaptureWidth(int minCaptureWidth) {
    this.minCaptureWidth = minCaptureWidth;
  }

  /** Minimum accepted stable canvas height, clamped to {@code [1, 4320]}. */
  public int getMinCaptureHeight() {
    return Math.max(1, Math.min(4_320, minCaptureHeight));
  }

  public void setMinCaptureHeight(int minCaptureHeight) {
    this.minCaptureHeight = minCaptureHeight;
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
        + ", stabilityWindowMs="
        + getStabilityWindowMs()
        + ", minCaptureWidth="
        + getMinCaptureWidth()
        + ", minCaptureHeight="
        + getMinCaptureHeight()
        + '}';
  }
}

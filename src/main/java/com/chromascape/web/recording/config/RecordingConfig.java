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
        + '}';
  }
}

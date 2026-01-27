package com.chromascape.web.viewport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the unified frame capture service.
 *
 * <p>This configuration controls the single screen capture thread that provides frames to all
 * consumers (viewport, recording, streaming). Using a single capture source prevents duplicate
 * screen captures and reduces CPU usage.
 */
@Configuration
@ConfigurationProperties(prefix = "unified-capture")
public class UnifiedCaptureConfig {

  /** Whether unified capture is enabled. When disabled, falls back to per-service capture. */
  private boolean enabled = true;

  /** Frames per second to capture (1-60). All consumers receive frames at this rate. */
  private int fps = 15;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getFps() {
    return fps;
  }

  public void setFps(int fps) {
    if (fps < 1 || fps > 60) {
      throw new IllegalArgumentException("FPS must be between 1 and 60, got: " + fps);
    }
    this.fps = fps;
  }

  /**
   * Returns the capture interval in milliseconds.
   *
   * @return Milliseconds between frame captures
   */
  public long getIntervalMs() {
    return (long) (1000.0 / fps);
  }
}

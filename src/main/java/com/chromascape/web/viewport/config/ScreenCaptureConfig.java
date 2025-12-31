package com.chromascape.web.viewport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for continuous screen capture to the viewport.
 *
 * <p>Configures the background service that continuously captures the game window
 * and streams it to the viewport for display, even when no bot script is running.
 */
@Configuration
@ConfigurationProperties(prefix = "screencapture")
public class ScreenCaptureConfig {

  /** Whether continuous screen capture is enabled. */
  private boolean enabled = true;

  /** Frames per second to capture. */
  private int fps = 30;

  /** Whether to capture only when the game window is focused. */
  private boolean captureOnlyWhenFocused = false;

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
    this.fps = Math.max(1, Math.min(60, fps)); // Clamp between 1-60 FPS
  }

  public boolean isCaptureOnlyWhenFocused() {
    return captureOnlyWhenFocused;
  }

  public void setCaptureOnlyWhenFocused(boolean captureOnlyWhenFocused) {
    this.captureOnlyWhenFocused = captureOnlyWhenFocused;
  }

  @Override
  public String toString() {
    return "ScreenCaptureConfig{"
        + "enabled="
        + enabled
        + ", fps="
        + fps
        + ", captureOnlyWhenFocused="
        + captureOnlyWhenFocused
        + '}';
  }
}

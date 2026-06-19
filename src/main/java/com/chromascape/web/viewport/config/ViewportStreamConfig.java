package com.chromascape.web.viewport.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime configuration for the live viewport stream.
 *
 * <p>This is intentionally separate from the unified capture FPS so the live browser viewport can
 * be throttled without affecting recording or other capture consumers.
 */
@Configuration
@ConfigurationProperties(prefix = "viewport-stream")
public class ViewportStreamConfig {

  private int targetFps = 15;
  private int jpegQuality = 70;
  private int maxLongEdge = 960;

  public int getTargetFps() {
    return targetFps;
  }

  public void setTargetFps(int targetFps) {
    if (targetFps < 1 || targetFps > 60) {
      throw new IllegalArgumentException(
          "viewport-stream.target-fps must be between 1 and 60, got: " + targetFps);
    }
    this.targetFps = targetFps;
  }

  public int getJpegQuality() {
    return jpegQuality;
  }

  public void setJpegQuality(int jpegQuality) {
    if (jpegQuality < 1 || jpegQuality > 100) {
      throw new IllegalArgumentException(
          "viewport-stream.jpeg-quality must be between 1 and 100, got: " + jpegQuality);
    }
    this.jpegQuality = jpegQuality;
  }

  public int getMaxLongEdge() {
    return maxLongEdge;
  }

  public void setMaxLongEdge(int maxLongEdge) {
    if (maxLongEdge < 1) {
      throw new IllegalArgumentException(
          "viewport-stream.max-long-edge must be greater than 0, got: " + maxLongEdge);
    }
    this.maxLongEdge = maxLongEdge;
  }

  public long getTargetIntervalNanos() {
    return 1_000_000_000L / targetFps;
  }
}

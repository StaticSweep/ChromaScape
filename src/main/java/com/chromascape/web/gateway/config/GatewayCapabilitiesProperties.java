package com.chromascape.web.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Server-only limits and identity used by the debugger capability contract.
 *
 * <p>These values are deliberately not accepted from a browser request. The frame limit includes
 * the transport envelope overhead so the existing one-megabyte viewport payload remains usable.
 */
@Configuration
@ConfigurationProperties(prefix = "gateway.capabilities")
public class GatewayCapabilitiesProperties {

  private static final int MAX_CONFIGURED_BYTES = 16 * 1024 * 1024;

  private String serviceBuildId = "chromascape-dev";
  private int maxRestBodyBytes = 1_048_576;
  private int maxFrameBytes = 2_097_152;

  public String getServiceBuildId() {
    return serviceBuildId;
  }

  public void setServiceBuildId(String serviceBuildId) {
    this.serviceBuildId = serviceBuildId == null ? "" : serviceBuildId;
  }

  public int getMaxRestBodyBytes() {
    return maxRestBodyBytes;
  }

  public void setMaxRestBodyBytes(int maxRestBodyBytes) {
    if (maxRestBodyBytes < 1 || maxRestBodyBytes > MAX_CONFIGURED_BYTES) {
      throw new IllegalArgumentException(
          "gateway.capabilities.max-rest-body-bytes must be between 1 and 16777216 bytes");
    }
    this.maxRestBodyBytes = maxRestBodyBytes;
  }

  public int getMaxFrameBytes() {
    return maxFrameBytes;
  }

  public void setMaxFrameBytes(int maxFrameBytes) {
    if (maxFrameBytes < 1_048_576 || maxFrameBytes > MAX_CONFIGURED_BYTES) {
      throw new IllegalArgumentException(
          "gateway.capabilities.max-frame-bytes must be between 1048576 and 16777216 bytes");
    }
    this.maxFrameBytes = maxFrameBytes;
  }
}

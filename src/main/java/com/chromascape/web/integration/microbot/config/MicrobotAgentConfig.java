package com.chromascape.web.integration.microbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the Microbot Agent Server integration.
 *
 * <p>Controls the HTTP adapter that connects ChromaScape to a Microbot Agent Server for runtime
 * state inspection and action dispatch.
 */
@Configuration
@ConfigurationProperties(prefix = "microbot.agent-server")
public class MicrobotAgentConfig {

  /** Enable or disable the Microbot Agent Server adapter. */
  private boolean enabled = false;

  /** Base URL of the Microbot Agent Server (e.g., "http://127.0.0.1:8081"). */
  private String baseUrl = "http://127.0.0.1:8081";

  /** HTTP request timeout in milliseconds. */
  private int timeoutMs = 5000;

  /** Polling interval for state snapshots in milliseconds. */
  private int pollIntervalMs = 2000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public int getTimeoutMs() {
    return timeoutMs;
  }

  public void setTimeoutMs(int timeoutMs) {
    this.timeoutMs = timeoutMs;
  }

  public int getPollIntervalMs() {
    return pollIntervalMs;
  }

  public void setPollIntervalMs(int pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
  }
}

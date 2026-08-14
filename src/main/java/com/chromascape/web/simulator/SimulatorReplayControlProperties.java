package com.chromascape.web.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the OpenRune Replay Control proxy.
 *
 * <p>Configures the integration with the OpenRune ReplayControlService HTTP API for controlling
 * in-game replay playback from the ChromaScape web UI.
 */
@Configuration
@ConfigurationProperties(prefix = "simulator.replay-control")
public class SimulatorReplayControlProperties {

  /** Whether the replay control proxy is enabled. */
  private boolean enabled = true;

  /** Base URL of the OpenRune ReplayControlService HTTP API. */
  private String openruneApiUrl = "http://127.0.0.1:8095";

  /** API key for authenticating with the OpenRune replay control API. */
  private String apiKey = "dev-token";

  /** HTTP connection timeout in milliseconds. */
  private int connectionTimeoutMs = 5000;

  /** HTTP read timeout in milliseconds. */
  private int readTimeoutMs = 10000;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getOpenruneApiUrl() {
    return openruneApiUrl;
  }

  public void setOpenruneApiUrl(String openruneApiUrl) {
    this.openruneApiUrl = openruneApiUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public int getConnectionTimeoutMs() {
    return connectionTimeoutMs;
  }

  public void setConnectionTimeoutMs(int connectionTimeoutMs) {
    this.connectionTimeoutMs = connectionTimeoutMs;
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = readTimeoutMs;
  }

  @Override
  public String toString() {
    return "SimulatorReplayControlProperties{"
        + "enabled="
        + enabled
        + ", openruneApiUrl='"
        + openruneApiUrl
        + '\''
        + ", connectionTimeoutMs="
        + connectionTimeoutMs
        + ", readTimeoutMs="
        + readTimeoutMs
        + '}';
  }
}

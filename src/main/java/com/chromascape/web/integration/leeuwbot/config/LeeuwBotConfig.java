package com.chromascape.web.integration.leeuwbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for LeeuwBot integration.
 *
 * <p>Configures connection to the ChromaScape Bridge Service for heartbeat reporting and log
 * streaming.
 */
@Configuration
@ConfigurationProperties(prefix = "leeuwbot.integration")
public class LeeuwBotConfig {

  /** Whether LeeuwBot integration is enabled. */
  private boolean enabled = false;

  /** URL of the ChromaScape Bridge Service. */
  private String bridgeUrl = "http://localhost:8092";

  /** API key for authenticating with the bridge service. */
  private String apiKey = "dev-token";

  /** Interval in seconds between heartbeats. */
  private int heartbeatIntervalSeconds = 30;

  /** Connection timeout in milliseconds. */
  private int connectionTimeoutMs = 5000;

  /** Read timeout in milliseconds. */
  private int readTimeoutMs = 10000;

  /** Whether to forward logs to the bridge service. */
  private boolean forwardLogs = true;

  /** Maximum batch size for log forwarding. */
  private int logBatchSize = 50;

  /** Interval in seconds for log batch forwarding. */
  private int logFlushIntervalSeconds = 10;

  /** Whether to report script lifecycle events. */
  private boolean reportScriptEvents = true;

  /** Whether to forward tick data to the bridge service. */
  private boolean forwardTickData = true;

  /** Timeout in milliseconds for tick data forwarding. */
  private int tickForwardTimeoutMs = 5000;

  /** Whether to use Go backend as primary storage (true) or hybrid mode (false). */
  private boolean useGoBackendStorage = false;

  // Screen streaming configuration (nested class for proper YAML binding)
  private StreamConfig stream = new StreamConfig();

  /** Nested configuration class for screen streaming properties. */
  public static class StreamConfig {
    private boolean enabled = true;
    private String url = "ws://192.168.1.6:8084/ws/screens/agent";
    private String hostId = "pc1";
    private String displayName = "ChromaScape";
    private int fps = 5;
    private int quality = 70;
    private int width = 800;
    private int height = 600;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getHostId() {
      return hostId;
    }

    public void setHostId(String hostId) {
      this.hostId = hostId;
    }

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public int getFps() {
      return fps;
    }

    public void setFps(int fps) {
      this.fps = fps;
    }

    public int getQuality() {
      return quality;
    }

    public void setQuality(int quality) {
      this.quality = quality;
    }

    public int getWidth() {
      return width;
    }

    public void setWidth(int width) {
      this.width = width;
    }

    public int getHeight() {
      return height;
    }

    public void setHeight(int height) {
      this.height = height;
    }
  }

  // Getters and Setters

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getBridgeUrl() {
    return bridgeUrl;
  }

  public void setBridgeUrl(String bridgeUrl) {
    this.bridgeUrl = bridgeUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public int getHeartbeatIntervalSeconds() {
    return heartbeatIntervalSeconds;
  }

  public void setHeartbeatIntervalSeconds(int heartbeatIntervalSeconds) {
    this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
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

  public boolean isForwardLogs() {
    return forwardLogs;
  }

  public void setForwardLogs(boolean forwardLogs) {
    this.forwardLogs = forwardLogs;
  }

  public int getLogBatchSize() {
    return logBatchSize;
  }

  public void setLogBatchSize(int logBatchSize) {
    this.logBatchSize = logBatchSize;
  }

  public int getLogFlushIntervalSeconds() {
    return logFlushIntervalSeconds;
  }

  public void setLogFlushIntervalSeconds(int logFlushIntervalSeconds) {
    this.logFlushIntervalSeconds = logFlushIntervalSeconds;
  }

  public boolean isReportScriptEvents() {
    return reportScriptEvents;
  }

  public void setReportScriptEvents(boolean reportScriptEvents) {
    this.reportScriptEvents = reportScriptEvents;
  }

  public boolean isForwardTickData() {
    return forwardTickData;
  }

  public void setForwardTickData(boolean forwardTickData) {
    this.forwardTickData = forwardTickData;
  }

  public int getTickForwardTimeoutMs() {
    return tickForwardTimeoutMs;
  }

  public void setTickForwardTimeoutMs(int tickForwardTimeoutMs) {
    this.tickForwardTimeoutMs = tickForwardTimeoutMs;
  }

  public boolean isUseGoBackendStorage() {
    return useGoBackendStorage;
  }

  public void setUseGoBackendStorage(boolean useGoBackendStorage) {
    this.useGoBackendStorage = useGoBackendStorage;
  }

  // Screen streaming getter and setter

  public StreamConfig getStream() {
    return stream;
  }

  public void setStream(StreamConfig stream) {
    this.stream = stream;
  }

  @Override
  public String toString() {
    return "LeeuwBotConfig{"
        + "enabled="
        + enabled
        + ", bridgeUrl='"
        + bridgeUrl
        + '\''
        + ", heartbeatIntervalSeconds="
        + heartbeatIntervalSeconds
        + ", forwardLogs="
        + forwardLogs
        + ", reportScriptEvents="
        + reportScriptEvents
        + '}';
  }
}

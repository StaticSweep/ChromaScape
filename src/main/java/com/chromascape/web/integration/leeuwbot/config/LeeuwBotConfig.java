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

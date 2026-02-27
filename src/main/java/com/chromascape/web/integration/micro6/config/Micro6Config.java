package com.chromascape.web.integration.micro6.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for Micro6 integration features.
 *
 * <p>Includes configuration for log file tailing/shipping to ChromaScape's log ingestion endpoint.
 */
@Configuration
@ConfigurationProperties(prefix = "micro6")
public class Micro6Config {

  private LogShipperConfig logShipper = new LogShipperConfig();

  public LogShipperConfig getLogShipper() {
    return logShipper;
  }

  public void setLogShipper(LogShipperConfig logShipper) {
    this.logShipper = logShipper;
  }

  /** Configuration for the log file shipper service. */
  public static class LogShipperConfig {
    /** Enable or disable log file shipping. */
    private boolean enabled = false;

    /** Absolute path to the micro6 client log file. */
    private String logFilePath = "";

    /** Source identifier for shipped logs. */
    private String sourceName = "micro6-shipper";

    /** Polling interval in milliseconds. */
    private int pollingIntervalMs = 5000;

    /** Maximum logs per batch before forcing a flush. */
    private int maxBatchSize = 100;

    /** Maximum number of lines to keep in deduplication history. */
    private int maxHistoryLines = 500;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getLogFilePath() {
      return logFilePath;
    }

    public void setLogFilePath(String logFilePath) {
      this.logFilePath = logFilePath;
    }

    public String getSourceName() {
      return sourceName;
    }

    public void setSourceName(String sourceName) {
      this.sourceName = sourceName;
    }

    public int getPollingIntervalMs() {
      return pollingIntervalMs;
    }

    public void setPollingIntervalMs(int pollingIntervalMs) {
      this.pollingIntervalMs = pollingIntervalMs;
    }

    public int getMaxBatchSize() {
      return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
      this.maxBatchSize = maxBatchSize;
    }

    public int getMaxHistoryLines() {
      return maxHistoryLines;
    }

    public void setMaxHistoryLines(int maxHistoryLines) {
      this.maxHistoryLines = maxHistoryLines;
    }
  }
}

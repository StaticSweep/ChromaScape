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
  private ExecutorCatalogConfig executorCatalog = new ExecutorCatalogConfig();
  private RuntimeSelectionConfig runtimeSelection = new RuntimeSelectionConfig();

  public LogShipperConfig getLogShipper() {
    return logShipper;
  }

  public void setLogShipper(LogShipperConfig logShipper) {
    this.logShipper = logShipper;
  }

  public ExecutorCatalogConfig getExecutorCatalog() {
    return executorCatalog;
  }

  public void setExecutorCatalog(ExecutorCatalogConfig executorCatalog) {
    this.executorCatalog = executorCatalog;
  }

  public RuntimeSelectionConfig getRuntimeSelection() {
    return runtimeSelection;
  }

  public void setRuntimeSelection(RuntimeSelectionConfig runtimeSelection) {
    this.runtimeSelection = runtimeSelection;
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

  /** Configuration for fetching task catalog from Micro6 local executor. */
  public static class ExecutorCatalogConfig {
    /** Enable remote catalog fetching. */
    private boolean enabled = true;

    /** Base URL to local executor service. */
    private String baseUrl = "http://127.0.0.1:8098";

    /** Request timeout in milliseconds. */
    private int timeoutMs = 5000;

    /** Refresh interval in seconds (cache TTL). */
    private int refreshSeconds = 30;

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

    public int getRefreshSeconds() {
      return refreshSeconds;
    }

    public void setRefreshSeconds(int refreshSeconds) {
      this.refreshSeconds = refreshSeconds;
    }
  }

  /**
   * Configuration for authenticated Micro6 executor selection through the leeuwbotapi runtime
   * registry.
   *
   * <p>The browser never submits a raw executor URL: ChromaScape resolves opaque endpoint
   * references against the authenticated registry and the registry fetches catalogs server-side. A
   * fixed local URL remains an explicit development fallback only (dev-fallback), visibly marked
   * {@code DEV_STATIC_ROUTE} and never usable as production or acceptance evidence.
   */
  public static class RuntimeSelectionConfig {
    /** Enable authenticated runtime selection. When false the legacy fixed-URL path is used. */
    private boolean enabled = true;

    /** Base URL of the leeuwbotapi mule service. */
    private String registryBaseUrl = "http://127.0.0.1:8084";

    /** Bearer token for the authenticated registry. Defaults to the shared ChromaScape API key. */
    private String registryAuthToken = "";

    /** Request timeout for registry calls in milliseconds. */
    private int requestTimeoutMs = 5000;

    /** Enable the explicit development-only fixed-URL fallback (DEV_STATIC_ROUTE). */
    private boolean devFallbackEnabled = false;

    /** Fixed local executor base URL used only by the development fallback. */
    private String devFallbackBaseUrl = "http://127.0.0.1:8098";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getRegistryBaseUrl() {
      return registryBaseUrl;
    }

    public void setRegistryBaseUrl(String registryBaseUrl) {
      this.registryBaseUrl = registryBaseUrl;
    }

    public String getRegistryAuthToken() {
      if (registryAuthToken != null && !registryAuthToken.isBlank()) {
        return registryAuthToken;
      }
      String env = System.getenv("CHROMASCAPE_API_KEY");
      return env != null && !env.isBlank() ? env : "dev-token";
    }

    public void setRegistryAuthToken(String registryAuthToken) {
      this.registryAuthToken = registryAuthToken;
    }

    public int getRequestTimeoutMs() {
      return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
      this.requestTimeoutMs = requestTimeoutMs;
    }

    public boolean isDevFallbackEnabled() {
      return devFallbackEnabled;
    }

    public void setDevFallbackEnabled(boolean devFallbackEnabled) {
      this.devFallbackEnabled = devFallbackEnabled;
    }

    public String getDevFallbackBaseUrl() {
      return devFallbackBaseUrl;
    }

    public void setDevFallbackBaseUrl(String devFallbackBaseUrl) {
      this.devFallbackBaseUrl = devFallbackBaseUrl;
    }
  }
}

package com.chromascape.web.debug.patchloop.service.provider.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for MCP (Model Context Protocol) integration.
 *
 * <p>Configures connection to the MCP server for Claude analysis via the MCP protocol. Properties
 * are bound from the {@code analysis.mcp} prefix in application.yml.
 */
@Configuration
@ConfigurationProperties(prefix = "analysis.mcp")
public class McpConfigProperties {

  /** URL of the MCP server. */
  private String serverUrl = "http://localhost:8765";

  /** Request timeout in seconds. */
  private int timeoutSeconds = 120;

  /** Maximum number of retry attempts for transient failures. */
  private int maxRetries = 3;

  /**
   * Returns the MCP server URL.
   *
   * @return the server URL
   */
  public String getServerUrl() {
    return serverUrl;
  }

  /**
   * Sets the MCP server URL.
   *
   * @param serverUrl the server URL
   */
  public void setServerUrl(String serverUrl) {
    this.serverUrl = serverUrl;
  }

  /**
   * Returns the request timeout in seconds.
   *
   * @return the timeout in seconds
   */
  public int getTimeoutSeconds() {
    return timeoutSeconds;
  }

  /**
   * Sets the request timeout in seconds.
   *
   * @param timeoutSeconds the timeout in seconds (must be between 1 and 600)
   * @throws IllegalArgumentException if timeoutSeconds is outside valid range
   */
  public void setTimeoutSeconds(int timeoutSeconds) {
    if (timeoutSeconds < 1 || timeoutSeconds > 600) {
      throw new IllegalArgumentException(
          "timeout-seconds must be between 1 and 600 (got: " + timeoutSeconds + ")");
    }
    this.timeoutSeconds = timeoutSeconds;
  }

  /**
   * Returns the maximum number of retry attempts.
   *
   * @return the maximum retries
   */
  public int getMaxRetries() {
    return maxRetries;
  }

  /**
   * Sets the maximum number of retry attempts.
   *
   * @param maxRetries the maximum retries (must be between 0 and 10)
   * @throws IllegalArgumentException if maxRetries is outside valid range
   */
  public void setMaxRetries(int maxRetries) {
    if (maxRetries < 0 || maxRetries > 10) {
      throw new IllegalArgumentException(
          "max-retries must be between 0 and 10 (got: " + maxRetries + ")");
    }
    this.maxRetries = maxRetries;
  }

  /**
   * Returns the connection timeout in milliseconds for HTTP client configuration.
   *
   * <p>Uses a fixed 5-second connection timeout, independent of request timeout.
   *
   * @return the connection timeout in milliseconds
   */
  public int getConnectionTimeoutMs() {
    return 5000;
  }

  /**
   * Returns the request timeout in milliseconds for HTTP client configuration.
   *
   * @return the request timeout in milliseconds
   */
  public int getRequestTimeoutMs() {
    return timeoutSeconds * 1000;
  }

  @Override
  public String toString() {
    return "McpConfigProperties{"
        + "serverUrl='"
        + serverUrl
        + '\''
        + ", timeoutSeconds="
        + timeoutSeconds
        + ", maxRetries="
        + maxRetries
        + '}';
  }
}

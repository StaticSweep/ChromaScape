package com.chromascape.web.debug.patchloop.service.provider.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * HTTP transport client for MCP (Model Context Protocol) server communication.
 *
 * <p>This client handles low-level HTTP communication with the MCP server, including health checks,
 * request/response serialization, and retry logic with exponential backoff.
 */
@Service
public class McpTransportClient {

  private static final Logger logger = LoggerFactory.getLogger(McpTransportClient.class);

  private static final String HEALTH_ENDPOINT = "/health";
  private static final String ANALYZE_ENDPOINT = "/analyze";

  private final McpConfigProperties config;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  /**
   * Creates a new MCP transport client.
   *
   * @param config the MCP configuration properties
   */
  @Autowired
  public McpTransportClient(McpConfigProperties config) {
    this.config = config;
    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());

    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(config.getConnectionTimeoutMs()))
            .build();

    logger.info(
        "McpTransportClient initialized - serverUrl: {}, timeout: {}s, maxRetries: {}",
        config.getServerUrl(),
        config.getTimeoutSeconds(),
        config.getMaxRetries());
  }

  /**
   * Performs a health check on the MCP server.
   *
   * <p>This method will retry on transient failures up to the configured max-retries limit with
   * exponential backoff.
   *
   * @return the health check response
   * @throws McpTransportException if the health check fails after all retries
   */
  public McpHealthResponse healthCheck() throws McpTransportException {
    String healthUrl = buildHealthUrl();

    logger.debug("Performing MCP health check: {}", healthUrl);

    int attempt = 0;
    int maxAttempts = config.getMaxRetries() + 1; // Initial attempt + retries

    while (attempt < maxAttempts) {
      attempt++;

      try {
        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(URI.create(healthUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .GET()
                .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          logger.debug(
              "MCP health check successful (attempt {}): status {}",
              attempt,
              response.statusCode());
          return parseHealthResponse(response.body());
        } else {
          logger.warn(
              "MCP health check failed (attempt {}): HTTP {}", attempt, response.statusCode());

          McpTransportException.McpErrorType errorType =
              determineHttpErrorType(response.statusCode());
          boolean isRetryable = isRetryableHttpError(response.statusCode());

          if (!isRetryable || attempt >= maxAttempts) {
            throw new McpTransportException(
                errorType,
                "MCP server returned HTTP " + response.statusCode() + ": " + response.body(),
                isRetryable);
          }
        }

      } catch (java.net.ConnectException e) {
        logger.warn("MCP health check failed (attempt {}): Connection refused", attempt);

        if (attempt >= maxAttempts) {
          throw new McpTransportException(
              McpTransportException.McpErrorType.CONNECTION_REFUSED,
              "Cannot connect to MCP server at " + healthUrl + ": " + e.getMessage(),
              e,
              false);
        }

      } catch (java.net.http.HttpTimeoutException e) {
        logger.warn("MCP health check failed (attempt {}): Timeout", attempt);

        if (attempt >= maxAttempts) {
          throw new McpTransportException(
              McpTransportException.McpErrorType.TIMEOUT,
              "MCP server request timed out after " + config.getRequestTimeoutMs() + "ms",
              e,
              true);
        }

      } catch (McpTransportException e) {
        // Re-throw our own exceptions without modification
        throw e;

      } catch (Exception e) {
        logger.warn("MCP health check failed (attempt {}): {}", attempt, e.getMessage());

        if (attempt >= maxAttempts) {
          throw new McpTransportException(
              McpTransportException.McpErrorType.UNKNOWN,
              "Unexpected error during MCP health check: " + e.getMessage(),
              e,
              false);
        }
      }

      // Exponential backoff before retry
      if (attempt < maxAttempts) {
        long backoffMs = calculateBackoff(attempt);
        logger.debug("Retrying MCP health check in {}ms", backoffMs);
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new McpTransportException(
              McpTransportException.McpErrorType.UNKNOWN,
              "Health check interrupted during backoff",
              ie,
              false);
        }
      }
    }

    // Should never reach here, but for completeness
    throw new McpTransportException(
        McpTransportException.McpErrorType.UNKNOWN,
        "Health check failed after " + maxAttempts + " attempts",
        false);
  }

  /**
   * Performs an analysis request to the MCP server.
   *
   * <p>This method will retry on transient failures up to the configured max-retries limit with
   * exponential backoff.
   *
   * @param request the analyze request
   * @return the analyze response
   * @throws McpTransportException if the analysis fails after all retries
   */
  public McpAnalyzeResponse analyze(McpAnalyzeRequest request) throws McpTransportException {
    String analyzeUrl = buildAnalyzeUrl();

    logger.debug("Performing MCP analysis: {}", analyzeUrl);

    int attempt = 0;
    int maxAttempts = config.getMaxRetries() + 1; // Initial attempt + retries

    while (attempt < maxAttempts) {
      attempt++;

      try {
        // Serialize request
        String requestBody;
        try {
          requestBody = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
          throw new McpTransportException(
              McpTransportException.McpErrorType.SERIALIZATION_ERROR,
              "Failed to serialize MCP analyze request: " + e.getMessage(),
              e,
              false);
        }

        HttpRequest httpRequest =
            HttpRequest.newBuilder()
                .uri(URI.create(analyzeUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.getRequestTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response =
            httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
          logger.debug(
              "MCP analyze successful (attempt {}): status {}", attempt, response.statusCode());
          return parseAnalyzeResponse(response.body());
        } else {
          logger.warn("MCP analyze failed (attempt {}): HTTP {}", attempt, response.statusCode());

          McpTransportException.McpErrorType errorType =
              determineHttpErrorType(response.statusCode());
          boolean isRetryable = isRetryableHttpError(response.statusCode());

          if (!isRetryable || attempt >= maxAttempts) {
            throw new McpTransportException(
                errorType,
                "MCP server returned HTTP " + response.statusCode() + ": " + response.body(),
                isRetryable);
          }
        }

      } catch (java.net.ConnectException e) {
        logger.warn("MCP analyze failed (attempt {}): Connection refused", attempt);

        if (attempt >= maxAttempts) {
          throw new McpTransportException(
              McpTransportException.McpErrorType.CONNECTION_REFUSED,
              "Cannot connect to MCP server at " + analyzeUrl + ": " + e.getMessage(),
              e,
              false);
        }

      } catch (java.net.http.HttpTimeoutException e) {
        logger.warn("MCP analyze failed (attempt {}): Timeout", attempt);

        if (attempt >= maxAttempts) {
          throw new McpTransportException(
              McpTransportException.McpErrorType.TIMEOUT,
              "MCP server request timed out after " + config.getRequestTimeoutMs() + "ms",
              e,
              true);
        }

      } catch (McpTransportException e) {
        // Re-throw our own exceptions without modification
        throw e;

      } catch (Exception e) {
        logger.warn("MCP analyze failed (attempt {}): {}", attempt, e.getMessage());

        if (attempt >= maxAttempts) {
          throw new McpTransportException(
              McpTransportException.McpErrorType.UNKNOWN,
              "Unexpected error during MCP analyze: " + e.getMessage(),
              e,
              false);
        }
      }

      // Exponential backoff before retry
      if (attempt < maxAttempts) {
        long backoffMs = calculateBackoff(attempt);
        logger.debug("Retrying MCP analyze in {}ms", backoffMs);
        try {
          Thread.sleep(backoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new McpTransportException(
              McpTransportException.McpErrorType.UNKNOWN,
              "Analyze interrupted during backoff",
              ie,
              false);
        }
      }
    }

    // Should never reach here, but for completeness
    throw new McpTransportException(
        McpTransportException.McpErrorType.UNKNOWN,
        "Analyze failed after " + maxAttempts + " attempts",
        false);
  }

  /**
   * Builds the full health check URL from server URL.
   *
   * @return the health check URL
   */
  private String buildHealthUrl() {
    String baseUrl = config.getServerUrl();
    if (baseUrl.endsWith("/")) {
      return baseUrl + HEALTH_ENDPOINT.substring(1);
    }
    return baseUrl + HEALTH_ENDPOINT;
  }

  /**
   * Builds the full analyze URL from server URL.
   *
   * @return the analyze URL
   */
  private String buildAnalyzeUrl() {
    String baseUrl = config.getServerUrl();
    if (baseUrl.endsWith("/")) {
      return baseUrl + ANALYZE_ENDPOINT.substring(1);
    }
    return baseUrl + ANALYZE_ENDPOINT;
  }

  /**
   * Parses the health check response body.
   *
   * @param body the response body
   * @return the parsed health response
   * @throws McpTransportException if parsing fails
   */
  private McpHealthResponse parseHealthResponse(String body) throws McpTransportException {
    try {
      Map<String, Object> response = objectMapper.readValue(body, HashMap.class);

      boolean isHealthy = parseBooleanField(response, "healthy", false);
      String serverVersion = parseStringField(response, "serverVersion");
      @SuppressWarnings("unchecked")
      Map<String, Object> metadata =
          (Map<String, Object>) response.getOrDefault("metadata", Map.of());

      return new McpHealthResponse(isHealthy, serverVersion, metadata);

    } catch (Exception e) {
      throw new McpTransportException(
          McpTransportException.McpErrorType.INVALID_RESPONSE,
          "Failed to parse MCP health response: " + e.getMessage(),
          e,
          false); // NOT retryable - server is broken
    }
  }

  /**
   * Parses a boolean field from a map, returning a default if not present or invalid.
   *
   * @param map the map to parse from
   * @param key the key to parse
   * @param defaultValue the default value
   * @return the parsed boolean value
   */
  private boolean parseBooleanField(Map<String, Object> map, String key, boolean defaultValue) {
    Object value = map.get(key);
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof String) {
      return Boolean.parseBoolean((String) value);
    }
    return defaultValue;
  }

  /**
   * Parses a string field from a map, returning null if not present.
   *
   * @param map the map to parse from
   * @param key the key to parse
   * @return the parsed string value, or null
   */
  private String parseStringField(Map<String, Object> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    return value.toString();
  }

  /**
   * Parses the analyze response body.
   *
   * @param body the response body
   * @return the parsed analyze response
   * @throws McpTransportException if parsing fails
   */
  private McpAnalyzeResponse parseAnalyzeResponse(String body) throws McpTransportException {
    try {
      return objectMapper.readValue(body, McpAnalyzeResponse.class);
    } catch (Exception e) {
      throw new McpTransportException(
          McpTransportException.McpErrorType.INVALID_RESPONSE,
          "Failed to parse MCP analyze response: " + e.getMessage(),
          e,
          false); // NOT retryable - server is broken
    }
  }

  /**
   * Determines the error type from an HTTP status code.
   *
   * @param statusCode the HTTP status code
   * @return the corresponding error type
   */
  private McpTransportException.McpErrorType determineHttpErrorType(int statusCode) {
    if (statusCode == 401 || statusCode == 403) {
      return McpTransportException.McpErrorType.AUTH_FAILED;
    } else if (statusCode == 429) {
      return McpTransportException.McpErrorType.RATE_LIMIT;
    } else if (statusCode >= 500) {
      return McpTransportException.McpErrorType.HTTP_ERROR;
    } else if (statusCode >= 400) {
      return McpTransportException.McpErrorType.CONFIGURATION_ERROR;
    }
    return McpTransportException.McpErrorType.UNKNOWN;
  }

  /**
   * Determines if an HTTP error is retryable based on status code.
   *
   * @param statusCode the HTTP status code
   * @return true if the error is retryable
   */
  private boolean isRetryableHttpError(int statusCode) {
    // 5xx errors are retryable, 4xx are not (except 429)
    return statusCode >= 500 || statusCode == 429;
  }

  /**
   * Calculates exponential backoff delay for retries.
   *
   * <p>Uses base delay of 100ms with exponential growth: 100ms, 200ms, 400ms, 800ms, etc.
   *
   * @param attempt the attempt number (1-based)
   * @return the backoff delay in milliseconds
   */
  private long calculateBackoff(int attempt) {
    long baseDelayMs = 100;
    long maxDelayMs = 5000;
    long delay = baseDelayMs * (1L << (attempt - 1)); // 2^(attempt-1) * base
    return Math.min(delay, maxDelayMs);
  }

  /**
   * Returns the configuration properties for this client.
   *
   * @return the config properties
   */
  public McpConfigProperties getConfig() {
    return config;
  }
}

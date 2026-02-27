package com.chromascape.web.debug.patchloop.service.provider.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Test suite for {@link McpTransportClient}.
 *
 * <p>Tests HTTP communication with MCP server including health checks, retry logic, timeout
 * handling, and error response parsing.
 */
class McpTransportClientTest {

  private HttpServer testServer;
  private int testServerPort;
  private McpConfigProperties config;
  private McpTransportClient client;

  @BeforeEach
  void setUp() throws IOException {
    // Start a test HTTP server on an available port
    testServer = HttpServer.create(new InetSocketAddress(0), 0);
    testServerPort = testServer.getAddress().getPort();

    // Create config pointing to test server
    config = new McpConfigProperties();
    config.setServerUrl("http://localhost:" + testServerPort);
    config.setTimeoutSeconds(5);
    config.setMaxRetries(2);

    // Create client with test config
    client = new McpTransportClient(config);
  }

  @AfterEach
  void tearDown() {
    if (testServer != null) {
      testServer.stop(0);
    }
  }

  @Test
  @DisplayName("healthCheck() returns true when MCP server returns healthy status")
  void testHealthCheckReturnsTrueWhenServerIsHealthy() throws Exception {
    // Arrange: server returns healthy response
    testServer.createContext(
        "/health",
        exchange -> {
          String response = "{\"healthy\":true,\"serverVersion\":\"1.0.0\"}";
          sendJsonResponse(exchange, 200, response);
        });

    testServer.start();

    // Act
    McpHealthResponse result = client.healthCheck();

    // Assert
    assertTrue(result.isHealthy(), "Health check should return healthy");
    assertEquals("1.0.0", result.getServerVersion(), "Should return server version");
  }

  @Test
  @DisplayName("healthCheck() returns false when MCP server returns unhealthy status")
  void testHealthCheckReturnsFalseWhenServerIsUnhealthy() throws Exception {
    // Arrange: server returns unhealthy response
    testServer.createContext(
        "/health",
        exchange -> {
          String response = "{\"healthy\":false}";
          sendJsonResponse(exchange, 200, response);
        });

    testServer.start();

    // Act
    McpHealthResponse result = client.healthCheck();

    // Assert
    assertFalse(result.isHealthy(), "Health check should return unhealthy");
  }

  @Test
  @DisplayName("healthCheck() returns unhealthy when healthy field is missing")
  void testHealthCheckReturnsUnhealthyWhenFieldMissing() throws Exception {
    // Arrange: server returns response without "healthy" field
    testServer.createContext(
        "/health",
        exchange -> {
          String response = "{}"; // Empty JSON, no "healthy" field
          sendJsonResponse(exchange, 200, response);
        });

    testServer.start();

    // Act
    McpHealthResponse result = client.healthCheck();

    // Assert
    assertFalse(result.isHealthy(), "Health check should return unhealthy when field missing");
  }

  @Test
  @DisplayName("healthCheck() returns unhealthy when healthy field is invalid/non-boolean")
  void testHealthCheckReturnsUnhealthyWhenFieldInvalid() throws Exception {
    // Arrange: server returns response with non-boolean "healthy" value
    testServer.createContext(
        "/health",
        exchange -> {
          String response = "{\"healthy\": 123}"; // Number instead of boolean
          sendJsonResponse(exchange, 200, response);
        });

    testServer.start();

    // Act
    McpHealthResponse result = client.healthCheck();

    // Assert
    assertFalse(result.isHealthy(), "Health check should return unhealthy for non-boolean value");
  }

  @Test
  @DisplayName("healthCheck() throws CONNECTION_REFUSED or TIMEOUT on connection refused")
  void testHealthCheckThrowsOnConnectionRefused() {
    // Arrange: don't start the server, so connection will be refused
    // (server is created but not started in setUp())

    // Act & Assert
    McpTransportException exception =
        assertThrows(McpTransportException.class, () -> client.healthCheck());

    // Note: HttpClient may throw TIMEOUT before CONNECTION_REFUSED depending on timing
    McpTransportException.McpErrorType errorType = exception.getErrorType();
    assertTrue(
        errorType == McpTransportException.McpErrorType.CONNECTION_REFUSED
            || errorType == McpTransportException.McpErrorType.TIMEOUT,
        "Error type should be CONNECTION_REFUSED or TIMEOUT, got: " + errorType);
    assertTrue(
        exception.getMessage().contains("Cannot connect")
            || exception.getMessage().contains("timed out")
            || exception.getMessage().contains("Timeout"),
        "Error message should mention connection failure or timeout");
  }

  @Test
  @DisplayName("healthCheck() throws TIMEOUT on connection timeout")
  void testHealthCheckThrowsOnTimeout() throws Exception {
    // Arrange: server takes too long to respond
    config.setTimeoutSeconds(1); // 1 second timeout for faster test
    client = new McpTransportClient(config);

    testServer.createContext(
        "/health",
        exchange -> {
          try {
            Thread.sleep(2000); // Sleep longer than timeout
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          sendJsonResponse(exchange, 200, "{\"healthy\":true}");
        });

    testServer.start();

    // Act & Assert
    McpTransportException exception =
        assertThrows(McpTransportException.class, () -> client.healthCheck());

    assertEquals(
        McpTransportException.McpErrorType.TIMEOUT,
        exception.getErrorType(),
        "Error type should be TIMEOUT");
    assertTrue(exception.isRetryable(), "Timeout errors should be retryable");
  }

  @Test
  @DisplayName("healthCheck() throws HTTP_ERROR on 500 status code")
  void testHealthCheckThrowsOnHttp500() throws Exception {
    // Arrange: server returns 500 error
    testServer.createContext(
        "/health",
        exchange -> {
          sendJsonResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
        });

    testServer.start();

    // Act & Assert
    McpTransportException exception =
        assertThrows(McpTransportException.class, () -> client.healthCheck());

    assertEquals(
        McpTransportException.McpErrorType.HTTP_ERROR,
        exception.getErrorType(),
        "Error type should be HTTP_ERROR for 500");
  }

  @Test
  @DisplayName("healthCheck() throws CONFIGURATION_ERROR on 400 status code")
  void testHealthCheckThrowsOnHttp400() throws Exception {
    // Arrange: server returns 400 error
    testServer.createContext(
        "/health",
        exchange -> {
          sendJsonResponse(exchange, 400, "{\"error\":\"Bad request\"}");
        });

    testServer.start();

    // Act & Assert
    McpTransportException exception =
        assertThrows(McpTransportException.class, () -> client.healthCheck());

    assertEquals(
        McpTransportException.McpErrorType.CONFIGURATION_ERROR,
        exception.getErrorType(),
        "Error type should be CONFIGURATION_ERROR for 400");
    assertFalse(exception.isRetryable(), "4xx errors (except 429) should not be retryable");
  }

  @Test
  @DisplayName("healthCheck() throws HTTP_ERROR on 429 with isRetryable=true")
  void testHealthCheckThrowsOnHttp429() throws Exception {
    // Arrange: server returns 429 error
    testServer.createContext(
        "/health",
        exchange -> {
          sendJsonResponse(exchange, 429, "{\"error\":\"Too many requests\"}");
        });

    testServer.start();

    // Act & Assert
    McpTransportException exception =
        assertThrows(McpTransportException.class, () -> client.healthCheck());

    assertEquals(
        McpTransportException.McpErrorType.HTTP_ERROR,
        exception.getErrorType(),
        "Error type should be HTTP_ERROR for 429");
    assertTrue(exception.isRetryable(), "429 should be retryable");
  }

  @Test
  @DisplayName("healthCheck() retries on transient failures")
  void testHealthCheckRetriesOnTransientFailures() throws Exception {
    // Arrange: server fails first 2 attempts, succeeds on 3rd
    AtomicInteger attemptCount = new AtomicInteger(0);

    testServer.createContext(
        "/health",
        exchange -> {
          int attempt = attemptCount.incrementAndGet();
          if (attempt < 3) {
            sendJsonResponse(exchange, 503, "{\"error\":\"Service unavailable\"}");
          } else {
            sendJsonResponse(exchange, 200, "{\"healthy\":true}");
          }
        });

    testServer.start();

    // Act
    McpHealthResponse result = client.healthCheck();

    // Assert
    assertTrue(result.isHealthy(), "Health check should succeed after retries");
    assertEquals(3, attemptCount.get(), "Should have made 3 attempts");
  }

  @Test
  @DisplayName("healthCheck() respects max-retries configuration")
  void testHealthCheckRespectsMaxRetries() throws Exception {
    // Arrange: configure for only 1 retry
    config.setMaxRetries(1);
    client = new McpTransportClient(config);

    AtomicInteger attemptCount = new AtomicInteger(0);

    testServer.createContext(
        "/health",
        exchange -> {
          attemptCount.incrementAndGet();
          sendJsonResponse(exchange, 503, "{\"error\":\"Service unavailable\"}");
        });

    testServer.start();

    // Act & Assert
    assertThrows(McpTransportException.class, () -> client.healthCheck());

    // Should have made initial attempt + 1 retry = 2 total
    assertEquals(
        2, attemptCount.get(), "Should respect max-retries configuration (1 retry = 2 attempts)");
  }

  @Test
  @DisplayName("healthCheck() throws INVALID_RESPONSE on malformed JSON")
  void testHealthCheckThrowsOnInvalidJson() throws Exception {
    // Arrange: server returns 200 with invalid JSON
    testServer.createContext(
        "/health",
        exchange -> {
          sendJsonResponse(exchange, 200, "not valid json");
        });

    testServer.start();

    // Act & Assert
    McpTransportException exception =
        assertThrows(McpTransportException.class, () -> client.healthCheck());
    assertEquals(
        McpTransportException.McpErrorType.INVALID_RESPONSE,
        exception.getErrorType(),
        "Error type should be INVALID_RESPONSE for malformed JSON");
    assertFalse(exception.isRetryable(), "INVALID_RESPONSE should not be retryable");
  }

  @Test
  @DisplayName("healthCheck() handles response with metadata")
  void testHealthCheckHandlesMetadata() throws Exception {
    // Arrange: server returns response with metadata
    testServer.createContext(
        "/health",
        exchange -> {
          String response =
              "{\"healthy\":true,\"serverVersion\":\"1.0.0\",\"metadata\":{\"features\":[\"analyze\",\"inspect\"]}}";
          sendJsonResponse(exchange, 200, response);
        });

    testServer.start();

    // Act
    McpHealthResponse result = client.healthCheck();

    // Assert
    assertTrue(result.isHealthy());
    assertEquals("1.0.0", result.getServerVersion());
    assertTrue(result.getMetadata().containsKey("features"));
  }

  @Test
  @DisplayName("healthCheck() handles missing optional fields")
  void testHealthCheckHandlesMissingOptionalFields() throws Exception {
    // Arrange: server returns minimal healthy response
    testServer.createContext(
        "/health",
        exchange -> {
          String response = "{\"healthy\":true}";
          sendJsonResponse(exchange, 200, response);
        });

    testServer.start();

    // Act
    McpHealthResponse result = client.healthCheck();

    // Assert
    assertTrue(result.isHealthy());
    assertNull(result.getServerVersion(), "serverVersion should be null when not provided");
    assertTrue(result.getMetadata().isEmpty(), "metadata should be empty when not provided");
  }

  @Test
  @DisplayName("Constructor initializes HttpClient with correct configuration")
  void testConstructorInitializesHttpClient() {
    // Assert
    assertNotNull(client, "Client should be constructed");
    assertEquals(
        "http://localhost:" + testServerPort,
        client.getConfig().getServerUrl(),
        "Server URL should match config");
    assertEquals(5, client.getConfig().getTimeoutSeconds(), "Timeout should match config");
    assertEquals(2, client.getConfig().getMaxRetries(), "Max retries should match config");
  }

  // Helper methods

  private void sendJsonResponse(HttpExchange exchange, int statusCode, String response)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    sendResponse(exchange, statusCode, response);
  }

  private void sendResponse(HttpExchange exchange, int statusCode, String response)
      throws IOException {
    exchange.sendResponseHeaders(statusCode, response.getBytes().length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(response.getBytes());
    }
  }
}

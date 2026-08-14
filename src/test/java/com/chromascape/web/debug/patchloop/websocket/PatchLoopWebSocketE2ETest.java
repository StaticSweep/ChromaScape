package com.chromascape.web.debug.patchloop.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * End-to-end integration tests for PatchLoop WebSocket functionality.
 *
 * <p>These tests verify:
 *
 * <ul>
 *   <li>Base connection to /ws/debug/patch-loop
 *   <li>Execution-scoped connection to /ws/debug/patch-loop/{executionId}
 *   <li>Message broadcasting to all sessions
 *   <li>Execution-scoped broadcasting
 * </ul>
 *
 * <p>To run these tests, set the system property:
 *
 * <pre>
 * ./gradlew test -DrunE2E=true --tests PatchLoopWebSocketE2ETest
 * </pre>
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfSystemProperty(named = "runE2E", matches = "true")
class PatchLoopWebSocketE2ETest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @LocalServerPort private int port;

  @Autowired private PatchLoopWebSocketHandler patchLoopWebSocketHandler;

  private String getBaseWsUrl() {
    return "ws://localhost:" + port + "/ws/debug/patch-loop";
  }

  private String getExecutionWsUrl(String executionId) {
    return "ws://localhost:" + port + "/ws/debug/patch-loop/" + executionId;
  }

  /**
   * Scenario 1: Base Connection Test Verify clients can connect to /ws/debug/patch-loop without
   * execution ID
   */
  @Test
  void testBaseConnection() throws Exception {
    CountDownLatch connectedLatch = new CountDownLatch(1);
    AtomicReference<String> connectedMessage = new AtomicReference<>();

    WebSocketClient client =
        new WebSocketClient(URI.create(getBaseWsUrl())) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            connectedLatch.countDown();
          }

          @Override
          public void onMessage(String message) {
            try {
              JsonNode json = objectMapper.readTree(message);
              if ("CONNECTED".equals(json.get("type").asText())) {
                connectedMessage.set(message);
              }
            } catch (Exception e) {
              // Ignore parse errors
            }
          }

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {
            ex.printStackTrace();
          }
        };

    client.connect();
    assertTrue(connectedLatch.await(10, TimeUnit.SECONDS), "Connection should be established");

    // Wait for welcome message
    Thread.sleep(500);
    assertNotNull(connectedMessage.get(), "Should receive CONNECTED message");

    client.close();
  }

  /**
   * Scenario 2: Execution-Scoped Connection Test Verify clients can connect with execution ID in
   * URL
   */
  @Test
  void testExecutionScopedConnection() throws Exception {
    String executionId = "test-exec-123";
    CountDownLatch connectedLatch = new CountDownLatch(1);
    AtomicReference<String> connectedMessage = new AtomicReference<>();

    WebSocketClient client =
        new WebSocketClient(URI.create(getExecutionWsUrl(executionId))) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            connectedLatch.countDown();
          }

          @Override
          public void onMessage(String message) {
            try {
              JsonNode json = objectMapper.readTree(message);
              if ("CONNECTED".equals(json.get("type").asText())) {
                connectedMessage.set(message);
              }
            } catch (Exception e) {
              // Ignore parse errors
            }
          }

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {
            ex.printStackTrace();
          }
        };

    client.connect();
    assertTrue(connectedLatch.await(10, TimeUnit.SECONDS), "Connection should be established");

    // Wait for welcome message
    Thread.sleep(500);
    assertNotNull(connectedMessage.get(), "Should receive CONNECTED message");

    client.close();
  }

  /**
   * Scenario 3: Multiple Clients Connection Test Verify multiple clients can connect to base
   * endpoint
   */
  @Test
  void testMultipleClientsBaseConnection() throws Exception {
    int clientCount = 3;
    CountDownLatch allConnected = new CountDownLatch(clientCount);

    WebSocketClient[] clients = new WebSocketClient[clientCount];

    for (int i = 0; i < clientCount; i++) {
      final int index = i;
      clients[i] =
          new WebSocketClient(URI.create(getBaseWsUrl())) {
            @Override
            public void onOpen(ServerHandshake handshake) {
              allConnected.countDown();
            }

            @Override
            public void onMessage(String message) {}

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
          };
      clients[i].connect();
    }

    assertTrue(
        allConnected.await(15, TimeUnit.SECONDS), "All clients should connect within timeout");

    // Clean up
    for (WebSocketClient client : clients) {
      client.close();
    }
  }

  /**
   * Scenario 4: Mixed Base and Scoped Connections Verify clients can connect to both base and
   * execution-scoped endpoints simultaneously
   */
  @Test
  void testMixedBaseAndScopedConnections() throws Exception {
    CountDownLatch allConnected = new CountDownLatch(3);

    // Base connection
    WebSocketClient baseClient =
        new WebSocketClient(URI.create(getBaseWsUrl())) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            allConnected.countDown();
          }

          @Override
          public void onMessage(String message) {}

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {}
        };

    // Execution-scoped connection A
    WebSocketClient execClientA =
        new WebSocketClient(URI.create(getExecutionWsUrl("exec-A"))) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            allConnected.countDown();
          }

          @Override
          public void onMessage(String message) {}

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {}
        };

    // Execution-scoped connection B
    WebSocketClient execClientB =
        new WebSocketClient(URI.create(getExecutionWsUrl("exec-B"))) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            allConnected.countDown();
          }

          @Override
          public void onMessage(String message) {}

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {}
        };

    baseClient.connect();
    execClientA.connect();
    execClientB.connect();

    assertTrue(
        allConnected.await(15, TimeUnit.SECONDS), "All clients should connect within timeout");

    // Clean up
    baseClient.close();
    execClientA.close();
    execClientB.close();
  }

  /**
   * Scenario 5: Broadcast to All Sessions Test Verify messages reach all clients when no
   * execution-scoped subscribers exist
   */
  @Test
  void testBroadcastToAllSessions() throws Exception {
    int clientCount = 2;
    CountDownLatch allConnected = new CountDownLatch(clientCount);
    CountDownLatch messageReceived = new CountDownLatch(clientCount);
    AtomicInteger receivedCount = new AtomicInteger(0);

    WebSocketClient[] clients = new WebSocketClient[clientCount];

    for (int i = 0; i < clientCount; i++) {
      final int index = i;
      clients[i] =
          new WebSocketClient(URI.create(getBaseWsUrl())) {
            @Override
            public void onOpen(ServerHandshake handshake) {
              allConnected.countDown();
            }

            @Override
            public void onMessage(String message) {
              try {
                JsonNode json = objectMapper.readTree(message);
                String type = json.has("type") ? json.get("type").asText() : "";
                if (!"CONNECTED".equals(type)) {
                  receivedCount.incrementAndGet();
                  messageReceived.countDown();
                }
              } catch (Exception e) {
                // Ignore parse errors
              }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {}

            @Override
            public void onError(Exception ex) {}
          };
      clients[i].connect();
    }

    assertTrue(allConnected.await(15, TimeUnit.SECONDS), "All clients should connect");

    // Wait for welcome messages
    Thread.sleep(500);

    // Broadcast a status change message
    patchLoopWebSocketHandler.broadcastStatusChange("test-execution", "RUNNING");

    // Verify both clients receive the message
    assertTrue(
        messageReceived.await(5, TimeUnit.SECONDS), "Both clients should receive the broadcast");

    assertEquals(clientCount, receivedCount.get(), "Message count should match client count");

    // Clean up
    for (WebSocketClient client : clients) {
      client.close();
    }
  }

  /**
   * Scenario 6: Execution-Scoped Broadcast Test Verify messages only reach clients subscribed to a
   * specific execution
   */
  @Test
  void testExecutionScopedBroadcast() throws Exception {
    String executionId = "test-exec-scoped";
    CountDownLatch allConnected = new CountDownLatch(3);
    AtomicInteger execACount = new AtomicInteger(0);
    AtomicInteger execBCount = new AtomicInteger(0);
    AtomicInteger baseCount = new AtomicInteger(0);

    // Client A - connected to exec-A
    WebSocketClient clientA =
        new WebSocketClient(URI.create(getExecutionWsUrl(executionId))) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            allConnected.countDown();
          }

          @Override
          public void onMessage(String message) {
            try {
              JsonNode json = objectMapper.readTree(message);
              if (!"CONNECTED".equals(json.get("type").asText())) {
                execACount.incrementAndGet();
              }
            } catch (Exception e) {
            }
          }

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {}
        };

    // Client B - connected to different execution
    WebSocketClient clientB =
        new WebSocketClient(URI.create(getExecutionWsUrl("other-exec"))) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            allConnected.countDown();
          }

          @Override
          public void onMessage(String message) {
            try {
              JsonNode json = objectMapper.readTree(message);
              if (!"CONNECTED".equals(json.get("type").asText())) {
                execBCount.incrementAndGet();
              }
            } catch (Exception e) {
            }
          }

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {}
        };

    // Client C - connected to base
    WebSocketClient clientC =
        new WebSocketClient(URI.create(getBaseWsUrl())) {
          @Override
          public void onOpen(ServerHandshake handshake) {
            allConnected.countDown();
          }

          @Override
          public void onMessage(String message) {
            try {
              JsonNode json = objectMapper.readTree(message);
              if (!"CONNECTED".equals(json.get("type").asText())) {
                baseCount.incrementAndGet();
              }
            } catch (Exception e) {
            }
          }

          @Override
          public void onClose(int code, String reason, boolean remote) {}

          @Override
          public void onError(Exception ex) {}
        };

    clientA.connect();
    clientB.connect();
    clientC.connect();

    assertTrue(allConnected.await(15, TimeUnit.SECONDS), "All clients should connect");

    // Wait for welcome messages
    Thread.sleep(500);

    // Broadcast to test-exec-scoped execution
    patchLoopWebSocketHandler.broadcastStatusChange(executionId, "RUNNING");

    // Wait for messages to propagate
    Thread.sleep(1000);

    // Only client A should receive the message
    assertEquals(1, execACount.get(), "Client A should receive the message");
    assertEquals(0, execBCount.get(), "Client B should NOT receive the message");
    assertEquals(0, baseCount.get(), "Base client should NOT receive the message");

    // Clean up
    clientA.close();
    clientB.close();
    clientC.close();
  }

  @AfterEach
  void tearDown() {
    // Clean up any remaining connections
  }
}

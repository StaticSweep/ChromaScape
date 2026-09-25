package com.chromascape.web.debug.patchloop.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler for patch loop graph execution updates.
 *
 * <p>Broadcasts real-time updates during graph execution including node status changes, patch
 * generation, validation results, and completion events.
 *
 * <p>Supports execution-scoped broadcasting where clients subscribe to specific executions.
 */
@Component
public class PatchLoopWebSocketHandler extends TextWebSocketHandler {

  private static final Logger logger = LoggerFactory.getLogger(PatchLoopWebSocketHandler.class);

  private static final int MAX_REPLAY_EVENTS = 100;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final Map<String, WebSocketSession> allSessions = new ConcurrentHashMap<>();
  private final Map<String, Set<WebSocketSession>> sessionsByExecution = new ConcurrentHashMap<>();
  private final Map<String, Queue<RecentEvent>> replayBuffer = new ConcurrentHashMap<>();

  /** Record of a recent WebSocket event for replay to late-joining clients. */
  private record RecentEvent(String type, String data, LocalDateTime timestamp) {}

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    logger.info("PatchLoop WebSocket connected: {}", session.getId());
    allSessions.put(session.getId(), session);

    // Extract executionId from URI if present
    String executionId = extractExecutionId(session.getUri().toString());
    if (executionId != null) {
      sessionsByExecution
          .computeIfAbsent(executionId, k -> new CopyOnWriteArraySet<>())
          .add(session);
      logger.debug("Session {} subscribed to execution {}", session.getId(), executionId);

      // Send replay buffer to late joiners
      Queue<RecentEvent> recentEvents = replayBuffer.get(executionId);
      if (recentEvents != null && !recentEvents.isEmpty()) {
        logger.debug(
            "Sending replay buffer with {} events to session {}",
            recentEvents.size(),
            session.getId());
        for (RecentEvent event : recentEvents) {
          sendMessage(session, event.data());
        }
      }
    }

    // Send welcome message (sendMessage now catches exceptions internally)
    sendMessage(session, createMessage("CONNECTED", "Connected to patch loop updates", null));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    logger.info("PatchLoop WebSocket disconnected: {}", session.getId());
    allSessions.remove(session.getId());

    // Remove from execution-scoped subscriptions
    sessionsByExecution.forEach(
        (execId, sessions) -> {
          sessions.remove(session);
          // Clean up replay buffer if no more sessions for this execution
          if (sessions.isEmpty()) {
            replayBuffer.remove(execId);
          }
        });
    // Clean up empty sets
    sessionsByExecution.entrySet().removeIf(entry -> entry.getValue().isEmpty());
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    // Handle incoming messages (if needed for bidirectional communication)
    String payload = message.getPayload();
    logger.debug("Received message on patch loop WebSocket: {}", payload);

    // Handle subscription requests
    try {
      JsonNode json = objectMapper.readTree(payload);
      if (json.has("action") && "subscribe".equals(json.get("action").asText())) {
        String executionId = json.has("executionId") ? json.get("executionId").asText() : null;
        if (executionId == null || executionId.isBlank()) {
          logger.warn(
              "Rejecting subscription with null or blank executionId from session {}",
              session.getId());
          return;
        }
        sessionsByExecution
            .computeIfAbsent(executionId, k -> new CopyOnWriteArraySet<>())
            .add(session);
        logger.debug("Session {} subscribed to execution {}", session.getId(), executionId);
      }
    } catch (Exception e) {
      logger.debug("Failed to parse WebSocket message", e);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    logger.error("PatchLoop WebSocket error", exception);
    allSessions.remove(session.getId());
    sessionsByExecution.forEach((execId, sessions) -> sessions.remove(session));
  }

  /** Extracts execution ID from WebSocket URI. */
  private String extractExecutionId(String uri) {
    if (uri == null) {
      return null;
    }

    // Extract from path pattern: /ws/debug/patch-loop/{executionId}
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile("/ws/debug/patch-loop/([^/?]+)");
    java.util.regex.Matcher matcher = pattern.matcher(uri);
    if (matcher.find()) {
      return matcher.group(1);
    }

    // Extract from query parameter: ?executionId={executionId}
    pattern = java.util.regex.Pattern.compile("[?&]executionId=([^&]+)");
    matcher = pattern.matcher(uri);
    if (matcher.find()) {
      return matcher.group(1);
    }

    return null;
  }

  /** Broadcasts a node started event. */
  public void broadcastNodeStarted(String executionId, String nodeId, String nodeType) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("nodeId", nodeId);
    data.put("nodeType", nodeType);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "NODE_STARTED", data);
  }

  /** Broadcasts a node completed event. */
  public void broadcastNodeCompleted(
      String executionId, String nodeId, long duration, String outputSummary) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("nodeId", nodeId);
    data.put("duration", duration);
    data.put("outputSummary", outputSummary);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "NODE_COMPLETED", data);
  }

  /** Broadcasts a node failed event. */
  public void broadcastNodeFailed(String executionId, String nodeId, String error, int retryCount) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("nodeId", nodeId);
    data.put("error", error);
    data.put("retryCount", retryCount);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "NODE_FAILED", data);
  }

  /** Broadcasts a patch generated event. */
  public void broadcastPatchGenerated(
      String executionId,
      String patchId,
      String diffPreview,
      java.util.List<String> affectedFiles) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("patchId", patchId);
    data.put("diffPreview", diffPreview);
    data.put("affectedFiles", affectedFiles);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "PATCH_GENERATED", data);
  }

  /** Broadcasts a patch applied event. */
  public void broadcastPatchApplied(
      String executionId, String patchId, int filesChanged, int linesChanged, String gitBranch) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("patchId", patchId);
    data.put("filesChanged", filesChanged);
    data.put("linesChanged", linesChanged);
    data.put("gitBranch", gitBranch);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "PATCH_APPLIED", data);
  }

  /** Broadcasts a validation result event. */
  public void broadcastValidationResult(String executionId, boolean passed, String outputSummary) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("passed", passed);
    data.put("outputSummary", outputSummary);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "VALIDATION_RESULT", data);
  }

  /** Broadcasts a retest result event. */
  public void broadcastRetestResult(String executionId, boolean success, String comparisonSummary) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("success", success);
    data.put("comparisonSummary", comparisonSummary);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "RETEST_RESULT", data);
  }

  /** Broadcasts an iteration completed event. */
  public void broadcastIterationCompleted(
      String executionId, String finalStatus, String stopReason, int totalAttempts) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("finalStatus", finalStatus);
    data.put("stopReason", stopReason);
    data.put("totalAttempts", totalAttempts);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "ITERATION_COMPLETED", data);
  }

  /** Broadcasts an iteration failed event. */
  public void broadcastIterationFailed(String executionId, String error) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("error", error);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "ITERATION_FAILED", data);
  }

  /** Broadcasts a status change event. */
  public void broadcastStatusChange(String executionId, String newStatus) {
    Map<String, Object> data = new HashMap<>();
    data.put("executionId", executionId);
    data.put("status", newStatus);
    data.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

    broadcastToExecution(executionId, "STATUS_CHANGED", data);
  }

  /**
   * Broadcasts a message to all sessions subscribed to an execution. Falls back to broadcasting to
   * all sessions if no execution-specific subscribers.
   *
   * <p>Also stores events in the replay buffer for late-joining clients.
   */
  private void broadcastToExecution(String executionId, String type, Map<String, Object> data) {
    String message = createMessage(type, null, data);

    // Add to replay buffer for late joiners
    replayBuffer.computeIfAbsent(executionId, k -> new LinkedList<>());
    Queue<RecentEvent> buffer = replayBuffer.get(executionId);
    synchronized (buffer) {
      buffer.offer(new RecentEvent(type, message, LocalDateTime.now()));
      // Keep buffer at max size
      while (buffer.size() > MAX_REPLAY_EVENTS) {
        buffer.poll();
      }
    }

    // Try execution-scoped broadcasting first
    Set<WebSocketSession> executionSessions = sessionsByExecution.get(executionId);
    if (executionSessions != null && !executionSessions.isEmpty()) {
      TextMessage textMessage = new TextMessage(message);
      for (WebSocketSession session : executionSessions) {
        if (session.isOpen()) {
          try {
            session.sendMessage(textMessage);
          } catch (Exception e) {
            logger.error("Failed to send message to session {}", session.getId(), e);
            dropSession(session);
          }
        }
      }
      return;
    }

    // Fallback to all sessions
    broadcast(message);
  }

  /** Broadcasts a message to all connected sessions. */
  private void broadcast(String message) {
    TextMessage textMessage = new TextMessage(message);

    for (WebSocketSession session : allSessions.values()) {
      if (session.isOpen()) {
        try {
          session.sendMessage(textMessage);
        } catch (Exception e) {
          logger.error("Failed to send message to session {}", session.getId(), e);
          dropSession(session);
        }
      }
    }
  }

  /** Sends a message to a specific session. */
  private void sendMessage(WebSocketSession session, String message) {
    if (session.isOpen()) {
      try {
        session.sendMessage(new TextMessage(message));
      } catch (Exception e) {
        logger.error("Failed to send message to session {}", session.getId(), e);
        dropSession(session);
      }
    }
  }

  /**
   * Drops a broken session from all tracking maps.
   *
   * <p>Called when a send fails, to prevent repeated errors against a dead connection.
   */
  private void dropSession(WebSocketSession session) {
    allSessions.remove(session.getId());
    sessionsByExecution.forEach((execId, sessions) -> sessions.remove(session));
    sessionsByExecution
        .entrySet()
        .removeIf(
            entry -> {
              boolean empty = entry.getValue().isEmpty();
              if (empty) {
                replayBuffer.remove(entry.getKey());
              }
              return empty;
            });
  }

  /** Creates a JSON message for WebSocket transmission. */
  private String createMessage(String type, String message, Map<String, Object> data) {
    try {
      Map<String, Object> msg = new HashMap<>();
      msg.put("type", type);
      if (message != null) {
        msg.put("message", message);
      }
      if (data != null) {
        msg.put("data", data);
      }
      return objectMapper.writeValueAsString(msg);
    } catch (Exception e) {
      logger.error("Failed to create WebSocket message", e);
      return "{}";
    }
  }
}

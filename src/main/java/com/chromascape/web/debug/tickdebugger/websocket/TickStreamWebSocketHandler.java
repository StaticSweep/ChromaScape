package com.chromascape.web.debug.tickdebugger.websocket;

import com.chromascape.web.debug.tickdebugger.dto.SessionStartRequest;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.dto.SessionStopRequest;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket handler for receiving tick batch data from Micro6 client.
 *
 * <p>This handler processes three message types:
 *
 * <ul>
 *   <li>SESSION_START: Initialize a new debug session
 *   <li>TICK_BATCH: Per-tick data containing logs, state, and overlays
 *   <li>SESSION_STOP: Finalize the debug session
 * </ul>
 *
 * <p>Endpoint: {@code /ws/micro6/tick-stream}
 */
@Component
public class TickStreamWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(TickStreamWebSocketHandler.class);

  private final ObjectMapper objectMapper;
  private final DebugSessionService sessionService;
  private final TickStreamIngestionService ingestionService;
  private final DebuggerTickWebSocketHandler debuggerWebSocketHandler;
  private final Set<WebSocketSession> micro6Clients = ConcurrentHashMap.newKeySet();

  public TickStreamWebSocketHandler(
      ObjectMapper objectMapper,
      DebugSessionService sessionService,
      TickStreamIngestionService ingestionService,
      DebuggerTickWebSocketHandler debuggerWebSocketHandler) {
    this.objectMapper = objectMapper;
    this.sessionService = sessionService;
    this.ingestionService = ingestionService;
    this.debuggerWebSocketHandler = debuggerWebSocketHandler;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    log.info("Micro6 tick stream client connected: {}", session.getId());
    micro6Clients.add(session);
    super.afterConnectionEstablished(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    try {
      JsonNode root = objectMapper.readTree(message.getPayload());
      String type = root.has("type") ? root.get("type").asText() : "";

      switch (type) {
        case "SESSION_START":
          handleSessionStart(session, root);
          break;
        case "TICK_BATCH":
          handleTickBatch(root);
          break;
        case "SESSION_STOP":
          handleSessionStop(root);
          break;
        default:
          log.warn("Unknown message type: {}", type);
      }
    } catch (Exception e) {
      log.error("Error processing tick stream message: {}", e.getMessage(), e);
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    log.info("Micro6 tick stream client disconnected: {}, status: {}", session.getId(), status);
    micro6Clients.remove(session);
    super.afterConnectionClosed(session, status);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    log.error(
        "Tick stream transport error for session {}: {}",
        session.getId(),
        exception.getMessage(),
        exception);
    super.handleTransportError(session, exception);
  }

  /** Handle SESSION_START message from Micro6. */
  private void handleSessionStart(WebSocketSession wsSession, JsonNode root) {
    try {
      SessionStartRequest request = objectMapper.treeToValue(root, SessionStartRequest.class);
      log.info(
          "Starting debug session via WebSocket: account={}, script={}, recording={}",
          request.getAccountName(),
          request.getScriptName(),
          request.getRecordingEnabled());

      SessionStartResponse response =
          sessionService.startSession(
              request.getAccountName(), request.getScriptName(), request.getRecordingEnabled());

      // Send response back to Micro6 with the sessionId
      String responseJson = objectMapper.writeValueAsString(response);
      wsSession.sendMessage(new TextMessage(responseJson));

      log.info("Sent SESSION_START response to Micro6: sessionId={}", response.getSessionId());

      // Broadcast session creation to all debugger clients
      broadcastSessionStartToDebugger(response);

    } catch (Exception e) {
      log.error("Error handling SESSION_START: {}", e.getMessage(), e);
      sendErrorResponse(wsSession, "SESSION_START failed", e.getMessage());
    }
  }

  /** Handle TICK_BATCH message from Micro6. */
  private void handleTickBatch(JsonNode root) {
    try {
      TickBatchMessage batch = objectMapper.treeToValue(root, TickBatchMessage.class);

      if (!"TICK_BATCH".equals(batch.getType())) {
        log.warn("Received non-batch message in TICK_BATCH handler: {}", batch.getType());
        return;
      }

      log.info(
          "Received tick batch: sessionId={}, tick={}, logs={}, overlays={}",
          batch.getSessionId(),
          batch.getTick(),
          batch.getLogs() != null ? batch.getLogs().size() : 0,
          batch.getOverlays() != null ? batch.getOverlays().size() : 0);

      ingestionService.processTickBatch(batch);

    } catch (Exception e) {
      log.error("Error handling TICK_BATCH: {}", e.getMessage(), e);
    }
  }

  /** Handle SESSION_STOP message from Micro6. */
  private void handleSessionStop(JsonNode root) {
    try {
      SessionStopRequest request = objectMapper.treeToValue(root, SessionStopRequest.class);
      log.info(
          "Stopping debug session: sessionId={}, endTick={}",
          request.getSessionId(),
          request.getEndTick());

      // Do not stop recording automatically on Micro6 session stop
      sessionService.stopSession(request.getSessionId(), request.getEndTick(), false);
    } catch (Exception e) {
      log.error("Error handling SESSION_STOP: {}", e.getMessage(), e);
    }
  }

  /** Send error response to Micro6 client. */
  private void sendErrorResponse(WebSocketSession session, String error, String detail) {
    try {
      Map<String, Object> errorResponse = Map.of("type", "ERROR", "error", error, "detail", detail);
      session.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorResponse)));
    } catch (Exception e) {
      log.error("Failed to send error response: {}", e.getMessage());
    }
  }

  /**
   * Broadcast session creation to all debugger web UI clients.
   *
   * <p>This allows the UI to detect when Micro6 creates a debug session and automatically sync to
   * it, fixing the session ID mismatch issue.
   */
  private void broadcastSessionStartToDebugger(SessionStartResponse session) {
    try {
      Map<String, Object> message =
          Map.of(
              "type", "SESSION_CREATED",
              "sessionId", session.getSessionId(),
              "startTick", session.getStartTick());

      String messageJson = objectMapper.writeValueAsString(message);
      debuggerWebSocketHandler.broadcast(new TextMessage(messageJson));

      log.info(
          "Broadcast SESSION_CREATED to {} debugger client(s): sessionId={}",
          debuggerWebSocketHandler.getClientCount(),
          session.getSessionId());
    } catch (Exception e) {
      log.error("Failed to broadcast session start to debugger: {}", e.getMessage(), e);
    }
  }

  /**
   * Broadcast a feed control message to connected Micro6 clients.
   *
   * @param enabled true to resume sending tick data, false to pause
   * @return number of clients notified
   */
  public int broadcastFeedControl(boolean enabled) {
    if (micro6Clients.isEmpty()) {
      return 0;
    }
    int sent = 0;
    try {
      Map<String, Object> message = Map.of("type", "FEED_CONTROL", "enabled", enabled);
      String messageJson = objectMapper.writeValueAsString(message);
      TextMessage textMessage = new TextMessage(messageJson);

      for (WebSocketSession client : micro6Clients) {
        if (client.isOpen()) {
          synchronized (client) {
            client.sendMessage(textMessage);
          }
          sent++;
        }
      }
    } catch (Exception e) {
      log.error("Failed to broadcast FEED_CONTROL: {}", e.getMessage(), e);
    }
    return sent;
  }
}

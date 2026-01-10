package com.chromascape.web.debug.tickdebugger.websocket;

import com.chromascape.web.debug.tickdebugger.dto.SessionStartRequest;
import com.chromascape.web.debug.tickdebugger.dto.SessionStopRequest;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  public TickStreamWebSocketHandler(
      ObjectMapper objectMapper,
      DebugSessionService sessionService,
      TickStreamIngestionService ingestionService) {
    this.objectMapper = objectMapper;
    this.sessionService = sessionService;
    this.ingestionService = ingestionService;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    log.info("Micro6 tick stream client connected: {}", session.getId());
    super.afterConnectionEstablished(session);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    try {
      JsonNode root = objectMapper.readTree(message.getPayload());
      String type = root.has("type") ? root.get("type").asText() : "";

      switch (type) {
        case "SESSION_START":
          handleSessionStart(root);
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
  private void handleSessionStart(JsonNode root) {
    try {
      SessionStartRequest request = objectMapper.treeToValue(root, SessionStartRequest.class);
      log.info(
          "Starting debug session: account={}, script={}, recording={}",
          request.getAccountName(),
          request.getScriptName(),
          request.getRecordingEnabled());

      sessionService.startSession(
          request.getAccountName(), request.getScriptName(), request.getRecordingEnabled());
    } catch (Exception e) {
      log.error("Error handling SESSION_START: {}", e.getMessage(), e);
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

      log.debug(
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

      sessionService.stopSession(request.getSessionId(), request.getEndTick());
    } catch (Exception e) {
      log.error("Error handling SESSION_STOP: {}", e.getMessage(), e);
    }
  }
}

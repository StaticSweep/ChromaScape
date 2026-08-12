package com.chromascape.web.debug.tickdebugger.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingShadowDecision;
import com.chromascape.web.aiplayer.prototype.FishingShadowDecisionService;
import com.chromascape.web.aiplayer.prototype.FishingState;
import com.chromascape.web.aiplayer.prototype.SemanticAction;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Tests that shadow decisions are broadcast to debugger clients on each tick batch. */
class TickStreamWebSocketHandlerTest {

  // Matches the Spring-managed ObjectMapper, which ignores unknown properties on DTOs.
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private String tickBatchJson() {
    return "{\n"
        + "  \"type\": \"TICK_BATCH\",\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"sessionId\": \"debug_1\",\n"
        + "  \"tick\": 12345,\n"
        + "  \"timestamp\": \"2026-08-11T05:30:00Z\",\n"
        + "  \"playerState\": {\"x\": 100, \"z\": 100, \"plane\": 0},\n"
        + "  \"inventory\": [{\"slot\": 0, \"itemId\": 303, \"quantity\": 1}],\n"
        + "  \"equipment\": [],\n"
        + "  \"threats\": [],\n"
        + "  \"projectiles\": [],\n"
        + "  \"cooldowns\": {},\n"
        + "  \"npcs\": [{\"name\": \"Fishing spot\", \"x\": 101, \"y\": 100, \"plane\": 0}],\n"
        + "  \"gameObjects\": [],\n"
        + "  \"groundObjects\": [],\n"
        + "  \"groundItems\": [],\n"
        + "  \"players\": [],\n"
        + "  \"widgets\": [],\n"
        + "  \"quest\": {\"active\": false},\n"
        + "  \"logs\": [],\n"
        + "  \"overlays\": []\n"
        + "}";
  }

  @Test
  @DisplayName("WebSocket clients receive AI_PLAYER_SHADOW_DECISION for each tick batch")
  void broadcastsShadowDecision() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    DebuggerTickWebSocketHandler debuggerWebSocketHandler =
        mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowDecisionService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);

    FishingShadowDecision decision =
        new FishingShadowDecision(
            1,
            "AI_PLAYER_SHADOW_DECISION",
            "PREDICTED",
            "fishing_ab12cd34",
            "debug_1",
            12345,
            "2026-08-11T05:30:00Z",
            SemanticAction.interactNpc("net", "fishing spot"),
            new FishingState.NearbyNpc("fishing spot", 101, 100, 0, 1),
            null,
            4.0,
            8201L,
            450);
    when(shadowDecisionService.evaluate(any())).thenReturn(decision);

    TickStreamWebSocketHandler handler =
        new TickStreamWebSocketHandler(
            objectMapper,
            sessionService,
            ingestionService,
            debuggerWebSocketHandler,
            shadowDecisionService);

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService).evaluate(any());
    ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
    verify(debuggerWebSocketHandler).broadcast(captor.capture());
    String payload = captor.getValue().getPayload();
    assertThat(payload).contains("\"type\":\"AI_PLAYER_SHADOW_DECISION\"");
    assertThat(payload).contains("\"status\":\"PREDICTED\"");
    assertThat(payload).contains("\"tick\":12345");
    verify(ingestionService).processTickBatch(any());
  }

  @Test
  @DisplayName("no decision message is broadcast when evaluation returns null")
  void noBroadcastWhenNoDecision() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    DebuggerTickWebSocketHandler debuggerWebSocketHandler =
        mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowDecisionService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);

    when(shadowDecisionService.evaluate(any())).thenReturn(null);

    TickStreamWebSocketHandler handler =
        new TickStreamWebSocketHandler(
            objectMapper,
            sessionService,
            ingestionService,
            debuggerWebSocketHandler,
            shadowDecisionService);

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService).evaluate(any());
    verify(debuggerWebSocketHandler, org.mockito.Mockito.never()).broadcast(any());
    verify(ingestionService).processTickBatch(any());
  }

  @Test
  @DisplayName("explicit SESSION_STOP triggers shadow session cleanup")
  void sessionStopCleansShadowSession() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    DebuggerTickWebSocketHandler debuggerWebSocketHandler =
        mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowDecisionService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);

    TickStreamWebSocketHandler handler =
        new TickStreamWebSocketHandler(
            objectMapper,
            sessionService,
            ingestionService,
            debuggerWebSocketHandler,
            shadowDecisionService);

    String stopJson = "{\"type\":\"SESSION_STOP\",\"sessionId\":\"debug_1\",\"endTick\":20000}";
    handler.handleTextMessage(wsSession, new TextMessage(stopJson));

    verify(shadowDecisionService).cleanupSession("debug_1");
    verify(sessionService).stopSession("debug_1", 20000L, true);
  }

  @Test
  @DisplayName("explicit SESSION_STOP cleans shadow state even when finalization throws")
  void sessionStopCleansShadowWhenFinalizationThrows() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    DebuggerTickWebSocketHandler debuggerWebSocketHandler =
        mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowDecisionService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);

    doThrow(new RuntimeException("finalize boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    TickStreamWebSocketHandler handler =
        new TickStreamWebSocketHandler(
            objectMapper,
            sessionService,
            ingestionService,
            debuggerWebSocketHandler,
            shadowDecisionService);

    String stopJson = "{\"type\":\"SESSION_STOP\",\"sessionId\":\"debug_1\",\"endTick\":20000}";
    handler.handleTextMessage(wsSession, new TextMessage(stopJson));

    verify(shadowDecisionService).cleanupSession("debug_1");
    verify(sessionService).stopSession("debug_1", 20000L, true);
  }

  @Test
  @DisplayName(
      "a failed explicit SESSION_STOP retains the binding so disconnect retries finalization")
  void failedExplicitStopRetriesFinalizationOnDisconnect() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    DebuggerTickWebSocketHandler debuggerWebSocketHandler =
        mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowDecisionService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    when(wsSession.getId()).thenReturn("ws-retry-1");

    SessionStartResponse response = new SessionStartResponse("debug_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);
    doThrow(new RuntimeException("stop boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    TickStreamWebSocketHandler handler =
        new TickStreamWebSocketHandler(
            objectMapper,
            sessionService,
            ingestionService,
            debuggerWebSocketHandler,
            shadowDecisionService);

    // 1. SESSION_START binds the WS session to the debug session.
    String startJson =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"preferredSessionId\":\"debug_1\"}";
    handler.handleTextMessage(wsSession, new TextMessage(startJson));

    // 2. Explicit SESSION_STOP throws; the binding must be retained so a later disconnect can
    // retry finalization.
    String stopJson = "{\"type\":\"SESSION_STOP\",\"sessionId\":\"debug_1\",\"endTick\":20000}";
    handler.handleTextMessage(wsSession, new TextMessage(stopJson));
    verify(shadowDecisionService).cleanupSession("debug_1");

    // 3-4. Closing the socket consumes the retained binding and runs disconnect finalization.
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    verify(sessionService).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
    // 5. Shadow cleanup runs on both attempts (explicit stop + disconnect).
    verify(shadowDecisionService, org.mockito.Mockito.times(2)).cleanupSession("debug_1");

    // 6. A second close must not attempt a third finalization: the binding was consumed.
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    verify(sessionService, org.mockito.Mockito.times(1))
        .finalizeSessionOnDisconnect(any(), anyString());
    verify(sessionService).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
    verify(shadowDecisionService, org.mockito.Mockito.times(2)).cleanupSession("debug_1");
  }

  @Test
  @DisplayName("disconnect cleanup runs when implicit finalization throws")
  void disconnectCleanupRunsWhenImplicitFinalizationThrows() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    DebuggerTickWebSocketHandler debuggerWebSocketHandler =
        mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowDecisionService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    when(wsSession.getId()).thenReturn("ws-test-1");

    SessionStartResponse response = new SessionStartResponse("debug_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);
    doThrow(new RuntimeException("finalize boom"))
        .when(sessionService)
        .finalizeSessionOnDisconnect(eq("debug_1"), anyString());

    TickStreamWebSocketHandler handler =
        new TickStreamWebSocketHandler(
            objectMapper,
            sessionService,
            ingestionService,
            debuggerWebSocketHandler,
            shadowDecisionService);

    String startJson =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"preferredSessionId\":\"debug_1\"}";
    handler.handleTextMessage(wsSession, new TextMessage(startJson));
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

    verify(sessionService).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
    verify(shadowDecisionService).cleanupSession("debug_1");
  }

  @Test
  @DisplayName("tick ingestion still runs when shadow evaluation throws")
  void tickIngestionRunsWhenShadowEvaluationThrows() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    DebuggerTickWebSocketHandler debuggerWebSocketHandler =
        mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowDecisionService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);

    when(shadowDecisionService.evaluate(any())).thenThrow(new RuntimeException("shadow boom"));

    TickStreamWebSocketHandler handler =
        new TickStreamWebSocketHandler(
            objectMapper,
            sessionService,
            ingestionService,
            debuggerWebSocketHandler,
            shadowDecisionService);

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService).evaluate(any());
    verify(ingestionService).processTickBatch(any());
    verify(debuggerWebSocketHandler, org.mockito.Mockito.never()).broadcast(any());
  }
}

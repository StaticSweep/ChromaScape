package com.chromascape.web.debug.tickdebugger.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingShadowDecisionService;
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

/**
 * Tests that SESSION_START produces exactly one response to Micro6 and continues the session even
 * when recording start fails (degraded tick-only mode). A second SESSION_START on one connection is
 * rejected without creating another debug session.
 */
class TickStreamWebSocketHandlerSessionStartTest {

  // Matches the Spring-managed ObjectMapper, which ignores unknown properties on DTOs.
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private String sessionStartJson(String preferredSessionId) {
    return "{\n"
        + "  \"type\": \"SESSION_START\",\n"
        + "  \"accountName\": \"account-1\",\n"
        + "  \"scriptName\": \"configurable_woodcutting\",\n"
        + "  \"recordingEnabled\": true,\n"
        + "  \"preferredSessionId\": \""
        + preferredSessionId
        + "\"\n"
        + "}";
  }

  private TickStreamWebSocketHandler handler(
      DebugSessionService sessionService,
      DebuggerTickWebSocketHandler debuggerHandler,
      FishingShadowDecisionService shadowService) {
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    return new TickStreamWebSocketHandler(
        objectMapper, sessionService, ingestionService, debuggerHandler, shadowService);
  }

  /** Stubs a non-null WebSocket session id, mirroring real framework behavior. */
  private void stubSessionId(WebSocketSession wsSession) {
    when(wsSession.getId()).thenReturn("ws-test-1");
  }

  @Test
  @DisplayName("SESSION_START success emits exactly one success response, not an ERROR")
  void successEmitsSingleResponse() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_new_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(
            eq("account-1"),
            eq("configurable_woodcutting"),
            eq(true),
            any(),
            eq("debug_reserved_1")))
        .thenReturn(response);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved_1")));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"sessionId\":\"debug_new_1\"");
    assertThat(payload).contains("\"recordingId\":\"rec_1\"");
    assertThat(payload).doesNotContain("\"type\":\"ERROR\"");
  }

  @Test
  @DisplayName("SESSION_START forwards the legacy sessionId alias as preferredSessionId")
  void aliasSessionIdIsForwarded() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response = new SessionStartResponse("debug_new_1", true, null, 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"sessionId\":\"debug_legacy_alias\"}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    verify(sessionService)
        .startSession(eq("a"), eq("s"), eq(true), any(), eq("debug_legacy_alias"));
  }

  @Test
  @DisplayName("SESSION_START with degraded (tick-only) recording emits a single success response")
  void degradedRecordingEmitsSingleSuccessResponse() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse degraded =
        new SessionStartResponse(
            "debug_new_2",
            false,
            null,
            0L,
            "Recording failed to start: failed to capture initial frame after 60 attempts over 15000 ms");
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(degraded);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved_2")));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"recordingEnabled\":false");
    assertThat(payload).contains("\"sessionId\":\"debug_new_2\"");
    assertThat(payload).contains("Recording failed to start");
    assertThat(payload).doesNotContain("\"type\":\"ERROR\"");
  }

  @Test
  @DisplayName("success response write failure produces no second response attempt")
  void successSendThrowsProducesNoSecondResponse() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_new_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);
    doThrow(new RuntimeException("write failed"))
        .when(wsSession)
        .sendMessage(any(TextMessage.class));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved_1")));

    // Exactly one response-write attempt is made (the success response); no ERROR is appended.
    verify(wsSession, times(1)).sendMessage(any(TextMessage.class));
    verify(wsSession, times(1)).close(any(CloseStatus.class));
  }

  @Test
  @DisplayName("failed response write retains the cleanup binding for disconnect finalization")
  void failedResponseWriteRetainsBindingForDisconnectFinalization() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_new_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);
    doThrow(new RuntimeException("write failed"))
        .when(wsSession)
        .sendMessage(any(TextMessage.class));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved_1")));

    // The created session binding is retained so the eventual disconnect finalizes it.
    h.afterConnectionClosed(wsSession, CloseStatus.SERVER_ERROR);
    verify(sessionService).finalizeSessionOnDisconnect("debug_new_1", "disconnect");
  }

  @Test
  @DisplayName("error before any response echoes the request id exactly once")
  void errorBeforeResponseEchoesRequestIdOnce() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    when(sessionService.startSession(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"clientRequestId\":\"a4a7d886-4444\","
            + "\"accountName\":\"account-1\",\"scriptName\":\"s\",\"recordingEnabled\":true}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"type\":\"ERROR\"");
    assertThat(payload).contains("\"clientRequestId\":\"a4a7d886-4444\"");
  }

  @Test
  @DisplayName("SESSION_START failure emits exactly one ERROR response")
  void failureEmitsSingleErrorResponse() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    when(sessionService.startSession(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved_3")));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"type\":\"ERROR\"");
    assertThat(payload).contains("boom");
  }

  @Test
  @DisplayName("SESSION_START forwards preferredSessionId as the reserved session binding")
  void forwardsPreferredSessionId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_reserved_7", false, null, 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved_7")));

    verify(sessionService)
        .startSession(
            eq("account-1"),
            eq("configurable_woodcutting"),
            eq(true),
            any(),
            eq("debug_reserved_7"));
  }

  @Test
  @DisplayName("successful response echoes clientRequestId unchanged")
  void successEchoesClientRequestId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_new_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"clientRequestId\":\"a4a7d886-1111\","
            + "\"accountName\":\"account-1\",\"scriptName\":\"s\",\"recordingEnabled\":true}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"clientRequestId\":\"a4a7d886-1111\"");
    assertThat(payload).contains("\"sessionId\":\"debug_new_1\"");
    assertThat(payload).doesNotContain("\"type\":\"ERROR\"");
  }

  @Test
  @DisplayName("degraded response echoes clientRequestId unchanged")
  void degradedEchoesClientRequestId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse degraded =
        new SessionStartResponse(
            "debug_new_2",
            false,
            null,
            0L,
            "Recording failed to start: failed to capture initial frame");
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(degraded);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"clientRequestId\":\"a4a7d886-2222\","
            + "\"accountName\":\"account-1\",\"scriptName\":\"s\",\"recordingEnabled\":true}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"clientRequestId\":\"a4a7d886-2222\"");
    assertThat(payload).contains("\"recordingEnabled\":false");
    assertThat(payload).doesNotContain("\"type\":\"ERROR\"");
  }

  @Test
  @DisplayName("SESSION_START failed error echoes clientRequestId")
  void failureEchoesClientRequestId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    when(sessionService.startSession(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"clientRequestId\":\"a4a7d886-3333\","
            + "\"accountName\":\"account-1\",\"scriptName\":\"s\",\"recordingEnabled\":true}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"type\":\"ERROR\"");
    assertThat(payload).contains("boom");
    assertThat(payload).contains("\"clientRequestId\":\"a4a7d886-3333\"");
  }

  @Test
  @DisplayName("SESSION_START without clientRequestId remains accepted")
  void missingClientRequestIdAccepted() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_new_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"accountName\":\"account-1\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    verify(wsSession, times(1)).sendMessage(any());
    assertThat(response.getClientRequestId()).isNull();
  }

  @Test
  @DisplayName("SessionStartResponse serializes the client request id only when set")
  void serializationIncludesClientRequestIdOnlyWhenSet() throws Exception {
    SessionStartResponse withoutId = new SessionStartResponse("s1", true, "r1", 0L, null);
    assertThat(objectMapper.writeValueAsString(withoutId)).doesNotContain("clientRequestId");

    SessionStartResponse withId = new SessionStartResponse("s1", true, "r1", 0L, null);
    withId.setClientRequestId("req-7");
    assertThat(objectMapper.writeValueAsString(withId)).contains("\"clientRequestId\":\"req-7\"");
  }

  @Test
  @DisplayName("a second SESSION_START on one connection is rejected with SESSION_ALREADY_BOUND")
  void secondStartRejectedWithSessionAlreadyBound() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse first = new SessionStartResponse("debug_first", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(first);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved_1")));

    // A second SESSION_START on the same connection must not create another debug session or
    // replace the existing binding.
    String secondJson =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"preferredSessionId\":\"debug_second\","
            + "\"clientRequestId\":\"req-2\"}";
    h.handleTextMessage(wsSession, new TextMessage(secondJson));

    verify(sessionService, times(1)).startSession(any(), any(), any(), any(), any());
    ArgumentCaptor<TextMessage> replies = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(2)).sendMessage(replies.capture());
    String errorPayload = replies.getAllValues().get(1).getPayload();
    assertThat(errorPayload).contains("\"type\":\"ERROR\"");
    assertThat(errorPayload).contains("\"error\":\"SESSION_ALREADY_BOUND\"");
    assertThat(errorPayload).contains("\"clientRequestId\":\"req-2\"");
  }
}

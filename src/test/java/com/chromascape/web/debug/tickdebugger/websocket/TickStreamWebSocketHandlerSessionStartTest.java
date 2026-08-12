package com.chromascape.web.debug.tickdebugger.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests that SESSION_START produces exactly one response to Micro6 and continues the session even
 * when recording start fails (degraded tick-only mode).
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
  @DisplayName("SESSION_START success echoes the client request id unchanged")
  void successEchoesClientRequestId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_corr_1", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"clientRequestId\":\"req-42\"}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"sessionId\":\"debug_corr_1\"");
    assertThat(payload).contains("\"clientRequestId\":\"req-42\"");
    assertThat(payload).doesNotContain("\"type\":\"ERROR\"");
  }

  @Test
  @DisplayName("SESSION_START failure echoes the client request id in the ERROR response")
  void errorEchoesClientRequestId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    when(sessionService.startSession(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"clientRequestId\":\"req-99\"}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"type\":\"ERROR\"");
    assertThat(payload).contains("\"clientRequestId\":\"req-99\"");
  }

  @Test
  @DisplayName("a SESSION_START without a client request id remains compatible")
  void missingClientRequestIdRemainsCompatible() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    SessionStartResponse response =
        new SessionStartResponse("debug_corr_2", true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_corr_2")));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"sessionId\":\"debug_corr_2\"");
    assertThat(payload).doesNotContain("clientRequestId");
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
}

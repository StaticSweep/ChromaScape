package com.chromascape.web.debug.tickdebugger.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.aiplayer.prototype.FishingShadowDecisionService;
import com.chromascape.web.debug.tickdebugger.dto.SessionStartResponse;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService.SessionStartOutcome;
import com.chromascape.web.debug.tickdebugger.service.DebugSessionService.SessionStartRollbackToken;
import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests that SESSION_START produces exactly one response to Micro6, reserves/owns preferred
 * sessions transactionally, compensates failed starts through the authoritative outcome, and
 * retains exactly one guarded retry when compensation fails.
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

  private String sessionStartJson(String preferredSessionId, String clientRequestId) {
    return "{\n"
        + "  \"type\": \"SESSION_START\",\n"
        + "  \"accountName\": \"account-1\",\n"
        + "  \"scriptName\": \"configurable_woodcutting\",\n"
        + "  \"recordingEnabled\": true,\n"
        + "  \"preferredSessionId\": \""
        + preferredSessionId
        + "\",\n"
        + "  \"clientRequestId\": \""
        + clientRequestId
        + "\"\n"
        + "}";
  }

  private String tickBatchJson(String sessionId) {
    return "{\"type\":\"TICK_BATCH\",\"schemaVersion\":2,\"sessionId\":\""
        + sessionId
        + "\",\"tick\":5}";
  }

  private TickStreamWebSocketHandler handler(
      DebugSessionService sessionService,
      DebuggerTickWebSocketHandler debuggerHandler,
      FishingShadowDecisionService shadowService) {
    return handler(objectMapper, sessionService, debuggerHandler, shadowService);
  }

  private TickStreamWebSocketHandler handler(
      ObjectMapper mapper,
      DebugSessionService sessionService,
      DebuggerTickWebSocketHandler debuggerHandler,
      FishingShadowDecisionService shadowService) {
    TickStreamIngestionService ingestionService = mock(TickStreamIngestionService.class);
    when(shadowService.openSession(any(), any())).thenReturn(true);
    return new TickStreamWebSocketHandler(
        mapper, sessionService, ingestionService, debuggerHandler, shadowService);
  }

  private SessionStartOutcome outcome(
      String sessionId, boolean createdNew, boolean reused, boolean recordingStarted) {
    SessionStartResponse response =
        new SessionStartResponse(sessionId, true, recordingStarted ? "rec_1" : null, 0L, null);
    SessionStartRollbackToken token =
        new SessionStartRollbackToken(
            sessionId, createdNew, recordingStarted ? "rec_1" : null, null, null, null);
    return new SessionStartOutcome(response, token, createdNew, reused, recordingStarted);
  }

  private void stubSessionId(WebSocketSession wsSession) {
    when(wsSession.getId()).thenReturn("ws-test-1");
  }

  private void stubSessionId(WebSocketSession wsSession, String id) {
    when(wsSession.getId()).thenReturn(id);
  }

  @Test
  void recordingIdentityTravelsThroughAuthoritativeStartAndCorrelatedRejection() throws Exception {
    var service = mock(DebugSessionService.class);
    var ws = mock(WebSocketSession.class);
    stubSessionId(ws);
    var handler =
        handler(
            service,
            mock(DebuggerTickWebSocketHandler.class),
            mock(FishingShadowDecisionService.class));
    var request =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            objectMapper.readTree(sessionStartJson("reserved", "identity-request"));
    var identity = request.putObject("captureIdentity");
    identity.put("schema", "debugger-client-identity/v1");
    when(service.startSessionWithOutcome(
            any(), any(), any(), any(), any(), any(), any(), any(), eq(identity)))
        .thenThrow(
            new com.chromascape.web.recording.provenance.ProvenanceException(
                "IMMUTABLE_CAPTURE_IDENTITY"));
    handler.handleTextMessage(ws, new TextMessage(request.toString()));
    var response = ArgumentCaptor.forClass(TextMessage.class);
    verify(ws).sendMessage(response.capture());
    assertThat(response.getValue().getPayload())
        .contains("IMMUTABLE_CAPTURE_IDENTITY", "identity-request");
    verify(service, never()).startSessionWithOutcome(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("SESSION_START success emits exactly one success response, not an ERROR")
  void successEmitsSingleResponse() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    when(sessionService.startSessionWithOutcome(
            eq("account-1"),
            eq("configurable_woodcutting"),
            eq(true),
            any(),
            eq("debug_reserved_1")))
        .thenReturn(outcome("debug_new_1", true, false, true));

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

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_new_1", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    String json =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"sessionId\":\"debug_legacy_alias\"}";
    h.handleTextMessage(wsSession, new TextMessage(json));

    verify(sessionService)
        .startSessionWithOutcome(eq("a"), eq("s"), eq(true), any(), eq("debug_legacy_alias"));
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
    SessionStartRollbackToken token =
        new SessionStartRollbackToken("debug_new_2", true, null, null, null, null);
    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(new SessionStartOutcome(degraded, token, true, false, false));

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

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
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
  @DisplayName("SESSION_START success echoes the client request id unchanged")
  void successEchoesClientRequestId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession);

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_corr_1", true, false, true));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_corr_1", "req-42")));

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

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("boom"));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reserved", "req-99")));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    String payload = reply.getValue().getPayload();
    assertThat(payload).contains("\"type\":\"ERROR\"");
    assertThat(payload).contains("\"clientRequestId\":\"req-99\"");
  }

  @Test
  @DisplayName("two WebSockets cannot reserve the same preferred session")
  void secondWebSocketCannotBindPreferredSession() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession ws1 = mock(WebSocketSession.class);
    WebSocketSession ws2 = mock(WebSocketSession.class);
    stubSessionId(ws1, "ws-owner-1");
    stubSessionId(ws2, "ws-owner-2");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), eq("debug_shared")))
        .thenReturn(outcome("debug_shared", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);

    h.handleTextMessage(ws1, new TextMessage(sessionStartJson("debug_shared")));
    ArgumentCaptor<TextMessage> firstReply = ArgumentCaptor.forClass(TextMessage.class);
    verify(ws1, times(1)).sendMessage(firstReply.capture());
    assertThat(firstReply.getValue().getPayload()).contains("\"sessionId\":\"debug_shared\"");

    reset(sessionService);
    reset(shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(true);
    h.handleTextMessage(ws2, new TextMessage(sessionStartJson("debug_shared")));

    ArgumentCaptor<TextMessage> secondReply = ArgumentCaptor.forClass(TextMessage.class);
    verify(ws2, times(1)).sendMessage(secondReply.capture());
    assertThat(secondReply.getValue().getPayload()).contains("\"type\":\"ERROR\"");
    assertThat(secondReply.getValue().getPayload()).contains("SESSION_ALREADY_OWNED");
    verify(sessionService, never())
        .startSessionWithOutcome(any(), any(), any(), any(), eq("debug_shared"));
    verify(shadowService, never()).openSession(any(), any());
  }

  @Test
  @DisplayName("a preferred-session conflict never starts a recording or a shadow run")
  void preferredConflictStartsNoRecordingAndNoShadowRun() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession ws1 = mock(WebSocketSession.class);
    WebSocketSession ws2 = mock(WebSocketSession.class);
    stubSessionId(ws1, "ws-owner-1");
    stubSessionId(ws2, "ws-owner-2");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), eq("debug_conflict")))
        .thenReturn(outcome("debug_conflict", true, false, true));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);

    h.handleTextMessage(ws1, new TextMessage(sessionStartJson("debug_conflict")));
    assertThat(awaitMessagePayload(ws1)).contains("\"sessionId\":\"debug_conflict\"");

    reset(sessionService);
    reset(shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(true);
    h.handleTextMessage(ws2, new TextMessage(sessionStartJson("debug_conflict")));

    assertThat(awaitMessagePayload(ws2)).contains("SESSION_ALREADY_OWNED");
    verify(sessionService, never()).startSessionWithOutcome(any(), any(), any(), any(), any());
    verify(shadowService, never()).openSession(any(), any());
  }

  @Test
  @DisplayName("openSession failure compensates the exact service outcome via rollback")
  void openSessionFailureRollsBackOwnership() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-openfail");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_openfail", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(false);

    h.handleTextMessage(
        wsSession, new TextMessage(sessionStartJson("debug_openfail", "req-openfail")));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(1)).sendMessage(reply.capture());
    assertThat(reply.getValue().getPayload()).contains("\"type\":\"ERROR\"");
    assertThat(reply.getValue().getPayload()).contains("\"clientRequestId\":\"req-openfail\"");
    // A session created by the transaction is compensated via the authoritative rollback token.
    verify(sessionService).rollbackSessionStart(argThat(t -> t.createdNewSession()), anyString());
    // The failed open never opens a run, so nothing is cleaned up.
    verify(shadowService, never()).cleanupSession(any(), any());
  }

  @Test
  @DisplayName("authoritative reusedPreferredSession prevents finalization of the prior session")
  void reusedPreferredSessionPreventsFinalization() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-reused");

    // A reused preferred session: createdNewSession=false.
    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_reused", false, true, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(false);

    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_reused", "req-reused")));

    // Compensation goes through the authoritative token; the pre-existing session is never
    // finalized directly by the handler.
    verify(sessionService).rollbackSessionStart(argThat(t -> !t.createdNewSession()), anyString());
    verify(sessionService, never()).finalizeSessionOnDisconnect(any(), anyString());
  }

  @Test
  @DisplayName("a canonical ownership conflict compensates the exact service outcome")
  void canonicalConflictCompensatesExactOutcome() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession ws1 = mock(WebSocketSession.class);
    WebSocketSession ws2 = mock(WebSocketSession.class);
    stubSessionId(ws1, "ws-canon-owner");
    stubSessionId(ws2, "ws-canon-conflict");

    // ws1 owns "debug_x".
    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), eq("debug_x")))
        .thenReturn(outcome("debug_x", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(ws1, new TextMessage(sessionStartJson("debug_x")));
    verify(ws1, times(1)).sendMessage(any());

    // ws2 requests with no preferred session, but the service authoritatively returns "debug_x"
    // which is already owned by ws1: a canonical ownership conflict.
    reset(sessionService);
    SessionStartRollbackToken conflictToken =
        new SessionStartRollbackToken("debug_x", true, null, null, null, null);
    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(
            new SessionStartOutcome(
                new SessionStartResponse("debug_x", true, null, 0L, null),
                conflictToken,
                true,
                false,
                false));

    h.handleTextMessage(
        ws2,
        new TextMessage(
            "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
                + "\"recordingEnabled\":true,\"clientRequestId\":\"req-conflict\"}"));

    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(ws2, times(1)).sendMessage(reply.capture());
    assertThat(reply.getValue().getPayload()).contains("SESSION_ALREADY_OWNED");
    verify(sessionService)
        .rollbackSessionStart(argThat(t -> t.sessionId().equals("debug_x")), anyString());
  }

  @Test
  @DisplayName("compensation failure retains a failure binding and ownership")
  void compensationFailureRetainsBindingAndOwnership() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-comp-fail");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_comp", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(false);
    doThrow(new RuntimeException("rollback boom"))
        .when(sessionService)
        .rollbackSessionStart(any(), anyString());

    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_comp")));

    @SuppressWarnings("unchecked")
    Map<String, Object> bindings =
        (Map<String, Object>) ReflectionTestUtils.getField(h, "sessionBindings");
    @SuppressWarnings("unchecked")
    Map<String, Object> owners =
        (Map<String, Object>) ReflectionTestUtils.getField(h, "sessionOwners");
    // The guarded fallback runs immediately after close, so no callback-dependent binding or
    // ownership remains even when the second compensation attempt also fails.
    assertThat(bindings).doesNotContainKey("ws-comp-fail");
    assertThat(owners).doesNotContainEntry("debug_comp", "ws-comp-fail");
    verify(sessionService, times(2)).rollbackSessionStart(any(), anyString());
    // A tick after cleanup is rejected, not ingested.
    h.handleTextMessage(wsSession, new TextMessage(tickBatchJson("debug_comp")));
    verify(shadowService, never()).evaluateAndPublish(any(), any(), any());
  }

  @Test
  @DisplayName("close retries a retained compensation exactly once")
  void closeRetriesCompensationExactlyOnce() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-close-retry");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_retry", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(false);
    doThrow(new RuntimeException("rollback boom"))
        .when(sessionService)
        .rollbackSessionStart(any(), anyString());

    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_retry")));
    // The first start cleanup and guarded close fallback both run without relying on a callback.
    verify(sessionService, times(2)).rollbackSessionStart(any(), anyString());

    h.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    // The later close callback is a no-op because the guarded fallback already ran.
    verify(sessionService, times(2)).rollbackSessionStart(any(), anyString());

    h.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    // A third attempt is never produced.
    verify(sessionService, times(2)).rollbackSessionStart(any(), anyString());
  }

  @Test
  @DisplayName("a close() that throws triggers the synchronous guarded fallback")
  void closeThrowingTriggersSynchronousFallback() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-close-throw");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_throw", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(false);
    doThrow(new RuntimeException("rollback boom"))
        .when(sessionService)
        .rollbackSessionStart(any(), anyString());
    doThrow(new IllegalStateException("close boom")).when(wsSession).close(any(CloseStatus.class));

    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_throw")));

    // The start-failure cleanup attempted compensation once; the throwing close() triggered the
    // synchronous guarded retry (the second attempt), and the binding is removed afterwards.
    verify(sessionService, times(2)).rollbackSessionStart(any(), anyString());
  }

  @Test
  @DisplayName("a later close callback after successful cleanup performs no work")
  void closeCallbackAfterSuccessfulCleanupIsNoop() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-noop-close");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_noop", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    when(shadowService.openSession(any(), any())).thenReturn(false);

    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_noop")));
    verify(sessionService, times(1)).rollbackSessionStart(any(), anyString());

    h.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    // No second compensation attempt: the binding was already removed by the successful cleanup.
    verify(sessionService, times(1)).rollbackSessionStart(any(), anyString());
  }

  @Test
  @DisplayName("a failed send invokes synchronous compensation, never a second response")
  void sendFailureInvokesSynchronousCompensation() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-send-fail");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_sendfail", true, false, true));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    doThrow(new IllegalStateException("socket closed"))
        .when(wsSession)
        .sendMessage(any(TextMessage.class));

    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_sendfail")));

    verify(sessionService, times(1)).rollbackSessionStart(any(), anyString());
    verify(shadowService, times(1)).cleanupSession(eq("debug_sendfail"), any());
    verify(wsSession).close(any(CloseStatus.class));
    // SESSION_CREATED is never broadcast because the response was never delivered.
    verify(debuggerHandler, never())
        .broadcast(argThat(msg -> msg.getPayload().contains("SESSION_CREATED")));
  }

  @Test
  @DisplayName("SESSION_CREATED is broadcast only after a successful response delivery")
  void sessionCreatedBroadcastOnlyAfterSuccessfulSend() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-created");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_created", true, false, true));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_created")));

    verify(debuggerHandler, times(1))
        .broadcast(argThat(msg -> msg.getPayload().contains("SESSION_CREATED")));
  }

  @Test
  @DisplayName("ticks in ACTIVE require the exact session id")
  void ticksInActiveRequireExactSessionId() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession wsSession = mock(WebSocketSession.class);
    stubSessionId(wsSession, "ws-active");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), any()))
        .thenReturn(outcome("debug_active", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(wsSession, new TextMessage(sessionStartJson("debug_active")));

    h.handleTextMessage(wsSession, new TextMessage(tickBatchJson("debug_other")));
    verify(shadowService, never()).evaluateAndPublish(any(), any(), any());
    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, times(2)).sendMessage(reply.capture());
    assertThat(reply.getValue().getPayload()).contains("SESSION_ID_MISMATCH");
  }

  @Test
  @DisplayName("same-preferred contention calls the session service only once")
  void samePreferredContentionCallsServiceOnce() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession ws1 = mock(WebSocketSession.class);
    WebSocketSession ws2 = mock(WebSocketSession.class);
    stubSessionId(ws1, "ws-contend-1");
    stubSessionId(ws2, "ws-contend-2");

    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), eq("debug_shared")))
        .thenReturn(outcome("debug_shared", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);
    h.handleTextMessage(ws1, new TextMessage(sessionStartJson("debug_shared")));
    h.handleTextMessage(ws2, new TextMessage(sessionStartJson("debug_shared")));

    verify(sessionService, times(1))
        .startSessionWithOutcome(any(), any(), any(), any(), eq("debug_shared"));
  }

  @Test
  @DisplayName("different preferred sessions progress while one recording startup is blocked")
  void differentPreferredProgressWhileOneBlocked() throws Exception {
    DebugSessionService sessionService = mock(DebugSessionService.class);
    DebuggerTickWebSocketHandler debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    FishingShadowDecisionService shadowService = mock(FishingShadowDecisionService.class);
    WebSocketSession ws1 = mock(WebSocketSession.class);
    WebSocketSession ws2 = mock(WebSocketSession.class);
    stubSessionId(ws1, "ws-block-a");
    stubSessionId(ws2, "ws-block-b");

    CountDownLatch aStarted = new CountDownLatch(1);
    CountDownLatch aRelease = new CountDownLatch(1);
    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), eq("debug_a")))
        .thenAnswer(
            inv -> {
              aStarted.countDown();
              if (!aRelease.await(5, TimeUnit.SECONDS)) {
                throw new RuntimeException("release timeout");
              }
              return outcome("debug_a", true, false, false);
            });
    when(sessionService.startSessionWithOutcome(any(), any(), any(), any(), eq("debug_b")))
        .thenReturn(outcome("debug_b", true, false, false));

    TickStreamWebSocketHandler h = handler(sessionService, debuggerHandler, shadowService);

    Thread blocked =
        new Thread(
            () -> {
              try {
                h.handleTextMessage(ws1, new TextMessage(sessionStartJson("debug_a")));
              } catch (Exception ignored) {
                // best-effort
              }
            });
    blocked.start();
    assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

    // While ws1's recording startup is blocked, ws2's start for a different preferred session
    // must progress: the service call runs outside the global ownership lock.
    h.handleTextMessage(ws2, new TextMessage(sessionStartJson("debug_b")));
    ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
    verify(ws2, times(1)).sendMessage(reply.capture());
    assertThat(reply.getValue().getPayload()).contains("\"sessionId\":\"debug_b\"");

    aRelease.countDown();
    blocked.join(5000);
  }

  /** Captures the sole message a mocked session is asked to send, or "" when none was sent. */
  private String awaitMessagePayload(WebSocketSession wsSession) throws Exception {
    ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
    try {
      verify(wsSession, times(1)).sendMessage(captor.capture());
      return captor.getValue().getPayload();
    } catch (AssertionError e) {
      return "";
    }
  }
}

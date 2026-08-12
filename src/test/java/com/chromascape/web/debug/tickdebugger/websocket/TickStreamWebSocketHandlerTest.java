package com.chromascape.web.debug.tickdebugger.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Tests the session-ownership contract of the tick stream handler: a Micro6 WebSocket may affect
 * only the debug session bound to that connection, and cleanup/publication ordering is
 * deterministic.
 */
class TickStreamWebSocketHandlerTest {

  // Matches the Spring-managed ObjectMapper, which ignores unknown properties on DTOs.
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private String tickBatchJson(String sessionId) {
    return "{\n"
        + "  \"type\": \"TICK_BATCH\",\n"
        + "  \"schemaVersion\": 2,\n"
        + "  \"sessionId\": \""
        + sessionId
        + "\",\n"
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

  private String tickBatchJson() {
    return tickBatchJson("debug_1");
  }

  private String startJson() {
    return "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
        + "\"recordingEnabled\":true,\"preferredSessionId\":\"debug_1\"}";
  }

  private String stopJson(String sessionId) {
    return "{\"type\":\"SESSION_STOP\",\"sessionId\":\"" + sessionId + "\",\"endTick\":20000}";
  }

  private DebugSessionService sessionService;
  private TickStreamIngestionService ingestionService;
  private DebuggerTickWebSocketHandler debuggerHandler;
  private FishingShadowDecisionService shadowDecisionService;
  private WebSocketSession wsSession;
  private TickStreamWebSocketHandler handler;

  private void setup(String wsId) {
    sessionService = mock(DebugSessionService.class);
    ingestionService = mock(TickStreamIngestionService.class);
    debuggerHandler = mock(DebuggerTickWebSocketHandler.class);
    shadowDecisionService = mock(FishingShadowDecisionService.class);
    wsSession = mock(WebSocketSession.class);
    when(wsSession.getId()).thenReturn(wsId);
    handler =
        new TickStreamWebSocketHandler(
            objectMapper, sessionService, ingestionService, debuggerHandler, shadowDecisionService);
  }

  private void stubStartSession(String sessionId) {
    SessionStartResponse response = new SessionStartResponse(sessionId, true, "rec_1", 0L, null);
    when(sessionService.startSession(any(), any(), any(), any(), any())).thenReturn(response);
  }

  private void bind(String sessionId) throws Exception {
    stubStartSession(sessionId);
    handler.handleTextMessage(wsSession, new TextMessage(startJson()));
  }

  private FishingShadowDecision decision() {
    return new FishingShadowDecision(
        2,
        "AI_PLAYER_SHADOW_DECISION",
        "PREDICTED",
        "fishing_ab12cd34",
        "debug_1",
        "shadow_run",
        12345,
        "2026-08-11T05:30:00Z",
        SemanticAction.interactNpc("net", "fishing spot"),
        new FishingState.NearbyNpc("fishing spot", 101, 100, 0, 1),
        null,
        4.0,
        8201L,
        450);
  }

  private void publishOnEvaluate(FishingShadowDecision decision) {
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<FishingShadowDecision> publisher = inv.getArgument(2);
              publisher.accept(decision);
              return null;
            })
        .when(shadowDecisionService)
        .evaluateAndPublish(any(), any(), any());
  }

  private void throwOnEvaluate(RuntimeException ex) {
    doThrow(ex).when(shadowDecisionService).evaluateAndPublish(any(), any(), any());
  }

  /** Verifies no shadow decision message was ever broadcast (SESSION_CREATED is fine). */
  private void verifyNoShadowDecisionBroadcast() {
    verify(debuggerHandler, never())
        .broadcast(argThat(msg -> msg.getPayload().contains("AI_PLAYER_SHADOW_DECISION")));
  }

  @Test
  @DisplayName("a tick without SESSION_START is rejected without invoking anything")
  void tickWithoutStartIsRejected() throws Exception {
    setup("ws-unbound");
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
    verify(debuggerHandler, never()).broadcast(any());
  }

  @Test
  @DisplayName("a rejected unbound tick does not invoke ingestion")
  void rejectedUnboundTickSkipsIngestion() throws Exception {
    setup("ws-unbound");
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(ingestionService, never()).processTickBatch(any());
  }

  @Test
  @DisplayName("a mismatched tick does not invoke inference or ingestion")
  void mismatchedTickRejected() throws Exception {
    setup("ws-mismatch");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson("debug_other")));

    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
    verifyNoShadowDecisionBroadcast();
  }

  @Test
  @DisplayName("a matching bound tick invokes evaluation/publication and ingestion")
  void matchingBoundTickInvokesEvaluationAndIngestion() throws Exception {
    setup("ws-match");
    bind("debug_1");
    publishOnEvaluate(decision());

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService).evaluateAndPublish(any(), any(), any());
    ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
    verify(debuggerHandler, times(2)).broadcast(captor.capture());
    TextMessage shadowMessage =
        captor.getAllValues().stream()
            .filter(msg -> msg.getPayload().contains("AI_PLAYER_SHADOW_DECISION"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no shadow decision broadcast"));
    assertThat(shadowMessage.getPayload()).contains("\"status\":\"PREDICTED\"");
    verify(ingestionService).processTickBatch(any());
  }

  @Test
  @DisplayName("a stop without a binding is rejected")
  void stopWithoutBindingIsRejected() throws Exception {
    setup("ws-nobind");
    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));

    verify(sessionService, never()).stopSession(any(), any(), anyBoolean());
    verify(shadowDecisionService, never()).cleanupSession(any(), any());
  }

  @Test
  @DisplayName("a stop for a different session is rejected")
  void stopForDifferentSessionRejected() throws Exception {
    setup("ws-stopdiff");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_other")));

    verify(sessionService, never()).stopSession(any(), any(), anyBoolean());
    verify(shadowDecisionService, never()).cleanupSession(any(), any());
  }

  @Test
  @DisplayName("a valid stop changes the binding to EXPLICIT_STOP_FAILED before finalization")
  void validStopChangesBindingToExplicitStopFailedBeforeFinalization() throws Exception {
    setup("ws-stop-phase");
    bind("debug_1");

    AtomicReference<String> phaseDuringFinalization = new AtomicReference<>();
    doAnswer(
            inv -> {
              Map<?, ?> bindings =
                  (Map<?, ?>) ReflectionTestUtils.getField(handler, "sessionBindings");
              Object binding = bindings.get("ws-stop-phase");
              phaseDuringFinalization.set(
                  binding.getClass().getDeclaredMethod("phase").invoke(binding).toString());
              return null;
            })
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));

    assertThat(phaseDuringFinalization.get()).isEqualTo("EXPLICIT_STOP_FAILED");
    verify(sessionService).stopSession("debug_1", 20000L, true);
    verify(shadowDecisionService).cleanupSession(eq("debug_1"), any());
  }

  @Test
  @DisplayName("a tick arriving during STOPPING is rejected")
  void tickDuringStoppingIsRejected() throws Exception {
    setup("ws-stopping");
    bind("debug_1");
    doThrow(new RuntimeException("stop boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
  }

  @Test
  @DisplayName("a repeated stop does not run finalization twice")
  void repeatedStopDoesNotFinalizeTwice() throws Exception {
    setup("ws-repeatstop");
    bind("debug_1");
    doThrow(new RuntimeException("stop boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));

    verify(sessionService, times(1)).stopSession(eq("debug_1"), eq(20000L), eq(true));
    verify(shadowDecisionService, times(1)).cleanupSession(eq("debug_1"), any());
  }

  @Test
  @DisplayName("a second SESSION_START on one connection is rejected without replacing the binding")
  void secondStartRejectedWithoutReplacingBinding() throws Exception {
    setup("ws-second-start");
    bind("debug_1");

    handler.handleTextMessage(
        wsSession,
        new TextMessage(
            "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
                + "\"recordingEnabled\":true,\"preferredSessionId\":\"debug_2\","
                + "\"clientRequestId\":\"req-2\"}"));

    verify(sessionService, times(1)).startSession(any(), any(), any(), any(), any());
    // The original binding is retained: a tick for debug_1 is still accepted.
    publishOnEvaluate(decision());
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson("debug_1")));
    verify(shadowDecisionService).evaluateAndPublish(any(), any(), any());
    verify(ingestionService).processTickBatch(any());
  }

  @Test
  @DisplayName("two WebSockets cannot stop one another's sessions")
  void twoWebSocketsCannotStopEachOthersSessions() throws Exception {
    setup("ws-one");
    bind("debug_1");

    WebSocketSession other = mock(WebSocketSession.class);
    when(other.getId()).thenReturn("ws-two");
    TickStreamWebSocketHandler otherHandler =
        new TickStreamWebSocketHandler(
            objectMapper, sessionService, ingestionService, debuggerHandler, shadowDecisionService);

    otherHandler.handleTextMessage(other, new TextMessage(stopJson("debug_1")));

    verify(sessionService, never()).stopSession(any(), any(), anyBoolean());
    // The owning connection still owns its session.
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson("debug_1")));
    verify(shadowDecisionService).evaluateAndPublish(any(), any(), any());
  }

  @Test
  @DisplayName("a successful explicit stop prevents disconnect finalization")
  void successfulStopPreventsDisconnectFinalization() throws Exception {
    setup("ws-ok-stop");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

    verify(sessionService, never()).finalizeSessionOnDisconnect(any(), anyString());
    verify(shadowDecisionService, times(1)).cleanupSession(eq("debug_1"), any());
  }

  @Test
  @DisplayName("a failed explicit stop receives exactly one disconnect retry")
  void failedExplicitStopGetsOneDisconnectRetry() throws Exception {
    setup("ws-retry");
    bind("debug_1");
    doThrow(new RuntimeException("stop boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

    verify(sessionService, times(1)).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
    verify(shadowDecisionService, times(2)).cleanupSession(eq("debug_1"), any());
  }

  @Test
  @DisplayName("a successful transport-error finalization prevents close retry")
  void successfulTransportErrorFinalizationPreventsCloseRetry() throws Exception {
    setup("ws-transport-ok");
    bind("debug_1");

    handler.handleTransportError(wsSession, new RuntimeException("boom"));
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

    verify(sessionService, times(1)).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
  }

  @Test
  @DisplayName("a failed transport-error finalization receives exactly one close retry")
  void failedTransportErrorFinalizationGetsOneCloseRetry() throws Exception {
    setup("ws-transport-fail");
    bind("debug_1");
    doThrow(new RuntimeException("finalize boom"))
        .when(sessionService)
        .finalizeSessionOnDisconnect(eq("debug_1"), anyString());

    handler.handleTransportError(wsSession, new RuntimeException("boom"));
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

    verify(sessionService, times(2)).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
  }

  @Test
  @DisplayName("a failed close finalization is not attempted a third time")
  void failedCloseFinalizationNotAttemptedAgain() throws Exception {
    setup("ws-close-fail");
    bind("debug_1");
    doThrow(new RuntimeException("finalize boom"))
        .when(sessionService)
        .finalizeSessionOnDisconnect(eq("debug_1"), anyString());

    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

    verify(sessionService, times(1)).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
  }

  @Test
  @DisplayName("a late tick after stop cannot recreate shadow state")
  void lateTickAfterStopCannotRecreateShadowState() throws Exception {
    setup("ws-late-tick");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
    verify(shadowDecisionService, times(1)).cleanupSession(eq("debug_1"), any());
  }

  @Test
  @DisplayName("ordinary ingestion still runs when an accepted shadow evaluation throws")
  void ingestionRunsWhenShadowEvaluationThrows() throws Exception {
    setup("ws-eval-throw");
    bind("debug_1");
    throwOnEvaluate(new RuntimeException("shadow boom"));

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService).evaluateAndPublish(any(), any(), any());
    verify(ingestionService).processTickBatch(any());
    verifyNoShadowDecisionBroadcast();
  }

  @Test
  @DisplayName("ordinary ingestion still runs when an accepted shadow publication throws")
  void ingestionRunsWhenShadowPublicationThrows() throws Exception {
    setup("ws-pub-throw");
    bind("debug_1");
    doAnswer(
            inv -> {
              @SuppressWarnings("unchecked")
              Consumer<FishingShadowDecision> publisher = inv.getArgument(2);
              publisher.accept(decision());
              return null;
            })
        .when(shadowDecisionService)
        .evaluateAndPublish(any(), any(), any());
    doThrow(new RuntimeException("broadcast boom")).when(debuggerHandler).broadcast(any());

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    // One SESSION_CREATED broadcast from the bind, plus one failed shadow-decision broadcast.
    verify(debuggerHandler, times(2)).broadcast(any());
    verify(ingestionService).processTickBatch(any());
  }

  @Test
  @DisplayName("no decision message is broadcast when no decision is produced")
  void noBroadcastWhenNoDecision() throws Exception {
    setup("ws-null");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService).evaluateAndPublish(any(), any(), any());
    verifyNoShadowDecisionBroadcast();
    verify(ingestionService).processTickBatch(any());
  }

  /** Returns the payload of the last message sent to the mocked WebSocket session. */
  private String lastSentPayload() throws Exception {
    ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
    verify(wsSession, atLeastOnce()).sendMessage(captor.capture());
    java.util.List<TextMessage> all = captor.getAllValues();
    return all.get(all.size() - 1).getPayload();
  }

  private void assertErrorPayload(String payload, String expectedError) {
    assertThat(payload).contains("\"type\":\"ERROR\"");
    assertThat(payload).contains("\"error\":\"" + expectedError + "\"");
  }

  @Test
  @DisplayName("an unbound tick sends SESSION_NOT_BOUND and invokes nothing")
  void unboundTickSendsSessionNotBound() throws Exception {
    setup("ws-unbound-err");
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    assertErrorPayload(lastSentPayload(), "SESSION_NOT_BOUND");
    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
  }

  @Test
  @DisplayName("a tick on a stopping connection sends SESSION_STOPPING")
  void stoppingTickSendsSessionStopping() throws Exception {
    setup("ws-stopping-err");
    bind("debug_1");
    doThrow(new RuntimeException("stop boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    assertErrorPayload(lastSentPayload(), "SESSION_STOPPING");
    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
  }

  @Test
  @DisplayName("a mismatched tick sends SESSION_ID_MISMATCH")
  void mismatchedTickSendsSessionIdMismatch() throws Exception {
    setup("ws-mismatch-err");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson("debug_other")));

    assertErrorPayload(lastSentPayload(), "SESSION_ID_MISMATCH");
    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
  }

  @Test
  @DisplayName("an unbound stop sends SESSION_NOT_BOUND")
  void unboundStopSendsSessionNotBound() throws Exception {
    setup("ws-unbound-stop-err");
    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));

    assertErrorPayload(lastSentPayload(), "SESSION_NOT_BOUND");
    verify(sessionService, never()).stopSession(any(), any(), anyBoolean());
    verify(shadowDecisionService, never()).cleanupSession(any(), any());
  }

  @Test
  @DisplayName("a mismatched stop sends SESSION_ID_MISMATCH")
  void mismatchedStopSendsSessionIdMismatch() throws Exception {
    setup("ws-mismatch-stop-err");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_other")));

    assertErrorPayload(lastSentPayload(), "SESSION_ID_MISMATCH");
    verify(sessionService, never()).stopSession(any(), any(), anyBoolean());
  }

  @Test
  @DisplayName("a repeated stop sends SESSION_STOPPING without finalizing again")
  void repeatedStopSendsSessionStopping() throws Exception {
    setup("ws-repeat-stop-err");
    bind("debug_1");
    doThrow(new RuntimeException("stop boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));

    assertErrorPayload(lastSentPayload(), "SESSION_STOPPING");
    verify(sessionService, times(1)).stopSession(eq("debug_1"), eq(20000L), eq(true));
  }

  @Test
  @DisplayName("two WebSockets on one handler cannot bind the same preferred session")
  void twoSocketsCannotBindSamePreferredSession() throws Exception {
    setup("ws-one");
    bind("debug_1");

    WebSocketSession other = mock(WebSocketSession.class);
    when(other.getId()).thenReturn("ws-two");
    String startJson2 =
        "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
            + "\"recordingEnabled\":true,\"preferredSessionId\":\"debug_1\","
            + "\"clientRequestId\":\"req-2\"}";
    handler.handleTextMessage(other, new TextMessage(startJson2));

    // No second session is created, and the correlated rejection is sent to the second socket.
    verify(sessionService, times(1)).startSession(any(), any(), any(), any(), any());
    ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
    verify(other).sendMessage(captor.capture());
    String payload = captor.getValue().getPayload();
    assertErrorPayload(payload, "SESSION_ALREADY_OWNED");
    assertThat(payload).contains("\"clientRequestId\":\"req-2\"");
  }

  @Test
  @DisplayName("a successful explicit stop releases both ownership maps")
  void successfulStopReleasesBothMaps() throws Exception {
    setup("ws-release");
    bind("debug_1");

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));

    @SuppressWarnings("unchecked")
    Map<String, Object> bindings =
        (Map<String, Object>) ReflectionTestUtils.getField(handler, "sessionBindings");
    @SuppressWarnings("unchecked")
    Map<String, Object> owners =
        (Map<String, Object>) ReflectionTestUtils.getField(handler, "sessionOwners");
    assertThat(bindings).doesNotContainKey("ws-release");
    assertThat(owners).doesNotContainKey("debug_1");
    // The released session can be rebound by another socket without an ownership conflict.
    WebSocketSession other = mock(WebSocketSession.class);
    when(other.getId()).thenReturn("ws-new");
    stubStartSession("debug_1");
    handler.handleTextMessage(
        other,
        new TextMessage(
            "{\"type\":\"SESSION_START\",\"accountName\":\"a\",\"scriptName\":\"s\","
                + "\"recordingEnabled\":true,\"preferredSessionId\":\"debug_1\","
                + "\"clientRequestId\":\"req-3\"}"));
    @SuppressWarnings("unchecked")
    Map<String, Object> ownersAfter =
        (Map<String, Object>) ReflectionTestUtils.getField(handler, "sessionOwners");
    assertThat(ownersAfter).containsEntry("debug_1", "ws-new");
  }

  @Test
  @DisplayName(
      "an explicit failure followed by transport and close performs only the close fallback")
  void explicitFailureTransportAndCloseOnlyCloseFallback() throws Exception {
    setup("ws-explicit-then-transport");
    bind("debug_1");
    doThrow(new RuntimeException("stop boom"))
        .when(sessionService)
        .stopSession(eq("debug_1"), eq(20000L), eq(true));

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.handleTransportError(wsSession, new RuntimeException("transport boom"));
    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);

    verify(sessionService, times(1)).stopSession(eq("debug_1"), eq(20000L), eq(true));
    // The transport callback skips finalization; the close fallback runs exactly once.
    verify(sessionService, times(1)).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
  }

  @Test
  @DisplayName("repeated transport errors do not repeat finalization")
  void repeatedTransportErrorsDoNotRepeatFinalization() throws Exception {
    setup("ws-repeated-transport");
    bind("debug_1");
    doThrow(new RuntimeException("finalize boom"))
        .when(sessionService)
        .finalizeSessionOnDisconnect(eq("debug_1"), anyString());

    handler.handleTransportError(wsSession, new RuntimeException("boom"));
    handler.handleTransportError(wsSession, new RuntimeException("boom"));
    handler.handleTransportError(wsSession, new RuntimeException("boom"));

    verify(sessionService, times(1)).finalizeSessionOnDisconnect(eq("debug_1"), anyString());
  }

  @Test
  @DisplayName("a failed response send retains ownership for disconnect cleanup")
  void failedResponseSendRetainsOwnership() throws Exception {
    setup("ws-send-fail");
    stubStartSession("debug_1");
    doThrow(new RuntimeException("send boom")).when(wsSession).sendMessage(any());

    handler.handleTextMessage(wsSession, new TextMessage(startJson()));

    @SuppressWarnings("unchecked")
    Map<String, Object> bindings =
        (Map<String, Object>) ReflectionTestUtils.getField(handler, "sessionBindings");
    assertThat(bindings).containsKey("ws-send-fail");

    handler.afterConnectionClosed(wsSession, CloseStatus.NORMAL);
    verify(sessionService).finalizeSessionOnDisconnect("debug_1", "disconnect");
    @SuppressWarnings("unchecked")
    Map<String, Object> owners =
        (Map<String, Object>) ReflectionTestUtils.getField(handler, "sessionOwners");
    assertThat(owners).doesNotContainKey("debug_1");
  }

  @Test
  @DisplayName("a late tick after an explicit stop cannot recreate a closed run")
  void lateTickCannotRecreateClosedRun() throws Exception {
    setup("ws-late-run");
    bind("debug_1");
    verify(shadowDecisionService).openSession(eq("debug_1"), any());

    handler.handleTextMessage(wsSession, new TextMessage(stopJson("debug_1")));
    handler.handleTextMessage(wsSession, new TextMessage(tickBatchJson()));

    verify(shadowDecisionService, never()).evaluateAndPublish(any(), any(), any());
    verify(ingestionService, never()).processTickBatch(any());
    verify(shadowDecisionService, times(1)).cleanupSession(eq("debug_1"), any());
  }
}

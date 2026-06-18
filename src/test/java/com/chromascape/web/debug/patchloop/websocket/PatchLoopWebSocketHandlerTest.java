package com.chromascape.web.debug.patchloop.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Unit tests for {@link PatchLoopWebSocketHandler}.
 *
 * <p>Verifies that broadcasts are non-fatal and broken sessions are cleaned up properly.
 */
@ExtendWith(MockitoExtension.class)
class PatchLoopWebSocketHandlerTest {

  private PatchLoopWebSocketHandler handler;

  @Mock private WebSocketSession session1;

  @Mock private WebSocketSession session2;

  @BeforeEach
  void setUp() {
    handler = new PatchLoopWebSocketHandler();
  }

  private void setupSession(WebSocketSession session, String id, String uri) throws Exception {
    when(session.getId()).thenReturn(id);
    when(session.getUri()).thenReturn(new URI(uri));
    when(session.isOpen()).thenReturn(true);
  }

  @Test
  @DisplayName("afterConnectionEstablished sends welcome message")
  void testConnectionEstablished() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop");

    handler.afterConnectionEstablished(session1);

    verify(session1).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("afterConnectionClosed removes session cleanly")
  void testConnectionClosed() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop");

    handler.afterConnectionEstablished(session1);
    handler.afterConnectionClosed(session1, CloseStatus.NORMAL);

    // Should not throw - session was removed
    handler.broadcastNodeStarted("exec-1", "node-1", "RUN_SCRIPT");
  }

  @Test
  @DisplayName("Broadcast to execution subscribers does not throw when a session is broken")
  void testBroadcastWithBrokenSession() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop/exec-123");
    handler.afterConnectionEstablished(session1);

    // Make session1 throw on send
    doThrow(new IOException("Connection reset")).when(session1).sendMessage(any(TextMessage.class));

    // Should not throw
    assertDoesNotThrow(() -> handler.broadcastNodeStarted("exec-123", "node-1", "RUN_SCRIPT"));
  }

  @Test
  @DisplayName("Broken session is removed from tracking after send failure")
  void testBrokenSessionRemovedAfterFailure() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop/exec-1");
    setupSession(session2, "session-2", "ws://localhost/ws/debug/patch-loop/exec-1");
    handler.afterConnectionEstablished(session1);
    handler.afterConnectionEstablished(session2);

    // session1 is broken, session2 is fine
    doThrow(new RuntimeException("Broken pipe")).when(session1).sendMessage(any(TextMessage.class));

    // First broadcast: session1 fails and gets dropped
    handler.broadcastNodeStarted("exec-1", "node-1", "RUN_SCRIPT");

    // Verify session2 received the message
    verify(session2, atLeastOnce()).sendMessage(any(TextMessage.class));

    // Reset session1 mock and verify it was dropped
    reset(session1);
    lenient().when(session1.getId()).thenReturn("session-1");
    handler.broadcastNodeCompleted("exec-1", "node-1", 100L, "Done");

    // session1 should NOT be called again (it was dropped)
    verify(session1, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("Broadcast fallback to all sessions when no execution subscribers")
  void testBroadcastFallbackToAllSessions() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop");
    setupSession(session2, "session-2", "ws://localhost/ws/debug/patch-loop");

    handler.afterConnectionEstablished(session1);
    handler.afterConnectionEstablished(session2);

    // Broadcast to an execution with no subscribers - should fallback to all
    handler.broadcastStatusChange("exec-no-subscribers", "RUNNING");

    // Both sessions should receive via fallback (at least welcome + broadcast)
    verify(session1, atLeast(2)).sendMessage(any(TextMessage.class));
    verify(session2, atLeast(2)).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("handleTransportError removes session cleanly")
  void testHandleTransportError() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop");

    handler.afterConnectionEstablished(session1);
    handler.handleTransportError(session1, new RuntimeException("Transport error"));

    // Should not throw on subsequent broadcast
    assertDoesNotThrow(() -> handler.broadcastNodeStarted("exec-1", "node-1", "RUN_SCRIPT"));
  }

  @Test
  @DisplayName("Broadcast handles concurrent session failures without throwing")
  void testMultipleSessionFailures() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop/exec-1");
    setupSession(session2, "session-2", "ws://localhost/ws/debug/patch-loop/exec-1");
    handler.afterConnectionEstablished(session1);
    handler.afterConnectionEstablished(session2);

    // Both sessions are broken
    doThrow(new IOException("Reset")).when(session1).sendMessage(any(TextMessage.class));
    doThrow(new RuntimeException("Broken")).when(session2).sendMessage(any(TextMessage.class));

    // Should not throw even when ALL sessions fail
    assertDoesNotThrow(() -> handler.broadcastNodeStarted("exec-1", "node-1", "RUN_SCRIPT"));
  }

  @Test
  @DisplayName("Replay buffer works for late-joining clients")
  void testReplayBufferForLateJoiners() throws Exception {
    setupSession(session1, "session-1", "ws://localhost/ws/debug/patch-loop/exec-replay");
    handler.afterConnectionEstablished(session1);

    // Broadcast some events
    handler.broadcastNodeStarted("exec-replay", "node-1", "RUN_SCRIPT");
    handler.broadcastNodeCompleted("exec-replay", "node-1", 50L, "Done");

    // A new session joins late
    WebSocketSession lateSession = mock(WebSocketSession.class);
    setupSession(lateSession, "late-session", "ws://localhost/ws/debug/patch-loop/exec-replay");

    handler.afterConnectionEstablished(lateSession);

    // Late session should receive replayed events (2) plus the welcome message (1)
    verify(lateSession, atLeast(3)).sendMessage(any(TextMessage.class));
  }
}

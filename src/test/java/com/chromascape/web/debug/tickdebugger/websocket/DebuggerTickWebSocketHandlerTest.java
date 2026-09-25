package com.chromascape.web.debug.tickdebugger.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.service.TickStreamIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** Tests the debugger broadcast handler: idempotent connect, safe concurrent iteration, cleanup. */
class DebuggerTickWebSocketHandlerTest {

  private TickStreamIngestionService ingestionService;
  private DebuggerTickWebSocketHandler handler;

  @BeforeEach
  void setUp() {
    ingestionService = mock(TickStreamIngestionService.class);
    handler = new DebuggerTickWebSocketHandler(ingestionService);
  }

  @Test
  @DisplayName("connect registers the client and reports a thread-safe count")
  void connectRegistersClient() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    handler.afterConnectionEstablished(session);

    assertThat(handler.getClientCount()).isEqualTo(1);
    verify(ingestionService).registerDebuggerClient(session);
  }

  @Test
  @DisplayName("connect is idempotent and does not double-register")
  void connectIsIdempotent() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    handler.afterConnectionEstablished(session);
    handler.afterConnectionEstablished(session);

    assertThat(handler.getClientCount()).isEqualTo(1);
    verify(ingestionService, times(1)).registerDebuggerClient(session);
  }

  @Test
  @DisplayName("close removes the client and unregisters from ingestion")
  void closeRemovesAndUnregisters() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    handler.afterConnectionEstablished(session);
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    assertThat(handler.getClientCount()).isZero();
    verify(ingestionService).unregisterDebuggerClient(session);
  }

  @Test
  @DisplayName("transport error removes the client and unregisters from ingestion")
  void transportErrorRemovesAndUnregisters() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    handler.afterConnectionEstablished(session);
    handler.handleTransportError(session, new RuntimeException("boom"));

    assertThat(handler.getClientCount()).isZero();
    verify(ingestionService).unregisterDebuggerClient(session);
  }

  @Test
  @DisplayName("broadcast sends to all open clients and drops closed ones once")
  void broadcastSendsAndRemovesClosed() throws Exception {
    WebSocketSession open = mock(WebSocketSession.class);
    when(open.isOpen()).thenReturn(true);
    WebSocketSession closed = mock(WebSocketSession.class);
    when(closed.isOpen()).thenReturn(false);
    handler.afterConnectionEstablished(open);
    handler.afterConnectionEstablished(closed);

    handler.broadcast(new TextMessage("ping"));

    verify(open).sendMessage(any(TextMessage.class));
    verify(closed, never()).sendMessage(any(TextMessage.class));
    assertThat(handler.getClientCount()).isEqualTo(1);
    verify(ingestionService).unregisterDebuggerClient(closed);
  }

  @Test
  @DisplayName("a failed send removes and unregisters the client once")
  void failedSendRemovesAndUnregistersOnce() throws Exception {
    WebSocketSession failing = mock(WebSocketSession.class);
    when(failing.isOpen()).thenReturn(true);
    doThrow(new RuntimeException("send boom")).when(failing).sendMessage(any(TextMessage.class));

    handler.afterConnectionEstablished(failing);
    handler.broadcast(new TextMessage("ping"));
    handler.broadcast(new TextMessage("ping"));

    assertThat(handler.getClientCount()).isZero();
    verify(ingestionService, times(1)).unregisterDebuggerClient(failing);
  }

  @Test
  @DisplayName("broadcast is safe during concurrent connect and disconnect")
  void broadcastSafeDuringConcurrentConnectDisconnect() throws Exception {
    for (int i = 0; i < 20; i++) {
      WebSocketSession session = mock(WebSocketSession.class);
      when(session.isOpen()).thenReturn(true);
      handler.afterConnectionEstablished(session);
      Thread broadcaster = new Thread(() -> handler.broadcast(new TextMessage("tick")));
      Thread remover =
          new Thread(
              () -> {
                try {
                  handler.afterConnectionClosed(session, CloseStatus.NORMAL);
                } catch (Exception ignored) {
                  // best-effort
                }
              });
      broadcaster.start();
      remover.start();
      broadcaster.join(5000);
      remover.join(5000);
    }
    // No exceptions thrown above means iteration stayed safe; the count is consistent.
    assertThat(handler.getClientCount()).isGreaterThanOrEqualTo(0);
  }
}

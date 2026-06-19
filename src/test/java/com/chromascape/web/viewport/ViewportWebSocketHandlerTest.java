package com.chromascape.web.viewport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.viewport.service.ScreenCaptureService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@ExtendWith(MockitoExtension.class)
class ViewportWebSocketHandlerTest {

  @Mock private ScreenCaptureService screenCaptureService;
  @Mock private WebSocketSession session;

  @Test
  void sessionLifecycleRegistersAndUnregistersViewportStreaming() throws Exception {
    ViewportWebSocketHandler handler = new ViewportWebSocketHandler(screenCaptureService);

    handler.afterConnectionEstablished(session);

    assertEquals(1, handler.getActiveSessionCount());
    verify(screenCaptureService).onViewportSessionOpened();

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    assertEquals(0, handler.getActiveSessionCount());
    verify(screenCaptureService).onViewportSessionClosed();
  }

  @Test
  void broadcastSendsBinaryMessagesToActiveSessions() throws Exception {
    when(session.isOpen()).thenReturn(true);

    ViewportWebSocketHandler handler = new ViewportWebSocketHandler(screenCaptureService);
    handler.afterConnectionEstablished(session);
    handler.broadcast(new byte[] {1, 2, 3, 4});

    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(() -> verify(session).sendMessage(any(BinaryMessage.class)));
  }
}

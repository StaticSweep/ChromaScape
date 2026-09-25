package com.chromascape.web.viewport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.viewport.config.ViewportStreamConfig;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebsocketViewportTest {

  @Mock private ViewportWebSocketHandler handler;

  @Test
  void publishFrameSkipsEncodeWhenNoSessionsAreActive() {
    when(handler.getActiveSessionCount()).thenReturn(0);

    WebsocketViewport viewport =
        new WebsocketViewport(handler, streamConfig(), new JpegFrameEncoder());
    viewport.publishFrame(new BufferedImage(20, 10, BufferedImage.TYPE_3BYTE_BGR), 20, 10);

    assertEquals(1, viewport.getFramesSkippedNoSessions());
    verify(handler, never()).broadcast(any());
  }

  @Test
  void publishFrameBroadcastsBinaryPayloadWithHeader() {
    when(handler.getActiveSessionCount()).thenReturn(1);

    WebsocketViewport viewport =
        new WebsocketViewport(handler, streamConfig(), new JpegFrameEncoder());
    viewport.publishFrame(new BufferedImage(40, 20, BufferedImage.TYPE_3BYTE_BGR), 320, 240);

    ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
    await()
        .atMost(2, TimeUnit.SECONDS)
        .untilAsserted(() -> verify(handler).broadcast(payloadCaptor.capture()));

    byte[] payload = payloadCaptor.getValue();
    assertTrue(payload.length > 16);

    ByteBuffer buffer = ByteBuffer.wrap(payload);
    assertEquals(1L, buffer.getLong());
    assertEquals(320, buffer.getInt());
    assertEquals(240, buffer.getInt());
    assertTrue(buffer.remaining() > 0);
  }

  private static ViewportStreamConfig streamConfig() {
    ViewportStreamConfig config = new ViewportStreamConfig();
    config.setTargetFps(15);
    config.setJpegQuality(70);
    config.setMaxLongEdge(960);
    return config;
  }
}

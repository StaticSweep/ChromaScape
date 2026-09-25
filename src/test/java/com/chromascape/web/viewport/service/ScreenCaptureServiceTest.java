package com.chromascape.web.viewport.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.viewport.Viewport;
import com.chromascape.utils.core.screen.viewport.ViewportManager;
import com.chromascape.web.viewport.WebsocketViewport;
import com.chromascape.web.viewport.config.ScreenCaptureConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScreenCaptureServiceTest {

  private Viewport originalViewport;

  @BeforeEach
  void setUp() {
    originalViewport = ViewportManager.getInstance();
  }

  @AfterEach
  void tearDown() {
    ViewportManager.setInstance(originalViewport);
  }

  @Test
  void viewportSessionLifecycleRegistersUnifiedCaptureOnlyForFirstAndLastViewer() {
    UnifiedFrameCaptureService unifiedCaptureService = mock(UnifiedFrameCaptureService.class);
    ScreenCaptureService service =
        new ScreenCaptureService(config(), unifiedConfig(), streamConfig(), unifiedCaptureService);

    service.onViewportSessionOpened();
    service.onViewportSessionOpened();
    service.onViewportSessionClosed();
    service.onViewportSessionClosed();

    verify(unifiedCaptureService).registerSubscriber(service);
    verify(unifiedCaptureService).unregisterSubscriber(service);
    assertEquals(0, service.getActiveViewportSessions());
    assertEquals(1, service.getStreamActivations());
    assertEquals(1, service.getStreamDeactivations());
  }

  @Test
  void onFrameCapturedThrottlesViewportForwarding() {
    UnifiedFrameCaptureService unifiedCaptureService = mock(UnifiedFrameCaptureService.class);
    ScreenCaptureService service =
        new ScreenCaptureService(config(), unifiedConfig(), streamConfig(), unifiedCaptureService);
    WebsocketViewport viewport = mock(WebsocketViewport.class);
    ViewportManager.setInstance(viewport);

    service.onViewportSessionOpened();

    BufferedImage image = new BufferedImage(64, 32, BufferedImage.TYPE_3BYTE_BGR);
    service.onFrameCaptured(new FrameEvent(image, 1_000_000_000L, 640, 480));
    service.onFrameCaptured(new FrameEvent(image, 1_020_000_000L, 640, 480));

    verify(viewport).publishFrame(any(BufferedImage.class), eq(640), eq(480));
    verifyNoMoreInteractions(viewport);
    assertEquals(1, service.getFramesForwarded());
    assertEquals(1, service.getFramesSkippedThrottle());
  }

  private static ScreenCaptureConfig config() {
    ScreenCaptureConfig config = new ScreenCaptureConfig();
    config.setEnabled(true);
    return config;
  }

  private static UnifiedCaptureConfig unifiedConfig() {
    UnifiedCaptureConfig config = new UnifiedCaptureConfig();
    config.setEnabled(true);
    config.setFps(30);
    return config;
  }

  private static ViewportStreamConfig streamConfig() {
    ViewportStreamConfig config = new ViewportStreamConfig();
    config.setTargetFps(15);
    config.setJpegQuality(70);
    config.setMaxLongEdge(960);
    return config;
  }
}

package com.chromascape.web.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.window.WindowSelectionService;
import com.chromascape.web.recording.capture.ScreenManagerWindowFrameCapture;
import com.chromascape.web.viewport.config.ScreenCaptureConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.config.ViewportStreamConfig;
import com.chromascape.web.viewport.service.ScreenCaptureService;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Boots the real capture slice (unified capture, screen capture config, observe service) and proves
 * a started watch delivers desktop frames to subscribers without any input session.
 *
 * <p>Runs only where an X display and ffmpeg exist; elsewhere the single test aborts instead of
 * failing.
 */
class ObservePipelineIntegrationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(
              ScreenCaptureConfig.class,
              UnifiedCaptureConfig.class,
              ViewportStreamConfig.class,
              ScreenManagerWindowFrameCapture.class,
              UnifiedFrameCaptureService.class,
              ScreenCaptureService.class,
              ObserveService.class)
          .withBean(WindowSelectionService.class, () -> mock(WindowSelectionService.class));

  @Test
  @DisabledIfEnvironmentVariable(named = "SKIP_OBSERVE_PIPELINE", matches = ".+")
  void watchDeliversDesktopFramesWithoutAnInputSession() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getenv("DISPLAY") != null && !System.getenv("DISPLAY").isBlank(),
        "needs an X display");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        new File("/usr/bin/ffmpeg").canExecute(), "needs ffmpeg");

    runner.run(
        context -> {
          UnifiedFrameCaptureService capture = context.getBean(UnifiedFrameCaptureService.class);
          ObserveService observe = context.getBean(ObserveService.class);

          CountDownLatch frameArrived = new CountDownLatch(1);
          AtomicReference<FrameEvent> latest = new AtomicReference<>(null);
          FrameListener listener =
              new FrameListener() {
                @Override
                public void onFrameCaptured(FrameEvent event) {
                  latest.set(event);
                  frameArrived.countDown();
                }

                @Override
                public boolean isActive() {
                  return true;
                }
              };
          capture.registerSubscriber(listener);
          Map<String, Object> started = observe.start(null, 320, 2);
          try {
            assertEquals("ACTIVE", started.get("state"));
            assertTrue(
                frameArrived.await(60, TimeUnit.SECONDS), "expected a desktop frame within 60s");
            assertNotNull(latest.get());
            assertEquals(320, latest.get().getFrame().getWidth());
          } finally {
            capture.unregisterSubscriber(listener);
            observe.stop();
          }
        });
  }
}

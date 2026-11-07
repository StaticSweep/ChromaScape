package com.chromascape.web.viewport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.web.recording.capture.WindowFrameCapture;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UnifiedFrameCaptureShutdownTest {
  @Test
  void lastSubscriberCanFinalizeAfterUnregisteringItself() throws Exception {
    var config = new UnifiedCaptureConfig();
    config.setEnabled(true);
    config.setFps(1);
    var capture = mock(WindowFrameCapture.class);
    var image = new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR);
    when(capture.captureEvent())
        .thenReturn(new FrameEvent(image, System.currentTimeMillis(), 16, 16));
    var service = new UnifiedFrameCaptureService(config, capture);
    var completed = new CompletableFuture<Boolean>();
    FrameListener listener =
        new FrameListener() {
          public boolean isActive() {
            return true;
          }

          public void onFrameCaptured(FrameEvent event) {
            service.unregisterSubscriber(this);
            try {
              // Finalizing the recorder performs interruptible joins and filesystem work.
              Thread.sleep(25);
              completed.complete(Thread.currentThread().isInterrupted());
            } catch (InterruptedException e) {
              completed.completeExceptionally(e);
            }
          }
        };
    try {
      service.registerSubscriber(listener);
      assertThat(completed.get(5, TimeUnit.SECONDS)).isFalse();
      assertThat(service.getLatestFrame()).isNull();
    } finally {
      service.unregisterSubscriber(listener);
    }
  }
}

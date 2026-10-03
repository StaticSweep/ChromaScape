package com.chromascape.web.recording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.web.recording.capture.WindowFrameCapture;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for canvas-size-change handling in {@link RecordingService}.
 *
 * <p>Verifies that frames captured at a size different from the recorder dimensions are scaled into
 * the recorder canvas instead of being dropped, that scaling counters and distinct size-transition
 * counters behave, and that the scaling helper preserves the target size and letterboxes.
 */
class RecordingServiceDimensionScalingTest {

  private RecordingService newService() {
    RecordingConfig config = new RecordingConfig();
    config.setEnabled(true);
    // Recording start time is a field normally set by startRecording(); disable the max-duration
    // auto-stop so direct onFrameCaptured calls do not finalize the session mid-test.
    config.setMaxDurationMinutes(0);
    try {
      config.setOutputDirectory(Files.createTempDirectory("rec-scaling").toString());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return new RecordingService(
        config,
        new UnifiedCaptureConfig(),
        mock(UnifiedFrameCaptureService.class),
        mock(WindowFrameCapture.class));
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = RecordingService.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static BufferedImage solidFrame(int width, int height) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  @Test
  void fitToRecorderSizeFillsTargetAndLetterboxes() {
    BufferedImage source = solidFrame(100, 50);

    BufferedImage fitted = RecordingService.fitToRecorderSize(source, 40, 40);

    assertThat(fitted.getWidth()).isEqualTo(40);
    assertThat(fitted.getHeight()).isEqualTo(40);
    // 100x50 fitted into 40x40 leaves black bands top and bottom.
    assertThat(fitted.getRGB(20, 1) & 0x00FFFFFF).isZero();
    assertThat(fitted.getRGB(20, 39) & 0x00FFFFFF).isZero();
    assertThat(fitted.getRGB(20, 20) & 0x00FFFFFF).isNotZero();
  }

  @Test
  void mismatchedFrameIsScaledInsteadOfDropped() throws Exception {
    RecordingService service = newService();
    setField(service, "recording", new AtomicBoolean(true));
    setField(service, "initialWidth", 40);
    setField(service, "initialHeight", 40);

    service.onFrameCaptured(new FrameEvent(solidFrame(100, 50), System.nanoTime(), 100, 50));

    assertThat(service.getScaledFrameCount()).isEqualTo(1);
    assertThat(service.getDimensionChangeCount()).isEqualTo(1);
    assertThat(service.getMetrics().get("frameBufferDepth")).isEqualTo(1);
  }

  @Test
  void matchingFrameIsQueuedWithoutScaling() throws Exception {
    RecordingService service = newService();
    setField(service, "recording", new AtomicBoolean(true));
    setField(service, "initialWidth", 40);
    setField(service, "initialHeight", 40);

    service.onFrameCaptured(new FrameEvent(solidFrame(40, 40), System.nanoTime(), 40, 40));

    assertThat(service.getScaledFrameCount()).isZero();
    assertThat(service.getDimensionChangeCount()).isZero();
    assertThat(service.getMetrics().get("frameBufferDepth")).isEqualTo(1);
  }

  @Test
  void repeatedMismatchCountsEachDistinctSizeTransitionOnce() throws Exception {
    RecordingService service = newService();
    setField(service, "recording", new AtomicBoolean(true));
    setField(service, "initialWidth", 40);
    setField(service, "initialHeight", 40);

    service.onFrameCaptured(new FrameEvent(solidFrame(100, 50), System.nanoTime(), 100, 50));
    service.onFrameCaptured(new FrameEvent(solidFrame(100, 50), System.nanoTime(), 100, 50));
    service.onFrameCaptured(new FrameEvent(solidFrame(64, 48), System.nanoTime(), 64, 48));

    assertThat(service.getScaledFrameCount()).isEqualTo(3);
    assertThat(service.getDimensionChangeCount()).isEqualTo(2);
    assertThat(service.getMetrics().get("frameBufferDepth")).isEqualTo(3);
  }
}

package com.chromascape.web.recording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.recording.capture.WindowFrameCapture;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.exception.FrameCaptureException;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the bounded, stability-gated initial-frame wait in {@link RecordingService}.
 *
 * <p>Verifies that {@code waitForStableInitialFrame} retries until a valid frame arrives, prefers a
 * canvas size that stays unchanged for the configured stability window, falls back to the most
 * recent frame at the deadline, honors the configured timeout, aborts on interruption with the
 * interrupt flag restored, and that a timed-out {@code startRecording} resets the {@code
 * recordingStarting} guard so a later attempt can proceed.
 */
class RecordingServiceInitialFrameReadinessTest {

  private RecordingService newService(RecordingConfig config, WindowFrameCapture capture) {
    UnifiedCaptureConfig unifiedConfig = new UnifiedCaptureConfig();
    UnifiedFrameCaptureService captureService = mock(UnifiedFrameCaptureService.class);
    return new RecordingService(config, unifiedConfig, captureService, capture);
  }

  private RecordingConfig config() {
    RecordingConfig c = new RecordingConfig();
    c.setEnabled(true);
    // Legacy-style readiness for tests that only care about retry semantics: accept the first
    // valid frame regardless of size. Stability-specific tests override these.
    c.setStabilityWindowMs(0);
    c.setMinCaptureWidth(1);
    c.setMinCaptureHeight(1);
    try {
      Path tmp = Files.createTempDirectory("rec-readiness");
      c.setOutputDirectory(tmp.toString());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return c;
  }

  private BufferedImage waitForStableInitialFrame(RecordingService service) throws Exception {
    Method m = RecordingService.class.getDeclaredMethod("waitForStableInitialFrame");
    m.setAccessible(true);
    try {
      return (BufferedImage) m.invoke(service);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw (RuntimeException) e.getCause();
    }
  }

  private BufferedImage frame(int width, int height) {
    return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
  }

  @Test
  void validFrameOnFirstAttemptIsReturnedImmediately() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.capture()).thenReturn(frame(8, 6));
    RecordingService service = newService(config(), capture);

    BufferedImage result = waitForStableInitialFrame(service);

    assertThat(result.getWidth()).isEqualTo(8);
    assertThat(result.getHeight()).isEqualTo(6);
    verify(capture, times(1)).capture();
  }

  @Test
  void retriesUntilAValidFrameArrives() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.capture()).thenReturn(null, null, frame(16, 16));
    RecordingService service = newService(config(), capture);

    BufferedImage result = waitForStableInitialFrame(service);

    assertThat(result.getWidth()).isEqualTo(16);
    verify(capture, times(3)).capture();
  }

  @Test
  void recoversAfterCaptureThrowsThenSucceeds() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.capture())
        .thenThrow(new RuntimeException("Game window not found - is RuneLite running?"))
        .thenReturn(frame(4, 4));
    RecordingService service = newService(config(), capture);

    BufferedImage result = waitForStableInitialFrame(service);

    assertThat(result.getWidth()).isEqualTo(4);
    verify(capture, times(2)).capture();
  }

  @Test
  void rejectsZeroDimensionFramesAndKeepsRetrying() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    BufferedImage zeroDim = mock(BufferedImage.class);
    when(zeroDim.getWidth()).thenReturn(0);
    when(zeroDim.getHeight()).thenReturn(0);
    when(capture.capture()).thenReturn(zeroDim, frame(10, 10));
    RecordingService service = newService(config(), capture);

    BufferedImage result = waitForStableInitialFrame(service);

    assertThat(result.getWidth()).isEqualTo(10);
    verify(capture, times(2)).capture();
  }

  @Test
  void prefersStableSizeOverTransientStartupSize() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    // RuneLite reports a transient startup canvas (200x274) before settling on 764x502.
    when(capture.capture()).thenReturn(frame(200, 274), frame(764, 502));
    RecordingConfig cfg = config();
    cfg.setStabilityWindowMs(100);
    cfg.setInitialFrameRetryIntervalMs(25);
    cfg.setInitialFrameTimeoutMs(5_000);
    RecordingService service = newService(cfg, capture);

    BufferedImage result = waitForStableInitialFrame(service);

    assertThat(result.getWidth()).isEqualTo(764);
    assertThat(result.getHeight()).isEqualTo(502);
    verify(capture, atLeast(4)).capture();
  }

  @Test
  void deadlineFallsBackToLastObservedFrameWhenNeverLargeEnough() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.capture()).thenReturn(frame(200, 274));
    RecordingConfig cfg = config();
    cfg.setStabilityWindowMs(100);
    cfg.setInitialFrameRetryIntervalMs(25);
    cfg.setInitialFrameTimeoutMs(300);
    cfg.setMinCaptureWidth(640);
    cfg.setMinCaptureHeight(400);
    RecordingService service = newService(cfg, capture);

    BufferedImage result = waitForStableInitialFrame(service);

    assertThat(result.getWidth()).isEqualTo(200);
    assertThat(result.getHeight()).isEqualTo(274);
  }

  @Test
  void timesOutWithFrameCaptureExceptionWhenNoValidFrameArrives() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.capture()).thenReturn((BufferedImage) null);
    RecordingConfig cfg = config();
    cfg.setInitialFrameTimeoutMs(300);
    RecordingService service = newService(cfg, capture);

    assertThatThrownBy(() -> waitForStableInitialFrame(service))
        .isInstanceOf(FrameCaptureException.class)
        .hasMessageContaining("Failed to capture initial frame after")
        .hasMessageContaining("capture returned no frame");
  }

  @Test
  void interruptedWaitAbortsAndRestoresInterruptFlag() throws Exception {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.capture()).thenReturn((BufferedImage) null);
    RecordingConfig cfg = config();
    cfg.setInitialFrameTimeoutMs(5_000);
    RecordingService service = newService(cfg, capture);

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> waitForStableInitialFrame(service))
          .isInstanceOf(FrameCaptureException.class)
          .hasMessageContaining("interrupted after");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.currentThread().interrupted();
    }
  }

  @Test
  void timedOutStartRecordingResetsStartingGuardForLaterAttempt() {
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.capture()).thenReturn((BufferedImage) null);
    RecordingConfig cfg = config();
    cfg.setInitialFrameTimeoutMs(250);
    RecordingService service = newService(cfg, capture);

    RecordingTriggerSource source = RecordingTriggerSource.MANUAL;

    assertThatThrownBy(() -> service.startRecording(source))
        .isInstanceOf(FrameCaptureException.class);

    // The finally block must have released the single-flight guard, so a second attempt
    // reaches the readiness wait again instead of failing with "already starting".
    assertThatThrownBy(() -> service.startRecording(source))
        .isInstanceOf(FrameCaptureException.class);
  }
}

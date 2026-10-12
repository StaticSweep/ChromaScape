package com.chromascape.web.recording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameSourceBinding;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import com.chromascape.web.recording.capture.WindowFrameCapture;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RecordingServiceRoundTripTest {

  @Test
  void encoderTimeoutDoesNotCloseOwnedNativeRecorderOrPublishSuccess() throws Exception {
    RecordingConfig config = new RecordingConfig();
    config.setEnabled(true);
    config.setOutputDirectory(Files.createTempDirectory("encoder-timeout").toString());
    RecordingService service =
        new RecordingService(
            config,
            new UnifiedCaptureConfig(),
            mock(UnifiedFrameCaptureService.class),
            mock(WindowFrameCapture.class));
    var nativeRecorder = mock(org.bytedeco.javacv.FFmpegFrameRecorder.class);
    var future = mock(java.util.concurrent.Future.class);
    when(future.get(15, java.util.concurrent.TimeUnit.SECONDS))
        .thenThrow(new java.util.concurrent.TimeoutException());
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "recording", new java.util.concurrent.atomic.AtomicBoolean(true));
    org.springframework.test.util.ReflectionTestUtils.setField(service, "recorder", nativeRecorder);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "encoderFuture", future);
    assertThat(service.stopRecording()).isNull();
    org.mockito.Mockito.verify(nativeRecorder, org.mockito.Mockito.never()).stop();
    org.mockito.Mockito.verify(nativeRecorder, org.mockito.Mockito.never()).release();
    org.assertj.core.api.Assertions.assertThatThrownBy(service::startRecording)
        .hasMessageContaining("not finalized");
    // No real encoder was started by this fixture; cleanup is safe after its owner finishes.
    when(future.isDone()).thenReturn(true);
    service.cleanup();
  }

  @Test
  void boundFramesEncodeAndFinalizeAsANonemptyMp4() throws Exception {
    Path output = Files.createTempDirectory("recording-round-trip");
    RecordingConfig config = new RecordingConfig();
    config.setEnabled(true);
    config.setOutputDirectory(output.toString());
    UnifiedCaptureConfig captureConfig = new UnifiedCaptureConfig();
    captureConfig.setEnabled(true);
    captureConfig.setFps(10);
    UnifiedFrameCaptureService unified = mock(UnifiedFrameCaptureService.class);
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    FrameSourceBinding binding = binding();
    when(capture.captureEvent())
        .thenAnswer(invocation -> event(binding, Color.RED, System.nanoTime()));
    RecordingService service = new RecordingService(config, captureConfig, unified, capture);

    try {
      String recordingId = service.startRecording(RecordingTriggerSource.MANUAL);
      Thread.sleep(120);
      service.onFrameCaptured(event(binding, Color.GREEN, System.nanoTime()));
      Thread.sleep(120);
      service.onFrameCaptured(event(binding, Color.BLUE, System.nanoTime()));

      RecordingService.RecordingMetadata metadata = service.stopRecording();

      assertThat(metadata).isNotNull();
      assertThat(metadata.getId()).isEqualTo(recordingId);
      MeasuredVideoIndex.Index index =
          MeasuredVideoIndex.read(Path.of(metadata.getFilePath()), recordingId);
      assertThat(index.qualityCodes()).isEmpty();
      assertThat(index.frames()).hasSizeGreaterThanOrEqualTo(3);
      assertThat(metadata.getFrameCount()).isEqualTo(index.frames().size());
      assertThat(index.frames())
          .allSatisfy(frame -> assertThat(frame.encodeLagMillis()).isGreaterThanOrEqualTo(0));
      assertThat(Path.of(metadata.getFilePath())).exists().isNotEmptyFile();
      verify(unified).registerSubscriber(service);
      verify(unified).unregisterSubscriber(service);
    } finally {
      service.cleanup();
    }
  }

  private static FrameSourceBinding binding() {
    return new FrameSourceBinding(
        ProcessOrigin.LOCAL, "fedora-host", 101, Instant.parse("2026-09-18T00:00:00Z"), "boot", 6);
  }

  private static FrameEvent event(FrameSourceBinding binding, Color color, long captureTime) {
    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_3BYTE_BGR);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(color);
      graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
    } finally {
      graphics.dispose();
    }
    return new FrameEvent(image, captureTime, 16, 16, binding, null, null, null);
  }
}

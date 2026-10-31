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
  void automaticDurationStopPublishesIndexFromRealDispatchThread() throws Exception {
    var output = Files.createTempDirectory("auto-stop-index");
    var config = new RecordingConfig();
    config.setEnabled(true);
    config.setOutputDirectory(output.toString());
    config.setMinCaptureWidth(16);
    config.setMinCaptureHeight(16);
    config.setStabilityWindowMs(0);
    config.setMaxDurationMinutes(1);
    var captureConfig = new UnifiedCaptureConfig();
    captureConfig.setEnabled(true);
    captureConfig.setFps(5);
    var capture = mock(WindowFrameCapture.class);
    when(capture.captureEvent())
        .thenAnswer(call -> event(binding(), Color.GREEN, System.nanoTime()));
    var unified = new UnifiedFrameCaptureService(captureConfig, capture);
    var service = new RecordingService(config, captureConfig, unified, capture);
    try {
      String id = service.startRecording();
      // Expire only the duration guard; no wall-clock change or minute-long sleep.
      org.springframework.test.util.ReflectionTestUtils.setField(
          service, "recordingStartTime", System.currentTimeMillis() - 60_001L);
      Path video = output.resolve(id + ".mp4");
      Path sidecar = MeasuredVideoIndex.sidecar(video);
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
      while (!Files.exists(sidecar) && System.nanoTime() < deadline) Thread.sleep(20);
      assertThat(service.isActive()).isFalse();
      assertThat(sidecar).exists();
      // This virtual elapsed-time injection may mark a clock reset; it must still
      // finalize honest index bytes rather than interrupting the owning callback.
      assertThat(MeasuredVideoIndex.read(video, id).frames()).isNotEmpty();
    } finally {
      service.cleanup();
    }
  }

  @Test
  void burstInOneCaptureSlotDoesNotStretchTheMediaTimeline() throws Exception {
    RecordingConfig config = new RecordingConfig();
    config.setEnabled(true);
    config.setMinCaptureWidth(16);
    config.setMinCaptureHeight(16);
    config.setStabilityWindowMs(0);
    config.setOutputDirectory(Files.createTempDirectory("calibrated-burst").toString());
    UnifiedCaptureConfig captureConfig = new UnifiedCaptureConfig();
    captureConfig.setEnabled(true);
    captureConfig.setFps(10);
    WindowFrameCapture capture = mock(WindowFrameCapture.class);
    when(capture.captureEvent()).thenAnswer(call -> event(binding(), Color.RED, System.nanoTime()));
    RecordingService service =
        new RecordingService(
            config, captureConfig, mock(UnifiedFrameCaptureService.class), capture);
    try {
      String id = service.startRecording();
      long origin =
          (long)
              org.springframework.test.util.ReflectionTestUtils.getField(
                  service, "recordingStartTimeNano");
      FrameEvent burst = event(binding(), Color.GREEN, origin + 1_000_000);
      for (int i = 0; i < 1000; i++) service.onFrameCaptured(burst);
      Thread.sleep(120);
      service.onFrameCaptured(event(binding(), Color.BLUE, System.nanoTime()));
      var metadata = service.stopRecording();
      assertThat(metadata).isNotNull();
      var index = MeasuredVideoIndex.read(Path.of(metadata.getFilePath()), id);
      assertThat(index.mediaDurationMicros())
          .isLessThanOrEqualTo((metadata.getDuration() + 200) * 1000);
      assertThat(index.frames().size()).isLessThan(100);
      assertThat(metadata.getFrameCount()).isEqualTo(index.frames().size());
    } finally {
      service.cleanup();
    }
  }

  @Test
  void failedReleaseRefusesAnotherStartAndRetainsMedia() throws Exception {
    Path output = Files.createTempDirectory("recorder-release-failure");
    Path media = Files.writeString(output.resolve("previous.mp4"), "preserve-existing-media");
    RecordingConfig config = new RecordingConfig();
    config.setEnabled(true);
    config.setOutputDirectory(output.toString());
    config.setMinCaptureWidth(16);
    config.setMinCaptureHeight(16);
    config.setStabilityWindowMs(0);
    RecordingService service =
        new RecordingService(
            config,
            new UnifiedCaptureConfig(),
            mock(UnifiedFrameCaptureService.class),
            mock(WindowFrameCapture.class));
    var nativeRecorder = mock(org.bytedeco.javacv.FFmpegFrameRecorder.class);
    org.mockito.Mockito.doThrow(new RuntimeException("release failed")).when(nativeRecorder).stop();
    org.springframework.test.util.ReflectionTestUtils.setField(service, "recorder", nativeRecorder);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "encoderFuture", java.util.concurrent.CompletableFuture.completedFuture(null));
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(service::startRecording)
          .hasMessageContaining("not finalized");
      assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service, "recorder"))
          .isSameAs(nativeRecorder);
      assertThat(Files.readString(media)).isEqualTo("preserve-existing-media");
    } finally {
      service.cleanup();
    }
  }

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

    // Reproduce the state after a stop timed out, then its encoder drained later.
    var previousRecorder = mock(org.bytedeco.javacv.FFmpegFrameRecorder.class);
    Path previousMedia = Files.writeString(output.resolve("previous.mp4"), "retained-media");
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "recorder", previousRecorder);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "encoderFuture", java.util.concurrent.CompletableFuture.completedFuture(null));

    try {
      String recordingId = service.startRecording(RecordingTriggerSource.MANUAL);
      verify(previousRecorder).stop();
      verify(previousRecorder).release();
      assertThat(Files.readString(previousMedia)).isEqualTo("retained-media");
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

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
    FrameEvent first = event(binding, Color.RED, 1);
    when(capture.captureEvent()).thenReturn(first);
    RecordingService service = new RecordingService(config, captureConfig, unified, capture);

    try {
      String recordingId = service.startRecording(RecordingTriggerSource.MANUAL);
      service.onFrameCaptured(event(binding, Color.GREEN, 2));
      service.onFrameCaptured(event(binding, Color.BLUE, 3));

      RecordingService.RecordingMetadata metadata = service.stopRecording();

      assertThat(metadata).isNotNull();
      assertThat(metadata.getId()).isEqualTo(recordingId);
      assertThat(metadata.getFrameCount()).isEqualTo(3);
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

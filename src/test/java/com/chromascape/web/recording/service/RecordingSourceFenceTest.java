package com.chromascape.web.recording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameSourceBinding;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import com.chromascape.web.recording.capture.WindowFrameCapture;
import com.chromascape.web.recording.config.RecordingConfig;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RecordingSourceFenceTest {

  @Test
  void recordingAcceptsOnlyFramesFromItsStartTarget() throws Exception {
    RecordingConfig config = new RecordingConfig();
    config.setOutputDirectory(Files.createTempDirectory("recording-source-fence").toString());
    RecordingService service =
        new RecordingService(
            config,
            new UnifiedCaptureConfig(),
            mock(UnifiedFrameCaptureService.class),
            mock(WindowFrameCapture.class));
    FrameSourceBinding pinned = binding("fedora-a", 5);
    set(service, "recordingSourceBinding", pinned);
    set(service, "initialWidth", 4);
    set(service, "initialHeight", 4);
    ((AtomicBoolean) get(service, "recording")).set(true);

    service.onFrameCaptured(event(binding("fedora-b", 5), 1));
    service.onFrameCaptured(event(binding("fedora-a", 6), 2));
    service.onFrameCaptured(event(pinned, 3));

    assertThat(service.getMetrics().get("sourceMismatchFrames")).isEqualTo(2L);
    assertThat(service.getMetrics().get("frameBufferDepth")).isEqualTo(1);
    service.forceResetRecordingState();
  }

  private static FrameSourceBinding binding(String host, long generation) {
    return new FrameSourceBinding(
        ProcessOrigin.LOCAL, host, 100, Instant.parse("2026-09-18T00:00:00Z"), "boot", generation);
  }

  private static FrameEvent event(FrameSourceBinding binding, long time) {
    BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_3BYTE_BGR);
    return new FrameEvent(image, time, 4, 4, binding, null, null, null);
  }

  private static Object get(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}

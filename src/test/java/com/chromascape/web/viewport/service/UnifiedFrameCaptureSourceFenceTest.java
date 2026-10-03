package com.chromascape.web.viewport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.chromascape.utils.core.screen.FrameEvent;
import com.chromascape.utils.core.screen.FrameListener;
import com.chromascape.utils.core.screen.FrameSourceBinding;
import com.chromascape.utils.core.screen.window.ProcessOrigin;
import com.chromascape.web.recording.capture.WindowFrameCapture;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedFrameCaptureSourceFenceTest {

  @Test
  void queuedFrameFromDisplacedHostIsNotDelivered() throws Exception {
    UnifiedFrameCaptureService service =
        new UnifiedFrameCaptureService(new UnifiedCaptureConfig(), mock(WindowFrameCapture.class));
    FrameSourceBinding hostA = binding("host-a", 1);
    FrameSourceBinding hostB = binding("host-b", 1);
    List<FrameEvent> delivered = new ArrayList<>();
    FrameListener listener = listener(delivered);

    observe(service, hostA);
    observe(service, hostB);
    deliver(service, listener, event(hostA, 1));
    deliver(service, listener, event(hostB, 2));

    assertThat(delivered).extracting(FrameEvent::getSourceBinding).containsExactly(hostB);
    assertThat(service.getSourceTransitions()).isEqualTo(1);
    assertThat(service.getStaleSourceDispatches()).isEqualTo(1);
  }

  @Test
  void generationChangeInvalidatesQueuedFrameOnSameHostAndPid() throws Exception {
    UnifiedFrameCaptureService service =
        new UnifiedFrameCaptureService(new UnifiedCaptureConfig(), mock(WindowFrameCapture.class));
    FrameSourceBinding generationOne = binding("host-a", 1);
    FrameSourceBinding generationTwo = binding("host-a", 2);
    List<FrameEvent> delivered = new ArrayList<>();
    FrameListener listener = listener(delivered);

    observe(service, generationOne);
    observe(service, generationTwo);
    deliver(service, listener, event(generationOne, 1));

    assertThat(delivered).isEmpty();
    assertThat(service.getStaleSourceDispatches()).isEqualTo(1);
  }

  @Test
  void anonymousFrameCannotBypassEstablishedBinding() throws Exception {
    UnifiedFrameCaptureService service =
        new UnifiedFrameCaptureService(new UnifiedCaptureConfig(), mock(WindowFrameCapture.class));
    List<FrameEvent> delivered = new ArrayList<>();

    observe(service, binding("host-a", 1));
    deliver(service, listener(delivered), event(null, 1));

    assertThat(delivered).isEmpty();
    assertThat(service.getStaleSourceDispatches()).isEqualTo(1);
  }

  private static FrameListener listener(List<FrameEvent> delivered) {
    return new FrameListener() {
      @Override
      public void onFrameCaptured(FrameEvent event) {
        delivered.add(event);
      }

      @Override
      public boolean isActive() {
        return true;
      }
    };
  }

  private static FrameSourceBinding binding(String host, long generation) {
    return new FrameSourceBinding(
        ProcessOrigin.LOCAL, host, 77, Instant.parse("2026-09-18T00:00:00Z"), "boot", generation);
  }

  private static FrameEvent event(FrameSourceBinding binding, long time) {
    BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_3BYTE_BGR);
    return new FrameEvent(image, time, 2, 2, binding, null, null, null);
  }

  private static void observe(UnifiedFrameCaptureService service, FrameSourceBinding binding)
      throws Exception {
    Method method =
        UnifiedFrameCaptureService.class.getDeclaredMethod(
            "observeSourceBinding", FrameSourceBinding.class);
    method.setAccessible(true);
    method.invoke(service, binding);
  }

  private static void deliver(
      UnifiedFrameCaptureService service, FrameListener listener, FrameEvent event)
      throws Exception {
    Method method =
        UnifiedFrameCaptureService.class.getDeclaredMethod(
            "deliverFrame", FrameListener.class, FrameEvent.class);
    method.setAccessible(true);
    method.invoke(service, listener, event);
  }
}

package com.chromascape.web.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.utils.core.screen.capture.CapturedFrame;
import com.chromascape.utils.core.screen.capture.TargetFrameRuntime;
import com.chromascape.utils.core.screen.capture.TargetFrameSource;
import com.chromascape.utils.core.screen.capture.ViewOnlyFrameSource;
import com.chromascape.utils.core.screen.window.ProcessIdentity;
import java.awt.Rectangle;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ObserveServiceTest {

  private ObserveService service;

  @AfterEach
  void reset() {
    // Stopping releases the watch source from the process-wide runtime so no test can poison
    // a later one. Real sessions are uninstalled by their own tests.
    if (service != null) {
      try {
        service.stop();
      } catch (ObserveException ignored) {
        // Already idle: nothing to release.
      }
      service = null;
    }
  }

  @Test
  void startStopAndStatusRoundTrip() {
    service = new ObserveService((display, width, fps) -> new StubWatch());

    Map<String, Object> started = service.start(":0", 960, 2);
    assertEquals("ACTIVE", started.get("state"));
    assertEquals(":0", started.get("display"));

    Map<String, Object> status = service.status();
    assertEquals("ACTIVE", status.get("state"));

    Map<String, Object> stopped = service.stop();
    assertEquals("IDLE", stopped.get("state"));
  }

  @Test
  void secondStartIsRejectedAndStopWithoutStartFails() {
    service = new ObserveService((display, width, fps) -> new StubWatch());

    service.start("", 0, 0);
    ObserveException conflict = assertThrows(ObserveException.class, () -> service.start("", 0, 0));
    assertEquals(ObserveException.ALREADY_ACTIVE, conflict.getCode());

    service.stop();
    ObserveException idle = assertThrows(ObserveException.class, service::stop);
    assertEquals(ObserveException.NOT_ACTIVE, idle.getCode());
  }

  @Test
  void refusesToStealAnActiveInputSession() {
    service = new ObserveService((display, width, fps) -> new StubWatch());
    StubSession session = new StubSession();
    TargetFrameRuntime.install(session);
    try {
      ObserveException conflict =
          assertThrows(ObserveException.class, () -> service.start("", 0, 0));
      assertEquals(ObserveException.CONFLICT, conflict.getCode());
      assertEquals("IDLE", service.status().get("state"));
    } finally {
      TargetFrameRuntime.uninstall(session);
    }
  }

  @Test
  void reportsEvictedWhenAnInputSessionPreemptsTheWatch() {
    StubWatch watch = new StubWatch();
    service = new ObserveService((display, width, fps) -> watch);

    service.start("", 0, 0);
    StubSession session = new StubSession();
    TargetFrameRuntime.install(session);
    try {
      Map<String, Object> status = service.status();
      assertEquals("EVICTED", status.get("state"));
      assertTrue(watch.closed);
    } finally {
      TargetFrameRuntime.uninstall(session);
    }
    // Stopping clears the evicted state.
    assertEquals("IDLE", service.stop().get("state"));
  }

  private static final class StubWatch implements ViewOnlyFrameSource {
    boolean closed;

    @Override
    public CapturedFrame captureFrame() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessIdentity identity() {
      return null;
    }

    @Override
    public long selectionGeneration() {
      return 0;
    }

    @Override
    public Rectangle targetBounds() {
      return new Rectangle();
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class StubSession implements TargetFrameSource {
    @Override
    public CapturedFrame captureFrame() {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessIdentity identity() {
      return null;
    }

    @Override
    public long selectionGeneration() {
      return 0;
    }

    @Override
    public Rectangle targetBounds() {
      return new Rectangle();
    }
  }
}

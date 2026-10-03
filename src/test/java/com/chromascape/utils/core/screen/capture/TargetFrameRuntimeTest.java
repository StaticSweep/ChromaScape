package com.chromascape.utils.core.screen.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chromascape.utils.core.screen.window.ProcessIdentity;
import java.awt.Rectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TargetFrameRuntimeTest {
  @AfterEach
  void reset() {
    TargetFrameRuntime.resetForTests();
  }

  @Test
  void refusesReplacementAndOnlyUninstallsExactOwner() {
    TargetFrameSource first = new StubSource();
    TargetFrameSource second = new StubSource();
    TargetFrameRuntime.install(first);

    assertThrows(IllegalStateException.class, () -> TargetFrameRuntime.install(second));
    TargetFrameRuntime.uninstall(second);
    assertSame(first, TargetFrameRuntime.requireActive());
    TargetFrameRuntime.uninstall(first);
    assertThrows(IllegalStateException.class, TargetFrameRuntime::requireActive);
  }

  @Test
  void viewOnlyInstallsWhenIdleAndReportsActive() {
    ViewOnlyStub watch = new ViewOnlyStub();
    TargetFrameRuntime.installViewOnly(watch);

    assertTrue(TargetFrameRuntime.isActiveSource(watch));
    assertSame(watch, TargetFrameRuntime.requireActive());
    TargetFrameRuntime.uninstall(watch);
    assertFalse(TargetFrameRuntime.isActiveSource(watch));
    assertThrows(IllegalStateException.class, TargetFrameRuntime::requireActive);
  }

  @Test
  void viewOnlyNeverStealsAnActiveAttachment() {
    TargetFrameSource real = new StubSource();
    TargetFrameRuntime.install(real);

    ViewOnlyStub watch = new ViewOnlyStub();
    assertThrows(IllegalStateException.class, () -> TargetFrameRuntime.installViewOnly(watch));
    assertSame(real, TargetFrameRuntime.requireActive());
    assertFalse(watch.closed);
    TargetFrameRuntime.uninstall(real);
  }

  @Test
  void realSessionPreemptsViewOnlyAndClosesIt() {
    ViewOnlyStub watch = new ViewOnlyStub();
    TargetFrameRuntime.installViewOnly(watch);
    TargetFrameSource real = new StubSource();

    TargetFrameRuntime.install(real);

    assertSame(real, TargetFrameRuntime.requireActive());
    assertTrue(watch.closed);
    assertFalse(TargetFrameRuntime.isActiveSource(watch));
    TargetFrameRuntime.uninstall(real);
  }

  @Test
  void realSessionStillRefusesToReplaceRealSession() {
    TargetFrameSource first = new StubSource();
    TargetFrameRuntime.install(first);

    assertThrows(IllegalStateException.class, () -> TargetFrameRuntime.install(new StubSource()));
    assertSame(first, TargetFrameRuntime.requireActive());
    TargetFrameRuntime.uninstall(first);
  }

  private static final class ViewOnlyStub implements ViewOnlyFrameSource {
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

  private static final class StubSource implements TargetFrameSource {
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

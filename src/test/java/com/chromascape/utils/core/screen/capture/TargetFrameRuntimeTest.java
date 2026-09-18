package com.chromascape.utils.core.screen.capture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

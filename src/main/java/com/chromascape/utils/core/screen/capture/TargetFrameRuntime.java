package com.chromascape.utils.core.screen.capture;

import java.util.concurrent.atomic.AtomicReference;

/** Process-wide bridge exposing only the currently active, lifecycle-owned frame source. */
public final class TargetFrameRuntime {
  private static final AtomicReference<TargetFrameSource> ACTIVE = new AtomicReference<>();

  private TargetFrameRuntime() {}

  /** Installs a source, refusing to replace an active attachment silently. */
  public static void install(TargetFrameSource source) {
    if (source == null) {
      throw new IllegalArgumentException("Frame source must not be null");
    }
    if (!ACTIVE.compareAndSet(null, source)) {
      throw new IllegalStateException("A target frame source is already active");
    }
  }

  /** Removes exactly the supplied source; a later replacement can never be cleared accidentally. */
  public static void uninstall(TargetFrameSource source) {
    if (source != null) {
      ACTIVE.compareAndSet(source, null);
    }
  }

  /** Returns the active source or fails bounded when no Controller-owned attachment exists. */
  public static TargetFrameSource requireActive() {
    TargetFrameSource source = ACTIVE.get();
    if (source == null) {
      throw new IllegalStateException(
          "No rendered-buffer source is active; initialize a selected target session first");
    }
    return source;
  }

  static void resetForTests() {
    ACTIVE.set(null);
  }
}

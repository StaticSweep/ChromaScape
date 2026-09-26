package com.chromascape.utils.core.screen.capture;

import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Process-wide bridge exposing only the currently active, lifecycle-owned frame source. */
public final class TargetFrameRuntime {
  private static final Logger logger = LogManager.getLogger(TargetFrameRuntime.class);

  private static final AtomicReference<TargetFrameSource> ACTIVE = new AtomicReference<>();

  private TargetFrameRuntime() {}

  /** Installs a source, refusing to replace an active attachment silently. */
  public static synchronized void install(TargetFrameSource source) {
    if (source == null) {
      throw new IllegalArgumentException("Frame source must not be null");
    }
    TargetFrameSource active = ACTIVE.get();
    if (active == null) {
      if (!ACTIVE.compareAndSet(null, source)) {
        throw new IllegalStateException("A target frame source is already active");
      }
      return;
    }
    if (active instanceof ViewOnlyFrameSource viewOnly
        && !(source instanceof ViewOnlyFrameSource)) {
      // Observation is strictly preemptible: a real input session always wins over a watching
      // source. The evicted source is closed so its native pipeline cannot leak.
      if (!ACTIVE.compareAndSet(active, source)) {
        throw new IllegalStateException("A target frame source is already active");
      }
      try {
        viewOnly.close();
      } catch (RuntimeException failure) {
        logger.warn("View-only frame source threw during preemption close", failure);
      }
      return;
    }
    throw new IllegalStateException("A target frame source is already active");
  }

  /**
   * Installs a view-only observation source, which never takes input or attaches.
   *
   * <p>Refuses when any source is already active, so observation can never steal an attachment. A
   * later real input session preempts the view-only source via {@link #install(TargetFrameSource)}.
   */
  public static synchronized void installViewOnly(ViewOnlyFrameSource source) {
    if (source == null) {
      throw new IllegalArgumentException("Frame source must not be null");
    }
    if (!ACTIVE.compareAndSet(null, source)) {
      throw new IllegalStateException("A target frame source is already active");
    }
  }

  /** Returns whether the supplied source is the currently active one. */
  public static boolean isActiveSource(TargetFrameSource source) {
    return source != null && ACTIVE.get() == source;
  }

  /** Removes exactly the supplied source; a later replacement can never be cleared accidentally. */
  public static synchronized void uninstall(TargetFrameSource source) {
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

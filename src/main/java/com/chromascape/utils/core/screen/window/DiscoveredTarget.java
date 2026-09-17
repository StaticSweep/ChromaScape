package com.chromascape.utils.core.screen.window;

import java.util.Objects;

/**
 * One eligible local target produced by discovery.
 *
 * <p>Discovery only describes a candidate; it never becomes an attachment, injection or focus
 * action.
 *
 * @param identity the neutral process identity; may be null when the platform cannot observe a
 *     start time (Windows falls back to the stored window pid; such candidates fail closed on
 *     attachment-oriented resolution)
 * @param window the user-facing selection value carrying the candidate id and pid
 * @param discovery source label ({@code "process"} on Linux, {@code "supported-client"} / {@code
 *     "java-fallback"} on Windows)
 * @param fallback whether this is a fallback candidate ({@code true} for Windows {@code
 *     "java-fallback"} listings)
 * @param captureable whether the platform metadata marks this candidate captureable ({@code false}
 *     on Linux; SC-1665 decides actual capturability)
 * @param selectionReason why this candidate is offered
 */
public record DiscoveredTarget(
    ProcessIdentity identity,
    WindowInfo window,
    String source,
    boolean fallback,
    boolean captureable,
    String selectionReason) {

  public DiscoveredTarget {
    Objects.requireNonNull(window, "window");
  }

  /**
   * Compatibility constructor for discoveries without UI flags: both flags default to {@code false}
   * (the Linux process-candidate shape).
   */
  public DiscoveredTarget(
      ProcessIdentity identity, WindowInfo window, String source, String selectionReason) {
    this(identity, window, source, false, false, selectionReason);
  }
}

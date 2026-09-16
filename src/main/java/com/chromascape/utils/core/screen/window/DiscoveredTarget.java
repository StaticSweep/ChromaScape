package com.chromascape.utils.core.screen.window;

import java.util.Objects;

/**
 * One eligible local target produced by discovery.
 *
 * <p>Discovery only describes a candidate; it never becomes an attachment, injection or focus
 * action.
 *
 * @param identity the neutral process identity; may be null when the platform cannot observe a
 *     start time (Windows falls back to the stored window pid)
 * @param window the user-facing selection value carrying the candidate id and pid
 * @param discovery source label ({@code "process"} on Linux, {@code "supported-client"} /
 *     {@code "java-fallback"} on Windows)
 * @param selectionReason why this candidate is offered
 */
public record DiscoveredTarget(
    ProcessIdentity identity, WindowInfo window, String source, String selectionReason) {

  public DiscoveredTarget {
    Objects.requireNonNull(window, "window");
  }
}

package com.chromascape.utils.core.screen.capture;

import com.chromascape.utils.core.screen.window.ProcessIdentity;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.Objects;

/**
 * One detached, caller-owned target frame and its observation metadata.
 *
 * <p>The image is a new allocation for every successful capture. Neither the native buffer nor a
 * prior caller's image is retained or reused.
 */
public record CapturedFrame(
    BufferedImage image,
    FrameFreshness freshness,
    Instant observedAt,
    Instant contentChangedAt,
    ProcessIdentity identity,
    long selectionGeneration) {

  /** Validates the immutable metadata and the ownership-bearing image reference. */
  public CapturedFrame {
    Objects.requireNonNull(image, "image");
    Objects.requireNonNull(freshness, "freshness");
    Objects.requireNonNull(observedAt, "observedAt");
    Objects.requireNonNull(contentChangedAt, "contentChangedAt");
    Objects.requireNonNull(identity, "identity");
  }
}

package com.chromascape.utils.core.screen.capture;

/** Content-based freshness of a rendered target frame. */
public enum FrameFreshness {
  /** First frame observed from this attachment. */
  NEW,
  /** Pixel content differs from the preceding frame. */
  CHANGED,
  /** Pixel content is unchanged, but has not exceeded the pause threshold. */
  UNCHANGED,
  /** Pixel content has remained unchanged for at least the pause threshold. */
  PAUSED
}

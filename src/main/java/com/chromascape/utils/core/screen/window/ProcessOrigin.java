package com.chromascape.utils.core.screen.window;

/** Origin of a discovered process target. */
public enum ProcessOrigin {
  /** Process running on the same host as ChromaScape. */
  LOCAL,
  /** Process reported by a remote executor; never a local discovery result. */
  REMOTE
}

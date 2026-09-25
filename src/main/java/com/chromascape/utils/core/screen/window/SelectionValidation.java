package com.chromascape.utils.core.screen.window;

/** Outcome of the read-only revalidation of the current target selection. */
public enum SelectionValidation {
  /** A selection exists, its process is alive and its identity still matches. */
  VALID,
  /** No selection exists. */
  NONE,
  /** The caller presented a generation older than the current selection generation. */
  STALE_GENERATION,
  /** The selected process no longer exists. */
  EXITED,
  /** The pid exists but its start time/boot id no longer matches: the pid was reused. */
  REPLACED,
  /** The selected identity is not local and can never be resolved locally. */
  NOT_LOCAL,
  /** This platform has no discovery implementation to validate against. */
  UNSUPPORTED_PLATFORM
}

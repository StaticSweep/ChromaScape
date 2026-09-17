package com.chromascape.utils.core.input.remoteinput;

/**
 * Stable diagnostic categories for Linux RemoteInput native-artifact resolution and loading.
 *
 * <p>These values are part of the contract consumed by SC-1664 and later stories. Callers should
 * branch on the category rather than parsing messages; the message carries operator detail, the
 * category carries meaning.
 */
public enum RemoteInputDiagnostic {
  /** The intended artifact is absent. */
  MISSING_LIBRARY,
  /** The host is unsupported, or the artifact targets a different architecture. */
  UNSUPPORTED_PLATFORM_OR_ARCH,
  /** The selected file exists but is not a valid expected artifact. */
  INVALID_ARTIFACT,
  /** Native loading failed, including failure to resolve transitive dependencies. */
  LOAD_FAILED,
  /** A required exported binding is unavailable in the loaded library. */
  MISSING_SYMBOL
}

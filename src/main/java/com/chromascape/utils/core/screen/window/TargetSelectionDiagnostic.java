package com.chromascape.utils.core.screen.window;

/**
 * Sanitized diagnostic vocabulary for target discovery, resolution and revalidation.
 *
 * <p>Diagnostics never carry command lines, credentials, account identity or file contents; they
 * may carry pid, origin, host id (when configured), expected vs observed start time and bounded
 * counts.
 */
public enum TargetSelectionDiagnostic {
  /** Discovery found no eligible local client. */
  NO_ELIGIBLE_TARGET,
  /** More than one eligible candidate exists and no explicit selection matched one of them. */
  AMBIGUOUS_TARGET,
  /** One or more procfs entries were skipped as missing, unreadable, malformed or empty. */
  PROC_ENTRY_UNREADABLE,
  /** This platform has no discovery implementation. */
  UNSUPPORTED_PLATFORM,
  /** The selected process exited. */
  TARGET_EXITED,
  /** The selected pid was reused by a different process (start time/boot id mismatch). */
  TARGET_REPLACED,
  /** The requested identity is not local. */
  TARGET_NOT_LOCAL,
  /** An explicit selection no longer matches any current candidate. */
  STALE_SELECTION
}

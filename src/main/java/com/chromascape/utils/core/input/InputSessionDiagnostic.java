package com.chromascape.utils.core.input;

/**
 * Stable, sanitized reasons an input session operation can be refused or fail.
 *
 * <p>Consumers branch on the category, never on message text. The {@link InputSessionException}
 * message carries operator detail.
 */
public enum InputSessionDiagnostic {
  /** RemoteInput attachment is disabled by configuration. */
  ATTACHMENT_DISABLED,
  /** No target selection exists. */
  NO_SELECTION,
  /** Discovery found more than one eligible target and the selection matched none. */
  AMBIGUOUS_TARGET,
  /** The selected target has no neutral process identity to bind to. */
  IDENTITY_UNAVAILABLE,
  /** The selection no longer resolves to a discovered target. */
  STALE_SELECTION,
  /** The selection generation advanced; the caller observed an obsolete selection. */
  STALE_GENERATION,
  /** The selected process no longer exists. */
  TARGET_EXITED,
  /** The pid exists but its start time or host no longer matches: the pid was reused. */
  TARGET_REPLACED,
  /** The selected identity is not local and can never be resolved locally. */
  NOT_LOCAL,
  /** This platform has no input implementation. */
  UNSUPPORTED_PLATFORM,
  /** The requested input primitive is not implemented by this backend. */
  INPUT_UNSUPPORTED,
  /** The native artifact is missing, invalid, or targets another platform. */
  ARTIFACT_UNAVAILABLE,
  /** Loading the native library failed. */
  LOAD_FAILED,
  /** Native client enumeration returned values outside the documented domain. */
  CLIENT_ENUMERATION_INVALID,
  /** Injecting RemoteInput into the target failed. */
  INJECTION_FAILED,
  /** Injecting RemoteInput into the target did not complete before the deadline. */
  INJECTION_TIMEOUT,
  /** Pairing with the injected target did not complete before the deadline. */
  PAIR_TIMEOUT,
  /** Pairing returned a null or zero native target handle. */
  INVALID_TARGET_HANDLE,
  /** A session is already attached to the selected identity. */
  TARGET_ALREADY_ATTACHED,
  /** A native call failed. */
  NATIVE_CALL_FAILED,
  /** A native call exceeded its deadline. */
  NATIVE_CALL_TIMEOUT,
  /** Releasing the native target exceeded its deadline. */
  RELEASE_TIMEOUT,
  /** The session is closed. */
  SESSION_CLOSED,
  /** The session's native call lane timed out and can never be reused. */
  SESSION_POISONED
}

package com.chromascape.utils.core.input;

import com.chromascape.utils.core.screen.window.ProcessIdentity;

/**
 * Exception carrying a stable {@link InputSessionDiagnostic} without leaking process command-line
 * data.
 */
public class InputSessionException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final transient InputSessionDiagnostic diagnostic;
  private final transient ProcessIdentity identity;
  private final long expectedSelectionGeneration;

  /**
   * Creates a diagnostic exception.
   *
   * @param diagnostic the stable category
   * @param identity the bound identity, or {@code null}
   * @param expectedSelectionGeneration the selection generation the caller observed
   * @param detail operator-facing detail
   */
  public InputSessionException(
      InputSessionDiagnostic diagnostic,
      ProcessIdentity identity,
      long expectedSelectionGeneration,
      String detail) {
    this(diagnostic, identity, expectedSelectionGeneration, detail, null);
  }

  /**
   * Creates a diagnostic exception with a cause.
   *
   * @param diagnostic the stable category
   * @param identity the bound identity, or {@code null}
   * @param expectedSelectionGeneration the selection generation the caller observed
   * @param detail operator-facing detail
   * @param cause the underlying failure
   */
  public InputSessionException(
      InputSessionDiagnostic diagnostic,
      ProcessIdentity identity,
      long expectedSelectionGeneration,
      String detail,
      Throwable cause) {
    super(detail == null || detail.isBlank() ? diagnostic.name() : detail, cause);
    this.diagnostic = diagnostic;
    this.identity = identity;
    this.expectedSelectionGeneration = expectedSelectionGeneration;
  }

  /** Returns the stable diagnostic category. */
  public InputSessionDiagnostic diagnostic() {
    return diagnostic;
  }

  /** Returns the bound identity, or {@code null}. */
  public ProcessIdentity identity() {
    return identity;
  }

  /** Returns the selection generation the caller observed. */
  public long expectedSelectionGeneration() {
    return expectedSelectionGeneration;
  }
}

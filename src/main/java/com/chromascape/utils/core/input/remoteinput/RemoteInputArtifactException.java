package com.chromascape.utils.core.input.remoteinput;

import java.nio.file.Path;

/**
 * Raised when the Linux RemoteInput native artifact cannot be resolved or loaded.
 *
 * <p>The exception carries a stable {@link RemoteInputDiagnostic} category plus enough context to
 * act on (the selected path and the expected platform/architecture). It never contains secrets,
 * account identity, or process command lines.
 *
 * <p>Failure is terminal: no caller may fall back to attaching to a process or to loading a
 * different, unrequested library after this is thrown.
 */
public class RemoteInputArtifactException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient RemoteInputDiagnostic diagnostic;
  private final String selectedPath;
  private final String expected;

  /**
   * Creates a failure from a path.
   *
   * @param diagnostic the stable category
   * @param selectedPath the path that was selected, or {@code null}
   * @param expected the expected platform/architecture description
   * @param message operator detail
   */
  public RemoteInputArtifactException(
      RemoteInputDiagnostic diagnostic, Path selectedPath, String expected, String message) {
    this(
        diagnostic, selectedPath == null ? null : selectedPath.toString(), expected, message, null);
  }

  /**
   * Creates a failure from a path with an underlying cause.
   *
   * @param diagnostic the stable category
   * @param selectedPath the path that was selected, or {@code null}
   * @param expected the expected platform/architecture description
   * @param message operator detail
   * @param cause the original native or I/O failure
   */
  public RemoteInputArtifactException(
      RemoteInputDiagnostic diagnostic,
      Path selectedPath,
      String expected,
      String message,
      Throwable cause) {
    this(
        diagnostic,
        selectedPath == null ? null : selectedPath.toString(),
        expected,
        message,
        cause);
  }

  /**
   * Creates a failure from an already-stringified path.
   *
   * @param diagnostic the stable category
   * @param selectedPath the path that was selected, or {@code null}
   * @param expected the expected platform/architecture description
   * @param message operator detail
   * @param cause the original native or I/O failure
   */
  public RemoteInputArtifactException(
      RemoteInputDiagnostic diagnostic,
      String selectedPath,
      String expected,
      String message,
      Throwable cause) {
    super(compose(diagnostic, message, selectedPath, expected), cause);
    this.diagnostic = diagnostic;
    this.selectedPath = selectedPath;
    this.expected = expected;
  }

  private static String compose(
      RemoteInputDiagnostic diagnostic, String message, String selectedPath, String expected) {
    StringBuilder sb = new StringBuilder();
    sb.append('[').append(diagnostic).append("] ").append(message);
    if (selectedPath != null) {
      sb.append(" (selected path: ").append(selectedPath).append(')');
    }
    if (expected != null) {
      sb.append(" (expected: ").append(expected).append(')');
    }
    return sb.toString();
  }

  /** Returns the stable diagnostic category for this failure. */
  public RemoteInputDiagnostic diagnostic() {
    return diagnostic;
  }

  /** The path that was selected, or {@code null} when no path was reached. */
  public String selectedPath() {
    return selectedPath;
  }

  /** Human-readable expected platform/architecture, or {@code null}. */
  public String expected() {
    return expected;
  }

  /** True when {@code throwable} or any cause is a RemoteInput artifact failure. */
  public static boolean isRemoteInputFailure(Throwable throwable) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (t instanceof RemoteInputArtifactException) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }
}

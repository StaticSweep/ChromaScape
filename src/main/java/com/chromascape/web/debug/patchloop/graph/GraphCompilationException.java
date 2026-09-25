package com.chromascape.web.debug.patchloop.graph;

/** Exception thrown when graph compilation fails. */
public class GraphCompilationException extends Exception {

  private final int lineNumber;

  public GraphCompilationException(String message) {
    super(message);
    this.lineNumber = -1;
  }

  public GraphCompilationException(String message, Throwable cause) {
    super(message, cause);
    this.lineNumber = -1;
  }

  public GraphCompilationException(String message, int lineNumber) {
    super(message + " at line " + lineNumber);
    this.lineNumber = lineNumber;
  }

  public GraphCompilationException(String message, int lineNumber, Throwable cause) {
    super(message + " at line " + lineNumber, cause);
    this.lineNumber = lineNumber;
  }

  public int getLineNumber() {
    return lineNumber;
  }
}

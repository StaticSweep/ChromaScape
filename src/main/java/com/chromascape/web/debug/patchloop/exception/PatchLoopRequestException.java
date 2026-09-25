package com.chromascape.web.debug.patchloop.exception;

/** Runtime exception for operator-facing patch-loop request validation failures. */
public class PatchLoopRequestException extends RuntimeException {

  private final String errorCode;

  public PatchLoopRequestException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String getErrorCode() {
    return errorCode;
  }
}

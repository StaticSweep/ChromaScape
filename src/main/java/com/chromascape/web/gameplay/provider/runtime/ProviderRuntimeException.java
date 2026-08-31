package com.chromascape.web.gameplay.provider.runtime;

/** Typed, secret-safe runtime error suitable for API and audit boundaries. */
public final class ProviderRuntimeException extends IllegalStateException {
  private final String failureCode;

  public ProviderRuntimeException(String failureCode, String message) {
    super(message);
    this.failureCode = failureCode;
  }

  public String failureCode() {
    return failureCode;
  }
}

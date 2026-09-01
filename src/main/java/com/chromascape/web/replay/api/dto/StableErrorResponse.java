package com.chromascape.web.replay.api.dto;

import java.util.UUID;

/**
 * Stable error response transferred over the wire. Never includes paths, process output, or raw
 * exception messages.
 */
public record StableErrorResponse(
    int errorVersion,
    String code,
    String message,
    boolean retryable,
    String remediation,
    String correlationId) {

  public static final int CURRENT_VERSION = 1;

  public static StableErrorResponse of(String code, String message, boolean retryable) {
    return new StableErrorResponse(CURRENT_VERSION, code, message, retryable, null, null);
  }

  public static StableErrorResponse of(
      String code, String message, boolean retryable, String remediation) {
    return new StableErrorResponse(CURRENT_VERSION, code, message, retryable, remediation, null);
  }

  public static StableErrorResponse withCorrelation(
      String code, String message, boolean retryable, String remediation, String correlationId) {
    return new StableErrorResponse(
        CURRENT_VERSION, code, message, retryable, remediation, correlationId);
  }

  /** Generates a fresh correlation ID. */
  public static String newCorrelationId() {
    return UUID.randomUUID().toString();
  }
}

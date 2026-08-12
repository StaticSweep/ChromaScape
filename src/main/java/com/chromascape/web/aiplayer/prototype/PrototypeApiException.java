package com.chromascape.web.aiplayer.prototype;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Structured error for the AI-player prototype endpoints.
 *
 * <p>Serialized as {@code {status, code, message, details}} with a stable HTTP status per the
 * prototype HTTP error contract. Never exposes stack traces, credentials, or filesystem secrets.
 */
public class PrototypeApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final Map<String, Object> details;

  public PrototypeApiException(HttpStatus status, String code, String message) {
    this(status, code, message, Map.of());
  }

  public PrototypeApiException(
      HttpStatus status, String code, String message, Map<String, Object> details) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details != null ? details : Map.of();
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public Map<String, Object> getDetails() {
    return details;
  }

  /** Builds the structured error body. */
  public Map<String, Object> toErrorBody() {
    return Map.of("status", "REJECTED", "code", code, "message", getMessage(), "details", details);
  }
}

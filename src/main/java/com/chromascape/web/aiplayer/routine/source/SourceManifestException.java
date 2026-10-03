package com.chromascape.web.aiplayer.routine.source;

import org.springframework.http.HttpStatus;

/** Runtime exception carrying the stable source contract error code and HTTP mapping. */
public class SourceManifestException extends RuntimeException {
  private final SourceManifestFailureCode code;
  private final HttpStatus status;

  public SourceManifestException(SourceManifestFailureCode code, String message) {
    this(code, defaultStatus(code), message, null);
  }

  public SourceManifestException(SourceManifestFailureCode code, String message, Throwable cause) {
    this(code, defaultStatus(code), message, cause);
  }

  public SourceManifestException(
      SourceManifestFailureCode code, HttpStatus status, String message) {
    this(code, status, message, null);
  }

  public SourceManifestException(
      SourceManifestFailureCode code, HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.code = code == null ? SourceManifestFailureCode.SOURCE_UNAVAILABLE : code;
    this.status = status == null ? HttpStatus.UNPROCESSABLE_ENTITY : status;
  }

  public SourceManifestFailureCode code() {
    return code;
  }

  public HttpStatus status() {
    return status;
  }

  private static HttpStatus defaultStatus(SourceManifestFailureCode code) {
    if (code == null) return HttpStatus.UNPROCESSABLE_ENTITY;
    return switch (code) {
      case SOURCE_ACCESS_DENIED -> HttpStatus.UNAUTHORIZED;
      case SOURCE_REDACTED,
          SOURCE_FILE_NOT_FOUND,
          SOURCE_SYMBOL_NOT_FOUND,
          SOURCE_UNAVAILABLE -> HttpStatus.NOT_FOUND;
      case SOURCE_PRUNED -> HttpStatus.GONE;
      case SOURCE_NETWORK_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
      case SOURCE_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
      case SOURCE_BUILD_MISMATCH -> HttpStatus.CONFLICT;
      case SOURCE_AUDIT_CONTEXT_INVALID -> HttpStatus.BAD_REQUEST;
      default -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
  }
}

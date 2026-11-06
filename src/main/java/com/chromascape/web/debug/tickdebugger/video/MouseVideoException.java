package com.chromascape.web.debug.tickdebugger.video;

/**
 * Typed external-video failure carrying a stable machine code and a retryability hint. Codes are
 * surfaced verbatim by the API and persisted on asynchronous job failures.
 */
public class MouseVideoException extends RuntimeException {

  public static final String CODE_INVALID_REQUEST = "INVALID_REQUEST";
  public static final String CODE_UNAUTHENTICATED = "UNAUTHENTICATED";
  public static final String CODE_FORBIDDEN = "FORBIDDEN";
  public static final String CODE_NOT_FOUND = "NOT_FOUND";
  public static final String CODE_CONFLICT = "CONFLICT";
  public static final String CODE_MEDIA_LIMIT = "MEDIA_LIMIT";
  public static final String CODE_UNSUPPORTED_MEDIA = "UNSUPPORTED_MEDIA";
  public static final String CODE_CHECKSUM_MISMATCH = "CHECKSUM_MISMATCH";
  public static final String CODE_DECODER_FAILED = "DECODER_FAILED";
  public static final String CODE_NO_VISIBLE_CURSOR = "NO_VISIBLE_CURSOR";
  public static final String CODE_CURSOR_UNSUPPORTED = "CURSOR_UNSUPPORTED";
  public static final String CODE_SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";
  public static final String CODE_SOURCE_AUTH_REQUIRED = "SOURCE_AUTH_REQUIRED";
  public static final String CODE_SOURCE_RATE_LIMITED = "SOURCE_RATE_LIMITED";
  public static final String CODE_TOOL_UNAVAILABLE = "TOOL_UNAVAILABLE";
  public static final String CODE_QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
  public static final String CODE_WORKER_TIMEOUT = "WORKER_TIMEOUT";
  public static final String CODE_INTERRUPTED = "INTERRUPTED";
  public static final String CODE_CLEANUP_UNCONFIRMED = "CLEANUP_UNCONFIRMED";
  public static final String CODE_DISABLED = "FEATURE_DISABLED";
  public static final String CODE_IDENTITY_CHANGED = "IDENTITY_CHANGED";

  private final String code;
  private final boolean retryable;

  public MouseVideoException(String code, String message) {
    this(code, message, false);
  }

  public MouseVideoException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public MouseVideoException(String code, String message, Throwable cause) {
    this(code, message, false, cause);
  }

  public MouseVideoException(String code, String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public String getCode() {
    return code;
  }

  public boolean isRetryable() {
    return retryable;
  }
}

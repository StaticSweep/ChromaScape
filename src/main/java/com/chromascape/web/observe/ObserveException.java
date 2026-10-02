package com.chromascape.web.observe;

/** Failure of a view-only observation request, carrying a stable machine-readable code. */
public class ObserveException extends RuntimeException {

  /** A watch is already running. */
  public static final String ALREADY_ACTIVE = "OBSERVE_ALREADY_ACTIVE";

  /** No watch is running. */
  public static final String NOT_ACTIVE = "OBSERVE_NOT_ACTIVE";

  /** An input session already owns the frame runtime; observation refuses to steal it. */
  public static final String CONFLICT = "OBSERVE_CONFLICT";

  /** ffmpeg is not installed or not executable. */
  public static final String FFMPEG_MISSING = "OBSERVE_FFMPEG_MISSING";

  /** The capture pipeline could not start. */
  public static final String START_FAILED = "OBSERVE_START_FAILED";

  private final String code;

  public ObserveException(String code, String message) {
    super(message);
    this.code = code;
  }

  public ObserveException(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  /** Returns the stable machine-readable failure code. */
  public String getCode() {
    return code;
  }
}

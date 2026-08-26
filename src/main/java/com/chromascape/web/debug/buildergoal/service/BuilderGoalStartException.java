package com.chromascape.web.debug.buildergoal.service;

import com.chromascape.web.debug.buildergoal.dto.BuilderGoalStartFailureResponse;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;

/**
 * Sanitized, traceable failure raised while accepting a Builder Goal run.
 *
 * <p>The original cause is retained for server-side diagnostics, while every externally visible
 * string is bounded and passed through the Builder Goal sensitive-text sanitizer.
 */
public class BuilderGoalStartException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final String phase;
  private final String safeDetail;
  private final String iterationId;
  private final String logFile;
  private final String correlationId;

  public BuilderGoalStartException(
      HttpStatus status,
      String code,
      String phase,
      String safeDetail,
      String iterationId,
      Path logFile,
      String correlationId,
      Throwable cause) {
    super(safe(safeDetail), cause);
    this.status = status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    this.code = safe(code);
    this.phase = safe(phase);
    this.safeDetail = safe(safeDetail);
    this.iterationId = safe(iterationId);
    this.logFile =
        logFile != null
            ? BuilderGoalSensitiveTextSanitizer.sanitize(logFile.toString())
            : null;
    this.correlationId = safe(correlationId);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }

  public String getPhase() {
    return phase;
  }

  public String getSafeDetail() {
    return safeDetail;
  }

  public String getIterationId() {
    return iterationId;
  }

  public String getLogFile() {
    return logFile;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public BuilderGoalStartFailureResponse toResponse() {
    String statusUrl =
        iterationId != null
            ? "/api/debug/builder-goals/runs/" + iterationId
            : null;
    return new BuilderGoalStartFailureResponse(
        "Failed to start builder goal run",
        code,
        phase,
        safeDetail,
        iterationId,
        statusUrl,
        logFile,
        correlationId);
  }

  private static String safe(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String sanitized = BuilderGoalSensitiveTextSanitizer.sanitize(value).trim();
    return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
  }
}

package com.chromascape.web.replay.error;

import com.chromascape.web.replay.api.dto.StableErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates ReplayException into stable API error responses. */
@RestControllerAdvice(basePackages = "com.chromascape.web.replay")
public class ReplayExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ReplayExceptionHandler.class);

  @ExceptionHandler(ReplayException.class)
  public ResponseEntity<StableErrorResponse> handleReplayException(ReplayException ex) {
    String correlationId = StableErrorResponse.newCorrelationId();
    if (ex.getHttpStatus().is5xxServerError()) {
      log.error(
          "Replay error [{}]: {} (correlation={})",
          ex.getErrorCode(),
          ex.getSafeMessage(),
          correlationId,
          ex);
    } else {
      log.warn(
          "Replay client error [{}]: {} (correlation={})",
          ex.getErrorCode(),
          ex.getSafeMessage(),
          correlationId);
    }
    return ResponseEntity.status(ex.getHttpStatus())
        .body(
            StableErrorResponse.withCorrelation(
                ex.getErrorCode().name(),
                ex.getSafeMessage(),
                ex.isRetryable(),
                ex.getRemediation(),
                correlationId));
  }
}

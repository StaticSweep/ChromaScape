package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.routine.source.SourceAccessPolicy;
import com.chromascape.web.aiplayer.routine.source.SourceDebuggerReadService;
import com.chromascape.web.aiplayer.routine.source.SourceManifestException;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authorized read-only debugger source API. There are intentionally no write or export methods. */
@RestController
@RequestMapping("/api/v1/debugger/source")
public class SourceDebuggerController {
  private final SourceDebuggerReadService service;
  private final SourceAccessPolicy access;

  public SourceDebuggerController(SourceDebuggerReadService service, SourceAccessPolicy access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping("/runs/{runId}/manifest")
  public ResponseEntity<Object> manifest(
      @PathVariable String runId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    return withCorrelation(correlationId, () -> {
      String actor = access.authorize(authorization, SourceAccessPolicy.Purpose.METADATA);
      return ResponseEntity.ok(service.manifest(runId, actor));
    });
  }

  @GetMapping("/runs/{runId}/files/{fileId}")
  public ResponseEntity<Object> file(
      @PathVariable String runId,
      @PathVariable String fileId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    return withCorrelation(correlationId, () -> {
      String actor = access.authorize(authorization, SourceAccessPolicy.Purpose.CONTENT);
      return ResponseEntity.ok(service.file(runId, fileId, actor));
    });
  }

  @GetMapping("/runs/{runId}/symbols")
  public ResponseEntity<Object> symbols(
      @PathVariable String runId,
      @RequestParam(required = false) String fileId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int limit,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    return withCorrelation(correlationId, () -> {
      String actor = access.authorize(authorization, SourceAccessPolicy.Purpose.METADATA);
      return ResponseEntity.ok(service.symbols(runId, fileId, page, limit, actor));
    });
  }

  @ExceptionHandler(SourceManifestException.class)
  public ResponseEntity<Object> sourceError(SourceManifestException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of(
        "schema", "source-error/v1",
        "code", exception.code().name(),
        "message", exception.getMessage() == null ? "source request failed" : exception.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> unexpectedSourceError(Exception exception) {
    return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
        "schema", "source-error/v1",
        "code", "SOURCE_UNAVAILABLE",
        "message", "source request is unavailable"));
  }

  private static <T> T withCorrelation(String value, Supplier<T> action) {
    String previous = MDC.get("source_correlation_id");
    String normalized = normalizeCorrelation(value);
    if (normalized != null) MDC.put("source_correlation_id", normalized);
    try {
      return action.get();
    } finally {
      if (previous == null) MDC.remove("source_correlation_id");
      else MDC.put("source_correlation_id", previous);
    }
  }

  private static String normalizeCorrelation(String value) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > 160) normalized = normalized.substring(0, 160);
    for (int index = 0; index < normalized.length(); index++) {
      if (Character.isISOControl(normalized.charAt(index))) return null;
    }
    return normalized;
  }
}

package com.chromascape.web.aiplayer.routine.controller;

import com.chromascape.web.aiplayer.routine.source.SourceAccessPolicy;
import com.chromascape.web.aiplayer.routine.source.SourceDebuggerReadService;
import com.chromascape.web.aiplayer.routine.source.SourceManifestException;
import com.chromascape.web.aiplayer.routine.source.SourceReadAuditService;
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
  private final SourceReadAuditService audit;

  public SourceDebuggerController(
      SourceDebuggerReadService service, SourceAccessPolicy access, SourceReadAuditService audit) {
    this.service = service;
    this.access = access;
    this.audit = audit;
  }

  @GetMapping("/runs/{runId}/manifest")
  public ResponseEntity<Object> manifest(
      @PathVariable String runId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Source-Environment", required = false) String environment,
      @RequestHeader(value = "X-Source-Actor", required = false) String actor,
      @RequestHeader(value = "X-Source-Purpose", required = false) String purpose,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    RequestContext context =
        authorize(
            runId,
            null,
            authorization,
            environment,
            actor,
            purpose,
            correlationId,
            SourceAccessPolicy.Purpose.METADATA,
            "manifest");
    return withCorrelation(
        context.correlationId(),
        () -> {
          return ResponseEntity.ok(service.manifest(runId, context.actor()));
        });
  }

  @GetMapping("/runs/{runId}/files/{fileId}")
  public ResponseEntity<Object> file(
      @PathVariable String runId,
      @PathVariable String fileId,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Source-Environment", required = false) String environment,
      @RequestHeader(value = "X-Source-Actor", required = false) String actor,
      @RequestHeader(value = "X-Source-Purpose", required = false) String purpose,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    RequestContext context =
        authorize(
            runId,
            fileId,
            authorization,
            environment,
            actor,
            purpose,
            correlationId,
            SourceAccessPolicy.Purpose.CONTENT,
            "file");
    return withCorrelation(
        context.correlationId(),
        () -> {
          return ResponseEntity.ok(service.file(runId, fileId, context.actor()));
        });
  }

  @GetMapping("/runs/{runId}/symbols")
  public ResponseEntity<Object> symbols(
      @PathVariable String runId,
      @RequestParam(required = false) String fileId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int limit,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Source-Environment", required = false) String environment,
      @RequestHeader(value = "X-Source-Actor", required = false) String actor,
      @RequestHeader(value = "X-Source-Purpose", required = false) String purpose,
      @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
    RequestContext context =
        authorize(
            runId,
            fileId,
            authorization,
            environment,
            actor,
            purpose,
            correlationId,
            SourceAccessPolicy.Purpose.METADATA,
            "symbols");
    return withCorrelation(
        context.correlationId(),
        () -> {
          return ResponseEntity.ok(service.symbols(runId, fileId, page, limit, context.actor()));
        });
  }

  private RequestContext authorize(
      String runId,
      String fileId,
      String authorization,
      String environment,
      String actor,
      String purpose,
      String correlationId,
      SourceAccessPolicy.Purpose expectedPurpose,
      String operation) {
    try {
      String authorizedActor =
          access.authorize(
              authorization, expectedPurpose, environment, actor, purpose, correlationId);
      return new RequestContext(authorizedActor, correlationId);
    } catch (SourceManifestException exception) {
      audit.record(
          auditActor(actor),
          operation,
          auditRun(runId),
          null,
          null,
          null,
          auditFile(fileId),
          exception.code().name(),
          auditCorrelation(correlationId));
      throw exception;
    }
  }

  @ExceptionHandler(SourceManifestException.class)
  public ResponseEntity<Object> sourceError(SourceManifestException exception) {
    return ResponseEntity.status(exception.status())
        .body(
            Map.of(
                "schema",
                "source-error/v1",
                "code",
                exception.code().name(),
                "message",
                exception.getMessage() == null ? "source request failed" : exception.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> unexpectedSourceError(Exception exception) {
    return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            Map.of(
                "schema", "source-error/v1",
                "code", "SOURCE_UNAVAILABLE",
                "message", "source request is unavailable"));
  }

  private static <T> T withCorrelation(String value, Supplier<T> action) {
    String previous = MDC.get("source_correlation_id");
    MDC.put("source_correlation_id", value);
    try {
      return action.get();
    } finally {
      if (previous == null) MDC.remove("source_correlation_id");
      else MDC.put("source_correlation_id", previous);
    }
  }

  private static String auditActor(String actor) {
    return actor == null || actor.isBlank() ? "-" : actor;
  }

  private static String auditRun(String runId) {
    return runId == null || runId.isBlank() ? null : runId;
  }

  private static String auditFile(String fileId) {
    return fileId == null || fileId.isBlank() ? null : fileId;
  }

  private static String auditCorrelation(String correlationId) {
    return correlationId == null || correlationId.isBlank() ? null : correlationId;
  }

  private record RequestContext(String actor, String correlationId) {}
}

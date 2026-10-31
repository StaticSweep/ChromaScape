package com.chromascape.web.debug.tickdebugger.video.controller;

import com.chromascape.web.debug.tickdebugger.video.MouseVideoException;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoAccess;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoRequestGuard;
import com.chromascape.web.debug.tickdebugger.video.service.MouseVideoReviewSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Additive authenticated protocol routes; no prediction bytes are returned here. */
@RestController
@RequestMapping("/api/debug/mouse-videos")
public class MouseVideoReviewSessionController {
  private final MouseVideoReviewSessionService sessions;
  private final MouseVideoAccess access;
  private final MouseVideoRequestGuard guard;

  public MouseVideoReviewSessionController(
      MouseVideoReviewSessionService sessions,
      MouseVideoAccess access,
      MouseVideoRequestGuard guard) {
    this.sessions = sessions;
    this.access = access;
    this.guard = guard;
  }

  private UUID id(String value) {
    try {
      return UUID.fromString(value);
    } catch (Exception e) {
      throw new MouseVideoException("INVALID_REQUEST", "Invalid review identifier");
    }
  }

  @GetMapping("/sources/{sourceId}/review-policy")
  public Map<String, Object> policy(
      @PathVariable String sourceId, @RequestParam String analysisId, Principal principal) {
    var actor = access.require(principal);
    return sessions.policy(actor.owner(), actor.auditActor(), id(sourceId), id(analysisId));
  }

  @PostMapping("/sources/{sourceId}/review-sessions")
  public ResponseEntity<Map<String, Object>> create(
      @PathVariable String sourceId,
      @RequestBody JsonNode body,
      HttpServletRequest request,
      Principal principal) {
    var actor = access.require(principal);
    guard.requireWrite(request, actor);
    var result = sessions.createResult(actor.owner(), actor.auditActor(), id(sourceId), body);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.body());
  }

  @GetMapping("/review-sessions/{sessionId}")
  public Map<String, Object> get(@PathVariable String sessionId, Principal principal) {
    var actor = access.require(principal);
    return sessions.get(actor.owner(), actor.auditActor(), id(sessionId));
  }

  @GetMapping("/review-sessions/{sessionId}/reviews")
  public Map<String, Object> history(
      @PathVariable String sessionId,
      @RequestParam(required = false) String before,
      @RequestParam(defaultValue = "20") int limit,
      Principal principal) {
    var actor = access.require(principal);
    return sessions.history(
        actor.owner(),
        actor.auditActor(),
        id(sessionId),
        before == null ? null : id(before),
        limit);
  }

  @PostMapping("/review-sessions/{sessionId}/reviews")
  public ResponseEntity<Map<String, Object>> submit(
      @PathVariable String sessionId,
      @RequestBody JsonNode body,
      HttpServletRequest request,
      Principal principal) {
    var actor = access.require(principal);
    guard.requireWrite(request, actor);
    var result = sessions.submitResult(actor.owner(), actor.auditActor(), id(sessionId), body);
    return ResponseEntity.status(result.created() ? 201 : 200).body(result.body());
  }

  @PostMapping("/review-sessions/{sessionId}/seal")
  public Map<String, Object> seal(
      @PathVariable String sessionId,
      @RequestBody JsonNode body,
      HttpServletRequest request,
      Principal principal) {
    var actor = access.require(principal);
    guard.requireWrite(request, actor);
    return sessions.seal(actor.owner(), actor.auditActor(), id(sessionId), body);
  }

  @PostMapping("/review-sessions/{sessionId}/abandon")
  public Map<String, Object> abandon(
      @PathVariable String sessionId,
      @RequestBody JsonNode body,
      HttpServletRequest request,
      Principal principal) {
    var actor = access.require(principal);
    guard.requireWrite(request, actor);
    return sessions.abandon(actor.owner(), actor.auditActor(), id(sessionId), body);
  }

  @GetMapping(value = "/review-sessions/{sessionId}/export", produces = "application/json")
  public ResponseEntity<String> export(@PathVariable String sessionId, Principal principal) {
    var actor = access.require(principal);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .body(sessions.export(actor.owner(), actor.auditActor(), id(sessionId)));
  }

  @ExceptionHandler(MouseVideoException.class)
  public ResponseEntity<Map<String, Object>> rejected(MouseVideoException error) {
    int status =
        switch (error.getCode()) {
          case "NOT_FOUND" -> 404;
          case "UNAUTHENTICATED" -> 401;
          case "FORBIDDEN" -> 403;
          case "CONFLICT" -> 409;
          case "INVALID_REQUEST" -> 400;
          default -> 422;
        };
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                error.getCode(),
                "message",
                error.getMessage(),
                "retryable",
                error.isRetryable()));
  }
}

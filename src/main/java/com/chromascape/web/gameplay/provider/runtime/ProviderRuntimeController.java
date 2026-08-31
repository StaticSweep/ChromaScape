package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.provider.runtime.GameplayProviderRuntime.Submission;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.Readiness;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionView;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Default-off operator API. Responses expose decisions and status, never execution authority. */
@RestController
@RequestMapping("/api/v1/gameplay")
public final class ProviderRuntimeController {
  private static final String OPERATOR_HEADER = "X-Gameplay-Operator-Key";
  private final GameplayProviderRuntime runtime;
  private final ProviderRuntimeProperties properties;

  public ProviderRuntimeController(
      GameplayProviderRuntime runtime, ProviderRuntimeProperties properties) {
    this.runtime = runtime;
    this.properties = properties;
  }

  @GetMapping("/provider-runtime/readiness")
  public Readiness readiness(@RequestHeader(value = OPERATOR_HEADER, required = false) String key) {
    authenticate(key);
    return runtime.readiness();
  }

  @PostMapping("/provider-sessions")
  public ResponseEntity<SessionView> createSession(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String key,
      @RequestBody SessionRequest request) {
    authenticate(key);
    return ResponseEntity.status(HttpStatus.CREATED).body(runtime.createSession(request));
  }

  @PostMapping("/provider-sessions/{sessionId}/turns")
  public ResponseEntity<TurnView> submitTurn(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String key,
      @PathVariable String sessionId,
      @RequestBody TurnRequest request) {
    authenticate(key);
    Submission submission = runtime.submitTurn(sessionId, request);
    return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
        .body(submission.turn());
  }

  @GetMapping("/provider-sessions/{sessionId}")
  public SessionDetails session(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String key,
      @PathVariable String sessionId) {
    authenticate(key);
    return new SessionDetails(runtime.session(sessionId), runtime.turns(sessionId));
  }

  @GetMapping("/provider-sessions/{sessionId}/turns/{turnId}")
  public TurnView turn(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String key,
      @PathVariable String sessionId,
      @PathVariable String turnId) {
    authenticate(key);
    return runtime.turn(sessionId, turnId);
  }

  @PostMapping("/provider-sessions/{sessionId}/resume")
  public ResponseEntity<TurnView> resume(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String key,
      @PathVariable String sessionId,
      @RequestBody TurnControlRequest request) {
    authenticate(key);
    TurnView value = runtime.reconcile(sessionId, request.turnId());
    return ResponseEntity.status(
            value.state() == ProviderRuntimeModels.TurnState.NEEDS_RECONCILIATION
                ? HttpStatus.CONFLICT
                : HttpStatus.OK)
        .body(value);
  }

  @PostMapping("/provider-sessions/{sessionId}/cancel")
  public ResponseEntity<TurnView> cancel(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String key,
      @PathVariable String sessionId,
      @RequestBody TurnControlRequest request) {
    authenticate(key);
    return ResponseEntity.accepted()
        .body(runtime.cancel(sessionId, request.turnId(), request.reason()));
  }

  @ExceptionHandler(ProviderRuntimeException.class)
  public ResponseEntity<ApiError> providerError(ProviderRuntimeException exception) {
    HttpStatus status =
        switch (exception.failureCode()) {
          case "OPERATOR_UNAUTHORIZED" -> HttpStatus.UNAUTHORIZED;
          case "SESSION_NOT_FOUND", "TURN_NOT_FOUND" -> HttpStatus.NOT_FOUND;
          case "IDEMPOTENCY_CONFLICT",
              "TERMINAL_IMMUTABLE",
              "ILLEGAL_STATE_TRANSITION" -> HttpStatus.CONFLICT;
          case "BUDGET_EXHAUSTED", "INVALID_CONTRACT" -> HttpStatus.UNPROCESSABLE_ENTITY;
          case "RUNTIME_DISABLED", "PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                ProviderRuntimeModels.VERSION, exception.failureCode(), exception.getMessage()));
  }

  private void authenticate(String supplied) {
    String expected = properties.getOperatorKey();
    boolean valid =
        expected != null
            && expected.length() >= 32
            && supplied != null
            && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    if (!valid) {
      throw new ProviderRuntimeException(
          "OPERATOR_UNAUTHORIZED", "Valid gameplay provider operator authentication is required");
    }
  }

  public record SessionDetails(SessionView session, List<TurnView> turns) {}

  public record TurnControlRequest(String turnId, String reason) {}

  public record ApiError(String contractVersion, String failureCode, String message) {}
}

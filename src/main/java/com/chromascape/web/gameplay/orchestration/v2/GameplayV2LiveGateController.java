package com.chromascape.web.gameplay.orchestration.v2;

import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2Bundle;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2ReserveResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StartRequest;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StartResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2State;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2DispatchModels.V2StatusResult;
import com.chromascape.web.gameplay.orchestration.v2.GameplayV2OperatorAuthenticator.GameplayV2OperatorException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Authenticated production entry point for the SC-1429/v2 live gameplay boundary. Every operation
 * verifies the operator secret with a constant-time comparison before anything else, and is then
 * gated on {@code gameplay.v2.live-enabled}. Reserve, start, status and stop are separate
 * operations; reserve and start are never combined, and start never resubmits the bundle. The
 * controller records the audit principal and returns stable response codes. Live delivery stays
 * disabled by default until the human gate passes.
 */
@RestController
@RequestMapping("/api/internal/gameplay/v2")
public final class GameplayV2LiveGateController {
  private static final Logger log = LoggerFactory.getLogger(GameplayV2LiveGateController.class);
  private static final String OPERATOR_HEADER = "X-Gameplay-Operator-Key";

  private final GameplayV2LiveGateService service;
  private final GameplayV2OperatorAuthenticator authenticator;
  private final boolean liveEnabled;

  public GameplayV2LiveGateController(
      GameplayV2LiveGateService service,
      GameplayV2OperatorAuthenticator authenticator,
      @Value("${gameplay.v2.live-enabled:false}") boolean liveEnabled) {
    this.service = service;
    this.authenticator = authenticator;
    this.liveEnabled = liveEnabled;
  }

  @PostMapping("/reserve")
  public ResponseEntity<Map<String, Object>> reserve(
      @RequestBody V2Bundle bundle,
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey) {
    String principal = authenticator.authenticate(operatorKey);
    if (!liveEnabled) {
      return disabled();
    }
    if (bundle == null) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(failure("BAD_REQUEST", "bundle is required"));
    }
    V2ReserveResult result = service.reserve(bundle);
    audit(principal, "reserve", result.requestId(), result.state(), result.failureCode());
    return ResponseEntity.status(statusFor(result)).body(payload(result));
  }

  @PostMapping("/start")
  public ResponseEntity<Map<String, Object>> start(
      @RequestBody V2StartRequest request,
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey) {
    String principal = authenticator.authenticate(operatorKey);
    if (!liveEnabled) {
      return disabled();
    }
    if (request == null || !request.hasRequestId()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(failure("BAD_REQUEST", "requestId is required"));
    }
    V2StartResult result =
        service.start(request.requestId(), request.leaseGeneration(), request.fence());
    audit(principal, "start", result.requestId(), result.state(), result.failureCode());
    return ResponseEntity.status(statusFor(result)).body(payload(result));
  }

  @GetMapping("/status/{requestId}")
  public ResponseEntity<Map<String, Object>> status(
      @PathVariable String requestId,
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey) {
    authenticator.authenticate(operatorKey);
    if (!liveEnabled) {
      return disabled();
    }
    if (requestId == null || requestId.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(failure("BAD_REQUEST", "requestId is required"));
    }
    return ResponseEntity.ok(payload(service.status(requestId)));
  }

  @PostMapping("/stop")
  public ResponseEntity<Map<String, Object>> stop(
      @RequestBody V2StopRequest request,
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey) {
    String principal = authenticator.authenticate(operatorKey);
    if (!liveEnabled) {
      return disabled();
    }
    if (request == null || request.requestId() == null || request.requestId().isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(failure("BAD_REQUEST", "requestId is required"));
    }
    V2StatusResult result =
        service.stop(request.requestId(), request.reason(), request.killSwitch());
    audit(principal, "stop", result.requestId(), result.state(), result.failureCode());
    return ResponseEntity.ok(payload(result));
  }

  @PostMapping("/reconcile/{requestId}")
  public ResponseEntity<Map<String, Object>> reconcile(
      @PathVariable String requestId,
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey) {
    String principal = authenticator.authenticate(operatorKey);
    if (!liveEnabled) {
      return disabled();
    }
    V2StatusResult result = service.reconcile(requestId);
    audit(principal, "reconcile", result.requestId(), result.state(), result.failureCode());
    return ResponseEntity.ok(payload(result));
  }

  @ExceptionHandler(GameplayV2OperatorException.class)
  ResponseEntity<Map<String, Object>> operatorError(GameplayV2OperatorException exception) {
    return ResponseEntity.status(exception.status())
        .body(failure(exception.failureCode(), exception.getMessage()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Map<String, Object>> invalidRequest(IllegalArgumentException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(failure("BAD_REQUEST", exception.getMessage()));
  }

  private ResponseEntity<Map<String, Object>> disabled() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(failure("V2_DISABLED", "v2 live gameplay is disabled"));
  }

  private void audit(
      String principal, String operation, String requestId, V2State state, String failureCode) {
    log.info(
        "[ChromaScape] v2 gate operation principal={} operation={} requestId={} state={} "
            + "failureCode={}",
        principal,
        operation,
        requestId,
        state == null ? null : state.name(),
        failureCode);
  }

  private HttpStatus statusFor(V2ReserveResult result) {
    if (result.state() == V2State.RESERVED || result.state() == V2State.DELIVERY_PENDING) {
      return HttpStatus.CREATED;
    }
    return HttpStatus.OK;
  }

  private HttpStatus statusFor(V2StartResult result) {
    if (result.state() == V2State.FAILED) {
      return HttpStatus.CONFLICT;
    }
    return HttpStatus.OK;
  }

  private Map<String, Object> payload(V2ReserveResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("requestId", result.requestId());
    payload.put("envelopeDigest", result.envelopeDigest());
    payload.put("state", result.state() == null ? null : result.state().name());
    payload.put("failureCode", result.failureCode());
    payload.put("evidence", result.evidenceRefs());
    return payload;
  }

  private Map<String, Object> payload(V2StartResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("requestId", result.requestId());
    payload.put("envelopeDigest", result.envelopeDigest());
    payload.put("state", result.state() == null ? null : result.state().name());
    payload.put("failureCode", result.failureCode());
    payload.put("evidence", result.evidenceRefs());
    return payload;
  }

  private Map<String, Object> payload(V2StatusResult result) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("requestId", result.requestId());
    payload.put("envelopeDigest", result.envelopeDigest());
    payload.put("state", result.state() == null ? null : result.state().name());
    payload.put("failureCode", result.failureCode());
    payload.put("terminal", result.state() != null && result.state().provenTerminal());
    payload.put("evidence", result.evidenceRefs());
    return payload;
  }

  private Map<String, Object> failure(String code, String message) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("failureCode", code);
    payload.put("message", message);
    return payload;
  }

  /** Closed stop contract: request id, reason, and optional kill-switch flag. */
  public record V2StopRequest(String requestId, String reason, boolean killSwitch) {
    public V2StopRequest {
      reason = reason == null ? "operator_stop" : reason;
      if (reason.length() > 256) {
        throw new IllegalArgumentException("reason must be 256 characters or fewer");
      }
    }
  }
}

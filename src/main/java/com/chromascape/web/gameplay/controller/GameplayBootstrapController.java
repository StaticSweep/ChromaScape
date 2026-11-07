package com.chromascape.web.gameplay.controller;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService.AuthorityException;
import com.chromascape.web.gameplay.bootstrap.BootstrapApprovalStore.BootstrapApprovalException;
import com.chromascape.web.gameplay.bootstrap.BootstrapProposalAssembler.BootstrapProposalException;
import com.chromascape.web.gameplay.bootstrap.BootstrapRecordingCoordinator.BootstrapRecordingException;
import com.chromascape.web.gameplay.contract.GameplayContractValidator.GameplayContractViolation;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.ApprovalRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.DispatchRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.KillRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.PreviewRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.StopRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationException;
import java.util.Map;
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
 * Durable human-gated operator facade for the one-use SC-1498 bootstrap recording.
 *
 * <p>Legacy v1 {@code /preview}, {@code /approve}, {@code /dispatch} and {@code /stop} shapes
 * accepting raw provider output are retired. The durable route selects a persisted SC-1430 decision
 * by session/turn identity, persists the human approval, binds it once, delivers once through
 * SC-1431 and the Micro6 v2 gate, and finalizes the recording atomically.
 */
@RestController
@RequestMapping("/api/v1/gameplay/bootstrap")
public final class GameplayBootstrapController {
  static final String OPERATOR_KEY_HEADER = "X-Gameplay-Operator-Key";

  private final BootstrapGameplayOperatorService operatorService;

  public GameplayBootstrapController(BootstrapGameplayOperatorService operatorService) {
    this.operatorService = operatorService;
  }

  @GetMapping("/availability")
  public Object availability(
      @RequestHeader(value = OPERATOR_KEY_HEADER, required = false) String operatorKey) {
    // An absent header gets the minimal public response. A present key must authenticate against
    // the real configured operator key; a wrong nonblank key is rejected with 401 and no detail.
    if (operatorKey == null || operatorKey.isBlank()) {
      return operatorService.publicAvailability();
    }
    return operatorService.availability(operatorKey);
  }

  @PostMapping("/previews")
  public Object previews(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey, @RequestBody PreviewRequest request) {
    return operatorService.preview(operatorKey, request);
  }

  @PostMapping("/approvals")
  public Object approvals(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @RequestBody ApprovalRequest request) {
    return operatorService.approve(operatorKey, request);
  }

  @PostMapping("/approvals/{approvalId}/dispatch")
  public Object dispatch(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @PathVariable String approvalId,
      @RequestBody DispatchRequest request) {
    DispatchRequest bound =
        new DispatchRequest(
            approvalId,
            request.observation(),
            request.executionEnvelope(),
            request.snapshotBindings());
    return operatorService.dispatch(operatorKey, bound);
  }

  @GetMapping("/runs/{orchestrationId}")
  public Object run(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @PathVariable String orchestrationId) {
    return operatorService.run(operatorKey, orchestrationId);
  }

  @PostMapping("/runs/{orchestrationId}/stop")
  public Object stop(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @PathVariable String orchestrationId,
      @RequestBody Map<String, String> body) {
    String reason = body == null ? "operator stop" : body.getOrDefault("reason", "operator stop");
    return operatorService.stop(operatorKey, new StopRequest(orchestrationId, reason));
  }

  @PostMapping("/runs/{orchestrationId}/kill")
  public Object kill(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @PathVariable String orchestrationId,
      @RequestBody Map<String, Object> body) {
    String reason =
        body == null || body.get("reason") == null
            ? "kill switch"
            : String.valueOf(body.get("reason"));
    boolean confirmed = body != null && Boolean.TRUE.equals(body.get("confirmed"));
    return operatorService.kill(operatorKey, new KillRequest(orchestrationId, reason, confirmed));
  }

  @PostMapping("/runs/{orchestrationId}/reconcile")
  public Object reconcile(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @PathVariable String orchestrationId) {
    return operatorService.reconcile(operatorKey, orchestrationId);
  }

  @ExceptionHandler(OperatorException.class)
  ResponseEntity<Object> operatorError(OperatorException exception) {
    return error(exception.status(), exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(AuthorityException.class)
  ResponseEntity<Object> authorityError(AuthorityException exception) {
    return error(HttpStatus.CONFLICT, exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(BootstrapApprovalException.class)
  ResponseEntity<Object> approvalError(BootstrapApprovalException exception) {
    return error(
        mapApprovalCode(exception.failureCode()), exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(BootstrapProposalException.class)
  ResponseEntity<Object> proposalError(BootstrapProposalException exception) {
    return error(
        mapProposalCode(exception.failureCode()), exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(BootstrapRecordingException.class)
  ResponseEntity<Object> recordingError(BootstrapRecordingException exception) {
    return error(HttpStatus.CONFLICT, exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(AccountBuilderOrchestrationException.class)
  ResponseEntity<Object> orchestrationError(AccountBuilderOrchestrationException exception) {
    return error(
        switch (exception.failureCode()) {
          case IDEMPOTENCY_CONFLICT, DELIVERY_UNCERTAIN, NEEDS_RECONCILIATION -> HttpStatus
              .CONFLICT;
          case INVALID_CONTRACT -> HttpStatus.BAD_REQUEST;
          case RUNTIME_DISABLED, GATEWAY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
          default -> HttpStatus.UNPROCESSABLE_ENTITY;
        },
        exception.failureCode().name(),
        exception.getMessage());
  }

  @ExceptionHandler(GameplayContractViolation.class)
  ResponseEntity<Object> contractError(GameplayContractViolation exception) {
    return error(HttpStatus.BAD_REQUEST, exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Object> invalidRequest(IllegalArgumentException exception) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
  }

  private HttpStatus mapApprovalCode(String code) {
    return switch (code) {
      case "APPROVAL_MISSING_OR_USED", "APPROVAL_CONFLICT", "AUTHORITY_EXPIRED" -> HttpStatus
          .CONFLICT;
      case "AUTHORITY_EXPIRY_INVALID", "APPROVER_INVALID", "INVALID_REQUEST" -> HttpStatus
          .BAD_REQUEST;
      default -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
  }

  private HttpStatus mapProposalCode(String code) {
    return switch (code) {
      case "PROPOSAL_INCOMPLETE" -> HttpStatus.BAD_REQUEST;
      case "PERSISTED_DECISION_MISSING" -> HttpStatus.NOT_FOUND;
      case "STALE_BINDING",
          "REVISION_MISMATCH",
          "IDENTITY_MISMATCH",
          "DECISION_IDENTITY_MISMATCH",
          "ACTION_ENVELOPE_MISMATCH",
          "FENCE_MISMATCH" -> HttpStatus.UNPROCESSABLE_ENTITY;
      case "TASK_INELIGIBLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
      default -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
  }

  private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "message", message == null ? code : message));
  }
}

package com.chromascape.web.gameplay.controller;

import com.chromascape.web.gameplay.bootstrap.BootstrapV2DraftService;
import com.chromascape.web.gameplay.bootstrap.BootstrapV2ProviderTurnService;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.BootstrapV2OperatorService;
import com.chromascape.web.gameplay.orchestration.BootstrapV2OperatorService.ApprovalRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationException;
import java.time.Instant;
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
 * Operator facade for the frozen SC-1429/v2 bootstrap delivery.
 *
 * <p>Preview freezes and persists the draft and returns the scope digest; approve records the
 * owner's typed confirmation of that digest; dispatch delivers exactly those bytes once. Stop, kill
 * and reconcile stay on the SC-1431 routes the v1 surface already exposes.
 */
@RestController
@RequestMapping("/api/v1/gameplay/bootstrap/v2")
public final class GameplayBootstrapV2Controller {
  static final String OPERATOR_KEY_HEADER = "X-Gameplay-Operator-Key";

  private final BootstrapV2OperatorService operatorService;

  public GameplayBootstrapV2Controller(BootstrapV2OperatorService operatorService) {
    this.operatorService = operatorService;
  }

  @PostMapping("/drafts")
  public Object drafts(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @RequestBody BootstrapV2DraftService.DraftRequest request) {
    return operatorService.preview(operatorKey, request);
  }

  @PostMapping("/turns")
  public Object turns(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @RequestBody BootstrapV2ProviderTurnService.TurnPreparation request) {
    return operatorService.prepareTurn(operatorKey, request);
  }

  @PostMapping("/approvals")
  public Object approvals(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @RequestBody ApprovalRequest request) {
    return operatorService.approve(operatorKey, request);
  }

  @PostMapping("/approvals/{approvalId}/dispatch")
  public Object dispatch(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey, @PathVariable String approvalId) {
    return operatorService.dispatch(operatorKey, approvalId);
  }

  @GetMapping("/runs/{orchestrationId}")
  public Object run(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @PathVariable String orchestrationId) {
    return operatorService.run(operatorKey, orchestrationId);
  }

  @PostMapping("/runs/{orchestrationId}/await")
  public Object await(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @PathVariable String orchestrationId,
      @RequestBody(required = false) Map<String, Object> body) {
    return operatorService.awaitTerminal(operatorKey, orchestrationId, deadline(body));
  }

  private static Instant deadline(Map<String, Object> body) {
    Object value = body == null ? null : body.get("deadline");
    if (value == null) {
      throw new OperatorException(
          HttpStatus.BAD_REQUEST,
          "INVALID_REQUEST",
          "A bounded wait requires an explicit deadline");
    }
    try {
      return Instant.parse(String.valueOf(value));
    } catch (RuntimeException exception) {
      throw new OperatorException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The deadline must be an ISO-8601 instant");
    }
  }

  @ExceptionHandler(OperatorException.class)
  ResponseEntity<Object> operatorError(OperatorException exception) {
    return error(exception.status(), exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(AccountBuilderOrchestrationException.class)
  ResponseEntity<Object> orchestrationError(AccountBuilderOrchestrationException exception) {
    return error(
        HttpStatus.UNPROCESSABLE_ENTITY, exception.failureCode().name(), exception.getMessage());
  }

  private static ResponseEntity<Object> error(
      HttpStatus status, String failureCode, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "success",
                false,
                "failureCode",
                failureCode == null ? "INTERNAL_FAILURE" : failureCode,
                "message",
                message == null ? "Request was refused" : message));
  }
}

package com.chromascape.web.gameplay.controller;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService.AuthorityException;
import com.chromascape.web.gameplay.contract.GameplayContractValidator.GameplayContractViolation;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.ApprovalRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.DispatchRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.ProposalRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.StopRequest;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOrchestrator.GameplayProposalException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit human gate for the one-use SC-1498 bootstrap recording. */
@RestController
@RequestMapping("/api/v1/gameplay/bootstrap")
public final class GameplayBootstrapController {
  static final String OPERATOR_KEY_HEADER = "X-Gameplay-Operator-Key";

  private final BootstrapGameplayOperatorService operatorService;

  public GameplayBootstrapController(BootstrapGameplayOperatorService operatorService) {
    this.operatorService = operatorService;
  }

  @GetMapping("/availability")
  public Object availability() {
    return operatorService.availability();
  }

  @PostMapping("/preview")
  public Object preview(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @RequestBody ProposalRequest request) {
    return operatorService.preview(operatorKey, request);
  }

  @PostMapping("/approve")
  public Object approve(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @RequestBody ApprovalRequest request) {
    return operatorService.approve(operatorKey, request);
  }

  @PostMapping("/dispatch")
  public Object dispatch(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey,
      @RequestBody DispatchRequest request) {
    return operatorService.dispatch(operatorKey, request);
  }

  @PostMapping("/stop")
  public Object stop(
      @RequestHeader(OPERATOR_KEY_HEADER) String operatorKey, @RequestBody StopRequest request) {
    return operatorService.stop(operatorKey, request);
  }

  @ExceptionHandler(OperatorException.class)
  ResponseEntity<Object> operatorError(OperatorException exception) {
    return error(exception.status(), exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(AuthorityException.class)
  ResponseEntity<Object> authorityError(AuthorityException exception) {
    return error(HttpStatus.CONFLICT, exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(GameplayProposalException.class)
  ResponseEntity<Object> proposalError(GameplayProposalException exception) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(GameplayContractViolation.class)
  ResponseEntity<Object> contractError(GameplayContractViolation exception) {
    return error(HttpStatus.BAD_REQUEST, exception.failureCode(), exception.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<Object> invalidRequest(IllegalArgumentException exception) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
  }

  private ResponseEntity<Object> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("code", code, "message", message == null ? code : message));
  }
}

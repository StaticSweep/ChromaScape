package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Readiness;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/** Default-off protected SC-1431 status and orchestration API. */
@RestController
@RequestMapping("/api/v1/gameplay/accountbuilder")
public final class AccountBuilderOrchestrationController {
  private static final String OPERATOR_HEADER = "X-Gameplay-Operator-Key";
  private final AccountBuilderOrchestrationService service;
  private final AccountBuilderOrchestrationProperties properties;

  public AccountBuilderOrchestrationController(
      AccountBuilderOrchestrationService service,
      AccountBuilderOrchestrationProperties properties) {
    this.service = service;
    this.properties = properties;
  }

  @GetMapping("/readiness")
  public Readiness readiness(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey) {
    authenticate(operatorKey);
    return service.readiness();
  }

  @PostMapping("/orchestrations")
  public ResponseEntity<OrchestrationView> orchestrate(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey,
      @RequestBody OrchestrationRequest request) {
    authenticate(operatorKey);
    AccountBuilderOrchestrationService.Submission submission = service.orchestrate(request);
    return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(submission.view());
  }

  @GetMapping("/orchestrations/{orchestrationId}")
  public OrchestrationView find(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey,
      @PathVariable String orchestrationId) {
    authenticate(operatorKey);
    return service.find(orchestrationId);
  }

  @PostMapping("/orchestrations/{orchestrationId}/stop")
  public ResponseEntity<OrchestrationView> stop(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey,
      @PathVariable String orchestrationId,
      @RequestBody ControlRequest request) {
    authenticate(operatorKey);
    return ResponseEntity.accepted()
        .body(service.stop(orchestrationId, request.reason(), request.killSwitch()));
  }

  @PostMapping("/orchestrations/{orchestrationId}/reconcile")
  public ResponseEntity<OrchestrationView> reconcile(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey,
      @PathVariable String orchestrationId) {
    authenticate(operatorKey);
    OrchestrationView view = service.reconcile(orchestrationId);
    return ResponseEntity.status(
            view.state() == AccountBuilderOrchestrationModels.State.NEEDS_RECONCILIATION
                ? HttpStatus.CONFLICT
                : HttpStatus.OK)
        .body(view);
  }

  @ExceptionHandler(AccountBuilderOrchestrationException.class)
  public ResponseEntity<ApiError> orchestrationError(
      AccountBuilderOrchestrationException exception) {
    HttpStatus status =
        switch (exception.failureCode()) {
          case OPERATOR_UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
          case IDEMPOTENCY_CONFLICT -> HttpStatus.CONFLICT;
          case RUNTIME_DISABLED, GATEWAY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
          case NEEDS_RECONCILIATION, DELIVERY_UNCERTAIN -> HttpStatus.CONFLICT;
          case INVALID_CONTRACT -> HttpStatus.BAD_REQUEST;
          default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    return ResponseEntity.status(status)
        .body(
            new ApiError(
                AccountBuilderOrchestrationModels.VERSION,
                exception.failureCode(),
                exception.getMessage()));
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
      throw new AccountBuilderOrchestrationException(
          FailureCode.OPERATOR_UNAUTHORIZED,
          "Valid AccountBuilder orchestration operator authentication is required");
    }
  }

  public record ControlRequest(String reason, boolean killSwitch) {}

  public record ApiError(String contractVersion, FailureCode failureCode, String message) {}
}

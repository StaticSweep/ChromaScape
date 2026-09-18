package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Readiness;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
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
  private final ProviderDecisionOrchestrationSource providerSource;
  private final AccountBuilderCapabilityPreflight capabilityPreflight;

  public AccountBuilderOrchestrationController(
      AccountBuilderOrchestrationService service,
      AccountBuilderOrchestrationProperties properties,
      ProviderDecisionOrchestrationSource providerSource,
      AccountBuilderCapabilityPreflight capabilityPreflight) {
    this.service = service;
    this.properties = properties;
    this.providerSource = providerSource;
    this.capabilityPreflight = capabilityPreflight;
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
    // The generic endpoint can never carry a provider source: provider orchestrations must be
    // created through the persisted provider decision handoff, so a caller can never submit its
    // own decision or provenance by naming a provider source type. The check is ASCII
    // case-independent, so provider:CODEX and mixed-case variants are rejected here too.
    if (request != null && AccountBuilderOrchestrationSources.providerLike(request.sourceType())) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT,
          "Provider source orchestrations must be created through the persisted provider"
              + " decision handoff");
    }
    AccountBuilderOrchestrationService.Submission submission = service.orchestrate(request);
    return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(submission.view());
  }

  /**
   * Production provider-orchestration route. The provider decision, its provenance and the source
   * type are loaded from the durable persisted provider turn; the command may only carry provider
   * session and turn identity plus request identity, envelope, authority, observation and deadline.
   *
   * <p>Ordering is part of the trust boundary: authenticate, prepare the persisted decision,
   * resolve any existing exact replay with the stored capability digest, and only then perform the
   * capability preflight for a genuinely new orchestration. Invalid decisions and exact replays
   * therefore never touch Micro6.
   */
  @PostMapping("/provider-orchestrations")
  public ResponseEntity<OrchestrationView> orchestrateProvider(
      @RequestHeader(value = OPERATOR_HEADER, required = false) String operatorKey,
      @RequestBody ProviderOrchestrationCommand command) {
    authenticate(operatorKey);
    ProviderDecisionOrchestrationSource.PreparedProviderRequest prepared =
        providerSource.prepareProduction(command);
    Optional<OrchestrationView> existing = service.findOrchestration(command.orchestrationId());
    if (existing.isPresent()) {
      String storedCapabilityDigest = service.storedCapabilityDigest(command.orchestrationId());
      OrchestrationRequest replay =
          providerSource.completeProductionReplay(prepared, storedCapabilityDigest);
      if (!replay.requestFingerprint().equals(existing.get().requestFingerprint())) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.IDEMPOTENCY_CONFLICT,
            "A different request is already persisted under this orchestration id");
      }
      return ResponseEntity.ok(existing.get());
    }
    var capabilityPreflightSnapshot = capabilityPreflight.probe(command.envelope());
    OrchestrationRequest request =
        providerSource.completeProduction(prepared, capabilityPreflightSnapshot);
    AccountBuilderOrchestrationService.Submission submission = service.orchestrateProvider(request);
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

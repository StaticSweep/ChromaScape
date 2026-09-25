package com.chromascape.web.gameplay.orchestration;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService.PendingApproval;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.contract.GameplayContracts.Receipt;
import com.chromascape.web.gameplay.provider.GameplayProviderAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Local, fail-closed operator boundary for the one-use SC-1498 bootstrap run. */
@Service
public final class BootstrapGameplayOperatorService {
  private static final int MAX_PENDING_APPROVALS = 32;

  private final CanonicalGameplayJson canonicalJson;
  private final HumanGameplayAuthorityService authorityService;
  private final BootstrapGameplayOrchestrator orchestrator;
  private final Map<Provider, GameplayProviderAdapter> adapters;
  private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
  private final Clock clock;
  private final boolean enabled;
  private final byte[] operatorKey;

  @Autowired
  public BootstrapGameplayOperatorService(
      CanonicalGameplayJson canonicalJson,
      HumanGameplayAuthorityService authorityService,
      BootstrapGameplayOrchestrator orchestrator,
      List<GameplayProviderAdapter> adapters,
      @Value("${gameplay.bootstrap.operator-enabled:false}") boolean enabled,
      @Value("${gameplay.bootstrap.operator-key:}") String operatorKey) {
    this(
        canonicalJson,
        authorityService,
        orchestrator,
        adapters,
        Clock.systemUTC(),
        enabled,
        operatorKey);
  }

  BootstrapGameplayOperatorService(
      CanonicalGameplayJson canonicalJson,
      HumanGameplayAuthorityService authorityService,
      BootstrapGameplayOrchestrator orchestrator,
      List<GameplayProviderAdapter> adapters,
      Clock clock,
      boolean enabled,
      String operatorKey) {
    this.canonicalJson = canonicalJson;
    this.authorityService = authorityService;
    this.orchestrator = orchestrator;
    this.clock = clock;
    this.enabled = enabled;
    this.operatorKey =
        operatorKey == null ? new byte[0] : operatorKey.getBytes(StandardCharsets.UTF_8);
    Map<Provider, GameplayProviderAdapter> indexed = new EnumMap<>(Provider.class);
    for (GameplayProviderAdapter adapter : adapters) {
      if (indexed.put(adapter.provider(), adapter) != null) {
        throw new IllegalStateException("Duplicate gameplay adapter for " + adapter.provider());
      }
    }
    this.adapters = Map.copyOf(indexed);
  }

  public Availability availability() {
    return new Availability(
        enabled,
        operatorKey.length >= 32,
        adapters.keySet().stream().map(Enum::name).sorted().toList(),
        pendingApprovals.size());
  }

  public ApprovalPreview preview(String presentedKey, ProposalRequest request) {
    requireOperator(presentedKey);
    ProviderDecision decision = validatedDecision(request);
    orchestrator.validateProposal(request.observation(), decision, request.envelope());
    Map<String, Object> exactScope = authorityService.exactScope(request.envelope());
    return new ApprovalPreview(exactScope, canonicalJson.digest(exactScope));
  }

  public PendingApprovalView approve(String presentedKey, ApprovalRequest request) {
    requireOperator(presentedKey);
    if (request == null || request.proposal() == null) {
      throw error(HttpStatus.BAD_REQUEST, "APPROVAL_INCOMPLETE", "A complete approval is required");
    }
    if (!request.humanConfirmed()) {
      throw error(
          HttpStatus.PRECONDITION_REQUIRED,
          "HUMAN_CONFIRMATION_REQUIRED",
          "The exact displayed scope requires explicit human confirmation");
    }
    ProviderDecision decision = validatedDecision(request.proposal());
    orchestrator.validateProposal(
        request.proposal().observation(), decision, request.proposal().envelope());
    Map<String, Object> exactScope = authorityService.exactScope(request.proposal().envelope());
    String scopeDigest = canonicalJson.digest(exactScope);
    if (!constantTimeEquals(scopeDigest, request.approvedScopeDigest())) {
      throw error(
          HttpStatus.CONFLICT,
          "APPROVED_SCOPE_MISMATCH",
          "Approved digest does not match the current exact scope");
    }
    pruneExpired();
    if (pendingApprovals.size() >= MAX_PENDING_APPROVALS) {
      throw error(
          HttpStatus.TOO_MANY_REQUESTS,
          "TOO_MANY_PENDING_APPROVALS",
          "Pending approval capacity is exhausted");
    }
    PendingApproval pending =
        authorityService.approveExactScope(request.approvedBy(), exactScope, request.expiresAt());
    pendingApprovals.put(pending.authorityId(), pending);
    return new PendingApprovalView(
        pending.authorityId(), pending.approvedBy(), pending.expiresAt(), pending.scopeDigest());
  }

  public Receipt dispatch(String presentedKey, DispatchRequest request) {
    requireOperator(presentedKey);
    if (request == null
        || request.approvalId() == null
        || request.approvalId().isBlank()
        || request.proposal() == null) {
      throw error(HttpStatus.BAD_REQUEST, "DISPATCH_INCOMPLETE", "A complete dispatch is required");
    }
    PendingApproval pending = pendingApprovals.remove(request.approvalId());
    if (pending == null) {
      throw error(
          HttpStatus.CONFLICT,
          "APPROVAL_MISSING_OR_USED",
          "Pending approval is absent, expired, restarted, or already used");
    }
    try {
      ProviderDecision decision = validatedDecision(request.proposal());
      orchestrator.validateProposal(
          request.proposal().observation(), decision, request.proposal().envelope());
      if (!pending.authorityId().equals(request.proposal().envelope().authorityId())) {
        throw error(
            HttpStatus.CONFLICT,
            "AUTHORITY_ID_MISMATCH",
            "Envelope authority id differs from the approved authority");
      }
      var authority = authorityService.bindEnvelope(pending, request.proposal().envelope());
      return orchestrator.dispatch(
          request.proposal().observation(), decision, request.proposal().envelope(), authority);
    } catch (RuntimeException exception) {
      authorityService.cancel(pending);
      throw exception;
    }
  }

  public AccountBuilderGameplayGateway.DispatchResult stop(
      String presentedKey, StopRequest request) {
    requireOperator(presentedKey);
    if (request == null) {
      throw error(HttpStatus.BAD_REQUEST, "STOP_INCOMPLETE", "A stop request is required");
    }
    return orchestrator.requestStop(request.requestId(), request.reason(), request.killSwitch());
  }

  private ProviderDecision validatedDecision(ProposalRequest request) {
    if (request == null
        || request.observation() == null
        || request.providerOutput() == null
        || request.envelope() == null
        || request.modelVersion() == null
        || request.modelVersion().isBlank()) {
      throw error(HttpStatus.BAD_REQUEST, "PROPOSAL_INCOMPLETE", "A complete proposal is required");
    }
    GameplayProviderAdapter adapter = adapters.get(request.envelope().provider());
    if (adapter == null) {
      throw error(
          HttpStatus.BAD_REQUEST,
          "PROVIDER_UNAVAILABLE",
          "No adapter is configured for the selected provider");
    }
    return adapter.adapt(request.modelVersion(), request.providerOutput());
  }

  private void requireOperator(String presentedKey) {
    if (!enabled) {
      throw error(
          HttpStatus.SERVICE_UNAVAILABLE,
          "BOOTSTRAP_OPERATOR_DISABLED",
          "SC-1498 operator endpoints are disabled");
    }
    byte[] presented =
        presentedKey == null ? new byte[0] : presentedKey.getBytes(StandardCharsets.UTF_8);
    if (operatorKey.length < 32 || !MessageDigest.isEqual(operatorKey, presented)) {
      throw error(
          HttpStatus.UNAUTHORIZED,
          "OPERATOR_AUTHENTICATION_FAILED",
          "A valid SC-1498 operator key is required");
    }
  }

  private void pruneExpired() {
    Instant now = clock.instant();
    pendingApprovals.forEach(
        (authorityId, approval) -> {
          if (!approval.expiresAt().isAfter(now)
              && pendingApprovals.remove(authorityId, approval)) {
            authorityService.cancel(approval);
          }
        });
  }

  private boolean constantTimeEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private OperatorException error(HttpStatus status, String failureCode, String message) {
    return new OperatorException(status, failureCode, message);
  }

  public record ProposalRequest(
      JsonNode observation,
      String modelVersion,
      JsonNode providerOutput,
      ExecutionEnvelope envelope) {}

  public record ApprovalRequest(
      ProposalRequest proposal,
      String approvedBy,
      Instant expiresAt,
      String approvedScopeDigest,
      boolean humanConfirmed) {}

  public record DispatchRequest(String approvalId, ProposalRequest proposal) {}

  public record StopRequest(String requestId, String reason, boolean killSwitch) {}

  public record ApprovalPreview(Map<String, Object> exactScope, String scopeDigest) {}

  public record PendingApprovalView(
      String authorityId, String approvedBy, Instant expiresAt, String scopeDigest) {}

  public record Availability(
      boolean enabled,
      boolean operatorKeyConfigured,
      List<String> providers,
      int pendingApprovals) {}

  public static final class OperatorException extends IllegalStateException {
    private final HttpStatus status;
    private final String failureCode;

    public OperatorException(HttpStatus status, String failureCode, String message) {
      super(message);
      this.status = status;
      this.failureCode = failureCode;
    }

    public HttpStatus status() {
      return status;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}

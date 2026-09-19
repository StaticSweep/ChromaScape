package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.CapabilitySnapshot;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.TaskCapability;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fail-closed validation of every decision, snapshot, revision, lease and authority binding. */
@Component
public final class AccountBuilderOrchestrationValidator {
  private static final String ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
  private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";
  private static final String SIGNATURE_PATTERN = "hmac-sha256:[0-9a-f]{64}";
  private final CanonicalGameplayJson canonicalJson;
  private final Clock clock;
  private final AccountBuilderAuthorityVerifier authorityVerifier;

  @Autowired
  public AccountBuilderOrchestrationValidator(
      CanonicalGameplayJson canonicalJson, AccountBuilderAuthorityVerifier authorityVerifier) {
    this(canonicalJson, Clock.systemUTC(), authorityVerifier);
  }

  AccountBuilderOrchestrationValidator(
      CanonicalGameplayJson canonicalJson,
      Clock clock,
      AccountBuilderAuthorityVerifier authorityVerifier) {
    this.canonicalJson = canonicalJson;
    this.clock = clock;
    this.authorityVerifier = java.util.Objects.requireNonNull(authorityVerifier);
  }

  public String validateAndFingerprint(OrchestrationRequest request) {
    validateImmutable(request, true);
    String fingerprint = immutableFingerprint(request);
    if (!fingerprint.equals(request.requestFingerprint())) {
      throw invalid("Request fingerprint does not match immutable orchestration inputs");
    }
    return fingerprint;
  }

  /**
   * Validates every immutable request invariant except the capability digest and the final
   * fingerprint. Production provider preparation runs this before any capability preflight, so a
   * malformed request can never cause a Micro6 read. The capability binding is intentionally absent
   * at this stage; the full {@link #validateAndFingerprint} runs again after the server-issued
   * capability digest is bound.
   */
  public void validatePrepared(OrchestrationRequest request) {
    validateImmutable(request, false);
    // Authority is a non-capability invariant. Validate it before any preflight so a malformed or
    // unsigned command can never cause a Micro6 read. The service re-validates it after the
    // capability preflight as part of the normal delivery path.
    validateAuthority(request, canonicalJson.digest(request.envelope()), request.authority());
  }

  private void validateImmutable(OrchestrationRequest request, boolean requireCapability) {
    if (request == null
        || !validId(request.orchestrationId())
        || !validId(request.requestId())
        || !validId(request.idempotencyKey())
        || request.mode() == null
        || request.sourceType() == null
        || request.decision() == null
        || request.envelope() == null
        || request.bindings() == null
        || request.observation() == null
        || request.deadline() == null) {
      throw invalid("Required immutable orchestration fields are missing");
    }
    if (!request.deadline().isAfter(clock.instant())
        || !Objects.equals(request.envelope().deadline(), request.deadline())) {
      throw failure(FailureCode.DEADLINE_EXPIRED, "Request and envelope deadline must be current");
    }
    if (!Objects.equals(request.requestId(), request.envelope().requestId())
        || !Objects.equals(request.idempotencyKey(), request.envelope().idempotencyKey())) {
      throw invalid("Request identity does not match the execution envelope");
    }
    validateDecision(request.decision(), request.envelope(), request.bindings(), requireCapability);
    validateEnvelope(request.envelope(), request.bindings(), request.observation());
    // Closed source vocabulary: only an exact approved non-provider token, or the exact
    // PROVIDER:<PROVIDER_ENUM_NAME> token, is accepted. This rejects provider:CODEX, mixed-case
    // variants, unknown provider names, whitespace padding and arbitrary strings.
    AccountBuilderOrchestrationSources.Kind sourceKind =
        AccountBuilderOrchestrationSources.classify(request.sourceType());
    SnapshotBindings bindings = request.bindings();
    if (sourceKind == AccountBuilderOrchestrationSources.Kind.PROVIDER) {
      // A bindable provider decision carries its session prompt and provider session/turn
      // provenance; without them the planning input can never be re-verified or made distinct from
      // another provider turn with identical decision content.
      if (!validDigest(bindings.promptDigest())) {
        throw failure(
            FailureCode.STALE_BINDING, "Provider provenance is missing the session prompt digest");
      }
      if (!validId(bindings.providerSessionId()) || !validId(bindings.providerTurnId())) {
        throw failure(
            FailureCode.STALE_BINDING,
            "Provider provenance is missing the session or turn identity");
      }
    } else if (bindings.providerSessionId() != null || bindings.providerTurnId() != null) {
      throw failure(
          FailureCode.STALE_BINDING,
          "Non-provider provenance cannot carry provider session or turn identity");
    }
    if (request.mode().deliveryMode()
        && (request.envelope().leaseGeneration() < 1 || request.envelope().fence() < 1)) {
      throw failure(
          FailureCode.FENCE_MISMATCH, "Live delivery requires positive lease generation and fence");
    }
  }

  public void validateCapabilities(OrchestrationRequest request, CapabilitySnapshot snapshot) {
    SnapshotBindings bindings = request.bindings();
    if (snapshot == null
        || snapshot.expiresAt() == null
        || !snapshot.expiresAt().isAfter(clock.instant())
        || !Objects.equals(bindings.catalogDigest(), snapshot.catalogDigest())
        || !Objects.equals(bindings.capabilityDigest(), snapshot.capabilityDigest())
        || !Objects.equals(bindings.buildId(), snapshot.buildId())
        || bindings.expectedObservationRevision() != snapshot.observationRevision()
        || bindings.expectedTaskRevision() != snapshot.taskRevision()) {
      throw failure(FailureCode.STALE_BINDING, "Capability, catalog, build or revision changed");
    }
    TaskCapability task = snapshot.task(request.envelope().taskKey());
    if (task == null
        || !task.eligible()
        || !Objects.equals(task.schemaDigest(), request.envelope().schemaDigest())) {
      throw failure(FailureCode.TASK_INELIGIBLE, "AccountBuilder task is not currently eligible");
    }
  }

  public void validateAuthority(
      OrchestrationRequest request, String envelopeDigest, HumanAuthority authority) {
    ExecutionEnvelope envelope = request.envelope();
    if (authority == null
        || !GameplayContracts.VERSION.equals(authority.contractVersion())
        || !authority.oneUse()
        || !"HUMAN".equals(authority.actorType())
        || !validId(authority.authorityId())
        || authority.approvedBy() == null
        || !authority.approvedBy().matches(ID_PATTERN)
        || !Objects.equals(authority.authorityId(), envelope.authorityId())
        || !Objects.equals(authority.envelopeDigest(), envelopeDigest)
        || !validDigest(authority.envelopeDigest())
        || !validDigest(authority.scopeDigest())
        || authority.approvedAt() == null
        || authority.expiresAt() == null
        || authority.approvedAt().isAfter(clock.instant())
        || !authority.expiresAt().isAfter(authority.approvedAt())
        || authority.signature() == null
        || !authority.signature().matches(SIGNATURE_PATTERN)
        || !authority.expiresAt().isAfter(clock.instant())) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Exact unexpired one-use authority is required");
    }
    if (!authorityVerifier.verifyBoundAuthority(authority, envelope)) {
      throw failure(FailureCode.AUTHORITY_INVALID, "Human authority signature or scope is invalid");
    }
  }

  public String immutableFingerprint(OrchestrationRequest request) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("contractVersion", AccountBuilderOrchestrationModels.VERSION);
    values.put("orchestrationId", request.orchestrationId());
    values.put("requestId", request.requestId());
    values.put("idempotencyKey", request.idempotencyKey());
    values.put("mode", request.mode());
    values.put("sourceType", request.sourceType());
    values.put("decision", request.decision());
    values.put("envelope", request.envelope());
    values.put("bindings", request.bindings());
    values.put("authority", request.authority());
    values.put("observation", request.observation());
    values.put("deadline", request.deadline());
    return canonicalJson.digest(values);
  }

  private void validateDecision(
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings,
      boolean requireCapability) {
    if (!validSnapshotBindings(bindings, requireCapability)
        || decision.semanticAction() == null
        || decision.provider() == null
        || !Double.isFinite(decision.policyConfidence())
        || decision.policyConfidence() < 0
        || decision.policyConfidence() > 1
        || !GameplayContracts.VERSION.equals(decision.contractVersion())
        || decision.provider() != envelope.provider()
        || !Objects.equals(decision.modelVersion(), envelope.modelVersion())
        || !Objects.equals(decision.decisionId(), envelope.decisionId())
        || !Objects.equals(bindings.decisionDigest(), canonicalJson.digest(decision))
        || !Objects.equals(
            bindings.semanticActionDigest(), canonicalJson.digest(decision.semanticAction()))
        || !Objects.equals(bindings.semanticActionDigest(), envelope.semanticActionDigest())
        || !Objects.equals(
            envelope.taskKey(), decision.semanticAction().path("taskKey").asText(null))
        || !Objects.equals(envelope.settings(), decision.semanticAction().path("settings"))
        || !Objects.equals(
            envelope.expectedTransition(),
            decision.semanticAction().path("expectedTransition").asText(null))
        || !Objects.equals(
            envelope.verifierId(), decision.semanticAction().path("verifierId").asText(null))) {
      throw failure(
          FailureCode.STALE_BINDING, "Decision identity or digest does not match envelope");
    }
  }

  private void validateEnvelope(
      ExecutionEnvelope envelope, SnapshotBindings bindings, JsonNode observation) {
    if (!validEnvelope(envelope)
        || !GameplayContracts.VERSION.equals(envelope.contractVersion())
        || !Objects.equals(envelope.goalPlanDigest(), bindings.goalPlanDigest())
        || !Objects.equals(envelope.taskSkillDigests(), bindings.taskSkillDigests())
        || !Objects.equals(envelope.catalogDigest(), bindings.catalogDigest())
        || !Objects.equals(envelope.schemaDigest(), bindings.toolSchemaDigest())
        || !Objects.equals(envelope.knowledgeDigest(), bindings.knowledgeDigest())
        || !Objects.equals(envelope.policyDigest(), bindings.policyDigest())
        || !Objects.equals(envelope.verifierId(), bindings.verifierDigest())
        || !Objects.equals(envelope.buildId(), bindings.buildId())
        || !observation.isObject()
        || !Objects.equals(envelope.identities(), observation.path("identities"))
        || !Objects.equals(envelope.catalogDigest(), observation.path("catalogDigest").asText(null))
        || !Objects.equals(envelope.buildId(), observation.path("buildId").asText(null))) {
      throw failure(FailureCode.STALE_BINDING, "Execution envelope snapshot binding changed");
    }
    if (envelope.expectedObservationRevision() != bindings.expectedObservationRevision()
        || envelope.expectedTaskRevision() != bindings.expectedTaskRevision()
        || envelope.expectedObservationRevision() != observation.path("revision").asLong(-1)
        || envelope.expectedTaskRevision()
            != observation.path("taskState").path("revision").asLong(-1)) {
      throw failure(FailureCode.REVISION_MISMATCH, "Expected observation or task revision changed");
    }
  }

  private boolean validSnapshotBindings(SnapshotBindings bindings, boolean requireCapability) {
    return bindings != null
        && validDigest(bindings.decisionDigest())
        && validDigest(bindings.semanticActionDigest())
        && validDigest(bindings.catalogDigest())
        && (!requireCapability || validDigest(bindings.capabilityDigest()))
        && validDigest(bindings.goalPlanDigest())
        && bindings.taskSkillDigests() != null
        && !bindings.taskSkillDigests().isEmpty()
        && bindings.taskSkillDigests().stream().allMatch(this::validDigest)
        && validDigest(bindings.toolSchemaDigest())
        && validDigest(bindings.knowledgeDigest())
        && validDigest(bindings.policyDigest())
        && validId(bindings.verifierDigest())
        && validId(bindings.buildId())
        && validDigest(bindings.observationDigest())
        && bindings.expectedObservationRevision() >= 0
        && bindings.expectedTaskRevision() >= 0;
  }

  private boolean validEnvelope(ExecutionEnvelope envelope) {
    return envelope != null
        && envelope.provider() != null
        && validId(envelope.modelVersion())
        && validId(envelope.decisionId())
        && validId(envelope.requestId())
        && validId(envelope.idempotencyKey())
        && validDigest(envelope.goalPlanDigest())
        && envelope.taskSkillDigests() != null
        && !envelope.taskSkillDigests().isEmpty()
        && envelope.taskSkillDigests().stream().allMatch(this::validDigest)
        && validId(envelope.taskKey())
        && envelope.settings() != null
        && envelope.identities() != null
        && validDigest(envelope.catalogDigest())
        && validDigest(envelope.schemaDigest())
        && validDigest(envelope.policyDigest())
        && validDigest(envelope.knowledgeDigest())
        && validDigest(envelope.semanticActionDigest())
        && envelope.expectedTransition() != null
        && !envelope.expectedTransition().isBlank()
        && validId(envelope.verifierId())
        && validId(envelope.buildId())
        && envelope.expectedObservationRevision() >= 0
        && envelope.expectedTaskRevision() >= 0
        && validId(envelope.authorityId())
        && envelope.budgets() != null
        && envelope.deadline() != null
        && "RUN".equals(envelope.killSwitchScope());
  }

  private boolean validId(String value) {
    return value != null && value.matches(ID_PATTERN);
  }

  private boolean validDigest(String value) {
    return value != null && value.matches(DIGEST_PATTERN);
  }

  private AccountBuilderOrchestrationException invalid(String message) {
    return failure(FailureCode.INVALID_CONTRACT, message);
  }

  private AccountBuilderOrchestrationException failure(FailureCode code, String message) {
    return new AccountBuilderOrchestrationException(code, message);
  }
}

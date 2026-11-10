package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationRequest;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Sole SC-1431 request-construction boundary.
 *
 * <p>Every production caller builds its immutable {@link OrchestrationRequest} here, so the request
 * fingerprint is computed exactly once by the same function the validator re-uses ({@link
 * AccountBuilderOrchestrationValidator#immutableFingerprint}). No caller may compute a partial
 * fingerprint from a subset of the immutable fields; a request whose fingerprint does not match
 * this factory's calculation is rejected before any durable claim.
 */
@Component
public final class AccountBuilderOrchestrationRequestFactory {
  private static final String DIGEST_PATTERN = "sha256:[0-9a-f]{64}";

  private final AccountBuilderOrchestrationValidator validator;
  private final CanonicalGameplayJson canonicalJson;

  public AccountBuilderOrchestrationRequestFactory(
      AccountBuilderOrchestrationValidator validator, CanonicalGameplayJson canonicalJson) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
  }

  /**
   * Builds the immutable SC-1431 request for one bootstrap proposal and stamps the full immutable
   * request fingerprint into it. The returned request is exactly the request the validator will
   * accept during {@code validateAndFingerprint}.
   *
   * <p>{@code capabilityPreflight} must be the read-only capability probe obtained from the active
   * gateway before this call. The request binds only a server-issued capability digest from that
   * preflight; the catalog digest is never substituted, and a preflight without a capability
   * attestation fails closed before any durable claim.
   */
  public OrchestrationRequest fromBootstrap(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority,
      AccountBuilderOrchestrationModels.CapabilitySnapshot capabilityPreflight) {
    return withFingerprint(
        new OrchestrationRequest(
            "bootstrap-" + envelope.requestId(),
            envelope.requestId(),
            envelope.idempotencyKey(),
            null,
            Mode.BOOTSTRAP_RECORDING,
            "BOOTSTRAP",
            decision,
            envelope,
            bootstrapBindings(
                decision,
                envelope,
                observation,
                requireServerCapability(envelope, capabilityPreflight)),
            authority,
            observation,
            envelope.deadline()));
  }

  /**
   * Rebuilds the exact immutable bootstrap request for an existing durable aggregate by binding the
   * already persisted capability digest. It performs no capability access, so an exact replay
   * neither fetches a new snapshot nor replaces an existing capability binding; the caller compares
   * the returned full fingerprint against the stored one.
   */
  public OrchestrationRequest fromBootstrapReplay(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority,
      String storedCapabilityDigest) {
    if (storedCapabilityDigest == null || !storedCapabilityDigest.matches(DIGEST_PATTERN)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT, "Persisted capability digest is missing or malformed");
    }
    return withFingerprint(
        new OrchestrationRequest(
            "bootstrap-" + envelope.requestId(),
            envelope.requestId(),
            envelope.idempotencyKey(),
            null,
            Mode.BOOTSTRAP_RECORDING,
            "BOOTSTRAP",
            decision,
            envelope,
            bootstrapBindings(decision, envelope, observation, storedCapabilityDigest),
            authority,
            observation,
            envelope.deadline()));
  }

  /**
   * Returns a copy of the draft request carrying the immutable fingerprint computed over exactly
   * the fields the validator treats as immutable. The fingerprint excludes the fingerprint field
   * itself.
   */
  public OrchestrationRequest withFingerprint(OrchestrationRequest draft) {
    Objects.requireNonNull(draft, "draft");
    return new OrchestrationRequest(
        draft.orchestrationId(),
        draft.requestId(),
        draft.idempotencyKey(),
        validator.immutableFingerprint(draft),
        draft.mode(),
        draft.sourceType(),
        draft.decision(),
        draft.envelope(),
        draft.bindings(),
        draft.authority(),
        draft.observation(),
        draft.deadline(),
        draft.frozenBundle());
  }

  /**
   * Returns a copy of a prepared draft with the given capability digest bound into its bindings and
   * the full immutable fingerprint stamped. The caller must have validated a fresh server-issued
   * digest, or read the persisted digest for an exact replay.
   */
  public OrchestrationRequest bindCapability(OrchestrationRequest draft, String capabilityDigest) {
    Objects.requireNonNull(draft, "draft");
    if (capabilityDigest == null || !capabilityDigest.matches(DIGEST_PATTERN)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.INVALID_CONTRACT, "Capability digest is missing or malformed");
    }
    SnapshotBindings bindings = draft.bindings();
    SnapshotBindings bound =
        new SnapshotBindings(
            bindings.decisionDigest(),
            bindings.semanticActionDigest(),
            bindings.catalogDigest(),
            capabilityDigest,
            bindings.goalPlanDigest(),
            bindings.taskSkillDigests(),
            bindings.toolSchemaDigest(),
            bindings.knowledgeDigest(),
            bindings.policyDigest(),
            bindings.verifierDigest(),
            bindings.buildId(),
            bindings.expectedObservationRevision(),
            bindings.expectedTaskRevision(),
            bindings.observationDigest(),
            bindings.promptDigest(),
            bindings.providerSessionId(),
            bindings.providerTurnId());
    return withFingerprint(
        new OrchestrationRequest(
            draft.orchestrationId(),
            draft.requestId(),
            draft.idempotencyKey(),
            draft.requestFingerprint(),
            draft.mode(),
            draft.sourceType(),
            draft.decision(),
            draft.envelope(),
            bound,
            draft.authority(),
            draft.observation(),
            draft.deadline(),
            draft.frozenBundle()));
  }

  /**
   * Fail-closed validation of a live capability preflight. Only a server-issued capability digest
   * is returned; the catalog digest is never substituted, and a preflight that carries no
   * attestation can never produce a bindable request.
   */
  private String requireServerCapability(
      ExecutionEnvelope envelope, AccountBuilderOrchestrationModels.CapabilitySnapshot preflight) {
    if (preflight == null
        || preflight.capabilityDigest() == null
        || !preflight.capabilityDigest().matches(DIGEST_PATTERN)) {
      throw new AccountBuilderOrchestrationException(
          FailureCode.GATEWAY_UNAVAILABLE,
          "Capability preflight did not return a server-issued capability digest");
    }
    if (preflight.liveDeliveryReady()) {
      // A live attestation must agree with the exact proposal snapshot it is bound to.
      if (!Objects.equals(preflight.catalogDigest(), envelope.catalogDigest())
          || !Objects.equals(preflight.buildId(), envelope.buildId())) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.STALE_BINDING,
            "Capability preflight catalog or build identity does not match the proposal");
      }
      if (preflight.observationRevision() != envelope.expectedObservationRevision()
          || preflight.taskRevision() != envelope.expectedTaskRevision()) {
        throw new AccountBuilderOrchestrationException(
            FailureCode.REVISION_MISMATCH,
            "Capability preflight observation or task revision drifted from the proposal");
      }
    }
    return preflight.capabilityDigest();
  }

  private SnapshotBindings bootstrapBindings(
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      JsonNode observation,
      String capabilityDigest) {
    return new SnapshotBindings(
        canonicalJson.digest(decision),
        envelope.semanticActionDigest(),
        envelope.catalogDigest(),
        capabilityDigest,
        envelope.goalPlanDigest(),
        envelope.taskSkillDigests(),
        envelope.schemaDigest(),
        envelope.knowledgeDigest(),
        envelope.policyDigest(),
        envelope.verifierId(),
        envelope.buildId(),
        envelope.expectedObservationRevision(),
        envelope.expectedTaskRevision(),
        canonicalJson.digest(observation),
        null,
        null,
        null);
  }
}

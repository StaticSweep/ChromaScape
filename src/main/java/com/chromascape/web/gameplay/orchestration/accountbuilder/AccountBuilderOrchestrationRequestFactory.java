package com.chromascape.web.gameplay.orchestration.accountbuilder;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.HumanAuthority;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
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
   */
  public OrchestrationRequest fromBootstrap(
      JsonNode observation,
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      HumanAuthority authority) {
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
            bootstrapBindings(decision, envelope),
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
        draft.deadline());
  }

  private SnapshotBindings bootstrapBindings(
      ProviderDecision decision, ExecutionEnvelope envelope) {
    return new SnapshotBindings(
        canonicalJson.digest(decision),
        envelope.semanticActionDigest(),
        envelope.catalogDigest(),
        envelope.catalogDigest(),
        envelope.goalPlanDigest(),
        envelope.taskSkillDigests(),
        envelope.schemaDigest(),
        envelope.knowledgeDigest(),
        envelope.policyDigest(),
        envelope.verifierId(),
        envelope.buildId(),
        envelope.expectedObservationRevision(),
        envelope.expectedTaskRevision());
  }
}

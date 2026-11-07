package com.chromascape.web.gameplay.bootstrap;

import com.chromascape.web.gameplay.authority.HumanGameplayAuthorityService;
import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContracts.ExecutionEnvelope;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.SnapshotBindings;
import com.chromascape.web.gameplay.provider.runtime.ProviderDecisionHandoff;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Assembles an SC-1498 bootstrap proposal exclusively from a persisted SC-1430 {@code DECIDED}
 * turn.
 *
 * <p>Raw {@code providerOutput} is never accepted. The operator selects a provider session/turn;
 * this component loads the validated persisted decision, checks every digest/revision/capability
 * binding, derives the immutable request fingerprint (authority-independent preview form) and
 * returns the exact human approval scope plus {@code scopeDigest}.
 */
@Component
public final class BootstrapProposalAssembler {
  private final ProviderDecisionHandoff handoff;
  private final HumanGameplayAuthorityService authorityService;
  private final CanonicalGameplayJson canonicalJson;

  public BootstrapProposalAssembler(
      ProviderDecisionHandoff handoff,
      HumanGameplayAuthorityService authorityService,
      CanonicalGameplayJson canonicalJson) {
    this.handoff = handoff;
    this.authorityService = authorityService;
    this.canonicalJson = canonicalJson;
  }

  public AssembledPreview assemble(
      String providerSessionId,
      String providerTurnId,
      JsonNode observation,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings) {
    if (providerSessionId == null || providerSessionId.isBlank()) {
      throw new BootstrapProposalException(
          "PROPOSAL_INCOMPLETE", "A provider session id is required");
    }
    if (providerTurnId == null || providerTurnId.isBlank()) {
      throw new BootstrapProposalException("PROPOSAL_INCOMPLETE", "A provider turn id is required");
    }
    if (observation == null || envelope == null || bindings == null) {
      throw new BootstrapProposalException(
          "PROPOSAL_INCOMPLETE", "A complete proposal is required");
    }
    ProviderDecisionHandoff.PersistedDecision persisted =
        handoff
            .findValidatedDecision(providerSessionId, providerTurnId)
            .orElseThrow(
                () ->
                    new BootstrapProposalException(
                        "PERSISTED_DECISION_MISSING",
                        "Validated persisted provider decision was not found"));
    ProviderDecision decision = persisted.decision();
    if (!persisted.decisionDigest().equals(bindings.decisionDigest())
        || !persisted.semanticActionDigest().equals(bindings.semanticActionDigest())
        || !persisted.requestId().equals(envelope.requestId())) {
      throw new BootstrapProposalException(
          "STALE_BINDING", "Persisted provider decision binding changed");
    }
    validateDecisionEnvelopeObservation(decision, envelope, bindings, observation);
    Map<String, Object> exactScope = authorityService.exactScope(envelope);
    String scopeDigest = canonicalJson.digest(exactScope);
    String previewFingerprint = previewFingerprint(decision, envelope, bindings, observation);
    String orchestrationId = envelope.requestId();
    String recordingId = envelope.identities().path("recordingId").asText();
    if (recordingId == null || recordingId.isBlank()) {
      throw new BootstrapProposalException("PROPOSAL_INCOMPLETE", "A recording id is required");
    }
    return new AssembledPreview(
        decision,
        envelope,
        bindings,
        observation,
        persisted,
        exactScope,
        scopeDigest,
        previewFingerprint,
        orchestrationId,
        envelope.requestId(),
        recordingId);
  }

  private void validateDecisionEnvelopeObservation(
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings,
      JsonNode observation) {
    if (decision.provider() != envelope.provider()
        || !Objects.equals(decision.modelVersion(), envelope.modelVersion())
        || !Objects.equals(decision.decisionId(), envelope.decisionId())) {
      throw new BootstrapProposalException(
          "DECISION_IDENTITY_MISMATCH", "Persisted decision does not match the envelope");
    }
    if (!Objects.equals(bindings.semanticActionDigest(), envelope.semanticActionDigest())
        || !Objects.equals(
            bindings.semanticActionDigest(), canonicalJson.digest(decision.semanticAction()))) {
      throw new BootstrapProposalException(
          "ACTION_ENVELOPE_MISMATCH", "Semantic action digest does not match the envelope");
    }
    if (!"START_FISHING_TASK".equals(decision.semanticAction().path("kind").asText())
        || !"FISHING_TRAINING".equals(envelope.taskKey())) {
      throw new BootstrapProposalException(
          "TASK_INELIGIBLE", "Only bounded FISHING_TRAINING bootstrap runs are supported");
    }
    if (!Objects.equals(envelope.taskKey(), decision.semanticAction().path("taskKey").asText(null))
        || !Objects.equals(envelope.settings(), decision.semanticAction().path("settings"))
        || !Objects.equals(
            envelope.expectedTransition(),
            decision.semanticAction().path("expectedTransition").asText(null))
        || !Objects.equals(
            envelope.verifierId(), decision.semanticAction().path("verifierId").asText(null))) {
      throw new BootstrapProposalException(
          "ACTION_ENVELOPE_MISMATCH", "Proposal does not match its envelope");
    }
    if (envelope.expectedObservationRevision() != bindings.expectedObservationRevision()
        || envelope.expectedTaskRevision() != bindings.expectedTaskRevision()
        || envelope.expectedObservationRevision() != observation.path("revision").asLong(-1)
        || envelope.expectedTaskRevision()
            != observation.path("taskState").path("revision").asLong(-1)) {
      throw new BootstrapProposalException(
          "REVISION_MISMATCH", "Expected observation or task revision changed");
    }
    if (!Objects.equals(envelope.identities(), observation.path("identities"))
        || !Objects.equals(envelope.catalogDigest(), observation.path("catalogDigest").asText(null))
        || !Objects.equals(envelope.buildId(), observation.path("buildId").asText(null))) {
      throw new BootstrapProposalException("IDENTITY_MISMATCH", "Observation binding changed");
    }
    if (!Objects.equals(envelope.goalPlanDigest(), bindings.goalPlanDigest())
        || !Objects.equals(envelope.taskSkillDigests(), bindings.taskSkillDigests())
        || !Objects.equals(envelope.catalogDigest(), bindings.catalogDigest())
        || !Objects.equals(envelope.schemaDigest(), bindings.toolSchemaDigest())
        || !Objects.equals(envelope.knowledgeDigest(), bindings.knowledgeDigest())
        || !Objects.equals(envelope.policyDigest(), bindings.policyDigest())
        || !Objects.equals(envelope.verifierId(), bindings.verifierDigest())
        || !Objects.equals(envelope.buildId(), bindings.buildId())) {
      throw new BootstrapProposalException(
          "STALE_BINDING", "Execution envelope snapshot binding changed");
    }
    if (!Objects.equals(canonicalJson.digest(decision), bindings.decisionDigest())) {
      throw new BootstrapProposalException(
          "STALE_BINDING", "Decision digest does not match the persisted decision");
    }
    if (envelope.leaseGeneration() < 1 || envelope.fence() < 1) {
      throw new BootstrapProposalException(
          "FENCE_MISMATCH", "Live delivery requires positive lease generation and fence");
    }
    if (envelope.deadline() == null || envelope.budgets() == null) {
      throw new BootstrapProposalException(
          "PROPOSAL_INCOMPLETE", "Budgets and deadline are required");
    }
    if (!"RUN".equals(envelope.killSwitchScope())) {
      throw new BootstrapProposalException(
          "PROPOSAL_INCOMPLETE", "Kill-switch scope must be the exact run");
    }
  }

  private String previewFingerprint(
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings,
      JsonNode observation) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("contractVersion", "sc-1498/v1");
    values.put("decision", decision);
    values.put("envelopeWithoutAuthority", envelopeWithoutAuthority(envelope));
    values.put("bindings", bindings);
    values.put("observation", observation);
    values.put("deadline", envelope.deadline());
    return canonicalJson.digest(values);
  }

  private Map<String, Object> envelopeWithoutAuthority(ExecutionEnvelope envelope) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("contractVersion", envelope.contractVersion());
    values.put("provider", envelope.provider());
    values.put("modelVersion", envelope.modelVersion());
    values.put("decisionId", envelope.decisionId());
    values.put("requestId", envelope.requestId());
    values.put("idempotencyKey", envelope.idempotencyKey());
    values.put("goalPlanDigest", envelope.goalPlanDigest());
    values.put("taskSkillDigests", envelope.taskSkillDigests());
    values.put("taskKey", envelope.taskKey());
    values.put("settings", envelope.settings());
    values.put("identities", envelope.identities());
    values.put("catalogDigest", envelope.catalogDigest());
    values.put("schemaDigest", envelope.schemaDigest());
    values.put("policyDigest", envelope.policyDigest());
    values.put("knowledgeDigest", envelope.knowledgeDigest());
    values.put("semanticActionDigest", envelope.semanticActionDigest());
    values.put("expectedTransition", envelope.expectedTransition());
    values.put("verifierId", envelope.verifierId());
    values.put("buildId", envelope.buildId());
    values.put("expectedObservationRevision", envelope.expectedObservationRevision());
    values.put("expectedTaskRevision", envelope.expectedTaskRevision());
    values.put("leaseGeneration", envelope.leaseGeneration());
    values.put("fence", envelope.fence());
    values.put("budgets", envelope.budgets());
    values.put("deadline", envelope.deadline());
    values.put("killSwitchScope", envelope.killSwitchScope());
    return values;
  }

  public record AssembledPreview(
      ProviderDecision decision,
      ExecutionEnvelope envelope,
      SnapshotBindings bindings,
      JsonNode observation,
      ProviderDecisionHandoff.PersistedDecision persisted,
      Map<String, Object> exactScope,
      String scopeDigest,
      String previewFingerprint,
      String orchestrationId,
      String requestId,
      String recordingId) {}

  public static final class BootstrapProposalException extends IllegalArgumentException {
    private final String failureCode;

    public BootstrapProposalException(String failureCode, String message) {
      super(message);
      this.failureCode = failureCode;
    }

    public String failureCode() {
      return failureCode;
    }
  }
}

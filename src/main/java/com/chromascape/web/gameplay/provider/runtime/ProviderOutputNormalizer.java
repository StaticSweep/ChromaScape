package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts.Provider;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.springframework.stereotype.Component;

/** Strictly extracts one provider proposal and binds provenance from trusted runtime context. */
@Component
public final class ProviderOutputNormalizer {
  private final ObjectMapper objectMapper;
  private final GameplayContractValidator validator;
  private final CanonicalGameplayJson canonicalJson;

  public ProviderOutputNormalizer(
      ObjectMapper objectMapper,
      GameplayContractValidator validator,
      CanonicalGameplayJson canonicalJson) {
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.canonicalJson = canonicalJson;
  }

  public NormalizedDecision normalize(
      String rawOutput, Provider provider, String modelId, TurnRequest request) {
    try {
      JsonNode proposal = extractOne(rawOutput);
      validator.validate("providerProposal", proposal);
      JsonNode action = proposal.path("semanticAction");
      long matches =
          StreamSupport.stream(request.eligibleActions().spliterator(), false)
              .filter(
                  candidate -> canonicalJson.digest(candidate).equals(canonicalJson.digest(action)))
              .count();
      if (matches != 1) {
        throw invalid(
            FailureCode.UNSUPPORTED_ACTION, "Provider action was not exactly one eligible action");
      }
      String decisionId = proposal.path("decisionId").asText();
      if (!decisionId.equals(action.path("decisionId").asText())) {
        throw invalid(FailureCode.INVALID_OUTPUT, "Decision identities did not match");
      }
      Set<String> pinned = pinnedValues(request);
      validateEvidence(proposal.path("evidenceRefs"), pinned);
      validateEvidence(action.path("evidenceRefs"), pinned);
      ProviderDecision decision =
          new ProviderDecision(
              proposal.path("contractVersion").asText(),
              provider,
              modelId,
              decisionId,
              proposal.path("policyConfidence").asDouble(),
              action.deepCopy(),
              objectMapper.convertValue(
                  proposal.path("evidenceRefs"),
                  objectMapper
                      .getTypeFactory()
                      .constructCollectionType(java.util.List.class, String.class)));
      TurnState state =
          "ABSTAIN".equals(action.path("kind").asText()) ? TurnState.ABSTAINED : TurnState.DECIDED;
      return new NormalizedDecision(state, decision);
    } catch (ProviderRuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid(
          FailureCode.INVALID_OUTPUT, "Provider output was not one valid structured result");
    }
  }

  /**
   * Normalises an SC-1429/v2 decision.
   *
   * <p>This is deliberately narrower than {@code GameplayV2ProviderAdapter}: at turn time the
   * runtime has no catalog, observation or planning input, so the full adapter cannot run. What it
   * can prove here is that the provider answered the v2 shape, that it bound the eligibility digest
   * it was shown, that its GoalPlan is internally consistent, and that its evidence references only
   * the pinned input. The adapter's catalog and eligibility replay run later, at draft assembly,
   * where those inputs exist.
   */
  public NormalizedV2Decision normalizeV2(
      String rawOutput, Provider provider, String modelId, TurnRequest request) {
    try {
      JsonNode decision = extractOne(rawOutput);
      Set<com.networknt.schema.ValidationMessage> violations =
          v2Schema().validate(decision);
      if (!violations.isEmpty()) {
        throw invalid(
            FailureCode.INVALID_OUTPUT,
            "Provider output did not conform to the sc-1429/v2 decision schema");
      }
      if (!provider.name().equals(decision.path("provider").asText())
          || !modelId.equals(decision.path("modelVersion").asText())) {
        throw invalid(FailureCode.INVALID_OUTPUT, "Decision provider or model did not match");
      }
      String decisionId = decision.path("decisionId").asText();
      if (!request.eligibilityDigest().equals(decision.path("eligibleInputDigest").asText())) {
        throw invalid(
            FailureCode.INVALID_OUTPUT,
            "Decision did not bind the eligibility digest it was shown");
      }
      JsonNode goalPlan = decision.path("goalPlan");
      if (!decisionId.equals(goalPlan.path("sourceDecisionId").asText())) {
        throw invalid(FailureCode.INVALID_OUTPUT, "GoalPlan source decision did not match");
      }
      JsonNode copy = goalPlan.deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("goalPlanDigest");
      if (!canonicalJson.digest(copy).equals(goalPlan.path("goalPlanDigest").asText())) {
        throw invalid(FailureCode.INVALID_OUTPUT, "GoalPlan digest did not match its bytes");
      }
      JsonNode head = goalPlan.path("nodes").path(0);
      JsonNode semanticAction = objectMapper.createObjectNode();
      ((com.fasterxml.jackson.databind.node.ObjectNode) semanticAction)
          .put("kind", "START_FISHING_TASK");
      ((com.fasterxml.jackson.databind.node.ObjectNode) semanticAction)
          .set("taskKey", head.path("taskKey"));
      ((com.fasterxml.jackson.databind.node.ObjectNode) semanticAction)
          .set("settings", head.path("settings"));
      ((com.fasterxml.jackson.databind.node.ObjectNode) semanticAction)
          .set("expectedTransition", head.path("expectedTransition"));
      ((com.fasterxml.jackson.databind.node.ObjectNode) semanticAction)
          .set("verifierId", head.path("verifierId"));
      ((com.fasterxml.jackson.databind.node.ObjectNode) semanticAction)
          .put("decisionId", decisionId);
      Set<String> pinned = pinnedValues(request);
      validateEvidence(decision.path("evidenceRefs"), pinned);
      return new NormalizedV2Decision(
          TurnState.DECIDED,
          decision.deepCopy(),
          canonicalJson.digest(decision),
          canonicalJson.digest(semanticAction));
    } catch (ProviderRuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid(
          FailureCode.INVALID_OUTPUT, "Provider output was not one valid structured result");
    }
  }

  private com.networknt.schema.JsonSchema v2Schema() {
    return com.networknt.schema.JsonSchemaFactory.getInstance(
            com.networknt.schema.SpecVersion.VersionFlag.V202012)
        .getSchema(ProviderProposalSchemaV2.JSON);
  }

  private JsonNode extractOne(String rawOutput) throws JsonProcessingException {
    if (rawOutput == null || rawOutput.isBlank()) {
      throw invalid(FailureCode.INVALID_OUTPUT, "Provider output was empty");
    }
    JsonNode root = objectMapper.readTree(rawOutput.trim());
    if (root.isArray()) {
      List<JsonNode> terminalResults = new java.util.ArrayList<>();
      for (JsonNode event : root) {
        if (event.has("structured_output")
            || ("result".equals(event.path("type").asText()) && event.has("result"))) {
          terminalResults.add(event);
        }
      }
      if (terminalResults.size() == 1) {
        root = terminalResults.get(0);
      } else if (root.size() == 1) {
        root = root.get(0);
      } else {
        throw invalid(FailureCode.MULTIPLE_DECISIONS, "Provider returned multiple decisions");
      }
    }
    if (root.has("structured_output")) {
      return root.path("structured_output");
    }
    if (root.has("result")) {
      JsonNode result = root.path("result");
      if (result.isTextual()) {
        return objectMapper.readTree(result.asText());
      }
      return result;
    }
    return root;
  }

  private Set<String> pinnedValues(TurnRequest request) {
    Set<String> values = new HashSet<>();
    collectText(request.observation(), values);
    collectText(request.eligibleActions(), values);
    values.add(request.observationDigest());
    values.add(request.eligibilityDigest());
    values.add(request.sourceId());
    String observationId = request.observation().path("observationId").asText();
    if (!observationId.isBlank()) {
      values.add("observation:" + observationId);
    }
    return values;
  }

  private void validateEvidence(JsonNode evidenceRefs, Set<String> pinned) {
    for (JsonNode evidence : evidenceRefs) {
      if (!pinned.contains(evidence.asText())) {
        throw invalid(
            FailureCode.INVALID_EVIDENCE_REFERENCE,
            "Provider evidence did not reference the pinned input");
      }
    }
  }

  private void collectText(JsonNode node, Set<String> values) {
    if (node == null) {
      return;
    }
    if (node.isTextual()) {
      values.add(node.asText());
      return;
    }
    Iterator<JsonNode> children = node.elements();
    while (children.hasNext()) {
      collectText(children.next(), values);
    }
  }

  private ProviderRuntimeException invalid(FailureCode code, String message) {
    return new ProviderRuntimeException(code.name(), message);
  }

  public record NormalizedDecision(TurnState state, ProviderDecision decision) {}

  /** A validated v2 decision: the exact provider bytes plus the digests derived from them. */
  public record NormalizedV2Decision(
      TurnState state,
      com.fasterxml.jackson.databind.JsonNode decision,
      String decisionDigest,
      String semanticActionDigest) {}
}

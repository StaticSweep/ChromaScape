package com.chromascape.web.gameplay.provider;

import com.chromascape.web.gameplay.contract.GameplayContractValidator;
import com.chromascape.web.gameplay.contract.GameplayContracts;
import com.chromascape.web.gameplay.contract.GameplayContracts.ProviderDecision;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Shared Claude/Codex normalization. Provider-specific free-form actions never cross this class.
 */
abstract class ClosedGameplayProviderAdapter implements GameplayProviderAdapter {
  private final GameplayContractValidator validator;

  ClosedGameplayProviderAdapter(GameplayContractValidator validator) {
    this.validator = validator;
  }

  @Override
  public final ProviderDecision adapt(String modelVersion, JsonNode providerOutput) {
    validateModelVersion(modelVersion);
    validator.validate("providerProposal", providerOutput);
    String decisionId = requiredText(providerOutput, "decisionId");
    JsonNode semanticAction = providerOutput.path("semanticAction");
    if (!decisionId.equals(semanticAction.path("decisionId").asText())) {
      throw new IllegalArgumentException("decisionId must match semanticAction.decisionId");
    }
    List<String> evidence =
        providerOutput.has("evidenceRefs")
            ? java.util.stream.StreamSupport.stream(
                    providerOutput.path("evidenceRefs").spliterator(), false)
                .map(JsonNode::asText)
                .toList()
            : List.of();
    return new ProviderDecision(
        GameplayContracts.VERSION,
        provider(),
        modelVersion,
        decisionId,
        providerOutput.path("policyConfidence").asDouble(),
        semanticAction.deepCopy(),
        evidence);
  }

  private String requiredText(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value;
  }

  private static void validateModelVersion(String modelVersion) {
    if (modelVersion == null || !modelVersion.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")) {
      throw new IllegalArgumentException("modelVersion must be a schema token");
    }
  }
}

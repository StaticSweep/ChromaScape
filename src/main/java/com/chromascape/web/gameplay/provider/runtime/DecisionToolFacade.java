package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Closed read-only view of data already pinned into a decision turn. */
@Component
public final class DecisionToolFacade {
  public static final String OBSERVATION = "fetch_pinned_observation";
  public static final String ELIGIBLE_ACTIONS = "list_eligible_actions";
  public static final String SESSION_STATUS = "read_provider_session_status";
  private static final Set<String> ALLOWED = Set.of(OBSERVATION, ELIGIBLE_ACTIONS, SESSION_STATUS);

  private final CanonicalGameplayJson canonicalJson;

  public DecisionToolFacade(CanonicalGameplayJson canonicalJson) {
    this.canonicalJson = canonicalJson;
  }

  public JsonNode invoke(String toolName, Map<String, String> arguments, TurnRequest request) {
    if (!ALLOWED.contains(toolName)) {
      throw new ProviderRuntimeException(
          FailureCode.TOOL_NOT_ALLOWED.name(),
          "Provider requested a tool outside the closed facade");
    }
    if (arguments == null
        || !arguments.keySet().equals(Set.of("digest"))
        || arguments.get("digest") == null) {
      throw new ProviderRuntimeException(
          FailureCode.TOOL_ARGUMENT_MISMATCH.name(), "Pinned tool arguments did not match");
    }
    return switch (toolName) {
      case OBSERVATION -> requireDigest(
          arguments.get("digest"), request.observationDigest(), request.observation());
      case ELIGIBLE_ACTIONS -> requireDigest(
          arguments.get("digest"), request.eligibilityDigest(), request.eligibleActions());
      case SESSION_STATUS -> throw new ProviderRuntimeException(
          FailureCode.TOOL_ARGUMENT_MISMATCH.name(),
          "Session status is supplied by the runtime, not the turn payload");
      default -> throw new IllegalStateException("Exhaustive tool gate failed");
    };
  }

  private JsonNode requireDigest(String supplied, String expected, JsonNode value) {
    if (!expected.equals(supplied) || !expected.equals(canonicalJson.digest(value))) {
      throw new ProviderRuntimeException(
          FailureCode.TOOL_ARGUMENT_MISMATCH.name(), "Pinned tool digest did not match");
    }
    return value.deepCopy();
  }
}

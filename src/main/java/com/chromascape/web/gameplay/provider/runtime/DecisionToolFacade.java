package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.contract.CanonicalGameplayJson;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.FailureCode;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionView;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Closed read-only view of data already pinned into a decision turn. */
@Component
public final class DecisionToolFacade {
  public static final String OBSERVATION = "fetch_pinned_observation";
  public static final String ELIGIBLE_ACTIONS = "list_eligible_actions";
  public static final String KNOWLEDGE = "read_pinned_knowledge";
  public static final String GOAL_PLAN = "read_pinned_goal_plan";
  public static final String TASK_SKILL = "read_pinned_task_skill";
  public static final String SESSION_STATUS = "read_provider_session_status";
  private static final Set<String> ALLOWED =
      Set.of(OBSERVATION, ELIGIBLE_ACTIONS, KNOWLEDGE, GOAL_PLAN, TASK_SKILL, SESSION_STATUS);

  private final CanonicalGameplayJson canonicalJson;

  public DecisionToolFacade(CanonicalGameplayJson canonicalJson) {
    this.canonicalJson = canonicalJson;
  }

  public JsonNode invoke(String toolName, Map<String, String> arguments, TurnRequest request) {
    return invoke(toolName, arguments, null, request, null);
  }

  public JsonNode invoke(
      String toolName,
      Map<String, String> arguments,
      SessionRequest session,
      TurnRequest request,
      SessionView status) {
    if (!ALLOWED.contains(toolName)) {
      throw new ProviderRuntimeException(
          FailureCode.TOOL_NOT_ALLOWED.name(),
          "Provider requested a tool outside the closed facade");
    }
    return switch (toolName) {
      case OBSERVATION -> requireDigest(
          digestArgument(arguments), request.observationDigest(), request.observation());
      case ELIGIBLE_ACTIONS -> requireDigest(
          digestArgument(arguments), request.eligibilityDigest(), request.eligibleActions());
      case KNOWLEDGE -> pinnedReference(
          digestArgument(arguments), requireSession(session).knowledgeDigest());
      case GOAL_PLAN -> pinnedReference(
          digestArgument(arguments), requireSession(session).goalPlanDigest());
      case TASK_SKILL -> {
        String supplied = digestArgument(arguments);
        if (!requireSession(session).taskSkillDigests().contains(supplied)) {
          throw mismatch();
        }
        yield pinnedReference(supplied, supplied);
      }
      case SESSION_STATUS -> sessionStatus(arguments, status);
      default -> throw new IllegalStateException("Exhaustive tool gate failed");
    };
  }

  private String digestArgument(Map<String, String> arguments) {
    if (arguments == null
        || !arguments.keySet().equals(Set.of("digest"))
        || arguments.get("digest") == null) {
      throw mismatch();
    }
    return arguments.get("digest");
  }

  private JsonNode pinnedReference(String supplied, String expected) {
    if (!expected.equals(supplied)) {
      throw mismatch();
    }
    return JsonNodeFactory.instance.objectNode().put("digest", expected);
  }

  private JsonNode sessionStatus(Map<String, String> arguments, SessionView status) {
    if (status == null
        || arguments == null
        || !arguments.keySet().equals(Set.of("sessionId"))
        || !status.sessionId().equals(arguments.get("sessionId"))) {
      throw mismatch();
    }
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("contractVersion", status.contractVersion());
    value.put("sessionId", status.sessionId());
    value.put("state", status.state().name());
    value.put("providerCalls", status.usage().providerCalls());
    value.put("toolCalls", status.usage().toolCalls());
    value.put("turns", status.usage().turns());
    return value;
  }

  private SessionRequest requireSession(SessionRequest session) {
    if (session == null) {
      throw mismatch();
    }
    return session;
  }

  private ProviderRuntimeException mismatch() {
    return new ProviderRuntimeException(
        FailureCode.TOOL_ARGUMENT_MISMATCH.name(), "Pinned tool arguments did not match");
  }

  private JsonNode requireDigest(String supplied, String expected, JsonNode value) {
    if (!expected.equals(supplied) || !expected.equals(canonicalJson.digest(value))) {
      throw mismatch();
    }
    return value.deepCopy();
  }
}

package com.chromascape.web.gameplay.provider.runtime;

import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.SessionRequest;
import com.chromascape.web.gameplay.provider.runtime.ProviderRuntimeModels.TurnRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds the versioned, closed-action, decision-only provider prompt. */
@Component
public final class ProviderPromptBuilder {
  private final ObjectMapper objectMapper;

  public ProviderPromptBuilder(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String build(SessionRequest session, TurnRequest turn) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("runtimeContractVersion", ProviderRuntimeModels.VERSION);
    input.put("gameplayContractVersion", "sc-1429/v1");
    input.put("sessionId", session.sessionId());
    input.put("turnId", turn.turnId());
    input.put("requestId", turn.requestId());
    input.put("observationDigest", turn.observationDigest());
    input.put("observation", turn.observation());
    input.put("eligibilityDigest", turn.eligibilityDigest());
    input.put("eligibleActions", turn.eligibleActions());
    input.put("sourceId", turn.sourceId());
    input.put("promptDigest", session.promptDigest());
    input.put("toolsetDigest", session.toolsetDigest());
    input.put("knowledgeDigest", session.knowledgeDigest());
    input.put("goalPlanDigest", session.goalPlanDigest());
    input.put("taskSkillDigests", session.taskSkillDigests());
    try {
      return "You are a decide-only gameplay policy. Return exactly one JSON object conforming "
          + "to sc-1429/v1 providerProposal. Select semanticAction exactly from eligibleActions "
          + "or select its ABSTAIN action, and set the proposal's decisionId to exactly the "
          + "selected semanticAction's decisionId. An adapter may expose only the closed read-only "
          + "provider tool gateway for these pinned inputs. Never use shell, browser, filesystem, "
          + "generic MCP, or mutating tools. Do not execute, mutate state, claim authority, repair "
          + "inputs, or add prose. Evidence must reference only the pinned input.\nINPUT:\n"
          + objectMapper.writeValueAsString(input);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to build provider decision prompt", exception);
    }
  }
}

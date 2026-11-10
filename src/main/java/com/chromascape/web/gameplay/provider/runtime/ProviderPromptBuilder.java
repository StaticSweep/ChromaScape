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
    input.put(
        "gameplayContractVersion",
        session.v2() ? "sc-1429/v2" : "sc-1429/v1");
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
      if (session.v2()) {
        // The v2 contract requires the provider's GoalPlan, the prompt identity, the confidence
        // triple and the eligibility digest it was shown. The schema is embedded so the shape is
        // unambiguous and the model cannot answer with a v1 proposal.
        input.put("proposalSchema", ProviderProposalSchemaV2.JSON);
        return "You are a decide-only gameplay policy. Return exactly one JSON object conforming "
            + "to the embedded sc-1429/v2 providerDecision proposalSchema. Set "
            + "eligibleInputDigest to the pinned eligibilityDigest, set goalPlan.sourceDecisionId "
            + "to your decisionId, and select the goal plan's tasks only from eligibleActions. "
            + "Every goal-plan node must carry task-skill references whose artifactDigest values "
            + "equal the pinned taskSkillDigests, and its verifierId, expectedTransition, "
            + "preconditions and budgets must be honour-able by the executor. An adapter may "
            + "expose only the closed read-only provider tool gateway for these pinned inputs. "
            + "Never use shell, browser, filesystem, generic MCP, or mutating tools. Do not "
            + "execute, mutate state, claim authority, repair inputs, or add prose. Evidence must "
            + "reference only the pinned input.\nINPUT:\n"
            + objectMapper.writeValueAsString(input);
      }
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
